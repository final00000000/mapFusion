package com.mapfusion.demo.presentation.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.OfficialNavigationDayNightMode
import com.mapfusion.api.model.OfficialNavigationEvent
import com.mapfusion.api.model.OfficialNavigationMode
import com.mapfusion.api.model.OfficialNavigationState
import com.mapfusion.api.model.OfficialRouteSummary
import com.mapfusion.api.model.TravelMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 官方导航业务状态的唯一所有者；输入选择、统一事件和引擎值快照，输出只读状态流。
 * 仅选择写入 SavedStateHandle，不持有 Activity、Context、View、导航器或会话。
 * 新宿主必须重置运行期状态，不能把恢复的旧导航状态当成新会话的实际状态。
 */
internal class OfficialNavigationViewModel(
    /** 保存导航模式、出行、速度、语音与配色选择的恢复容器，不存原生会话或路线运行态。 */
    private val savedState: SavedStateHandle
) : ViewModel() {
    /** 当前变体允许的真实/模拟模式值集合；null 表示尚未配置，配置时复制且不持久化能力。 */
    private var supportedModes: Set<OfficialNavigationMode>? = null
    /** 当前变体允许的规范化出行方式值集合；null 表示尚未配置，不持有厂商对象。 */
    private var supportedTravelModes: Set<TravelMode>? = null
    /** 是否已输入变体能力，默认 false；只控制 sanitize 能力规范化，不代表引擎初始化成功。 */
    private var capabilitiesConfigured = false
    /** VM 内部唯一可写状态流；初始只恢复选择，运行字段由新宿主/统一事件重新产生。 */
    private val mutableState = MutableStateFlow(readInitialState())
    /** 页面只读快照流，供生命周期内单向渲染；外部不能直接写 MutableStateFlow。 */
    val state: StateFlow<OfficialNavigationUiState> = mutableState.asStateFlow()

    /** 读取可恢复选择，非法枚举回退默认值；速度先以宽整数限幅，避免进程恢复时溢出。 */
    private fun readInitialState(): OfficialNavigationUiState {
        val savedSpeed = savedState.get<Any>(KEY_SIMULATION_SPEED) as? Number
            ?: OFFICIAL_NAVIGATION_DEFAULT_SPEED
        return OfficialNavigationUiState(
            navigationMode = savedState.readEnum(KEY_NAVIGATION_MODE, OfficialNavigationMode.REAL),
            travelMode = savedState.readEnum(KEY_TRAVEL_MODE, TravelMode.DRIVING),
            simulationSpeed = clampSimulationSpeed(savedSpeed),
            voiceGuidanceEnabled = savedState.get<Boolean>(KEY_VOICE_GUIDANCE) ?: true,
            dayNightMode = savedState.readEnum(KEY_DAY_NIGHT_MODE, OfficialNavigationDayNightMode.AUTO),
        )
    }

    /** 输入当前变体能力，复制值集合并降级不受支持的恢复选择；能力本身不持久化。 */
    fun configureCapabilities(modes: Set<OfficialNavigationMode>, travelModes: Set<TravelMode>) {
        supportedModes = modes.toSet()
        supportedTravelModes = travelModes.map(::canonicalTravelMode).toSet()
        capabilitiesConfigured = true
        update { current ->
            val mode = current.navigationMode.takeIf { it in supportedModes.orEmpty() }
                ?: supportedModes.orEmpty().firstOrNull() ?: OfficialNavigationMode.REAL
            val fallbackTravel = OFFICIAL_NAVIGATION_TRAVEL_MODE_ORDER
                .firstOrNull { it in supportedTravelModes.orEmpty() } ?: TravelMode.DRIVING
            val travel = canonicalTravelMode(current.travelMode)
                .takeIf { it in supportedTravelModes.orEmpty() } ?: fallbackTravel
            current.copy(navigationMode = mode, travelMode = travel)
        }
    }

    /** 输入纯状态转换，统一限幅、发布并保存选择；页面和操作层不得另存一套业务状态。 */
    fun update(transform: (OfficialNavigationUiState) -> OfficialNavigationUiState) {
        val next = sanitize(transform(mutableState.value))
        mutableState.value = next
        persist(next)
    }

    /** 输入导航模式，拒绝忙碌、已启动、被阻断或厂商不支持的切换。 */
    fun selectNavigationMode(mode: OfficialNavigationMode) = update {
        if (canSelect(it) && mode in supportedModes.orEmpty()) it.copy(navigationMode = mode) else it
    }

    /** 输入出行方式，归一骑行别名并拒绝忙碌或不受支持的选择。 */
    fun selectTravelMode(mode: TravelMode) = update {
        val canonical = canonicalTravelMode(mode)
        if (canSelect(it) && canonical in supportedTravelModes.orEmpty()) it.copy(travelMode = canonical) else it
    }

    /** 输入 km/h，保存限幅后的选择；是否调用运行时引擎由命令层根据能力决定。 */
    fun setSimulationSpeed(kilometersPerHour: Int) = update {
        if (it.controlsBlocked) it else it.copy(simulationSpeed = clampSimulationSpeed(kilometersPerHour))
    }

    /** 输入厂商已确认的语音开关，更新并持久化该选择。 */
    fun setVoiceGuidanceEnabled(enabled: Boolean) = update { it.copy(voiceGuidanceEnabled = enabled) }

    /** 输入厂商已确认的配色，更新并持久化该选择。 */
    fun setDayNightMode(mode: OfficialNavigationDayNightMode) = update { it.copy(dayNightMode = mode) }

    /** 输入中文详情和语义色，仅更新展示字段，不改变导航运行状态。 */
    fun setDetail(text: String, tone: NaviTone) = update { it.copy(detailText = text, detailTone = tone) }

    /** 输入权限/页面错误，显示错误详情与占位层，不假造引擎状态。 */
    fun showErrorDetail(message: String) = update {
        it.copy(detailText = message, detailTone = NaviTone.ERROR, placeholderText = message,
            placeholderVisible = true, placeholderTone = NaviTone.ERROR)
    }

    /** 输入统一错误及当前引擎快照，解除启动等待并展示错误，避免操作层复制归约规则。 */
    fun showMapError(title: String, error: MapError, engineState: OfficialNavigationState) {
        update { it.copy(startRequestPending = false, navigationState = engineState) }
        showErrorDetail("$title：${error.message}")
    }

    /** 在请求权限前标记真实导航等待，重复点击不会创建第二个权限请求。 */
    fun awaitRealNavigationPermission() = update {
        it.copy(pendingRealNavigationStart = true, detailText = "等待精确位置权限", detailTone = NaviTone.INFO)
    }

    /** 消费一次权限等待并立即清除标记；返回是否仍有请求，撤回/重建后的回调不会启动导航。 */
    fun consumePermissionRequest(): Boolean {
        val pending = state.value.pendingRealNavigationStart && !state.value.controlsBlocked
        update { it.copy(pendingRealNavigationStart = false) }
        return pending
    }

    /** 请求提交前清空路线并显示规划占位，提前置等待标记以防 SDK 同步事件触发重复启动。 */
    fun prepareStart() = update {
        it.copy(routeSummaries = emptyList(), selectedRouteIndex = 0, startRequestPending = true,
            pendingRealNavigationStart = false, placeholderText = "路线规划中",
            placeholderVisible = true, placeholderTone = NaviTone.NEUTRAL,
            detailText = "正在请求${OfficialNavigationText.travel(it.travelMode)}路线", detailTone = NaviTone.INFO)
    }

    /** 输入提交成功后的引擎快照；导航/终态不重新置等待，避免覆盖 start 内同步回调的归约。 */
    fun markStartSucceeded(engineState: OfficialNavigationState) = update {
        if (it.controlsBlocked) it else it.copy(
            startRequestPending = engineState == OfficialNavigationState.INITIALIZING ||
                engineState == OfficialNavigationState.READY || engineState == OfficialNavigationState.PLANNING,
            navigationStarted = true, navigationState = engineState,
        )
    }

    /** 提交失败时解除启动等待，错误内容由 showMapError 统一补充。 */
    fun markStartFailed() = update { it.copy(startRequestPending = false) }

    /** 输入暂停、继续或停止后的引擎快照，清除等待并通过统一状态归约同步占位层。 */
    fun onControlState(engineState: OfficialNavigationState) {
        onStateChanged(engineState)
        update { it.copy(startRequestPending = false) }
    }

    /**
     * 输入事件或初始化轮询得到的引擎状态；非初始化状态解除等待，同步占位与终态。
     * 被隐私阻断的状态不能被迟到快照复活；失败详情由 onEngineSnapshot 补充。
     */
    fun onStateChanged(engineState: OfficialNavigationState) = update {
        if (it.controlsBlocked) return@update it
        val previous = it.navigationState
        it.copy(
            navigationState = engineState,
            startRequestPending = if (engineState == OfficialNavigationState.INITIALIZING) it.startRequestPending else false,
            placeholderVisible = engineState == OfficialNavigationState.INITIALIZING ||
                engineState == OfficialNavigationState.READY || engineState in OFFICIAL_NAVIGATION_TERMINAL_STATES,
            placeholderText = when (engineState) {
                OfficialNavigationState.READY -> "选择导航模式后开始"
                OfficialNavigationState.ARRIVED -> "导航已完成"
                OfficialNavigationState.INITIALIZING -> if (previous != OfficialNavigationState.INITIALIZING) {
                    "官方导航引擎准备中"
                } else it.placeholderText
                else -> it.placeholderText
            },
            placeholderTone = if (engineState == OfficialNavigationState.FAILED) it.placeholderTone else NaviTone.NEUTRAL,
        )
    }

    /** 输入当前引擎状态和可选错误值，处理监听安装前的初始化完成或失败，不持有引擎。 */
    fun onEngineSnapshot(engineState: OfficialNavigationState, error: MapError?) {
        if (state.value.controlsBlocked) return
        onStateChanged(engineState)
        if (engineState == OfficialNavigationState.FAILED) {
            showErrorDetail(error?.let { "官方导航失败：${it.message}" } ?: "官方导航初始化失败，请返回后重试")
        }
    }

    /** 输入已由宿主校验身份、代次和隐私的事件，业务归约集中于此，文案由纯格式器提供。 */
    fun onNavigationEvent(event: OfficialNavigationEvent, engineState: OfficialNavigationState) {
        if (state.value.controlsBlocked) return
        when (event) {
            is OfficialNavigationEvent.StateChanged -> onStateChanged(event.state)
            is OfficialNavigationEvent.RoutesReady -> {
                onRoutesReady(event.routes, event.selectedRouteIndex)
                showSelectedRoute()
            }
            OfficialNavigationEvent.Arrived -> onArrived()
            is OfficialNavigationEvent.Error -> showMapError("官方导航错误", event.error, engineState)
            else -> Unit
        }
        OfficialNavigationText.event(event)?.let { setDetail(it, NaviTone.INFO) }
    }

    /** 到达事件置终态并清除等待，占位层保留完成提示，不允许再次启动同一会话。 */
    fun onArrived() = update {
        it.copy(startRequestPending = false, navigationState = OfficialNavigationState.ARRIVED,
            placeholderVisible = true, placeholderText = "导航已完成", placeholderTone = NaviTone.NEUTRAL)
    }

    /** 输入规划结果，复制路线列表并夹紧厂商下标，空结果固定为零。 */
    fun onRoutesReady(routes: List<OfficialRouteSummary>, selectedIndex: Int) = update {
        it.copy(routeSummaries = routes.toList(), selectedRouteIndex = clampRouteIndex(selectedIndex, routes.size))
    }

    /** 根据当前路线推导循环下标；不足两条时返回 null，避免对空集合取模。 */
    fun nextRouteIndex(): Int? {
        val current = state.value
        return if (current.routeSummaries.size < 2) null
        else (current.selectedRouteIndex + 1) % current.routeSummaries.size
    }

    /** 从已确认的配色推导下一选择，预览不写状态，厂商成功后才调用设置方法。 */
    fun nextDayNightMode(): OfficialNavigationDayNightMode = when (state.value.dayNightMode) {
        OfficialNavigationDayNightMode.AUTO -> OfficialNavigationDayNightMode.DAY
        OfficialNavigationDayNightMode.DAY -> OfficialNavigationDayNightMode.NIGHT
        OfficialNavigationDayNightMode.NIGHT -> OfficialNavigationDayNightMode.AUTO
    }

    /** 输入厂商切换成功的下标，夹紧到当前路线范围，不保存运行期路线选择。 */
    fun selectRoute(index: Int) = update { it.copy(selectedRouteIndex = clampRouteIndex(index, it.routeSummaries.size)) }

    /** 从当前路线快照生成详情；无路线时保持上一个事件提示。 */
    fun showSelectedRoute() {
        OfficialNavigationText.route(state.value.routeSummaries, state.value.selectedRouteIndex)
            ?.let { setDetail(it, NaviTone.INFO) }
    }

    /** 引擎创建或隐私同步失败时禁用控件，保留错误供用户返回后诊断。 */
    fun blockControls() = update { it.copy(controlsBlocked = true) }

    /** 隐私失效后清空运行期标记与路线、阻断控件；持久化选择不受影响。 */
    fun blockForConsent(message: String) = update {
        it.copy(navigationState = OfficialNavigationState.DESTROYED, routeSummaries = emptyList(),
            selectedRouteIndex = 0, startRequestPending = false, navigationStarted = false,
            pendingRealNavigationStart = false, controlsBlocked = true, detailText = message,
            detailTone = NaviTone.ERROR, placeholderText = message, placeholderVisible = true,
            placeholderTone = NaviTone.ERROR)
    }

    /** 新页面/会话从初始化开始，清空所有运行字段，仅保留 SavedStateHandle 中的选择。 */
    fun resetRuntimeSession() = update {
        it.copy(navigationState = OfficialNavigationState.INITIALIZING, routeSummaries = emptyList(),
            selectedRouteIndex = 0, startRequestPending = false, navigationStarted = false,
            pendingRealNavigationStart = false, controlsBlocked = false, detailText = "等待开始",
            detailTone = NaviTone.INFO, placeholderText = "官方导航引擎准备中",
            placeholderVisible = true, placeholderTone = NaviTone.NEUTRAL)
    }

    /** 输入状态快照，判断是否允许选择；权限等待也阻止模式被切换造成请求语义漂移。 */
    private fun canSelect(state: OfficialNavigationUiState): Boolean =
        !state.controlsBlocked && !state.busy && !state.navigationStarted && !state.pendingRealNavigationStart

    /** 输入候选状态，再次规范速度、能力和路线下标，保护所有归约入口。 */
    private fun sanitize(value: OfficialNavigationUiState): OfficialNavigationUiState {
        var result = value.copy(simulationSpeed = clampSimulationSpeed(value.simulationSpeed))
        if (capabilitiesConfigured) {
            val modes = supportedModes.orEmpty()
            val travel = supportedTravelModes.orEmpty()
            if (modes.isNotEmpty() && result.navigationMode !in modes) {
                result = result.copy(navigationMode = modes.first())
            }
            if (travel.isNotEmpty() && canonicalTravelMode(result.travelMode) !in travel) {
                result = result.copy(travelMode = OFFICIAL_NAVIGATION_TRAVEL_MODE_ORDER
                    .firstOrNull { it in travel } ?: TravelMode.DRIVING)
            }
        }
        return result.copy(selectedRouteIndex = clampRouteIndex(result.selectedRouteIndex, result.routeSummaries.size))
    }

    /** 仅写入可恢复选择，不保存导航状态、权限等待、路线或任何原生对象。 */
    private fun persist(state: OfficialNavigationUiState) {
        savedState[KEY_NAVIGATION_MODE] = state.navigationMode.name
        savedState[KEY_TRAVEL_MODE] = state.travelMode.name
        savedState[KEY_SIMULATION_SPEED] = state.simulationSpeed
        savedState[KEY_VOICE_GUIDANCE] = state.voiceGuidanceEnabled
        savedState[KEY_DAY_NIGHT_MODE] = state.dayNightMode.name
    }

    /** 从指定键读取枚举名称，输入缺失或未知名称时使用默认值，不让恢复异常中断页面。 */
    private inline fun <reified T : Enum<T>> SavedStateHandle.readEnum(key: String, default: T): T =
        get<String>(key)?.let { raw -> runCatching { enumValueOf<T>(raw) }.getOrNull() } ?: default

    /** 选择项保存协议，保持旧版本键名以兼容已存在的 Activity 恢复状态。 */
    internal companion object {
        /** 真实/模拟模式枚举 name 的旧恢复键，不保存导航启动状态。 */
        const val KEY_NAVIGATION_MODE = "officialNavigation.mode"
        /** 规范化出行方式枚举 name 的旧恢复键，不保存原生请求或路线。 */
        const val KEY_TRAVEL_MODE = "officialNavigation.travelMode"
        /** 模拟速度选择的旧恢复键，值单位 km/h；读取时先宽整数限幅再转 Int。 */
        const val KEY_SIMULATION_SPEED = "officialNavigation.simulationSpeed"
        /** 语音启用选择的旧恢复键，不保存 TTS 引擎或语音资源。 */
        const val KEY_VOICE_GUIDANCE = "officialNavigation.voiceGuidance"
        /** 自动/白天/夜间配色枚举 name 的旧恢复键，不保存原生 View 样式对象。 */
        const val KEY_DAY_NIGHT_MODE = "officialNavigation.dayNightMode"
    }
}
