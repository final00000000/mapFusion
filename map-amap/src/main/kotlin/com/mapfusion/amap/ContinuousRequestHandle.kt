package com.mapfusion.amap

import com.mapfusion.api.async.AndroidMainThreadExecutor
import com.mapfusion.api.async.AsyncRequest
import com.mapfusion.api.async.AsyncRuntime
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.RequestHandle
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 首次结果受超时控制、之后可持续派发的订阅句柄。
 *
 * AsyncRequest 只负责“等待首次结果”这一段；首次成功会静默结束计时请求，但本句柄仍保持
 * ACTIVE。取消、停止、销毁和首次超时都通过同一状态机竞争，原生释放动作最多执行一次。
 */
internal class ContinuousRequestHandle<T>(
    private val callback: MapCallback<T>,
    options: AsyncCallOptions,
    runtime: AsyncRuntime = AsyncRuntime.DEFAULT,
    private val releaseAction: () -> Unit,
) : RequestHandle {

    private val state = AtomicReference(State.WAITING_FIRST)
    private val released = AtomicBoolean(false)
    private val suppressTimerRelease = AtomicBoolean(false)
    private val callbackExecutor: Executor = options.callbackExecutor ?: AndroidMainThreadExecutor
    private val firstRequest: AsyncRequest<T>

    init {
        firstRequest = runtime.createRequest(
            callback = MapCallback { result ->
                if (result is MapResult.Failure) finishTimeout(result)
            },
            options = options,
            terminalAction = Runnable {
                if (!suppressTimerRelease.get()) releaseOnce()
            },
        )
    }

    override val isDone: Boolean
        get() {
            val current = state.get()
            return current.isTerminal ||
                (current == State.WAITING_FIRST && firstRequest.isDone)
        }

    override val isCancelled: Boolean
        get() = state.get() == State.CANCELLED

    /** 派发一次原生结果。首个结果结束超时计时，之后成功或失败都可继续投递。 */
    fun emit(result: MapResult<T>): Boolean = emitResult(result)

    /** 初始化阶段失败，始终结束订阅。 */
    fun fail(error: MapError): Boolean = finishFailure(MapResult.Failure(error))

    /** Provider destroy 使用：静默释放，不向宿主派发取消结果。 */
    fun dispose(): Boolean {
        while (true) {
            val current = state.get()
            if (current.isTerminal) return false
            if (state.compareAndSet(current, State.DISPOSED)) {
                firstRequest.dispose()
                releaseOnce()
                return true
            }
        }
    }

    override fun cancel(): Boolean = cancelWithMessage("请求已取消")

    private fun emitResult(result: MapResult<T>): Boolean {
        while (true) {
            when (val current = state.get()) {
                State.WAITING_FIRST -> {
                    // 超时终态已经确定但尚未投递到 executor 时，原生迟到结果必须丢弃。
                    if (firstRequest.isDone) return false
                    if (!state.compareAndSet(current, State.ACTIVE)) continue
                    suppressTimerRelease.set(true)
                    if (!firstRequest.dispose()) {
                        releaseOnce()
                        return false
                    }
                    dispatch(result)
                    return true
                }
                State.ACTIVE -> {
                    dispatch(result)
                    return true
                }
                else -> return false
            }
        }
    }

    private fun finishFailure(result: MapResult.Failure): Boolean {
        while (true) {
            val current = state.get()
            if (current != State.WAITING_FIRST) return false
            if (firstRequest.isDone) return false
            if (!state.compareAndSet(current, State.COMPLETED)) continue
            // 若超时恰好同时获胜，自定义状态已先进入 COMPLETED，因此保留原生失败结果。
            firstRequest.dispose()
            releaseOnce()
            dispatch(result)
            return true
        }
    }

    private fun finishTimeout(result: MapResult.Failure) {
        while (true) {
            val current = state.get()
            if (current != State.WAITING_FIRST && current != State.ACTIVE) return
            if (!state.compareAndSet(current, State.COMPLETED)) continue
            releaseOnce()
            // 当前方法已由首次 AsyncRequest 投递到配置的 callbackExecutor。
            callback.onResult(result)
            return
        }
    }

    private fun cancelWithMessage(message: String): Boolean {
        while (true) {
            val current = state.get()
            if (current.isTerminal) return false
            if (current == State.WAITING_FIRST && firstRequest.isDone) return false
            if (!state.compareAndSet(current, State.CANCELLED)) continue
            firstRequest.dispose()
            releaseOnce()
            dispatch(MapResult.Failure(MapError(ErrorType.CANCELLED, message)))
            return true
        }
    }

    private fun dispatch(result: MapResult<T>) {
        runCatching { callbackExecutor.execute { callback.onResult(result) } }
    }

    private fun releaseOnce() {
        if (released.compareAndSet(false, true)) runCatching(releaseAction)
    }

    private enum class State(val isTerminal: Boolean) {
        WAITING_FIRST(false),
        ACTIVE(false),
        COMPLETED(true),
        CANCELLED(true),
        DISPOSED(true),
    }
}
