package com.mapfusion.demo.presentation.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.EmbeddedNavigationMode
import com.mapfusion.api.model.MapLocation
import com.mapfusion.api.model.TravelMode
import com.mapfusion.demo.data.PrivacyDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 首页状态归约器：保存选择与权限流程，不持有 Activity、Context、View 或地图会话。 */
internal class MainViewModel(
    /** Android 业务恢复容器；只保存选择/设置与权限待办，不写地图、定位或导航运行态。 */
    private val savedState: SavedStateHandle
) : ViewModel() {
    /** VM 内部唯一可写快照流；初始恢复业务选择，修改统一经过 update 持久化可恢复字段。 */
    private val mutableState = MutableStateFlow(MainUiState(
        preferredProvider = enumOrDefault(savedState[PROVIDER], Provider.BAIDU),
        routeMode = enumOrDefault(savedState[ROUTE_MODE], TravelMode.DRIVING),
        navigationMode = enumOrDefault(savedState[NAVIGATION_MODE], EmbeddedNavigationMode.REAL),
        toolCategory = enumOrDefault(savedState[TOOL_CATEGORY], DemoToolCategory.COMMON),
        mapSettings = DemoMapSettings.decode(savedState[MAP_SETTINGS]),
        pendingLocationAction = savedState.get<String>(PENDING_ACTION)?.let {
            runCatching { LocationPermissionAction.valueOf(it) }.getOrNull()
        },
        permissionRequestInFlight = savedState[PERMISSION_IN_FLIGHT] ?: false,
        permissionDenied = savedState[PERMISSION_DENIED] ?: false,
        permissionPermanentlyDenied = savedState[PERMISSION_PERMANENT] ?: false,
    ))
    /** 暴露给页面的只读状态流；Activity 在 STARTED 收集，不能从视图直接改内部状态。 */
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    /** 原子替换页面快照，并只持久化可恢复字段；运行态不写入 SavedStateHandle。 */
    fun update(transform: (MainUiState) -> MainUiState) {
        val next = transform(mutableState.value)
        mutableState.value = next
        savedState[PROVIDER] = next.preferredProvider.name
        savedState[ROUTE_MODE] = next.routeMode.name
        savedState[NAVIGATION_MODE] = next.navigationMode.name
        savedState[TOOL_CATEGORY] = next.toolCategory.name
        savedState[MAP_SETTINGS] = next.mapSettings.encode()
        savedState[PENDING_ACTION] = next.pendingLocationAction?.name
        savedState[PERMISSION_IN_FLIGHT] = next.permissionRequestInFlight
        savedState[PERMISSION_DENIED] = next.permissionDenied
        savedState[PERMISSION_PERMANENT] = next.permissionPermanentlyDenied
    }

    /** 按当前安装变体规范化厂商选择；没有保存选择时使用变体默认厂商。 */
    fun configureProviders(default: Provider, supported: Set<Provider>) {
        require(default in supported)
        val candidate = if (savedState.contains(PROVIDER)) state.value.preferredProvider else default
        selectProvider(candidate.takeIf { it in supported } ?: default)
    }

    /** 同厂商保留设置；真实切厂商重置厂商相关配置并丢弃旧定位和导航运行态。 */
    fun selectProvider(provider: Provider) = update {
        if (provider == it.preferredProvider) it else it.copy(preferredProvider = provider,
            mapSettings = DemoMapSettings(), lastLocation = null, embeddedNavigationActive = false,
            pendingLocationAction = null, mapPhase = MainMapPhase.DISABLED, capabilityCount = 0)
    }

    /** 切换工具分类，只更新可恢复选择，视图重建由状态收集器触发。 */
    fun selectToolCategory(category: DemoToolCategory) = update { it.copy(toolCategory = category) }

    /** 提交路线弹窗的选择；取消弹窗不调用本方法，不污染已应用选择。 */
    fun selectRouteOptions(mode: TravelMode, navigation: EmbeddedNavigationMode = state.value.navigationMode) =
        update { it.copy(routeMode = mode, navigationMode = navigation) }

    /** 记录已持久化或重新读取的隐私决定；调用者必须先确认保存成功。 */
    fun setPrivacyDecision(decision: PrivacyDecision) = update { it.copy(privacyDecision = decision) }

    /** 保存 SDK 确认生效的完整设置，而不是尚未提交的弹窗草稿。 */
    fun applyMapSettings(settings: DemoMapSettings) = update { it.copy(mapSettings = settings) }

    /** 接收当前会话的定位值；迟到回调由页面请求边界过滤后才允许进入。 */
    fun setLocation(location: MapLocation?) = update { it.copy(lastLocation = location) }

    /** 同步轻量导航运行标记，终止或释放会话时必须传 false。 */
    fun setNavigationActive(active: Boolean) = update { it.copy(embeddedNavigationActive = active) }

    /** 更新地图宿主运行阶段与能力数量，避免视图层单独维护一套就绪状态。 */
    fun setMapPhase(phase: MainMapPhase, capabilityCount: Int = 0) = update {
        it.copy(mapPhase = phase, capabilityCount = capabilityCount)
    }

    /** 接收业务结果快照；结果条只渲染此状态，不由控制器直接操作 View。 */
    fun showStatus(status: DemoStatus) = update { it.copy(status = status) }

    /** 暂存等待位置授权的操作；同一系统请求在途时不重复启动授权流程。 */
    fun awaitLocationPermission(action: LocationPermissionAction, requestInFlight: Boolean) = update {
        it.copy(pendingLocationAction = action, permissionRequestInFlight = requestInFlight)
    }

    /** 收拢位置授权结果，清除 pending，并把可执行操作返回给宿主。 */
    fun completeLocationPermission(granted: Boolean, permanentlyDenied: Boolean = false): LocationPermissionAction? {
        val pending = state.value.pendingLocationAction
        update { it.copy(pendingLocationAction = null, permissionRequestInFlight = false,
            permissionDenied = !granted, permissionPermanentlyDenied = !granted && permanentlyDenied) }
        return pending.takeIf { granted }
    }

    /** 取消等待的系统授权操作；撤回隐私或关闭提示后禁止返回时自动继续。 */
    fun cancelPendingLocationAction() = update {
        it.copy(pendingLocationAction = null, permissionRequestInFlight = false)
    }

    /** 新 Activity 使用新 SDK 会话；清除旧运行态但保留用户设置和选择。 */
    fun resetSessionState() = update { it.copy(lastLocation = null, embeddedNavigationActive = false,
        mapPhase = MainMapPhase.DISABLED, capabilityCount = 0) }

    /** 对损坏或旧版本枚举名称回退到默认值，不把恢复失败变成启动崩溃。 */
    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    /** 恢复键保留旧约定，避免此次职责拆分破坏已保存的用户选择。 */
    internal companion object {
        /** 厂商枚举 name 的旧恢复键，不保存 MapProvider 实例。 */
        const val PROVIDER = "main.provider"
        /** 路线出行方式枚举 name 的旧恢复键，不保存规划结果。 */
        const val ROUTE_MODE = "main.routeMode"
        /** 轻导航真实/模拟模式枚举 name 的旧恢复键，不保存活跃导航组件。 */
        const val NAVIGATION_MODE = "main.navigationMode"
        /** 工具分类枚举 name 的旧恢复键，保留重建前所选面板。 */
        const val TOOL_CATEGORY = "main.toolCategory"
        /** 全部地图设置编码字符串的旧恢复键，编码顺序由 DemoMapSettings 维护。 */
        const val MAP_SETTINGS = "main.mapSettings"
        /** 待授权动作枚举 name 的旧恢复键；null 表示没有待办，不保存点击闭包。 */
        const val PENDING_ACTION = "main.pendingAction"
        /** 系统权限请求在途标记的旧恢复键，用于重建后避免重复发起授权。 */
        const val PERMISSION_IN_FLIGHT = "main.permissionInFlight"
        /** 最近位置授权拒绝标记的旧恢复键，不代替实际权限检查。 */
        const val PERMISSION_DENIED = "main.permissionDenied"
        /** 需引导系统应用设置的拒绝标记旧恢复键，不表示隐私同意被拒绝。 */
        const val PERMISSION_PERMANENT = "main.permissionPermanent"
    }
}
