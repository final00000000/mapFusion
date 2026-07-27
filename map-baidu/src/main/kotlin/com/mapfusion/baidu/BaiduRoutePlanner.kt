package com.mapfusion.baidu

import com.baidu.mapapi.search.core.RouteLine as NativeRouteLine
import com.baidu.mapapi.search.core.RouteStep as NativeRouteStep
import com.baidu.mapapi.search.core.SearchResult
import com.baidu.mapapi.search.route.BikingRouteLine
import com.baidu.mapapi.search.route.BikingRoutePlanOption
import com.baidu.mapapi.search.route.BikingRouteResult
import com.baidu.mapapi.search.route.DrivingRouteLine
import com.baidu.mapapi.search.route.DrivingRoutePlanOption
import com.baidu.mapapi.search.route.DrivingRouteResult
import com.baidu.mapapi.search.route.IndoorRouteResult
import com.baidu.mapapi.search.route.IntegralRouteResult
import com.baidu.mapapi.search.route.MassTransitRouteResult
import com.baidu.mapapi.search.route.OnGetRoutePlanResultListener
import com.baidu.mapapi.search.route.PlanNode
import com.baidu.mapapi.search.route.RoutePlanSearch
import com.baidu.mapapi.search.route.TransitRouteLine
import com.baidu.mapapi.search.route.TransitRoutePlanOption
import com.baidu.mapapi.search.route.TransitRouteResult
import com.baidu.mapapi.search.route.WalkingRouteLine
import com.baidu.mapapi.search.route.WalkingRoutePlanOption
import com.baidu.mapapi.search.route.WalkingRouteResult
import com.mapfusion.api.async.AsyncRequest
import com.mapfusion.api.async.AsyncRuntime
import com.mapfusion.api.capability.RoutePlanner
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.RoutePath
import com.mapfusion.api.model.RoutePreference
import com.mapfusion.api.model.RouteRequest
import com.mapfusion.api.model.RouteResult
import com.mapfusion.api.model.RouteStep
import com.mapfusion.api.model.RequestHandle
import com.mapfusion.api.model.TravelMode

/** 百度驾车、步行、骑行和市内公交路线规划真实适配。 */
internal class BaiduRoutePlanner(
    private val asyncRuntime: AsyncRuntime = AsyncRuntime.DEFAULT,
) : RoutePlanner {

    private val requests = NativeRequestRegistry<RoutePlanSearch>(RoutePlanSearch::destroy)

    override fun supportedModes(): Set<TravelMode> = setOf(
        TravelMode.DRIVING,
        TravelMode.WALKING,
        TravelMode.BICYCLE,
        TravelMode.ELECTRIC_BICYCLE,
        TravelMode.TRANSIT,
    )

    override fun plan(
        request: RouteRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<RouteResult>,
    ): RequestHandle {
        if (requests.isDestroyed) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度路线规划器已销毁"),
            )
        }
        if (!supportsMode(request.mode)) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.UNSUPPORTED, "百度不支持 ${request.mode} 路线规划"),
            )
        }
        if (request.mode == TravelMode.TRANSIT && request.city.isNullOrBlank()) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "公交路线必须提供 city"),
            )
        }

        val search = runCatching { RoutePlanSearch.newInstance() }.getOrElse {
            return asyncRuntime.failedRequest(asyncOptions, callback, it.toRouteError("百度路线规划初始化失败"))
        }
        val async = requests.trackedRequest(search, asyncRuntime, asyncOptions, callback)
        if (async == null) {
            runCatching { search.destroy() }
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度路线规划器已销毁"),
            )
        }
        if (async.isDone) return async

        val accepted = try {
            search.setOnGetRoutePlanResultListener(
                object : OnGetRoutePlanResultListener {
                    override fun onGetDrivingRouteResult(result: DrivingRouteResult) {
                        if (request.mode != TravelMode.DRIVING) return
                        deliver(result, result.routeLines.orEmpty(), request.mode, async)
                    }

                    override fun onGetWalkingRouteResult(result: WalkingRouteResult) {
                        if (request.mode != TravelMode.WALKING) return
                        deliver(result, result.routeLines.orEmpty(), request.mode, async)
                    }

                    override fun onGetBikingRouteResult(result: BikingRouteResult) {
                        if (!request.mode.isBiking()) return
                        deliver(result, result.routeLines.orEmpty(), request.mode, async)
                    }

                    override fun onGetTransitRouteResult(result: TransitRouteResult) {
                        if (request.mode != TravelMode.TRANSIT) return
                        deliver(result, result.routeLines.orEmpty(), request.mode, async)
                    }

                    override fun onGetMassTransitRouteResult(result: MassTransitRouteResult) = Unit
                    override fun onGetIndoorRouteResult(result: IndoorRouteResult) = Unit
                    override fun onGetIntegralRouteResult(result: IntegralRouteResult) = Unit
                },
            )

            val from = PlanNode.withLocation(request.origin.toBaiduRoutePoint())
            val to = PlanNode.withLocation(request.destination.toBaiduRoutePoint())
            when (request.mode) {
                TravelMode.DRIVING -> search.drivingSearch(
                    DrivingRoutePlanOption()
                        .from(from)
                        .to(to)
                        .passBy(request.waypoints.map { PlanNode.withLocation(it.toBaiduRoutePoint()) })
                        .policy(request.preference.toBaiduDrivingPolicy())
                        .trafficPolicy(DrivingRoutePlanOption.DrivingTrafficPolicy.ROUTE_PATH_AND_TRAFFIC),
                )
                TravelMode.WALKING -> search.walkingSearch(WalkingRoutePlanOption().from(from).to(to))
                TravelMode.BICYCLE, TravelMode.RIDING -> search.bikingSearch(
                    BikingRoutePlanOption().from(from).to(to)
                        .ridingType(BICYCLE_TYPE)
                        .passBy(request.waypoints.map { PlanNode.withLocation(it.toBaiduRoutePoint()) }),
                )
                TravelMode.ELECTRIC_BICYCLE -> search.bikingSearch(
                    BikingRoutePlanOption().from(from).to(to)
                        .ridingType(ELECTRIC_BICYCLE_TYPE)
                        .passBy(request.waypoints.map { PlanNode.withLocation(it.toBaiduRoutePoint()) }),
                )
                TravelMode.TRANSIT -> search.transitSearch(
                    TransitRoutePlanOption().from(from).to(to)
                        .city(request.city)
                        .policy(request.preference.toBaiduTransitPolicy()),
                )
            }
        } catch (error: Throwable) {
            async.failure(error.toRouteError("百度路线规划失败"))
            return async
        }
        if (!accepted) {
            async.failure(MapError(ErrorType.INVALID_PARAM, "百度未接受路线请求"))
        }
        return async
    }

    private fun deliver(
        result: SearchResult,
        lines: List<NativeRouteLine<out NativeRouteStep>>,
        mode: TravelMode,
        async: AsyncRequest<RouteResult>,
    ) {
        val routeResult = runCatching {
            if (result.error == SearchResult.ERRORNO.NO_ERROR && lines.isNotEmpty()) {
                MapResult.Success(RouteResult(mode, lines.map { it.toFusionPath() }))
            } else {
                MapResult.Failure(result.toRouteError("百度路线规划失败"))
            }
        }.getOrElse { MapResult.Failure(it.toRouteError("百度路线结果解析失败")) }
        async.complete(routeResult)
    }

    override fun destroy() = requests.destroy()

    private companion object {
        const val BICYCLE_TYPE = 0
        const val ELECTRIC_BICYCLE_TYPE = 1
    }
}

private fun Throwable.toRouteError(prefix: String): MapError = when (this) {
    is IllegalArgumentException -> MapError(ErrorType.INVALID_PARAM, "$prefix：${message.orEmpty()}", cause = this)
    else -> MapError(ErrorType.UNKNOWN, "$prefix：${message.orEmpty()}", cause = this)
}

private fun TravelMode.isBiking(): Boolean =
    this == TravelMode.BICYCLE || this == TravelMode.ELECTRIC_BICYCLE || this == TravelMode.RIDING

private fun LatLng.toBaiduRoutePoint() = toBaiduSdkLatLng()

private fun RoutePreference.toBaiduDrivingPolicy() = when (this) {
    RoutePreference.SHORTEST_DISTANCE -> DrivingRoutePlanOption.DrivingPolicy.ECAR_DIS_FIRST
    RoutePreference.AVOID_CONGESTION -> DrivingRoutePlanOption.DrivingPolicy.ECAR_AVOID_JAM
    RoutePreference.AVOID_TOLLS -> DrivingRoutePlanOption.DrivingPolicy.ECAR_FEE_FIRST
    else -> DrivingRoutePlanOption.DrivingPolicy.ECAR_TIME_FIRST
}

private fun RoutePreference.toBaiduTransitPolicy() = when (this) {
    RoutePreference.LEAST_TRANSFERS -> TransitRoutePlanOption.TransitPolicy.EBUS_TRANSFER_FIRST
    RoutePreference.LEAST_WALKING -> TransitRoutePlanOption.TransitPolicy.EBUS_WALK_FIRST
    RoutePreference.NO_SUBWAY -> TransitRoutePlanOption.TransitPolicy.EBUS_NO_SUBWAY
    else -> TransitRoutePlanOption.TransitPolicy.EBUS_TIME_FIRST
}

private fun NativeRouteLine<out NativeRouteStep>.toFusionPath(): RoutePath {
    val nativeSteps = allStep.orEmpty()
    val steps = nativeSteps.map { step ->
        val instruction = when (step) {
            is DrivingRouteLine.DrivingStep -> step.instructions
            is WalkingRouteLine.WalkingStep -> step.instructions
            is BikingRouteLine.BikingStep -> step.instructions
            is TransitRouteLine.TransitStep -> step.instructions
            else -> step.name
        }.orEmpty()
        RouteStep(
            instruction = instruction,
            distanceMeters = step.distance,
            durationSeconds = step.duration,
            polyline = step.wayPoints.orEmpty().map {
                LatLng(it.latitude, it.longitude, CoordType.BD09)
            },
        )
    }
    val cost = when (this) {
        is DrivingRouteLine -> toll.takeIf { it > 0 }?.toDouble()
        else -> null
    }
    return RoutePath(
        distanceMeters = distance,
        durationSeconds = duration,
        steps = steps,
        polyline = steps.flatMap(RouteStep::polyline),
        cost = cost,
    )
}

private fun SearchResult.toRouteError(prefix: String): MapError {
    val typeOverride = when (error) {
        SearchResult.ERRORNO.NOT_SUPPORT_BUS,
        SearchResult.ERRORNO.NOT_SUPPORT_BUS_2CITY -> ErrorType.UNSUPPORTED
        else -> null
    }
    return error.toBaiduSearchError(prefix, status, typeOverride)
}
