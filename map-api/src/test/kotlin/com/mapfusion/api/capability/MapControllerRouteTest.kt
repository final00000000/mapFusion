package com.mapfusion.api.capability

import android.os.Bundle
import android.view.View
import com.mapfusion.api.model.CameraPosition
import com.mapfusion.api.model.CameraUpdate
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.CircleOptions
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.ErrorType
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
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.MapSnapshot
import com.mapfusion.api.model.MapTextOverlay
import com.mapfusion.api.model.MapTileOverlay
import com.mapfusion.api.model.MapType
import com.mapfusion.api.model.MapUiOptions
import com.mapfusion.api.model.MarkerIcon
import com.mapfusion.api.model.MarkerOptions
import com.mapfusion.api.model.MultiPointItem
import com.mapfusion.api.model.MultiPointOverlayOptions
import com.mapfusion.api.model.PolygonOptions
import com.mapfusion.api.model.PolylineOptions
import com.mapfusion.api.model.RouteMarkerOptions
import com.mapfusion.api.model.RouteOverlayOptions
import com.mapfusion.api.model.RoutePath
import com.mapfusion.api.model.RouteRequest
import com.mapfusion.api.model.RequestHandle
import com.mapfusion.api.model.TextOverlayOptions
import com.mapfusion.api.model.TileOverlayOptions
import com.mapfusion.api.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class MapControllerRouteTest {

    private val origin = LatLng(39.9087, 116.3975, CoordType.GCJ02)
    private val destination = LatLng(39.9181, 116.4219, CoordType.GCJ02)
    private val path = RoutePath(
        distanceMeters = 2_400,
        durationSeconds = 900,
        steps = emptyList(),
        polyline = listOf(origin, LatLng(39.913, 116.409, CoordType.GCJ02), destination),
    )

    @Test
    fun addRoute_appliesCustomEndpointIconsAndLineStyle() {
        val controller = RecordingMapController()
        val request = RouteRequest(TravelMode.WALKING, origin, destination)
        val startIcon = MarkerIcon.Resource(101)
        val endIcon = MarkerIcon.Bytes(byteArrayOf(1, 2, 3))

        val route = controller.addRoute(
            request,
            path,
            RouteOverlayOptions(
                startMarker = RouteMarkerOptions(icon = startIcon, title = "我的起点"),
                endMarker = RouteMarkerOptions(icon = endIcon, title = "我的终点"),
                lineWidth = 18f,
                lineColor = 0xFF008577.toInt(),
                lineDotted = true,
                lineClickable = false,
            ),
        )

        assertSame(path, route.path)
        assertEquals(path.polyline, controller.polylineOptions.single().points)
        assertEquals(18f, controller.polylineOptions.single().width)
        assertEquals(0xFF008577.toInt(), controller.polylineOptions.single().color)
        assertEquals(true, controller.polylineOptions.single().dotted)
        assertEquals(false, controller.polylineOptions.single().clickable)
        assertEquals(origin, controller.markerOptions[0].position)
        assertEquals(startIcon, controller.markerOptions[0].icon)
        assertEquals("我的起点", controller.markerOptions[0].title)
        assertEquals(destination, controller.markerOptions[1].position)
        assertEquals(endIcon, controller.markerOptions[1].icon)
        assertEquals("我的终点", controller.markerOptions[1].title)
    }

    @Test
    fun routeRemove_isIdempotentForAllCreatedOverlays() {
        val controller = RecordingMapController()
        val route = controller.addRoute(path)

        route.remove()
        route.remove()
        route.close()

        assertEquals(1, controller.polylines.single().removeCount)
        assertEquals(listOf(1, 1), controller.markers.map { it.removeCount })
    }

    @Test
    fun addRoute_rollsBackAlreadyCreatedOverlaysWhenCreationFails() {
        val controller = RecordingMapController(failOnMarkerNumber = 2)

        assertThrows(IllegalStateException::class.java) {
            controller.addRoute(path)
        }

        assertEquals(1, controller.polylines.single().removeCount)
        assertEquals(1, controller.markers.single().removeCount)
    }

    @Test
    fun addRoute_rejectsPathWithoutDrawableGeometryBeforeCreatingOverlays() {
        val controller = RecordingMapController()
        val emptyPath = path.copy(polyline = emptyList())

        assertThrows(IllegalArgumentException::class.java) {
            controller.addRoute(emptyPath)
        }

        assertEquals(0, controller.polylineOptions.size)
        assertEquals(0, controller.markerOptions.size)
    }

    @Test
    fun fitPoints_usesBoundsAndPaddingForMultiplePoints() {
        val controller = RecordingMapController()
        val points = listOf(origin, destination)

        controller.fitPoints(points, paddingPixels = 48, animated = false, durationMs = 0)

        val update = controller.cameraUpdates.single()
        assertEquals(LatLngBounds(origin, destination), update.bounds)
        assertEquals(48, update.paddingPixels)
        assertEquals(false, update.animated)
        assertEquals(0, update.durationMs)
    }

    @Test
    fun fitPoints_usesSinglePointZoomAnd_rejectsMixedCoordinates() {
        val controller = RecordingMapController()
        controller.fitPoints(listOf(origin), singlePointZoom = 15f)

        val update = controller.cameraUpdates.single()
        assertEquals(origin, update.target)
        assertEquals(15f, update.zoom)
        assertThrows(IllegalArgumentException::class.java) {
            controller.fitPoints(listOf(origin, destination.copy(coordType = CoordType.BD09)))
        }
        assertEquals(1, controller.cameraUpdates.size)
    }

    @Test
    fun applyMapType_reportsSupportedAndUnsupportedTypesWithoutFallback() {
        val controller = RecordingMapController()

        assertEquals(setOf(MapType.NORMAL, MapType.SATELLITE), controller.supportedMapTypes())
        assertEquals(MapResult.Success(MapType.SATELLITE), controller.applyMapType(MapType.SATELLITE))
        assertEquals(MapType.SATELLITE, controller.getMapType())

        val failure = controller.applyMapType(MapType.NIGHT) as MapResult.Failure
        assertEquals(ErrorType.UNSUPPORTED, failure.error.type)
        assertEquals(MapType.SATELLITE, controller.getMapType())
    }

    @Test
    fun applyMapType_failsWhenNativeControllerDoesNotApplyRequestedType() {
        val controller = RecordingMapController(ignoreMapTypeChanges = true)

        val failure = controller.applyMapType(MapType.SATELLITE) as MapResult.Failure

        assertEquals(ErrorType.UNSUPPORTED, failure.error.type)
        assertEquals(MapType.NORMAL, controller.getMapType())
    }

    @Test
    fun customControllerMustOptInToNativeMultiPointInsteadOfSilentlyUsingMarkers() {
        val controller = RecordingMapController()
        val options = MultiPointOverlayOptions(
            listOf(MultiPointItem("one", origin)),
        )

        assertThrows(UnsupportedOperationException::class.java) {
            controller.addMultiPointOverlay(options)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            controller.setOnMultiPointClickListener { _, _ -> true }
        }
    }
}

private class RecordingMapController(
    private val failOnMarkerNumber: Int? = null,
    private val ignoreMapTypeChanges: Boolean = false,
) : MapController {
    val markerOptions = mutableListOf<MarkerOptions>()
    val polylineOptions = mutableListOf<PolylineOptions>()
    val cameraUpdates = mutableListOf<CameraUpdate>()
    val markers = mutableListOf<RecordingMarker>()
    val polylines = mutableListOf<RecordingPolyline>()
    private var mapType = MapType.NORMAL

    override val view: View get() = error("测试未使用地图 View")

    override fun addMarker(options: MarkerOptions): MapMarker {
        markerOptions += options
        if (markerOptions.size == failOnMarkerNumber) error("模拟 Marker 创建失败")
        return RecordingMarker(options).also(markers::add)
    }

    override fun addPolyline(options: PolylineOptions): MapPolyline {
        polylineOptions += options
        return RecordingPolyline(options).also(polylines::add)
    }

    override fun onCreate(savedState: Bundle?) = Unit
    override fun onResume() = Unit
    override fun onPause() = Unit
    override fun onDestroy() = Unit
    override fun onSaveInstanceState(outState: Bundle) = Unit
    override fun onLowMemory() = Unit
    override fun moveCamera(update: CameraUpdate) {
        cameraUpdates += update
    }
    override fun getCameraPosition(): CameraPosition = error("测试未使用")
    override fun setCameraBounds(bounds: LatLngBounds?) = Unit
    override fun setZoomRange(minZoom: Float, maxZoom: Float) = Unit
    override fun addPolygon(options: PolygonOptions): MapPolygon = error("测试未使用")
    override fun addCircle(options: CircleOptions): MapCircle = error("测试未使用")
    override fun addGroundOverlay(options: GroundOverlayOptions): MapGroundOverlay = error("测试未使用")
    override fun addText(options: TextOverlayOptions): MapTextOverlay = error("测试未使用")
    override fun addTileOverlay(options: TileOverlayOptions): MapTileOverlay = error("测试未使用")
    override fun addHeatMap(options: HeatMapOptions): MapHeatMap = error("测试未使用")
    override fun clearMarkers() = Unit
    override fun clearOverlays() = Unit
    override fun setMyLocationEnabled(enabled: Boolean) = Unit
    override fun setTrafficEnabled(enabled: Boolean) = Unit
    override fun setZoomControlsEnabled(enabled: Boolean) = Unit
    override fun setBuildingsEnabled(enabled: Boolean) = Unit
    override fun setIndoorEnabled(enabled: Boolean) = Unit
    override fun setMapPoiEnabled(enabled: Boolean) = Unit
    override fun supportedMapTypes(): Set<MapType> = setOf(MapType.NORMAL, MapType.SATELLITE)
    @Deprecated("测试旧兼容接口", ReplaceWith("applyMapType(type)"))
    override fun setMapType(type: MapType) {
        if (!ignoreMapTypeChanges) mapType = type
    }
    override fun getMapType(): MapType = mapType
    override fun setUiOptions(options: MapUiOptions) = Unit
    override fun snapshot(
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<MapSnapshot>,
    ): RequestHandle = error("测试未使用")
    override fun setOnMapClickListener(listener: ((LatLng) -> Unit)?) = Unit
    override fun setOnMapLongClickListener(listener: ((LatLng) -> Unit)?) = Unit
    override fun setOnMarkerClickListener(listener: ((MapMarker) -> Boolean)?) = Unit
    override fun setOnOverlayClickListener(listener: ((MapOverlay) -> Boolean)?) = Unit
    override fun setOnCameraIdleListener(listener: ((CameraPosition) -> Unit)?) = Unit
    override fun setOnMapLoadedListener(listener: (() -> Unit)?) = Unit
}

private abstract class RecordingOverlay : MapOverlay {
    var removeCount = 0
    override val id: String = "test-overlay"
    override var visible: Boolean = true
    override var zIndex: Float = 0f
    override var tag: Any? = null
    override fun remove() {
        removeCount++
    }
    override fun rawOverlay(): Any = this
}

private class RecordingMarker(options: MarkerOptions) : RecordingOverlay(), MapMarker {
    override var position: LatLng = options.position
    override var title: String? = options.title
    override var snippet: String? = options.snippet
    override var rotation: Float = options.rotation
    override var alpha: Float = options.alpha
    override var flat: Boolean = options.flat
    override fun showInfoWindow() = Unit
    override fun hideInfoWindow() = Unit
}

private class RecordingPolyline(options: PolylineOptions) : RecordingOverlay(), MapPolyline {
    override var points: List<LatLng> = options.points
    override var width: Float = options.width
    override var color: Int = options.color
}
