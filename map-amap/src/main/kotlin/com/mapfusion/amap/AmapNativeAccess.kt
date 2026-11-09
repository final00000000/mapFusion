package com.mapfusion.amap

import android.content.Context
import com.amap.api.maps.AMap
import com.amap.api.maps.MapView
import com.amap.api.maps.offlinemap.OfflineMapManager
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.busline.BusLineQuery
import com.amap.api.services.busline.BusLineSearch
import com.amap.api.services.busline.BusStationQuery
import com.amap.api.services.busline.BusStationSearch
import com.amap.api.services.district.DistrictSearch
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.poisearch.PoiSearch
import com.amap.api.services.poisearch.PoiSearchV2
import com.amap.api.services.route.DistanceSearch
import com.amap.api.services.route.RouteSearch
import com.amap.api.services.route.RouteSearchV2
import com.amap.api.services.weather.WeatherSearch
import com.mapfusion.api.capability.MapController

/**
 * 高德高级能力逃生舱。
 *
 * 通用层未覆盖的热力图、瓦片、3D 模型、粒子、轨迹等功能，可通过原生 [AMap] 局部实现。
 * `new*` 方法统一复用 application context；监听器和异步请求的释放仍按高德 SDK 对应对象要求处理。
 */
class AmapNativeAccess internal constructor(
    private val context: Context,
) {
    fun mapOf(controller: MapController): AMap? =
        (controller as? AmapMapController)?.nativeMap

    fun mapViewOf(controller: MapController): MapView? =
        (controller as? AmapMapController)?.nativeMapView

    fun newGeocodeSearch(): GeocodeSearch = GeocodeSearch(context)
    fun newPoiSearch(query: PoiSearch.Query): PoiSearch = PoiSearch(context, query)
    fun newPoiSearchV2(query: PoiSearchV2.Query): PoiSearchV2 = PoiSearchV2(context, query)
    fun newRouteSearch(): RouteSearch = RouteSearch(context)
    fun newRouteSearchV2(): RouteSearchV2 = RouteSearchV2(context)
    fun newDistrictSearch(): DistrictSearch = DistrictSearch(context)
    fun newWeatherSearch(): WeatherSearch = WeatherSearch(context)
    fun newInputTips(query: InputtipsQuery): Inputtips = Inputtips(context, query)
    fun newBusLineSearch(query: BusLineQuery): BusLineSearch = BusLineSearch(context, query)
    fun newBusStationSearch(query: BusStationQuery): BusStationSearch = BusStationSearch(context, query)
    fun newDistanceSearch(): DistanceSearch = DistanceSearch(context)

    /**
     * 创建高德官方离线地图管理器。百度地图 8.2 没有对应公开 API，因此该能力不进入统一层。
     * 返回对象归调用方所有，使用完必须调用 [OfflineMapManager.destroy]。
     */
    @Throws(Exception::class)
    fun newOfflineMapManager(
        listener: OfflineMapManager.OfflineMapDownloadListener,
    ): OfflineMapManager = OfflineMapManager(context, listener)
}
