package com.mapfusion.amap

import android.content.Context
import com.amap.api.maps.AMap
import com.amap.api.maps.MapView
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.poisearch.PoiSearch
import com.amap.api.services.route.RouteSearch
import com.mapfusion.api.capability.MapController

/**
 * 高德高级能力逃生舱。
 *
 * 通用层未覆盖的热力图、瓦片、3D 模型、粒子、轨迹等功能，可通过原生 [AMap] 局部实现。
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
    fun newRouteSearch(): RouteSearch = RouteSearch(context)
}
