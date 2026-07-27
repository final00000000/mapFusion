package com.mapfusion.api.model

/** 所有地图覆盖物的统一句柄。 */
interface MapOverlay {
    val id: String
    /** 已从原生地图移除后为 true；组件可据此自动重建自己的覆盖物。 */
    val isRemoved: Boolean get() = false
    var visible: Boolean
    var zIndex: Float
    var tag: Any?

    fun remove()

    /** 覆盖物级逃生舱，返回百度/高德原生 Overlay。 */
    fun rawOverlay(): Any
}

data class PolylineOptions(
    val points: List<LatLng>,
    val width: Float = 10f,
    val color: Int = 0xFF1976D2.toInt(),
    val dotted: Boolean = false,
    val geodesic: Boolean = false,
    val clickable: Boolean = false,
    val visible: Boolean = true,
    val zIndex: Float = 0f,
    val tag: Any? = null,
)

interface MapPolyline : MapOverlay {
    var points: List<LatLng>
    var width: Float
    var color: Int
}

data class PolygonOptions(
    val points: List<LatLng>,
    val strokeWidth: Float = 5f,
    val strokeColor: Int = 0xFF1976D2.toInt(),
    val fillColor: Int = 0x331976D2,
    val clickable: Boolean = false,
    val visible: Boolean = true,
    val zIndex: Float = 0f,
    val tag: Any? = null,
)

interface MapPolygon : MapOverlay {
    var points: List<LatLng>
    var strokeWidth: Float
    var strokeColor: Int
    var fillColor: Int
}

data class CircleOptions(
    val center: LatLng,
    val radiusMeters: Double,
    val strokeWidth: Float = 5f,
    val strokeColor: Int = 0xFF1976D2.toInt(),
    val fillColor: Int = 0x331976D2,
    val clickable: Boolean = false,
    val visible: Boolean = true,
    val zIndex: Float = 0f,
    val tag: Any? = null,
)

interface MapCircle : MapOverlay {
    var center: LatLng
    var radiusMeters: Double
    var strokeWidth: Float
    var strokeColor: Int
    var fillColor: Int
}
