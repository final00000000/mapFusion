package com.mapfusion.demo.data

import com.mapfusion.api.async.RequestScope
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.RequestHandle

/** 页面单次请求仓库：只持有句柄和代次，不保存厂商客户端、Activity 或 View。 */
internal class DemoRequestRepository : AutoCloseable {
    private val scope = RequestScope()
    private val lock = Any()
    private val keys = mutableMapOf<String, Long>()
    private var sequence = 0L
    private var closed = false
    var generation: Int = 0
        private set

    /** 判断回调是否仍属于当前活跃会话，永久关闭后始终拒绝。 */
    fun accepts(epoch: Int): Boolean = synchronized(lock) { !closed && generation == epoch }

    /** 同键替换先使旧监听失效再取消句柄，避免同步取消结果覆盖新请求状态。 */
    fun <T> execute(key: String, launch: (MapCallback<T>) -> RequestHandle, callback: MapCallback<T>): RequestHandle {
        val token = synchronized(lock) {
            check(!closed) { "页面请求仓库已关闭" }
            (++sequence).also { keys[key] = it }
        }
        // 取消前先失效旧 token，SDK 同步发出的 CANCELLED 也不能进入新监听。
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

    /** 切厂商或撤回隐私时增加代次、失效监听并取消全部请求。 */
    fun invalidateSession() {
        synchronized(lock) { generation++; keys.clear() }
        scope.cancelAll()
    }

    /** 幂等永久关闭仓库，拒绝新请求和关闭后的所有迟到回调。 */
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
