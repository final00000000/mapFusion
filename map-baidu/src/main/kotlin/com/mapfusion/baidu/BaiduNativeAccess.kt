package com.mapfusion.baidu

import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.search.geocode.GeoCoder
import com.baidu.mapapi.search.busline.BusLineSearch
import com.baidu.mapapi.search.building.BuildingSearch
import com.baidu.mapapi.search.recommendstop.RecommendStopSearch
import com.baidu.mapapi.search.share.ShareUrlSearch
import com.baidu.mapapi.search.district.DistrictSearch
import com.baidu.mapapi.search.poi.PoiSearch
import com.baidu.mapapi.search.route.RoutePlanSearch
import com.baidu.mapapi.search.sug.SuggestionSearch
import com.baidu.mapapi.search.weather.WeatherSearch
import com.mapfusion.api.capability.MapController

/**
 * 百度高级能力逃生舱。
 *
 * 通用层未覆盖的热力图、瓦片、3D 模型、轨迹等功能，可通过原生 [BaiduMap] 局部实现。
 * `new*` 方法创建的官方检索对象归调用方所有，使用完必须按百度 SDK 要求调用 `destroy()`。
 */
class BaiduNativeAccess internal constructor() {
    fun mapOf(controller: MapController): BaiduMap? =
        (controller as? BaiduMapController)?.nativeMap

    fun mapViewOf(controller: MapController): MapView? =
        (controller as? BaiduMapController)?.nativeMapView

    fun newGeocoder(): GeoCoder = GeoCoder.newInstance()
    fun newPoiSearch(): PoiSearch = PoiSearch.newInstance()
    fun newRoutePlanSearch(): RoutePlanSearch = RoutePlanSearch.newInstance()
    fun newDistrictSearch(): DistrictSearch = DistrictSearch.newInstance()
    fun newWeatherSearch(): WeatherSearch = WeatherSearch.newInstance()
    fun newSuggestionSearch(): SuggestionSearch = SuggestionSearch.newInstance()
    fun newBusLineSearch(): BusLineSearch = BusLineSearch.newInstance()
    fun newBuildingSearch(): BuildingSearch = BuildingSearch.newInstance()
    fun newRecommendStopSearch(): RecommendStopSearch = RecommendStopSearch.newInstance()
    fun newShareUrlSearch(): ShareUrlSearch = ShareUrlSearch.newInstance()
}
