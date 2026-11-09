package com.mapfusion.demo.map

import com.mapfusion.api.capability.MapController
import com.mapfusion.api.model.*
import com.mapfusion.demo.presentation.main.DemoMapSettings
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class DemoMapIntegrationTest {
    private class Fixture {
        val calls = mutableListOf<String>()
        var removed = 0
        var rejectType = false
        var acceptedUi: MapUiOptions? = null
        val marker = Proxy.newProxyInstance(MapMarker::class.java.classLoader, arrayOf(MapMarker::class.java)) { _, method, _ ->
            when (method.name) {
                "remove" -> { removed++; Unit }
                "getId" -> "test-marker"
                else -> null
            }
        } as MapMarker
        val controller = Proxy.newProxyInstance(MapController::class.java.classLoader, arrayOf(MapController::class.java)) { _, method, args ->
            calls += method.name
            when (method.name) {
                "addMarker" -> marker
                "applyMapType" -> if (rejectType) MapResult.Failure(MapError(ErrorType.UNSUPPORTED, "unsupported")) else MapResult.Success(args!![0])
                "supportedMapLanguages" -> setOf(MapLanguage.CHINESE)
                "applyMapLanguage" -> MapResult.Success(args!![0])
                "applyUiOptions" -> (args!![0] as MapUiOptions).let { acceptedUi = it; MapResult.Success(it) }
                else -> Unit
            }
        } as MapController
    }
    @Test fun wrapper_registers_demo_handles_without_global_clear() {
        val fixture = Fixture()
        val store = DemoOverlayStore()
        val controller = DemoOverlayController(fixture.controller, store)
        controller.addMarker(MarkerOptions(LatLng(1.0, 1.0, CoordType.WGS84)))
        store.clear()
        assertEquals(1, fixture.removed)
        assertFalse(fixture.calls.contains("clearOverlays"))
    }
    @Test fun restore_applies_all_layer_switches_and_normalizes_supported_language() {
        val fixture = Fixture()
        val requested = DemoMapSettings(mapType = MapType.SATELLITE, mapLanguage = MapLanguage.ENGLISH,
            trafficEnabled = true, buildingsEnabled = false, indoorEnabled = true, mapPoiEnabled = false,
            compassEnabled = false, zoomControlsEnabled = false)
        val result = DemoMapSettingsApplier.apply(fixture.controller, requested) as MapResult.Success
        assertEquals(requested.copy(mapLanguage = MapLanguage.CHINESE), result.data)
        listOf("setTrafficEnabled", "setBuildingsEnabled", "setIndoorEnabled", "setMapPoiEnabled").forEach {
            assertTrue(fixture.calls.contains(it))
        }
        assertFalse(fixture.acceptedUi!!.compassEnabled)
        assertFalse(fixture.acceptedUi!!.zoomControlsEnabled)
    }
    @Test fun unsupported_type_does_not_claim_settings_applied() {
        val fixture = Fixture().apply { rejectType = true }
        val result = DemoMapSettingsApplier.apply(fixture.controller, DemoMapSettings())
        assertTrue(result is MapResult.Failure)
        assertEquals(listOf("applyMapType"), fixture.calls)
    }
}
