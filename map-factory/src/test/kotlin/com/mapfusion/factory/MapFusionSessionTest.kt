package com.mapfusion.factory

import android.content.Context
import android.os.Bundle
import android.view.View
import com.mapfusion.api.MapProvider
import com.mapfusion.api.capability.Capability
import com.mapfusion.api.capability.Geocoder
import com.mapfusion.api.capability.LocationClient
import com.mapfusion.api.capability.MapController
import com.mapfusion.api.capability.Navigator
import com.mapfusion.api.capability.PoiSearcher
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.capability.RoutePlanner
import com.mapfusion.api.model.CameraPosition
import com.mapfusion.api.model.CameraUpdate
import com.mapfusion.api.model.CircleOptions
import com.mapfusion.api.model.GroundOverlayOptions
import com.mapfusion.api.model.HeatMapOptions
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.LatLngBounds
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapCircle
import com.mapfusion.api.model.MapGroundOverlay
import com.mapfusion.api.model.MapHeatMap
import com.mapfusion.api.model.MapMarker
import com.mapfusion.api.model.MapOverlay
import com.mapfusion.api.model.MapPolygon
import com.mapfusion.api.model.MapPolyline
import com.mapfusion.api.model.MapSnapshot
import com.mapfusion.api.model.MapTextOverlay
import com.mapfusion.api.model.MapTileOverlay
import com.mapfusion.api.model.MapType
import com.mapfusion.api.model.MapUiOptions
import com.mapfusion.api.model.MarkerOptions
import com.mapfusion.api.model.PolygonOptions
import com.mapfusion.api.model.PolylineOptions
import com.mapfusion.api.model.TextOverlayOptions
import com.mapfusion.api.model.TileOverlayOptions
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.RequestHandle
import com.mapfusion.api.model.RouteRequest
import com.mapfusion.api.model.RouteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFusionSessionTest {

    @Test
    fun destroyed_session_does_not_lazily_create_capability() {
        val provider = FakeProvider()
        val session = MapFusionSession(provider, FakeMapController())

        session.destroy()

        assertEquals(0, provider.locationCalls)
        assertThrowsIllegalState { session.provider }
        assertThrowsIllegalState { session.mapController }
        assertThrowsIllegalState { session.locationClient }
    }

    @Test
    fun capability_failure_does_not_skip_map_or_provider_cleanup() {
        val provider = FakeProvider(locationDestroyFailure = true)
        val controller = FakeMapController()
        val session = MapFusionSession(provider, controller)
        // 初始化一个会在 destroy 时失败的能力。
        assertTrue(session.locationClient != null)

        val failure = runCatching { session.destroy() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, controller.destroyCalls)
        assertEquals(1, provider.destroyCalls)
        // destroy 已完成后，公开能力 getter 仍应拒绝访问。
        assertThrowsIllegalState { session.locationClient }
    }

    @Test
    fun embedded_navigation_uses_clients_independent_from_public_session_capabilities() {
        val provider = FakeProvider(withRoutePlanner = true)
        val session = MapFusionSession(provider, FakeMapController())

        assertTrue(session.locationClient != null)
        assertTrue(session.routePlanner != null)
        assertTrue(session.embeddedNavigator != null)

        assertEquals(2, provider.locationCalls)
        assertEquals(2, provider.routePlannerCalls)

        session.destroy()

        assertEquals(listOf(1, 1), provider.locationClients.map { it.destroyCalls })
        assertEquals(listOf(1, 1), provider.routePlanners.map { it.destroyCalls })
    }

    @Test
    fun location_display_uses_independent_client_and_session_destroys_it() {
        val provider = FakeProvider()
        val session = MapFusionSession(provider, FakeMapController())

        assertTrue(session.locationClient != null)
        assertTrue(session.locationDisplay != null)
        assertEquals(2, provider.locationCalls)

        session.destroy()

        assertEquals(listOf(1, 1), provider.locationClients.map { it.destroyCalls })
        assertThrowsIllegalState { session.locationDisplay }
    }

    private fun assertThrowsIllegalState(block: () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected IllegalStateException", error is IllegalStateException)
    }

    private class FakeProvider(
        private val locationDestroyFailure: Boolean = false,
        private val withRoutePlanner: Boolean = false,
    ) : MapProvider {
        var locationCalls = 0
        var routePlannerCalls = 0
        var destroyCalls = 0
        val locationClients = mutableListOf<FakeLocationClient>()
        val routePlanners = mutableListOf<FakeRoutePlanner>()

        override val provider: Provider = Provider.BAIDU
        override fun capabilities(): Set<Capability> = setOf(Capability.LOCATION)
        override fun mapController(mapView: Any): MapController? = null
        override fun createMapController(context: Context): MapController? = null
        override fun locationClient(): LocationClient {
            locationCalls++
            return FakeLocationClient(locationDestroyFailure).also(locationClients::add)
        }
        override fun geocoder(): Geocoder? = null
        override fun poiSearcher(): PoiSearcher? = null
        override fun routePlanner(): RoutePlanner? {
            if (!withRoutePlanner) return null
            routePlannerCalls++
            return FakeRoutePlanner().also(routePlanners::add)
        }
        override fun navigator(): Navigator? = null
        override fun rawProvider(): Any? = null
        override fun destroy() {
            destroyCalls++
        }
    }

    private class FakeLocationClient(
        private val destroyFailure: Boolean,
    ) : LocationClient {
        var destroyCalls = 0

        override fun requestSingleLocation(
            options: com.mapfusion.api.model.LocationOptions,
            asyncOptions: com.mapfusion.api.model.AsyncCallOptions,
            callback: MapCallback<com.mapfusion.api.model.MapLocation>,
        ): com.mapfusion.api.model.RequestHandle = TestRequestHandle()

        override fun startContinuousLocation(
            options: com.mapfusion.api.model.LocationOptions,
            asyncOptions: com.mapfusion.api.model.AsyncCallOptions,
            callback: MapCallback<com.mapfusion.api.model.MapLocation>,
        ): com.mapfusion.api.model.RequestHandle = TestRequestHandle()

        override fun stopContinuousLocation() = Unit

        override fun destroy() {
            destroyCalls++
            if (destroyFailure) error("location cleanup failed")
        }
    }

    private class FakeRoutePlanner : RoutePlanner {
        var destroyCalls = 0

        override fun plan(
            request: RouteRequest,
            asyncOptions: AsyncCallOptions,
            callback: MapCallback<RouteResult>,
        ): RequestHandle = TestRequestHandle()

        override fun destroy() {
            destroyCalls++
        }
    }

    /** 不触碰 Android View 构造，测试只覆盖会话清理顺序。 */
    private class FakeMapController : MapController {
        var destroyCalls = 0
        override val view: View get() = error("view is not used in this test")
        override fun onCreate(savedState: Bundle?) = Unit
        override fun onResume() = Unit
        override fun onPause() = Unit
        override fun onDestroy() {
            destroyCalls++
        }
        override fun onSaveInstanceState(outState: Bundle) = Unit
        override fun onLowMemory() = Unit
        override fun moveCamera(update: CameraUpdate) = Unit
        override fun getCameraPosition(): CameraPosition = error("camera is not used in this test")
        override fun setCameraBounds(bounds: LatLngBounds?) = Unit
        override fun setZoomRange(minZoom: Float, maxZoom: Float) = Unit
        override fun addMarker(options: MarkerOptions): MapMarker = error("overlay is not used in this test")
        override fun addPolyline(options: PolylineOptions): MapPolyline = error("overlay is not used in this test")
        override fun addPolygon(options: PolygonOptions): MapPolygon = error("overlay is not used in this test")
        override fun addCircle(options: CircleOptions): MapCircle = error("overlay is not used in this test")
        override fun addGroundOverlay(options: GroundOverlayOptions): MapGroundOverlay = error("overlay is not used in this test")
        override fun addText(options: TextOverlayOptions): MapTextOverlay = error("overlay is not used in this test")
        override fun addTileOverlay(options: TileOverlayOptions): MapTileOverlay = error("overlay is not used in this test")
        override fun addHeatMap(options: HeatMapOptions): MapHeatMap = error("overlay is not used in this test")
        override fun clearMarkers() = Unit
        override fun clearOverlays() = Unit
        override fun setMyLocationEnabled(enabled: Boolean) = Unit
        override fun setTrafficEnabled(enabled: Boolean) = Unit
        override fun setZoomControlsEnabled(enabled: Boolean) = Unit
        override fun setBuildingsEnabled(enabled: Boolean) = Unit
        override fun setIndoorEnabled(enabled: Boolean) = Unit
        override fun setMapPoiEnabled(enabled: Boolean) = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun setMapType(type: MapType) = Unit
        override fun getMapType(): MapType = MapType.NORMAL
        override fun setUiOptions(options: MapUiOptions) = Unit
        override fun snapshot(
            asyncOptions: com.mapfusion.api.model.AsyncCallOptions,
            callback: MapCallback<MapSnapshot>,
        ): com.mapfusion.api.model.RequestHandle = TestRequestHandle()
        override fun setOnMapClickListener(listener: ((LatLng) -> Unit)?) = Unit
        override fun setOnMapLongClickListener(listener: ((LatLng) -> Unit)?) = Unit
        override fun setOnMarkerClickListener(listener: ((MapMarker) -> Boolean)?) = Unit
        override fun setOnOverlayClickListener(listener: ((MapOverlay) -> Boolean)?) = Unit
        override fun setOnCameraIdleListener(listener: ((CameraPosition) -> Unit)?) = Unit
        override fun setOnMapLoadedListener(listener: (() -> Unit)?) = Unit
    }
}
