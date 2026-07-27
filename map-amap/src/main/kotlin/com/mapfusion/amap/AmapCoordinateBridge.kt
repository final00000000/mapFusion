package com.mapfusion.amap

import com.amap.api.services.core.LatLonPoint
import com.mapfusion.api.coordinate.DefaultCoordinateConverter
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapResult

/** 统一坐标到高德 GCJ02 的唯一适配边界。 */
internal fun LatLng.toAmapCoordinate(): LatLng =
    when (val result = DefaultCoordinateConverter.convert(this, CoordType.GCJ02)) {
        is MapResult.Success -> result.data
        is MapResult.Failure -> throw IllegalArgumentException(
            "高德坐标转换失败: ${result.error.message}",
            result.error.cause,
        )
    }

internal fun LatLng.toAmapMapLatLng(): com.amap.api.maps.model.LatLng =
    toAmapCoordinate().let { com.amap.api.maps.model.LatLng(it.latitude, it.longitude) }

internal fun LatLng.toAmapServicePoint(): LatLonPoint =
    toAmapCoordinate().let { LatLonPoint(it.latitude, it.longitude) }
