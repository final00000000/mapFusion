package com.mapfusion.amap

import android.content.Context
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.route.BusPath
import com.amap.api.services.route.BusRouteResult
import com.amap.api.services.route.DrivePath
import com.amap.api.services.route.DriveRouteResult
import com.amap.api.services.route.Path
import com.amap.api.services.route.RidePath
import com.amap.api.services.route.RideRouteResult
import com.amap.api.services.route.RouteSearch
import com.amap.api.services.route.WalkPath
import com.amap.api.services.route.WalkRouteResult
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

/** 高德驾车、步行、骑行和公交路线规划真实适配。 */
internal class AmapRoutePlanner(
    private val context: Context,
) : RoutePlanner {

    private val runtime = AsyncRuntime.DEFAULT
    private val requests = NativeRequestRegistry<RouteSearch> { it.setRouteSearchListener(null) }

    override fun supportedModes(): Set<TravelMode> = setOf(
        TravelMode.DRIVING,
        TravelMode.WALKING,
        TravelMode.BICYCLE,
        TravelMode.TRANSIT,
    )

    override fun plan(
        request: RouteRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<RouteResult>,
    ): RequestHandle {
        if (requests.isDestroyed) {
            return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "高德路线规划器已销毁"))
        }
        if (!supportsMode(request.mode)) {
            return failed(
                asyncOptions,
                callback,
                MapError(ErrorType.UNSUPPORTED, "高德当前 SDK 不支持电动自行车路线"),
            )
        }
        if (request.mode == TravelMode.TRANSIT && request.city.isNullOrBlank()) {
            return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "公交路线必须提供 city"))
        }

        val search = runCatching { RouteSearch(context) }.getOrElse {
            return failed(asyncOptions, callback, it.toRouteError("高德路线规划初始化失败"))
        }
        val async = runtime.createRequest(
            callback = callback,
            options = asyncOptions,
            terminalAction = Runnable { requests.release(search) },
        )
        if (!requests.register(search, async)) {
            runCatching { search.setRouteSearchListener(null) }
            async.dispose()
            return async
        }
        runCatching {
            val listener = object : RouteSearch.OnRouteSearchListener {
                override fun onDriveRouteSearched(result: DriveRouteResult?, code: Int) {
                    if (request.mode == TravelMode.DRIVING) {
                        deliver(code, request.mode, result?.paths.orEmpty().map(DrivePath::toFusionPath), search)
                    }
                }

                override fun onWalkRouteSearched(result: WalkRouteResult?, code: Int) {
                    if (request.mode == TravelMode.WALKING) {
                        deliver(code, request.mode, result?.paths.orEmpty().map(WalkPath::toFusionPath), search)
                    }
                }

                override fun onRideRouteSearched(result: RideRouteResult?, code: Int) {
                    if (request.mode == TravelMode.BICYCLE || request.mode == TravelMode.RIDING) {
                        deliver(code, request.mode, result?.paths.orEmpty().map(RidePath::toFusionPath), search)
                    }
                }

                override fun onBusRouteSearched(result: BusRouteResult?, code: Int) {
                    if (request.mode == TravelMode.TRANSIT) {
                        deliver(code, request.mode, result?.paths.orEmpty().map(BusPath::toFusionPath), search)
                    }
                }
            }

            val fromAndTo = RouteSearch.FromAndTo(
                request.origin.toAmapRoutePoint(),
                request.destination.toAmapRoutePoint(),
            ).apply {
                plateProvince = request.plateProvince
                plateNumber = request.plateNumber
            }
            requests.withRegistered(search) {
                search.setRouteSearchListener(listener)
                when (request.mode) {
                    TravelMode.DRIVING -> search.calculateDriveRouteAsyn(
                        RouteSearch.DriveRouteQuery(
                            fromAndTo,
                            request.preference.toAmapDrivingMode(),
                            request.waypoints.map(LatLng::toAmapRoutePoint),
                            null,
                            "",
                        ).apply {
                            isUseFerry = !request.avoidFerries
                            extensions = RouteSearch.EXTENSIONS_ALL
                            exclude = when (request.preference) {
                                RoutePreference.AVOID_TOLLS -> RouteSearch.DRIVING_EXCLUDE_TOLL
                                RoutePreference.AVOID_HIGHWAYS -> RouteSearch.DRIVING_EXCLUDE_MOTORWAY
                                else -> null
                            }
                        },
                    )
                    TravelMode.WALKING -> search.calculateWalkRouteAsyn(
                        RouteSearch.WalkRouteQuery(fromAndTo, RouteSearch.WALK_MULTI_PATH).apply {
                            extensions = RouteSearch.EXTENSIONS_ALL
                        },
                    )
                    TravelMode.BICYCLE, TravelMode.RIDING -> search.calculateRideRouteAsyn(
                        RouteSearch.RideRouteQuery(fromAndTo, RouteSearch.RIDING_RECOMMEND).apply {
                            extensions = RouteSearch.EXTENSIONS_ALL
                        },
                    )
                    TravelMode.TRANSIT -> search.calculateBusRouteAsyn(
                        RouteSearch.BusRouteQuery(
                            fromAndTo,
                            request.preference.toAmapBusMode(),
                            request.city,
                            0,
                        ).apply { extensions = RouteSearch.EXTENSIONS_ALL },
                    )
                    TravelMode.ELECTRIC_BICYCLE -> error("已在请求入口拦截不支持的电动自行车路线")
                }
            }
        }.onFailure { async.failure(it.toRouteError("高德路线规划初始化失败")) }
        return async
    }

    private fun deliver(
        code: Int,
        mode: TravelMode,
        paths: List<RoutePath>,
        search: RouteSearch,
    ) {
        val result = runCatching {
            if (code == AMapException.CODE_AMAP_SUCCESS && paths.isNotEmpty()) {
                MapResult.Success(RouteResult(mode, paths))
            } else {
                MapResult.Failure(code.toRouteError("高德路线规划失败"))
            }
        }.getOrElse { MapResult.Failure(it.toRouteError("高德路线结果解析失败")) }
        requests.complete(search, result)
    }

    override fun destroy() = requests.destroy()

    private fun failed(
        options: AsyncCallOptions,
        callback: MapCallback<RouteResult>,
        error: MapError,
    ): RequestHandle = runtime.createRequest(callback, options).also { it.failure(error) }
}

private fun LatLng.toAmapRoutePoint() = toAmapServicePoint()

private fun RoutePreference.toAmapDrivingMode() = when (this) {
    RoutePreference.SHORTEST_DISTANCE -> RouteSearch.DRIVING_SINGLE_SHORTEST
    RoutePreference.AVOID_CONGESTION -> RouteSearch.DRIVING_SINGLE_AVOID_CONGESTION
    RoutePreference.AVOID_TOLLS -> RouteSearch.DRIVING_SINGLE_SAVE_MONEY
    RoutePreference.AVOID_HIGHWAYS -> RouteSearch.DRIVING_SINGLE_NO_HIGHWAY
    else -> RouteSearch.DRIVING_SINGLE_DEFAULT
}

private fun RoutePreference.toAmapBusMode() = when (this) {
    RoutePreference.LEAST_TRANSFERS -> RouteSearch.BUS_LEASE_CHANGE
    RoutePreference.LEAST_WALKING -> RouteSearch.BUS_LEASE_WALK
    RoutePreference.NO_SUBWAY -> RouteSearch.BUS_NO_SUBWAY
    else -> RouteSearch.BUS_DEFAULT
}

private fun DrivePath.toFusionPath(): RoutePath {
    val mappedSteps = steps.orEmpty().map { step ->
        RouteStep(
            step.instruction.orEmpty(),
            step.distance.toInt(),
            step.duration.toInt(),
            step.polyline.orEmpty().toFusionPolyline(),
        )
    }
    return commonPath(mappedSteps, tolls.takeIf { it > 0 }?.toDouble())
}

private fun WalkPath.toFusionPath(): RoutePath {
    val mappedSteps = steps.orEmpty().map { step ->
        RouteStep(
            step.instruction.orEmpty(), step.distance.toInt(), step.duration.toInt(),
            step.polyline.orEmpty().toFusionPolyline(),
        )
    }
    return commonPath(mappedSteps)
}

private fun RidePath.toFusionPath(): RoutePath {
    val mappedSteps = steps.orEmpty().map { step ->
        RouteStep(
            step.instruction.orEmpty(), step.distance.toInt(), step.duration.toInt(),
            step.polyline.orEmpty().toFusionPolyline(),
        )
    }
    return commonPath(mappedSteps)
}

private fun BusPath.toFusionPath(): RoutePath {
    val mappedSteps = steps.orEmpty().flatMap { busStep ->
        buildList {
            busStep.walk?.steps.orEmpty().forEach { step ->
                add(
                    RouteStep(
                        step.instruction.orEmpty(), step.distance.toInt(), step.duration.toInt(),
                        step.polyline.orEmpty().toFusionPolyline(),
                    ),
                )
            }
            busStep.busLines.orEmpty().forEach { line ->
                add(
                    RouteStep(
                        instruction = line.busLineName.orEmpty(),
                        distanceMeters = line.distance.toInt(),
                        durationSeconds = line.duration.toInt(),
                        polyline = line.polyline.orEmpty().toFusionPolyline(),
                    ),
                )
            }
        }
    }
    return commonPath(mappedSteps, cost.takeIf { it > 0 }?.toDouble())
}

private fun Path.commonPath(steps: List<RouteStep>, cost: Double? = null) = RoutePath(
    distanceMeters = distance.toInt(),
    durationSeconds = duration.toInt(),
    steps = steps,
    polyline = polyline.orEmpty().toFusionPolyline().ifEmpty { steps.flatMap(RouteStep::polyline) },
    cost = cost,
)

private fun List<LatLonPoint>.toFusionPolyline() = map {
    LatLng(it.latitude, it.longitude, CoordType.GCJ02)
}

private fun Int.toRouteError(prefix: String): MapError {
    val type = when (this) {
        AMapException.CODE_AMAP_SUCCESS -> ErrorType.NO_RESULT
        AMapException.CODE_AMAP_INVALID_USER_KEY,
        AMapException.CODE_AMAP_INVALID_USER_SCODE,
        AMapException.CODE_AMAP_USERKEY_PLAT_NOMATCH -> ErrorType.AUTH
        AMapException.CODE_AMAP_ENGINE_CONNECT_TIMEOUT,
        AMapException.CODE_AMAP_ENGINE_RETURN_TIMEOUT,
        AMapException.CODE_AMAP_CLIENT_NETWORK_EXCEPTION -> ErrorType.NETWORK
        AMapException.CODE_AMAP_SERVICE_INVALID_PARAMS,
        AMapException.CODE_AMAP_CLIENT_INVALID_PARAMETER -> ErrorType.INVALID_PARAM
        AMapException.CODE_AMAP_ROUTE_OUT_OF_SERVICE -> ErrorType.UNSUPPORTED
        else -> ErrorType.UNKNOWN
    }
    return MapError(type, "$prefix（$this）", rawCode = this)
}

private fun Throwable.toRouteError(prefix: String): MapError = when (this) {
    is AMapException -> errorCode.toRouteError("$prefix：${errorMessage}")
    is IllegalStateException,
    is IllegalArgumentException -> MapError(ErrorType.INVALID_PARAM, "$prefix：${message.orEmpty()}", cause = this)
    else -> MapError(ErrorType.UNKNOWN, "$prefix：${message.orEmpty()}", cause = this)
}
