package com.mapfusion.demo.main

import com.mapfusion.api.capability.Provider
import com.mapfusion.api.coordinate.DefaultCoordinateConverter
import com.mapfusion.api.model.CameraPosition
import com.mapfusion.api.model.CameraUpdate
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapLocation
import com.mapfusion.api.model.MapResult

/** 演示坐标策略：默认点为公开示例坐标，定位值通过回调读取，不保存用户位置。 */
internal class DemoCoordinates(
    /** 当前厂商提供者的惰性读取口：切换厂商后返回新值，构造时不做缓存。 */
    private val provider: () -> Provider,
    /** 最近一次有效定位的读取口；返回 null 表示本次页面尚未定位，不在此保存真实位置。 */
    private val location: () -> MapLocation?
) {
    /** 操作优先使用当前定位；没有定位时显式转换公开演示点到厂商坐标。 */
    fun center(): LatLng = location()?.position ?: defaultPoint(provider())

    /** 新地图的公开初始相机点，始终从 WGS84 转换，避免混用百度与高德坐标。 */
    fun defaultPoint(provider: Provider): LatLng = convert(LatLng(39.9087, 116.3975, CoordType.WGS84), provider)

    /** 有定位时生成邻近演示终点；无定位时使用原 Demo 的公开示例终点。 */
    fun destination(origin: LatLng): LatLng = if (location() != null)
        LatLng(origin.latitude + 0.012, origin.longitude + 0.015, origin.coordType)
        else convert(LatLng(39.9163, 116.4172, CoordType.WGS84), provider())

    /** 将指定坐标转换到目标厂商原生坐标系（百度 BD09 或高德 GCJ02）。 */
    fun convertFor(point: LatLng, targetProvider: Provider): LatLng = convert(point, targetProvider)

    /** 将既有相机状态转换到目标厂商坐标系，保留中心、缩放、旋转角与俯仰角。 */
    fun convertCamera(camera: CameraPosition, targetProvider: Provider): CameraUpdate = CameraUpdate(
        target = convert(camera.target, targetProvider),
        zoom = camera.zoom,
        bearing = camera.bearing,
        tilt = camera.tilt,
        animated = false,
    )

    /** 坐标转换失败明确抛出错误，由页面操作边界转换成失败状态。 */
    private fun convert(point: LatLng, provider: Provider): LatLng = when (val result = DefaultCoordinateConverter.convert(
        point, if (provider == Provider.BAIDU) CoordType.BD09 else CoordType.GCJ02)) {
        is MapResult.Success -> result.data
        is MapResult.Failure -> error(result.error.message)
    }
}
