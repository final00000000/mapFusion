package com.mapfusion.api.model

/**
 * 海量点图层中的单个业务点。
 *
 * [id] 在同一图层内必须唯一；点击回调会返回当前图层保存的这个统一模型及其 [tag]，
 * 业务层不需要接触百度/高德的 MultiPointItem。
 */
data class MultiPointItem(
    val id: String,
    val position: LatLng,
    val title: String? = null,
    val tag: Any? = null,
) {
    init {
        require(id.isNotBlank()) { "海量点 id 不能为空" }
        require(position.coordType != CoordType.UNKNOWN) { "海量点坐标系必须明确" }
        require(position.latitude.isFinite() && position.latitude in -90.0..90.0) {
            "海量点纬度必须是 -90..90 的有限值"
        }
        require(position.longitude.isFinite() && position.longitude in -180.0..180.0) {
            "海量点经度必须是 -180..180 的有限值"
        }
    }
}

/** 使用厂商原生海量点图层一次性添加大量同图标点位。 */
data class MultiPointOverlayOptions(
    val items: List<MultiPointItem>,
    val icon: MarkerIcon = MarkerIcon.Default,
    val anchorU: Float = 0.5f,
    val anchorV: Float = 0.5f,
    val clickable: Boolean = true,
    val visible: Boolean = true,
    val tag: Any? = null,
) {
    init {
        requireValidMultiPointItems(items)
        require(anchorU.isFinite() && anchorU in 0f..1f) { "海量点 anchorU 必须在 0..1" }
        require(anchorV.isFinite() && anchorV in 0f..1f) { "海量点 anchorV 必须在 0..1" }
    }
}

/**
 * 已添加的厂商原生海量点图层句柄。
 *
 * 该接口没有继承 [MapOverlay]：高德原生 MultiPointOverlay 不提供 zIndex，统一层不能伪造
 * 一个实际不会生效的层级属性。[items] 支持运行中整体替换，列表不能为空且 id 必须唯一。
 */
interface MapMultiPointOverlay {
    val id: String
    val isRemoved: Boolean
    var items: List<MultiPointItem>
    var visible: Boolean
    var clickable: Boolean
    var tag: Any?

    fun remove()

    /** 覆盖物级逃生舱，返回百度 MultiPoint 或高德 MultiPointOverlay。 */
    fun rawOverlay(): Any
}

internal fun requireValidMultiPointItems(items: List<MultiPointItem>) {
    require(items.isNotEmpty()) { "海量点图层至少需要一个点" }
    require(items.map(MultiPointItem::id).toSet().size == items.size) {
        "同一海量点图层内的 id 不能重复"
    }
}
