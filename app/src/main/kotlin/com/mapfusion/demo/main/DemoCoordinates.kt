package com.mapfusion.demo.main

import com.mapfusion.api.capability.Provider
import com.mapfusion.api.coordinate.DefaultCoordinateConverter
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapLocation
import com.mapfusion.api.model.MapResult

/** 演示坐标策略：默认点为公开示例坐标，定位值通过回调读取，不保存用户位置。 */
internal class DemoCoordinates(private val provider: () -> Provider, private val location: () -> MapLocation?) {
    /** 操作优先使用当前定位；没有定位时显式转换公开演示点到厂商坐标。 */
    fun center(): LatLng = location()?.position ?: defaultPoint(provider())

    /** 新地图的公开初始相机点，始终从 WGS84 转换，避免混用百度与高德坐标。 */
    fun defaultPoint(provider: Provider): LatLng = convert(LatLng(39.9087, 116.3975, CoordType.WGS84), provider)

    /** 有定位时生成邻近演示终点；无定位时使用原 Demo 的公开示例终点。 */
    fun destination(origin: LatLng): LatLng = if (location() != null)
        LatLng(origin.latitude + 0.012, origin.longitude + 0.015, origin.coordType)
        else convert(LatLng(39.9163, 116.4172, CoordType.WGS84), provider())

    /** 坐标转换失败明确抛出错误，由页面操作边界转换成失败状态。 */
    private fun convert(point: LatLng, provider: Provider): LatLng = when (val result = DefaultCoordinateConverter.convert(
        point, if (provider == Provider.BAIDU) CoordType.BD09 else CoordType.GCJ02)) {
        is MapResult.Success -> result.data
        is MapResult.Failure -> error(result.error.message)
    }
}
