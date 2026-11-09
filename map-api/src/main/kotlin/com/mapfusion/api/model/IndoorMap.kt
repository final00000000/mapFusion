package com.mapfusion.api.model

/** [id] 是厂商楼层标识，不是列表下标；不得跨厂商复用。 */
data class MapIndoorFloor(val id: String, val name: String)

/** 室内建筑快照；离开室内地图后监听器回传 null。 */
data class MapIndoorBuilding(
    val id: String,
    val floors: List<MapIndoorFloor>,
    val activeFloorId: String?,
)
