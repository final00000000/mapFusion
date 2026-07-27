package com.mapfusion.amap

import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AmapCoordinateBridgeTest {

    @Test
    fun converts_wgs84_input_to_gcj02() {
        val converted = LatLng(39.908823, 116.397470, CoordType.WGS84).toAmapCoordinate()

        assertEquals(CoordType.GCJ02, converted.coordType)
        assertEquals(39.910226, converted.latitude, 0.00001)
        assertEquals(116.403714, converted.longitude, 0.00001)
    }

    @Test
    fun rejects_unknown_input() {
        assertThrows(IllegalArgumentException::class.java) {
            LatLng(39.9, 116.4).toAmapCoordinate()
        }
    }
}
