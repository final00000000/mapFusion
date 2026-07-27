package com.mapfusion.api.capability

/**
 * 地图厂商标识。新增厂商在此扩展。
 */
enum class Provider {
    BAIDU,
    AMAP,
}

/**
 * 单项地图能力枚举。用于运行时能力查询:
 * 由于百度/高德的能力并非完全重叠,业务方可先查询再调用,
 * 避免对"这家有那家没有"的能力做硬编码假设。
 */
enum class Capability {
    MAP_CONTROLLER,
    LOCATION,
    GEOCODER,
    POI_SEARCH,
    ROUTE_PLANNING,
    NAVIGATION,
    DISTRICT_SEARCH,
    WEATHER,
}
