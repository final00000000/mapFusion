package com.mapfusion.demo.map

import com.mapfusion.api.capability.MapController
import com.mapfusion.api.model.CircleOptions
import com.mapfusion.api.model.GroundOverlayOptions
import com.mapfusion.api.model.HeatMapOptions
import com.mapfusion.api.model.MapOverlay
import com.mapfusion.api.model.MapMultiPointOverlay
import com.mapfusion.api.model.MapTileOverlay
import com.mapfusion.api.model.MapHeatMap
import com.mapfusion.api.model.MapRouteOverlay
import com.mapfusion.api.model.MarkerOptions
import com.mapfusion.api.model.MultiPointOverlayOptions
import com.mapfusion.api.model.PolygonOptions
import com.mapfusion.api.model.PolylineOptions
import com.mapfusion.api.model.RouteOverlayOptions
import com.mapfusion.api.model.RoutePath
import com.mapfusion.api.model.RouteRequest
import com.mapfusion.api.model.TextOverlayOptions
import com.mapfusion.api.model.TileOverlayOptions

/** Only explicitly registered Demo handles are owned here; Session components use the original controller. */
internal class DemoOverlayStore {
    private val removals = mutableListOf<() -> Unit>()
    fun <T : MapOverlay> track(overlay: T): T = overlay.also { removals += { it.remove() } }
    fun track(overlay: MapMultiPointOverlay): MapMultiPointOverlay = overlay.also { removals += { it.remove() } }
    fun track(overlay: MapTileOverlay): MapTileOverlay = overlay.also { removals += { it.remove() } }
    fun track(overlay: MapHeatMap): MapHeatMap = overlay.also { removals += { it.remove() } }
    fun trackRoute(route: MapRouteOverlay): MapRouteOverlay = route.also { removals += { it.remove() } }

    fun clear() {
        val pending = removals.toList()
        removals.clear()
        var failure: Throwable? = null
        pending.forEach { remove ->
            try { remove() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}

internal class DemoOverlayController(
    private val delegate: MapController,
    private val store: DemoOverlayStore,
) : MapController by delegate {
    override fun addMarker(options: MarkerOptions) = store.track(delegate.addMarker(options))
    override fun addMultiPointOverlay(options: MultiPointOverlayOptions) = store.track(delegate.addMultiPointOverlay(options))
    override fun addPolyline(options: PolylineOptions) = store.track(delegate.addPolyline(options))
    override fun addPolygon(options: PolygonOptions) = store.track(delegate.addPolygon(options))
    override fun addCircle(options: CircleOptions) = store.track(delegate.addCircle(options))
    override fun addGroundOverlay(options: GroundOverlayOptions) = store.track(delegate.addGroundOverlay(options))
    override fun addText(options: TextOverlayOptions) = store.track(delegate.addText(options))
    override fun addTileOverlay(options: TileOverlayOptions) = store.track(delegate.addTileOverlay(options))
    override fun addHeatMap(options: HeatMapOptions) = store.track(delegate.addHeatMap(options))
    override fun addRoute(request: RouteRequest, path: RoutePath, options: RouteOverlayOptions) =
        store.trackRoute(delegate.addRoute(request, path, options))
    override fun addRoute(path: RoutePath, options: RouteOverlayOptions) = store.trackRoute(delegate.addRoute(path, options))
}
