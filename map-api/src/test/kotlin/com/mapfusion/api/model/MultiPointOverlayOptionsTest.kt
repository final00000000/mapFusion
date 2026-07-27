package com.mapfusion.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MultiPointOverlayOptionsTest {

    private val point = MultiPointItem(
        id = "point-1",
        position = LatLng(39.9087, 116.3975, CoordType.WGS84),
        title = "测试点",
        tag = 7,
    )

    @Test
    fun validOptions_keepUnifiedItemAndCommonNativeDefaults() {
        val options = MultiPointOverlayOptions(listOf(point))

        assertEquals(listOf(point), options.items)
        assertEquals(MarkerIcon.Default, options.icon)
        assertEquals(0.5f, options.anchorU)
        assertEquals(0.5f, options.anchorV)
        assertEquals(true, options.clickable)
        assertEquals(true, options.visible)
    }

    @Test
    fun item_rejectsUnknownOrInvalidCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            MultiPointItem("unknown", LatLng(39.0, 116.0, CoordType.UNKNOWN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MultiPointItem("invalid", LatLng(Double.NaN, 116.0, CoordType.WGS84))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MultiPointItem("invalid", LatLng(39.0, 181.0, CoordType.WGS84))
        }
    }

    @Test
    fun options_rejectEmptyItemsDuplicateIdsAndInvalidAnchor() {
        assertThrows(IllegalArgumentException::class.java) {
            MultiPointOverlayOptions(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            MultiPointOverlayOptions(listOf(point, point.copy(position = point.position.copy(latitude = 40.0))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MultiPointOverlayOptions(listOf(point), anchorU = 1.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MultiPointOverlayOptions(listOf(point), anchorV = Float.NaN)
        }
    }
}
