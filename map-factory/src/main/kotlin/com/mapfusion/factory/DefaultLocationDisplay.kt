package com.mapfusion.factory

import com.mapfusion.api.capability.LocationClient
import com.mapfusion.api.capability.LocationDisplay
import com.mapfusion.api.capability.MapController
import com.mapfusion.api.model.CameraUpdate
import com.mapfusion.api.model.CircleOptions
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.LocationAccuracyStyle
import com.mapfusion.api.model.LocationDisplayEvent
import com.mapfusion.api.model.LocationDisplayOptions
import com.mapfusion.api.model.LocationDisplayListener
import com.mapfusion.api.model.LocationDisplayState
import com.mapfusion.api.model.LocationDisplayStyle
import com.mapfusion.api.model.LocationFollowMode
import com.mapfusion.api.model.MapCircle
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapLocation
import com.mapfusion.api.model.MapMarker
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.MarkerOptions
import com.mapfusion.api.model.RequestHandle

/** 使用普通统一覆盖物实现，避免业务分别适配两家不对称的原生定位图层。 */
internal class DefaultLocationDisplay(
    private val locationClient: LocationClient,
    private val mapController: MapController,
) : LocationDisplay {

    @Volatile
    override var state: LocationDisplayState = LocationDisplayState.IDLE
        private set

    @Volatile
    override var lastLocation: MapLocation? = null
        private set

    private var options = LocationDisplayOptions()
    private var listener: LocationDisplayListener? = null
    private var requestHandle: RequestHandle? = null
    private var marker: MapMarker? = null
    private var accuracyCircle: MapCircle? = null
    private var generation = 0L
    private var followed = false
    private var pausedByLifecycle = false

    override fun start(
        options: LocationDisplayOptions,
        listener: LocationDisplayListener,
    ): MapResult<Unit> {
        if (state == LocationDisplayState.DESTROYED) return failure("定位展示组件已销毁")
        options.validationError()?.let { return failure(it) }
        if (state == LocationDisplayState.RUNNING || state == LocationDisplayState.PAUSED) {
            return failure("定位展示已启动，请先 stop()")
        }

        runCatching(::removeOverlays).exceptionOrNull()?.let { error ->
            return operationFailure("旧定位覆盖物清理失败", error)
        }
        generation++
        val token = generation
        this.options = options
        this.listener = listener
        lastLocation = null
        followed = false
        pausedByLifecycle = false
        state = LocationDisplayState.RUNNING
        dispatch(LocationDisplayEvent.StateChanged(state))
        return startSubscription(token)
    }

    override fun updateStyle(style: LocationDisplayStyle): MapResult<Unit> {
        if (state == LocationDisplayState.DESTROYED) return failure("定位展示组件已销毁")
        style.validationError()?.let { return failure(it) }
        options = options.copy(style = style)
        return try {
            removeOverlays()
            if (state == LocationDisplayState.RUNNING || state == LocationDisplayState.PAUSED) {
                lastLocation?.let { render(it, followCamera = false) }
            }
            MapResult.Success(Unit)
        } catch (error: Throwable) {
            val mapError = MapError(
                ErrorType.UNKNOWN,
                "定位样式更新失败：${error.message.orEmpty()}",
                cause = error,
            )
            dispatch(LocationDisplayEvent.Failure(mapError))
            MapResult.Failure(mapError)
        }
    }

    override fun pause(): MapResult<Unit> = pauseInternal(byLifecycle = false)

    override fun resume(): MapResult<Unit> {
        if (state == LocationDisplayState.DESTROYED) return failure("定位展示组件已销毁")
        if (state != LocationDisplayState.PAUSED) return failure("只有已暂停的定位展示可以继续")
        generation++
        val token = generation
        pausedByLifecycle = false
        state = LocationDisplayState.RUNNING
        dispatch(LocationDisplayEvent.StateChanged(state))
        return startSubscription(token)
    }

    override fun stop(): MapResult<Unit> {
        if (state == LocationDisplayState.DESTROYED) return failure("定位展示组件已销毁")
        if (state == LocationDisplayState.IDLE || state == LocationDisplayState.STOPPED) {
            return runCatching {
                removeOverlays()
                MapResult.Success(Unit)
            }.getOrElse { operationFailure("定位覆盖物清理失败", it) }
        }
        generation++
        val handle = requestHandle.also { requestHandle = null }
        pausedByLifecycle = false
        state = LocationDisplayState.STOPPED
        val cancelError = runCatching { handle?.cancel() }.exceptionOrNull()
        val cleanupError = runCatching(::removeOverlays).exceptionOrNull()
        dispatch(LocationDisplayEvent.StateChanged(state))
        val error = cancelError ?: cleanupError
        if (cancelError != null && cleanupError != null && cleanupError !== cancelError) {
            cancelError.addSuppressed(cleanupError)
        }
        if (error != null) return operationFailure("定位展示停止清理失败", error)
        return MapResult.Success(Unit)
    }

    override fun onResume() {
        if (state == LocationDisplayState.PAUSED && pausedByLifecycle) resume()
    }

    override fun onPause() {
        if (state == LocationDisplayState.RUNNING && options.pauseWhenBackground) {
            pauseInternal(byLifecycle = true)
        }
    }

    override fun destroy() {
        if (state == LocationDisplayState.DESTROYED) return
        generation++
        val handle = requestHandle.also { requestHandle = null }
        listener = null
        pausedByLifecycle = false
        state = LocationDisplayState.DESTROYED

        var firstFailure: Throwable? = null
        fun attempt(block: () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error else firstFailure?.addSuppressed(error)
            }
        }
        attempt { handle?.cancel() }
        attempt(locationClient::destroy)
        attempt(::removeOverlays)
        firstFailure?.let { throw it }
    }

    private fun pauseInternal(byLifecycle: Boolean): MapResult<Unit> {
        if (state == LocationDisplayState.DESTROYED) return failure("定位展示组件已销毁")
        if (state != LocationDisplayState.RUNNING) return failure("只有运行中的定位展示可以暂停")
        generation++
        val handle = requestHandle.also { requestHandle = null }
        pausedByLifecycle = byLifecycle
        state = LocationDisplayState.PAUSED
        val cancelError = runCatching { handle?.cancel() }.exceptionOrNull()
        dispatch(LocationDisplayEvent.StateChanged(state))
        if (cancelError != null) return operationFailure("定位展示暂停失败", cancelError)
        return MapResult.Success(Unit)
    }

    private fun startSubscription(token: Long): MapResult<Unit> {
        val handle = try {
            locationClient.startContinuousLocation(
                options.locationOptions.copy(onceOnly = false),
            ) { result ->
                when (result) {
                    is MapResult.Success -> handleLocation(result.data, token)
                    is MapResult.Failure -> handleLocationFailure(result.error, token)
                }
            }
        } catch (error: Throwable) {
            val mapError = MapError(
                ErrorType.UNKNOWN,
                "定位展示启动失败：${error.message.orEmpty()}",
                cause = error,
            )
            handleLocationFailure(mapError, token)
            return MapResult.Failure(mapError)
        }
        if (state == LocationDisplayState.RUNNING && generation == token && requestHandle == null) {
            requestHandle = handle
        } else {
            runCatching { handle.cancel() }
        }
        return MapResult.Success(Unit)
    }

    private fun handleLocation(location: MapLocation, token: Long) {
        if (state != LocationDisplayState.RUNNING || generation != token) return
        if (!location.isValidForDisplay()) {
            dispatch(
                LocationDisplayEvent.Failure(
                    MapError(ErrorType.INVALID_PARAM, "定位结果包含非法或未声明坐标"),
                ),
            )
            return
        }
        if (options.maxAccuracyMeters > 0f && location.accuracy > options.maxAccuracyMeters) {
            dispatch(LocationDisplayEvent.AccuracyRejected(location, options.maxAccuracyMeters))
            return
        }

        try {
            render(location, followCamera = true)
            lastLocation = location
            dispatch(LocationDisplayEvent.LocationUpdated(location))
        } catch (error: Throwable) {
            dispatch(
                LocationDisplayEvent.Failure(
                    MapError(
                        ErrorType.UNKNOWN,
                        "定位结果绘制失败：${error.message.orEmpty()}",
                        cause = error,
                    ),
                ),
            )
        }
    }

    private fun handleLocationFailure(error: MapError, token: Long) {
        if (error.type == ErrorType.CANCELLED || generation != token ||
            state != LocationDisplayState.RUNNING
        ) {
            return
        }
        generation++
        requestHandle = null
        state = LocationDisplayState.STOPPED
        dispatch(LocationDisplayEvent.Failure(error))
        dispatch(LocationDisplayEvent.StateChanged(state))
    }

    private fun render(location: MapLocation, followCamera: Boolean) {
        val position = location.position
        val markerStyle = options.style.marker
        if (markerStyle == null) {
            marker?.remove()
            marker = null
        } else {
            marker = marker?.takeUnless { it.isRemoved } ?: mapController.addMarker(
                MarkerOptions(
                    position = position,
                    title = markerStyle.title,
                    icon = markerStyle.icon,
                    anchorU = markerStyle.anchorU,
                    anchorV = markerStyle.anchorV,
                    rotation = location.markerRotation(markerStyle.rotateWithBearing),
                    alpha = markerStyle.alpha,
                    flat = markerStyle.flat,
                    zIndex = markerStyle.zIndex,
                    tag = LOCATION_MARKER_TAG,
                ),
            )
            marker?.apply {
                this.position = position
                rotation = location.markerRotation(markerStyle.rotateWithBearing)
                alpha = markerStyle.alpha
                flat = markerStyle.flat
                visible = true
            }
        }

        renderAccuracy(location, options.style.accuracy)
        if (followCamera && shouldFollow()) {
            mapController.moveCamera(
                CameraUpdate(
                    target = position,
                    zoom = options.followZoom,
                    bearing = location.bearing
                        .takeIf { options.followMode == LocationFollowMode.COURSE_UP && it.isFinite() }
                        ?.normalizedBearing(),
                    animated = options.animateCamera,
                    durationMs = options.cameraAnimationDurationMs,
                ),
            )
            followed = true
        }
    }

    private fun renderAccuracy(location: MapLocation, style: LocationAccuracyStyle?) {
        if (style == null || !location.accuracy.isFinite() || location.accuracy <= 0f) {
            accuracyCircle?.visible = false
            return
        }
        accuracyCircle = accuracyCircle?.takeUnless { it.isRemoved } ?: mapController.addCircle(
            CircleOptions(
                center = location.position,
                radiusMeters = location.accuracy.toDouble(),
                strokeWidth = style.strokeWidth,
                strokeColor = style.strokeColor,
                fillColor = style.fillColor,
                zIndex = style.zIndex,
                tag = LOCATION_ACCURACY_TAG,
            ),
        )
        accuracyCircle?.apply {
            center = location.position
            radiusMeters = location.accuracy.toDouble()
            strokeWidth = style.strokeWidth
            strokeColor = style.strokeColor
            fillColor = style.fillColor
            zIndex = style.zIndex
            visible = true
        }
    }

    private fun shouldFollow(): Boolean = when (options.followMode) {
        LocationFollowMode.NONE -> false
        LocationFollowMode.FIRST_FIX -> !followed
        LocationFollowMode.CONTINUOUS, LocationFollowMode.COURSE_UP -> true
    }

    private fun removeOverlays() {
        var firstFailure: Throwable? = null
        listOfNotNull(marker, accuracyCircle).forEach { overlay ->
            try {
                overlay.remove()
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error else firstFailure?.addSuppressed(error)
            }
        }
        marker = null
        accuracyCircle = null
        firstFailure?.let { throw it }
    }

    private fun dispatch(event: LocationDisplayEvent) {
        runCatching { listener?.onEvent(event) }
    }

    private fun failure(message: String): MapResult.Failure =
        MapResult.Failure(MapError(ErrorType.INVALID_PARAM, message))

    private fun operationFailure(message: String, error: Throwable): MapResult.Failure {
        val mapError = MapError(
            ErrorType.UNKNOWN,
            "$message：${error.message.orEmpty()}",
            cause = error,
        )
        dispatch(LocationDisplayEvent.Failure(mapError))
        return MapResult.Failure(mapError)
    }

    private companion object {
        const val LOCATION_MARKER_TAG = "map-fusion-location-marker"
        const val LOCATION_ACCURACY_TAG = "map-fusion-location-accuracy"
    }
}

private fun LocationDisplayOptions.validationError(): String? = when {
    locationOptions.timeoutMs <= 0L -> "定位 timeoutMs 必须大于 0"
    locationOptions.intervalMs <= 0L -> "连续定位 intervalMs 必须大于 0"
    !maxAccuracyMeters.isFinite() || maxAccuracyMeters < 0f -> "最大可接受精度不能小于 0"
    followZoom?.let { !it.isFinite() } == true -> "定位跟随缩放级别无效"
    cameraAnimationDurationMs < 0 -> "相机动画时长不能小于 0"
    else -> style.validationError()
}

private fun LocationDisplayStyle.validationError(): String? = when {
    marker?.let { it.anchorU !in 0f..1f || it.anchorV !in 0f..1f } == true ->
        "定位图标锚点必须在 0..1"
    marker?.let { !it.alpha.isFinite() || it.alpha !in 0f..1f } == true ->
        "定位图标透明度必须在 0..1"
    marker?.let { !it.zIndex.isFinite() } == true -> "定位图标 zIndex 无效"
    accuracy?.let { !it.strokeWidth.isFinite() || it.strokeWidth < 0f } == true ->
        "定位精度圈线宽不能小于 0"
    accuracy?.let { !it.zIndex.isFinite() } == true -> "定位精度圈 zIndex 无效"
    else -> null
}

private fun MapLocation.isValidForDisplay(): Boolean =
    position.coordType != CoordType.UNKNOWN &&
        position.latitude.isFinite() && position.latitude in -90.0..90.0 &&
        position.longitude.isFinite() && position.longitude in -180.0..180.0 &&
        accuracy.isFinite() && accuracy >= 0f

private fun MapLocation.markerRotation(enabled: Boolean): Float =
    if (enabled && bearing.isFinite()) bearing.normalizedBearing() else 0f

private fun Float.normalizedBearing(): Float = ((this % 360f) + 360f) % 360f
