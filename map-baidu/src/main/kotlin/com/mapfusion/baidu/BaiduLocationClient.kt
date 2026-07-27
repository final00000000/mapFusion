package com.mapfusion.baidu

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClientOption
import com.mapfusion.api.async.AndroidMainThreadExecutor
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
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** 百度定位 SDK 的真实适配。 */
internal class BaiduLocationClient(
    context: Context,
    private val asyncRuntime: AsyncRuntime = AsyncRuntime.DEFAULT,
) : LocationClient {

    private val appContext = context.applicationContext
    private val native = com.baidu.location.LocationClient(appContext)
    private val lock = Any()
    private var activeOperation: LocationOperation? = null
    @Volatile
    private var destroyed = false

    override fun requestSingleLocation(
        options: LocationOptions,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<MapLocation>,
    ): RequestHandle = start(options.copy(onceOnly = true), asyncOptions, callback, onceOnly = true)

    override fun startContinuousLocation(
        options: LocationOptions,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<MapLocation>,
    ): RequestHandle = start(options.copy(onceOnly = false), asyncOptions, callback, onceOnly = false)

    private fun start(
        options: LocationOptions,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<MapLocation>,
        onceOnly: Boolean,
    ): RequestHandle {
        if (destroyed) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度定位客户端已销毁"),
            )
        }
        if (!appContext.hasLocationPermission()) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.PERMISSION, "未授予 Android 定位权限"),
            )
        }
        if (options.timeoutMs <= 0) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "定位 timeoutMs 必须大于 0"),
            )
        }
        val operation = LocationOperation(asyncOptions, callback, onceOnly)
        operation.attach()
        synchronized(lock) {
            if (destroyed || operation.request.isDone) {
                operation.disposeSilently()
                return operation
            }
            activeOperation?.disposeSilently()
            activeOperation = operation
            try {
                // 百度的原生单次模式会把 62（当前定位依据不足）作为第一次也是最后一次
                // 回调。统一层改为托管多轮原生定位，直到成功、不可恢复错误或统一超时。
                native.locOption = options.toBaiduOption(adapterManagedSingle = onceOnly)
                native.registerLocationListener(operation.listener)
                native.start()
            } catch (error: Throwable) {
                operation.request.failure(error.toLocationError("百度定位启动失败"))
            }
        }
        return operation
    }

    override fun stopContinuousLocation() {
        synchronized(lock) { activeOperation?.disposeSilently() }
    }

    override fun destroy() {
        synchronized(lock) {
            if (destroyed) return
            destroyed = true
            activeOperation?.disposeSilently()
            activeOperation = null
        }
    }

    /**
     * 连续定位句柄的首次结果由 AsyncRequest 管理超时和取消；首次结果之后，句柄保持
     * 活跃并把后续位置投递到同一个执行器，直到 cancel/stop/destroy。
     */
    private inner class LocationOperation(
        private val asyncOptions: AsyncCallOptions,
        private val callback: MapCallback<MapLocation>,
        private val onceOnly: Boolean,
    ) : RequestHandle {
        private val active = AtomicBoolean(true)
        private val firstDelivered = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        private val callbackExecutor: Executor = asyncOptions.callbackExecutor ?: AndroidMainThreadExecutor
        private lateinit var firstRequest: AsyncRequest<MapLocation>

        val request: AsyncRequest<MapLocation>
            get() = firstRequest

        val listener: BDAbstractLocationListener = object : BDAbstractLocationListener() {
            override fun onReceiveLocation(location: BDLocation) {
                if (!isCurrent()) return
                if (onceOnly && location.locType.isRetryableBaiduLocationFailure()) {
                    // 室内启动、Wi-Fi 列表刷新或 GPS 尚未首定位时，百度可能先回调
                    // 62/63/67。它们只是本轮失败，不能提前结束宿主要求的单次定位。
                    return
                }
                val result = runCatching {
                    if (location.isSuccessful()) {
                        MapResult.Success(location.toFusion())
                    } else {
                        MapResult.Failure(location.toMapError())
                    }
                }.getOrElse { error ->
                    MapResult.Failure(error.toLocationError("百度定位结果解析失败"))
                }
                if (firstDelivered.compareAndSet(false, true)) {
                    // AsyncRequest 的 terminalAction 会在回调派发前释放单次请求；连续请求
                    // 首次结果则保持原生监听器存活。
                    firstRequest.complete(result)
                } else if (!onceOnly) {
                    // 取消后已经排队的连续结果再次检查句柄状态，避免迟到回调触达业务。
                    callbackExecutor.execute {
                        if (isCurrent()) callback.onResult(result)
                    }
                }
            }
        }

        fun attach() {
            val self = this
            firstRequest = asyncRuntime.createRequest(
                callback = callback,
                options = asyncOptions,
                terminalAction = Runnable { self.onInitialTerminal() },
            )
        }

        private fun onInitialTerminal() {
            // 单次定位无论成功/失败都结束；连续定位仅在首次结果尚未到达、或被主动取消
            // 时结束。首次失败也属于一次结果，允许连续定位继续等待后续位置。
            if (onceOnly || !firstDelivered.get()) closeNative()
        }

        private fun isCurrent(): Boolean = synchronized(lock) {
            !destroyed && active.get() && activeOperation === this
        }

        private fun closeNative() {
            if (!active.compareAndSet(true, false)) return
            releaseNative(this)
        }

        fun disposeSilently() {
            if (!active.compareAndSet(true, false)) return
            firstRequest.dispose()
            releaseNative(this)
        }

        override val isDone: Boolean
            get() = !active.get()

        override val isCancelled: Boolean
            get() = cancelled.get()

        override fun cancel(): Boolean {
            if (!active.compareAndSet(true, false)) return false
            cancelled.set(true)
            val firstRequestPending = !firstRequest.isDone
            // 先停止原生监听，确保取消回调触达前资源已经释放；AsyncRequest 的
            // terminalAction 此时只会看到 active=false，不会重复释放原生对象。
            releaseNative(this)
            val cancelledFirst = if (firstRequestPending) firstRequest.cancel() else false
            if (!cancelledFirst && firstDelivered.get()) {
                // 连续订阅在首次位置成功后，首个 AsyncRequest 已经终结，无法再次调用
                // cancel()。契约仍要求句柄取消通知宿主，因此补发一次统一 CANCELLED。
                callbackExecutor.execute {
                    callback.onResult(
                        MapResult.Failure(MapError(ErrorType.CANCELLED, "请求已取消")),
                    )
                }
            }
            return true
        }
    }

    private fun releaseNative(operation: LocationOperation) {
        synchronized(lock) {
            if (activeOperation === operation) {
                activeOperation = null
                runCatching { if (native.isStarted) native.stop() }
            }
            runCatching { native.unRegisterLocationListener(operation.listener) }
        }
    }
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

private fun LocationOptions.toBaiduOption(
    adapterManagedSingle: Boolean = false,
) = LocationClientOption().apply {
    val nativeOnceOnly = onceOnly && !adapterManagedSingle
    // 9.6.8 的 BDLOCATION_COOR_TYPE_BD09LL 常量值为 "bd09"，部分设备会返回 bd09mc。
    setCoorType("bd09ll")
    setIsNeedAddress(needAddress)
    setOnceLocation(nativeOnceOnly)
    setScanSpan(if (nativeOnceOnly) 0 else intervalMs.coerceAtLeast(1_000).toInt())
    setTimeOut(timeoutMs.coerceIn(1_000, Int.MAX_VALUE.toLong()).toInt())
    setEnableSimulateGps(allowMock)
    disableCache(!useCache)
    setLocationMode(
        when (accuracy) {
            LocationAccuracy.HIGH -> LocationClientOption.LocationMode.Hight_Accuracy
            LocationAccuracy.LOW_POWER -> LocationClientOption.LocationMode.Battery_Saving
            LocationAccuracy.DEVICE_ONLY -> LocationClientOption.LocationMode.Device_Sensors
        },
    )
    setOpenGps(accuracy != LocationAccuracy.LOW_POWER)
    if (!onceOnly && distanceFilterMeters > 0f) {
        setOpenAutoNotifyMode(
            intervalMs.coerceAtLeast(1_000).toInt(),
            distanceFilterMeters.toInt(),
            LocationClientOption.LOC_SENSITIVITY_MIDDLE,
        )
    }
    if (gpsFirst) priority = LocationClientOption.GPS_FIRST
}

private fun BDLocation.isSuccessful(): Boolean = locType in setOf(
    BDLocation.TypeGpsLocation,
    BDLocation.TypeGnssLocation,
    BDLocation.TypeNetWorkLocation,
    BDLocation.TypeOffLineLocation,
    BDLocation.TypeCacheLocation,
    BDLocation.TypeCoarseLocation,
    BDLocation.TYPE_HD_LOCATION,
    BDLocation.TYPE_BMS_HD_LOCATION,
    BDLocation.TYPE_LANE_HD_LOCATION,
)

private fun BDLocation.toFusion(): MapLocation {
    val normalizedPosition = if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
        bd09MercatorToLatLng(longitude, latitude)
    } else {
        LatLng(latitude, longitude, CoordType.BD09)
    }
    return MapLocation(
    position = normalizedPosition,
    accuracy = radius,
    bearing = direction,
    speed = speed,
    altitude = altitude,
    time = timeStamp.takeIf { it > 0 } ?: System.currentTimeMillis(),
    address = addrStr,
    country = country,
    province = province,
    city = city,
    district = district,
    )
}

private fun BDLocation.toMapError(): MapError {
    return MapError(
        type = locType.toBaiduLocationErrorType(),
        message = locTypeDescription ?: "百度定位失败（$locType）",
        rawCode = locType,
        rawMessage = locTypeDescription,
    )
}

/**
 * 62 表示当前没有足够的定位依据，并非参数、鉴权或永久权限错误。63/67 也可能在
 * GPS 随后成功前由网络定位链路先返回，因此单次请求应继续等待统一层超时。
 */
internal fun Int.isRetryableBaiduLocationFailure(): Boolean = when (this) {
    BDLocation.TypeCriteriaException,
    BDLocation.TypeNetWorkException,
    BDLocation.TypeOffLineLocationNetworkFail -> true
    else -> false
}

internal fun Int.toBaiduLocationErrorType(): ErrorType = when (this) {
    BDLocation.TypeCriteriaException -> ErrorType.NO_RESULT
    BDLocation.TypeNetWorkException,
    BDLocation.TypeOffLineLocationNetworkFail -> ErrorType.NETWORK
    BDLocation.TYPE_NO_PERMISSION_LOCATION_FAIL,
    BDLocation.TYPE_NO_PERMISSION_AND_CLOSE_SWITCH_FAIL -> ErrorType.PERMISSION
    BDLocation.TypeServerCheckKeyError,
    BDLocation.TypeServerCheckFlowError -> ErrorType.AUTH
    else -> ErrorType.UNKNOWN
}
