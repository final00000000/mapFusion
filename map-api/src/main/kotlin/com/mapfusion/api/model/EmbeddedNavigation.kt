package com.mapfusion.api.model

import java.util.concurrent.Executor

/** 内嵌导航会话状态。 */
enum class EmbeddedNavigationState {
    IDLE,
    PLANNING,
    NAVIGATING,
    PAUSED,
    REROUTING,
    ARRIVED,
    STOPPED,
    FAILED,
    DESTROYED,
}

/**
 * App 内轻量导航的数据来源。
 *
 * [REAL] 使用厂商连续定位结果推进导航；[SIMULATED] 按规划路线、配置速度和时间间隔生成
 * 模拟位置。两者都不是厂商官方 Navi 引擎的真实/模拟导航模式。
 */
enum class EmbeddedNavigationMode {
    REAL,
    SIMULATED,
}

/**
 * 内嵌导航参数。
 *
 * 默认实现使用统一路线规划，并按 [navigationMode] 使用设备连续定位或沿路线生成模拟位置，
 * 在当前 MapView 内完成路线展示、位置跟随、到达判断和简单偏航重算。它不承诺车道级引导、
 * 路口放大图或厂商官方语音播报。
 * [callbackExecutor] 为空时监听事件统一派发到 Android 主线程；自定义执行器也会由
 * 实现串行化，同一导航会话内不会并发调用监听器。
 */
data class EmbeddedNavigationOptions(
    val locationOptions: LocationOptions = LocationOptions(
        accuracy = LocationAccuracy.HIGH,
        intervalMs = 1_000,
        needAddress = false,
        onceOnly = false,
        useCache = false,
        gpsFirst = true,
    ),
    /** 路线规划返回多条方案时使用的下标。 */
    val routeIndex: Int = 0,
    val routeOverlay: RouteOverlayOptions = RouteOverlayOptions(),
    val followLocation: Boolean = true,
    val followZoom: Float? = 17f,
    val rotateWithBearing: Boolean = true,
    val showCurrentMarker: Boolean = true,
    val currentMarkerIcon: MarkerIcon = MarkerIcon.Default,
    val arrivalThresholdMeters: Double = 30.0,
    val offRouteThresholdMeters: Double = 60.0,
    val autoReroute: Boolean = true,
    val rerouteCooldownMillis: Long = 10_000,
    val callbackExecutor: Executor? = null,
    /** 真实定位导航或沿规划路线推进的模拟导航。 */
    val navigationMode: EmbeddedNavigationMode = EmbeddedNavigationMode.REAL,
    /** 模拟导航速度，单位米/秒；仅 [EmbeddedNavigationMode.SIMULATED] 使用。 */
    val simulationSpeedMetersPerSecond: Float = 13.9f,
    /** 模拟位置刷新间隔；仅 [EmbeddedNavigationMode.SIMULATED] 使用。 */
    val simulationIntervalMillis: Long = 500L,
)

/** 内嵌导航启动参数。 */
data class EmbeddedNavigationRequest(
    val routeRequest: RouteRequest,
    val options: EmbeddedNavigationOptions = EmbeddedNavigationOptions(),
)

/** 一次真实或模拟位置更新对应的统一导航进度。 */
data class EmbeddedNavigationProgress(
    val location: MapLocation,
    val path: RoutePath,
    val currentStepIndex: Int,
    val currentInstruction: String?,
    val distanceToRouteMeters: Double,
    val distanceAlongRouteMeters: Double,
    val remainingDistanceMeters: Double,
    val remainingDurationSeconds: Int,
    val offRoute: Boolean,
    val navigationMode: EmbeddedNavigationMode = EmbeddedNavigationMode.REAL,
)

/**
 * 内嵌导航事件；实现必须保证同一次会话中的事件串行有序。
 * 事件默认派发到 Android 主线程，[EmbeddedNavigationOptions.callbackExecutor] 可覆盖执行器；
 * 会话销毁后，已排队但尚未开始的事件必须丢弃。
 */
sealed class EmbeddedNavigationEvent {
    data class StateChanged(val state: EmbeddedNavigationState) : EmbeddedNavigationEvent()

    data class RouteReady(
        val result: RouteResult,
        val selectedPathIndex: Int,
        val selectedPath: RoutePath,
    ) : EmbeddedNavigationEvent()

    data class Progress(val value: EmbeddedNavigationProgress) : EmbeddedNavigationEvent()

    data class RerouteStarted(val location: MapLocation) : EmbeddedNavigationEvent()

    data class Arrived(val progress: EmbeddedNavigationProgress) : EmbeddedNavigationEvent()

    data class Error(val error: MapError) : EmbeddedNavigationEvent()
}

fun interface EmbeddedNavigationListener {
    fun onEvent(event: EmbeddedNavigationEvent)
}
