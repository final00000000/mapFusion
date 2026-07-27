package com.mapfusion.baidu

import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BaiduCoordinateBridgeTest {

    @Test
    fun converts_wgs84_input_to_bd09() {
        val converted = LatLng(39.908823, 116.397470, CoordType.WGS84).toBaiduCoordinate()

        assertEquals(CoordType.BD09, converted.coordType)
        assertEquals(39.916, converted.latitude, 0.001)
        assertEquals(116.410, converted.longitude, 0.001)
    }

    @Test
    fun rejects_unknown_input() {
        assertThrows(IllegalArgumentException::class.java) {
            LatLng(39.9, 116.4).toBaiduCoordinate()
        }
    }
}
