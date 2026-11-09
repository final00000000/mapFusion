package com.mapfusion.demo.main

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.mapfusion.demo.DemoTheme

/** 首页及弹窗的小型控件工厂，只负责外观和点击绑定，不包含地图业务逻辑。 */
internal class MainWidgets(
    /** 创建控件所用的上下文；只读取主题资源与密度，不持有页面引用。 */
    private val context: Context
) {
    /** 将布局 dp 转为当前宿主像素，保持旧 Demo 的尺寸计算方式。 */
    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** 创建弹窗分节标题，统一字体与边距。 */
    fun label(text: String): TextView = TextView(context).apply {
        this.text = text; textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(DemoTheme.TEXT_MUTED); setPadding(dp(2), dp(8), dp(2), dp(2))
    }

    /** 根据值列表创建选择器；选项位置限定在列表内，避免恢复非法下标。 */
    fun spinner(items: List<String>, selected: Int): Spinner = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        background = DemoTheme.roundedRect(context, DemoTheme.SURFACE, DemoTheme.BORDER_STRONG)
        setPadding(dp(10), 0, dp(10), 0)
    }

    /** 仅把开关变化交给草稿回调，不直接提交业务状态或调用 SDK。 */
    @Suppress("UseSwitchCompatOrMaterialCode")
    fun toggle(text: String, checked: Boolean, onChanged: (Boolean) -> Unit): Switch = Switch(context).apply {
        this.text = text; isChecked = checked; textSize = 14f
        setTextColor(DemoTheme.TEXT_PRIMARY); setPadding(dp(4), 0, dp(4), 0)
        setOnCheckedChangeListener { _, value -> onChanged(value) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42))
    }

    /** 创建紧凑按钮，取消系统最小高度，避免工具卡片挤占地图空间。 */
    fun button(text: String, onClick: () -> Unit): Button = Button(context).apply {
        this.text = text; isAllCaps = false; textSize = 12.5f
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        stateListAnimator = null; setPadding(dp(6), 0, dp(6), 0)
        setTextColor(DemoTheme.TEXT_PRIMARY)
        background = DemoTheme.interactiveSurface(context, DemoTheme.SURFACE_ELEVATED,
            DemoTheme.SURFACE_PRESSED, DemoTheme.BORDER, 10f)
        setOnClickListener { onClick() }
    }

    /** 根据单一状态渲染分段选择按钮，不在按钮内部保存业务选择。 */
    fun styleSegment(button: Button, selected: Boolean, category: Boolean = false) {
        button.setTextColor(if (selected) Color.WHITE else DemoTheme.TEXT_SECONDARY)
        button.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        button.background = DemoTheme.interactiveSurface(context,
            if (selected) DemoTheme.BRAND else Color.TRANSPARENT,
            if (selected) DemoTheme.BRAND_DARK else DemoTheme.SURFACE_PRESSED,
            Color.TRANSPARENT, if (category) 9f else 8f, 0f)
    }

    /** 将弹窗操作排列成可横向滚动行，保持小屏与横屏上的全部操作可达。 */
    fun actionStrip(vararg actions: Pair<String, () -> Unit>): HorizontalScrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            actions.forEachIndexed { index, (label, action) ->
                addView(button(label, action).apply { minWidth = dp(82); setPadding(dp(14), 0, dp(14), 0) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                        if (index > 0) marginStart = dp(8)
                    })
            }
        })
    }
}
