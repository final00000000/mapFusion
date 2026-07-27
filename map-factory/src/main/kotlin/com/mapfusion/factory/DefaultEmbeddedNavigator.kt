package com.mapfusion.factory

import com.mapfusion.api.async.AndroidMainThreadExecutor
import com.mapfusion.api.async.SharedTimeoutScheduler
import com.mapfusion.api.async.TimeoutHandle
import com.mapfusion.api.async.TimeoutScheduler
import com.mapfusion.api.capability.EmbeddedNavigator
import com.mapfusion.api.capability.LocationClient
import com.mapfusion.api.capability.MapController
import com.mapfusion.api.capability.RoutePlanner
import com.mapfusion.api.coordinate.DefaultCoordinateConverter
import com.mapfusion.api.model.CameraUpdate
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.EmbeddedNavigationEvent
import com.mapfusion.api.model.EmbeddedNavigationListener
import com.mapfusion.api.model.EmbeddedNavigationMode
import com.mapfusion.api.model.EmbeddedNavigationOptions
import com.mapfusion.api.model.EmbeddedNavigationProgress
import com.mapfusion.api.model.EmbeddedNavigationRequest
import com.mapfusion.api.model.EmbeddedNavigationState
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapLocation
import com.mapfusion.api.model.MapMarker
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.MapRouteOverlay
import com.mapfusion.api.model.MarkerOptions
import com.mapfusion.api.model.RoutePath
import com.mapfusion.api.model.RouteRequest
import com.mapfusion.api.model.RouteResult
import com.mapfusion.api.model.RouteStep
import com.mapfusion.api.model.RequestHandle
import java.util.concurrent.Executor
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val CURRENT_MARKER_Z_INDEX = 102f

/**
 * 由 map-api 三项基础能力组合而成的厂商无关内嵌导航实现。
 *
 * 这不是百度/高德官方导航引擎：它负责路线绘制、定位跟随、距离/到达判断和简单偏航
 * 重算。车道级引导、路口放大图、电子眼和厂商语音资源应由可选 navi 模块提供。
 */
internal class DefaultEmbeddedNavigator(
    private val mapController: MapController,
    private val locationClient: LocationClient,
    private val routePlanner: RoutePlanner,
    private val simulationScheduler: TimeoutScheduler = SharedTimeoutScheduler,
    private val simulationExecutor: Executor = AndroidMainThreadExecutor,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : EmbeddedNavigator {

    private val lock = Any()

    @Volatile
    override var state: EmbeddedNavigationState = EmbeddedNavigationState.IDLE
        private set

    private var destroyed = false
    private var generation = 0L
    private var callbackSessionId = 0L
    private var callbackDispatcher: SerialCallbackDispatcher<PendingEvent>? = null
    private var request: EmbeddedNavigationRequest? = null
    private var activeRouteRequest: RouteRequest? = null
    private var listener: EmbeddedNavigationListener? = null
    private var selectedPath: RoutePath? = null
    private var selectedPathIndex = 0
    private var routeOverlay: MapRouteOverlay? = null
    private var currentMarker: MapMarker? = null
    private var routeRequestHandle: RequestHandle? = null
    private var locationRequestHandle: RequestHandle? = null
    private var simulationHandle: TimeoutHandle? = null
    private var simulationPoints: List<LatLng> = emptyList()
    private var simulationDistanceMeters = 0.0
    private var lastRerouteAt = Long.MIN_VALUE
    private var pausedByLifecycle = false
    private var hostPaused = false

    override fun start(
        request: EmbeddedNavigationRequest,
        listener: EmbeddedNavigationListener,
    ): MapResult<Unit> {
        synchronized(lock) {
            rejectedStateLocked()?.let { return it }
        }
        validate(request)?.let { return failure(ErrorType.INVALID_PARAM, it) }
        val supportsMode = try {
            routePlanner.supportsMode(request.routeRequest.mode)
        } catch (error: Throwable) {
            return MapResult.Failure(
                MapError(ErrorType.UNKNOWN, "读取路线能力失败：${error.message.orEmpty()}", cause = error),
            )
        }
        if (!supportsMode) {
            return failure(
                ErrorType.UNSUPPORTED,
                "当前地图厂商不支持${request.routeRequest.mode.canonical().name}内嵌导航",
            )
        }

        val events = mutableListOf<PendingEvent>()
        val detached: DetachedOverlays
        val oldRouteRequest: RequestHandle?
        val oldLocationRequest: RequestHandle?
        val oldSimulation: TimeoutHandle?
        val oldDispatcher: SerialCallbackDispatcher<PendingEvent>?
        val token: Long
        synchronized(lock) {
            rejectedStateLocked()?.let { return it }
            generation++
            token = generation
            callbackSessionId++
            oldDispatcher = callbackDispatcher
            callbackDispatcher = createCallbackDispatcher(request.options)
            this.request = request
            activeRouteRequest = null
            this.listener = listener
            selectedPath = null
            selectedPathIndex = 0
            lastRerouteAt = Long.MIN_VALUE
            simulationPoints = emptyList()
            simulationDistanceMeters = 0.0
            pausedByLifecycle = false
            oldRouteRequest = detachRouteRequestLocked()
            oldLocationRequest = detachLocationRequestLocked()
            oldSimulation = detachSimulationLocked()
            detached = detachOverlaysLocked()
            transitionLocked(EmbeddedNavigationState.PLANNING, events)
        }
        oldDispatcher?.close()
        cancelRequest(oldRouteRequest)?.let {
            queueError(token, cleanupError("旧路线规划取消失败", it), events)
        }
        cancelRequest(oldLocationRequest)?.let {
            queueError(token, cleanupError("旧导航定位取消失败", it), events)
        }
        cancelSimulation(oldSimulation)?.let {
            queueError(token, cleanupError("旧模拟导航任务取消失败", it), events)
        }
        cleanupOverlays(detached)?.let { queueError(token, cleanupError("旧导航覆盖物清理失败", it), events) }
        dispatch(events)
        if (isTokenActive(token, EmbeddedNavigationState.PLANNING)) {
            plan(request.routeRequest, token)
        }
        return MapResult.Success(Unit)
    }

    override fun pause(): MapResult<Unit> = pauseInternal(byLifecycle = false)

    private fun pauseInternal(byLifecycle: Boolean): MapResult<Unit> {
        val events = mutableListOf<PendingEvent>()
        val locationRequest: RequestHandle?
        val simulation: TimeoutHandle?
        val token: Long
        synchronized(lock) {
            if (destroyed) return failure(ErrorType.INVALID_PARAM, "内嵌导航会话已销毁")
            if (state != EmbeddedNavigationState.NAVIGATING) {
                return failure(ErrorType.INVALID_PARAM, "只有导航进行中才能暂停")
            }
            generation++
            token = generation
            pausedByLifecycle = byLifecycle
            locationRequest = detachLocationRequestLocked()
            simulation = detachSimulationLocked()
            transitionLocked(EmbeddedNavigationState.PAUSED, events)
        }
        cancelRequest(locationRequest)?.let { queueError(token, cleanupError("暂停导航定位失败", it), events) }
        cancelSimulation(simulation)?.let { queueError(token, cleanupError("暂停模拟导航失败", it), events) }
        dispatch(events)
        return MapResult.Success(Unit)
    }

    override fun resume(): MapResult<Unit> {
        val events = mutableListOf<PendingEvent>()
        val token: Long
        synchronized(lock) {
            if (destroyed) return failure(ErrorType.INVALID_PARAM, "内嵌导航会话已销毁")
            if (state != EmbeddedNavigationState.PAUSED) {
                return failure(ErrorType.INVALID_PARAM, "只有已暂停的导航才能继续")
            }
            if (hostPaused) return failure(ErrorType.INVALID_PARAM, "宿主仍在后台，不能恢复导航定位")
            generation++
            token = generation
            pausedByLifecycle = false
            transitionLocked(EmbeddedNavigationState.NAVIGATING, events)
        }
        dispatch(events)
        startGuidance(token, emitSimulationCurrent = false)
        return MapResult.Success(Unit)
    }

    override fun stop(): MapResult<Unit> {
        val events = mutableListOf<PendingEvent>()
        val detached: DetachedOverlays
        val routeRequest: RequestHandle?
        val locationRequest: RequestHandle?
        val simulation: TimeoutHandle?
        val token: Long
        synchronized(lock) {
            if (destroyed) return failure(ErrorType.INVALID_PARAM, "内嵌导航会话已销毁")
            generation++
            token = generation
            detached = detachOverlaysLocked()
            selectedPath = null
            activeRouteRequest = null
            request = null
            simulationPoints = emptyList()
            simulationDistanceMeters = 0.0
            pausedByLifecycle = false
            routeRequest = detachRouteRequestLocked()
            locationRequest = detachLocationRequestLocked()
            simulation = detachSimulationLocked()
            transitionLocked(EmbeddedNavigationState.STOPPED, events)
        }
        cancelRequest(routeRequest)?.let { queueError(token, cleanupError("停止路线规划失败", it), events) }
        cancelRequest(locationRequest)?.let { queueError(token, cleanupError("停止导航定位失败", it), events) }
        cancelSimulation(simulation)?.let { queueError(token, cleanupError("停止模拟导航失败", it), events) }
        cleanupOverlays(detached)?.let { queueError(token, cleanupError("导航覆盖物清理失败", it), events) }
        dispatch(events)
        return MapResult.Success(Unit)
    }

    override fun onResume() {
        val shouldResume = synchronized(lock) {
            if (destroyed) return
            hostPaused = false
            state == EmbeddedNavigationState.PAUSED && pausedByLifecycle
        }
        if (shouldResume) resume()
    }

    override fun onPause() {
        val shouldPause = synchronized(lock) {
            if (destroyed) return
            hostPaused = true
            state == EmbeddedNavigationState.NAVIGATING
        }
        if (shouldPause) pauseInternal(byLifecycle = true)
    }

    override fun destroy() {
        val detached: DetachedOverlays
        val routeRequest: RequestHandle?
        val locationRequest: RequestHandle?
        val simulation: TimeoutHandle?
        val dispatcher: SerialCallbackDispatcher<PendingEvent>?
        synchronized(lock) {
            if (destroyed) return
            destroyed = true
            generation++
            callbackSessionId++
            dispatcher = callbackDispatcher.also { callbackDispatcher = null }
            detached = detachOverlaysLocked()
            selectedPath = null
            activeRouteRequest = null
            request = null
            simulationPoints = emptyList()
            simulationDistanceMeters = 0.0
            pausedByLifecycle = false
            routeRequest = detachRouteRequestLocked()
            locationRequest = detachLocationRequestLocked()
            simulation = detachSimulationLocked()
            state = EmbeddedNavigationState.DESTROYED
            listener = null
        }
        dispatcher?.close()
        var firstFailure = cancelRequest(routeRequest)
        cancelRequest(locationRequest)?.let { firstFailure = mergeFailure(firstFailure, it) }
        cancelSimulation(simulation)?.let { firstFailure = mergeFailure(firstFailure, it) }
        cleanupOverlays(detached)?.let { firstFailure = mergeFailure(firstFailure, it) }
        firstFailure?.let { throw it }
    }

    private fun plan(routeRequest: RouteRequest, token: Long) {
        if (!isTokenActive(token, EmbeddedNavigationState.PLANNING, EmbeddedNavigationState.REROUTING)) return
        try {
            val handle = routePlanner.plan(routeRequest) { result -> onRouteResult(routeRequest, result, token) }
            val keep = synchronized(lock) {
                if (isTokenActiveLocked(
                        token,
                        EmbeddedNavigationState.PLANNING,
                        EmbeddedNavigationState.REROUTING,
                    ) && routeRequestHandle == null
                ) {
                    routeRequestHandle = handle
                    true
                } else {
                    false
                }
            }
            if (!keep) cancelRequest(handle)
        } catch (error: Throwable) {
            fail(token, MapError(ErrorType.UNKNOWN, "路线规划调用失败：${error.message.orEmpty()}", cause = error))
        }
    }

    private fun onRouteResult(
        routeRequest: RouteRequest,
        result: MapResult<RouteResult>,
        token: Long,
    ) {
        synchronized(lock) {
            if (!isTokenActiveLocked(token, EmbeddedNavigationState.PLANNING, EmbeddedNavigationState.REROUTING)) {
                return
            }
            routeRequestHandle = null
        }
        if (result is MapResult.Failure) {
            if (result.error.type == ErrorType.CANCELLED) return
            fail(token, result.error)
            return
        }
        result as MapResult.Success
        if (result.data.paths.isEmpty()) {
            fail(token, MapError(ErrorType.NO_RESULT, "路线规划未返回可用路线"))
            return
        }
        val options = synchronized(lock) {
            if (!isTokenActiveLocked(token, EmbeddedNavigationState.PLANNING, EmbeddedNavigationState.REROUTING)) {
                return
            }
            request?.options
        } ?: return
        val index = options.routeIndex.coerceIn(0, result.data.paths.lastIndex)
        val path = result.data.paths[index]
        validateRoutePath(path)?.let {
            fail(token, it)
            return
        }
        val simulatedPoints = if (options.navigationMode == EmbeddedNavigationMode.SIMULATED) {
            when (val result = buildSimulationPoints(path, routeRequest)) {
                is MapResult.Success -> result.data
                is MapResult.Failure -> {
                    fail(token, result.error)
                    return
                }
            }
        } else {
            emptyList()
        }
        val newOverlay = try {
            mapController.addRoute(routeRequest, path, options.routeOverlay)
        } catch (error: Throwable) {
            fail(
                token,
                MapError(ErrorType.UNKNOWN, "路线覆盖物绘制失败：${error.message.orEmpty()}", cause = error),
            )
            return
        }

        val events = mutableListOf<PendingEvent>()
        var stale = false
        var oldOverlay: MapRouteOverlay? = null
        var shouldStartGuidance = false
        synchronized(lock) {
            if (!isTokenActiveLocked(token, EmbeddedNavigationState.PLANNING, EmbeddedNavigationState.REROUTING)) {
                stale = true
            } else {
                oldOverlay = routeOverlay
                routeOverlay = newOverlay
                selectedPath = path
                selectedPathIndex = index
                activeRouteRequest = routeRequest
                simulationPoints = simulatedPoints
                simulationDistanceMeters = 0.0
                queueLocked(EmbeddedNavigationEvent.RouteReady(result.data, index, path), events)
                if (hostPaused) {
                    pausedByLifecycle = true
                    transitionLocked(EmbeddedNavigationState.PAUSED, events)
                } else {
                    transitionLocked(EmbeddedNavigationState.NAVIGATING, events)
                    shouldStartGuidance = true
                }
            }
        }
        if (stale) {
            runCatching { newOverlay.remove() }
            return
        }
        oldOverlay?.let { old ->
            runCatching { old.remove() }.exceptionOrNull()?.let {
                queueError(token, cleanupError("旧路线覆盖物清理失败", it), events)
            }
        }
        dispatch(events)
        if (shouldStartGuidance) startGuidance(token, emitSimulationCurrent = true)
    }

    private fun startGuidance(token: Long, emitSimulationCurrent: Boolean) {
        val mode = synchronized(lock) {
            if (!isTokenActiveLocked(token, EmbeddedNavigationState.NAVIGATING)) return
            request?.options?.navigationMode
        } ?: return
        when (mode) {
            EmbeddedNavigationMode.REAL -> startLocation(token)
            EmbeddedNavigationMode.SIMULATED -> startSimulation(token, emitSimulationCurrent)
        }
    }

    private fun startLocation(token: Long) {
        val options = synchronized(lock) {
            if (!isTokenActiveLocked(token, EmbeddedNavigationState.NAVIGATING)) return
            request?.options?.locationOptions?.copy(onceOnly = false)
        } ?: return
        try {
            val replaced = synchronized(lock) { detachLocationRequestLocked() }
            cancelRequest(replaced)?.let {
                fail(token, cleanupError("旧导航定位取消失败", it))
                return
            }
            val handle = locationClient.startContinuousLocation(options) { result ->
                onLocationResult(result, token)
            }
            val keep = synchronized(lock) {
                if (isTokenActiveLocked(token, EmbeddedNavigationState.NAVIGATING) &&
                    locationRequestHandle == null
                ) {
                    locationRequestHandle = handle
                    true
                } else {
                    false
                }
            }
            // stop()/pause()/reroute 可能恰好发生在原生 start 返回前，必须只取消本次迟到订阅。
            if (!keep) cancelRequest(handle)
        } catch (error: Throwable) {
            fail(
                token,
                MapError(ErrorType.UNKNOWN, "连续定位启动失败：${error.message.orEmpty()}", cause = error),
            )
        }
    }

    private fun startSimulation(token: Long, emitCurrent: Boolean) {
        if (emitCurrent) {
            emitSimulatedLocation(token, advance = false)
        }
        if (isTokenActive(token, EmbeddedNavigationState.NAVIGATING)) {
            scheduleSimulationTick(token)
        }
    }

    private fun scheduleSimulationTick(token: Long) {
        val delayMillis = synchronized(lock) {
            if (!isTokenActiveLocked(token, EmbeddedNavigationState.NAVIGATING) || simulationHandle != null) return
            request?.options?.simulationIntervalMillis
        } ?: return
        try {
            val handle = simulationScheduler.schedule(
                delayMillis,
                Runnable {
                    try {
                        simulationExecutor.execute { onSimulationTick(token) }
                    } catch (error: Throwable) {
                        fail(
                            token,
                            MapError(
                                ErrorType.UNKNOWN,
                                "模拟导航线程切换失败：${error.message.orEmpty()}",
                                cause = error,
                            ),
                        )
                    }
                },
            )
            val keep = synchronized(lock) {
                if (isTokenActiveLocked(token, EmbeddedNavigationState.NAVIGATING) && simulationHandle == null) {
                    simulationHandle = handle
                    true
                } else {
                    false
                }
            }
            if (!keep) cancelSimulation(handle)
        } catch (error: Throwable) {
            fail(
                token,
                MapError(ErrorType.UNKNOWN, "模拟导航调度失败：${error.message.orEmpty()}", cause = error),
            )
        }
    }

    private fun onSimulationTick(token: Long) {
        synchronized(lock) {
            if (!isTokenActiveLocked(token, EmbeddedNavigationState.NAVIGATING)) return
            simulationHandle = null
        }
        emitSimulatedLocation(token, advance = true)
        if (isTokenActive(token, EmbeddedNavigationState.NAVIGATING)) {
            scheduleSimulationTick(token)
        }
    }

    private fun emitSimulatedLocation(token: Long, advance: Boolean) {
        val location = synchronized(lock) {
            if (!isTokenActiveLocked(token, EmbeddedNavigationState.NAVIGATING)) return
            val options = request?.options ?: return
            if (options.navigationMode != EmbeddedNavigationMode.SIMULATED) return
            if (advance) {
                simulationDistanceMeters += options.simulationSpeedMetersPerSecond.toDouble() *
                    options.simulationIntervalMillis / 1_000.0
            }
            createSimulatedLocation(
                points = simulationPoints,
                distanceAlongMeters = simulationDistanceMeters,
                speedMetersPerSecond = options.simulationSpeedMetersPerSecond,
            )
        }
        handleLocation(location, token)
    }

    private fun createSimulatedLocation(
        points: List<LatLng>,
        distanceAlongMeters: Double,
        speedMetersPerSecond: Float,
    ): MapLocation {
        check(points.size >= 2) { "模拟导航路线至少需要 2 个点" }
        var remaining = distanceAlongMeters.coerceAtLeast(0.0)
        var lastBearing = 0f
        points.zipWithNext().forEach { (start, end) ->
            val segmentLength = distanceMeters(start, end)
            if (segmentLength <= 0.0) return@forEach
            lastBearing = bearingDegrees(start, end)
            if (remaining <= segmentLength) {
                val fraction = (remaining / segmentLength).coerceIn(0.0, 1.0)
                return MapLocation(
                    position = LatLng(
                        latitude = start.latitude + (end.latitude - start.latitude) * fraction,
                        longitude = start.longitude + (end.longitude - start.longitude) * fraction,
                        coordType = start.coordType,
                    ),
                    accuracy = 1f,
                    bearing = lastBearing,
                    speed = speedMetersPerSecond,
                    time = runCatching(nowMillis).getOrDefault(0L),
                )
            }
            remaining -= segmentLength
        }
        return MapLocation(
            position = points.last(),
            accuracy = 1f,
            bearing = lastBearing,
            speed = speedMetersPerSecond,
            time = runCatching(nowMillis).getOrDefault(0L),
        )
    }

    private fun onLocationResult(result: MapResult<MapLocation>, token: Long) {
        when (result) {
            is MapResult.Success -> handleLocation(result.data, token)
            is MapResult.Failure -> handleLocationFailure(result.error, token)
        }
    }

    private fun handleLocationFailure(error: MapError, token: Long) {
        if (error.type == ErrorType.CANCELLED) return
        val events = mutableListOf<PendingEvent>()
        var fatal = false
        var locationRequest: RequestHandle? = null
        var simulation: TimeoutHandle? = null
        synchronized(lock) {
            if (!isTokenActiveLocked(token, EmbeddedNavigationState.NAVIGATING)) return
            fatal = error.type == ErrorType.PERMISSION || error.type == ErrorType.AUTH
            if (fatal) {
                generation++
                locationRequest = detachLocationRequestLocked()
                simulation = detachSimulationLocked()
            }
            queueLocked(EmbeddedNavigationEvent.Error(error), events)
            if (fatal) transitionLocked(EmbeddedNavigationState.FAILED, events)
        }
        if (fatal) {
            cancelRequest(locationRequest)
            cancelSimulation(simulation)
        }
        dispatch(events)
    }

    private fun handleLocation(location: MapLocation, token: Long) {
        if (!location.position.isValid()) {
            emitIfActive(token, MapError(ErrorType.INVALID_PARAM, "定位返回了无效经纬度"))
            return
        }
        if (location.position.coordType == CoordType.UNKNOWN) {
            emitIfActive(token, MapError(ErrorType.INVALID_PARAM, "定位结果必须明确声明坐标系"))
            return
        }
        val snapshot = synchronized(lock) {
            if (!isTokenActiveLocked(token, EmbeddedNavigationState.NAVIGATING)) return
            val currentRequest = request ?: return
            val route = activeRouteRequest ?: currentRequest.routeRequest
            val path = selectedPath ?: return
            ProgressSnapshot(currentRequest, route, path)
        }
        val calculation = when (
            val result = calculateProgress(
                location = location,
                path = snapshot.path,
                routeRequest = snapshot.activeRoute,
                offRouteThresholdMeters = snapshot.request.options.offRouteThresholdMeters,
                navigationMode = snapshot.request.options.navigationMode,
            )
        ) {
            is MapResult.Success -> result.data
            is MapResult.Failure -> {
                emitIfActive(token, result.error)
                return
            }
        }
        val progress = calculation.progress
        val now = if (progress.offRoute && snapshot.request.options.autoReroute) {
            runCatching(nowMillis).getOrDefault(0L)
        } else {
            0L
        }

        val events = mutableListOf<PendingEvent>()
        var eventToken = token
        var routeRequestToCancel: RequestHandle? = null
        var locationRequestToCancel: RequestHandle? = null
        var simulationToCancel: TimeoutHandle? = null
        var reroute: RouteRequest? = null
        synchronized(lock) {
            if (!isTokenActiveLocked(token, EmbeddedNavigationState.NAVIGATING)) return
            val arrived = calculation.distanceToDestinationMeters <=
                snapshot.request.options.arrivalThresholdMeters
            val canReroute = !arrived && snapshot.request.options.autoReroute && progress.offRoute &&
                (lastRerouteAt == Long.MIN_VALUE ||
                    now - lastRerouteAt >= snapshot.request.options.rerouteCooldownMillis)
            if (arrived || canReroute) {
                generation++
                eventToken = generation
                routeRequestToCancel = detachRouteRequestLocked()
                locationRequestToCancel = detachLocationRequestLocked()
                simulationToCancel = detachSimulationLocked()
            }
            queueLocked(EmbeddedNavigationEvent.Progress(progress), events)
            when {
                arrived -> {
                    transitionLocked(EmbeddedNavigationState.ARRIVED, events)
                    queueLocked(EmbeddedNavigationEvent.Arrived(progress), events)
                }

                canReroute -> {
                    lastRerouteAt = now
                    reroute = calculation.normalizedRouteRequest.copy(
                        origin = location.position,
                        waypoints = emptyList(),
                    )
                    transitionLocked(EmbeddedNavigationState.REROUTING, events)
                    queueLocked(EmbeddedNavigationEvent.RerouteStarted(location), events)
                }
            }
        }

        cancelRequest(routeRequestToCancel)?.let {
            queueError(eventToken, cleanupError("旧路线规划取消失败", it), events)
        }
        cancelRequest(locationRequestToCancel)?.let {
            queueError(eventToken, cleanupError("导航定位取消失败", it), events)
        }
        cancelSimulation(simulationToCancel)?.let {
            queueError(eventToken, cleanupError("模拟导航任务取消失败", it), events)
        }
        renderLocation(location, snapshot.request.options, eventToken)
        dispatch(events)
        reroute?.let { plan(it, eventToken) }
    }

    private fun renderLocation(location: MapLocation, options: EmbeddedNavigationOptions, token: Long) {
        try {
            if (options.showCurrentMarker) updateCurrentMarker(location, options, token)
            if (options.followLocation && isDisplayTokenActive(token)) {
                mapController.moveCamera(
                    CameraUpdate(
                        target = location.position,
                        zoom = options.followZoom,
                        bearing = if (options.rotateWithBearing && location.bearing.isFinite()) {
                            location.bearing
                        } else {
                            null
                        },
                        animated = true,
                    ),
                )
            }
        } catch (error: Throwable) {
            emitIfActive(
                token,
                MapError(ErrorType.UNKNOWN, "当前位置覆盖物更新失败：${error.message.orEmpty()}", cause = error),
            )
        }
    }

    private fun updateCurrentMarker(
        location: MapLocation,
        options: EmbeddedNavigationOptions,
        token: Long,
    ) {
        val existing = synchronized(lock) { currentMarker }
        if (existing != null) {
            existing.position = location.position
            return
        }
        val created = mapController.addMarker(
            MarkerOptions(
                position = location.position,
                title = "当前位置",
                icon = options.currentMarkerIcon,
                zIndex = CURRENT_MARKER_Z_INDEX,
                tag = "map-fusion-embedded-navigation-current",
            ),
        )
        var keep = false
        synchronized(lock) {
            if (isDisplayTokenActiveLocked(token) && currentMarker == null) {
                currentMarker = created
                keep = true
            }
        }
        if (!keep) runCatching { created.remove() }
    }

    private fun fail(token: Long, error: MapError) {
        val events = mutableListOf<PendingEvent>()
        val routeRequest: RequestHandle?
        val locationRequest: RequestHandle?
        val simulation: TimeoutHandle?
        synchronized(lock) {
            if (!isTokenActiveLocked(
                    token,
                    EmbeddedNavigationState.PLANNING,
                    EmbeddedNavigationState.REROUTING,
                    EmbeddedNavigationState.NAVIGATING,
                )
            ) {
                return
            }
            generation++
            routeRequest = detachRouteRequestLocked()
            locationRequest = detachLocationRequestLocked()
            simulation = detachSimulationLocked()
            transitionLocked(EmbeddedNavigationState.FAILED, events)
            queueLocked(EmbeddedNavigationEvent.Error(error), events)
        }
        cancelRequest(routeRequest)
        cancelRequest(locationRequest)
        cancelSimulation(simulation)
        dispatch(events)
    }

    private fun calculateProgress(
        location: MapLocation,
        path: RoutePath,
        routeRequest: RouteRequest,
        offRouteThresholdMeters: Double,
        navigationMode: EmbeddedNavigationMode,
    ): MapResult<ProgressCalculation> {
        // 定位结果是本次测量的坐标基准；路线返回值可能来自另一厂商坐标系。
        val target = location.position.coordType
        val normalizedRouteRequest = when (val result = normalizeRouteRequest(routeRequest, target)) {
            is MapResult.Success -> result.data
            is MapResult.Failure -> return result
        }
        val normalizedPath = when (val result = normalizeRoutePath(path, target)) {
            is MapResult.Success -> result.data
            is MapResult.Failure -> return result
        }
        val points = pathPoints(normalizedPath, normalizedRouteRequest)
        val projection = nearestProjection(location.position, points)
        val geometricLength = points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }
        val routeLength = path.distanceMeters.coerceAtLeast(0).toDouble()
        val ratio = if (geometricLength > 0.0) {
            (projection.alongMeters / geometricLength).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val along = if (routeLength > 0.0) routeLength * ratio else projection.alongMeters
        val remaining = (routeLength - along).coerceAtLeast(0.0)
        val duration = if (routeLength > 0.0) {
            (path.durationSeconds * (remaining / routeLength)).toInt().coerceAtLeast(0)
        } else {
            path.durationSeconds.coerceAtLeast(0)
        }
        val stepIndex = findStepIndex(normalizedPath.steps, projection.alongMeters, geometricLength)
        return MapResult.Success(
            ProgressCalculation(
                progress = EmbeddedNavigationProgress(
                    location = location,
                    path = path,
                    currentStepIndex = stepIndex,
                    currentInstruction = path.steps.getOrNull(stepIndex)?.instruction,
                    distanceToRouteMeters = projection.distanceMeters,
                    distanceAlongRouteMeters = along,
                    remainingDistanceMeters = remaining,
                    remainingDurationSeconds = duration,
                    offRoute = projection.distanceMeters > offRouteThresholdMeters,
                    navigationMode = navigationMode,
                ),
                distanceToDestinationMeters = distanceMeters(
                    location.position,
                    normalizedRouteRequest.destination,
                ),
                normalizedRouteRequest = normalizedRouteRequest,
            ),
        )
    }

    private fun normalizeRouteRequest(request: RouteRequest, target: CoordType): MapResult<RouteRequest> {
        val normalized = when (
            val result = normalizePoints(
                listOf(request.origin, request.destination) + request.waypoints,
                target,
            )
        ) {
            is MapResult.Success -> result.data
            is MapResult.Failure -> return result
        }
        return MapResult.Success(
            request.copy(
                origin = normalized[0],
                destination = normalized[1],
                waypoints = normalized.drop(2),
            ),
        )
    }

    private fun normalizeRoutePath(path: RoutePath, target: CoordType): MapResult<RoutePath> {
        val polyline = when (val result = normalizePoints(path.polyline, target)) {
            is MapResult.Success -> result.data
            is MapResult.Failure -> return result
        }
        val steps = ArrayList<RouteStep>(path.steps.size)
        path.steps.forEach { step ->
            val stepPolyline = when (val result = normalizePoints(step.polyline, target)) {
                is MapResult.Success -> result.data
                is MapResult.Failure -> return result
            }
            steps += step.copy(polyline = stepPolyline)
        }
        return MapResult.Success(path.copy(steps = steps, polyline = polyline))
    }

    private fun normalizePoints(points: List<LatLng>, target: CoordType): MapResult<List<LatLng>> {
        val normalized = ArrayList<LatLng>(points.size)
        points.forEach { point ->
            when (val result = DefaultCoordinateConverter.convert(point, target)) {
                is MapResult.Success -> normalized += result.data
                is MapResult.Failure -> return result
            }
        }
        return MapResult.Success(normalized)
    }

    private fun buildSimulationPoints(
        path: RoutePath,
        routeRequest: RouteRequest,
    ): MapResult<List<LatLng>> {
        val points = pathPoints(path, routeRequest)
        if (points.size < 2) {
            return MapResult.Failure(
                MapError(ErrorType.INVALID_PARAM, "模拟导航路线至少需要 2 个点"),
            )
        }
        val target = points.first().coordType
        if (target == CoordType.UNKNOWN) {
            return MapResult.Failure(
                MapError(ErrorType.INVALID_PARAM, "模拟导航路线必须明确声明坐标系"),
            )
        }
        return normalizePoints(points, target)
    }

    private fun validateRoutePath(path: RoutePath): MapError? {
        val points = path.polyline + path.steps.flatMap(RouteStep::polyline)
        if (points.any { !it.isValid() }) {
            return MapError(ErrorType.INVALID_PARAM, "路线结果包含无效经纬度")
        }
        if (points.any { it.coordType == CoordType.UNKNOWN }) {
            return MapError(ErrorType.INVALID_PARAM, "路线结果中的坐标必须明确声明坐标系")
        }
        return null
    }

    private fun pathPoints(path: RoutePath, routeRequest: RouteRequest): List<LatLng> {
        val direct = path.polyline.filter { it.isValid() }
        if (direct.size >= 2) return direct
        val steps = path.steps.flatMap(RouteStep::polyline).filter { it.isValid() }
        if (steps.size >= 2) return steps
        return listOf(routeRequest.origin, routeRequest.destination).filter { it.isValid() }
    }

    private fun findStepIndex(steps: List<RouteStep>, alongGeometry: Double, geometryLength: Double): Int {
        if (steps.isEmpty() || geometryLength <= 0.0) return 0
        var cursor = 0.0
        steps.forEachIndexed { index, step ->
            val stepLength = step.polyline.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }
                .takeIf { it > 0.0 }
                ?: step.distanceMeters.coerceAtLeast(0).toDouble()
            cursor += stepLength
            if (alongGeometry <= cursor) return index
        }
        return steps.lastIndex
    }

    private fun nearestProjection(point: LatLng, points: List<LatLng>): Projection {
        if (points.isEmpty()) return Projection(Double.POSITIVE_INFINITY, 0.0)
        if (points.size == 1) return Projection(distanceMeters(point, points.single()), 0.0)
        var best = Projection(Double.POSITIVE_INFINITY, 0.0)
        var cursor = 0.0
        points.zipWithNext().forEach { (start, end) ->
            val segmentLength = distanceMeters(start, end)
            val projected = project(point, start, end)
            val candidate = Projection(
                distanceMeters(point, projected.point),
                cursor + segmentLength * projected.fraction,
            )
            if (candidate.distanceMeters < best.distanceMeters) best = candidate
            cursor += segmentLength
        }
        return best
    }

    private fun project(point: LatLng, start: LatLng, end: LatLng): ProjectedPoint {
        val scale = 111_320.0
        val lat = Math.toRadians((start.latitude + end.latitude + point.latitude) / 3.0)
        val xScale = scale * cos(lat).coerceAtLeast(0.01)
        val yScale = scale
        val sx = start.longitude * xScale
        val sy = start.latitude * yScale
        val ex = end.longitude * xScale
        val ey = end.latitude * yScale
        val px = point.longitude * xScale
        val py = point.latitude * yScale
        val dx = ex - sx
        val dy = ey - sy
        val lengthSquared = dx * dx + dy * dy
        val fraction = if (lengthSquared <= 0.0) 0.0 else {
            (((px - sx) * dx + (py - sy) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        }
        return ProjectedPoint(
            LatLng(
                latitude = start.latitude + (end.latitude - start.latitude) * fraction,
                longitude = start.longitude + (end.longitude - start.longitude) * fraction,
                coordType = start.coordType,
            ),
            fraction,
        )
    }

    private fun rejectedStateLocked(): MapResult.Failure? = when {
        destroyed -> failure(ErrorType.INVALID_PARAM, "内嵌导航会话已销毁")
        state in ACTIVE_STATES -> failure(ErrorType.INVALID_PARAM, "已有导航任务，请先停止当前会话")
        else -> null
    }

    private fun detachOverlaysLocked(): DetachedOverlays {
        val detached = DetachedOverlays(routeOverlay, currentMarker)
        routeOverlay = null
        currentMarker = null
        return detached
    }

    private fun detachRouteRequestLocked(): RequestHandle? =
        routeRequestHandle.also { routeRequestHandle = null }

    private fun detachLocationRequestLocked(): RequestHandle? =
        locationRequestHandle.also { locationRequestHandle = null }

    private fun detachSimulationLocked(): TimeoutHandle? =
        simulationHandle.also { simulationHandle = null }

    private fun cleanupOverlays(detached: DetachedOverlays): Throwable? {
        var failure: Throwable? = null
        try {
            detached.route?.remove()
        } catch (error: Throwable) {
            failure = mergeFailure(failure, error)
        }
        try {
            detached.current?.remove()
        } catch (error: Throwable) {
            failure = mergeFailure(failure, error)
        }
        return failure
    }

    private fun cancelRequest(handle: RequestHandle?): Throwable? = try {
        handle?.cancel()
        null
    } catch (error: Throwable) {
        error
    }

    private fun cancelSimulation(handle: TimeoutHandle?): Throwable? = try {
        handle?.cancel()
        null
    } catch (error: Throwable) {
        error
    }

    private fun transitionLocked(next: EmbeddedNavigationState, events: MutableList<PendingEvent>) {
        state = next
        queueLocked(EmbeddedNavigationEvent.StateChanged(next), events)
    }

    private fun queueLocked(event: EmbeddedNavigationEvent, events: MutableList<PendingEvent>) {
        if (destroyed) return
        val target = listener ?: return
        val dispatcher = callbackDispatcher ?: return
        events += PendingEvent(callbackSessionId, target, event, dispatcher)
    }

    private fun queueError(token: Long, error: MapError, events: MutableList<PendingEvent>) {
        synchronized(lock) {
            if (destroyed || token != generation) return
            queueLocked(EmbeddedNavigationEvent.Error(error), events)
        }
    }

    private fun emitIfActive(token: Long, error: MapError) {
        val events = mutableListOf<PendingEvent>()
        queueError(token, error, events)
        dispatch(events)
    }

    private fun dispatch(events: List<PendingEvent>) {
        events.groupBy { it.dispatcher }.forEach { (dispatcher, batch) ->
            dispatcher.dispatch(batch)
        }
    }

    private fun createCallbackDispatcher(
        options: EmbeddedNavigationOptions,
    ): SerialCallbackDispatcher<PendingEvent> =
        SerialCallbackDispatcher(options.callbackExecutor ?: AndroidMainThreadExecutor) { pending ->
            val active = synchronized(lock) {
                !destroyed && pending.sessionId == callbackSessionId && listener === pending.listener
            }
            if (active) pending.listener.onEvent(pending.event)
        }

    private fun isTokenActive(token: Long, vararg allowedStates: EmbeddedNavigationState): Boolean =
        synchronized(lock) { isTokenActiveLocked(token, *allowedStates) }

    private fun isTokenActiveLocked(token: Long, vararg allowedStates: EmbeddedNavigationState): Boolean =
        !destroyed && token == generation && state in allowedStates

    private fun isDisplayTokenActive(token: Long): Boolean = synchronized(lock) {
        isDisplayTokenActiveLocked(token)
    }

    private fun isDisplayTokenActiveLocked(token: Long): Boolean =
        !destroyed && token == generation && state in DISPLAY_STATES

    private fun failure(type: ErrorType, message: String): MapResult.Failure =
        MapResult.Failure(MapError(type, message))

    private fun validate(request: EmbeddedNavigationRequest): String? {
        val route = request.routeRequest
        if (!route.origin.isValid() || !route.destination.isValid() || route.waypoints.any { !it.isValid() }) {
            return "起点、终点或途经点经纬度无效"
        }
        if (route.origin.coordType == CoordType.UNKNOWN || route.destination.coordType == CoordType.UNKNOWN ||
            route.waypoints.any { it.coordType == CoordType.UNKNOWN }
        ) {
            return "起点、终点和途经点必须明确声明坐标系"
        }
        val options = request.options
        if (options.routeIndex < 0) return "路线下标不能小于 0"
        if (!options.arrivalThresholdMeters.isFinite() || options.arrivalThresholdMeters <= 0.0) {
            return "到达判定距离必须大于 0"
        }
        if (!options.offRouteThresholdMeters.isFinite() || options.offRouteThresholdMeters <= 0.0) {
            return "偏航判定距离必须大于 0"
        }
        if (options.rerouteCooldownMillis < 0L) return "偏航重算冷却时间不能小于 0"
        if (options.followZoom?.isFinite() == false) return "导航跟随缩放级别无效"
        if (!options.simulationSpeedMetersPerSecond.isFinite() ||
            options.simulationSpeedMetersPerSecond <= 0f
        ) {
            return "模拟导航速度必须大于 0"
        }
        if (options.simulationIntervalMillis <= 0L) return "模拟导航刷新间隔必须大于 0"
        if (!options.routeOverlay.lineWidth.isFinite() || options.routeOverlay.lineWidth <= 0f) {
            return "导航路线宽度必须大于 0"
        }
        return null
    }

    private fun LatLng.isValid(): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    private data class PendingEvent(
        val sessionId: Long,
        val listener: EmbeddedNavigationListener,
        val event: EmbeddedNavigationEvent,
        val dispatcher: SerialCallbackDispatcher<PendingEvent>,
    )

    private data class DetachedOverlays(val route: MapRouteOverlay?, val current: MapMarker?)

    private data class ProgressSnapshot(
        val request: EmbeddedNavigationRequest,
        val activeRoute: RouteRequest,
        val path: RoutePath,
    )

    private data class ProgressCalculation(
        val progress: EmbeddedNavigationProgress,
        val distanceToDestinationMeters: Double,
        val normalizedRouteRequest: RouteRequest,
    )

    private data class Projection(val distanceMeters: Double, val alongMeters: Double)

    private data class ProjectedPoint(val point: LatLng, val fraction: Double)

    private companion object {
        val ACTIVE_STATES = setOf(
            EmbeddedNavigationState.PLANNING,
            EmbeddedNavigationState.NAVIGATING,
            EmbeddedNavigationState.PAUSED,
            EmbeddedNavigationState.REROUTING,
        )
        val DISPLAY_STATES = setOf(
            EmbeddedNavigationState.NAVIGATING,
            EmbeddedNavigationState.REROUTING,
            EmbeddedNavigationState.ARRIVED,
        )
    }
}

private fun cleanupError(prefix: String, error: Throwable) = MapError(
    ErrorType.UNKNOWN,
    "$prefix：${error.message.orEmpty()}",
    cause = error,
)

private fun mergeFailure(first: Throwable?, next: Throwable): Throwable {
    if (first == null) return next
    if (first !== next) first.addSuppressed(next)
    return first
}

private fun distanceMeters(from: LatLng, to: LatLng): Double {
    require(from.coordType != CoordType.UNKNOWN && from.coordType == to.coordType) {
        "距离计算前必须将坐标归一到同一明确坐标系"
    }
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val deltaLat = lat2 - lat1
    val deltaLng = Math.toRadians(to.longitude - from.longitude)
    val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
        cos(lat1) * cos(lat2) * sin(deltaLng / 2) * sin(deltaLng / 2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

private fun bearingDegrees(from: LatLng, to: LatLng): Float {
    require(from.coordType != CoordType.UNKNOWN && from.coordType == to.coordType) {
        "方位角计算前必须将坐标归一到同一明确坐标系"
    }
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val deltaLongitude = Math.toRadians(to.longitude - from.longitude)
    val y = sin(deltaLongitude) * cos(lat2)
    val x = cos(lat1) * sin(lat2) -
        sin(lat1) * cos(lat2) * cos(deltaLongitude)
    return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
}
