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

internal val OFFICIAL_NAVIGATION_ACTIVE_STATES = setOf(
    OfficialNavigationState.PLANNING,
    OfficialNavigationState.NAVIGATING,
    OfficialNavigationState.PAUSED,
)

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
    val busy: Boolean get() = startRequestPending || navigationState in OFFICIAL_NAVIGATION_ACTIVE_STATES
    val navigationActive: Boolean
        get() = navigationState == OfficialNavigationState.NAVIGATING ||
            navigationState == OfficialNavigationState.PAUSED
}

internal fun clampSimulationSpeed(value: Number): Int =
    value.toLong().coerceIn(OFFICIAL_NAVIGATION_MIN_SPEED.toLong(), OFFICIAL_NAVIGATION_MAX_SPEED.toLong()).toInt()

internal fun clampRouteIndex(index: Int, routeCount: Int): Int =
    if (routeCount <= 0) 0 else index.coerceIn(0, routeCount - 1)

internal fun canonicalTravelMode(mode: TravelMode): TravelMode = mode.canonical()

internal fun OfficialNavigationState.isTerminal(): Boolean =
    this in OFFICIAL_NAVIGATION_TERMINAL_STATES
