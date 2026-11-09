package com.mapfusion.demo.presentation.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.mapfusion.api.model.OfficialNavigationDayNightMode
import com.mapfusion.api.model.OfficialNavigationMode
import com.mapfusion.api.model.OfficialNavigationState
import com.mapfusion.api.model.OfficialRouteSummary
import com.mapfusion.api.model.TravelMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 官方导航页面的状态所有者。
 *
 * 只保存可序列化的选择项；导航引擎、View 与 Activity 生命周期仍由 Activity 持有。
 * 新 Activity 必须调用 [resetRuntimeSession]，避免进程重建后把旧会话的
 * NAVIGATING/PAUSED 当成新引擎的真实状态。
 */
internal class OfficialNavigationViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    private var supportedModes: Set<OfficialNavigationMode>? = null
    private var supportedTravelModes: Set<TravelMode>? = null
    private var capabilitiesConfigured = false

    private val mutableState = MutableStateFlow(readInitialState())
    val state: StateFlow<OfficialNavigationUiState> = mutableState.asStateFlow()

    private fun readInitialState(): OfficialNavigationUiState {
        val savedSpeed = savedState.get<Any>(KEY_SIMULATION_SPEED) as? Number
            ?: OFFICIAL_NAVIGATION_DEFAULT_SPEED
        return OfficialNavigationUiState(
            navigationMode = savedState.readEnum(KEY_NAVIGATION_MODE, OfficialNavigationMode.REAL),
            travelMode = savedState.readEnum(KEY_TRAVEL_MODE, TravelMode.DRIVING),
            simulationSpeed = clampSimulationSpeed(savedSpeed),
            voiceGuidanceEnabled = savedState.get<Boolean>(KEY_VOICE_GUIDANCE) ?: true,
            dayNightMode = savedState.readEnum(
                KEY_DAY_NIGHT_MODE,
                OfficialNavigationDayNightMode.AUTO,
            ),
        )
    }

    /**
     * 能力表在 Activity 创建引擎前已知；用同一组 Set 降级非法或当前变体不支持的选择。
     * 能力表本身不持久化，也不进入 UI 状态。
     */
    fun configureCapabilities(
        modes: Set<OfficialNavigationMode>,
        travelModes: Set<TravelMode>,
    ) {
        supportedModes = modes.toSet()
        supportedTravelModes = travelModes.map(::canonicalTravelMode).toSet()
        capabilitiesConfigured = true
        update { current ->
            val fallbackMode = supportedModes.orEmpty().firstOrNull() ?: OfficialNavigationMode.REAL
            val mode = current.navigationMode.takeIf { it in supportedModes.orEmpty() } ?: fallbackMode
            val fallbackTravel = OFFICIAL_NAVIGATION_TRAVEL_MODE_ORDER
                .firstOrNull { it in supportedTravelModes.orEmpty() } ?: TravelMode.DRIVING
            val travel = canonicalTravelMode(current.travelMode)
                .takeIf { it in supportedTravelModes.orEmpty() } ?: fallbackTravel
            current.copy(navigationMode = mode, travelMode = travel)
        }
    }

    /** 唯一的归约入口：校验模式/出行方式、速度与路线下标，并把选择项写回 SavedStateHandle。 */
    fun update(transform: (OfficialNavigationUiState) -> OfficialNavigationUiState) {
        val next = sanitize(transform(mutableState.value))
        mutableState.value = next
        persist(next)
    }

    fun selectNavigationMode(mode: OfficialNavigationMode) = update {
        if (mode in supportedModes.orEmpty()) it.copy(navigationMode = mode) else it
    }

    fun selectTravelMode(mode: TravelMode) = update {
        val canonical = canonicalTravelMode(mode)
        if (canonical in supportedTravelModes.orEmpty()) it.copy(travelMode = canonical) else it
    }

    fun setSimulationSpeed(kilometersPerHour: Int) = update {
        it.copy(simulationSpeed = clampSimulationSpeed(kilometersPerHour))
    }

    fun setVoiceGuidanceEnabled(enabled: Boolean) = update {
        it.copy(voiceGuidanceEnabled = enabled)
    }

    fun setDayNightMode(mode: OfficialNavigationDayNightMode) = update {
        it.copy(dayNightMode = mode)
    }

    fun setDetail(text: String, tone: NaviTone) = update {
        it.copy(detailText = text, detailTone = tone)
    }

    fun showErrorDetail(message: String) = update {
        it.copy(
            detailText = message,
            detailTone = NaviTone.ERROR,
            placeholderText = message,
            placeholderVisible = true,
            placeholderTone = NaviTone.ERROR,
        )
    }

    fun prepareStart() = update {
        it.copy(
            routeSummaries = emptyList(),
            selectedRouteIndex = 0,
            placeholderText = "路线规划中",
            placeholderVisible = true,
            placeholderTone = NaviTone.NEUTRAL,
        )
    }

    fun markStartSucceeded(engineState: OfficialNavigationState) = update {
        it.copy(
            startRequestPending = true,
            navigationStarted = true,
            navigationState = engineState,
        )
    }

    fun markStartFailed() = update {
        it.copy(startRequestPending = false)
    }

    /**
     * 引擎状态变化（事件或初始化轮询）的归约。非 INITIALIZING 状态解除启动 pending，
     * 并同步占位层；FAILED 的具体错误文案仍由 Activity 读取 lastError 后补充。
     */
    fun onStateChanged(engineState: OfficialNavigationState) = update {
        val previous = it.navigationState
        it.copy(
            navigationState = engineState,
            startRequestPending = if (engineState == OfficialNavigationState.INITIALIZING) {
                it.startRequestPending
            } else {
                false
            },
            placeholderVisible = engineState == OfficialNavigationState.INITIALIZING ||
                engineState == OfficialNavigationState.READY ||
                engineState in OFFICIAL_NAVIGATION_TERMINAL_STATES,
            placeholderText = when (engineState) {
                OfficialNavigationState.READY -> "选择导航模式后开始"
                OfficialNavigationState.ARRIVED -> "导航已完成"
                OfficialNavigationState.INITIALIZING -> if (previous != OfficialNavigationState.INITIALIZING) {
                    "官方导航引擎准备中"
                } else {
                    it.placeholderText
                }
                else -> it.placeholderText
            },
            placeholderTone = if (engineState == OfficialNavigationState.FAILED) {
                it.placeholderTone
            } else {
                NaviTone.NEUTRAL
            },
        )
    }

    fun onArrived() = update {
        it.copy(
            startRequestPending = false,
            navigationState = OfficialNavigationState.ARRIVED,
            placeholderVisible = true,
            placeholderText = "导航已完成",
            placeholderTone = NaviTone.NEUTRAL,
        )
    }

    fun onRoutesReady(routes: List<OfficialRouteSummary>, selectedIndex: Int) = update {
        it.copy(
            routeSummaries = routes.toList(),
            selectedRouteIndex = clampRouteIndex(selectedIndex, routes.size),
        )
    }

    fun selectRoute(index: Int) = update {
        it.copy(selectedRouteIndex = clampRouteIndex(index, it.routeSummaries.size))
    }

    fun blockControls() = update { it.copy(controlsBlocked = true) }

    /** 隐私撤回：停止后禁止任何启动，并清空运行期标记。 */
    fun blockForConsent(message: String) = update {
        it.copy(
            navigationState = OfficialNavigationState.DESTROYED,
            startRequestPending = false,
            navigationStarted = false,
            pendingRealNavigationStart = false,
            controlsBlocked = true,
            detailText = message,
            detailTone = NaviTone.ERROR,
            placeholderText = message,
            placeholderVisible = true,
            placeholderTone = NaviTone.ERROR,
        )
    }

    /** 新引擎的 runtime 从 INITIALIZING 开始；已保存的选项保持不变。 */
    fun resetRuntimeSession() = update {
        it.copy(
            navigationState = OfficialNavigationState.INITIALIZING,
            routeSummaries = emptyList(),
            selectedRouteIndex = 0,
            startRequestPending = false,
            navigationStarted = false,
            pendingRealNavigationStart = false,
            controlsBlocked = false,
            detailText = "等待开始",
            detailTone = NaviTone.INFO,
            placeholderText = "官方导航引擎准备中",
            placeholderVisible = true,
            placeholderTone = NaviTone.NEUTRAL,
        )
    }

    private fun sanitize(value: OfficialNavigationUiState): OfficialNavigationUiState {
        var result = value.copy(simulationSpeed = clampSimulationSpeed(value.simulationSpeed))
        if (capabilitiesConfigured) {
            val modeSet = supportedModes.orEmpty()
            val travelSet = supportedTravelModes.orEmpty()
            if (modeSet.isNotEmpty() && result.navigationMode !in modeSet) {
                result = result.copy(
                    navigationMode = modeSet.firstOrNull() ?: OfficialNavigationMode.REAL,
                )
            }
            if (travelSet.isNotEmpty() && canonicalTravelMode(result.travelMode) !in travelSet) {
                result = result.copy(
                    travelMode = OFFICIAL_NAVIGATION_TRAVEL_MODE_ORDER
                        .firstOrNull { it in travelSet } ?: TravelMode.DRIVING,
                )
            }
        }
        return result.copy(
            selectedRouteIndex = clampRouteIndex(
                result.selectedRouteIndex,
                result.routeSummaries.size,
            ),
        )
    }

    private fun persist(state: OfficialNavigationUiState) {
        savedState[KEY_NAVIGATION_MODE] = state.navigationMode.name
        savedState[KEY_TRAVEL_MODE] = state.travelMode.name
        savedState[KEY_SIMULATION_SPEED] = state.simulationSpeed
        savedState[KEY_VOICE_GUIDANCE] = state.voiceGuidanceEnabled
        savedState[KEY_DAY_NIGHT_MODE] = state.dayNightMode.name
    }

    private inline fun <reified T : Enum<T>> SavedStateHandle.readEnum(key: String, default: T): T =
        get<String>(key)?.let { raw -> runCatching { enumValueOf<T>(raw) }.getOrNull() } ?: default

    internal companion object {
        const val KEY_NAVIGATION_MODE = "officialNavigation.mode"
        const val KEY_TRAVEL_MODE = "officialNavigation.travelMode"
        const val KEY_SIMULATION_SPEED = "officialNavigation.simulationSpeed"
        const val KEY_VOICE_GUIDANCE = "officialNavigation.voiceGuidance"
        const val KEY_DAY_NIGHT_MODE = "officialNavigation.dayNightMode"
    }
}
