package com.mapfusion.factory

import android.os.Bundle
import android.view.View
import com.mapfusion.api.capability.LocationClient
import com.mapfusion.api.capability.MapController
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.CameraPosition
import com.mapfusion.api.model.CameraUpdate
import com.mapfusion.api.model.CircleOptions
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.GroundOverlayOptions
import com.mapfusion.api.model.HeatMapOptions
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.LatLngBounds
import com.mapfusion.api.model.LocationAccuracyStyle
import com.mapfusion.api.model.LocationDisplayEvent
import com.mapfusion.api.model.LocationDisplayOptions
import com.mapfusion.api.model.LocationDisplayState
import com.mapfusion.api.model.LocationDisplayStyle
import com.mapfusion.api.model.LocationFollowMode
import com.mapfusion.api.model.LocationMarkerStyle
import com.mapfusion.api.model.LocationOptions
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapCircle
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapGroundOverlay
import com.mapfusion.api.model.MapHeatMap
import com.mapfusion.api.model.MapLocation
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
import com.mapfusion.api.model.MarkerOptions
import com.mapfusion.api.model.PolygonOptions
import com.mapfusion.api.model.PolylineOptions
import com.mapfusion.api.model.RequestHandle
import com.mapfusion.api.model.TextOverlayOptions
import com.mapfusion.api.model.TileOverlayOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultLocationDisplayTest {

    @Test
    fun firstFixRendersCustomMarkerAccuracyAndMovesCameraOnlyOnce() {
        val client = FakeLocationClient()
        val controller = FakeMapController()
        val display = DefaultLocationDisplay(client, controller)
        val events = mutableListOf<LocationDisplayEvent>()

        val result = display.start(
            LocationDisplayOptions(
                style = LocationDisplayStyle(
                    marker = LocationMarkerStyle(alpha = 0.65f, flat = true),
                    accuracy = LocationAccuracyStyle(
                        strokeWidth = 3f,
                        strokeColor = 0xFF123456.toInt(),
                        fillColor = 0x33123456,
                    ),
                ),
                followMode = LocationFollowMode.FIRST_FIX,
                followZoom = 18f,
            ),
            events::add,
        )

        assertTrue(result is MapResult.Success)
        client.success(location(39.0, 116.0, accuracy = 18f, bearing = 45f))
        client.success(location(39.1, 116.1, accuracy = 12f, bearing = 90f))

        assertEquals(LocationDisplayState.RUNNING, display.state)
        assertEquals(2, events.filterIsInstance<LocationDisplayEvent.LocationUpdated>().size)
        assertEquals(1, controller.markers.size)
        assertEquals(1, controller.circles.size)
        assertEquals(1, controller.cameraUpdates.size)
        assertEquals(18f, controller.cameraUpdates.single().zoom)
        assertEquals(90f, controller.markers.single().rotation)
        assertEquals(0.65f, controller.markers.single().alpha)
        assertTrue(controller.markers.single().flat)
        assertEquals(12.0, controller.circles.single().radiusMeters, 0.0)
        assertEquals(0x33123456, controller.circles.single().fillColor)
    }

    @Test
    fun externalClearIsDetectedAndDisplayOverlaysAreRecreated() {
        val client = FakeLocationClient()
        val controller = FakeMapController()
        val display = DefaultLocationDisplay(client, controller)
        display.start()
        client.success(location(39.0, 116.0))

        controller.clearOverlays()
        assertTrue(controller.markers.single().isRemoved)
        assertTrue(controller.circles.single().isRemoved)

        client.success(location(39.1, 116.1))

        assertEquals(2, controller.markers.size)
        assertEquals(2, controller.circles.size)
        assertFalse(controller.markers.last().isRemoved)
        assertFalse(controller.circles.last().isRemoved)
    }

    @Test
    fun accuracyFilterRejectsPoorFixWithoutMovingMap() {
        val client = FakeLocationClient()
        val controller = FakeMapController()
        val display = DefaultLocationDisplay(client, controller)
        val events = mutableListOf<LocationDisplayEvent>()
        display.start(LocationDisplayOptions(maxAccuracyMeters = 20f), events::add)

        client.success(location(39.0, 116.0, accuracy = 35f))

        assertTrue(events.any { it is LocationDisplayEvent.AccuracyRejected })
        assertTrue(controller.markers.isEmpty())
        assertTrue(controller.cameraUpdates.isEmpty())

        client.success(location(39.0, 116.0, accuracy = 10f))
        assertEquals(1, controller.markers.size)
        assertEquals(1, controller.cameraUpdates.size)
    }

    @Test
    fun lifecyclePauseResumeOwnsOnlyItsSubscriptionAndDestroyDropsLateCallbacks() {
        val client = FakeLocationClient()
        val controller = FakeMapController()
        val display = DefaultLocationDisplay(client, controller)
        display.start(LocationDisplayOptions(pauseWhenBackground = true))

        display.onPause()
        assertEquals(LocationDisplayState.PAUSED, display.state)
        assertTrue(client.handles.first().isCancelled)

        display.onResume()
        assertEquals(LocationDisplayState.RUNNING, display.state)
        assertEquals(2, client.startCount)

        display.destroy()
        assertEquals(LocationDisplayState.DESTROYED, display.state)
        assertTrue(client.destroyed)
        assertTrue(client.handles.last().isCancelled)

        client.successFrom(1, location(39.0, 116.0))
        assertTrue(controller.markers.isEmpty())
    }

    @Test
    fun styleCanBeReplacedWithoutRestartingLocationOrRefollowingCamera() {
        val client = FakeLocationClient()
        val controller = FakeMapController()
        val display = DefaultLocationDisplay(client, controller)
        display.start()
        client.success(location(39.0, 116.0, bearing = 30f))

        val result = display.updateStyle(
            LocationDisplayStyle(
                marker = LocationMarkerStyle(alpha = 0.4f, flat = false, rotateWithBearing = false),
                accuracy = null,
            ),
        )

        assertTrue(result is MapResult.Success)
        assertEquals(1, client.startCount)
        assertEquals(1, controller.cameraUpdates.size)
        assertEquals(2, controller.markers.size)
        assertTrue(controller.markers.first().isRemoved)
        assertEquals(0f, controller.markers.last().rotation)
        assertEquals(0.4f, controller.markers.last().alpha)
        assertTrue(controller.circles.single().isRemoved)
    }

    @Test
    fun invalidOptionsDoNotStartNativeLocation() {
        val client = FakeLocationClient()
        val display = DefaultLocationDisplay(client, FakeMapController())

        val result = display.start(LocationDisplayOptions(maxAccuracyMeters = -1f))

        assertTrue(result is MapResult.Failure)
        assertEquals(ErrorType.INVALID_PARAM, (result as MapResult.Failure).error.type)
        assertEquals(0, client.startCount)
        assertEquals(LocationDisplayState.IDLE, display.state)
    }

    private fun location(
        latitude: Double,
        longitude: Double,
        accuracy: Float = 8f,
        bearing: Float = 0f,
    ) = MapLocation(
        position = LatLng(latitude, longitude, CoordType.GCJ02),
        accuracy = accuracy,
        bearing = bearing,
        time = 1L,
    )

    private class FakeLocationClient : LocationClient {
        private val subscriptions = mutableListOf<Subscription>()
        var startCount = 0
        var destroyed = false
        val handles: List<TestRequestHandle> get() = subscriptions.map(Subscription::handle)

        override fun requestSingleLocation(
            options: LocationOptions,
            asyncOptions: AsyncCallOptions,
            callback: MapCallback<MapLocation>,
        ): RequestHandle = TestRequestHandle()

        override fun startContinuousLocation(
            options: LocationOptions,
            asyncOptions: AsyncCallOptions,
            callback: MapCallback<MapLocation>,
        ): RequestHandle {
            startCount++
            val handle = TestRequestHandle.active {
                callback.onResult(MapResult.Failure(MapError(ErrorType.CANCELLED, "cancelled")))
            }
            subscriptions += Subscription(callback, handle)
            return handle
        }

        override fun stopContinuousLocation() = Unit

        override fun destroy() {
            destroyed = true
        }

        fun success(location: MapLocation) = successFrom(subscriptions.lastIndex, location)

        fun successFrom(index: Int, location: MapLocation) {
            subscriptions[index].callback.onResult(MapResult.Success(location))
        }

        private data class Subscription(
            val callback: MapCallback<MapLocation>,
            val handle: TestRequestHandle,
        )
    }

    private class FakeMapController : MapController {
        val markers = mutableListOf<FakeMarker>()
        val circles = mutableListOf<FakeCircle>()
        val cameraUpdates = mutableListOf<CameraUpdate>()

        override val view: View get() = error("view is not used in this test")
        override fun onCreate(savedState: Bundle?) = Unit
        override fun onResume() = Unit
        override fun onPause() = Unit
        override fun onDestroy() = Unit
        override fun onSaveInstanceState(outState: Bundle) = Unit
        override fun onLowMemory() = Unit
        override fun moveCamera(update: CameraUpdate) {
            cameraUpdates += update
        }
        override fun getCameraPosition(): CameraPosition = error("camera is not used in this test")
        override fun setCameraBounds(bounds: LatLngBounds?) = Unit
        override fun setZoomRange(minZoom: Float, maxZoom: Float) = Unit
        override fun addMarker(options: MarkerOptions): MapMarker = FakeMarker(options).also(markers::add)
        override fun addPolyline(options: PolylineOptions): MapPolyline = error("not used")
        override fun addPolygon(options: PolygonOptions): MapPolygon = error("not used")
        override fun addCircle(options: CircleOptions): MapCircle = FakeCircle(options).also(circles::add)
        override fun addGroundOverlay(options: GroundOverlayOptions): MapGroundOverlay = error("not used")
        override fun addText(options: TextOverlayOptions): MapTextOverlay = error("not used")
        override fun addTileOverlay(options: TileOverlayOptions): MapTileOverlay = error("not used")
        override fun addHeatMap(options: HeatMapOptions): MapHeatMap = error("not used")
        override fun clearMarkers() = markers.forEach(MapMarker::remove)
        override fun clearOverlays() {
            markers.forEach(MapMarker::remove)
            circles.forEach(MapCircle::remove)
        }
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
        override fun snapshot(asyncOptions: AsyncCallOptions, callback: MapCallback<MapSnapshot>): RequestHandle =
            TestRequestHandle()
        override fun setOnMapClickListener(listener: ((LatLng) -> Unit)?) = Unit
        override fun setOnMapLongClickListener(listener: ((LatLng) -> Unit)?) = Unit
        override fun setOnMarkerClickListener(listener: ((MapMarker) -> Boolean)?) = Unit
        override fun setOnOverlayClickListener(listener: ((MapOverlay) -> Boolean)?) = Unit
        override fun setOnCameraIdleListener(listener: ((CameraPosition) -> Unit)?) = Unit
        override fun setOnMapLoadedListener(listener: (() -> Unit)?) = Unit
    }

    private class FakeMarker(options: MarkerOptions) : MapMarker {
        override val id = "marker"
        override var isRemoved = false
            private set
        override var position = options.position
        override var title = options.title
        override var snippet = options.snippet
        override var rotation = options.rotation
        override var alpha = options.alpha
        override var flat = options.flat
        override var visible = options.visible
        override var zIndex = options.zIndex
        override var tag = options.tag
        override fun remove() {
            isRemoved = true
        }
        override fun rawOverlay(): Any = this
        override fun showInfoWindow() = Unit
        override fun hideInfoWindow() = Unit
    }

    private class FakeCircle(options: CircleOptions) : MapCircle {
        override val id = "circle"
        override var isRemoved = false
            private set
        override var center = options.center
        override var radiusMeters = options.radiusMeters
        override var strokeWidth = options.strokeWidth
        override var strokeColor = options.strokeColor
        override var fillColor = options.fillColor
        override var visible = options.visible
        override var zIndex = options.zIndex
        override var tag = options.tag
        override fun remove() {
            isRemoved = true
        }
        override fun rawOverlay(): Any = this
    }
}
