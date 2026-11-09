package com.mapfusion.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OfficialMapModelsTest {

    @Test
    fun visibleRegion_calculatesBoundsFromAllFourCorners() {
        val region = MapVisibleRegion(
            nearLeft = LatLng(39.0, 116.0, CoordType.GCJ02),
            nearRight = LatLng(39.1, 117.0, CoordType.GCJ02),
            farLeft = LatLng(40.0, 115.5, CoordType.GCJ02),
            farRight = LatLng(40.2, 117.5, CoordType.GCJ02),
        )

        assertEquals(LatLng(39.0, 115.5, CoordType.GCJ02), region.bounds.southwest)
        assertEquals(LatLng(40.2, 117.5, CoordType.GCJ02), region.bounds.northeast)
    }

    @Test
    fun visibleRegion_rejectsMixedCoordinateSystemsWhenBoundsAreRead() {
        val region = MapVisibleRegion(
            nearLeft = LatLng(39.0, 116.0, CoordType.GCJ02),
            nearRight = LatLng(39.1, 117.0, CoordType.GCJ02),
            farLeft = LatLng(40.0, 115.5, CoordType.GCJ02),
            farRight = LatLng(40.2, 117.5, CoordType.BD09),
        )

        assertThrows(IllegalArgumentException::class.java) { region.bounds }
    }
}
