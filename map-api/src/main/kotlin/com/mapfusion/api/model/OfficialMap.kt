package com.mapfusion.api.model

/** 地图底图语言。适配器只能声明并应用厂商原生支持的语言。 */
enum class MapLanguage {
    CHINESE,
    ENGLISH,
    /** 跟随设备或厂商默认语言；并非所有厂商都提供该模式。 */
    LOCAL,
}

/** 地图 View 内的像素坐标，原点位于左上角。 */
data class ScreenPoint(
    val x: Int,
    val y: Int,
)

/** 当前地图 View 的四角可视坐标；倾斜地图下四个角不一定构成轴对齐矩形。 */
data class MapVisibleRegion(
    val nearLeft: LatLng,
    val nearRight: LatLng,
    val farLeft: LatLng,
    val farRight: LatLng,
) {
    /** 包含四个可视角点的最小经纬度外接矩形。 */
    val bounds: LatLngBounds
        get() = LatLngBounds.fromPoints(listOf(nearLeft, nearRight, farLeft, farRight))
}

/**
 * 厂商官方地图审图号与版权信息。宿主可直接用于关于页或地图合规信息页，避免依赖原生类型。
 * 不适用于当前底图的字段由适配器返回 null。
 */
data class MapLegalInfo(
    val mapContentApprovalNumber: String? = null,
    val englishMapApprovalNumber: String? = null,
    val satelliteImageApprovalNumber: String? = null,
    val terrainApprovalNumber: String? = null,
    val copyright: String? = null,
    val mappingQualification: String? = null,
)

/** 厂商官方实时路况图层的四级配色。颜色使用 Android ARGB Int。 */
data class MapTrafficStyle(
    val smoothColor: Int,
    val slowColor: Int,
    val congestedColor: Int,
    val seriouslyCongestedColor: Int,
)
