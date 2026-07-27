package com.mapfusion.baidu

import android.content.Context
import com.baidu.mapapi.map.MapView
import com.mapfusion.api.MapProvider
import com.mapfusion.api.capability.Capability
import com.mapfusion.api.capability.DistrictSearcher
import com.mapfusion.api.capability.Geocoder
import com.mapfusion.api.capability.LocationClient
import com.mapfusion.api.capability.MapController
import com.mapfusion.api.capability.Navigator
import com.mapfusion.api.capability.PoiSearcher
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.capability.RoutePlanner
import com.mapfusion.api.capability.WeatherService

/**
 * 百度地图聚合入口。
 *
 * @param appContext application context,避免持有 Activity。
 * @param apiKey 百度地图 AK,由工厂完成 SDK 注入与初始化。
 */
internal class BaiduMapProvider(
    private val appContext: Context,
    private val apiKey: String,
) : MapProvider {

    private val nativeAccess = BaiduNativeAccess()

    override val provider: Provider = Provider.BAIDU

    override fun capabilities(): Set<Capability> = setOf(
        Capability.MAP_CONTROLLER,
        Capability.LOCATION,
        Capability.GEOCODER,
        Capability.POI_SEARCH,
        Capability.ROUTE_PLANNING,
        Capability.NAVIGATION,
        Capability.DISTRICT_SEARCH,
        Capability.WEATHER,
    )

    override fun mapController(mapView: Any): MapController? {
        return (mapView as? MapView)?.let(::BaiduMapController)
    }

    override fun createMapController(context: Context): MapController =
        BaiduMapController(MapView(context))

    override fun locationClient(): LocationClient = BaiduLocationClient(appContext)
    override fun geocoder(): Geocoder = BaiduGeocoder()
    override fun poiSearcher(): PoiSearcher = BaiduPoiSearcher()
    override fun routePlanner(): RoutePlanner = BaiduRoutePlanner()
    override fun districtSearcher(): DistrictSearcher = BaiduDistrictSearcher()
    override fun weatherService(): WeatherService = BaiduWeatherService()
    override fun navigator(): Navigator = BaiduNavigator()

    override fun rawProvider(): BaiduNativeAccess = nativeAccess

    // Provider 本身不缓存能力实例，具体原生资源由各能力的 destroy() 释放。
    override fun destroy() = Unit
}
