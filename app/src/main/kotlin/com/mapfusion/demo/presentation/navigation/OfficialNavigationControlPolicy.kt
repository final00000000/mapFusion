package com.mapfusion.demo.presentation.navigation

import com.mapfusion.api.model.OfficialNavigationCapabilities
import com.mapfusion.api.model.OfficialNavigationFeature
import com.mapfusion.api.model.OfficialNavigationMode

/** 从业务快照、厂商能力和会话可用性推导按钮策略；不持有页面或会话，便于 JVM 验证。 */
internal data class OfficialNavigationControlPolicy(
    val start: Boolean,
    val pauseResume: Boolean,
    val route: Boolean,
    val stop: Boolean,
    val choices: Boolean,
    val advanced: Boolean,
    val settings: Boolean,
    val speedVisible: Boolean,
    val speedEnabled: Boolean,
) {
    /** 汇集纯派生规则，渲染层与命令层共享同一套可用性判断，避免重复业务条件。 */
    companion object {
        /** 输入状态、能力和可用会话标记；撤回隐私或会话缺失时关闭所有有副作用的控件。 */
        fun from(
            state: OfficialNavigationUiState,
            capabilities: OfficialNavigationCapabilities,
            sessionAvailable: Boolean,
        ): OfficialNavigationControlPolicy {
            val allowed = !state.controlsBlocked && sessionAvailable
            val choices = allowed && !state.busy && !state.navigationStarted
            val speedVisible = capabilities.supports(OfficialNavigationFeature.SIMULATION_SPEED) &&
                state.navigationMode == OfficialNavigationMode.SIMULATED
            val runtimeSpeed = capabilities.supports(OfficialNavigationFeature.RUNTIME_SIMULATION_SPEED) &&
                state.navigationActive && state.navigationMode == OfficialNavigationMode.SIMULATED
            return OfficialNavigationControlPolicy(
                start = choices,
                pauseResume = allowed && state.navigationActive,
                route = allowed && state.routeSummaries.size > 1 && state.busy,
                stop = allowed && state.busy,
                choices = choices,
                advanced = allowed && state.navigationActive,
                settings = allowed,
                speedVisible = speedVisible,
                speedEnabled = allowed && speedVisible && (choices || runtimeSpeed),
            )
        }
    }
}
