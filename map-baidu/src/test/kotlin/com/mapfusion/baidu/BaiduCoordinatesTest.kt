package com.mapfusion.baidu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaiduCoordinatesTest {

    @Test
    fun convertsSdkMercatorLocationToValidBd09LatLng() {
        val result = bd09MercatorToLatLng(12_941_311.205839, 4_849_515.694373)

        assertTrue(result.latitude in 39.0..41.0)
        assertTrue(result.longitude in 115.0..117.0)
        assertEquals("BD09", result.coordType.name)
    }
}
