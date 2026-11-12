package com.mapfusion.demo.main

import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapLocation
import org.junit.Assert.*
import org.junit.Test

/** 只使用公开演示点和合成值验证坐标系，不保存设备位置。 */
class DemoCoordinatesTest {
    /** 两个厂商默认点具有对应坐标系，终点也遵循同一转换。 */
    @Test fun public_examples_use_vendor_coordinate_types() {
        Provider.entries.forEach { provider ->
            val coordinates = DemoCoordinates({ provider }, { null })
            val expected = if (provider == Provider.BAIDU) CoordType.BD09 else CoordType.GCJ02
            assertEquals(expected, coordinates.center().coordType)
            assertEquals(expected, coordinates.destination(coordinates.center()).coordType)
        }
    }

    /** 已有定位值不被重复转换或复制成错误厂商坐标。 */
    @Test fun current_location_is_used_without_conversion() {
        val synthetic = LatLng(10.0, 20.0, CoordType.WGS84)
        val coordinates = DemoCoordinates({ Provider.BAIDU }, { MapLocation(synthetic) })
        assertEquals(synthetic, coordinates.center())
        val destination = coordinates.destination(synthetic)
        assertEquals(CoordType.WGS84, destination.coordType)
        assertEquals(10.012, destination.latitude, 0.000001)
        assertEquals(20.015, destination.longitude, 0.000001)
    }

    /** 策略读取最新会话的值，不缓存上一个厂商或已移除的定位。 */
    @Test fun provider_and_location_are_read_for_each_action() {
        var provider = Provider.BAIDU
        var location: MapLocation? = MapLocation(LatLng(10.0, 20.0, CoordType.WGS84))
        val coordinates = DemoCoordinates({ provider }, { location })
        assertEquals(location?.position, coordinates.center())
        location = null
        assertEquals(CoordType.BD09, coordinates.center().coordType)
        provider = Provider.AMAP
        assertEquals(CoordType.GCJ02, coordinates.center().coordType)
    }

    /** 跨厂商切换相机时保留缩放、朝向与俯仰角，并将坐标转换到目标厂商坐标系。 */
    @Test fun convert_camera_preserves_zoom_bearing_and_tilt_across_providers() {
        val coordinates = DemoCoordinates({ Provider.AMAP }, { null })
        val baiduCamera = com.mapfusion.api.model.CameraPosition(
            target = LatLng(39.915, 116.404, CoordType.BD09),
            zoom = 16.5f,
            bearing = 42f,
            tilt = 28f,
        )
        val converted = coordinates.convertCamera(baiduCamera, Provider.AMAP)
        assertEquals(CoordType.GCJ02, converted.target?.coordType)
        assertEquals(16.5f, converted.zoom ?: 0f, 0.001f)
        assertEquals(42f, converted.bearing ?: 0f, 0.001f)
        assertEquals(28f, converted.tilt ?: 0f, 0.001f)
        assertFalse(converted.animated)
    }
}
