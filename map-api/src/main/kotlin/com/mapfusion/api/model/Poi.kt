package com.mapfusion.api.model

/**
 * POI(兴趣点)统一模型。把百度/高德各自的 PoiInfo/PoiItem 归一。
 */
data class PoiItem(
    val id: String,
    val name: String,
    val location: LatLng,
    val address: String? = null,
    val province: String? = null,
    val city: String? = null,
    val district: String? = null,
    /** 分类,如 "餐饮服务;中餐厅" */
    val category: String? = null,
    val phone: String? = null,
    /** 距检索中心的距离(米),仅周边检索时有值 */
    val distanceMeters: Int? = null,
)

/** POI 检索类型 */
enum class PoiSearchType {
    /** 关键字检索(城市内) */
    KEYWORD,

    /** 周边检索(圆形) */
    NEARBY,
}

enum class PoiSort {
    DEFAULT,
    DISTANCE,
}

/**
 * POI 检索请求。同时覆盖关键字与周边两种模式,由 type 决定必填字段。
 */
data class PoiSearchRequest(
    val type: PoiSearchType,
    val keyword: String,
    /** 分类名称或厂商分类编码；null 表示不限。 */
    val category: String? = null,
    /** KEYWORD 模式:限定城市(名称或城市码);NEARBY 可选 */
    val city: String? = null,
    /** NEARBY 模式:检索中心 */
    val center: LatLng? = null,
    /** NEARBY 模式:半径(米) */
    val radiusMeters: Int = 1000,
    /** 分页,从 0 开始 */
    val pageIndex: Int = 0,
    val pageSize: Int = 20,
    val sort: PoiSort = PoiSort.DEFAULT,
)

data class PoiSearchResult(
    val items: List<PoiItem>,
    val totalCount: Int,
    val pageIndex: Int,
    val pageSize: Int,
)

data class PoiSuggestionRequest(
    val keyword: String,
    val city: String? = null,
    val center: LatLng? = null,
    val cityLimit: Boolean = false,
    val category: String? = null,
)

data class PoiSuggestion(
    val id: String? = null,
    val name: String,
    val address: String? = null,
    val city: String? = null,
    val district: String? = null,
    val location: LatLng? = null,
    val category: String? = null,
)
