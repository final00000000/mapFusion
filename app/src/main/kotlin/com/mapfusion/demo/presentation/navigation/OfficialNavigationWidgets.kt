package com.mapfusion.demo.presentation.navigation

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.TextView
import com.mapfusion.demo.DemoTheme

/** 按钮的页面语义样式，集中保持开始、停止与普通控件的视觉区分。 */
internal enum class NavigationButtonStyle { COMPACT, STANDARD, PRIMARY, DANGER }

/** 页面级小控件工厂，只封装尺寸和样式；记录监听按钮以便 Activity 销毁时显式断开回调。 */
internal class OfficialNavigationWidgets(private val context: Context) : AutoCloseable {
    private val buttons = mutableListOf<Button>()

    /** 输入 dp 整数，沿用导航页原有密度换算，避免拆分改变面板尺寸。 */
    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** 输入文案、语义样式和点击动作，创建统一紧凑按钮并记录可释放的监听关系。 */
    fun button(label: String, style: NavigationButtonStyle = NavigationButtonStyle.COMPACT, action: () -> Unit): Button {
        val primary = style == NavigationButtonStyle.PRIMARY
        val danger = style == NavigationButtonStyle.DANGER
        return Button(context).apply {
            text = label
            isAllCaps = false
            textSize = if (style == NavigationButtonStyle.COMPACT) 12.5f else 13f
            maxLines = 1
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            stateListAnimator = null
            setPadding(dp(4), 0, dp(4), 0)
            typeface = if (style == NavigationButtonStyle.COMPACT) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.WHITE else if (danger) DemoTheme.ERROR else DemoTheme.TEXT_PRIMARY)
            background = DemoTheme.interactiveSurface(
                context = context,
                fillColor = if (primary) DemoTheme.BRAND else if (danger) DemoTheme.ERROR_SOFT else DemoTheme.SURFACE_ELEVATED,
                pressedColor = if (primary) DemoTheme.BRAND_DARK else DemoTheme.SURFACE_PRESSED,
                strokeColor = when (style) {
                    NavigationButtonStyle.PRIMARY -> DemoTheme.BRAND_DARK
                    NavigationButtonStyle.DANGER -> DemoTheme.ERROR_BORDER
                    NavigationButtonStyle.STANDARD -> DemoTheme.BORDER_STRONG
                    NavigationButtonStyle.COMPACT -> DemoTheme.BORDER
                },
                cornerDp = if (style == NavigationButtonStyle.COMPACT) 10f else 11f,
            )
            setOnClickListener { action() }
            buttons.add(this)
        }
    }

    /** 输入行标题，创建固定风格的标签；布局宽度交由控制面板构造层决定。 */
    fun label(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(DemoTheme.TEXT_MUTED)
    }

    /** 输入按钮和选中态，分段模式使用实心强调，普通选择使用浅色描边，不改变业务状态。 */
    fun styleChoice(button: Button, selected: Boolean, segmented: Boolean = false) {
        button.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        button.setTextColor(when {
            segmented && selected -> Color.WHITE
            segmented -> DemoTheme.TEXT_SECONDARY
            selected -> DemoTheme.BRAND_DARK
            else -> DemoTheme.TEXT_PRIMARY
        })
        button.background = DemoTheme.interactiveSurface(
            context = context,
            fillColor = when {
                segmented && selected -> DemoTheme.BRAND
                segmented -> Color.TRANSPARENT
                selected -> DemoTheme.BRAND_SOFT
                else -> DemoTheme.SURFACE_ELEVATED
            },
            pressedColor = if (segmented && selected) DemoTheme.BRAND_DARK else DemoTheme.SURFACE_PRESSED,
            strokeColor = if (segmented) Color.TRANSPARENT else if (selected) DemoTheme.BRAND else DemoTheme.BORDER,
            cornerDp = if (segmented) 8f else 10f,
            strokeDp = if (segmented) 0f else 1f,
        )
    }

    /** 输入可用性，同时更新点击门禁和透明度，渲染层不散落禁用视觉规则。 */
    fun enabled(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.42f
    }

    /** 页面销毁时断开所有按钮监听并清空记录，避免原生 View 或回调继续持有 Activity。 */
    override fun close() {
        buttons.forEach { it.setOnClickListener(null) }
        buttons.clear()
    }
}
