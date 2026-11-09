package com.mapfusion.demo.main

import android.content.Context
import android.content.res.Configuration
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

/** 首页屏幕：组合地图容器、头部与工具面板，仅通过 UiState 渲染页面。
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
    /** 是否横屏；决定地图与工具面板的左右分栏还是上下布局。 */
    private val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
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
    /** 页面根视图；包含头部、地图与工具面板，仅构建一次。 */
    val root: View = buildContent()

    /** 构造横竖屏布局并应用系统窗口边距，不访问任何地图 SDK。 */
    private fun buildContent(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(DemoTheme.PAGE_BG)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        addView(buildHeader())
        addView(View(context).apply { setBackgroundColor(DemoTheme.BORDER) }, LinearLayout.LayoutParams(-1, widgets.dp(1)))
        if (landscape) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(mapContainer, LinearLayout.LayoutParams(0, -1, 1f))
                addView(View(context).apply { setBackgroundColor(DemoTheme.BORDER) }, LinearLayout.LayoutParams(widgets.dp(1), -1))
                addView(tools.root, LinearLayout.LayoutParams(minOf(widgets.dp(372),
                    (context.resources.displayMetrics.widthPixels * 0.46f).toInt()), -1))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            addView(mapContainer, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(tools.root)
        }
    }

    /** 创建品牌、厂商切换与隐私入口；按钮只触发宿主提供的用户意图。 */
    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(widgets.dp(14), widgets.dp(10), widgets.dp(14), widgets.dp(10))
        setBackgroundColor(DemoTheme.SURFACE); elevation = widgets.dp(2).toFloat()
        addView(DemoTheme.buildBrandBadge(context), LinearLayout.LayoutParams(widgets.dp(36), widgets.dp(36)).apply {
            marginEnd = widgets.dp(10)
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = context.getString(R.string.brand_name); textSize = 17.5f
                typeface = Typeface.DEFAULT_BOLD; setTextColor(DemoTheme.TEXT_PRIMARY); includeFontPadding = false
            })
            addView(providerStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = widgets.dp(2) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(widgets.dp(3), widgets.dp(3), widgets.dp(3), widgets.dp(3))
            background = DemoTheme.roundedRect(context, DemoTheme.SURFACE_SUBTLE, DemoTheme.BORDER, 11f)
            Provider.entries.filter(supportedProviders::contains).forEachIndexed { index, provider ->
                val button = widgets.button(provider.displayName()) { onProvider(provider) }
                providerButtons[provider] = button
                addView(button, LinearLayout.LayoutParams(widgets.dp(54), widgets.dp(32)).apply {
                    if (index > 0) marginStart = widgets.dp(3)
                })
            }
        })
        addView(widgets.button("隐私", onPrivacy), LinearLayout.LayoutParams(widgets.dp(56), widgets.dp(36)).apply {
            marginStart = widgets.dp(8)
        })
    }

    /** 从同一状态渲染结果、厂商就绪状态及工具能力；不反向写入 ViewModel。 */
    fun render(state: MainUiState) {
        if (state.mapPhase == MainMapPhase.DISABLED && renderedPhase != state.mapPhase) showDisabledMap()
        renderedPhase = state.mapPhase
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

    /** 未获隐私同意时只展示本地占位说明，不创建原生地图或申请定位权限。 */
    private fun showDisabledMap() {
        mapContainer.removeAllViews()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(widgets.dp(24), widgets.dp(22), widgets.dp(24), widgets.dp(22))
            background = DemoTheme.roundedRect(context, DemoTheme.SURFACE, DemoTheme.BORDER, 16f)
            addView(TextView(context).apply {
                text = context.getString(R.string.map_service_disabled); textSize = 15f
                typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setLineSpacing(widgets.dp(4).toFloat(), 1f); setTextColor(DemoTheme.TEXT_SECONDARY)
            })
        }
        mapContainer.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(widgets.dp(24), widgets.dp(24), widgets.dp(24), widgets.dp(24))
        })
    }
}
