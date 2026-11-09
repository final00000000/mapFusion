package com.mapfusion.demo.presentation.navigation

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.mapfusion.api.model.OfficialNavigationCapabilities
import com.mapfusion.api.model.OfficialNavigationFeature
import com.mapfusion.api.model.OfficialNavigationMode
import com.mapfusion.demo.DemoTheme
import com.mapfusion.demo.StatusTone
import com.mapfusion.demo.R

/** 仅构造导航控制面板并发出意图；能力决定控件是否存在，状态/可用性由独立渲染器处理。 */
internal class OfficialNavigationControls(
    /** 本页控件/样式 Context，不访问引擎，不缓存到 VM 或全局对象。 */
    private val context: Context,
    /** 当前变体能力值，用于决定模式、出行和高级按钮是否构造；不代表已完成初始化。 */
    private val capabilities: OfficialNavigationCapabilities,
    /** 页面与渲染器共享的控件引用表；构造时填入 View，不存业务选择或 SDK 会话。 */
    private val views: OfficialNavigationViews,
    /** 本页小控件工厂，跟踪按钮监听供关闭时解除；所有权由 Page 管理。 */
    private val widgets: OfficialNavigationWidgets,
    /** 向 Page 发出值类型意图的回调，不直接调用 SDK；滑条仅发送用户变化和松手意图。 */
    private val emit: (OfficialNavigationAction) -> Unit,
) : AutoCloseable {
    /** 构造详情、模式、出行、速度、高级控制与四个操作按钮，保留原页全部功能。 */
    fun build(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(12))
        background = DemoTheme.topSheetBackground(context, DemoTheme.SURFACE, DemoTheme.BORDER, 20f)
        elevation = dp(8).toFloat()
        addView(DemoTheme.buildSheetHandle(context), LinearLayout.LayoutParams(-1, dp(10)).apply { bottomMargin = dp(4) })
        views.detail = TextView(context).apply {
            text = "等待开始"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(DemoTheme.TEXT_PRIMARY)
            maxLines = 2
            minHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = DemoTheme.roundedRect(context, StatusTone.INFO.cardBgColor, StatusTone.INFO.cardBorderColor, 11f)
            includeFontPadding = false
        }
        addView(views.detail, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        addView(buildModes(), rowParams(38, 4))
        addView(buildTravelModes(), rowParams(38, 6))
        views.speedRow = buildSpeed()
        addView(views.speedRow, rowParams(36, 4))
        buildAdvanced()?.let { addView(it, rowParams(38, 6)) }
        addView(buildActions(), rowParams(42, 10))
    }

    /** 按统一能力过滤真实/模拟选择，分段容器保留选中背景与均分宽度。 */
    private fun buildModes(): View {
        val buttons = OfficialNavigationMode.entries.filter(capabilities::supports).map { mode ->
            widgets.button(OfficialNavigationText.mode(mode)) { emit(OfficialNavigationAction.SelectMode(mode)) }
                .also { views.modes[mode] = it }
        }
        return choiceRow("模式", buttons, segmented = true)
    }

    /** 使用固定中文出行顺序过滤厂商支持项；将按钮关联统一模式，不在 View 中保存选择。 */
    private fun buildTravelModes(): View {
        val buttons = OFFICIAL_NAVIGATION_TRAVEL_MODE_ORDER.filter(capabilities::supports).map { mode ->
            widgets.button(OfficialNavigationText.travel(mode)) { emit(OfficialNavigationAction.SelectTravel(mode)) }
                .also { views.travelModes[mode] = it }
        }
        return choiceRow("出行", buttons, scrollWidth = 72)
    }

    /** 构造 20–120 km/h 滑条；仅用户拖动发出选择，松手发出运行时应用意图，渲染不回写状态。 */
    private fun buildSpeed(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(widgets.label("速度"), LinearLayout.LayoutParams(dp(44), -1))
        views.speed = SeekBar(context).apply {
            max = OFFICIAL_NAVIGATION_MAX_SPEED - OFFICIAL_NAVIGATION_MIN_SPEED
            progress = OFFICIAL_NAVIGATION_DEFAULT_SPEED - OFFICIAL_NAVIGATION_MIN_SPEED
            setOnSeekBarChangeListener(
                /** 视图级速度监听器，只发送用户意图，关闭控件时解除绑定。 */
                object : SeekBar.OnSeekBarChangeListener {
                    /** 只接收来自用户的进度，将偏移转换为 km/h，防止状态渲染导致反馈循环。 */
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) emit(OfficialNavigationAction.SelectSpeed(OFFICIAL_NAVIGATION_MIN_SPEED + progress))
                    }

                    /** 拖动开始无需调用厂商；速度仅在松手后一次性应用。 */
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                    /** 滑条松手后发出应用意图，运行状态和能力仍由命令层校验。 */
                    override fun onStopTrackingTouch(seekBar: SeekBar?) { emit(OfficialNavigationAction.ApplySpeed) }
                },
            )
        }
        addView(views.speed, LinearLayout.LayoutParams(0, -1, 1f))
        views.speedValue = TextView(context).apply {
            text = context.getString(R.string.official_navigation_speed, OFFICIAL_NAVIGATION_DEFAULT_SPEED)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(DemoTheme.BRAND_DARK)
        }
        addView(views.speedValue, LinearLayout.LayoutParams(dp(68), -1))
    }

    /** 按统一功能能力构造全览、跟随、重算、语音和昼夜控件，五项时保留横向滚动。 */
    private fun buildAdvanced(): View? {
        val entries = listOf(
            Triple(OfficialNavigationFeature.ROUTE_OVERVIEW, "全览", OfficialNavigationAction.Overview),
            Triple(OfficialNavigationFeature.RESTORE_FOLLOWING, "跟随", OfficialNavigationAction.Follow),
            Triple(OfficialNavigationFeature.MANUAL_REROUTE, "重算", OfficialNavigationAction.Reroute),
            Triple(OfficialNavigationFeature.VOICE_CONTROL, "语音开", OfficialNavigationAction.ToggleVoice),
            Triple(OfficialNavigationFeature.DAY_NIGHT_MODE, "自动色", OfficialNavigationAction.CycleDayNight),
        )
        val buttons = entries.filter { capabilities.supports(it.first) }.map { (_, label, action) ->
            actionButton(label, action)
        }
        return if (buttons.isEmpty()) null else choiceRow("控制", buttons, scrollWidth = 66)
    }

    /** 构造开始、暂停/继续、路线切换和停止四个固定操作，保留主按钮及危险按钮语义。 */
    private fun buildActions(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val buttons = listOf(
            actionButton("开始", OfficialNavigationAction.Start, NavigationButtonStyle.PRIMARY),
            actionButton("暂停", OfficialNavigationAction.PauseOrResume, NavigationButtonStyle.STANDARD),
            actionButton("路线", OfficialNavigationAction.NextRoute, NavigationButtonStyle.STANDARD),
            actionButton("停止", OfficialNavigationAction.Stop, NavigationButtonStyle.DANGER),
        )
        addButtons(this, buttons, weighted = true, gap = 6, width = 0)
    }

    /** 输入意图和文案，登记按钮供渲染器按身份查找，不绑定引擎方法。 */
    private fun actionButton(
        label: String,
        action: OfficialNavigationAction,
        style: NavigationButtonStyle = NavigationButtonStyle.COMPACT,
    ): Button = widgets.button(label, style) { emit(action) }.also { views.actions[action] = it }

    /** 输入标签和控件，四项以内均分宽度；更多项滚动，分段模式另加内边距和背景。 */
    private fun choiceRow(label: String, buttons: List<Button>, segmented: Boolean = false, scrollWidth: Int = 66): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(widgets.label(label), LinearLayout.LayoutParams(dp(44), -1))
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (segmented) {
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    background = DemoTheme.roundedRect(context, DemoTheme.SURFACE_SUBTLE, DemoTheme.BORDER, 11f)
                }
            }
            val weighted = buttons.size <= 4
            addButtons(content, buttons, weighted, if (segmented) 4 else 5, scrollWidth)
            val child = if (weighted) content else HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(content, ViewGroup.LayoutParams(-2, -1))
            }
            addView(child, LinearLayout.LayoutParams(0, -1, 1f))
        }

    /** 输入容器、控件和宽度策略，统一设置行内间距，不改变按钮点击或业务状态。 */
    private fun addButtons(row: LinearLayout, buttons: List<Button>, weighted: Boolean, gap: Int, width: Int) {
        buttons.forEachIndexed { index, button ->
            val params = if (weighted) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(dp(width), -1)
            if (index > 0) params.marginStart = dp(gap)
            row.addView(button, params)
        }
    }

    /** 输入行高和上边距 dp，生成控制面板专用布局参数。 */
    private fun rowParams(height: Int, top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }

    /** 输入 dp 值，委托页面控件工厂保持一致的密度换算。 */
    private fun dp(value: Int): Int = widgets.dp(value)

    /** 页面释放时断开滑条监听；按钮监听由控件工厂统一断开。 */
    override fun close() { views.speed.setOnSeekBarChangeListener(null) }
}
