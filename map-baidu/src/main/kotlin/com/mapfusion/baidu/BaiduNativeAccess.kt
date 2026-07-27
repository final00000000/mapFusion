package com.mapfusion.baidu

import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.search.geocode.GeoCoder
import com.baidu.mapapi.search.poi.PoiSearch
import com.baidu.mapapi.search.route.RoutePlanSearch
import com.mapfusion.api.capability.MapController

/**
 * 百度高级能力逃生舱。
 *
 * 通用层未覆盖的热力图、瓦片、3D 模型、轨迹等功能，可通过原生 [BaiduMap] 局部实现。
 */
class BaiduNativeAccess internal constructor() {
    fun mapOf(controller: MapController): BaiduMap? =
        (controller as? BaiduMapController)?.nativeMap

    fun mapViewOf(controller: MapController): MapView? =
        (controller as? BaiduMapController)?.nativeMapView

    fun newGeocoder(): GeoCoder = GeoCoder.newInstance()
    fun newPoiSearch(): PoiSearch = PoiSearch.newInstance()
    fun newRoutePlanSearch(): RoutePlanSearch = RoutePlanSearch.newInstance()
}
