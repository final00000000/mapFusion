package com.mapfusion.api.model

import java.util.concurrent.Executor

/**
 * 单次异步请求句柄。
 *
 * [cancel] 只影响当前请求且幂等。只有首次把活动请求切换为取消态时返回 true；
 * 已成功、失败、超时、取消或被静默释放的请求均返回 false。
 */
interface RequestHandle {
    val isDone: Boolean
    val isCancelled: Boolean

    fun cancel(): Boolean
}

/**
 * 单次异步调用选项。
 *
 * [callbackExecutor] 为 null 时使用 SDK 的 Android 主线程执行器。超时从请求句柄创建时
 * 开始计算，且必须为正数。
 */
data class AsyncCallOptions(
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val callbackExecutor: Executor? = null,
) {
    init {
        require(timeoutMillis > 0) { "timeoutMillis 必须大于 0" }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 15_000L
    }
}
