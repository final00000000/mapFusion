package com.mapfusion.api.model

/** 出行方式 */
enum class TravelMode {
    DRIVING,
    WALKING,
    /** 普通自行车。 */
    BICYCLE,
    /** 电动自行车；厂商原生 SDK 不支持时必须返回 UNSUPPORTED。 */
    ELECTRIC_BICYCLE,
    /** 兼容旧版调用，语义等同于 [BICYCLE]。 */
    @Deprecated("请使用 BICYCLE", ReplaceWith("TravelMode.BICYCLE"))
    RIDING,
    TRANSIT,

    ;

    @Suppress("DEPRECATION")
    fun canonical(): TravelMode = if (this == RIDING) BICYCLE else this
}

/** 可跨厂商表达的路线偏好；厂商不支持时适配器会选择最接近策略。 */
enum class RoutePreference {
    DEFAULT,
    FASTEST,
    SHORTEST_DISTANCE,
    AVOID_CONGESTION,
    AVOID_TOLLS,
    AVOID_HIGHWAYS,
    LEAST_WALKING,
    LEAST_TRANSFERS,
    NO_SUBWAY,
}

/**
 * 路径规划请求。transit 模式下 city 必填。
 */
data class RouteRequest(
    val mode: TravelMode,
    val origin: LatLng,
    val destination: LatLng,
    /** 途经点,driving 支持 */
    val waypoints: List<LatLng> = emptyList(),
    /** transit 模式:城市名或城市码 */
    val city: String? = null,
    val preference: RoutePreference = RoutePreference.DEFAULT,
    val avoidFerries: Boolean = false,
    /** 驾车限行策略所需车牌；不需要时留空。 */
    val plateProvince: String? = null,
    val plateNumber: String? = null,
)

/**
 * 一条路线中的一段(转向/换乘粒度)。
 */
data class RouteStep(
    val instruction: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    /** 该段的路径点,用于绘制 polyline */
    val polyline: List<LatLng> = emptyList(),
)

/**
 * 一条完整路线方案。
 */
data class RoutePath(
    val distanceMeters: Int,
    val durationSeconds: Int,
    val steps: List<RouteStep>,
    /** 整条路线的路径点(合并各段),方便直接画线 */
    val polyline: List<LatLng> = emptyList(),
    /** 打车/公交费用(元),无则 null */
    val cost: Double? = null,
)

data class RouteResult(
    val mode: TravelMode,
    val paths: List<RoutePath>,
)
