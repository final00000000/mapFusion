package com.mapfusion.demo.main

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mapfusion.api.capability.Provider
import com.mapfusion.demo.DemoOfficialNaviSdk
import com.mapfusion.demo.DemoTheme
import com.mapfusion.demo.R
import com.mapfusion.demo.presentation.main.DemoToolCategory
import com.mapfusion.demo.presentation.main.MainMapPhase
import com.mapfusion.demo.presentation.main.MainUiState

/** 首页屏幕：组合全屏地图底层、悬浮顶栏与悬浮工具抽屉，仅通过 UiState 渲染页面。
 *
 * @param onCategory 点击工具分类时上抛的分类意图，只切换按钮网格，不调用 SDK。
 * @param onAction 点击具体功能按钮时上抛的 MainAction，由分发器路由到功能层。
 */
internal class MainScreen(
    /** 视图上下文；只读取资源与密度，不注册任何页面回调。 */
    private val context: Context,
    /** 当前变体支持的厂商集合；顶部只显示可用按钮，避免入口指向未装载的 SDK。 */
    private val supportedProviders: Set<Provider>,
    /** 厂商切换意图；点击按钮上抛，实际会话切换由宿主完成。 */
    private val onProvider: (Provider) -> Unit,
    onCategory: (DemoToolCategory) -> Unit,
    onAction: (MainAction) -> Unit,
    /** 隐私入口意图；点击顶部“隐私”按钮时上抛。 */
    private val onPrivacy: () -> Unit
) {
    /** 小型控件工厂；只创建视图与点击绑定，不保存业务状态。 */
    private val widgets = MainWidgets(context)
    /** 是否横屏；决定工具面板停靠右侧还是悬浮于底部。 */
    private val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    /** 头部状态圆点；按当前地图阶段切换指示颜色。 */
    private val statusDot = View(context).apply {
        background = DemoTheme.roundedRect(context, DemoTheme.TEXT_MUTED, cornerDp = 99f, strokeDp = 0f)
    }
    /** 头部第二行的厂商状态文本；随地图阶段与能力数量刷新。 */
    private val providerStatus = TextView(context).apply {
        textSize = 11.5f; setTextColor(DemoTheme.TEXT_SECONDARY); maxLines = 1
        ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }
    /** 已创建的厂商切换按钮索引；用于按当前状态刷新选中样式。 */
    private val providerButtons = mutableMapOf<Provider, Button>()
    /** 分类与功能按钮面板；持有控件和渲染缓存，不拥有地图会话。 */
    private val tools = MainToolPanel(context, landscape, onCategory, onAction)
    /** 上一次渲染的地图阶段；仅在进入未启用阶段时重建占位卡片。 */
    private var renderedPhase: MainMapPhase? = null
    /** 地图挂载容器；宿主把原生地图 View 添加到这里，页面不直接创建地图。 */
    val mapContainer = FrameLayout(context).apply { setBackgroundColor(DemoTheme.MAP_PLACEHOLDER) }
    /** 页面根视图；包含地图底层与安全区悬浮层，仅构建一次。 */
    val root: View = buildContent()

    /** 构造全屏地图底层与悬浮 UI 层，并将系统窗口安全边距作用于悬浮层。 */
    private fun buildContent(): View = FrameLayout(context).apply {
        setBackgroundColor(DemoTheme.PAGE_BG)
        addView(mapContainer, FrameLayout.LayoutParams(-1, -1))
        val overlay = FrameLayout(context).apply {
            val pad = widgets.dp(12)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left + pad, bars.top + widgets.dp(8), bars.right + pad, bars.bottom + pad)
                insets
            }
            addView(buildHeader(), FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            if (landscape) {
                val width = minOf(widgets.dp(376), (context.resources.displayMetrics.widthPixels * 0.46f).toInt())
                addView(tools.root, FrameLayout.LayoutParams(width, -2, Gravity.BOTTOM or Gravity.END).apply {
                    topMargin = widgets.dp(66)
                })
            } else {
                addView(tools.root, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            }
        }
        addView(overlay, FrameLayout.LayoutParams(-1, -1))
    }

    /** 创建悬浮胶囊顶栏，包含品牌徽标、状态圆点、厂商切换分段与隐私按钮。 */
    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setOnTouchListener { _, _ -> true }
        setPadding(widgets.dp(12), widgets.dp(9), widgets.dp(12), widgets.dp(9))
        background = DemoTheme.floatingCardBackground(context, DemoTheme.SURFACE_GLASS, DemoTheme.BORDER, 18f)
        elevation = widgets.dp(10).toFloat()
        addView(DemoTheme.buildBrandBadge(context), LinearLayout.LayoutParams(widgets.dp(38), widgets.dp(38)).apply {
            marginEnd = widgets.dp(10)
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = context.getString(R.string.brand_name); textSize = 16.5f
                typeface = Typeface.DEFAULT_BOLD; setTextColor(DemoTheme.TEXT_PRIMARY); includeFontPadding = false
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(statusDot, LinearLayout.LayoutParams(widgets.dp(6), widgets.dp(6)).apply {
                    marginEnd = widgets.dp(5)
                })
                addView(providerStatus, LinearLayout.LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = widgets.dp(2) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(widgets.dp(3), widgets.dp(3), widgets.dp(3), widgets.dp(3))
            background = DemoTheme.roundedRect(context, DemoTheme.SURFACE_SUBTLE, DemoTheme.BORDER, 12f)
            Provider.entries.filter(supportedProviders::contains).forEachIndexed { index, provider ->
                val button = widgets.button(provider.displayName()) { onProvider(provider) }
                providerButtons[provider] = button
                addView(button, LinearLayout.LayoutParams(widgets.dp(54), widgets.dp(32)).apply {
                    if (index > 0) marginStart = widgets.dp(3)
                })
            }
        })
        addView(widgets.button("隐私", onPrivacy).apply {
            setTextColor(DemoTheme.TEXT_SECONDARY)
            background = DemoTheme.interactiveSurface(context, DemoTheme.SURFACE_SUBTLE, DemoTheme.SURFACE_PRESSED, DemoTheme.BORDER, 12f)
        }, LinearLayout.LayoutParams(widgets.dp(54), widgets.dp(38)).apply {
            marginStart = widgets.dp(8)
        })
    }

    /** 从同一状态渲染结果、厂商就绪状态及工具能力；不反向写入 ViewModel。 */
    fun render(state: MainUiState) {
        if (state.mapPhase == MainMapPhase.DISABLED && renderedPhase != state.mapPhase) showDisabledMap()
        renderedPhase = state.mapPhase
        val dotColor = when (state.mapPhase) {
            MainMapPhase.DISABLED -> DemoTheme.TEXT_MUTED
            MainMapPhase.INITIALIZING -> DemoTheme.WARNING
            MainMapPhase.READY -> DemoTheme.SUCCESS
            MainMapPhase.FAILED -> DemoTheme.ERROR
        }
        statusDot.background = DemoTheme.roundedRect(context, dotColor, cornerDp = 99f, strokeDp = 0f)
        providerStatus.text = when (state.mapPhase) {
            MainMapPhase.DISABLED -> "地图服务未启用"
            MainMapPhase.INITIALIZING -> context.getString(R.string.provider_initializing, state.preferredProvider.displayName())
            MainMapPhase.READY -> context.getString(R.string.provider_capability_summary,
                state.preferredProvider.displayName(), state.capabilityCount)
            MainMapPhase.FAILED -> context.getString(R.string.provider_initialization_failed, state.preferredProvider.displayName())
        }
        providerButtons.forEach { (provider, button) ->
            widgets.styleSegment(button, state.mapPhase == MainMapPhase.READY && state.preferredProvider == provider)
        }
        tools.renderStatus(state.status)
        tools.renderCategory(state.toolCategory, DemoOfficialNaviSdk.supportsOfficialNavigation(state.preferredProvider))
    }

    /** 未获隐私同意时只展示本地占位卡片，不创建原生地图或申请定位权限。 */
    private fun showDisabledMap() {
        mapContainer.removeAllViews()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(widgets.dp(28), widgets.dp(24), widgets.dp(28), widgets.dp(24))
            background = DemoTheme.floatingCardBackground(context, DemoTheme.SURFACE, DemoTheme.BORDER, 22f)
            elevation = widgets.dp(6).toFloat()
            addView(FrameLayout(context).apply {
                background = DemoTheme.roundedRect(context, DemoTheme.BRAND_SOFT, DemoTheme.BRAND_BORDER, 99f)
                addView(TextView(context).apply {
                    text = "隐私保护门禁"; textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(DemoTheme.BRAND_DARK); gravity = Gravity.CENTER
                    setPadding(widgets.dp(12), widgets.dp(4), widgets.dp(12), widgets.dp(4))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
            }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = widgets.dp(12) })
            addView(TextView(context).apply {
                text = context.getString(R.string.map_service_disabled); textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setLineSpacing(widgets.dp(5).toFloat(), 1f); setTextColor(DemoTheme.TEXT_PRIMARY)
            })
        }
        mapContainer.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(widgets.dp(28), widgets.dp(24), widgets.dp(28), widgets.dp(88))
        })
    }
}
