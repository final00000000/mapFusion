package com.mapfusion.api.model

/**
 * 标记点的图标来源。抽象掉各家 SDK 各自的 BitmapDescriptor 概念。
 */
sealed class MarkerIcon {
    /** 使用厂商默认图标 */
    object Default : MarkerIcon()

    /** assets 目录下的图片名,如 "marker_red.png" */
    data class Asset(val assetName: String) : MarkerIcon()

    /** drawable 资源 id */
    data class Resource(val resId: Int) : MarkerIcon()

    /** 原始字节(PNG/JPG),由适配器转成各家 BitmapDescriptor */
    data class Bytes(val data: ByteArray) : MarkerIcon() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bytes) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()
    }
}

/**
 * 添加标记时的可配置项。业务方构造这个,适配器翻译成各家 MarkerOptions。
 */
data class MarkerOptions(
    val position: LatLng,
    val title: String? = null,
    val snippet: String? = null,
    val icon: MarkerIcon = MarkerIcon.Default,
    /** 锚点,0..1,默认底部中心(经典大头针) */
    val anchorU: Float = 0.5f,
    val anchorV: Float = 1.0f,
    val draggable: Boolean = false,
    /** 图标顺时针旋转角度。 */
    val rotation: Float = 0f,
    /** 0 完全透明，1 完全不透明。 */
    val alpha: Float = 1f,
    /** 是否平贴地图；false 时图标始终朝向屏幕。 */
    val flat: Boolean = false,
    val visible: Boolean = true,
    val zIndex: Float = 0f,
    /** 透明附加数据,回调里原样带回,方便业务关联标记与领域对象 */
    val tag: Any? = null,
) {
    init {
        require(anchorU in 0f..1f && anchorV in 0f..1f) { "Marker 锚点必须在 0..1" }
        require(rotation.isFinite()) { "Marker rotation 必须是有限值" }
        require(alpha.isFinite() && alpha in 0f..1f) { "Marker alpha 必须在 0..1" }
        require(zIndex.isFinite()) { "Marker zIndex 必须是有限值" }
    }
}

/**
 * 已添加到地图上的标记句柄。业务方拿它增删改,不接触各家原生 Marker 对象。
 */
interface MapMarker : MapOverlay {
    var position: LatLng
    var title: String?
    var snippet: String?
    var rotation: Float
    var alpha: Float
    var flat: Boolean

    fun showInfoWindow()
    fun hideInfoWindow()
}
