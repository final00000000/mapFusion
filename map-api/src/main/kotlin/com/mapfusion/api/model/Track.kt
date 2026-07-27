package com.mapfusion.api.model

import java.util.concurrent.Executor

/**
 * 轨迹记录与实时绘制参数。
 *
 * [callbackExecutor] 为空时监听事件统一派发到 Android 主线程；自定义执行器也会由
 * 实现串行化，同一轨迹会话内不会并发调用监听器。
 */
data class TrackOptions(
    val locationOptions: LocationOptions = LocationOptions(
        onceOnly = false,
        intervalMs = 2_000,
        needAddress = false,
        useCache = false,
    ),
    /** 小于等于 0 时不按位移去重。 */
    val minPointDistanceMeters: Double = 2.0,
    /** 大于 0 时丢弃精度差于该值的点,0 表示不限制。 */
    val maxAccuracyMeters: Float = 100f,
    /** 是否将轨迹实时绘制到地图。 */
    val drawOnMap: Boolean = true,
    /** 是否用相机跟随最新点。 */
    val followLocation: Boolean = false,
    /** 跟随时可选的固定缩放级别,为空则保留当前缩放。 */
    val followZoom: Float? = null,
    val polylineWidth: Float = 12f,
    val polylineColor: Int = 0xFF1976D2.toInt(),
    val showCurrentMarker: Boolean = true,
    val callbackExecutor: Executor? = null,
)

/** 轨迹当前快照,列表顺序即记录顺序。 */
data class TrackSnapshot(
    val state: com.mapfusion.api.capability.TrackState,
    val points: List<MapLocation> = emptyList(),
    val distanceMeters: Double = 0.0,
    val elapsedTimeMillis: Long = 0L,
    val startedAtMillis: Long? = null,
    val endedAtMillis: Long? = null,
    val rejectedPointCount: Int = 0,
)
