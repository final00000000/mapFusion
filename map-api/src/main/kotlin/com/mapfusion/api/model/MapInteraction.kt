package com.mapfusion.api.model

/** 厂商底图 POI 的点击信息。 */
data class MapPoiClick(
    val id: String?,
    val name: String?,
    val position: LatLng,
)

enum class MarkerDragPhase {
    STARTED,
    DRAGGING,
    ENDED,
}

/** 可拖拽 Marker 的位置变化事件。 */
data class MarkerDragEvent(
    val marker: MapMarker,
    val phase: MarkerDragPhase,
    val position: LatLng,
)

enum class MapTouchAction {
    DOWN,
    UP,
    MOVE,
    CANCEL,
    POINTER_DOWN,
    POINTER_UP,
    OTHER,
}

/**
 * 地图 View 的触摸事件快照。事件在原生回调内复制，不会把可回收的 MotionEvent 暴露给业务层。
 */
data class MapTouchEvent(
    val action: MapTouchAction,
    val x: Float,
    val y: Float,
    val pointerCount: Int,
)
