package com.mapfusion.amap

import android.content.Context
import com.amap.api.maps.MapView
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

/** 高德地图聚合入口。 */
internal class AmapMapProvider(
    private val appContext: Context,
    private val apiKey: String,
) : MapProvider {

    private val nativeAccess = AmapNativeAccess(appContext)

    override val provider: Provider = Provider.AMAP

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

    override fun mapController(mapView: Any): MapController? =
        (mapView as? MapView)?.let(::AmapMapController)

    override fun createMapController(context: Context): MapController =
        AmapMapController(MapView(context))
    override fun locationClient(): LocationClient = AmapLocationClient(appContext)
    override fun geocoder(): Geocoder = AmapGeocoder(appContext)
    override fun poiSearcher(): PoiSearcher = AmapPoiSearcher(appContext)
    override fun routePlanner(): RoutePlanner = AmapRoutePlanner(appContext)
    override fun districtSearcher(): DistrictSearcher = AmapDistrictSearcher(appContext)
    override fun weatherService(): WeatherService = AmapWeatherService(appContext)
    override fun navigator(): Navigator = AmapNavigator()

    override fun rawProvider(): AmapNativeAccess = nativeAccess

    // Provider 本身不缓存能力实例，具体原生资源由各能力的 destroy() 释放。
    override fun destroy() = Unit
}
