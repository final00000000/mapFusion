package com.mapfusion.demo.data

import com.mapfusion.api.async.RequestScope
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.RequestHandle

/** 页面单次请求仓库：只持有句柄和代次，不保存厂商客户端、Activity 或 View。 */
internal class DemoRequestRepository : AutoCloseable {
    /** 托管本页单次请求句柄的 SDK 作用域；替换、会话失效或 close 时负责取消。 */
    private val scope = RequestScope()
    /** 保护关闭标记、序号、同键 token 和会话代次的一把锁，不是 Android/厂商对象。 */
    private val lock = Any()
    /** 业务请求键到最新监听 token 的映射；同键替换先改 token，防止旧取消回调串入。 */
    private val keys = mutableMapOf<String, Long>()
    /** 递增的请求监听序号，初始 0；即使键相同也区分新旧监听，不是时间或会话代次。 */
    private var sequence = 0L
    /** 仓库永久关闭标记，默认 false；close 后拒绝请求和所有迟到监听。 */
    private var closed = false
    /** 当前页面 SDK 会话代次，初始 0；切厂商/撤回/关闭递增，仅运行态且由仓库维护。 */
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
