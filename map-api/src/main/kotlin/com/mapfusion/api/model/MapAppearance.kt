package com.mapfusion.api.model

/** 统一地图类型。适配器不得把不支持的类型静默回退为另一种类型。 */
enum class MapType {
    NORMAL,
    SATELLITE,
    NIGHT,
    NAVIGATION,
    NONE,
}

/** 可在运行时查询的地图控件/手势能力。厂商未声明的能力不得静默伪造。 */
enum class MapUiFeature {
    ZOOM_CONTROLS,
    SCALE_CONTROLS,
    COMPASS,
    MY_LOCATION_BUTTON,
    SCROLL_GESTURES,
    ZOOM_GESTURES,
    ROTATE_GESTURES,
    TILT_GESTURES,
    INDOOR_SWITCH,
    ZOOM_IN_BY_SCREEN_CENTER,
    GESTURE_SCALE_BY_MAP_CENTER,
    DOUBLE_CLICK_ZOOM,
    TWO_TOUCH_CLICK_ZOOM,
    DOUBLE_CLICK_MOVE_ZOOM,
    FLING,
    INERTIAL_ANIMATION,
}

/** 可在运行时查询的自定义地图样式来源。 */
enum class MapCustomStyleFeature {
    LOCAL_STYLE_FILE,
    STYLE_ID,
    LOCAL_TEXTURE_FILE,
}

/**
 * 厂商官方自定义地图样式配置。
 *
 * [enabled] 为 true 时必须提供 [localStylePath] 或 [styleId]。调用控制器成功只表示配置已提交给
 * 厂商 SDK，不表示远程样式资源已完成下载或渲染。
 */
data class MapCustomStyleOptions(
    val enabled: Boolean,
    val localStylePath: String? = null,
    val styleId: String? = null,
    val localTexturePath: String? = null,
) {
    init {
        require(localStylePath == null || localStylePath.isNotBlank()) { "localStylePath 不能为空白字符串" }
        require(styleId == null || styleId.isNotBlank()) { "styleId 不能为空白字符串" }
        require(localTexturePath == null || localTexturePath.isNotBlank()) {
            "localTexturePath 不能为空白字符串"
        }
        require(!enabled || localStylePath != null || styleId != null) {
            "启用自定义地图样式时必须提供 localStylePath 或 styleId"
        }
    }
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
    val indoorSwitchEnabled: Boolean = false,
    val zoomInByScreenCenter: Boolean = false,
    val gestureScaleByMapCenter: Boolean = false,
    val doubleClickZoomEnabled: Boolean = false,
    val twoTouchClickZoomEnabled: Boolean = false,
    val doubleClickMoveZoomEnabled: Boolean = false,
    val flingEnabled: Boolean = false,
    val inertialAnimationEnabled: Boolean = false,
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
