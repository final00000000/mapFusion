package com.mapfusion.api.model

/**
 * 地理编码(地址 -> 坐标)请求。
 */
data class GeocodeRequest(
    val address: String,
    /** 限定城市,提升精度 */
    val city: String? = null,
)

data class GeocodeResult(
    val location: LatLng,
    val formattedAddress: String? = null,
    val province: String? = null,
    val city: String? = null,
    val district: String? = null,
    /** 置信度/精度级别,各家含义不同,原样透出 */
    val level: String? = null,
)

/**
 * 逆地理编码(坐标 -> 地址)请求。
 */
data class ReverseGeocodeRequest(
    val location: LatLng,
    /** 检索半径(米),影响返回的周边 POI */
    val radiusMeters: Int = 1000,
)

data class ReverseGeocodeResult(
    val formattedAddress: String,
    val country: String? = null,
    val province: String? = null,
    val city: String? = null,
    val district: String? = null,
    val township: String? = null,
    val street: String? = null,
    val streetNumber: String? = null,
    /** 行政区划编码 */
    val adCode: String? = null,
    /** 周边 POI */
    val pois: List<PoiItem> = emptyList(),
)
