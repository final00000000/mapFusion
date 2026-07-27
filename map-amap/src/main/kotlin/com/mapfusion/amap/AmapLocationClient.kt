package com.mapfusion.amap

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.mapfusion.api.async.AsyncRequest
import com.mapfusion.api.async.AsyncRuntime
import com.mapfusion.api.capability.LocationClient
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.LocationAccuracy
import com.mapfusion.api.model.LocationOptions
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapLocation
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.RequestHandle
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 高德定位 SDK 的真实适配。 */
internal class AmapLocationClient(context: Context) : LocationClient {

    private val appContext = context.applicationContext
    // 显式绑定主 Looper，避免宿主从无 Looper 的工作线程创建客户端时丢失原生回调。
    private val native = com.amap.api.location.AMapLocationClient(Looper.getMainLooper(), appContext)
    private val runtime = AsyncRuntime.DEFAULT
    private val lock = Any()

    @Volatile
    private var destroyed = false

    private var active: LocationSession? = null

    override fun requestSingleLocation(
        options: LocationOptions,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<MapLocation>,
    ): RequestHandle {
        if (destroyed) return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "高德定位客户端已销毁"))
        if (!appContext.hasLocationPermission()) {
            return failed(asyncOptions, callback, MapError(ErrorType.PERMISSION, "未授予 Android 定位权限"))
        }
        if (options.timeoutMs <= 0) {
            return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "定位 timeoutMs 必须大于 0"))
        }
        stopContinuousLocation()

        val sessionRef = AtomicReference<SingleSession>()
        val request = runtime.createAmapSingleLocationRequest(
            locationTimeoutMillis = options.timeoutMs,
            callback = callback,
            options = asyncOptions,
            terminalAction = Runnable { sessionRef.get()?.let(::releaseNative) },
        )
        val listener = AMapLocationListener { location ->
            val session = sessionRef.get() ?: return@AMapLocationListener
            if (!isActive(session)) return@AMapLocationListener
            val result = runCatching {
                if (location.errorCode == AMapLocation.LOCATION_SUCCESS) {
                    MapResult.Success(location.toFusion())
                } else {
                    MapResult.Failure(location.toMapError())
                }
            }.getOrElse { MapResult.Failure(it.toLocationError("高德定位结果解析失败")) }
            request.complete(result)
        }
        val session = SingleSession(listener, request)
        sessionRef.set(session)
        if (request.isDone) return request
        if (!activate(session)) {
            request.dispose()
            return request
        }
        runCatching {
            synchronized(lock) {
                if (destroyed || active !== session) return@synchronized
                native.setLocationOption(options.copy(onceOnly = true).toAmapOption())
                native.setLocationListener(listener)
                native.startLocation()
            }
        }.onFailure { error -> request.failure(error.toLocationError("高德定位启动失败")) }
        return request
    }

    override fun startContinuousLocation(
        options: LocationOptions,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<MapLocation>,
    ): RequestHandle {
        if (destroyed) return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "高德定位客户端已销毁"))
        if (!appContext.hasLocationPermission()) {
            return failed(asyncOptions, callback, MapError(ErrorType.PERMISSION, "未授予 Android 定位权限"))
        }
        if (options.timeoutMs <= 0) {
            return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "定位 timeoutMs 必须大于 0"))
        }
        stopContinuousLocation()

        val session = ContinuousSession(
            asyncOptions = asyncOptions,
            callback = callback,
        )
        if (session.isDone) return session
        if (!activate(session)) {
            session.disposeFromDestroy()
            return session
        }
        runCatching {
            synchronized(lock) {
                if (destroyed || active !== session) return@synchronized
                native.setLocationOption(options.copy(onceOnly = false).toAmapOption())
                native.setLocationListener(session.listener)
                native.startLocation()
            }
        }.onFailure { error -> session.fail(error.toLocationError("高德连续定位启动失败")) }
        return session
    }

    /** 停止当前订阅；重复调用不会重复触发原生释放。 */
    override fun stopContinuousLocation() {
        val session = synchronized(lock) { active.also { active = null } } ?: return
        when (session) {
            is SingleSession -> session.request.dispose()
            is ContinuousSession -> session.disposeFromStop()
        }
    }

    override fun destroy() {
        val session = synchronized(lock) {
            if (destroyed) return
            destroyed = true
            active.also { active = null }
        }
        when (session) {
            is SingleSession -> session.request.dispose()
            is ContinuousSession -> session.disposeFromDestroy()
            null -> Unit
        }
        runCatching { native.onDestroy() }
    }

    private fun activate(session: LocationSession): Boolean = synchronized(lock) {
        if (destroyed || session.released.get()) false else {
            active = session
            true
        }
    }

    private fun isActive(session: LocationSession): Boolean =
        !destroyed && synchronized(lock) { active === session }

    private fun releaseNative(session: LocationSession) {
        if (!session.released.compareAndSet(false, true)) return
        synchronized(lock) {
            if (active === session) active = null
        }
        runCatching { if (native.isStarted) native.stopLocation() }
        runCatching { native.unRegisterLocationListener(session.listener) }
    }

    private fun failed(
        options: AsyncCallOptions,
        callback: MapCallback<MapLocation>,
        error: MapError,
    ): RequestHandle = runtime.createRequest(callback, options).also { it.failure(error) }

    private sealed interface LocationSession {
        val listener: AMapLocationListener
        val released: AtomicBoolean
    }

    private inner class SingleSession(
        override val listener: AMapLocationListener,
        val request: AsyncRequest<MapLocation>,
    ) : LocationSession {
        override val released = AtomicBoolean(false)
    }

    private inner class ContinuousSession(
        asyncOptions: AsyncCallOptions,
        private val callback: MapCallback<MapLocation>,
    ) : LocationSession, RequestHandle {
        override val released = AtomicBoolean(false)
        override val listener = AMapLocationListener { location ->
            if (!isActive(this)) return@AMapLocationListener
            val result = runCatching {
                if (location.errorCode == AMapLocation.LOCATION_SUCCESS) {
                    MapResult.Success(location.toFusion())
                } else {
                    MapResult.Failure(location.toMapError())
                }
            }.getOrElse { MapResult.Failure(it.toLocationError("高德连续定位结果解析失败")) }
            control.emit(result)
        }
        private val control = ContinuousRequestHandle(
            callback = callback,
            options = asyncOptions,
            runtime = runtime,
            releaseAction = { releaseNative(this) },
        )

        override val isDone: Boolean get() = control.isDone
        override val isCancelled: Boolean get() = control.isCancelled

        fun fail(error: MapError) {
            control.fail(error)
        }

        fun disposeFromStop() {
            control.dispose()
        }

        fun disposeFromDestroy() {
            control.dispose()
        }

        override fun cancel(): Boolean = control.cancel()
    }
}

/**
 * 单次定位同时受调用级超时和定位选项超时约束，任何一方先到都必须结束请求。
 * 原生 SDK 的 httpTimeOut 只限制网络阶段，不能替代这一层完整请求的硬截止时间。
 */
internal fun <T> AsyncRuntime.createAmapSingleLocationRequest(
    locationTimeoutMillis: Long,
    callback: MapCallback<T>,
    options: AsyncCallOptions,
    terminalAction: Runnable = Runnable {},
): AsyncRequest<T> {
    require(locationTimeoutMillis > 0) { "locationTimeoutMillis 必须大于 0" }
    return createRequest(
        callback = callback,
        options = options.copy(
            timeoutMillis = minOf(options.timeoutMillis, locationTimeoutMillis),
        ),
        terminalAction = terminalAction,
    )
}

private fun Throwable.toLocationError(prefix: String): MapError = when (this) {
    is SecurityException -> MapError(ErrorType.PERMISSION, "$prefix：${message.orEmpty()}", cause = this)
    is IllegalArgumentException -> MapError(ErrorType.INVALID_PARAM, "$prefix：${message.orEmpty()}", cause = this)
    else -> MapError(ErrorType.UNKNOWN, "$prefix：${message.orEmpty()}", cause = this)
}

private fun Context.hasLocationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun LocationOptions.toAmapOption() = AMapLocationClientOption().apply {
    isOnceLocation = onceOnly
    isOnceLocationLatest = onceOnly
    interval = intervalMs.coerceAtLeast(1_000)
    isNeedAddress = needAddress
    httpTimeOut = timeoutMs.coerceAtLeast(1_000)
    isMockEnable = allowMock
    isLocationCacheEnable = useCache
    isGpsFirst = gpsFirst
    deviceModeDistanceFilter = distanceFilterMeters.coerceAtLeast(0f)
    locationMode = when (accuracy) {
        LocationAccuracy.HIGH -> AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
        LocationAccuracy.LOW_POWER -> AMapLocationClientOption.AMapLocationMode.Battery_Saving
        LocationAccuracy.DEVICE_ONLY -> AMapLocationClientOption.AMapLocationMode.Device_Sensors
    }
}

private fun AMapLocation.toFusion() = MapLocation(
    position = LatLng(latitude, longitude, CoordType.GCJ02),
    accuracy = accuracy,
    bearing = bearing,
    speed = speed,
    altitude = altitude,
    time = time.takeIf { it > 0 } ?: System.currentTimeMillis(),
    address = address,
    country = country,
    province = province,
    city = city,
    district = district,
)

private fun AMapLocation.toMapError(): MapError {
    val type = when (errorCode) {
        AMapLocation.ERROR_CODE_FAILURE_CONNECTION -> ErrorType.NETWORK
        AMapLocation.ERROR_CODE_FAILURE_AUTH -> ErrorType.AUTH
        AMapLocation.ERROR_CODE_FAILURE_LOCATION_PERMISSION,
        AMapLocation.ERROR_CODE_FAILURE_COARSE_LOCATION -> ErrorType.PERMISSION
        AMapLocation.ERROR_CODE_INVALID_PARAMETER,
        AMapLocation.ERROR_CODE_FAILURE_LOCATION_PARAMETER -> ErrorType.INVALID_PARAM
        else -> ErrorType.UNKNOWN
    }
    return MapError(
        type = type,
        message = errorInfo ?: "高德定位失败（$errorCode）",
        rawCode = errorCode,
        rawMessage = locationDetail,
    )
}
