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
 * 页面级 UI 宿主：组装全屏原生容器、悬浮顶栏和悬浮控制面板，连接意图与独立渲染器。
 * 可以持有页面 Context；不持有导航器，Activity 销毁时必须 close 断开所有监听。
 * @param capabilities 当前变体能力值，只用于构造控件/纯策略，不持有原生引擎。
 * @param onReturn 此页面返回入口，登记后由 close 清空，不能跨 Activity 复用。
 */
internal class OfficialNavigationPage(
    /** 本 Activity 的 UI Context，创建控件和读取尺寸/资源；随页面关闭，不放入 VM。 */
    private val context: Context,
    /** 页面标题使用的厂商枚举值，不创建或持有厂商 MapProvider。 */
    private val provider: Provider,
    capabilities: OfficialNavigationCapabilities,
    onReturn: () -> Unit,
) : AutoCloseable {
    /** 页面内控件引用表，buildContent/Controls 填充，Renderer 只更新这些已构造控件。 */
    private val views = OfficialNavigationViews()
    /** 本页小控件与按钮监听所有者，close 时解除全部按钮回调。 */
    private val widgets = OfficialNavigationWidgets(context)
    /** 会话宿主保护过的意图接收器；默认 null，未绑定/关闭时不发送操作，关闭后清空。 */
    private var actionListener: ((OfficialNavigationAction) -> Unit)? = null
    /** 当前页面返回回调；close 置 null，避免已关闭控件继续结束或引用旧 Activity。 */
    private var returnListener: (() -> Unit)? = onReturn
    /** 页面永久关闭标记，默认 false；关闭后不再渲染或发送意图。 */
    private var closed = false
    /** 本页控制面板构造器；只发意图，页面关闭时解除速度监听，不保存业务状态。 */
    private val controls = OfficialNavigationControls(context, capabilities, views, widgets, ::emit)
    /** 本页根 View，由 buildContent 完成控件装配；生命周期归 Activity 窗口，不全局缓存。 */
    val root: View = buildContent()
    /** 对外提供的原生 View 容器引用；仅交给 SessionHost 挂载 SDK View，不能交给 VM。 */
    val navigatorContainer: FrameLayout get() = views.navigatorHost
    /** 本页纯渲染入口，按 UiState/会话可用性更新控件，不订阅或读取导航器。 */
    private val renderer = OfficialNavigationRenderer(context, views, widgets, capabilities)

    /** 输入已由会话宿主封装身份/代次的页面回调，不让控件直接引用原生会话。 */
    fun bindActions(listener: (OfficialNavigationAction) -> Unit) { if (!closed) actionListener = listener }

    /** 输入状态快照和可用会话标记，委托渲染器；页面关闭后不再访问 View。 */
    fun render(state: OfficialNavigationUiState, sessionAvailable: Boolean) {
        if (!closed) renderer.render(state, sessionAvailable)
    }

    /** 控件发出值类型意图，页面关闭或尚未绑定时忽略，不在 UI 层归约业务状态。 */
    private fun emit(action: OfficialNavigationAction) { if (!closed) actionListener?.invoke(action) }

    /** 构造全屏导航容器与安全区悬浮层，原生容器只负责放置 View，不管理导航生命周期。 */
    private fun buildContent(): View = FrameLayout(context).apply {
        setBackgroundColor(DemoTheme.PAGE_BG)
        views.navigatorHost = FrameLayout(context).apply { setBackgroundColor(DemoTheme.MAP_PLACEHOLDER) }
        views.placeholder = TextView(context).apply {
            text = "官方导航引擎准备中"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(DemoTheme.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(widgets.dp(26), widgets.dp(20), widgets.dp(26), widgets.dp(20))
            background = DemoTheme.floatingCardBackground(context, DemoTheme.SURFACE, DemoTheme.BORDER, 20f)
            elevation = widgets.dp(6).toFloat()
        }
        views.navigatorHost.addView(views.placeholder, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply {
            val margin = widgets.dp(24)
            setMargins(margin, margin, margin, widgets.dp(96))
        })
        addView(views.navigatorHost, FrameLayout.LayoutParams(-1, -1))
        val overlay = FrameLayout(context).apply {
            val pad = widgets.dp(12)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left + pad, bars.top + widgets.dp(8), bars.right + pad, bars.bottom + pad)
                insets
            }
            addView(buildHeader(), FrameLayout.LayoutParams(-1, widgets.dp(56), Gravity.TOP))
            addView(controls.build(), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        addView(overlay, FrameLayout.LayoutParams(-1, -1))
    }

    /** 构造悬浮返回入口、厂商标题和胶囊状态徽章；返回只交给 Activity，不绕过原生页面生命周期。 */
    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setOnTouchListener { _, _ -> true }
        setPadding(widgets.dp(10), widgets.dp(8), widgets.dp(12), widgets.dp(8))
        background = DemoTheme.floatingCardBackground(context, DemoTheme.SURFACE_GLASS, DemoTheme.BORDER, 18f)
        elevation = widgets.dp(10).toFloat()
        addView(widgets.button("返回", NavigationButtonStyle.STANDARD) {
            if (!closed) returnListener?.invoke()
        }, LinearLayout.LayoutParams(widgets.dp(58), widgets.dp(36)))
        addView(TextView(context).apply {
            text = context.getString(R.string.official_navigation_title, OfficialNavigationText.provider(provider))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(DemoTheme.TEXT_PRIMARY)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = widgets.dp(12) })
        views.stateBadge = TextView(context).apply {
            text = "引擎初始化"
            textSize = 11.5f
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
