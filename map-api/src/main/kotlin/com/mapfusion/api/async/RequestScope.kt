package com.mapfusion.api.async

import com.mapfusion.api.model.RequestHandle

/**
 * 一组可按业务键替换和统一释放的异步请求。
 *
 * 同一个 key 最多保留一个未完成请求。替换、取消或关闭时调用句柄的 cancel；因此
 * 业务仍会收到一次统一的 CANCELLED 结果。Scope 本身不持有 Android Context。
 */
class RequestScope : AutoCloseable {

    private val lock = Any()
    private val requests = LinkedHashMap<String, RequestHandle>()
    private var closed = false

    /** 登记请求；同 key 的旧请求会被取消。Scope 已关闭时新请求立即取消。 */
    fun replace(key: String, handle: RequestHandle): RequestHandle {
        val previous: RequestHandle?
        val cancelNew: Boolean
        synchronized(lock) {
            previous = requests.put(key, handle)
            cancelNew = closed
            if (closed || handle.isDone) requests.remove(key)
        }
        runCatching { previous?.cancel() }
        if (cancelNew) runCatching { handle.cancel() }
        return handle
    }

    /** 取消并移除指定 key 的请求。 */
    fun cancel(key: String): Boolean {
        val handle = synchronized(lock) { requests.remove(key) } ?: return false
        return runCatching { handle.cancel() }.getOrDefault(false)
    }

    /** 取消并移除当前全部请求；之后仍可登记新请求。 */
    fun cancelAll() {
        val handles = synchronized(lock) {
            val values = requests.values.toList()
            requests.clear()
            values
        }
        handles.forEach { handle -> runCatching { handle.cancel() } }
    }

    /** 永久关闭 Scope；关闭后登记的请求会立即取消。 */
    override fun close() {
        val handles = synchronized(lock) {
            if (closed) return
            closed = true
            val values = requests.values.toList()
            requests.clear()
            values
        }
        handles.forEach { handle -> runCatching { handle.cancel() } }
    }
}
