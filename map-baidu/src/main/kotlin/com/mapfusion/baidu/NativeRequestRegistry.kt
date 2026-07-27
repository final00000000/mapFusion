package com.mapfusion.baidu

import java.util.IdentityHashMap

/**
 * 跟踪异步原生请求，保证完成、取消、超时或能力销毁时只释放一次。
 *
 * [bind] 将统一请求的静默释放动作绑定到原生对象。能力销毁时先把所有条目从注册表
 * 摘除，再静默结束统一请求并释放原生对象，因此请求的 terminalAction 即使重入
 * [complete] 也不会二次销毁。
 */
internal class NativeRequestRegistry<T : Any>(
    private val releaseNative: (T) -> Unit,
) {
    private val lock = Any()
    private val active = IdentityHashMap<T, (() -> Unit)?>()

    @Volatile
    var isDestroyed: Boolean = false
        private set

    fun register(request: T): Boolean = synchronized(lock) {
        if (isDestroyed || active.containsKey(request)) {
            false
        } else {
            active[request] = null
            true
        }
    }

    /**
     * 绑定能力销毁时需要调用的统一请求 disposer。返回 false 表示条目已经终结，调用方
     * 应立即静默 dispose 自己刚创建的请求。
     */
    fun bind(request: T, disposeRequest: () -> Unit): Boolean = synchronized(lock) {
        if (!active.containsKey(request)) {
            false
        } else {
            active[request] = disposeRequest
            true
        }
    }

    /** 返回 false 表示请求已被 destroy() 统一取消，调用方不得再触达业务回调。 */
    fun complete(request: T): Boolean {
        val owned = synchronized(lock) {
            if (active.containsKey(request)) {
                active.remove(request)
                true
            } else {
                false
            }
        }
        if (owned) runCatching { releaseNative(request) }
        return owned
    }

    fun destroy() {
        val requests = synchronized(lock) {
            if (isDestroyed) return
            isDestroyed = true
            active.entries.map { it.key to it.value }.also { active.clear() }
        }
        requests.forEach { (request, disposeRequest) ->
            runCatching { disposeRequest?.invoke() }
            runCatching { releaseNative(request) }
        }
    }
}
