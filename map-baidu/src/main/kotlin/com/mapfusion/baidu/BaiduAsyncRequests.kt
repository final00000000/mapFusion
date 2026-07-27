package com.mapfusion.baidu

import com.mapfusion.api.async.AsyncRequest
import com.mapfusion.api.async.AsyncRuntime
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapError

/** 创建一个已经失败的请求；回调仍严格使用 AsyncCallOptions 指定的执行器。 */
internal fun <T> AsyncRuntime.failedRequest(
    options: AsyncCallOptions,
    callback: MapCallback<T>,
    error: MapError,
): AsyncRequest<T> = createRequest(callback, options).also { it.failure(error) }

/**
 * 为已登记的原生对象创建统一请求，并将两边生命周期绑定在同一个终态动作上。
 * 返回的请求若已经被并发 destroy 静默释放，调用方不得再启动原生操作。
 */
internal fun <N : Any, T> NativeRequestRegistry<N>.trackedRequest(
    native: N,
    runtime: AsyncRuntime,
    options: AsyncCallOptions,
    callback: MapCallback<T>,
): AsyncRequest<T>? {
    if (!register(native)) return null
    val request = try {
        runtime.createRequest(
            callback = callback,
            options = options,
            terminalAction = Runnable { complete(native) },
        )
    } catch (error: Throwable) {
        complete(native)
        throw error
    }
    if (!bind(native, request::dispose)) request.dispose()
    return request
}
