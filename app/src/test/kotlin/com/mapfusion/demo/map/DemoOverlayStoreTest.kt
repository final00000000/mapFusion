package com.mapfusion.demo.map

import com.mapfusion.api.model.MapOverlay
import com.mapfusion.api.model.MapMultiPointOverlay
import com.mapfusion.api.model.MapTileOverlay
import com.mapfusion.api.model.MapHeatMap
import com.mapfusion.api.model.MapRouteOverlay
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class DemoOverlayStoreTest {
    private class Overlay(override val id: String, private val fail: Boolean = false) : MapOverlay {
        var removals = 0
        override var visible = true
        override var zIndex = 0f
        override var tag: Any? = null
        override fun rawOverlay(): Any = this
        override fun remove() { removals++; if (fail) error("remove failure") }
    }
    @Test fun clearing_demo_handles_does_not_touch_navigation_or_track() {
        val store = DemoOverlayStore()
        val navigation = Overlay("session-navigation")
        val track = Overlay("session-track")
        val demo = store.track(Overlay("demo-poi"))
        store.clear()
        assertEquals(1, demo.removals)
        assertEquals(0, navigation.removals)
        assertEquals(0, track.removals)
    }
    @Test fun repeated_clear_does_not_repeat_removal() {
        val store = DemoOverlayStore()
        val demo = store.track(Overlay("demo"))
        store.clear()
        store.clear()
        assertEquals(1, demo.removals)
    }
    @Test fun failed_removal_does_not_prevent_other_handles_being_released() {
        val store = DemoOverlayStore()
        val broken = store.track(Overlay("broken", fail = true))
        val good = store.track(Overlay("good"))
        assertThrows(IllegalStateException::class.java) { store.clear() }
        assertEquals(1, broken.removals)
        assertEquals(1, good.removals)
        store.clear()
        assertEquals(1, good.removals)
    }
    @Test fun independent_overlay_handles_are_all_released_once() {
        val removals = mutableListOf<String>()
        fun <T> handle(type: Class<T>): T = type.cast(Proxy.newProxyInstance(
            type.classLoader, arrayOf(type),
        ) { _, method, _ ->
            when (method.name) {
                "remove" -> { removals += type.simpleName; null }
                else -> error("unexpected call: ${method.name}")
            }
        })
        val store = DemoOverlayStore()
        store.track(handle(MapMultiPointOverlay::class.java))
        store.track(handle(MapTileOverlay::class.java))
        store.track(handle(MapHeatMap::class.java))
        store.trackRoute(handle(MapRouteOverlay::class.java))
        store.clear()
        store.clear()
        assertEquals(listOf("MapMultiPointOverlay", "MapTileOverlay", "MapHeatMap", "MapRouteOverlay"), removals)
    }

}
