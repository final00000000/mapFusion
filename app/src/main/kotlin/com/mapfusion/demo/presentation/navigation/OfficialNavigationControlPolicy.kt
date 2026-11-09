package com.mapfusion.demo.presentation.navigation

import com.mapfusion.api.model.OfficialNavigationCapabilities
import com.mapfusion.api.model.OfficialNavigationFeature
import com.mapfusion.api.model.OfficialNavigationMode

/** 从业务快照、厂商能力和会话可用性推导按钮策略；不持有页面或会话，便于 JVM 验证。 */
internal data class OfficialNavigationControlPolicy(
    /** 开始按钮基础门禁：有效会话、未阻断、不忙碌且本会话尚未开始；不保证 SDK 初始化已完成。 */
    val start: Boolean,
    /** 是否允许按当前 NAVIGATING/PAUSED 状态暂停或继续；SDK 返回结果仍需另外处理。 */
    val pauseResume: Boolean,
    /** 是否允许循环切换路线：需有效会话、至少两条摘要且业务处于 busy 状态。 */
    val route: Boolean,
    /** 是否允许停止当前请求/导航；有效会话且 busy 即开放，不代表停止已成功。 */
    val stop: Boolean,
    /** 导航模式和出行方式能否切换；权限/提交等待、运行中或本会话已开始时关闭。 */
    val choices: Boolean,
    /** 重算、全览、跟随的运行基础门禁；要求导航或暂停，各按钮还须厂商能力支持。 */
    val advanced: Boolean,
    /** 语音/昼夜设置的基础门禁；不要求正在导航，各入口继续按能力过滤。 */
    val settings: Boolean,
    /** 是否展示速度行，只取决于模拟模式和速度能力；显示不等于已授权、会话可用或能操作。 */
    val speedVisible: Boolean,
    /** 速度滑条是否可操作：会话有效且速度行可见，并允许初始选择或支持运行中模拟调速。 */
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
