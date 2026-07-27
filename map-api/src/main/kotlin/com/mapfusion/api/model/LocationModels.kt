package com.mapfusion.api.model

/**
 * 定位结果。归一百度 BDLocation / 高德 AMapLocation。
 */
data class MapLocation(
    val position: LatLng,
    /** 精度(米) */
    val accuracy: Float = 0f,
    /** 方向(度,正北为 0) */
    val bearing: Float = 0f,
    /** 速度(米/秒) */
    val speed: Float = 0f,
    /** 海拔(米) */
    val altitude: Double = 0.0,
    /** 定位时刻的时间戳(毫秒) */
    val time: Long = 0L,
    // 逆地理附带信息(部分 SDK 定位时一并返回)
    val address: String? = null,
    val country: String? = null,
    val province: String? = null,
    val city: String? = null,
    val district: String? = null,
)

/** 定位精度/耗电取向 */
enum class LocationAccuracy {
    /** 高精度:GPS+网络 */
    HIGH,

    /** 仅省电:网络定位 */
    LOW_POWER,

    /** 仅设备:仅 GPS */
    DEVICE_ONLY,
}

/**
 * 定位参数。适配器翻译成各家 LocationClientOption / AMapLocationClientOption。
 */
data class LocationOptions(
    val accuracy: LocationAccuracy = LocationAccuracy.HIGH,
    /** 连续定位间隔(毫秒);单次定位忽略 */
    val intervalMs: Long = 2000,
    /** 是否需要逆地理地址 */
    val needAddress: Boolean = true,
    /** 是否只定位一次 */
    val onceOnly: Boolean = false,
    /** 单次网络定位/首定位超时。 */
    val timeoutMs: Long = 12_000,
    /** 是否允许 SDK 返回模拟位置；生产环境通常关闭。 */
    val allowMock: Boolean = false,
    /** 是否允许使用 SDK 历史定位缓存。 */
    val useCache: Boolean = true,
    /** DEVICE_ONLY 下或支持时的最小位移回调距离。 */
    val distanceFilterMeters: Float = 0f,
    /** 高精度模式是否优先等待 GPS。 */
    val gpsFirst: Boolean = false,
)
