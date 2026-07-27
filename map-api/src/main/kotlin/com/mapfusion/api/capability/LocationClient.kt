package com.mapfusion.api.capability

import com.mapfusion.api.model.LocationOptions
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapLocation
import com.mapfusion.api.model.RequestHandle

/**
 * 定位能力:单次定位与连续定位。归一百度 LocationClient / 高德 AMapLocationClient。
 *
 * 注意:定位权限由宿主 App 申请,适配器不负责运行时权限弹窗,
 * 权限缺失时通过 MapResult.Failure(PERMISSION) 返回。
 */
interface LocationClient {

    /** 单次定位。 */
    fun requestSingleLocation(
        options: LocationOptions = LocationOptions(onceOnly = true),
        callback: MapCallback<MapLocation>,
    ): RequestHandle = requestSingleLocation(
        options,
        options.toDefaultAsyncCallOptions(),
        callback,
    )

    fun requestSingleLocation(
        options: LocationOptions,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<MapLocation>,
    ): RequestHandle

    /**
     * 开始连续定位,每次更新回调一次。返回句柄代表本次订阅；取消只停止当前订阅。
     * [AsyncCallOptions.timeoutMillis] 仅限制等待首次定位结果的时间，首次结果后不再计时。
     */
    fun startContinuousLocation(
        options: LocationOptions,
        callback: MapCallback<MapLocation>,
    ): RequestHandle = startContinuousLocation(
        options,
        options.toDefaultAsyncCallOptions(),
        callback,
    )

    fun startContinuousLocation(
        options: LocationOptions,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<MapLocation>,
    ): RequestHandle

    /** 停止连续定位。 */
    fun stopContinuousLocation()

    /** 释放底层资源。 */
    fun destroy()
}

private fun LocationOptions.toDefaultAsyncCallOptions(): AsyncCallOptions =
    AsyncCallOptions(
        // 让 LocationOptions.timeoutMs 成为默认调用的首定位截止时间；非正值仍交给
        // 具体适配器返回统一 INVALID_PARAM，而不是在默认参数构造阶段抛出异常。
        timeoutMillis = timeoutMs.takeIf { it > 0 } ?: AsyncCallOptions.DEFAULT_TIMEOUT_MILLIS,
    )
