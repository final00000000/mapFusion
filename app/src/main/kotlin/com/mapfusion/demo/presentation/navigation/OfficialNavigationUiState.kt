package com.mapfusion.demo.presentation.navigation

import com.mapfusion.api.model.OfficialNavigationDayNightMode
import com.mapfusion.api.model.OfficialNavigationMode
import com.mapfusion.api.model.OfficialNavigationState
import com.mapfusion.api.model.OfficialRouteSummary
import com.mapfusion.api.model.TravelMode

internal const val OFFICIAL_NAVIGATION_DEFAULT_SPEED = 60
internal const val OFFICIAL_NAVIGATION_MIN_SPEED = 20
internal const val OFFICIAL_NAVIGATION_MAX_SPEED = 120

/** 与 Demo 控制面板一致的出行方式顺序；能力过滤后使用。 */
internal val OFFICIAL_NAVIGATION_TRAVEL_MODE_ORDER = listOf(
    TravelMode.DRIVING,
    TravelMode.WALKING,
    TravelMode.BICYCLE,
    TravelMode.ELECTRIC_BICYCLE,
)

/** 规划、导航或暂停都视为忙碌，不能切换请求模式或再次启动。 */
internal val OFFICIAL_NAVIGATION_ACTIVE_STATES = setOf(
    OfficialNavigationState.PLANNING,
    OfficialNavigationState.NAVIGATING,
    OfficialNavigationState.PAUSED,
)

/** 终态需要占位提示；与运行中的忙碌状态互斥。 */
internal val OFFICIAL_NAVIGATION_TERMINAL_STATES = setOf(
    OfficialNavigationState.ARRIVED,
    OfficialNavigationState.STOPPED,
    OfficialNavigationState.FAILED,
    OfficialNavigationState.DESTROYED,
)

/** 状态消息的语义色，渲染层不依赖具体业务文案。 */
internal enum class NaviTone { NEUTRAL, INFO, SUCCESS, ERROR }

/**
 * 导航页面的完整不可变 UI 状态。
 *
 * 只包含值类型和 SDK 值对象；不持有 Activity、View、Context 或 [com.mapfusion.api.capability.OfficialNavigator]。
 * 运行期字段（状态、路线和 pending 标记）在进程重建后由 ViewModel 清空，选择项才写入 SavedStateHandle。
 */
internal data class OfficialNavigationUiState(
    val navigationMode: OfficialNavigationMode = OfficialNavigationMode.REAL,
    val travelMode: TravelMode = TravelMode.DRIVING,
    val simulationSpeed: Int = OFFICIAL_NAVIGATION_DEFAULT_SPEED,
    val voiceGuidanceEnabled: Boolean = true,
    val dayNightMode: OfficialNavigationDayNightMode = OfficialNavigationDayNightMode.AUTO,
    val navigationState: OfficialNavigationState = OfficialNavigationState.INITIALIZING,
    val routeSummaries: List<OfficialRouteSummary> = emptyList(),
    val selectedRouteIndex: Int = 0,
    val startRequestPending: Boolean = false,
    val navigationStarted: Boolean = false,
    val pendingRealNavigationStart: Boolean = false,
    val controlsBlocked: Boolean = false,
    val detailText: String = "等待开始",
    val detailTone: NaviTone = NaviTone.INFO,
    val placeholderText: String = "官方导航引擎准备中",
    val placeholderVisible: Boolean = true,
    val placeholderTone: NaviTone = NaviTone.NEUTRAL,
) {
    /** 权限等待和提交等待均占用开始入口，防止重复请求及选择语义漂移。 */
    val busy: Boolean get() = pendingRealNavigationStart || startRequestPending ||
        navigationState in OFFICIAL_NAVIGATION_ACTIVE_STATES
    /** 只有实际导航或暂停时允许重算、全览和跟随等运行期控制。 */
    val navigationActive: Boolean
        get() = navigationState == OfficialNavigationState.NAVIGATING ||
            navigationState == OfficialNavigationState.PAUSED
}

/** 输入任意数字速度，先用宽整数限幅到演示范围，再转换为 km/h 整数。 */
internal fun clampSimulationSpeed(value: Number): Int =
    value.toLong().coerceIn(OFFICIAL_NAVIGATION_MIN_SPEED.toLong(), OFFICIAL_NAVIGATION_MAX_SPEED.toLong()).toInt()

/** 输入路线下标与数量，空列表归零，非空列表夹紧上下界。 */
internal fun clampRouteIndex(index: Int, routeCount: Int): Int =
    if (routeCount <= 0) 0 else index.coerceIn(0, routeCount - 1)

/** 输入出行方式，调用统一契约归一骑行别名，不改变厂商能力表。 */
internal fun canonicalTravelMode(mode: TravelMode): TravelMode = mode.canonical()

/** 判断统一引擎状态是否结束，用于占位展示，不调用原生引擎。 */
internal fun OfficialNavigationState.isTerminal(): Boolean =
    this in OFFICIAL_NAVIGATION_TERMINAL_STATES
