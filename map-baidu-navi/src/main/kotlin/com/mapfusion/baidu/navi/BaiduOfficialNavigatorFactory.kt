package com.mapfusion.baidu.navi

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import com.baidu.navisdk.adapter.BNRoutePlanNode
import com.baidu.navisdk.adapter.BNaviCommonParams
import com.baidu.navisdk.adapter.BaiduNaviManagerFactory
import com.baidu.navisdk.adapter.IBNRoutePlanManager
import com.baidu.navisdk.adapter.IBNOuterSettingParams
import com.baidu.navisdk.adapter.IBNaviListener
import com.baidu.navisdk.adapter.struct.BNCameraInfo
import com.baidu.navisdk.adapter.struct.BNGuideConfig
import com.baidu.navisdk.adapter.struct.BNHighwayInfo
import com.baidu.navisdk.adapter.struct.BNHighwayPanelModel
import com.baidu.navisdk.adapter.struct.BNavLineItem
import com.baidu.navisdk.adapter.struct.BNaviEnlargeMapInfo
import com.baidu.navisdk.adapter.struct.BNaviInfo
import com.baidu.navisdk.adapter.struct.BNaviInitConfig
import com.baidu.navisdk.adapter.struct.BNaviLocation
import com.baidu.navisdk.adapter.struct.BNaviMockInfo
import com.baidu.navisdk.adapter.struct.BNRoadCondition
import com.baidu.navisdk.adapter.struct.GuidePanelMessage
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
import com.mapfusion.api.model.OfficialNavigationCapabilities
import com.mapfusion.api.model.OfficialNavigationDayNightMode
import com.mapfusion.api.model.OfficialNavigationEvent
import com.mapfusion.api.model.OfficialNavigationFeature
import com.mapfusion.api.model.OfficialNavigationListener
import com.mapfusion.api.model.OfficialNavigationMode
import com.mapfusion.api.model.OfficialNavigationProgress
import com.mapfusion.api.model.OfficialNavigationRequest
import com.mapfusion.api.model.OfficialNavigationState
import com.mapfusion.api.model.OfficialRouteNotice
import com.mapfusion.api.model.OfficialRouteSummary
import com.mapfusion.api.model.OfficialServiceArea
import com.mapfusion.api.model.OfficialTrafficSegment
import com.mapfusion.api.model.OfficialWaypointProgress
import com.mapfusion.api.model.RoutePreference
import com.mapfusion.api.model.TravelMode
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class BaiduOfficialNavigatorFactory : OfficialNavigatorFactory {
    override val capabilities: OfficialNavigationCapabilities = BAIDU_OFFICIAL_NAVI_CAPABILITIES

    override fun create(activity: Activity, savedState: Bundle?): MapResult<OfficialNavigator> {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return failure(ErrorType.INVALID_PARAM, "百度官方导航会话必须在主线程创建")
        }
        if (!activeSession.compareAndSet(false, true)) {
            return failure(ErrorType.INVALID_PARAM, "同一进程只能创建一个百度官方导航会话")
        }
        return runCatching<OfficialNavigator> {
            BaiduOfficialNavigator(activity) { activeSession.set(false) }
        }.fold(
            onSuccess = { MapResult.Success(it) },
            onFailure = { error ->
                activeSession.set(false)
                failure(ErrorType.UNKNOWN, "百度官方导航初始化失败：${error.message.orEmpty()}", cause = error)
            },
        )
    }

    private companion object {
        val activeSession = AtomicBoolean(false)
    }
}

private val BAIDU_OFFICIAL_NAVI_CAPABILITIES = OfficialNavigationCapabilities(
    provider = Provider.BAIDU,
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
        OfficialNavigationFeature.WAYPOINT_ARRIVAL,
        OfficialNavigationFeature.SERVICE_AREAS,
        OfficialNavigationFeature.WAYPOINT_PROGRESS,
        OfficialNavigationFeature.HIGHWAY_GUIDANCE,
        OfficialNavigationFeature.ROUTE_NOTICES,
        OfficialNavigationFeature.MANUAL_REROUTE,
        OfficialNavigationFeature.ROUTE_OVERVIEW,
        OfficialNavigationFeature.RESTORE_FOLLOWING,
        OfficialNavigationFeature.VOICE_CONTROL,
        OfficialNavigationFeature.DAY_NIGHT_MODE,
    ),
    modes = OfficialNavigationMode.entries.toSet(),
    travelModes = setOf(TravelMode.DRIVING),
    maxWaypoints = 3,
)

internal class BaiduOfficialNavigator(
    private val activity: Activity,
    private val releaseSession: () -> Unit,
) : OfficialNavigator {
    override val capabilities: OfficialNavigationCapabilities = BAIDU_OFFICIAL_NAVI_CAPABILITIES
    override val view: FrameLayout = FrameLayout(activity)

    internal val naviManager = BaiduNaviManagerFactory.getBaiduNaviManager()
    internal val routePlanManager = BaiduNaviManagerFactory.getRoutePlanManager()
    internal val routeGuideManager = BaiduNaviManagerFactory.getRouteGuideManager()
    private val routeHandler = Handler(Looper.getMainLooper(), ::handleRouteMessage)
    private var currentState = OfficialNavigationState.INITIALIZING
    private var latestError: MapError? = null
    private var initialized = false
    private var initializationError: MapError? = null
    private var destroyed = false
    private var guideCreated = false
    private var activityStarted = false
    private var activityResumed = false
    private var routeCount = 0
    private var currentRoadName: String? = null
    private var currentSpeed: Int? = null
    private var remainingDistanceMeters: Int? = null
    private var remainingDurationSeconds: Int? = null
    private var travelledDistanceMeters: Int? = null
    private var rerouting = false
    private var voiceModeBeforeMute = IBNOuterSettingParams.VoiceMode.NOVICE
    private var lastHighwayGuidance: OfficialHighwayGuidance? = null
    private var pendingRequest: OfficialNavigationRequest? = null
    private var listener: OfficialNavigationListener? = null
    private var dispatcher: SerialEventDispatcher? = null

    override val state: OfficialNavigationState get() = currentState
    override val lastError: MapError? get() = latestError
    internal val nativeAccessAvailable: Boolean get() = !destroyed

    init {
        if (naviManager.isInited) {
            initialized = true
            currentState = OfficialNavigationState.READY
        } else {
            val rootPath = activity.getExternalFilesDir(null)?.absolutePath ?: activity.filesDir.absolutePath
            naviManager.init(
                activity.applicationContext,
                BNaviInitConfig.Builder()
                    .sdcardRootPath(rootPath)
                    .naviInitListener(NaviInitListener())
                    .build(),
            )
        }
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
            beginRoutePlan(request)
        }
    }

    override fun selectRoute(index: Int): MapResult<Unit> {
        if (destroyed) return destroyedFailure()
        if (index !in 0 until routeCount) return failure(ErrorType.INVALID_PARAM, "路线下标 $index 超出范围")
        return runCatching {
            if (guideCreated) {
                routeGuideManager.selectRoute(index)
            } else {
                routePlanManager.selectRoute(index) { }
            }
            MapResult.Success(Unit)
        }.getOrElse { error ->
            failure(ErrorType.UNKNOWN, "百度官方导航切换路线失败：${error.message.orEmpty()}", cause = error)
        }
    }

    override fun pause(): MapResult<Unit> {
        if (destroyed) return destroyedFailure()
        if (!guideCreated || state != OfficialNavigationState.NAVIGATING) {
            return failure(ErrorType.INVALID_PARAM, "当前状态 $state 不能暂停导航")
        }
        routeGuideManager.pauseRouteGuide()
        transition(OfficialNavigationState.PAUSED)
        return MapResult.Success(Unit)
    }

    override fun resume(): MapResult<Unit> {
        if (destroyed) return destroyedFailure()
        if (!guideCreated || state != OfficialNavigationState.PAUSED) {
            return failure(ErrorType.INVALID_PARAM, "当前状态 $state 不能继续导航")
        }
        routeGuideManager.resumeRouteGuide()
        transition(OfficialNavigationState.NAVIGATING)
        return MapResult.Success(Unit)
    }

    override fun stop(): MapResult<Unit> {
        if (destroyed) return destroyedFailure()
        pendingRequest = null
        rerouting = false
        lastHighwayGuidance = null
        routePlanManager.removeRequestByHandler(routeHandler)
        if (guideCreated) routeGuideManager.stopNavi()
        transition(OfficialNavigationState.STOPPED)
        return MapResult.Success(Unit)
    }

    override fun recalculateRoute(preference: RoutePreference): MapResult<Unit> {
        activeNavigationFailure("重新规划路线")?.let { return it }
        if (rerouting) return failure(ErrorType.INVALID_PARAM, "百度官方导航正在重新规划路线")
        return runCatching {
            rerouting = true
            emit(OfficialNavigationEvent.Rerouting)
            if (preference == RoutePreference.DEFAULT) {
                routeGuideManager.refreshRoute(object : com.baidu.navisdk.adapter.IBNRouteGuideManager.RefreshRouteListener {
                    override fun onSuccess() = completeManualReroute(success = true)
                    override fun onNoNewRoute() = completeManualReroute(success = true)
                    override fun onFail() = completeManualReroute(
                        success = false,
                        error = MapError(ErrorType.UNKNOWN, "百度官方导航路线刷新失败"),
                    )
                    override fun onRefreshTimeout() = completeManualReroute(
                        success = false,
                        error = MapError(ErrorType.TIMEOUT, "百度官方导航路线刷新超时"),
                    )
                })
            } else {
                routeGuideManager.reCalcRouteWithPrefer(preference.toBaiduPreference())
            }
            MapResult.Success(Unit)
        }.getOrElse { error ->
            rerouting = false
            val mapError = MapError(ErrorType.UNKNOWN, "百度官方导航重新规划路线失败：${error.message.orEmpty()}", cause = error)
            emit(OfficialNavigationEvent.RerouteCompleted(success = false, error = mapError))
            MapResult.Failure(mapError)
        }
    }

    override fun showRouteOverview(): MapResult<Unit> {
        activeNavigationFailure("显示路线全览")?.let { return it }
        routeGuideManager.fullView(true)
        return MapResult.Success(Unit)
    }

    override fun resumeFollowing(): MapResult<Unit> {
        activeNavigationFailure("恢复车标跟随")?.let { return it }
        routeGuideManager.continueNavi()
        return MapResult.Success(Unit)
    }

    override fun setVoiceGuidanceEnabled(enabled: Boolean): MapResult<Unit> {
        if (destroyed) return destroyedFailure()
        return runCatching {
            val settings = BaiduNaviManagerFactory.getProfessionalNaviSettingManager()
            if (!enabled) {
                settings.getVoiceMode(IBNRoutePlanManager.Vehicle.CAR)
                    .takeIf { it != IBNOuterSettingParams.VoiceMode.QUITE }
                    ?.let { voiceModeBeforeMute = it }
            }
            settings.setVoiceMode(
                if (enabled) voiceModeBeforeMute else IBNOuterSettingParams.VoiceMode.QUITE,
                IBNRoutePlanManager.Vehicle.CAR,
            )
            MapResult.Success(Unit)
        }.getOrElse { error ->
            failure(ErrorType.UNKNOWN, "百度官方导航语音设置失败：${error.message.orEmpty()}", cause = error)
        }
    }

    override fun setDayNightMode(mode: OfficialNavigationDayNightMode): MapResult<Unit> {
        if (destroyed) return destroyedFailure()
        val vendorMode = when (mode) {
            OfficialNavigationDayNightMode.AUTO -> IBNOuterSettingParams.DayNightMode.DAY_NIGHT_MODE_AUTO
            OfficialNavigationDayNightMode.DAY -> IBNOuterSettingParams.DayNightMode.DAY_NIGHT_MODE_DAY
            OfficialNavigationDayNightMode.NIGHT -> IBNOuterSettingParams.DayNightMode.DAY_NIGHT_MODE_NIGHT
        }
        return runCatching {
            BaiduNaviManagerFactory.getProfessionalNaviSettingManager().setDayNightMode(
                vendorMode,
                IBNRoutePlanManager.Vehicle.CAR,
            )
            MapResult.Success(Unit)
        }.getOrElse { error ->
            failure(ErrorType.UNKNOWN, "百度官方导航昼夜模式设置失败：${error.message.orEmpty()}", cause = error)
        }
    }

    override fun onStart() {
        activityStarted = true
        if (guideCreated) {
            routeGuideManager.onStart()
            routeGuideManager.onForeground()
        }
    }

    override fun onResume() {
        activityResumed = true
        if (guideCreated) routeGuideManager.onResume()
    }

    override fun onPause() {
        activityResumed = false
        if (guideCreated) routeGuideManager.onPause()
    }

    override fun onStop() {
        activityStarted = false
        if (guideCreated) {
            routeGuideManager.onBackground()
            routeGuideManager.onStop()
        }
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        if (guideCreated) routeGuideManager.onConfigurationChanged(configuration)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        if (guideCreated) routeGuideManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (guideCreated) routeGuideManager.onActivityResult(requestCode, resultCode, data)
    }

    override fun onBackPressed(): Boolean {
        if (!guideCreated) return false
        routeGuideManager.onBackPressed(false)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        guideCreated && routeGuideManager.onKeyDown(keyCode, event)

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        currentState = OfficialNavigationState.DESTROYED
        pendingRequest = null
        rerouting = false
        lastHighwayGuidance = null
        routePlanManager.removeRequestByHandler(routeHandler)
        if (guideCreated) {
            runCatching { routeGuideManager.removeNaviListener(naviListener) }
            runCatching { routeGuideManager.onDestroy(true) }
        }
        view.removeAllViews()
        dispatcher?.close()
        listener = null
        releaseSession()
    }

    private fun validateStart(request: OfficialNavigationRequest): MapError? {
        if (destroyed) return MapError(ErrorType.INVALID_PARAM, "百度官方导航会话已销毁")
        initializationError?.let { return it }
        val route = request.routeRequest
        if (!capabilities.supports(route.mode)) {
            return MapError(ErrorType.UNSUPPORTED, "百度官方车导航只支持 DRIVING")
        }
        if (!capabilities.supports(request.options.navigationMode)) {
            return MapError(ErrorType.UNSUPPORTED, "百度官方导航不支持 ${request.options.navigationMode}")
        }
        if (request.options.routeIndex < 0) return MapError(ErrorType.INVALID_PARAM, "路线下标不能小于 0")
        if (route.waypoints.size > capabilities.maxWaypoints) {
            return MapError(ErrorType.INVALID_PARAM, "百度官方导航最多支持 ${capabilities.maxWaypoints} 个途经点")
        }
        return null
    }

    private fun beginRoutePlan(request: OfficialNavigationRequest): MapResult<Unit> {
        val route = request.routeRequest
        val converted = DefaultCoordinateConverter.convertAll(
            listOf(route.origin) + route.waypoints + route.destination,
            CoordType.BD09,
        )
        val points = when (converted) {
            is MapResult.Success -> converted.data
            is MapResult.Failure -> return converted
        }
        val nodes = points.mapIndexed { index, point ->
            BNRoutePlanNode.Builder()
                .latitude(point.latitude)
                .longitude(point.longitude)
                .name(
                    when (index) {
                        0 -> "起点"
                        points.lastIndex -> "终点"
                        else -> "途经点$index"
                    },
                )
                .build()
        }
        val routeBundle = Bundle().apply {
            putInt(BNaviCommonParams.RoutePlanKey.VEHICLE_TYPE, IBNRoutePlanManager.Vehicle.CAR)
        }
        pendingRequest = request
        routeCount = 0
        currentRoadName = null
        currentSpeed = null
        remainingDistanceMeters = null
        remainingDurationSeconds = null
        travelledDistanceMeters = null
        rerouting = false
        lastHighwayGuidance = null
        transition(OfficialNavigationState.PLANNING)
        routePlanManager.setAvoidBlockedRoad(route.preference == RoutePreference.AVOID_CONGESTION)
        val accepted = routePlanManager.routePlan(
            nodes,
            route.preference.toBaiduPreference(),
            routeBundle,
            routeHandler,
        )
        if (!accepted) {
            val error = MapError(ErrorType.UNKNOWN, "百度官方导航拒绝路线规划请求")
            fail(error)
            return MapResult.Failure(error)
        }
        return MapResult.Success(Unit)
    }

    private fun handleRoutesReady() {
        if (destroyed || state != OfficialNavigationState.PLANNING) return
        val request = pendingRequest ?: return
        val items = routePlanManager.routePlanInfo?.routeTabInfos.orEmpty()
        if (items.isEmpty()) {
            fail(MapError(ErrorType.NO_RESULT, "百度官方导航未返回路线"))
            return
        }
        if (request.options.routeIndex !in items.indices) {
            fail(MapError(ErrorType.INVALID_PARAM, "路线下标 ${request.options.routeIndex} 超出 ${items.size} 条结果"))
            return
        }
        routeCount = items.size
        emit(
            OfficialNavigationEvent.RoutesReady(
                routes = items.mapIndexed { index, item ->
                    OfficialRouteSummary(
                        routeId = index.toString(),
                        distanceMeters = item.length.toInt(),
                        durationSeconds = item.passTime.toInt(),
                        tollCost = item.toll,
                        trafficLightCount = item.lights,
                    )
                },
                selectedRouteIndex = request.options.routeIndex,
            ),
        )
        routePlanManager.selectRoute(request.options.routeIndex) { createGuideView(request) }
    }

    private fun createGuideView(request: OfficialNavigationRequest) {
        if (destroyed || guideCreated) return
        val params = Bundle().apply {
            putBoolean(
                BNaviCommonParams.ProGuideKey.IS_REALNAVI,
                request.options.navigationMode == OfficialNavigationMode.REAL,
            )
            putBoolean(BNaviCommonParams.ProGuideKey.ADD_MAP, true)
            putBoolean(BNaviCommonParams.ProGuideKey.IS_SUPPORT_FULL_SCREEN, true)
        }
        routeGuideManager.setNaviListener(naviListener)
        val guideView = routeGuideManager.onCreate(
            activity,
            BNGuideConfig.Builder().params(params).build(),
        )
        view.removeAllViews()
        view.addView(
            guideView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        guideCreated = true
        if (activityStarted) routeGuideManager.onStart()
        if (activityResumed) routeGuideManager.onResume()
        transition(OfficialNavigationState.NAVIGATING)
    }

    private fun transition(next: OfficialNavigationState) {
        currentState = next
        emit(OfficialNavigationEvent.StateChanged(next))
    }

    private fun fail(error: MapError) {
        latestError = error
        currentState = OfficialNavigationState.FAILED
        emit(OfficialNavigationEvent.Error(error))
        emit(OfficialNavigationEvent.StateChanged(OfficialNavigationState.FAILED))
    }

    private fun emit(event: OfficialNavigationEvent) {
        dispatcher?.dispatch(event)
    }

    private fun emitProgress() {
        val distance = remainingDistanceMeters ?: return
        val duration = remainingDurationSeconds ?: return
        emit(
            OfficialNavigationEvent.Progress(
                OfficialNavigationProgress(
                    currentRoadName = currentRoadName,
                    remainingDistanceMeters = distance,
                    remainingDurationSeconds = duration,
                    currentSpeedKilometersPerHour = currentSpeed,
                    travelledDistanceMeters = travelledDistanceMeters,
                ),
            ),
        )
    }

    private fun destroyedFailure(): MapResult.Failure =
        MapResult.Failure(MapError(ErrorType.INVALID_PARAM, "百度官方导航会话已销毁"))

    private fun activeNavigationFailure(action: String): MapResult.Failure? = when {
        destroyed -> destroyedFailure()
        !guideCreated || (state != OfficialNavigationState.NAVIGATING && state != OfficialNavigationState.PAUSED) ->
            MapResult.Failure(MapError(ErrorType.INVALID_PARAM, "当前状态 $state 不能$action"))
        else -> null
    }

    private fun completeManualReroute(success: Boolean, error: MapError? = null) {
        if (destroyed || !rerouting) return
        rerouting = false
        emit(OfficialNavigationEvent.RerouteCompleted(success = success, error = error))
    }

    private inner class NaviInitListener : com.baidu.navisdk.adapter.IBaiduNaviManager.INaviInitListener {
        override fun onAuthResult(status: Int, message: String?) {
            if (status != 0) {
                val error = MapError(
                    ErrorType.AUTH,
                    "百度官方导航鉴权失败：${message.orEmpty()}",
                    rawCode = status,
                    rawMessage = message,
                )
                initializationError = error
                fail(error)
            }
        }

        override fun initStart() = Unit

        override fun initSuccess() {
            if (destroyed) return
            initializationError?.let { error ->
                fail(error)
                return
            }
            initialized = true
            transition(OfficialNavigationState.READY)
            pendingRequest?.let(::beginRoutePlan)
        }

        override fun initFailed(errorCode: Int) {
            // The engine can report a specific auth failure first and then a generic init failure.
            // Preserve the actionable MCode/package/SHA1 diagnosis for the host application.
            val error = initializationError
                ?: MapError(ErrorType.UNKNOWN, "百度官方导航引擎初始化失败", rawCode = errorCode)
            initializationError = error
            fail(error)
        }
    }

    private fun handleRouteMessage(message: Message): Boolean {
        if (destroyed) return true
        when (message.what) {
            IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_SUCCESS -> handleRoutesReady()
            IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_CANCELED -> fail(
                MapError(ErrorType.CANCELLED, "百度官方导航路线规划已取消"),
            )
            IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_FAILED -> fail(
                MapError(ErrorType.UNKNOWN, "百度官方导航路线规划失败", rawCode = message.arg1),
            )
        }
        return true
    }

    private val naviListener = object : IBNaviListener() {
        override fun onRoadNameUpdate(roadName: String?) {
            currentRoadName = roadName
        }

        override fun onRemainInfoUpdate(distance: Int, time: Int) {
            remainingDistanceMeters = distance
            remainingDurationSeconds = time
            emitProgress()
        }

        override fun onViaListRemainInfoUpdate(distances: IntArray?, times: IntArray?) {
            val distanceValues = distances ?: intArrayOf()
            val timeValues = times ?: intArrayOf()
            emit(
                OfficialNavigationEvent.WaypointProgress(
                    (0 until minOf(distanceValues.size, timeValues.size)).mapNotNull { index ->
                        val distance = distanceValues[index]
                        val duration = timeValues[index]
                        if (distance < 0 || duration < 0) return@mapNotNull null
                        OfficialWaypointProgress(
                            waypointIndex = index,
                            remainingDistanceMeters = distance,
                            remainingDurationSeconds = duration,
                        )
                    },
                ),
            )
        }

        override fun onGuideInfoUpdate(info: BNaviInfo?, message: GuidePanelMessage?) {
            info?.roadName?.takeIf(String::isNotBlank)?.let { currentRoadName = it }
        }

        override fun onLaneInfoUpdate(action: Action?, lanes: MutableList<BNavLineItem>?) {
            emit(
                OfficialNavigationEvent.LaneGuidance(
                    visible = action != Action.HIDE,
                    laneCount = if (action == Action.HIDE) 0 else lanes.orEmpty().size,
                ),
            )
        }

        override fun onEnlargeMapUpdate(info: BNaviEnlargeMapInfo?) {
            emit(OfficialNavigationEvent.JunctionView(info?.action != Action.HIDE))
        }

        override fun onCameraInfoChanged(cameras: MutableList<BNCameraInfo>?) {
            emit(
                OfficialNavigationEvent.CameraAlerts(
                    cameras.orEmpty().map { camera ->
                        OfficialCameraAlert(
                            distanceMeters = camera.dist,
                            speedLimitKilometersPerHour = camera.speedLimit.takeIf { it > 0 },
                            vendorType = camera.type,
                        )
                    },
                ),
            )
        }

        override fun onSpeedUpdate(speed: Int, limit: Int) {
            currentSpeed = speed
            emit(OfficialNavigationEvent.SpeedLimit(speed, limit.takeIf { it > 0 }))
        }

        override fun onOverSpeed(speed: Int, limit: Int) {
            currentSpeed = speed
            emit(OfficialNavigationEvent.SpeedLimit(speed, limit.takeIf { it > 0 }))
        }

        override fun onStartYawing(reason: String?) {
            if (!rerouting) {
                rerouting = true
                emit(OfficialNavigationEvent.Rerouting)
            }
        }

        override fun onYawingSuccess() {
            if (!rerouting) return
            rerouting = false
            emit(OfficialNavigationEvent.RerouteCompleted(success = true))
        }

        override fun onNotificationShow(message: String?) {
            message?.takeIf(String::isNotBlank)?.let { notice ->
                emit(
                    OfficialNavigationEvent.RouteNotice(
                        OfficialRouteNotice(message = notice),
                    ),
                )
            }
        }

        override fun onTravelledDistanceChange(distance: Int) {
            travelledDistanceMeters = distance.takeIf { it >= 0 }
            emitProgress()
        }

        override fun onArriveDestination() {
            currentState = OfficialNavigationState.ARRIVED
            emit(OfficialNavigationEvent.Arrived)
            emit(OfficialNavigationEvent.StateChanged(OfficialNavigationState.ARRIVED))
        }

        override fun onNaviGuideEnd() {
            if (state != OfficialNavigationState.ARRIVED) transition(OfficialNavigationState.STOPPED)
        }

        override fun onArrivedWayPoint(index: Int) {
            emit(OfficialNavigationEvent.WaypointArrived(index))
        }

        override fun onHighWayInfoUpdate(action: Action?, info: BNHighwayInfo?) {
            val areas = if (action == Action.HIDE || info == null) {
                emptyList()
            } else {
                listOfNotNull(
                    info.serviceName?.takeIf(String::isNotBlank)?.let { name ->
                        info.serviceRemainDist.takeIf { it >= 0 }?.let { distance ->
                            OfficialServiceArea(
                                name = name,
                                remainingDistanceMeters = distance,
                            )
                        }
                    },
                    info.service2Name?.takeIf(String::isNotBlank)?.let { name ->
                        info.service2RemainDist.takeIf { it >= 0 }?.let { distance ->
                            OfficialServiceArea(
                                name = name,
                                remainingDistanceMeters = distance,
                            )
                        }
                    },
                )
            }
            emit(OfficialNavigationEvent.ServiceAreas(areas))

            val guidance = if (action == Action.HIDE || info == null) {
                OfficialHighwayGuidance(visible = false)
            } else {
                OfficialHighwayGuidance(
                    visible = true,
                    highwayName = info.highWayName?.takeIf(String::isNotBlank),
                    direction = info.directionText?.takeIf(String::isNotBlank),
                    exitName = info.exitName?.takeIf(String::isNotBlank),
                    exitCode = info.exitCode?.takeIf(String::isNotBlank),
                    exitRemainingDistanceMeters = info.exitRemainDist.takeIf { it >= 0 },
                    tollGateName = info.gateName?.takeIf(String::isNotBlank),
                    tollGateRemainingDistanceMeters = info.gateRemainDist.takeIf { it >= 0 },
                )
            }
            if (guidance != lastHighwayGuidance && (guidance.visible || lastHighwayGuidance != null)) {
                lastHighwayGuidance = guidance
                emit(OfficialNavigationEvent.HighwayGuidance(guidance))
            }
        }

        override fun onHighWayPanelUpdate(action: Action?, info: BNHighwayPanelModel?) = Unit
        override fun onRoadConditionInfoUpdate(distance: Double, items: MutableList<BNRoadCondition>?) {
            var previousEnd = 0
            val segments = items.orEmpty().mapNotNull { item ->
                val end = item.endAddDist
                if (end <= previousEnd) return@mapNotNull null
                OfficialTrafficSegment(
                    lengthMeters = end - previousEnd,
                    vendorStatus = item.type,
                ).also { previousEnd = end }
            }
            emit(
                OfficialNavigationEvent.TrafficStatus(
                    distanceMeters = distance.toInt().takeIf { it >= 0 },
                    segments = segments,
                ),
            )
        }
        override fun onLocationChange(location: BNaviLocation?) = Unit
        override fun onMockInfoUpdate(info: BNaviMockInfo?) = Unit
    }
}

private fun RoutePreference.toBaiduPreference(): Int = when (this) {
    RoutePreference.FASTEST -> IBNRoutePlanManager.RoutePlanPreference.ROUTE_PLAN_PREFERENCE_TIME_FIRST
    RoutePreference.SHORTEST_DISTANCE -> IBNRoutePlanManager.RoutePlanPreference.ROUTE_PLAN_PREFERENCE_DISTANCE_FIRST
    RoutePreference.AVOID_CONGESTION -> IBNRoutePlanManager.RoutePlanPreference.ROUTE_PLAN_PREFERENCE_AVOID_TRAFFIC_JAM
    RoutePreference.AVOID_TOLLS -> IBNRoutePlanManager.RoutePlanPreference.ROUTE_PLAN_PREFERENCE_NOTOLL
    RoutePreference.AVOID_HIGHWAYS -> IBNRoutePlanManager.RoutePlanPreference.ROUTE_PLAN_PREFERENCE_NOHIGHWAY
    else -> IBNRoutePlanManager.RoutePlanPreference.ROUTE_PLAN_PREFERENCE_DEFAULT
}

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
