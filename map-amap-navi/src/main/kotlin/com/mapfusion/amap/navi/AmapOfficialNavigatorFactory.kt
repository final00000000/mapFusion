package com.mapfusion.amap.navi

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviListener
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.AMapNaviViewListener
import com.amap.api.navi.AMapNaviViewOptions
import com.amap.api.navi.AmapPageType
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapLaneInfo
import com.amap.api.navi.model.AMapModelCross
import com.amap.api.navi.model.AMapNaviCameraInfo
import com.amap.api.navi.model.AMapNaviCross
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.AMapNaviRouteNotifyData
import com.amap.api.navi.model.AMapNaviTrafficFacilityInfo
import com.amap.api.navi.model.AMapServiceAreaInfo
import com.amap.api.navi.model.AimLessModeCongestionInfo
import com.amap.api.navi.model.AimLessModeStat
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviLatLng
import com.mapfusion.api.async.AndroidMainThreadExecutor
import com.mapfusion.api.capability.OfficialNavigator
import com.mapfusion.api.capability.OfficialNavigatorFactory
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.coordinate.DefaultCoordinateConverter
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.OfficialCameraAlert
import com.mapfusion.api.model.OfficialHighwayGuidance
import com.mapfusion.api.model.OfficialIntervalCameraAlert
import com.mapfusion.api.model.OfficialNavigationCapabilities
import com.mapfusion.api.model.OfficialNavigationDayNightMode
import com.mapfusion.api.model.OfficialNavigationEvent
import com.mapfusion.api.model.OfficialNavigationFeature
import com.mapfusion.api.model.OfficialNavigationListener
import com.mapfusion.api.model.OfficialNavigationMode
import com.mapfusion.api.model.OfficialNavigationProgress
import com.mapfusion.api.model.OfficialNavigationRequest
import com.mapfusion.api.model.OfficialNavigationState
import com.mapfusion.api.model.OfficialRouteSummary
import com.mapfusion.api.model.OfficialRouteNotice
import com.mapfusion.api.model.OfficialServiceArea
import com.mapfusion.api.model.OfficialTrafficSegment
import com.mapfusion.api.model.OfficialWaypointProgress
import com.mapfusion.api.model.RoutePreference
import com.mapfusion.api.model.TravelMode
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class AmapOfficialNavigatorFactory : OfficialNavigatorFactory {
    override val capabilities: OfficialNavigationCapabilities = AMAP_OFFICIAL_NAVI_CAPABILITIES

    override fun create(activity: Activity, savedState: Bundle?): MapResult<OfficialNavigator> {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return failure(ErrorType.INVALID_PARAM, "高德官方导航会话必须在主线程创建")
        }
        if (!activeSession.compareAndSet(false, true)) {
            return failure(ErrorType.INVALID_PARAM, "同一进程只能创建一个高德官方导航会话")
        }
        return runCatching<OfficialNavigator> {
            AmapOfficialNavigator(activity, savedState) { activeSession.set(false) }
        }.fold(
            onSuccess = { MapResult.Success(it) },
            onFailure = { error ->
                activeSession.set(false)
                failure(ErrorType.UNKNOWN, "高德官方导航初始化失败：${error.message.orEmpty()}", cause = error)
            },
        )
    }

    private companion object {
        val activeSession = AtomicBoolean(false)
    }
}

private val AMAP_OFFICIAL_NAVI_CAPABILITIES = OfficialNavigationCapabilities(
    provider = Provider.AMAP,
    features = setOf(
        OfficialNavigationFeature.IN_APP_UI,
        OfficialNavigationFeature.VOICE_GUIDANCE,
        OfficialNavigationFeature.LANE_GUIDANCE,
        OfficialNavigationFeature.JUNCTION_VIEW,
        OfficialNavigationFeature.CAMERA_ALERTS,
        OfficialNavigationFeature.SPEED_LIMIT,
        OfficialNavigationFeature.TRAFFIC,
        OfficialNavigationFeature.AUTOMATIC_REROUTE,
        OfficialNavigationFeature.MULTI_ROUTE,
        OfficialNavigationFeature.SIMULATION_SPEED,
        OfficialNavigationFeature.WAYPOINT_ARRIVAL,
        OfficialNavigationFeature.GPS_STATUS,
        OfficialNavigationFeature.INTERVAL_CAMERA_ALERTS,
        OfficialNavigationFeature.SERVICE_AREAS,
        OfficialNavigationFeature.WAYPOINT_PROGRESS,
        OfficialNavigationFeature.HIGHWAY_GUIDANCE,
        OfficialNavigationFeature.ROUTE_NOTICES,
        OfficialNavigationFeature.MANUAL_REROUTE,
        OfficialNavigationFeature.ROUTE_OVERVIEW,
        OfficialNavigationFeature.RESTORE_FOLLOWING,
        OfficialNavigationFeature.RUNTIME_SIMULATION_SPEED,
        OfficialNavigationFeature.VOICE_CONTROL,
        OfficialNavigationFeature.DAY_NIGHT_MODE,
    ),
    modes = OfficialNavigationMode.entries.toSet(),
    travelModes = setOf(
        TravelMode.DRIVING,
        TravelMode.WALKING,
        TravelMode.BICYCLE,
        TravelMode.ELECTRIC_BICYCLE,
    ),
    maxWaypoints = 3,
)

internal class AmapOfficialNavigator(
    private val activity: Activity,
    savedState: Bundle?,
    private val releaseSession: () -> Unit,
) : OfficialNavigator, AMapNaviListener, AMapNaviViewListener {
    override val capabilities: OfficialNavigationCapabilities = AMAP_OFFICIAL_NAVI_CAPABILITIES
    // Hide empty-route guidance until navigation starts; keep vendor UI defaults
    // so turn instructions and other official guidance remain available in use.
    override val view: AMapNaviView = AMapNaviView(
        activity,
        AMapNaviViewOptions().apply {
            setLayoutVisible(false)
        },
    )

    private val engine: AMapNavi = AMapNavi.getInstance(activity.applicationContext)
    internal val nativeEngine: AMapNavi get() = engine
    private var currentState = OfficialNavigationState.INITIALIZING
    private var latestError: MapError? = null
    private var initialized = false
    private var initializationError: MapError? = null
    private var destroyed = false
    private var pendingRequest: OfficialNavigationRequest? = null
    private var listener: OfficialNavigationListener? = null
    private var dispatcher: SerialEventDispatcher? = null
    private var routeIds = intArrayOf()
    private var gpsEnabled: Boolean? = null
    private var gpsSignalWeak = false
    private var rerouting = false
    private var voiceGuidanceEnabled = true
    private var lastHighwayGuidance: OfficialHighwayGuidance? = null

    override val state: OfficialNavigationState get() = currentState
    override val lastError: MapError? get() = latestError
    internal val nativeAccessAvailable: Boolean get() = !destroyed

    init {
        engine.addAMapNaviListener(this)
        view.setAMapNaviViewListener(this)
        view.onCreate(savedState)
    }

    override fun start(
        request: OfficialNavigationRequest,
        listener: OfficialNavigationListener,
    ): MapResult<Unit> {
        validateStart(request)?.let { return MapResult.Failure(it) }
        this.listener = listener
        dispatcher?.close()
        dispatcher = SerialEventDispatcher(
            request.options.callbackExecutor ?: AndroidMainThreadExecutor,
        ) { event -> this.listener?.onEvent(event) }

        return if (!initialized) {
            pendingRequest = request
            emit(OfficialNavigationEvent.StateChanged(OfficialNavigationState.INITIALIZING))
            MapResult.Success(Unit)
        } else {
            beginRouteCalculation(request)
        }
    }

    override fun selectRoute(index: Int): MapResult<Unit> {
        if (destroyed) return destroyedFailure()
        if (index !in routeIds.indices) return failure(ErrorType.INVALID_PARAM, "路线下标 $index 超出范围")
        return if (engine.selectRouteId(routeIds[index])) {
            MapResult.Success(Unit)
        } else {
            failure(ErrorType.UNKNOWN, "高德官方导航未能切换到路线 $index")
        }
    }

    override fun pause(): MapResult<Unit> {
        if (destroyed) return destroyedFailure()
        if (state != OfficialNavigationState.NAVIGATING) {
            return failure(ErrorType.INVALID_PARAM, "当前状态 $state 不能暂停导航")
        }
        engine.pauseNavi()
        transition(OfficialNavigationState.PAUSED)
        return MapResult.Success(Unit)
    }

    override fun resume(): MapResult<Unit> {
        if (destroyed) return destroyedFailure()
        if (state != OfficialNavigationState.PAUSED) {
            return failure(ErrorType.INVALID_PARAM, "当前状态 $state 不能继续导航")
        }
        engine.resumeNavi()
        transition(OfficialNavigationState.NAVIGATING)
        return MapResult.Success(Unit)
    }

    override fun stop(): MapResult<Unit> {
        if (destroyed) return destroyedFailure()
        pendingRequest = null
        rerouting = false
        lastHighwayGuidance = null
        engine.stopNavi()
        transition(OfficialNavigationState.STOPPED)
        return MapResult.Success(Unit)
    }

    override fun recalculateRoute(preference: RoutePreference): MapResult<Unit> {
        activeNavigationFailure("重新规划路线")?.let { return it }
        if (rerouting) return failure(ErrorType.INVALID_PARAM, "高德官方导航正在重新规划路线")
        beginRerouting()
        return if (engine.reCalculateRoute(preference.toAmapStrategy(engine))) {
            MapResult.Success(Unit)
        } else {
            rerouting = false
            val error = MapError(ErrorType.UNKNOWN, "高德官方导航拒绝重新规划路线")
            emit(OfficialNavigationEvent.RerouteCompleted(success = false, error = error))
            MapResult.Failure(error)
        }
    }

    override fun showRouteOverview(): MapResult<Unit> {
        activeNavigationFailure("显示路线全览")?.let { return it }
        view.displayOverview()
        return MapResult.Success(Unit)
    }

    override fun resumeFollowing(): MapResult<Unit> {
        activeNavigationFailure("恢复车标跟随")?.let { return it }
        view.recoverLockMode()
        return MapResult.Success(Unit)
    }

    override fun setSimulationSpeed(kilometersPerHour: Int): MapResult<Unit> {
        activeNavigationFailure("设置模拟导航速度")?.let { return it }
        if (kilometersPerHour !in 9..120) {
            return failure(ErrorType.INVALID_PARAM, "高德模拟导航速度必须在 9..120 km/h")
        }
        if (pendingRequest?.options?.navigationMode != OfficialNavigationMode.SIMULATED) {
            return failure(ErrorType.INVALID_PARAM, "运行时速度只适用于高德模拟导航")
        }
        engine.setEmulatorNaviSpeed(kilometersPerHour)
        return MapResult.Success(Unit)
    }

    override fun setVoiceGuidanceEnabled(enabled: Boolean): MapResult<Unit> {
        if (destroyed) return destroyedFailure()
        voiceGuidanceEnabled = enabled
        engine.setUseInnerVoice(enabled, true)
        return MapResult.Success(Unit)
    }

    override fun setDayNightMode(mode: OfficialNavigationDayNightMode): MapResult<Unit> {
        if (destroyed) return destroyedFailure()
        val options = view.viewOptions
        options.isAutoNaviViewNightMode = mode == OfficialNavigationDayNightMode.AUTO
        if (mode != OfficialNavigationDayNightMode.AUTO) {
            options.isNaviNight = mode == OfficialNavigationDayNightMode.NIGHT
        }
        view.viewOptions = options
        return MapResult.Success(Unit)
    }

    override fun onResume() = view.onResume()
    override fun onPause() = view.onPause()
    override fun onSaveInstanceState(outState: Bundle) = view.onSaveInstanceState(outState)
    override fun onBackPressed(): Boolean = view.onBackPressed()
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = false

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        currentState = OfficialNavigationState.DESTROYED
        pendingRequest = null
        rerouting = false
        lastHighwayGuidance = null
        runCatching { engine.stopNavi() }
        runCatching { engine.removeAMapNaviListener(this) }
        runCatching { view.onDestroy() }
        runCatching { AMapNavi.destroy() }
        dispatcher?.close()
        listener = null
        releaseSession()
    }

    private fun validateStart(request: OfficialNavigationRequest): MapError? {
        if (destroyed) return MapError(ErrorType.INVALID_PARAM, "高德官方导航会话已销毁")
        initializationError?.let { return it }
        val route = request.routeRequest
        if (!capabilities.supports(route.mode)) {
            return MapError(ErrorType.UNSUPPORTED, "高德官方导航不支持 ${route.mode} 模式")
        }
        if (!capabilities.supports(request.options.navigationMode)) {
            return MapError(ErrorType.UNSUPPORTED, "高德官方导航不支持 ${request.options.navigationMode}")
        }
        if (request.options.routeIndex < 0) return MapError(ErrorType.INVALID_PARAM, "路线下标不能小于 0")
        if (request.options.simulationSpeedKilometersPerHour !in 9..120) {
            return MapError(ErrorType.INVALID_PARAM, "高德模拟导航速度必须在 9..120 km/h")
        }
        if (route.waypoints.size > capabilities.maxWaypoints) {
            return MapError(ErrorType.INVALID_PARAM, "高德官方导航最多支持 ${capabilities.maxWaypoints} 个途经点")
        }
        if (route.mode.canonical() != TravelMode.DRIVING && route.waypoints.isNotEmpty()) {
            return MapError(ErrorType.UNSUPPORTED, "高德非驾车官方导航不支持途经点")
        }
        return null
    }

    private fun beginRouteCalculation(request: OfficialNavigationRequest): MapResult<Unit> {
        val route = request.routeRequest
        val converted = DefaultCoordinateConverter.convertAll(
            listOf(route.origin) + route.waypoints + route.destination,
            CoordType.GCJ02,
        )
        val points = when (converted) {
            is MapResult.Success -> converted.data.map { NaviLatLng(it.latitude, it.longitude) }
            is MapResult.Failure -> return converted
        }
        val origin = points.first()
        val destination = points.last()
        val waypoints = points.subList(1, points.lastIndex)

        pendingRequest = request
        routeIds = intArrayOf()
        rerouting = false
        lastHighwayGuidance = null
        transition(OfficialNavigationState.PLANNING)
        val accepted = when (route.mode.canonical()) {
            TravelMode.DRIVING -> engine.calculateDriveRoute(
                listOf(origin),
                listOf(destination),
                waypoints,
                engine.strategyConvert(
                    route.preference == RoutePreference.AVOID_CONGESTION,
                    route.preference == RoutePreference.AVOID_HIGHWAYS,
                    route.preference == RoutePreference.AVOID_TOLLS,
                    route.preference == RoutePreference.FASTEST,
                    true,
                ),
            )

            TravelMode.WALKING -> engine.calculateWalkRoute(origin, destination)
            TravelMode.BICYCLE -> engine.calculateRideRoute(origin, destination)
            TravelMode.ELECTRIC_BICYCLE -> engine.calculateEleBikeRoute(origin, destination)
            else -> false
        }
        if (!accepted) {
            val error = MapError(ErrorType.UNKNOWN, "高德官方导航拒绝路线规划请求")
            fail(error)
            return MapResult.Failure(error)
        }
        return MapResult.Success(Unit)
    }

    private fun handleRoutesReady(ids: IntArray) {
        if (destroyed || state != OfficialNavigationState.PLANNING) return
        val request = pendingRequest ?: return
        if (ids.isEmpty()) {
            fail(MapError(ErrorType.NO_RESULT, "高德官方导航未返回路线"))
            return
        }
        if (request.options.routeIndex !in ids.indices) {
            fail(MapError(ErrorType.INVALID_PARAM, "路线下标 ${request.options.routeIndex} 超出 ${ids.size} 条结果"))
            return
        }
        routeIds = ids
        if (!engine.selectRouteId(ids[request.options.routeIndex])) {
            fail(MapError(ErrorType.UNKNOWN, "高德官方导航无法选择路线 ${request.options.routeIndex}"))
            return
        }
        val paths = engine.naviPaths
        val summaries = ids.map { id ->
            val path = paths[id]
            OfficialRouteSummary(
                routeId = id.toString(),
                distanceMeters = path?.allLength ?: 0,
                durationSeconds = path?.allTime ?: 0,
                tollCost = path?.tollCost,
                trafficLightCount = path?.trafficLightCount,
            )
        }
        emit(OfficialNavigationEvent.RoutesReady(summaries, request.options.routeIndex))
        if (request.options.navigationMode == OfficialNavigationMode.SIMULATED) {
            engine.setEmulatorNaviSpeed(request.options.simulationSpeedKilometersPerHour)
        }
        engine.setUseInnerVoice(voiceGuidanceEnabled, true)
        engine.setTrafficStatusUpdateEnabled(true)
        engine.setCameraInfoUpdateEnabled(true)
        engine.setServiceAreaDetailsEnable(true)
        val naviType = if (request.options.navigationMode == OfficialNavigationMode.REAL) {
            NaviType.GPS
        } else {
            NaviType.EMULATOR
        }
        if (!engine.startNavi(naviType)) {
            fail(MapError(ErrorType.UNKNOWN, "高德官方导航引擎启动失败"))
        }
    }

    private fun handleRouteCalculationSuccess(ids: IntArray) {
        if (destroyed) return
        if (!rerouting) {
            handleRoutesReady(ids)
            return
        }
        rerouting = false
        if (ids.isNotEmpty()) routeIds = ids
        emit(OfficialNavigationEvent.RerouteCompleted(success = true))
    }

    private fun handleRouteCalculationFailure(error: MapError) {
        if (destroyed) return
        if (!rerouting) {
            fail(error)
            return
        }
        rerouting = false
        emit(OfficialNavigationEvent.RerouteCompleted(success = false, error = error))
    }

    private fun beginRerouting() {
        if (destroyed || rerouting) return
        rerouting = true
        emit(OfficialNavigationEvent.Rerouting)
    }

    private fun emitTrafficFacilities(infos: List<AMapNaviTrafficFacilityInfo>) {
        emit(
            OfficialNavigationEvent.CameraAlerts(
                infos.filter { it.distance >= 0 }.map { info ->
                    OfficialCameraAlert(
                        distanceMeters = info.distance,
                        speedLimitKilometersPerHour = info.limitSpeed.takeIf { it > 0 },
                        vendorType = info.broadcastType,
                    )
                },
            ),
        )
    }

    private fun transition(next: OfficialNavigationState) {
        currentState = next
        updateNavigationLayout()
        emit(OfficialNavigationEvent.StateChanged(next))
    }

    private fun fail(error: MapError) {
        latestError = error
        currentState = OfficialNavigationState.FAILED
        updateNavigationLayout()
        emit(OfficialNavigationEvent.Error(error))
        emit(OfficialNavigationEvent.StateChanged(OfficialNavigationState.FAILED))
    }

    private fun updateNavigationLayout() {
        val visible = state == OfficialNavigationState.NAVIGATING || state == OfficialNavigationState.PAUSED
        val options = view.viewOptions
        if (options.isLayoutVisible != visible) {
            options.setLayoutVisible(visible)
            view.viewOptions = options
        }
    }

    private fun emit(event: OfficialNavigationEvent) {
        dispatcher?.dispatch(event)
    }

    private fun destroyedFailure(): MapResult.Failure =
        MapResult.Failure(MapError(ErrorType.INVALID_PARAM, "高德官方导航会话已销毁"))

    private fun activeNavigationFailure(action: String): MapResult.Failure? = when {
        destroyed -> destroyedFailure()
        state != OfficialNavigationState.NAVIGATING && state != OfficialNavigationState.PAUSED ->
            MapResult.Failure(MapError(ErrorType.INVALID_PARAM, "当前状态 $state 不能$action"))
        else -> null
    }

    override fun onInitNaviSuccess() {
        if (destroyed) return
        initialized = true
        transition(OfficialNavigationState.READY)
        pendingRequest?.let(::beginRouteCalculation)
    }

    override fun onInitNaviFailure() {
        val error = MapError(ErrorType.AUTH, "高德官方导航引擎初始化失败")
        initializationError = error
        fail(error)
    }

    override fun onStartNavi(type: Int) = transition(OfficialNavigationState.NAVIGATING)

    override fun onCalculateRouteSuccess(routeIds: IntArray) = handleRouteCalculationSuccess(routeIds)

    override fun onCalculateRouteSuccess(result: AMapCalcRouteResult) =
        handleRouteCalculationSuccess(result.routeid ?: intArrayOf())

    override fun onCalculateRouteFailure(errorCode: Int) = handleRouteCalculationFailure(
        MapError(ErrorType.UNKNOWN, "高德官方导航路线规划失败", rawCode = errorCode),
    )

    override fun onCalculateRouteFailure(result: AMapCalcRouteResult) = handleRouteCalculationFailure(
        MapError(
            ErrorType.UNKNOWN,
            "高德官方导航路线规划失败：${result.errorDescription.orEmpty()}",
            rawCode = result.errorCode,
            rawMessage = result.errorDetail,
        ),
    )

    override fun onNaviInfoUpdate(info: NaviInfo) {
        val travelledDistance = engine.naviPath?.allLength
            ?.takeIf { it >= info.pathRetainDistance }
            ?.minus(info.pathRetainDistance)
        emit(
            OfficialNavigationEvent.Progress(
                OfficialNavigationProgress(
                    currentRoadName = info.currentRoadName,
                    nextRoadName = info.nextRoadName,
                    remainingDistanceMeters = info.pathRetainDistance,
                    remainingDurationSeconds = info.pathRetainTime,
                    currentStepIndex = info.curStep,
                    currentSpeedKilometersPerHour = info.currentSpeed,
                    travelledDistanceMeters = travelledDistance,
                    remainingTrafficLightCount = info.routeRemainLightCount.takeIf { it >= 0 },
                ),
            ),
        )
        emit(
            OfficialNavigationEvent.WaypointProgress(
                info.toViaInfo.orEmpty().mapNotNull { waypoint ->
                    if (waypoint.viaIndex < 0 || waypoint.distance < 0 || waypoint.time < 0) {
                        return@mapNotNull null
                    }
                    OfficialWaypointProgress(
                        waypointIndex = waypoint.viaIndex,
                        remainingDistanceMeters = waypoint.distance,
                        remainingDurationSeconds = waypoint.time,
                        remainingTrafficLightCount = waypoint.trafficlightNum.takeIf { it >= 0 },
                    )
                },
            ),
        )

        val exitInfo = info.exitDirectionInfo
        val exitName = exitInfo?.exitNameInfo
            ?.filter(String::isNotBlank)
            ?.joinToString(" / ")
            ?.takeIf(String::isNotBlank)
        val direction = exitInfo?.directionInfo
            ?.filter(String::isNotBlank)
            ?.joinToString(" / ")
            ?.takeIf(String::isNotBlank)
        val guidance = OfficialHighwayGuidance(
            visible = exitName != null || direction != null,
            direction = direction,
            exitName = exitName,
        )
        if (guidance != lastHighwayGuidance && (guidance.visible || lastHighwayGuidance != null)) {
            lastHighwayGuidance = guidance
            emit(OfficialNavigationEvent.HighwayGuidance(guidance))
        }
    }

    override fun onGetNavigationText(type: Int, text: String?) {
        text?.takeIf(String::isNotBlank)?.let { emit(OfficialNavigationEvent.VoicePrompt(it)) }
    }

    override fun onGetNavigationText(text: String?) {
        text?.takeIf(String::isNotBlank)?.let { emit(OfficialNavigationEvent.VoicePrompt(it)) }
    }

    override fun showLaneInfo(lanes: Array<AMapLaneInfo>?, background: ByteArray?, front: ByteArray?) {
        emit(OfficialNavigationEvent.LaneGuidance(true, lanes?.sumOf { it.laneCount } ?: 0))
    }

    override fun showLaneInfo(lane: AMapLaneInfo?) {
        emit(OfficialNavigationEvent.LaneGuidance(true, lane?.laneCount ?: 0))
    }

    override fun hideLaneInfo() { emit(OfficialNavigationEvent.LaneGuidance(false)) }
    override fun showCross(cross: AMapNaviCross?) { emit(OfficialNavigationEvent.JunctionView(true)) }
    override fun hideCross() { emit(OfficialNavigationEvent.JunctionView(false)) }
    override fun showModeCross(cross: AMapModelCross?) { emit(OfficialNavigationEvent.JunctionView(true)) }
    override fun hideModeCross() { emit(OfficialNavigationEvent.JunctionView(false)) }

    override fun updateCameraInfo(cameras: Array<AMapNaviCameraInfo>?) {
        emit(
            OfficialNavigationEvent.CameraAlerts(
                cameras.orEmpty().map { camera ->
                    OfficialCameraAlert(
                        distanceMeters = camera.cameraDistance,
                        speedLimitKilometersPerHour = camera.cameraSpeed.takeIf { it > 0 },
                        vendorType = camera.cameraType,
                    )
                },
            ),
        )
    }

    override fun onReCalculateRouteForYaw() = beginRerouting()
    override fun onReCalculateRouteForTrafficJam() = beginRerouting()

    override fun onArriveDestination() {
        currentState = OfficialNavigationState.ARRIVED
        updateNavigationLayout()
        emit(OfficialNavigationEvent.Arrived)
        emit(OfficialNavigationEvent.StateChanged(OfficialNavigationState.ARRIVED))
    }

    override fun onEndEmulatorNavi() = onArriveDestination()
    override fun onNaviCancel() { stop() }
    override fun onAMapNaviViewExit() { stop() }
    override fun onNaviBackClick(): Boolean = false

    override fun onTrafficStatusUpdate() {
        val segments = engine.naviPath?.let { path ->
            engine.getTrafficStatuses(0, path.allLength).orEmpty().map { status ->
                OfficialTrafficSegment(
                    lengthMeters = status.length,
                    vendorStatus = status.status,
                )
            }
        }.orEmpty()
        emit(
            OfficialNavigationEvent.TrafficStatus(
                distanceMeters = segments.sumOf { it.lengthMeters }.takeIf { it > 0 },
                segments = segments,
            ),
        )
    }
    override fun onLocationChange(location: AMapNaviLocation?) = Unit
    override fun onArrivedWayPoint(index: Int) {
        emit(OfficialNavigationEvent.WaypointArrived(index))
    }

    override fun onGpsOpenStatus(enabled: Boolean) {
        gpsEnabled = enabled
        emit(OfficialNavigationEvent.GpsStatus(enabled, gpsSignalWeak))
    }

    override fun updateIntervalCameraInfo(
        start: AMapNaviCameraInfo?,
        end: AMapNaviCameraInfo?,
        status: Int,
    ) {
        val speedLimit = listOfNotNull(
            start?.cameraSpeed?.takeIf { it > 0 },
            end?.cameraSpeed?.takeIf { it > 0 },
        ).minOrNull()
        emit(
            OfficialNavigationEvent.IntervalCameraAlert(
                OfficialIntervalCameraAlert(
                    startDistanceMeters = start?.cameraDistance?.takeIf { it >= 0 },
                    endDistanceMeters = end?.cameraDistance?.takeIf { it >= 0 },
                    remainingDistanceMeters = start?.intervalRemainDistance?.takeIf { it >= 0 }
                        ?: end?.intervalRemainDistance?.takeIf { it >= 0 },
                    speedLimitKilometersPerHour = speedLimit,
                    averageSpeedKilometersPerHour = start?.averageSpeed?.takeIf { it > 0 }
                        ?: end?.averageSpeed?.takeIf { it > 0 },
                    vendorStatus = status,
                ),
            ),
        )
    }

    override fun onServiceAreaUpdate(infos: Array<AMapServiceAreaInfo>?) {
        emit(
            OfficialNavigationEvent.ServiceAreas(
                infos.orEmpty()
                    .filter { it.remainDist >= 0 }
                    .map { info ->
                        OfficialServiceArea(
                            name = info.name?.takeIf(String::isNotBlank),
                            remainingDistanceMeters = info.remainDist,
                            remainingDurationSeconds = info.remainTime.takeIf { it >= 0 },
                            vendorType = info.type,
                        )
                    },
            ),
        )
    }
    override fun notifyParallelRoad(type: Int) = Unit
    override fun OnUpdateTrafficFacility(infos: Array<AMapNaviTrafficFacilityInfo>?) {
        emitTrafficFacilities(infos.orEmpty().asList())
    }

    override fun OnUpdateTrafficFacility(info: AMapNaviTrafficFacilityInfo?) {
        emitTrafficFacilities(listOfNotNull(info))
    }
    override fun updateAimlessModeStatistics(stat: AimLessModeStat?) = Unit
    override fun updateAimlessModeCongestionInfo(info: AimLessModeCongestionInfo?) = Unit
    override fun onPlayRing(type: Int) = Unit
    override fun onNaviRouteNotify(data: AMapNaviRouteNotifyData?) {
        data ?: return
        emit(
            OfficialNavigationEvent.RouteNotice(
                OfficialRouteNotice(
                    message = data.reason?.takeIf(String::isNotBlank),
                    subTitle = data.subTitle?.takeIf(String::isNotBlank),
                    roadName = data.roadName?.takeIf(String::isNotBlank),
                    distanceMeters = data.distance.takeIf { it >= 0 },
                    success = data.isSuccess,
                    vendorType = data.notifyType,
                ),
            ),
        )
    }
    override fun onGpsSignalWeak(weak: Boolean) {
        gpsSignalWeak = weak
        emit(OfficialNavigationEvent.GpsStatus(gpsEnabled, weak))
    }
    override fun onNaviSetting() = Unit
    override fun onNaviMapMode(mode: Int) = Unit
    override fun onNaviTurnClick() = Unit
    override fun onNextRoadClick() = Unit
    override fun onScanViewButtonClick() = Unit
    override fun onLockMap(locked: Boolean) = Unit
    override fun onNaviViewLoaded() = updateNavigationLayout()
    override fun onMapTypeChanged(type: Int) = Unit
    override fun onNaviViewShowMode(mode: Int) = Unit
    override fun onStopSpeaking() = Unit
    override fun onViewTypeChanged(type: AmapPageType?) = Unit
    override fun onStrategyChanged(strategy: Int) = Unit
    override fun onBroadcastModeChanged(mode: Int) = Unit
    override fun onDayAndNightModeChanged(mode: Int) = Unit
    override fun onScaleAutoChanged(auto: Boolean) = Unit
    override fun onListenToVoiceDuringCallChanged(enabled: Boolean) = Unit
    override fun onControlMusicVolumeModeChanged(mode: Int) = Unit
    override fun onEagleChanged(enabled: Boolean) = Unit
    override fun onNaviRouteHighlightChange(pathId: Long, type: Int) = Unit
}

private fun RoutePreference.toAmapStrategy(engine: AMapNavi): Int = engine.strategyConvert(
    this == RoutePreference.AVOID_CONGESTION,
    this == RoutePreference.AVOID_HIGHWAYS,
    this == RoutePreference.AVOID_TOLLS,
    this == RoutePreference.FASTEST,
    true,
)

private class SerialEventDispatcher(
    private val executor: Executor,
    private val consumer: (OfficialNavigationEvent) -> Unit,
) {
    private val lock = Any()
    private val queue = ArrayDeque<OfficialNavigationEvent>()
    private var scheduled = false
    private var closed = false

    fun dispatch(event: OfficialNavigationEvent) {
        val schedule = synchronized(lock) {
            if (closed) return
            queue.addLast(event)
            if (scheduled) false else true.also { scheduled = it }
        }
        if (schedule) {
            runCatching { executor.execute(::drain) }.onFailure {
                synchronized(lock) {
                    queue.clear()
                    scheduled = false
                }
            }
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        queue.clear()
    }

    private fun drain() {
        while (true) {
            val event = synchronized(lock) {
                if (closed || queue.isEmpty()) {
                    scheduled = false
                    null
                } else {
                    queue.removeFirst()
                }
            } ?: return
            runCatching { consumer(event) }
        }
    }
}

private fun <T> failure(
    type: ErrorType,
    message: String,
    rawCode: Int? = null,
    cause: Throwable? = null,
): MapResult<T> = MapResult.Failure(MapError(type, message, rawCode = rawCode, cause = cause))
