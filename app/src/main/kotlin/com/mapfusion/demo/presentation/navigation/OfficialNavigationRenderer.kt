package com.mapfusion.demo.presentation.navigation

import android.content.Context
import android.view.View
import com.mapfusion.api.model.OfficialNavigationCapabilities
import com.mapfusion.api.model.OfficialNavigationState
import com.mapfusion.demo.DemoTheme
import com.mapfusion.demo.R
import com.mapfusion.demo.StatusTone

/** 仅把不可变业务快照渲染到已有控件；不创建布局、不订阅引擎、不处理用户命令。 */
internal class OfficialNavigationRenderer(
    /** 页面资源/密度 Context，仅用于格式资源与 Drawable，不放入状态流。 */
    private val context: Context,
    /** 当前页面已初始化的控件引用表；渲染器不构造整页、不保存业务状态副本。 */
    private val views: OfficialNavigationViews,
    /** 页面级样式工厂，用于选中/禁用外观；监听和关闭责任仍归 Page。 */
    private val widgets: OfficialNavigationWidgets,
    /** 当前变体能力值，和 UiState 一起交给 ControlPolicy，不是原生引擎引用。 */
    private val capabilities: OfficialNavigationCapabilities,
) {
    /** 输入状态与宿主会话可用性，所有展示和控件门禁由该快照推导，不直接读取导航器。 */
    fun render(state: OfficialNavigationUiState, sessionAvailable: Boolean) {
        val policy = OfficialNavigationControlPolicy.from(state, capabilities, sessionAvailable)
        renderBadge(state)
        renderChoices(state, policy)
        renderActions(state, policy)
        renderMessages(state)
    }

    /** 输入导航状态，更新顶部中文徽章及错误色，其余业务详情保持独立。 */
    private fun renderBadge(state: OfficialNavigationUiState) {
        val error = state.navigationState == OfficialNavigationState.FAILED
        views.stateBadge.text = OfficialNavigationText.state(state.navigationState)
        views.stateBadge.setTextColor(if (error) DemoTheme.ERROR else DemoTheme.BRAND_DARK)
        views.stateBadge.background = DemoTheme.roundedRect(context,
            if (error) DemoTheme.ERROR_SOFT else DemoTheme.BRAND_SOFT,
            if (error) DemoTheme.ERROR_BORDER else DemoTheme.BRAND_BORDER, 999f)
    }

    /** 输入选择与纯策略，同步选中态、速度行和滑条；程序进度更新不会发出用户意图。 */
    private fun renderChoices(state: OfficialNavigationUiState, policy: OfficialNavigationControlPolicy) {
        views.modes.forEach { (mode, button) ->
            widgets.styleChoice(button, mode == state.navigationMode, segmented = true)
            widgets.enabled(button, policy.choices)
        }
        views.travelModes.forEach { (mode, button) ->
            widgets.styleChoice(button, mode == state.travelMode)
            widgets.enabled(button, policy.choices)
        }
        views.speedRow.visibility = if (policy.speedVisible) View.VISIBLE else View.GONE
        views.speed.isEnabled = policy.speedEnabled
        views.speed.alpha = if (policy.speedEnabled) 1f else 0.42f
        val progress = state.simulationSpeed - OFFICIAL_NAVIGATION_MIN_SPEED
        if (views.speed.progress != progress) views.speed.progress = progress
        views.speedValue.text = context.getString(R.string.official_navigation_speed, state.simulationSpeed)
    }

    /** 输入状态与策略，更新暂停/路线/语音/配色文案并逐个应用可用性，不调用引擎。 */
    private fun renderActions(state: OfficialNavigationUiState, policy: OfficialNavigationControlPolicy) {
        views.actions.forEach { (action, button) ->
            val enabled = when (action) {
                OfficialNavigationAction.Start -> policy.start
                OfficialNavigationAction.PauseOrResume -> policy.pauseResume
                OfficialNavigationAction.NextRoute -> policy.route
                OfficialNavigationAction.Stop -> policy.stop
                OfficialNavigationAction.Reroute, OfficialNavigationAction.Overview, OfficialNavigationAction.Follow -> policy.advanced
                OfficialNavigationAction.ToggleVoice, OfficialNavigationAction.CycleDayNight -> policy.settings
                else -> false
            }
            when (action) {
                OfficialNavigationAction.PauseOrResume -> button.text =
                    if (state.navigationState == OfficialNavigationState.PAUSED) "继续" else "暂停"
                OfficialNavigationAction.NextRoute -> button.text = if (state.routeSummaries.isEmpty()) "路线"
                    else context.getString(R.string.official_navigation_route, state.selectedRouteIndex + 1, state.routeSummaries.size)
                OfficialNavigationAction.ToggleVoice -> {
                    button.text = if (state.voiceGuidanceEnabled) "语音开" else "语音关"
                    widgets.styleChoice(button, state.voiceGuidanceEnabled)
                }
                OfficialNavigationAction.CycleDayNight -> button.text = OfficialNavigationText.dayNightButton(state.dayNightMode)
                else -> Unit
            }
            widgets.enabled(button, enabled)
        }
    }

    /** 输入详情和占位字段，按语义色更新展示；隐藏事件不会通过此层覆盖旧文案。 */
    private fun renderMessages(state: OfficialNavigationUiState) {
        val error = state.detailTone == NaviTone.ERROR
        val tone = if (error) StatusTone.ERROR else StatusTone.INFO
        views.detail.text = state.detailText
        views.detail.setTextColor(if (error) DemoTheme.ERROR else DemoTheme.TEXT_PRIMARY)
        views.detail.background = DemoTheme.roundedRect(context, tone.cardBgColor, tone.cardBorderColor, 11f)
        views.placeholder.visibility = if (state.placeholderVisible) View.VISIBLE else View.GONE
        views.placeholder.text = state.placeholderText
        views.placeholder.setTextColor(if (state.placeholderTone == NaviTone.ERROR) DemoTheme.ERROR else DemoTheme.TEXT_SECONDARY)
    }
}
