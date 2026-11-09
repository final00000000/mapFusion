package com.mapfusion.demo.presentation.navigation

import com.mapfusion.api.model.OfficialNavigationDayNightMode
import com.mapfusion.api.model.OfficialNavigationMode
import com.mapfusion.api.model.OfficialNavigationState
import com.mapfusion.api.model.OfficialRouteSummary
import com.mapfusion.api.model.TravelMode

/** 模拟速度的默认选择，单位 km/h；只有模拟模式使用，不代表真实车辆速度。 */
internal const val OFFICIAL_NAVIGATION_DEFAULT_SPEED = 60
/** Demo 模拟速度的下限，单位 km/h；滑条偏移和恢复值限幅共用。 */
internal const val OFFICIAL_NAVIGATION_MIN_SPEED = 20
/** Demo 模拟速度的上限，单位 km/h；不是厂商引擎的完整能力范围。 */
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
internal enum class NaviTone {
    /** 初始化、完成等中性占位提示，不表示引擎操作成功。 */
    NEUTRAL,
    /** 普通状态、规划/导航进度和操作提示的语义。 */
    INFO,
    /** 成功消息的语义标识，不直接改变导航状态或厂商控件。 */
    SUCCESS,
    /** 失败、权限不足或隐私阻断提示；渲染层使用错误文字/卡片颜色。 */
    ERROR
}

/**
 * 导航页面的完整不可变 UI 状态。
 *
 * 只包含值类型和 SDK 值对象；不持有 Activity、View、Context 或 [com.mapfusion.api.capability.OfficialNavigator]。
 * 运行期字段（状态、路线和 pending 标记）在进程重建后由 ViewModel 清空，选择项才写入 SavedStateHandle。
 */
internal data class OfficialNavigationUiState(
    /** 真实/模拟导航选择，默认真实且可恢复；开始真实导航仍须精确位置权限。 */
    val navigationMode: OfficialNavigationMode = OfficialNavigationMode.REAL,
    /** 出行方式选择，默认驾车且可恢复；恢复/切换时按厂商能力归一骑行别名。 */
    val travelMode: TravelMode = TravelMode.DRIVING,
    /** 模拟速度选择，单位 km/h，默认 60，限于 20–120；可恢复，运行中应用还须厂商能力。 */
    val simulationSpeed: Int = OFFICIAL_NAVIGATION_DEFAULT_SPEED,
    /** 语音开关选择，默认 true 且可恢复；SDK 实际设置由宿主/命令提交，不代表 TTS 已验收。 */
    val voiceGuidanceEnabled: Boolean = true,
    /** 自动/白天/夜间配色选择，默认自动且可恢复；不是原生 View 或当前像素颜色快照。 */
    val dayNightMode: OfficialNavigationDayNightMode = OfficialNavigationDayNightMode.AUTO,
    /** 当前会话引擎状态值，默认初始化中；不持久化，不能恢复旧 NAVIGATING 冒充新引擎。 */
    val navigationState: OfficialNavigationState = OfficialNavigationState.INITIALIZING,
    /** 当前规划结果的值摘要列表，默认空并由 VM 复制；不保存原生路线/会话，不跨新会话恢复。 */
    val routeSummaries: List<OfficialRouteSummary> = emptyList(),
    /** 当前路线零基下标；夹紧在摘要范围内，空列表固定 0，不能据此假定已有第一条路线。 */
    val selectedRouteIndex: Int = 0,
    /** 开始请求已提交但仍等待推进的运行标记；默认 false，非初始化状态或失败可清除。 */
    val startRequestPending: Boolean = false,
    /** 本会话开始请求是否已被接收；终态也保留，防止重用已开始会话，不等于正在导航。 */
    val navigationStarted: Boolean = false,
    /** 等待精确权限后续接真实导航的运行标记；只消费一次，撤回或新宿主重建时清除。 */
    val pendingRealNavigationStart: Boolean = false,
    /** 隐私失效或引擎创建/隐私同步失败后的控件阻断；默认 false，实际操作还需可用会话。 */
    val controlsBlocked: Boolean = false,
    /** 当前事件/操作详情，默认等待开始；仅展示字段，不持久化或替代引擎状态。 */
    val detailText: String = "等待开始",
    /** 详情消息语义，默认 INFO；当前渲染器区分错误与普通提示，不据颜色判断导航状态。 */
    val detailTone: NaviTone = NaviTone.INFO,
    /** 覆盖原生导航容器的占位文案，默认引擎准备中；初始化/规划/终态或错误时由归约更新。 */
    val placeholderText: String = "官方导航引擎准备中",
    /** 是否显示占位层，默认 true；展示与引擎实际状态分别归约，不表示原生 View 已销毁。 */
    val placeholderVisible: Boolean = true,
    /** 占位文字语义，默认中性；错误使用错误色，其余使用普通占位色。 */
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
