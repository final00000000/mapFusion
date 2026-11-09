package com.mapfusion.demo.navigation

import com.mapfusion.api.capability.OfficialNavigator
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.OfficialNavigationCapabilities
import com.mapfusion.api.model.OfficialNavigationFeature
import com.mapfusion.api.model.OfficialNavigationOptions
import com.mapfusion.api.model.OfficialNavigationRequest
import com.mapfusion.api.model.OfficialNavigationState
import com.mapfusion.api.model.RouteRequest
import com.mapfusion.demo.presentation.navigation.NaviTone
import com.mapfusion.demo.presentation.navigation.OfficialNavigationAction
import com.mapfusion.demo.presentation.navigation.OfficialNavigationControlPolicy
import com.mapfusion.demo.presentation.navigation.OfficialNavigationText
import com.mapfusion.demo.presentation.navigation.OfficialNavigationViewModel

/**
 * 把用户意图转换为统一导航会话操作；只持请求端点、能力和页面级宿主，不缓存业务状态。
 * 权限由 Activity 在调用 start 前处理；结果交给 ViewModel 归约，停止成功通过回调返回页面。
 */
internal class OfficialNavigationCommands(
    private val host: OfficialNavigationSessionHost,
    private val viewModel: OfficialNavigationViewModel,
    private val capabilities: OfficialNavigationCapabilities,
    private val origin: LatLng,
    private val destination: LatLng,
    private val onStopped: () -> Unit,
) {
    /** 权限请求前检查隐私和共享开始策略，不向 Activity 暴露导航器或复制业务状态。 */
    fun canStart(): Boolean = host.ensureConsent() && policy().start

    /** 精确定位权限已确认后提交真实/模拟请求；复核隐私并拒绝重复启动，端点不写入日志。 */
    fun start() {
        if (!canStart()) return
        val state = viewModel.state.value
        val request = OfficialNavigationRequest(
            routeRequest = RouteRequest(mode = state.travelMode, origin = origin, destination = destination),
            options = OfficialNavigationOptions(
                navigationMode = state.navigationMode,
                simulationSpeedKilometersPerHour = state.simulationSpeed,
            ),
        )
        viewModel.prepareStart()
        host.start(request) { result, engineState ->
            when (result) {
                is MapResult.Success -> viewModel.markStartSucceeded(engineState)
                is MapResult.Failure -> {
                    viewModel.markStartFailed()
                    viewModel.showMapError("导航启动失败", result.error, engineState)
                }
            }
        }
    }

    /** 输入除开始之外的页面意图，按共享可用性策略和能力过滤，再调用引擎或纯选择归约。 */
    fun handle(action: OfficialNavigationAction) {
        if (!host.ensureConsent()) return
        val policy = policy()
        when (action) {
            OfficialNavigationAction.Start -> Unit
            is OfficialNavigationAction.SelectMode -> if (policy.choices) viewModel.selectNavigationMode(action.mode)
            is OfficialNavigationAction.SelectTravel -> if (policy.choices) viewModel.selectTravelMode(action.mode)
            is OfficialNavigationAction.SelectSpeed -> if (policy.speedEnabled) viewModel.setSimulationSpeed(action.kilometersPerHour)
            OfficialNavigationAction.PauseOrResume -> if (policy.pauseResume) pauseOrResume()
            OfficialNavigationAction.Stop -> if (policy.stop) stop()
            OfficialNavigationAction.NextRoute -> if (policy.route) selectNextRoute()
            OfficialNavigationAction.Reroute -> if (policy.advanced && supports(OfficialNavigationFeature.MANUAL_REROUTE)) {
                control("正在重新规划路线", "路线重新规划失败", { it.recalculateRoute() })
            }
            OfficialNavigationAction.Overview -> if (policy.advanced && supports(OfficialNavigationFeature.ROUTE_OVERVIEW)) {
                control("已显示路线全览", "路线全览失败", { it.showRouteOverview() })
            }
            OfficialNavigationAction.Follow -> if (policy.advanced && supports(OfficialNavigationFeature.RESTORE_FOLLOWING)) {
                control("已恢复车标跟随", "恢复跟随失败", { it.resumeFollowing() })
            }
            OfficialNavigationAction.ToggleVoice -> if (policy.settings && supports(OfficialNavigationFeature.VOICE_CONTROL)) toggleVoice()
            OfficialNavigationAction.CycleDayNight -> if (policy.settings && supports(OfficialNavigationFeature.DAY_NIGHT_MODE)) cycleDayNight()
            OfficialNavigationAction.ApplySpeed -> if (policy.speedEnabled && viewModel.state.value.navigationActive &&
                supports(OfficialNavigationFeature.RUNTIME_SIMULATION_SPEED)) applySpeed()
        }
    }

    /** 根据当前引擎业务快照选择暂停或继续，成功后统一清除等待并更新详情。 */
    private fun pauseOrResume() {
        val paused = viewModel.state.value.navigationState == OfficialNavigationState.PAUSED
        host.perform({ if (paused) it.resume() else it.pause() }) { result, state ->
            when (result) {
                is MapResult.Success -> {
                    viewModel.onControlState(state)
                    viewModel.setDetail(if (state == OfficialNavigationState.PAUSED) "导航已暂停" else "导航已继续", NaviTone.INFO)
                }
                is MapResult.Failure -> viewModel.showMapError("导航控制失败", result.error, state)
            }
        }
    }

    /** 停止当前会话；成功才归约停止快照并通知 Activity finish，失败保留页面错误。 */
    private fun stop() {
        host.perform({ it.stop() }) { result, state ->
            when (result) {
                is MapResult.Success -> { viewModel.onControlState(state); onStopped() }
                is MapResult.Failure -> viewModel.showMapError("停止导航失败", result.error, state)
            }
        }
    }

    /** 使用 ViewModel 推导的下一路线下标，只有厂商成功后才提交选择和路线详情。 */
    private fun selectNextRoute() {
        val next = viewModel.nextRouteIndex() ?: return
        host.perform({ it.selectRoute(next) }) { result, state ->
            when (result) {
                is MapResult.Success -> { viewModel.selectRoute(next); viewModel.showSelectedRoute() }
                is MapResult.Failure -> viewModel.showMapError("路线切换失败", result.error, state)
            }
        }
    }

    /** 把滑条已保存的 km/h 选择应用到运行中的模拟会话，不在拖动阶段重复调用厂商。 */
    private fun applySpeed() {
        val speed = viewModel.state.value.simulationSpeed
        control("模拟速度已调整为 $speed km/h", "模拟速度调整失败", { it.setSimulationSpeed(speed) })
    }

    /** 尝试切换语音开关；厂商失败时保持原选择和 SavedStateHandle 不变。 */
    private fun toggleVoice() {
        val enabled = !viewModel.state.value.voiceGuidanceEnabled
        control(if (enabled) "语音引导已开启" else "语音引导已关闭", "语音设置失败",
            { it.setVoiceGuidanceEnabled(enabled) }) { viewModel.setVoiceGuidanceEnabled(enabled) }
    }

    /** 使用 ViewModel 的配色循环策略调用厂商；成功后才保存下一配色。 */
    private fun cycleDayNight() {
        val next = viewModel.nextDayNightMode()
        control("导航配色：${OfficialNavigationText.dayNight(next)}", "导航配色设置失败",
            { it.setDayNightMode(next) }) { viewModel.setDayNightMode(next) }
    }

    /** 输入操作及成功/失败文案，统一处理 MapResult；成功副作用仅用于提交厂商已确认的选择。 */
    private fun control(
        success: String,
        failure: String,
        operation: (OfficialNavigator) -> MapResult<Unit>,
        onSuccess: () -> Unit = {},
    ) {
        host.perform(operation) { result, state ->
            when (result) {
                is MapResult.Success -> { onSuccess(); viewModel.setDetail(success, NaviTone.INFO) }
                is MapResult.Failure -> viewModel.showMapError(failure, result.error, state)
            }
        }
    }

    /** 输入统一功能枚举，读取变体能力，不探测或引用厂商具体类型。 */
    private fun supports(feature: OfficialNavigationFeature): Boolean = capabilities.supports(feature)

    /** 每次操作重新从只读业务快照推导可用性，避免渲染与命令层维护不同状态。 */
    private fun policy(): OfficialNavigationControlPolicy =
        OfficialNavigationControlPolicy.from(viewModel.state.value, capabilities, host.sessionAvailable)
}
