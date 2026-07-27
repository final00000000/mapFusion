package com.mapfusion.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TileOverlayOptionsTest {

    @Test
    fun constructor_rejectsInvalidDimensionsAndZoomRange() {
        val provider = MapTileProvider { _, _, _ -> null }

        assertThrows(IllegalArgumentException::class.java) {
            TileOverlayOptions(provider, tileWidth = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileOverlayOptions(provider, tileHeight = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileOverlayOptions(provider, minZoom = 12, maxZoom = 11)
        }
    }

    @Test
    fun loadTile_doesNotCallProviderOutsideZoomRange() {
        var calls = 0
        val options = TileOverlayOptions(
            provider = MapTileProvider { _, _, _ ->
                calls++
                MapTile(256, 256, byteArrayOf(1))
            },
            minZoom = 5,
            maxZoom = 8,
        )

        assertNull(options.loadTile(1, 1, 4))
        assertNull(options.loadTile(1, 1, 9))
        assertEquals(0, calls)
        assertNotNull(options.loadTile(1, 1, 5))
        assertEquals(1, calls)
    }

    @Test
    fun containsTile_filtersUsingWebMercatorBounds() {
        val options = TileOverlayOptions(
            provider = MapTileProvider { _, _, _ -> MapTile(256, 256, byteArrayOf(1)) },
            minZoom = 2,
            maxZoom = 2,
            bounds = LatLngBounds(
                southwest = LatLng(1.0, 1.0, CoordType.GCJ02),
                northeast = LatLng(10.0, 10.0, CoordType.GCJ02),
            ),
        )

        assertTrue(options.containsTile(x = 2, y = 1, zoom = 2))
        assertFalse(options.containsTile(x = 1, y = 1, zoom = 2))
        assertFalse(options.containsTile(x = 2, y = 2, zoom = 2))
        assertFalse(options.containsTile(x = -1, y = 1, zoom = 2))
    }

    @Test
    fun loadTile_rejectsProviderTileWithUnexpectedDimensions() {
        val options = TileOverlayOptions(
            provider = MapTileProvider { _, _, _ -> MapTile(128, 256, byteArrayOf(1)) },
            minZoom = 2,
            maxZoom = 2,
        )

        assertThrows(IllegalArgumentException::class.java) {
            options.loadTile(2, 1, 2)
        }
    }

    @Test
    fun constructor_rejectsUndeclaredOrInvertedBounds() {
        val provider = MapTileProvider { _, _, _ -> null }

        assertThrows(IllegalArgumentException::class.java) {
            TileOverlayOptions(
                provider = provider,
                bounds = LatLngBounds(LatLng(1.0, 1.0), LatLng(10.0, 10.0)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileOverlayOptions(
                provider = provider,
                bounds = LatLngBounds(
                    LatLng(10.0, 10.0, CoordType.GCJ02),
                    LatLng(1.0, 1.0, CoordType.GCJ02),
                ),
            )
        }
    }
}
