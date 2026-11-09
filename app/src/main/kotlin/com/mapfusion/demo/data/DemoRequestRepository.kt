package com.mapfusion.demo.data

import com.mapfusion.api.async.RequestScope
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.RequestHandle

/** Page-scoped service requests. SDK clients are passed per use case, never retained. */
internal class DemoRequestRepository : AutoCloseable {
    private val scope = RequestScope()
    private val lock = Any()
    private val keys = mutableMapOf<String, Long>()
    private var sequence = 0L
    private var closed = false
    var generation: Int = 0
        private set

    fun accepts(epoch: Int): Boolean = synchronized(lock) { !closed && generation == epoch }

    fun <T> execute(key: String, launch: (MapCallback<T>) -> RequestHandle, callback: MapCallback<T>): RequestHandle {
        val token = synchronized(lock) {
            check(!closed) { "页面请求仓库已关闭" }
            (++sequence).also { keys[key] = it }
        }
        // Invalidate the old listener before cancelling: synchronous CANCELLED cannot overwrite new status.
        scope.cancel(key)
        val handle = try {
            launch(MapCallback { result ->
                val current = synchronized(lock) { !closed && keys[key] == token }
                if (current) callback.onResult(result)
            })
        } catch (error: Throwable) {
            synchronized(lock) { if (keys[key] == token) keys.remove(key) }
            throw error
        }
        val current = synchronized(lock) { !closed && keys[key] == token }
        if (current) scope.replace(key, handle) else handle.cancel()
        return handle
    }

    fun invalidateSession() {
        synchronized(lock) { generation++; keys.clear() }
        scope.cancelAll()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation++
            keys.clear()
        }
        scope.close()
    }
}
