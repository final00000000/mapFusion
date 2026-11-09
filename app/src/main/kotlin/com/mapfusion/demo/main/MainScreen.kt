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

/** 首页屏幕：组合地图容器、头部与工具面板，仅通过 UiState 渲染页面。 */
internal class MainScreen(private val context: Context, private val supportedProviders: Set<Provider>,
    private val onProvider: (Provider) -> Unit, onCategory: (DemoToolCategory) -> Unit,
    onAction: (MainAction) -> Unit, private val onPrivacy: () -> Unit) {
    private val widgets = MainWidgets(context)
    private val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val providerStatus = TextView(context).apply {
        textSize = 11.5f; setTextColor(DemoTheme.TEXT_SECONDARY); maxLines = 1
        ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }
    private val providerButtons = mutableMapOf<Provider, Button>()
    private val tools = MainToolPanel(context, landscape, onCategory, onAction)
    private var renderedPhase: MainMapPhase? = null
    val mapContainer = FrameLayout(context).apply { setBackgroundColor(DemoTheme.MAP_PLACEHOLDER) }
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
