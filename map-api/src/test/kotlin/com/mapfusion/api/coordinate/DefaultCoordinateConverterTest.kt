package com.mapfusion.api.coordinate

import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCoordinateConverterTest {

    private val wgs84 = LatLng(39.908823, 116.397470, CoordType.WGS84)

    @Test
    fun wgs84_to_gcj02_matches_known_beijing_coordinate() {
        val result = DefaultCoordinateConverter.convert(wgs84, CoordType.GCJ02)
        require(result is MapResult.Success)
        assertEquals(CoordType.GCJ02, result.data.coordType)
        assertEquals(39.910226, result.data.latitude, 0.00001)
        assertEquals(116.403714, result.data.longitude, 0.00001)
    }

    @Test
    fun wgs84_to_bd09_and_back_is_stable() {
        val bd09 = DefaultCoordinateConverter.convert(wgs84, CoordType.BD09)
        require(bd09 is MapResult.Success)
        val restored = DefaultCoordinateConverter.convert(bd09.data, CoordType.WGS84)
        require(restored is MapResult.Success)
        assertEquals(wgs84.latitude, restored.data.latitude, 0.000002)
        assertEquals(wgs84.longitude, restored.data.longitude, 0.000002)
    }

    @Test
    fun outside_china_keeps_numeric_value() {
        val london = LatLng(51.5074, -0.1278, CoordType.WGS84)
        val result = DefaultCoordinateConverter.convert(london, CoordType.GCJ02)
        require(result is MapResult.Success)
        assertEquals(london.latitude, result.data.latitude, 0.0)
        assertEquals(london.longitude, result.data.longitude, 0.0)
        assertEquals(CoordType.GCJ02, result.data.coordType)
    }

    @Test
    fun outside_china_wgs84_to_bd09_keeps_numeric_value() {
        val london = LatLng(51.5074, -0.1278, CoordType.WGS84)
        val result = DefaultCoordinateConverter.convert(london, CoordType.BD09)
        require(result is MapResult.Success)
        assertEquals(london.latitude, result.data.latitude, 0.0)
        assertEquals(london.longitude, result.data.longitude, 0.0)
        assertEquals(CoordType.BD09, result.data.coordType)
    }

    @Test
    fun invalid_numeric_coordinate_is_rejected() {
        val result = DefaultCoordinateConverter.convert(
            LatLng(Double.NaN, 116.0, CoordType.WGS84),
            CoordType.GCJ02,
        )
        assertTrue(result is MapResult.Failure)
    }

    @Test
    fun empty_batch_still_rejects_unknown_target() {
        val result = DefaultCoordinateConverter.convertAll(emptyList(), CoordType.UNKNOWN)

        assertTrue(result is MapResult.Failure)
    }

    @Test
    fun unknown_coordinate_is_rejected() {
        val result = DefaultCoordinateConverter.convert(
            LatLng(1.0, 2.0, CoordType.UNKNOWN),
            CoordType.GCJ02,
        )
        assertTrue(result is MapResult.Failure)
    }
}
