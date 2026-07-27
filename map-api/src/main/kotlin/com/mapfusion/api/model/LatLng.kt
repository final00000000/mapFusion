package com.mapfusion.api.model

/**
 * 坐标系类型。百度默认 BD09,高德/国内多数默认 GCJ02,原始 GPS 为 WGS84。
 * SDK 在厂商适配器边界按此字段转换；未声明为 [UNKNOWN] 的坐标会被拒绝，避免静默偏移。
 */
enum class CoordType {
    /** GPS 原始坐标 */
    WGS84,

    /** 国测局加密(高德、腾讯、Google 中国) */
    GCJ02,

    /** 百度加密 */
    BD09,

    /** 未知/未声明 */
    UNKNOWN
}

/**
 * 统一经纬度模型。
 *
 * @param latitude 纬度
 * @param longitude 经度
 * @param coordType 坐标系,默认 [CoordType.UNKNOWN]。传给地图能力前必须显式声明。
 */
data class LatLng(
    val latitude: Double,
    val longitude: Double,
    val coordType: CoordType = CoordType.UNKNOWN
)

// 注:LatLngBounds 定义在 Camera.kt(带 center 计算属性),此处不重复声明。
