package com.mapfusion.api.model

/** 统一地图类型。适配器不得把不支持的类型静默回退为另一种类型。 */
enum class MapType {
    NORMAL,
    SATELLITE,
    NIGHT,
    NAVIGATION,
    NONE,
}

/** 地图控件和手势的统一设置。 */
data class MapUiOptions(
    val zoomControlsEnabled: Boolean = true,
    val scaleControlsEnabled: Boolean = true,
    val compassEnabled: Boolean = false,
    val myLocationButtonEnabled: Boolean = false,
    val scrollGesturesEnabled: Boolean = true,
    val zoomGesturesEnabled: Boolean = true,
    val rotateGesturesEnabled: Boolean = true,
    val tiltGesturesEnabled: Boolean = true,
)

/** 截图结果统一为 PNG，避免把厂商或 Android Bitmap 生命周期暴露给业务层。 */
class MapSnapshot(
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is MapSnapshot &&
            width == other.width &&
            height == other.height &&
            pngBytes.contentEquals(other.pngBytes)

    override fun hashCode(): Int = 31 * (31 * pngBytes.contentHashCode() + width) + height
}
