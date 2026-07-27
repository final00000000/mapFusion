package com.mapfusion.api.model

/** 相机如何跟随定位更新。 */
enum class LocationFollowMode {
    /** 只更新定位图标和精度圈，不改变相机。 */
    NONE,

    /** 只在首次有效定位时移动相机。 */
    FIRST_FIX,

    /** 每次有效定位都让相机跟随当前位置。 */
    CONTINUOUS,

    /** 持续跟随，并使用定位方向旋转地图。 */
    COURSE_UP,
}

/** 定位图标样式；传 null 给 [LocationDisplayStyle.marker] 可完全隐藏图标。 */
data class LocationMarkerStyle(
    val icon: MarkerIcon = MarkerIcon.Default,
    val anchorU: Float = 0.5f,
    val anchorV: Float = 0.5f,
    val alpha: Float = 1f,
    val flat: Boolean = true,
    val rotateWithBearing: Boolean = true,
    val zIndex: Float = 1_000f,
    val title: String? = "当前位置",
)

/** 定位精度圈样式；半径实时使用 [MapLocation.accuracy]。 */
data class LocationAccuracyStyle(
    val strokeWidth: Float = 2f,
    val strokeColor: Int = 0xFF1976D2.toInt(),
    val fillColor: Int = 0x331976D2,
    val zIndex: Float = 999f,
)

data class LocationDisplayStyle(
    val marker: LocationMarkerStyle? = LocationMarkerStyle(),
    val accuracy: LocationAccuracyStyle? = LocationAccuracyStyle(),
)

data class LocationDisplayOptions(
    val locationOptions: LocationOptions = LocationOptions(
        onceOnly = false,
        intervalMs = 2_000,
        needAddress = true,
        useCache = true,
    ),
    val style: LocationDisplayStyle = LocationDisplayStyle(),
    val followMode: LocationFollowMode = LocationFollowMode.FIRST_FIX,
    /** null 表示跟随时保留当前缩放级别。 */
    val followZoom: Float? = 17f,
    val animateCamera: Boolean = true,
    val cameraAnimationDurationMs: Int = 300,
    /** 大于 0 时丢弃精度差于该值的位置，0 表示不限制。 */
    val maxAccuracyMeters: Float = 0f,
    /** 进入后台时停止连续定位，回到前台后自动恢复。 */
    val pauseWhenBackground: Boolean = true,
)

enum class LocationDisplayState {
    IDLE,
    RUNNING,
    PAUSED,
    STOPPED,
    DESTROYED,
}

sealed class LocationDisplayEvent {
    data class StateChanged(val state: LocationDisplayState) : LocationDisplayEvent()

    data class LocationUpdated(val location: MapLocation) : LocationDisplayEvent()

    data class AccuracyRejected(
        val location: MapLocation,
        val maxAccuracyMeters: Float,
    ) : LocationDisplayEvent()

    data class Failure(val error: MapError) : LocationDisplayEvent()
}

fun interface LocationDisplayListener {
    fun onEvent(event: LocationDisplayEvent)
}
