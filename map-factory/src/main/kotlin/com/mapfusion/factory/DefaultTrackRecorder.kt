package com.mapfusion.factory

import com.mapfusion.api.async.AndroidMainThreadExecutor
import com.mapfusion.api.capability.LocationClient
import com.mapfusion.api.capability.MapController
import com.mapfusion.api.capability.TrackListener
import com.mapfusion.api.capability.TrackRecorder
import com.mapfusion.api.capability.TrackState
import com.mapfusion.api.model.CameraUpdate
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapLocation
import com.mapfusion.api.model.MapMarker
import com.mapfusion.api.model.MapPolyline
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.MarkerOptions
import com.mapfusion.api.model.PolylineOptions
import com.mapfusion.api.model.RequestHandle
import com.mapfusion.api.model.TrackOptions
import com.mapfusion.api.model.TrackSnapshot
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0

/** LocationClient + MapController 的厂商无关轨迹实现。 */
internal class DefaultTrackRecorder(
    private val locationClient: LocationClient,
    private val mapController: MapController?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : TrackRecorder {

    private val points = mutableListOf<MapLocation>()
    private var options = TrackOptions()
    private var listener: TrackListener? = null
    private var distanceMeters = 0.0
    private var rejectedPointCount = 0
    private var startedAtMillis: Long? = null
    private var endedAtMillis: Long? = null
    private var activeSegmentStartedAt: Long? = null
    private var accumulatedActiveMillis = 0L
    private var trackPolyline: MapPolyline? = null
    private var currentMarker: MapMarker? = null
    private var destroyed = false
    private var generation = 0L
    private var callbackSessionId = 0L
    private var callbackDispatcher: SerialCallbackDispatcher<PendingCallback>? = null
    private var locationRequestHandle: RequestHandle? = null

    @Volatile
    override var state: TrackState = TrackState.IDLE
        private set

    override fun start(options: TrackOptions, listener: TrackListener) {
        val validationError = options.validationError()
        val rejection = synchronized(this) {
            when {
                destroyed -> MapError(ErrorType.INVALID_PARAM, "轨迹组件已销毁")
                validationError != null -> MapError(ErrorType.INVALID_PARAM, validationError)
                state == TrackState.RECORDING || state == TrackState.PAUSED ->
                    MapError(ErrorType.INVALID_PARAM, "轨迹已开始，请先结束或清除当前轨迹")

                else -> null
            }
        }
        if (rejection != null) {
            dispatchRejected(options, listener, rejection)
            return
        }

        val events = mutableListOf<PendingCallback>()
        var oldDispatcher: SerialCallbackDispatcher<PendingCallback>? = null
        var staleLocationRequest: RequestHandle? = null
        var token = 0L
        var raceRejection: MapError? = null
        synchronized(this) {
            // 通过第二次检查处理两个 start() 并发进入的情况。
            if (destroyed || state == TrackState.RECORDING || state == TrackState.PAUSED) {
                raceRejection = MapError(ErrorType.INVALID_PARAM, "轨迹状态已变化，无法开始新轨迹")
            } else {
                callbackSessionId++
                oldDispatcher = callbackDispatcher
                callbackDispatcher = createCallbackDispatcher(options)
                generation++
                token = generation
                staleLocationRequest = locationRequestHandle.also { locationRequestHandle = null }
                removeTrackOverlays()
                points.clear()
                distanceMeters = 0.0
                rejectedPointCount = 0
                accumulatedActiveMillis = 0L
                val now = nowMillis()
                startedAtMillis = now
                endedAtMillis = null
                activeSegmentStartedAt = now
                this.options = options
                this.listener = listener
                state = TrackState.RECORDING
                queueStateLocked(snapshotLocked(now), events)
            }
        }
        raceRejection?.let {
            dispatchRejected(options, listener, it)
            return
        }
        oldDispatcher?.close()
        dispatch(events)
        cancelRequest(staleLocationRequest)?.let { emitError(cancelError("旧轨迹定位取消失败", it)) }
        startNativeLocation(token)
    }

    override fun pause() {
        val events = mutableListOf<PendingCallback>()
        var locationRequest: RequestHandle? = null
        var accepted = false
        synchronized(this) {
            if (destroyed || state != TrackState.RECORDING) {
                queueErrorLocked(MapError(ErrorType.INVALID_PARAM, "只有记录中的轨迹可以暂停"), events)
            } else {
                accepted = true
                generation++
                locationRequest = locationRequestHandle.also { locationRequestHandle = null }
                closeActiveSegment(nowMillis())
                state = TrackState.PAUSED
                queueStateLocked(snapshotLocked(), events)
            }
        }
        dispatch(events)
        if (!accepted) return
        cancelRequest(locationRequest)?.let { emitError(cancelError("暂停轨迹定位失败", it)) }
    }

    override fun resume() {
        val events = mutableListOf<PendingCallback>()
        var staleLocationRequest: RequestHandle? = null
        var token = 0L
        var accepted = false
        synchronized(this) {
            if (destroyed || state != TrackState.PAUSED) {
                queueErrorLocked(MapError(ErrorType.INVALID_PARAM, "只有已暂停的轨迹可以继续"), events)
            } else {
                accepted = true
                generation++
                token = generation
                staleLocationRequest = locationRequestHandle.also { locationRequestHandle = null }
                activeSegmentStartedAt = nowMillis()
                state = TrackState.RECORDING
                queueStateLocked(snapshotLocked(), events)
            }
        }
        dispatch(events)
        if (!accepted) return
        cancelRequest(staleLocationRequest)?.let { emitError(cancelError("旧轨迹定位取消失败", it)) }
        startNativeLocation(token)
    }

    override fun stop(): TrackSnapshot {
        val events = mutableListOf<PendingCallback>()
        var locationRequest: RequestHandle? = null
        val snapshot: TrackSnapshot
        synchronized(this) {
            if (!destroyed && (state == TrackState.RECORDING || state == TrackState.PAUSED)) {
                generation++
                locationRequest = locationRequestHandle.also { locationRequestHandle = null }
                val now = nowMillis()
                closeActiveSegment(now)
                endedAtMillis = now
                state = TrackState.STOPPED
                snapshot = snapshotLocked(now)
                queueStateLocked(snapshot, events)
            } else {
                snapshot = snapshotLocked()
            }
        }
        dispatch(events)
        cancelRequest(locationRequest)?.let { emitError(cancelError("停止轨迹定位失败", it)) }
        return snapshot
    }

    @Synchronized
    override fun snapshot(): TrackSnapshot = snapshotLocked()

    override fun clear() {
        val events = mutableListOf<PendingCallback>()
        val locationRequest: RequestHandle?
        val sessionId: Long
        synchronized(this) {
            if (destroyed) return
            generation++
            sessionId = callbackSessionId
            locationRequest = locationRequestHandle.also { locationRequestHandle = null }
            removeTrackOverlays()
            points.clear()
            distanceMeters = 0.0
            rejectedPointCount = 0
            accumulatedActiveMillis = 0L
            startedAtMillis = null
            endedAtMillis = null
            activeSegmentStartedAt = null
            state = TrackState.IDLE
            queueStateLocked(snapshotLocked(), events)
        }
        cancelRequest(locationRequest)?.let { error ->
            synchronized(this) {
                if (!destroyed && callbackSessionId == sessionId) {
                    queueErrorLocked(cancelError("清理轨迹定位失败", error), events)
                }
            }
        }
        synchronized(this) {
            if (callbackSessionId == sessionId) listener = null
        }
        dispatch(events)
    }

    override fun destroy() {
        val dispatcher: SerialCallbackDispatcher<PendingCallback>?
        val locationRequest: RequestHandle?
        synchronized(this) {
            if (destroyed) return
            // 先标记销毁并关闭派发器，取消定位产生的同步回调也无法重新入队。
            destroyed = true
            generation++
            callbackSessionId++
            dispatcher = callbackDispatcher.also { callbackDispatcher = null }
            locationRequest = locationRequestHandle.also { locationRequestHandle = null }
            listener = null
        }
        dispatcher?.close()

        var firstFailure: Throwable? = null
        fun attempt(block: () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                if (firstFailure == null) {
                    firstFailure = error
                } else if (error !== firstFailure) {
                    firstFailure?.addSuppressed(error)
                }
            }
        }

        attempt { locationRequest?.cancel() }
        attempt { locationClient.destroy() }
        attempt { removeTrackOverlays() }

        val now = runCatching { nowMillis() }.getOrElse { error ->
            if (firstFailure == null) {
                firstFailure = error
            } else if (error !== firstFailure) {
                firstFailure?.addSuppressed(error)
            }
            0L
        }
        synchronized(this) {
            if (state == TrackState.RECORDING) closeActiveSegment(now)
            if (state == TrackState.RECORDING || state == TrackState.PAUSED) {
                endedAtMillis = now
                state = TrackState.STOPPED
            }
            activeSegmentStartedAt = null
        }
        firstFailure?.let { throw it }
    }

    private fun startNativeLocation(token: Long) {
        val handle = try {
            locationClient.startContinuousLocation(
                options.locationOptions.copy(onceOnly = false),
            ) { result ->
                when (result) {
                    is MapResult.Success -> handleLocation(result.data, token)
                    is MapResult.Failure -> handleLocationError(result.error, token)
                }
            }
        } catch (error: Throwable) {
            handleLocationError(
                MapError(ErrorType.UNKNOWN, "轨迹定位启动失败：${error.message.orEmpty()}", cause = error),
                token,
            )
            return
        }
        val keep = synchronized(this) {
            if (!destroyed && generation == token && state == TrackState.RECORDING &&
                locationRequestHandle == null
            ) {
                locationRequestHandle = handle
                true
            } else {
                false
            }
        }
        if (!keep) cancelRequest(handle)
    }

    private fun handleLocation(location: MapLocation, token: Long) {
        val events = mutableListOf<PendingCallback>()
        synchronized(this) {
            if (destroyed || token != generation || state != TrackState.RECORDING) return
            if (!location.position.latitude.isFinite() || !location.position.longitude.isFinite() ||
                location.position.latitude !in -90.0..90.0 || location.position.longitude !in -180.0..180.0
            ) {
                rejectedPointCount++
            } else if (options.maxAccuracyMeters > 0f && location.accuracy > options.maxAccuracyMeters) {
                rejectedPointCount++
            } else {
                val previous = points.lastOrNull()
                if (previous != null && previous.position.coordType != location.position.coordType) {
                    rejectedPointCount++
                    queueErrorLocked(
                        MapError(ErrorType.INVALID_PARAM, "轨迹坐标系中途发生变化"),
                        events,
                    )
                } else {
                    val segmentDistance = previous?.let { distanceBetweenMeters(it, location) } ?: 0.0
                    if (previous != null && options.minPointDistanceMeters > 0.0 &&
                        segmentDistance < options.minPointDistanceMeters
                    ) {
                        rejectedPointCount++
                        runCatching { updateCurrentMarker(location) }.onFailure {
                            queueErrorLocked(
                                MapError(
                                    ErrorType.UNKNOWN,
                                    "轨迹当前位置更新失败：${it.message.orEmpty()}",
                                    cause = it,
                                ),
                                events,
                            )
                        }
                    } else {
                        points += location
                        distanceMeters += segmentDistance
                        renderAcceptedPoint(location)?.let { queueErrorLocked(it, events) }
                        queuePointLocked(snapshotLocked(), events)
                    }
                }
            }
        }
        dispatch(events)
    }

    private fun handleLocationError(error: MapError, token: Long) {
        if (error.type == ErrorType.CANCELLED) return
        val events = mutableListOf<PendingCallback>()
        var locationRequest: RequestHandle? = null
        val sessionId: Long
        synchronized(this) {
            if (destroyed || token != generation) return
            sessionId = callbackSessionId
            if (state == TrackState.RECORDING) {
                generation++
                locationRequest = locationRequestHandle.also { locationRequestHandle = null }
                val now = nowMillis()
                closeActiveSegment(now)
                endedAtMillis = now
                state = TrackState.STOPPED
                queueStateLocked(snapshotLocked(now), events)
            }
        }
        val cancellationFailure = cancelRequest(locationRequest)
        synchronized(this) {
            if (!destroyed && callbackSessionId == sessionId) {
                cancellationFailure?.let { queueErrorLocked(cancelError("轨迹定位停止失败", it), events) }
                queueErrorLocked(error, events)
            }
        }
        dispatch(events)
    }

    private fun renderAcceptedPoint(location: MapLocation): MapError? {
        if (!options.drawOnMap) return null
        val controller = mapController ?: return null
        return runCatching {
            updateCurrentMarker(location)
            val positions = points.map(MapLocation::position)
            if (positions.size >= 2) {
                val polyline = trackPolyline
                if (polyline == null) {
                    trackPolyline = controller.addPolyline(
                        PolylineOptions(
                            points = positions,
                            width = options.polylineWidth,
                            color = options.polylineColor,
                            zIndex = TRACK_Z_INDEX,
                            tag = TRACK_OVERLAY_TAG,
                        ),
                    )
                } else {
                    polyline.points = positions
                }
            }
            if (options.followLocation) {
                controller.moveCamera(
                    CameraUpdate(target = location.position, zoom = options.followZoom, animated = true),
                )
            }
        }.exceptionOrNull()?.let {
            MapError(ErrorType.UNKNOWN, "轨迹覆盖物绘制失败：${it.message.orEmpty()}", cause = it)
        }
    }

    private fun updateCurrentMarker(location: MapLocation) {
        if (!options.drawOnMap || !options.showCurrentMarker) return
        val controller = mapController ?: return
        val marker = currentMarker
        if (marker == null) {
            currentMarker = controller.addMarker(
                MarkerOptions(
                    position = location.position,
                    title = "轨迹当前位置",
                    zIndex = TRACK_Z_INDEX + 1f,
                    tag = TRACK_OVERLAY_TAG,
                ),
            )
        } else {
            marker.position = location.position
        }
    }

    private fun removeTrackOverlays() {
        runCatching { trackPolyline?.remove() }
        runCatching { currentMarker?.remove() }
        trackPolyline = null
        currentMarker = null
    }

    private fun closeActiveSegment(now: Long) {
        activeSegmentStartedAt?.let { accumulatedActiveMillis += (now - it).coerceAtLeast(0L) }
        activeSegmentStartedAt = null
    }

    private fun snapshotLocked(now: Long = nowMillis()): TrackSnapshot {
        val running = activeSegmentStartedAt?.let { (now - it).coerceAtLeast(0L) } ?: 0L
        return TrackSnapshot(
            state = state,
            points = points.toList(),
            distanceMeters = distanceMeters,
            elapsedTimeMillis = accumulatedActiveMillis + running,
            startedAtMillis = startedAtMillis,
            endedAtMillis = endedAtMillis,
            rejectedPointCount = rejectedPointCount,
        )
    }

    private fun createCallbackDispatcher(options: TrackOptions): SerialCallbackDispatcher<PendingCallback> =
        SerialCallbackDispatcher(options.callbackExecutor ?: AndroidMainThreadExecutor) { pending ->
            val active = synchronized(this) {
                !destroyed && pending.sessionId == callbackSessionId
            }
            if (active) pending.action()
        }

    private fun dispatchRejected(options: TrackOptions, target: TrackListener, error: MapError) {
        val dispatcher = SerialCallbackDispatcher<Runnable>(
            options.callbackExecutor ?: AndroidMainThreadExecutor,
        ) { action -> action.run() }
        dispatcher.dispatch(listOf(Runnable { target.onError(error) }))
    }

    private fun queueStateLocked(snapshot: TrackSnapshot, events: MutableList<PendingCallback>) {
        listener?.let { target ->
            callbackDispatcher?.let { dispatcher ->
                events += PendingCallback(callbackSessionId, dispatcher) {
                    target.onStateChanged(snapshot)
                }
            }
        }
    }

    private fun queuePointLocked(snapshot: TrackSnapshot, events: MutableList<PendingCallback>) {
        listener?.let { target ->
            callbackDispatcher?.let { dispatcher ->
                events += PendingCallback(callbackSessionId, dispatcher) {
                    target.onPointAdded(snapshot)
                }
            }
        }
    }

    private fun queueErrorLocked(error: MapError, events: MutableList<PendingCallback>) {
        listener?.let { target ->
            callbackDispatcher?.let { dispatcher ->
                events += PendingCallback(callbackSessionId, dispatcher) {
                    target.onError(error)
                }
            }
        }
    }

    private fun emitError(error: MapError) {
        val events = mutableListOf<PendingCallback>()
        synchronized(this) { queueErrorLocked(error, events) }
        dispatch(events)
    }

    private fun dispatch(events: List<PendingCallback>) {
        events.groupBy { it.dispatcher }.forEach { (dispatcher, batch) ->
            dispatcher.dispatch(batch)
        }
    }

    private data class PendingCallback(
        val sessionId: Long,
        val dispatcher: SerialCallbackDispatcher<PendingCallback>,
        val action: () -> Unit,
    )

    private companion object {
        const val TRACK_Z_INDEX = 50f
        const val TRACK_OVERLAY_TAG = "map-fusion-track"
    }

    private fun cancelRequest(handle: RequestHandle?): Throwable? = try {
        handle?.cancel()
        null
    } catch (error: Throwable) {
        error
    }

    private fun cancelError(prefix: String, error: Throwable) = MapError(
        ErrorType.UNKNOWN,
        "$prefix：${error.message.orEmpty()}",
        cause = error,
    )
}

private fun TrackOptions.validationError(): String? = when {
    !minPointDistanceMeters.isFinite() || minPointDistanceMeters < 0.0 -> "轨迹最小点间距不能小于 0"
    !maxAccuracyMeters.isFinite() || maxAccuracyMeters < 0f -> "轨迹最大精度不能小于 0"
    !polylineWidth.isFinite() || polylineWidth <= 0f -> "轨迹线宽必须大于 0"
    followZoom?.let { !it.isFinite() } == true -> "轨迹跟随缩放级别无效"
    else -> null
}

private fun distanceBetweenMeters(from: MapLocation, to: MapLocation): Double {
    val lat1 = Math.toRadians(from.position.latitude)
    val lat2 = Math.toRadians(to.position.latitude)
    val deltaLat = lat2 - lat1
    val deltaLng = Math.toRadians(to.position.longitude - from.position.longitude)
    val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
        cos(lat1) * cos(lat2) * sin(deltaLng / 2) * sin(deltaLng / 2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
