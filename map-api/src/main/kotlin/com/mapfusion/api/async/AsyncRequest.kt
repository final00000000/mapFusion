package com.mapfusion.api.async

import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.RequestHandle
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * Provider 侧的单次请求控制器。
 *
 * 业务接口只需将实例作为 [RequestHandle] 返回；Provider 用 [success]、[failure] 或
 * [complete] 接收原生 SDK 结果。成功、失败、取消、超时和 [dispose] 通过同一个原子
 * 状态竞争，只有胜出的终态会释放资源并派发一次回调。
 */
class AsyncRequest<T> internal constructor(
    private val callback: MapCallback<T>,
    private val callbackExecutor: Executor,
    private val timeoutMillis: Long,
    private val timeoutScheduler: TimeoutScheduler,
    terminalAction: Runnable,
) : RequestHandle {
    private val state = AtomicReference(State.ACTIVE)
    private val timeoutHandle = AtomicReference<TimeoutHandle?>(null)
    private val terminalAction = AtomicReference<Runnable?>(terminalAction)

    override val isDone: Boolean
        get() = state.get() != State.ACTIVE

    override val isCancelled: Boolean
        get() = state.get() == State.CANCELLED

    /** 以成功结果结束请求；请求已经进入终态时返回 false。 */
    fun success(value: T): Boolean = complete(MapResult.Success(value))

    /** 以统一错误结束请求；请求已经进入终态时返回 false。 */
    fun failure(error: MapError): Boolean = complete(MapResult.Failure(error))

    /** 以给定结果结束请求；迟到的原生回调会返回 false 且不会再次派发。 */
    fun complete(result: MapResult<T>): Boolean = finish(State.COMPLETED, result)

    /**
     * 静默结束请求并释放原生资源，不派发 CANCELLED 回调。
     * 适用于 Provider 初始化回滚或不再允许通知宿主的内部清理场景。
     */
    fun dispose(): Boolean = finish(State.DISPOSED, null)

    override fun cancel(): Boolean = finish(
        terminalState = State.CANCELLED,
        result = MapResult.Failure(
            MapError(ErrorType.CANCELLED, "请求已取消"),
        ),
    )

    internal fun startTimeout() {
        val scheduled = timeoutScheduler.schedule(
            timeoutMillis,
            Runnable {
                finish(
                    terminalState = State.TIMED_OUT,
                    result = MapResult.Failure(
                        MapError(ErrorType.TIMEOUT, "请求在 ${timeoutMillis}ms 内未完成"),
                    ),
                )
            },
        )

        check(timeoutHandle.compareAndSet(null, scheduled)) { "请求只能启动一次超时计时" }

        // 调度器可能立即执行任务，或原生同步回调可能先于句柄挂载完成。
        if (isDone && timeoutHandle.compareAndSet(scheduled, null)) {
            scheduled.cancel()
        }
    }

    private fun finish(
        terminalState: State,
        result: MapResult<T>?,
    ): Boolean {
        if (!state.compareAndSet(State.ACTIVE, terminalState)) return false

        timeoutHandle.getAndSet(null)?.cancel()
        try {
            terminalAction.getAndSet(null)?.run()
        } finally {
            if (result != null) dispatch(result)
        }
        return true
    }

    private fun dispatch(result: MapResult<T>) {
        callbackExecutor.execute {
            callback.onResult(result)
        }
    }

    private enum class State {
        ACTIVE,
        COMPLETED,
        CANCELLED,
        TIMED_OUT,
        DISPOSED,
    }
}

/**
 * 异步请求运行环境。生产代码通常复用 [DEFAULT]；第三方 Provider 可注入测试调度器和
 * 默认回调执行器，在不依赖 Android Looper 或真实时间的情况下验证请求契约。
 */
class AsyncRuntime @JvmOverloads constructor(
    private val defaultCallbackExecutor: Executor = AndroidMainThreadExecutor,
    private val timeoutScheduler: TimeoutScheduler = SharedTimeoutScheduler,
) {
    /**
     * 创建并立即启动超时计时。终态确定后先执行 [terminalAction]，再向回调执行器派发结果。
     * [terminalAction] 在赢得终态的线程执行，必须快速、线程安全且不阻塞。
     */
    @JvmOverloads
    fun <T> createRequest(
        callback: MapCallback<T>,
        options: AsyncCallOptions = AsyncCallOptions(),
        terminalAction: Runnable = Runnable {},
    ): AsyncRequest<T> {
        val request = AsyncRequest(
            callback = callback,
            callbackExecutor = options.callbackExecutor ?: defaultCallbackExecutor,
            timeoutMillis = options.timeoutMillis,
            timeoutScheduler = timeoutScheduler,
            terminalAction = terminalAction,
        )
        try {
            request.startTimeout()
        } catch (error: Throwable) {
            request.dispose()
            throw error
        }
        return request
    }

    companion object {
        /** Android 生产环境默认运行时：主线程回调、共享后台超时调度。 */
        @JvmField
        val DEFAULT: AsyncRuntime = AsyncRuntime()
    }
}
