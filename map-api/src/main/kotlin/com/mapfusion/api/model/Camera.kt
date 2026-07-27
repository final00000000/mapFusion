package com.mapfusion.api.model

/**
 * 地图相机状态。zoom 取值范围各厂商略有差异,适配器负责裁剪到合法区间。
 */
data class CameraPosition(
    val target: LatLng,
    val zoom: Float,
    val bearing: Float = 0f,
    val tilt: Float = 0f
)

/** 相机移动动画配置 */
data class CameraUpdate(
    val target: LatLng? = null,
    val zoom: Float? = null,
    val bearing: Float? = null,
    val tilt: Float? = null,
    /** 非 null 时按区域适配视野，优先级高于 target/zoom。 */
    val bounds: LatLngBounds? = null,
    /** bounds 模式下四边统一留白，单位 px。 */
    val paddingPixels: Int = 0,
    /** 是否带动画;false 则瞬时跳转 */
    val animated: Boolean = true,
    val durationMs: Int = 300
)

/** 经纬度矩形范围,用于 fitBounds / 区域搜索 */
data class LatLngBounds(
    val southwest: LatLng,
    val northeast: LatLng
) {
    val center: LatLng
        get() = LatLng(
            (southwest.latitude + northeast.latitude) / 2,
            (southwest.longitude + northeast.longitude) / 2,
            southwest.coordType
        )

    companion object {
        /**
         * 计算一组同坐标系点的最小外接矩形。
         *
         * UNKNOWN、混合坐标系和非法经纬度会直接拒绝，避免把错误边界静默交给厂商 SDK。
         */
        @JvmStatic
        fun fromPoints(points: Iterable<LatLng>): LatLngBounds {
            val iterator = points.iterator()
            require(iterator.hasNext()) { "计算地图边界至少需要 1 个点" }
            val first = iterator.next().also(::requireValidPoint)
            require(first.coordType != CoordType.UNKNOWN) { "地图边界坐标必须声明 coordType" }

            var south = first.latitude
            var north = first.latitude
            var west = first.longitude
            var east = first.longitude
            while (iterator.hasNext()) {
                val point = iterator.next().also(::requireValidPoint)
                require(point.coordType == first.coordType) { "地图边界不能混用不同坐标系" }
                south = minOf(south, point.latitude)
                north = maxOf(north, point.latitude)
                west = minOf(west, point.longitude)
                east = maxOf(east, point.longitude)
            }
            return LatLngBounds(
                southwest = LatLng(south, west, first.coordType),
                northeast = LatLng(north, east, first.coordType),
            )
        }

        private fun requireValidPoint(point: LatLng) {
            require(point.latitude.isFinite() && point.latitude in -90.0..90.0) {
                "纬度必须是 -90..90 的有限值"
            }
            require(point.longitude.isFinite() && point.longitude in -180.0..180.0) {
                "经度必须是 -180..180 的有限值"
            }
        }
    }
}
