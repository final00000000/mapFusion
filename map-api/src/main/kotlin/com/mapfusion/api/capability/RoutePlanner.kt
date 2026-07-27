package com.mapfusion.api.capability

import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.RouteRequest
import com.mapfusion.api.model.RouteResult
import com.mapfusion.api.model.TravelMode
import com.mapfusion.api.model.RequestHandle

/**
 * 路径规划能力:驾车/步行/骑行/公交。
 * 归一百度 RoutePlanSearch / 高德 RouteSearch。
 *
 * 具体支持哪些 [com.mapfusion.api.model.TravelMode] 因厂商而异,
 * 不支持的模式返回 MapResult.Failure(UNSUPPORTED)。
 */
interface RoutePlanner {

    /** 当前厂商真实支持的标准化出行方式，不包含已废弃的 RIDING 别名。 */
    fun supportedModes(): Set<TravelMode> = setOf(
        TravelMode.DRIVING,
        TravelMode.WALKING,
        TravelMode.BICYCLE,
        TravelMode.TRANSIT,
    )

    fun supportsMode(mode: TravelMode): Boolean = mode.canonical() in supportedModes()

    fun plan(
        request: RouteRequest,
        callback: MapCallback<RouteResult>,
    ): RequestHandle = plan(request, AsyncCallOptions(), callback)

    fun plan(
        request: RouteRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<RouteResult>,
    ): RequestHandle

    fun destroy()
}
