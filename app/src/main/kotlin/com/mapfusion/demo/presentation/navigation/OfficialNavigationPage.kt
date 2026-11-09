package com.mapfusion.demo.presentation.navigation

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.OfficialNavigationCapabilities
import com.mapfusion.demo.DemoTheme
import com.mapfusion.demo.R

/**
 * 页面级 UI 宿主：组装标题、原生容器和独立控制面板，连接意图与独立渲染器。
 * 可以持有页面 Context；不持有导航器，Activity 销毁时必须 close 断开所有监听。
 */
internal class OfficialNavigationPage(
    private val context: Context,
    private val provider: Provider,
    capabilities: OfficialNavigationCapabilities,
    onReturn: () -> Unit,
) : AutoCloseable {
    private val views = OfficialNavigationViews()
    private val widgets = OfficialNavigationWidgets(context)
    private var actionListener: ((OfficialNavigationAction) -> Unit)? = null
    private var returnListener: (() -> Unit)? = onReturn
    private var closed = false
    private val controls = OfficialNavigationControls(context, capabilities, views, widgets, ::emit)
    val root: View = buildContent()
    val navigatorContainer: FrameLayout get() = views.navigatorHost
    private val renderer = OfficialNavigationRenderer(context, views, widgets, capabilities)

    /** 输入已由会话宿主封装身份/代次的页面回调，不让控件直接引用原生会话。 */
    fun bindActions(listener: (OfficialNavigationAction) -> Unit) { if (!closed) actionListener = listener }

    /** 输入状态快照和可用会话标记，委托渲染器；页面关闭后不再访问 View。 */
    fun render(state: OfficialNavigationUiState, sessionAvailable: Boolean) {
        if (!closed) renderer.render(state, sessionAvailable)
    }

    /** 控件发出值类型意图，页面关闭或尚未绑定时忽略，不在 UI 层归约业务状态。 */
    private fun emit(action: OfficialNavigationAction) { if (!closed) actionListener?.invoke(action) }

    /** 构造页面垂直布局及系统栏内边距，原生容器只负责放置 View，不管理导航生命周期。 */
    private fun buildContent(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(DemoTheme.PAGE_BG)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        addView(buildHeader(), LinearLayout.LayoutParams(-1, widgets.dp(60)))
        addView(View(context).apply { setBackgroundColor(DemoTheme.BORDER) }, LinearLayout.LayoutParams(-1, widgets.dp(1)))
        views.navigatorHost = FrameLayout(context).apply { setBackgroundColor(DemoTheme.MAP_PLACEHOLDER) }
        views.placeholder = TextView(context).apply {
            text = "官方导航引擎准备中"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(DemoTheme.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(widgets.dp(24), widgets.dp(18), widgets.dp(24), widgets.dp(18))
            background = DemoTheme.roundedRect(context, DemoTheme.SURFACE, DemoTheme.BORDER, 16f)
        }
        views.navigatorHost.addView(views.placeholder, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply {
            val margin = widgets.dp(24)
            setMargins(margin, margin, margin, margin)
        })
        addView(views.navigatorHost, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(controls.build())
    }

    /** 构造返回入口、厂商标题和状态徽章；返回只交给 Activity，不绕过原生页面生命周期。 */
    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(widgets.dp(12), widgets.dp(8), widgets.dp(14), widgets.dp(8))
        setBackgroundColor(DemoTheme.SURFACE)
        elevation = widgets.dp(2).toFloat()
        addView(widgets.button("返回", NavigationButtonStyle.STANDARD) {
            if (!closed) returnListener?.invoke()
        }, LinearLayout.LayoutParams(widgets.dp(60), widgets.dp(38)))
        addView(TextView(context).apply {
            text = context.getString(R.string.official_navigation_title, OfficialNavigationText.provider(provider))
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(DemoTheme.TEXT_PRIMARY)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = widgets.dp(12) })
        views.stateBadge = TextView(context).apply {
            text = "引擎初始化"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(widgets.dp(12), widgets.dp(5), widgets.dp(12), widgets.dp(5))
            setTextColor(DemoTheme.BRAND_DARK)
            background = DemoTheme.roundedRect(context, DemoTheme.BRAND_SOFT, DemoTheme.BRAND_BORDER, 999f)
            includeFontPadding = false
        }
        addView(views.stateBadge, LinearLayout.LayoutParams(-2, -2))
    }

    /** 原生会话先由 Activity close；随后断开页面回调、按钮、滑条和系统栏监听，释放 View 树。 */
    override fun close() {
        if (closed) return
        closed = true
        actionListener = null
        returnListener = null
        controls.close()
        widgets.close()
        ViewCompat.setOnApplyWindowInsetsListener(root, null)
        views.navigatorHost.removeAllViews()
    }

    /** 无有效导航参数或变体能力时的轻量错误页面，不创建原生会话或控制面板。 */
    companion object {
        /** 输入页面 Context 和错误文案，创建居中提示；不保存 Context 到全局对象。 */
        fun fatal(context: Context, message: String): View = TextView(context).apply {
            text = message
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(DemoTheme.ERROR)
            val padding = (24 * context.resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
    }
}
