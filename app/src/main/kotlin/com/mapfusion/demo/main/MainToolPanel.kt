package com.mapfusion.demo.main

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mapfusion.demo.DemoTheme
import com.mapfusion.demo.StatusTone
import com.mapfusion.demo.presentation.main.DemoStatus
import com.mapfusion.demo.presentation.main.DemoToolCategory

/** 分类工具面板的视图状态持有者；只拥有控件与渲染缓存，不持有地图 Session。 */
internal class MainToolPanel(private val context: Context, private val landscape: Boolean,
    private val onCategory: (DemoToolCategory) -> Unit, private val onAction: (MainAction) -> Unit) {
    private val widgets = MainWidgets(context)
    private val categories = mutableMapOf<DemoToolCategory, Button>()
    private val grid = GridLayout(context).apply { columnCount = 4; alignmentMode = GridLayout.ALIGN_MARGINS }
    private var renderedCategory: DemoToolCategory? = null
    private var renderedOfficialAvailability: Boolean? = null
    private var renderedStatus: DemoStatus? = null
    private val result = TextView(context).apply {
        textSize = 13f; setTextColor(DemoTheme.TEXT_SECONDARY)
        setPadding(widgets.dp(14), widgets.dp(10), widgets.dp(14), widgets.dp(10))
        gravity = Gravity.CENTER_VERTICAL; setLineSpacing(widgets.dp(2).toFloat(), 1f)
        maxLines = if (landscape) 2 else 3; ellipsize = TextUtils.TruncateAt.END
        minHeight = widgets.dp(if (landscape) 46 else 54)
    }
    val root: LinearLayout = buildPanel()

    /** 构造结果条、分类选择与动态工具网格，横屏使用侧栏，竖屏使用底部卡片。 */
    private fun buildPanel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(widgets.dp(12), widgets.dp(if (landscape) 6 else 8), widgets.dp(12), widgets.dp(if (landscape) 8 else 12))
        background = if (landscape) DemoTheme.roundedRect(context, DemoTheme.SURFACE, cornerDp = 0f)
            else DemoTheme.topSheetBackground(context)
        elevation = widgets.dp(8).toFloat()
        if (!landscape) addView(DemoTheme.buildSheetHandle(context),
            LinearLayout.LayoutParams(-1, widgets.dp(10)).apply { bottomMargin = widgets.dp(4) })
        addView(result, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = widgets.dp(if (landscape) 6 else 10) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(widgets.dp(4), widgets.dp(4), widgets.dp(4), widgets.dp(4))
            background = DemoTheme.roundedRect(context, DemoTheme.SURFACE_SUBTLE, DemoTheme.BORDER, 12f)
            DemoToolCategory.entries.forEachIndexed { index, category ->
                val button = widgets.button(category.title) { onCategory(category) }
                categories[category] = button
                addView(button, LinearLayout.LayoutParams(0, widgets.dp(if (landscape) 32 else 36), 1f).apply {
                    if (index > 0) marginStart = widgets.dp(4)
                })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = widgets.dp(if (landscape) 6 else 8) })
        addView(grid, LinearLayout.LayoutParams(-1, -2))
    }

    /** 分类或厂商导航能力变化时重建按钮，避免切厂商后留下不适用的官方入口。 */
    fun renderCategory(category: DemoToolCategory, officialAvailable: Boolean) {
        if (renderedCategory == category && renderedOfficialAvailability == officialAvailable) return
        renderedCategory = category; renderedOfficialAvailability = officialAvailable
        categories.forEach { (item, button) -> widgets.styleSegment(button, item == category, category = true) }
        val actions = MainActionCatalog.actions(category, officialAvailable)
        val rows = MainActionCatalog.rowCount(actions.size)
        val height = if (landscape) 34 else 42
        grid.removeAllViews(); grid.rowCount = rows
        grid.layoutParams = LinearLayout.LayoutParams(-1, widgets.dp(rows * (height + 6)))
        actions.forEachIndexed { index, action ->
            grid.addView(actionButton(action), GridLayout.LayoutParams(
                GridLayout.spec(index / 4), GridLayout.spec(index % 4, 1, 1f)).apply {
                width = 0; this.height = widgets.dp(height)
                setMargins(widgets.dp(3), widgets.dp(3), widgets.dp(3), widgets.dp(3))
            })
        }
    }

    /** 只在消息快照改变时更新结果条，避免每次状态收集重复创建背景。 */
    fun renderStatus(status: DemoStatus) {
        if (renderedStatus == status) return
        renderedStatus = status
        val tone = StatusTone.valueOf(status.kind.name)
        result.text = DemoTheme.formatStatusText(status.title, status.detail, tone)
        result.background = DemoTheme.roundedRect(context, tone.cardBgColor, tone.cardBorderColor, 12f)
    }

    /** 把纯功能描述转成按钮；点击只发出 MainAction，不在视图内执行 SDK 请求。 */
    private fun actionButton(action: MainActionSpec): Button = widgets.button(action.title) { onAction(action.action) }.apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        typeface = if (action.style == MainActionStyle.DEFAULT) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        val colors = when (action.style) {
            MainActionStyle.DEFAULT -> Triple(DemoTheme.TEXT_PRIMARY, DemoTheme.SURFACE_ELEVATED, DemoTheme.BORDER)
            MainActionStyle.PRIMARY -> Triple(DemoTheme.BRAND_DARK, DemoTheme.BRAND_SOFT, DemoTheme.BRAND_BORDER)
            MainActionStyle.DANGER -> Triple(DemoTheme.ERROR, DemoTheme.ERROR_SOFT, DemoTheme.ERROR_BORDER)
        }
        setTextColor(colors.first)
        background = DemoTheme.interactiveSurface(context, colors.second, DemoTheme.SURFACE_PRESSED, colors.third, 11f)
    }
}
