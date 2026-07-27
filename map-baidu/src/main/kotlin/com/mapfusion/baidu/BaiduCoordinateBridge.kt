package com.mapfusion.baidu

import com.mapfusion.api.coordinate.DefaultCoordinateConverter
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapResult

/** 统一坐标到百度 BD09 的唯一适配边界。 */
internal fun LatLng.toBaiduCoordinate(): LatLng =
    when (val result = DefaultCoordinateConverter.convert(this, CoordType.BD09)) {
        is MapResult.Success -> result.data
        is MapResult.Failure -> throw IllegalArgumentException(
            "百度坐标转换失败: ${result.error.message}",
            result.error.cause,
        )
    }

internal fun LatLng.toBaiduSdkLatLng(): com.baidu.mapapi.model.LatLng =
    toBaiduCoordinate().let { com.baidu.mapapi.model.LatLng(it.latitude, it.longitude) }
