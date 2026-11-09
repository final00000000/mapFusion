package com.mapfusion.api.model

import com.mapfusion.api.capability.Provider
import java.util.concurrent.Executor

/** 厂商官方 Navi 引擎支持的运行模式。 */
enum class OfficialNavigationMode {
    REAL,
    SIMULATED,
}

/** 官方导航界面的昼夜配色策略。 */
enum class OfficialNavigationDayNightMode {
    AUTO,
    DAY,
    NIGHT,
}

/** 可在运行前查询的官方导航能力，未声明的能力不得由适配器静默伪造。 */
enum class OfficialNavigationFeature {
    IN_APP_UI,
    VOICE_GUIDANCE,
    LANE_GUIDANCE,
    JUNCTION_VIEW,
    CAMERA_ALERTS,
    SPEED_LIMIT,
    TRAFFIC,
    AUTOMATIC_REROUTE,
    MULTI_ROUTE,
    BACKGROUND_GUIDANCE,
    SIMULATION_SPEED,
    WAYPOINT_ARRIVAL,
    GPS_STATUS,
    INTERVAL_CAMERA_ALERTS,
    SERVICE_AREAS,
    WAYPOINT_PROGRESS,
    HIGHWAY_GUIDANCE,
    ROUTE_NOTICES,
    /** 导航中由业务主动触发路线重算。 */
    MANUAL_REROUTE,
    /** 将当前路线完整显示在导航地图中。 */
    ROUTE_OVERVIEW,
    /** 从自由浏览或全览恢复车标跟随。 */
    RESTORE_FOLLOWING,
    /** 模拟导航已开始后仍可动态调整速度。 */
    RUNTIME_SIMULATION_SPEED,
    /** 导航会话运行期间可开关厂商内置语音。 */
    VOICE_CONTROL,
    /** 导航会话运行期间可切换自动、白天和夜间配色。 */
    DAY_NIGHT_MODE,
}

data class OfficialNavigationCapabilities(
    val provider: Provider,
    val features: Set<OfficialNavigationFeature>,
    val modes: Set<OfficialNavigationMode>,
    val travelModes: Set<TravelMode>,
    val maxWaypoints: Int,
) {
    fun supports(feature: OfficialNavigationFeature): Boolean = feature in features
    fun supports(mode: OfficialNavigationMode): Boolean = mode in modes
    fun supports(mode: TravelMode): Boolean = mode.canonical() in travelModes
}

enum class OfficialNavigationState {
    INITIALIZING,
    READY,
    PLANNING,
    NAVIGATING,
    PAUSED,
    ARRIVED,
    STOPPED,
    FAILED,
    DESTROYED,
}

data class OfficialNavigationOptions(
    val navigationMode: OfficialNavigationMode = OfficialNavigationMode.REAL,
    /** 多路线结果中默认选择的下标。 */
    val routeIndex: Int = 0,
    /** 仅声明支持 [OfficialNavigationFeature.SIMULATION_SPEED] 的厂商会应用该值。 */
    val simulationSpeedKilometersPerHour: Int = 60,
    val callbackExecutor: Executor? = null,
)

data class OfficialNavigationRequest(
    val routeRequest: RouteRequest,
    val options: OfficialNavigationOptions = OfficialNavigationOptions(),
)

data class OfficialRouteSummary(
    val routeId: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val tollCost: Int? = null,
    val trafficLightCount: Int? = null,
)

data class OfficialNavigationProgress(
    val currentRoadName: String? = null,
    val nextRoadName: String? = null,
    val remainingDistanceMeters: Int,
    val remainingDurationSeconds: Int,
    val currentStepIndex: Int? = null,
    val currentSpeedKilometersPerHour: Int? = null,
    val travelledDistanceMeters: Int? = null,
    val remainingTrafficLightCount: Int? = null,
)

/** 尚未到达的途经点进度；[waypointIndex] 是请求中途经点列表的零基下标。 */
data class OfficialWaypointProgress(
    val waypointIndex: Int,
    val remainingDistanceMeters: Int,
    val remainingDurationSeconds: Int,
    val remainingTrafficLightCount: Int? = null,
)

/** 高速公路出口、方向和收费站引导；厂商未提供的字段返回 null。 */
data class OfficialHighwayGuidance(
    val visible: Boolean,
    val highwayName: String? = null,
    val direction: String? = null,
    val exitName: String? = null,
    val exitCode: String? = null,
    val exitRemainingDistanceMeters: Int? = null,
    val tollGateName: String? = null,
    val tollGateRemainingDistanceMeters: Int? = null,
)

/** 厂商路线提醒；[vendorType] 保留尚未统一的厂商通知类别。 */
data class OfficialRouteNotice(
    val message: String? = null,
    val subTitle: String? = null,
    val roadName: String? = null,
    val distanceMeters: Int? = null,
    val success: Boolean? = null,
    val vendorType: Int? = null,
)

data class OfficialCameraAlert(
    val distanceMeters: Int,
    val speedLimitKilometersPerHour: Int? = null,
    /** 厂商原始类型值，便于尚未统一的新电子眼类型透传。 */
    val vendorType: Int,
)

/** 区间测速信息。距离均相对车辆当前位置；厂商未提供的字段返回 null。 */
data class OfficialIntervalCameraAlert(
    val startDistanceMeters: Int? = null,
    val endDistanceMeters: Int? = null,
    val remainingDistanceMeters: Int? = null,
    val speedLimitKilometersPerHour: Int? = null,
    val averageSpeedKilometersPerHour: Int? = null,
    /** 厂商原始区间状态，业务可在统一枚举补齐前按需展示。 */
    val vendorStatus: Int,
)

/** 前方服务区或停车区。坐标不是所有厂商回调都会提供，因此不放入公共必填字段。 */
data class OfficialServiceArea(
    val name: String? = null,
    val remainingDistanceMeters: Int,
    val remainingDurationSeconds: Int? = null,
    val vendorType: Int? = null,
)

/** 路线上一段厂商路况；[vendorStatus] 保留厂商原始等级，避免错误归一。 */
data class OfficialTrafficSegment(
    val lengthMeters: Int,
    val vendorStatus: Int,
)

sealed class OfficialNavigationEvent {
    data class StateChanged(val state: OfficialNavigationState) : OfficialNavigationEvent()

    data class RoutesReady(
        val routes: List<OfficialRouteSummary>,
        val selectedRouteIndex: Int,
    ) : OfficialNavigationEvent()

    data class Progress(val value: OfficialNavigationProgress) : OfficialNavigationEvent()
    data class VoicePrompt(val text: String) : OfficialNavigationEvent()
    data class LaneGuidance(val visible: Boolean, val laneCount: Int = 0) : OfficialNavigationEvent()
    data class JunctionView(val visible: Boolean) : OfficialNavigationEvent()
    data class CameraAlerts(val cameras: List<OfficialCameraAlert>) : OfficialNavigationEvent()
    data class IntervalCameraAlert(val camera: OfficialIntervalCameraAlert) : OfficialNavigationEvent()
    data class ServiceAreas(val areas: List<OfficialServiceArea>) : OfficialNavigationEvent()
    data class TrafficStatus(
        val distanceMeters: Int? = null,
        val segments: List<OfficialTrafficSegment>,
    ) : OfficialNavigationEvent()
    data class SpeedLimit(val current: Int, val limit: Int?) : OfficialNavigationEvent()
    data class GpsStatus(val enabled: Boolean?, val signalWeak: Boolean) : OfficialNavigationEvent()
    data class WaypointArrived(val waypointIndex: Int) : OfficialNavigationEvent()
    data class WaypointProgress(val waypoints: List<OfficialWaypointProgress>) : OfficialNavigationEvent()
    data class HighwayGuidance(val value: OfficialHighwayGuidance) : OfficialNavigationEvent()
    data class RouteNotice(val value: OfficialRouteNotice) : OfficialNavigationEvent()
    data object Rerouting : OfficialNavigationEvent()
    data class RerouteCompleted(val success: Boolean, val error: MapError? = null) : OfficialNavigationEvent()
    data object Arrived : OfficialNavigationEvent()
    data class Error(val error: MapError) : OfficialNavigationEvent()
}

fun interface OfficialNavigationListener {
    fun onEvent(event: OfficialNavigationEvent)
}
