package com.mapfusion.api.coordinate

import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 显式坐标转换服务；各厂商适配器边界也复用此规则归一坐标，避免业务层切换 Provider 时发生静默偏移。
 */
fun interface CoordinateConverter {
    fun convert(point: LatLng, target: CoordType): MapResult<LatLng>
}

/**
 * WGS84 / GCJ02 / BD09 的内置转换实现。
 *
 * 国内 WGS84↔GCJ02 使用公开常用算法，GCJ02→WGS84 采用迭代反解；境外坐标保持数值不变。
 */
object DefaultCoordinateConverter : CoordinateConverter {

    override fun convert(point: LatLng, target: CoordType): MapResult<LatLng> {
        if (point.coordType == CoordType.UNKNOWN || target == CoordType.UNKNOWN) {
            return MapResult.Failure(
                MapError(ErrorType.INVALID_PARAM, "源坐标系和目标坐标系都必须明确，不能为 UNKNOWN"),
            )
        }
        if (!point.latitude.isFinite() || !point.longitude.isFinite() ||
            point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0
        ) {
            return MapResult.Failure(
                MapError(ErrorType.INVALID_PARAM, "经纬度必须是有限值，纬度范围为 -90..90，经度范围为 -180..180"),
            )
        }
        if (point.coordType == target) return MapResult.Success(point)

        // 中国境外没有 GCJ02/BD09 加密，转换时只变更声明的坐标系，避免人为引入偏移。
        if (isOutsideChina(point)) return MapResult.Success(point.copy(coordType = target))

        val result = when (point.coordType to target) {
            CoordType.WGS84 to CoordType.GCJ02 -> wgs84ToGcj02(point)
            CoordType.GCJ02 to CoordType.WGS84 -> gcj02ToWgs84(point)
            CoordType.GCJ02 to CoordType.BD09 -> gcj02ToBd09(point)
            CoordType.BD09 to CoordType.GCJ02 -> bd09ToGcj02(point)
            CoordType.WGS84 to CoordType.BD09 -> gcj02ToBd09(wgs84ToGcj02(point))
            CoordType.BD09 to CoordType.WGS84 -> gcj02ToWgs84(bd09ToGcj02(point))
            else -> return MapResult.Failure(
                MapError(ErrorType.UNSUPPORTED, "暂不支持 ${point.coordType} → $target"),
            )
        }
        return MapResult.Success(result.copy(coordType = target))
    }

    fun convertAll(points: Iterable<LatLng>, target: CoordType): MapResult<List<LatLng>> {
        if (target == CoordType.UNKNOWN) {
            return MapResult.Failure(
                MapError(ErrorType.INVALID_PARAM, "目标坐标系必须明确，不能为 UNKNOWN"),
            )
        }
        val converted = ArrayList<LatLng>()
        for (point in points) {
            when (val result = convert(point, target)) {
                is MapResult.Success -> converted += result.data
                is MapResult.Failure -> return result
            }
        }
        return MapResult.Success(converted)
    }

    private fun wgs84ToGcj02(point: LatLng): LatLng {
        val (latitudeDelta, longitudeDelta) = offset(point.latitude, point.longitude)
        return LatLng(
            point.latitude + latitudeDelta,
            point.longitude + longitudeDelta,
            CoordType.GCJ02,
        )
    }

    private fun gcj02ToWgs84(point: LatLng): LatLng {
        var latitudeMin = point.latitude - 0.02
        var latitudeMax = point.latitude + 0.02
        var longitudeMin = point.longitude - 0.02
        var longitudeMax = point.longitude + 0.02
        var candidate = point
        repeat(32) {
            candidate = LatLng(
                (latitudeMin + latitudeMax) / 2,
                (longitudeMin + longitudeMax) / 2,
                CoordType.WGS84,
            )
            val converted = wgs84ToGcj02(candidate)
            val latitudeError = converted.latitude - point.latitude
            val longitudeError = converted.longitude - point.longitude
            if (abs(latitudeError) < 1e-7 && abs(longitudeError) < 1e-7) {
                return candidate
            }
            if (latitudeError > 0) latitudeMax = candidate.latitude else latitudeMin = candidate.latitude
            if (longitudeError > 0) longitudeMax = candidate.longitude else longitudeMin = candidate.longitude
        }
        return candidate
    }

    private fun gcj02ToBd09(point: LatLng): LatLng {
        val x = point.longitude
        val y = point.latitude
        val z = sqrt(x * x + y * y) + 0.00002 * sin(y * X_PI)
        val theta = atan2(y, x) + 0.000003 * cos(x * X_PI)
        return LatLng(
            latitude = z * sin(theta) + 0.006,
            longitude = z * cos(theta) + 0.0065,
            coordType = CoordType.BD09,
        )
    }

    private fun bd09ToGcj02(point: LatLng): LatLng {
        val x = point.longitude - 0.0065
        val y = point.latitude - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        val theta = atan2(y, x) - 0.000003 * cos(x * X_PI)
        return LatLng(
            latitude = z * sin(theta),
            longitude = z * cos(theta),
            coordType = CoordType.GCJ02,
        )
    }

    private fun offset(latitude: Double, longitude: Double): Pair<Double, Double> {
        var latitudeDelta = transformLatitude(longitude - 105.0, latitude - 35.0)
        var longitudeDelta = transformLongitude(longitude - 105.0, latitude - 35.0)
        val radLatitude = latitude / 180.0 * PI
        var magic = sin(radLatitude)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        latitudeDelta = latitudeDelta * 180.0 / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        longitudeDelta = longitudeDelta * 180.0 / (A / sqrtMagic * cos(radLatitude) * PI)
        return latitudeDelta to longitudeDelta
    }

    private fun transformLatitude(x: Double, y: Double): Double {
        var result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
            0.2 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        result += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        result += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return result
    }

    private fun transformLongitude(x: Double, y: Double): Double {
        var result = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
            0.1 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        result += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        result += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return result
    }

    private fun isOutsideChina(point: LatLng): Boolean =
        point.longitude !in 72.004..137.8347 || point.latitude !in 0.8293..55.8271

    private const val A = 6_378_245.0
    private const val EE = 0.006693421622965943
    private const val X_PI = PI * 3_000.0 / 180.0
}
