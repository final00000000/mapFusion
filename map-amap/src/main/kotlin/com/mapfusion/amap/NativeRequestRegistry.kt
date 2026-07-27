package com.mapfusion.amap

import com.mapfusion.api.async.AsyncRequest
import com.mapfusion.api.model.MapResult
import java.util.IdentityHashMap

/** 跟踪异步原生请求，保证完成或能力销毁时只释放一次。 */
internal class NativeRequestRegistry<T : Any>(
    private val releaseNative: (T) -> Unit,
) {
    private data class Entry(
        val request: AsyncRequest<*>?,
    )

    private val lock = Any()
    private val active = IdentityHashMap<T, Entry>()

    @Volatile
    var isDestroyed: Boolean = false
        private set

    fun register(request: T): Boolean = synchronized(lock) {
        if (isDestroyed || active.containsKey(request)) false
        else {
            active[request] = Entry(null)
            true
        }
    }

    /** 注册一个由 [AsyncRequest] 管理终态的原生请求。 */
    fun register(request: T, asyncRequest: AsyncRequest<*>): Boolean = synchronized(lock) {
        if (isDestroyed || asyncRequest.isDone || active.containsKey(request)) false
        else {
            active[request] = Entry(asyncRequest)
            true
        }
    }

    /** 返回 false 表示请求已被 destroy() 统一取消，调用方不得再触达业务回调。 */
    fun complete(request: T): Boolean {
        val entry = synchronized(lock) { active.remove(request) } ?: return false
        if (entry.request == null) runCatching { releaseNative(request) }
        else entry.request.dispose()
        return true
    }

    /** 供 AsyncRequest 的 terminalAction 使用：从活动表注销并解绑原生 listener。 */
    fun release(request: T): Boolean {
        val owned = synchronized(lock) { active.remove(request) } != null
        if (owned) runCatching { releaseNative(request) }
        return owned
    }

    /** 在注册表锁内启动原生请求，避免超时/销毁与 listener 挂接之间出现启动后泄漏。 */
    fun withRegistered(request: T, action: () -> Unit): Boolean = synchronized(lock) {
        if (isDestroyed || !active.containsKey(request)) false
        else {
            action()
            true
        }
    }

    /** 将原生结果交给绑定句柄；终态动作负责从注册表移除并释放 listener。 */
    @Suppress("UNCHECKED_CAST")
    fun complete(request: T, result: MapResult<*>): Boolean {
        val entry = synchronized(lock) { active[request] } ?: return false
        val async = entry.request ?: return complete(request)
        return (async as AsyncRequest<Any?>).complete(result as MapResult<Any?>)
    }

    fun destroy() {
        val requests = synchronized(lock) {
            if (isDestroyed) return
            isDestroyed = true
            active.toList()
        }
        requests.forEach { (request, entry) ->
            if (entry.request != null) {
                // AsyncRequest 的 terminalAction 会解绑原生 listener。
                runCatching { entry.request.dispose() }
            } else {
                release(request)
            }
        }
        synchronized(lock) { active.clear() }
    }
}
