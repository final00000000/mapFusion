package com.mapfusion.demo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mapfusion.api.capability.OfficialNavigator
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.OfficialNavigationCapabilities
import com.mapfusion.api.model.OfficialNavigationDayNightMode
import com.mapfusion.api.model.OfficialNavigationEvent
import com.mapfusion.api.model.OfficialNavigationFeature
import com.mapfusion.api.model.OfficialNavigationMode
import com.mapfusion.api.model.OfficialNavigationOptions
import com.mapfusion.api.model.OfficialNavigationProgress
import com.mapfusion.api.model.OfficialNavigationRequest
import com.mapfusion.api.model.OfficialNavigationState
import com.mapfusion.api.model.OfficialRouteSummary
import com.mapfusion.api.model.RouteRequest
import com.mapfusion.api.model.TravelMode
import com.mapfusion.demo.data.DemoConsentStore
import com.mapfusion.demo.presentation.navigation.NaviTone
import com.mapfusion.demo.presentation.navigation.OfficialNavigationUiState
import com.mapfusion.demo.presentation.navigation.OfficialNavigationViewModel
import kotlinx.coroutines.launch
import java.util.Locale

/** 仅使用 Map Fusion 统一契约承载两家厂商的官方 App 内导航 View。 */
class OfficialNavigationActivity : ComponentActivity() {
    private lateinit var navigatorHost: FrameLayout
    private lateinit var placeholderView: TextView
    private lateinit var providerView: TextView
    private lateinit var stateView: TextView
    private lateinit var detailView: TextView
    private lateinit var speedRow: LinearLayout
    private lateinit var speedValueView: TextView
    private lateinit var speedSeekBar: SeekBar
    private lateinit var startButton: Button
    private lateinit var pauseResumeButton: Button
    private lateinit var stopButton: Button
    private lateinit var routeButton: Button
    private var rerouteButton: Button? = null
    private var overviewButton: Button? = null
    private var followButton: Button? = null
    private var voiceButton: Button? = null
    private var dayNightButton: Button? = null

    private val modeButtons = linkedMapOf<OfficialNavigationMode, Button>()
    private val travelModeButtons = linkedMapOf<TravelMode, Button>()
    private val viewModel: OfficialNavigationViewModel by viewModels()
    private val consentStore by lazy { DemoConsentStore(this) }
    private var consentGranted = false
    private var sessionEpoch = 0
    private var provider: Provider? = null
    private var capabilities: OfficialNavigationCapabilities? = null
    private var navigator: OfficialNavigator? = null
    private var origin = DEFAULT_ORIGIN
    private var destination = DEFAULT_DESTINATION

    // 旧属性的薄代理：保持既有调用点，同时让唯一可变状态归 ViewModel 所有。
    private var navigationState: OfficialNavigationState
        get() = viewModel.state.value.navigationState
        set(value) { viewModel.update { it.copy(navigationState = value) } }
    private var selectedNavigationMode: OfficialNavigationMode
        get() = viewModel.state.value.navigationMode
        set(value) { viewModel.selectNavigationMode(value) }
    private var selectedTravelMode: TravelMode
        get() = viewModel.state.value.travelMode
        set(value) { viewModel.selectTravelMode(value) }
    private var simulationSpeed: Int
        get() = viewModel.state.value.simulationSpeed
        set(value) { viewModel.setSimulationSpeed(value) }
    private var voiceGuidanceEnabled: Boolean
        get() = viewModel.state.value.voiceGuidanceEnabled
        set(value) { viewModel.setVoiceGuidanceEnabled(value) }
    private var dayNightMode: OfficialNavigationDayNightMode
        get() = viewModel.state.value.dayNightMode
        set(value) { viewModel.setDayNightMode(value) }
    private var routeSummaries: List<OfficialRouteSummary>
        get() = viewModel.state.value.routeSummaries
        set(value) { viewModel.update { it.copy(routeSummaries = value) } }
    private var selectedRouteIndex: Int
        get() = viewModel.state.value.selectedRouteIndex
        set(value) { viewModel.update { it.copy(selectedRouteIndex = value) } }
    private var startRequestPending: Boolean
        get() = viewModel.state.value.startRequestPending
        set(value) { viewModel.update { it.copy(startRequestPending = value) } }
    private var navigationStarted: Boolean
        get() = viewModel.state.value.navigationStarted
        set(value) { viewModel.update { it.copy(navigationStarted = value) } }
    private var pendingRealNavigationStart: Boolean
        get() = viewModel.state.value.pendingRealNavigationStart
        set(value) { viewModel.update { it.copy(pendingRealNavigationStart = value) } }

    /**
     * Vendor navigation engines initialize asynchronously and may finish before
     * [OfficialNavigator.start] installs the event listener. Keep the demo state
     * in sync during that short window so the controls do not remain stuck on
     * “引擎初始化”.
     */
    private val statePollHandler = Handler(Looper.getMainLooper())
    private val statePollRunnable = object : Runnable {
        override fun run() {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            syncNavigatorState()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                !isFinishing && !isDestroyed && consentGranted &&
                navigator != null && navigationState == OfficialNavigationState.INITIALIZING
            ) {
                statePollHandler.postDelayed(this, STATE_POLL_INTERVAL_MS)
            }
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (pendingRealNavigationStart) {
            pendingRealNavigationStart = false
            if (hasFineLocationPermission()) {
                startNavigationNow()
            } else {
                showError("真实导航需要精确位置权限；可切换到模拟导航继续体验")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (navigator?.onBackPressed() != true) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
        provider = intent.getStringExtra(EXTRA_PROVIDER)
            ?.let { value -> runCatching { Provider.valueOf(value) }.getOrNull() }
        origin = intent.readPoint(EXTRA_ORIGIN_PREFIX) ?: DEFAULT_ORIGIN
        destination = intent.readPoint(EXTRA_DESTINATION_PREFIX) ?: DEFAULT_DESTINATION

        val selectedProvider = provider
        if (selectedProvider == null) {
            setContentView(fatalView("缺少官方导航厂商参数"))
            return
        }
        val selectedCapabilities = DemoOfficialNaviSdk.capabilities(selectedProvider)
        if (selectedCapabilities == null) {
            setContentView(fatalView("当前 Demo 变体未装载${selectedProvider.displayName()}官方导航"))
            return
        }
        capabilities = selectedCapabilities
        consentGranted = consentStore.hasConsent()
        viewModel.configureCapabilities(selectedCapabilities.modes, selectedCapabilities.travelModes)
        viewModel.resetRuntimeSession()

        setContentView(buildContentView())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
        createNavigator(savedInstanceState?.getBundle(STATE_NAVIGATOR))
    }

    private fun createNavigator(savedState: Bundle?) {
        val selectedProvider = provider ?: return
        if (!refreshConsent()) {
            viewModel.blockForConsent("地图隐私同意已失效，请返回首页重新确认")
            renderState()
            return
        }
        when (val privacy = DemoOfficialNaviSdk.updatePrivacyConsent(this, selectedProvider, true)) {
            is MapResult.Failure -> {
                showMapError("厂商隐私状态同步失败", privacy.error)
                disableNavigationControls()
                return
            }
            is MapResult.Success -> Unit
        }
        when (val result = DemoOfficialNaviSdk.createNavigator(this, selectedProvider, savedState)) {
            is MapResult.Success -> {
                sessionEpoch += 1
                navigator = result.data
                navigationState = result.data.state
                if (result.data.capabilities.supports(OfficialNavigationFeature.VOICE_CONTROL)) {
                    result.data.setVoiceGuidanceEnabled(voiceGuidanceEnabled)
                }
                if (result.data.capabilities.supports(OfficialNavigationFeature.DAY_NIGHT_MODE)) {
                    result.data.setDayNightMode(dayNightMode)
                }
                val view = result.data.view
                (view.parent as? ViewGroup)?.removeView(view)
                navigatorHost.addView(
                    view,
                    0,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                syncNavigatorState()
                scheduleNavigatorStatePolling()
                updateControls()
            }
            is MapResult.Failure -> {
                showMapError("官方导航引擎初始化失败", result.error)
                disableNavigationControls()
            }
        }
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DemoTheme.PAGE_BG)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        root.addView(buildHeader(), fullWidthHeight(60))
        root.addView(divider(), fullWidthHeight(1))

        navigatorHost = FrameLayout(this).apply {
            setBackgroundColor(DemoTheme.MAP_PLACEHOLDER)
        }
        placeholderView = TextView(this).apply {
            text = "官方导航引擎准备中"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(DemoTheme.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(18), dp(24), dp(18))
            background = DemoTheme.roundedRect(
                context = this@OfficialNavigationActivity,
                fillColor = DemoTheme.SURFACE,
                strokeColor = DemoTheme.BORDER,
                cornerDp = 16f,
            )
        }
        navigatorHost.addView(
            placeholderView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                setMargins(dp(24), dp(24), dp(24), dp(24))
            },
        )
        root.addView(
            navigatorHost,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(buildControlPanel())
        return root
    }

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(14), dp(8))
        setBackgroundColor(DemoTheme.SURFACE)
        elevation = dp(2).toFloat()
        addView(
            compactButton("返回") { stopAndFinish() }.apply {
                typeface = Typeface.DEFAULT_BOLD
                background = DemoTheme.interactiveSurface(
                    context = this@OfficialNavigationActivity,
                    fillColor = DemoTheme.SURFACE_ELEVATED,
                    pressedColor = DemoTheme.SURFACE_PRESSED,
                    strokeColor = DemoTheme.BORDER_STRONG,
                    cornerDp = 10f,
                )
            },
            LinearLayout.LayoutParams(dp(60), dp(38)),
        )
        addView(
            LinearLayout(this@OfficialNavigationActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                providerView = TextView(this@OfficialNavigationActivity).apply {
                    text = getString(R.string.official_navigation_title, provider.displayName())
                    textSize = 16.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(DemoTheme.TEXT_PRIMARY)
                    includeFontPadding = false
                }
                addView(providerView)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        stateView = TextView(this@OfficialNavigationActivity).apply {
            text = navigationState.displayName()
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setTextColor(DemoTheme.BRAND_DARK)
            background = DemoTheme.roundedRect(
                context = this@OfficialNavigationActivity,
                fillColor = DemoTheme.BRAND_SOFT,
                strokeColor = DemoTheme.BRAND_BORDER,
                cornerDp = 999f,
            )
            includeFontPadding = false
        }
        addView(
            stateView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun buildControlPanel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(12))
        background = DemoTheme.topSheetBackground(
            context = this@OfficialNavigationActivity,
            fillColor = DemoTheme.SURFACE,
            strokeColor = DemoTheme.BORDER,
            cornerDp = 20f,
        )
        elevation = dp(8).toFloat()

        addView(
            DemoTheme.buildSheetHandle(this@OfficialNavigationActivity),
            LinearLayout.LayoutParams(-1, dp(10)).apply { bottomMargin = dp(4) },
        )

        detailView = TextView(this@OfficialNavigationActivity).apply {
            text = "等待开始"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(DemoTheme.TEXT_PRIMARY)
            maxLines = 2
            minHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = DemoTheme.roundedRect(
                context = this@OfficialNavigationActivity,
                fillColor = StatusTone.INFO.cardBgColor,
                strokeColor = StatusTone.INFO.cardBorderColor,
                cornerDp = 11f,
            )
            includeFontPadding = false
        }
        addView(detailView, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(4)
        })

        addView(buildModeRow(), LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(4) })
        addView(buildTravelModeRow(), LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(6) })

        speedRow = buildSpeedRow()
        addView(speedRow, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(4) })

        buildAdvancedControlRow()?.let { row ->
            addView(row, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(6) })
        }

        addView(buildActionRow(), LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(10) })
    }

    private fun buildModeRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(controlLabel("模式"), LinearLayout.LayoutParams(dp(44), -1))
        val container = LinearLayout(this@OfficialNavigationActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = DemoTheme.roundedRect(
                context = this@OfficialNavigationActivity,
                fillColor = DemoTheme.SURFACE_SUBTLE,
                strokeColor = DemoTheme.BORDER,
                cornerDp = 11f,
            )
            OfficialNavigationMode.entries
                .filter { capabilities?.supports(it) == true }
                .forEachIndexed { index, mode ->
                    val button = compactButton(mode.displayName()) {
                        selectedNavigationMode = mode
                        updateControls()
                    }
                    modeButtons[mode] = button
                    addView(
                        button,
                        LinearLayout.LayoutParams(0, -1, 1f).apply {
                            if (index > 0) marginStart = dp(4)
                        },
                    )
                }
        }
        addView(container, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun buildTravelModeRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(controlLabel("出行"), LinearLayout.LayoutParams(dp(44), -1))
        val modes = orderedTravelModes()
        if (modes.size <= 4) {
            val row = LinearLayout(this@OfficialNavigationActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                modes.forEachIndexed { index, mode ->
                    val button = compactButton(mode.displayName()) {
                        selectedTravelMode = mode
                        updateControls()
                    }
                    travelModeButtons[mode] = button
                    addView(
                        button,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                            if (index > 0) marginStart = dp(5)
                        },
                    )
                }
            }
            addView(row, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            addView(
                HorizontalScrollView(this@OfficialNavigationActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(
                        LinearLayout(this@OfficialNavigationActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            modes.forEachIndexed { index, mode ->
                                val button = compactButton(mode.displayName()) {
                                    selectedTravelMode = mode
                                    updateControls()
                                }
                                travelModeButtons[mode] = button
                                addView(
                                    button,
                                    LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                                        if (index > 0) marginStart = dp(5)
                                    },
                                )
                            }
                        },
                        ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT),
                    )
                },
                LinearLayout.LayoutParams(0, -1, 1f),
            )
        }
    }

    private fun buildSpeedRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(controlLabel("速度"), LinearLayout.LayoutParams(dp(44), -1))
        addView(
            SeekBar(this@OfficialNavigationActivity).apply {
                speedSeekBar = this
                max = MAX_SIMULATION_SPEED - MIN_SIMULATION_SPEED
                progress = simulationSpeed - MIN_SIMULATION_SPEED
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        simulationSpeed = MIN_SIMULATION_SPEED + progress
                        speedValueView.text = getString(R.string.official_navigation_speed, simulationSpeed)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        if (navigationState in ACTIVE_STATES &&
                            selectedNavigationMode == OfficialNavigationMode.SIMULATED &&
                            capabilities?.supports(OfficialNavigationFeature.RUNTIME_SIMULATION_SPEED) == true
                        ) {
                            applySimulationSpeed()
                        }
                    }
                })
            },
            LinearLayout.LayoutParams(0, -1, 1f),
        )
        speedValueView = TextView(this@OfficialNavigationActivity).apply {
            text = getString(R.string.official_navigation_speed, simulationSpeed)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(DemoTheme.BRAND_DARK)
        }
        addView(speedValueView, LinearLayout.LayoutParams(dp(68), -1))
    }

    private fun buildAdvancedControlRow(): View? {
        val supported = capabilities?.features.orEmpty()
        if (supported.none { it in ADVANCED_CONTROL_FEATURES }) return null
        val controls = buildList {
            if (OfficialNavigationFeature.ROUTE_OVERVIEW in supported) {
                add(compactButton("全览", ::showRouteOverview).also { overviewButton = it })
            }
            if (OfficialNavigationFeature.RESTORE_FOLLOWING in supported) {
                add(compactButton("跟随", ::resumeFollowing).also { followButton = it })
            }
            if (OfficialNavigationFeature.MANUAL_REROUTE in supported) {
                add(compactButton("重算", ::recalculateRoute).also { rerouteButton = it })
            }
            if (OfficialNavigationFeature.VOICE_CONTROL in supported) {
                add(compactButton("语音开", ::toggleVoiceGuidance).also { voiceButton = it })
            }
            if (OfficialNavigationFeature.DAY_NIGHT_MODE in supported) {
                add(compactButton("自动色", ::cycleDayNightMode).also { dayNightButton = it })
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(controlLabel("控制"), LinearLayout.LayoutParams(dp(44), -1))
            if (controls.size <= 4) {
                val row = LinearLayout(this@OfficialNavigationActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    controls.forEachIndexed { index, button ->
                        addView(
                            button,
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                                if (index > 0) marginStart = dp(5)
                            },
                        )
                    }
                }
                addView(row, LinearLayout.LayoutParams(0, -1, 1f))
            } else {
                addView(
                    HorizontalScrollView(this@OfficialNavigationActivity).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(
                            LinearLayout(this@OfficialNavigationActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                controls.forEachIndexed { index, button ->
                                    addView(
                                        button,
                                        LinearLayout.LayoutParams(dp(66), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                                            if (index > 0) marginStart = dp(5)
                                        },
                                    )
                                }
                            },
                            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT),
                        )
                    },
                    LinearLayout.LayoutParams(0, -1, 1f),
                )
            }
        }
    }

    private fun buildActionRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        startButton = primaryActionButton("开始", ::startNavigation)
        pauseResumeButton = actionButton("暂停", ::pauseOrResumeNavigation)
        routeButton = actionButton("路线", ::selectNextRoute)
        stopButton = dangerActionButton("停止", ::stopNavigation)
        listOf(startButton, pauseResumeButton, routeButton, stopButton).forEachIndexed { index, button ->
            addView(
                button,
                LinearLayout.LayoutParams(0, -1, 1f).apply {
                    if (index > 0) marginStart = dp(6)
                },
            )
        }
    }

    private fun startNavigation() {
        if (!refreshConsent()) {
            viewModel.blockForConsent("地图隐私同意已失效，请返回首页重新确认")
            renderState()
            return
        }
        if (selectedNavigationMode == OfficialNavigationMode.REAL && !hasFineLocationPermission()) {
            pendingRealNavigationStart = true
            showDetail("等待精确位置权限", NaviTone.INFO)
            locationPermissionLauncher.launch(LOCATION_PERMISSIONS)
            return
        }
        startNavigationNow()
    }

    private fun startNavigationNow() {
        val session = navigator ?: return
        if (!refreshConsent()) {
            viewModel.blockForConsent("地图隐私同意已失效，请返回首页重新确认")
            renderState()
            return
        }
        if (startRequestPending || navigationState in ACTIVE_STATES) return
        viewModel.prepareStart()
        val startEpoch = sessionEpoch
        val result = session.start(
            OfficialNavigationRequest(
                routeRequest = RouteRequest(
                    mode = selectedTravelMode,
                    origin = origin,
                    destination = destination,
                ),
                options = OfficialNavigationOptions(
                    navigationMode = selectedNavigationMode,
                    simulationSpeedKilometersPerHour = simulationSpeed,
                ),
            ),
        ) { event ->
            runOnUiThread {
                if (startEpoch != sessionEpoch ||
                    navigator !== session ||
                    isFinishing ||
                    isDestroyed
                ) {
                    return@runOnUiThread
                }
                if (!refreshConsent()) {
                    viewModel.blockForConsent("地图隐私同意已失效，请返回首页重新确认")
                    renderState()
                    return@runOnUiThread
                }
                handleNavigationEvent(event)
            }
        }
        when (result) {
            is MapResult.Success -> {
                viewModel.markStartSucceeded(session.state)
                showDetail(
                    getString(
                        R.string.official_navigation_requesting,
                        selectedTravelMode.displayName(),
                    ),
                    NaviTone.INFO,
                )
            }
            is MapResult.Failure -> {
                viewModel.markStartFailed()
                showMapError("导航启动失败", result.error)
            }
        }
    }

    private fun pauseOrResumeNavigation() {
        val session = navigator ?: return
        val result = if (navigationState == OfficialNavigationState.PAUSED) session.resume() else session.pause()
        when (result) {
            is MapResult.Success -> {
                viewModel.update { it.copy(startRequestPending = false, navigationState = session.state) }
                showDetail(
                    if (navigationState == OfficialNavigationState.PAUSED) "导航已暂停" else "导航已继续",
                    NaviTone.INFO,
                )
            }
            is MapResult.Failure -> showMapError("导航控制失败", result.error)
        }
    }

    private fun stopNavigation() {
        val session = navigator ?: return
        when (val result = session.stop()) {
            is MapResult.Success -> {
                viewModel.update { it.copy(startRequestPending = false, navigationState = session.state) }
                finish()
            }
            is MapResult.Failure -> showMapError("停止导航失败", result.error)
        }
    }

    private fun selectNextRoute() {
        if (routeSummaries.size < 2) return
        val next = (selectedRouteIndex + 1) % routeSummaries.size
        when (val result = navigator?.selectRoute(next)) {
            is MapResult.Success -> {
                viewModel.selectRoute(next)
                showSelectedRoute()
                renderState()
            }
            is MapResult.Failure -> showMapError("路线切换失败", result.error)
            null -> Unit
        }
    }

    private fun recalculateRoute() {
        val result = navigator?.recalculateRoute() ?: return
        handleControlResult("正在重新规划路线", "路线重新规划失败", result)
    }

    private fun showRouteOverview() {
        val result = navigator?.showRouteOverview() ?: return
        handleControlResult("已显示路线全览", "路线全览失败", result)
    }

    private fun resumeFollowing() {
        val result = navigator?.resumeFollowing() ?: return
        handleControlResult("已恢复车标跟随", "恢复跟随失败", result)
    }

    private fun applySimulationSpeed() {
        val result = navigator?.setSimulationSpeed(simulationSpeed) ?: return
        handleControlResult("模拟速度已调整为 $simulationSpeed km/h", "模拟速度调整失败", result)
    }

    private fun toggleVoiceGuidance() {
        val enabled = !voiceGuidanceEnabled
        when (val result = navigator?.setVoiceGuidanceEnabled(enabled)) {
            is MapResult.Success -> {
                voiceGuidanceEnabled = enabled
                showDetail(if (enabled) "语音引导已开启" else "语音引导已关闭", NaviTone.INFO)
            }
            is MapResult.Failure -> showMapError("语音设置失败", result.error)
            null -> Unit
        }
    }

    private fun cycleDayNightMode() {
        val next = when (dayNightMode) {
            OfficialNavigationDayNightMode.AUTO -> OfficialNavigationDayNightMode.DAY
            OfficialNavigationDayNightMode.DAY -> OfficialNavigationDayNightMode.NIGHT
            OfficialNavigationDayNightMode.NIGHT -> OfficialNavigationDayNightMode.AUTO
        }
        when (val result = navigator?.setDayNightMode(next)) {
            is MapResult.Success -> {
                dayNightMode = next
                showDetail("导航配色：${next.displayName()}", NaviTone.INFO)
            }
            is MapResult.Failure -> showMapError("导航配色设置失败", result.error)
            null -> Unit
        }
    }

    private fun handleControlResult(successMessage: String, errorTitle: String, result: MapResult<Unit>) {
        when (result) {
            is MapResult.Success -> showDetail(successMessage, NaviTone.INFO)
            is MapResult.Failure -> showMapError(errorTitle, result.error)
        }
        renderState()
    }

    private fun handleNavigationEvent(event: OfficialNavigationEvent) {
        when (event) {
            is OfficialNavigationEvent.StateChanged -> viewModel.onStateChanged(event.state)
            is OfficialNavigationEvent.RoutesReady -> {
                viewModel.onRoutesReady(event.routes, event.selectedRouteIndex)
                showSelectedRoute()
            }
            is OfficialNavigationEvent.Progress -> showProgress(event.value)
            is OfficialNavigationEvent.VoicePrompt -> showDetail(event.text, NaviTone.INFO)
            is OfficialNavigationEvent.LaneGuidance -> {
                if (event.visible) {
                    showDetail(
                        getString(R.string.official_navigation_lane_guidance, event.laneCount),
                        NaviTone.INFO,
                    )
                }
            }
            is OfficialNavigationEvent.JunctionView -> {
                if (event.visible) showDetail("前方路口放大图", NaviTone.INFO)
            }
            is OfficialNavigationEvent.CameraAlerts -> event.cameras.firstOrNull()?.let { camera ->
                showDetail(
                    getString(
                        R.string.official_navigation_camera_alert,
                        formatDistance(camera.distanceMeters),
                    ),
                    NaviTone.INFO,
                )
            }
            is OfficialNavigationEvent.IntervalCameraAlert -> {
                val distance = event.camera.remainingDistanceMeters
                    ?: event.camera.endDistanceMeters
                    ?: event.camera.startDistanceMeters
                showDetail(
                    if (distance != null) {
                        "区间测速，剩余 ${formatDistance(distance)}"
                    } else {
                        "进入区间测速路段"
                    },
                    NaviTone.INFO,
                )
            }
            is OfficialNavigationEvent.ServiceAreas -> event.areas.firstOrNull()?.let { area ->
                val name = area.name ?: "前方服务区"
                showDetail("$name ${formatDistance(area.remainingDistanceMeters)}", NaviTone.INFO)
            }
            is OfficialNavigationEvent.TrafficStatus -> {
                if (event.segments.isNotEmpty()) {
                    showDetail("路况已更新：${event.segments.size} 段", NaviTone.INFO)
                }
            }
            is OfficialNavigationEvent.SpeedLimit -> {
                showDetail(
                    event.limit?.let {
                        getString(R.string.official_navigation_speed_limit, event.current, it)
                    } ?: getString(R.string.official_navigation_current_speed, event.current),
                    NaviTone.INFO,
                )
            }
            is OfficialNavigationEvent.GpsStatus -> {
                showDetail(
                    when {
                        event.enabled == false -> "GPS 未开启"
                        event.signalWeak -> "GPS 信号较弱"
                        else -> "GPS 信号正常"
                    },
                    NaviTone.INFO,
                )
            }
            is OfficialNavigationEvent.WaypointArrived -> {
                showDetail("已到达第 ${event.waypointIndex + 1} 个途经点", NaviTone.INFO)
            }
            is OfficialNavigationEvent.WaypointProgress -> event.waypoints.firstOrNull()?.let { waypoint ->
                showDetail(
                    "途经点 ${waypoint.waypointIndex + 1}，剩余 ${formatDistance(waypoint.remainingDistanceMeters)}",
                    NaviTone.INFO,
                )
            }
            is OfficialNavigationEvent.HighwayGuidance -> {
                val guidance = event.value
                val labels = listOfNotNull(
                    guidance.highwayName,
                    guidance.direction,
                    guidance.exitName,
                    guidance.tollGateName,
                )
                showDetail(
                    if (guidance.visible && labels.isNotEmpty()) {
                        labels.joinToString(" · ")
                    } else {
                        "高速引导已更新"
                    },
                    NaviTone.INFO,
                )
            }
            is OfficialNavigationEvent.RouteNotice -> {
                showDetail(
                    event.value.message
                        ?: event.value.subTitle
                        ?: event.value.roadName
                        ?: "路线提醒已更新",
                    NaviTone.INFO,
                )
            }
            OfficialNavigationEvent.Rerouting -> showDetail("偏航，正在重新规划路线", NaviTone.INFO)
            is OfficialNavigationEvent.RerouteCompleted -> {
                showDetail(
                    if (event.success) {
                        "路线重新规划完成"
                    } else {
                        "路线重新规划失败：${event.error?.message ?: "未知错误"}"
                    },
                    NaviTone.INFO,
                )
            }
            OfficialNavigationEvent.Arrived -> {
                viewModel.onArrived()
                showDetail("已到达目的地", NaviTone.INFO)
            }
            is OfficialNavigationEvent.Error -> showMapError("官方导航错误", event.error)
        }
        renderState()
    }

    private fun showSelectedRoute() {
        val route = routeSummaries.getOrNull(selectedRouteIndex) ?: return
        showDetail(
            getString(
                R.string.official_navigation_route_summary,
                selectedRouteIndex + 1,
                routeSummaries.size,
                formatDistance(route.distanceMeters),
                formatDuration(route.durationSeconds),
            ),
            NaviTone.INFO,
        )
    }

    private fun showProgress(progress: OfficialNavigationProgress) {
        val road = progress.nextRoadName ?: progress.currentRoadName ?: "导航中"
        showDetail(
            getString(
                R.string.official_navigation_progress,
                road,
                formatDistance(progress.remainingDistanceMeters),
                formatDuration(progress.remainingDurationSeconds),
            ),
            NaviTone.INFO,
        )
    }

    private fun showMapError(title: String, error: MapError) {
        startRequestPending = false
        navigationState = navigator?.state ?: OfficialNavigationState.FAILED
        showError("$title：${error.message}")
    }

    private fun showError(message: String) {
        viewModel.showErrorDetail(message)
        renderState()
    }

    private fun showDetail(text: String, tone: NaviTone) {
        viewModel.setDetail(text, tone)
        renderState()
    }

    private fun renderState() = render(viewModel.state.value)

    /** 唯一渲染入口：所有文本、选中态和按钮可用性都由不可变 UI 状态推导。 */
    private fun render(state: OfficialNavigationUiState) {
        if (!::startButton.isInitialized) return
        stateView.text = state.navigationState.displayName()
        val stateError = state.navigationState == OfficialNavigationState.FAILED
        stateView.setTextColor(if (stateError) DemoTheme.ERROR else DemoTheme.BRAND_DARK)
        stateView.background = DemoTheme.roundedRect(
            context = this,
            fillColor = if (stateError) DemoTheme.ERROR_SOFT else DemoTheme.BRAND_SOFT,
            strokeColor = if (stateError) DemoTheme.ERROR_BORDER else DemoTheme.BRAND_BORDER,
            cornerDp = 999f,
        )
        modeButtons.forEach { (mode, button) ->
            styleModeChoice(button, mode == state.navigationMode)
        }
        travelModeButtons.forEach { (mode, button) ->
            styleChoice(button, mode == state.travelMode)
        }
        val speedSupported = capabilities?.supports(OfficialNavigationFeature.SIMULATION_SPEED) == true
        speedRow.visibility = if (
            speedSupported && state.navigationMode == OfficialNavigationMode.SIMULATED
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }

        setButtonEnabled(
            startButton,
            !state.controlsBlocked && navigator != null && !state.busy && !state.navigationStarted,
        )
        setButtonEnabled(
            pauseResumeButton,
            !state.controlsBlocked &&
                (state.navigationState == OfficialNavigationState.NAVIGATING ||
                    state.navigationState == OfficialNavigationState.PAUSED),
        )
        pauseResumeButton.text = if (state.navigationState == OfficialNavigationState.PAUSED) {
            "继续"
        } else {
            "暂停"
        }
        setButtonEnabled(
            routeButton,
            !state.controlsBlocked && state.routeSummaries.size > 1 && state.busy,
        )
        routeButton.text = if (state.routeSummaries.isEmpty()) {
            "路线"
        } else {
            getString(
                R.string.official_navigation_route,
                state.selectedRouteIndex + 1,
                state.routeSummaries.size,
            )
        }
        setButtonEnabled(stopButton, !state.controlsBlocked && state.busy)
        listOfNotNull(rerouteButton, overviewButton, followButton).forEach {
            setButtonEnabled(it, !state.controlsBlocked && state.navigationActive)
        }
        voiceButton?.let {
            it.text = if (state.voiceGuidanceEnabled) "语音开" else "语音关"
            styleChoice(it, state.voiceGuidanceEnabled)
            setButtonEnabled(it, !state.controlsBlocked && navigator != null)
        }
        dayNightButton?.let {
            it.text = state.dayNightMode.shortDisplayName()
            setButtonEnabled(it, !state.controlsBlocked && navigator != null)
        }
        modeButtons.values.forEach {
            setButtonEnabled(it, !state.controlsBlocked && !state.busy && !state.navigationStarted)
        }
        travelModeButtons.values.forEach {
            setButtonEnabled(it, !state.controlsBlocked && !state.busy && !state.navigationStarted)
        }
        val runtimeSpeed = capabilities?.supports(OfficialNavigationFeature.RUNTIME_SIMULATION_SPEED) == true &&
            state.navigationActive &&
            state.navigationMode == OfficialNavigationMode.SIMULATED
        speedSeekBar.isEnabled = !state.controlsBlocked &&
            ((!state.busy && !state.navigationStarted) || runtimeSpeed)
        speedSeekBar.alpha = if (speedSeekBar.isEnabled) 1f else 0.42f
        val expectedProgress = state.simulationSpeed - MIN_SIMULATION_SPEED
        if (speedSeekBar.progress != expectedProgress) {
            speedSeekBar.progress = expectedProgress
        }
        speedValueView.text = getString(R.string.official_navigation_speed, state.simulationSpeed)

        detailView.text = state.detailText
        if (state.detailTone == NaviTone.ERROR) {
            detailView.setTextColor(DemoTheme.ERROR)
            detailView.background = DemoTheme.roundedRect(
                context = this,
                fillColor = StatusTone.ERROR.cardBgColor,
                strokeColor = StatusTone.ERROR.cardBorderColor,
                cornerDp = 11f,
            )
        } else {
            detailView.setTextColor(DemoTheme.TEXT_PRIMARY)
            detailView.background = DemoTheme.roundedRect(
                context = this,
                fillColor = StatusTone.INFO.cardBgColor,
                strokeColor = StatusTone.INFO.cardBorderColor,
                cornerDp = 11f,
            )
        }
        placeholderView.visibility = if (state.placeholderVisible) View.VISIBLE else View.GONE
        placeholderView.text = state.placeholderText
        placeholderView.setTextColor(
            if (state.placeholderTone == NaviTone.ERROR) DemoTheme.ERROR else DemoTheme.TEXT_SECONDARY,
        )
    }

    /** 兼容旧调用点；统一走 [render]。 */
    private fun updateControls() = renderState()

    private fun disableNavigationControls() {
        viewModel.blockControls()
        renderState()
    }

    private fun orderedTravelModes(): List<TravelMode> = TRAVEL_MODE_ORDER.filter {
        capabilities?.supports(it) == true
    }

    private fun stopAndFinish() {
        if (navigationState in ACTIVE_STATES) runCatching { navigator?.stop() }
        finish()
    }

    private fun refreshConsent(): Boolean {
        val granted = consentStore.hasConsent()
        if (!granted && consentGranted) revokeNavigationSession()
        consentGranted = granted
        return granted
    }

    /** 撤回同意后立即停止并销毁会话；旧事件桥通过 sessionEpoch 失效。 */
    private fun revokeNavigationSession() {
        statePollHandler.removeCallbacks(statePollRunnable)
        sessionEpoch += 1
        val session = navigator
        navigator = null
        val view = session?.view
        if (view != null) (view.parent as? ViewGroup)?.removeView(view)
        runCatching { session?.stop() }
        runCatching { session?.destroy() }
    }

    private fun hasFineLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    override fun onStart() {
        super.onStart()
        if (!refreshConsent()) {
            viewModel.blockForConsent("地图隐私同意已失效，请返回首页重新确认")
            renderState()
            return
        }
        navigator?.onStart()
        syncNavigatorState()
        scheduleNavigatorStatePolling()
    }

    override fun onResume() {
        super.onResume()
        navigator?.onResume()
    }

    override fun onPause() {
        navigator?.onPause()
        super.onPause()
    }

    override fun onStop() {
        statePollHandler.removeCallbacks(statePollRunnable)
        navigator?.onStop()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        navigator?.onConfigurationChanged(newConfig)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val navigatorState = Bundle()
        navigator?.onSaveInstanceState(navigatorState)
        outState.putBundle(STATE_NAVIGATOR, navigatorState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Forwarded to the vendor navigation view")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        navigator?.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Forwarded to the vendor navigation view")
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        navigator?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        navigator?.onKeyDown(keyCode, event) == true || super.onKeyDown(keyCode, event)

    override fun onDestroy() {
        statePollHandler.removeCallbacks(statePollRunnable)
        sessionEpoch += 1
        val session = navigator
        navigator = null
        runCatching { session?.destroy() }
        super.onDestroy()
    }

    private fun scheduleNavigatorStatePolling() {
        statePollHandler.removeCallbacks(statePollRunnable)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            consentGranted &&
            navigator?.state == OfficialNavigationState.INITIALIZING
        ) {
            statePollHandler.postDelayed(statePollRunnable, STATE_POLL_INTERVAL_MS)
        }
    }

    private fun syncNavigatorState() {
        if (!refreshConsent()) {
            viewModel.blockForConsent("地图隐私同意已失效，请返回首页重新确认")
            renderState()
            return
        }
        val session = navigator ?: return
        val engineState = session.state
        viewModel.onStateChanged(engineState)
        if (engineState == OfficialNavigationState.FAILED) {
            showError(
                session.lastError?.let { "官方导航失败：${it.message}" }
                    ?: "官方导航初始化失败，请返回后重试",
            )
        }
        updateControls()
    }

    private fun compactButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12.5f
        maxLines = 1
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        stateListAnimator = null
        setPadding(dp(4), 0, dp(4), 0)
        setTextColor(DemoTheme.TEXT_PRIMARY)
        background = DemoTheme.interactiveSurface(
            context = this@OfficialNavigationActivity,
            fillColor = DemoTheme.SURFACE_ELEVATED,
            pressedColor = DemoTheme.SURFACE_PRESSED,
            strokeColor = DemoTheme.BORDER,
            cornerDp = 10f,
        )
        setOnClickListener { action() }
    }

    private fun actionButton(label: String, action: () -> Unit): Button = compactButton(label, action).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = 13f
        background = DemoTheme.interactiveSurface(
            context = this@OfficialNavigationActivity,
            fillColor = DemoTheme.SURFACE_ELEVATED,
            pressedColor = DemoTheme.SURFACE_PRESSED,
            strokeColor = DemoTheme.BORDER_STRONG,
            cornerDp = 11f,
        )
    }

    private fun primaryActionButton(label: String, action: () -> Unit): Button = compactButton(label, action).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = 13f
        setTextColor(Color.WHITE)
        background = DemoTheme.interactiveSurface(
            context = this@OfficialNavigationActivity,
            fillColor = DemoTheme.BRAND,
            pressedColor = DemoTheme.BRAND_DARK,
            strokeColor = DemoTheme.BRAND_DARK,
            cornerDp = 11f,
        )
    }

    private fun dangerActionButton(label: String, action: () -> Unit): Button = compactButton(label, action).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = 13f
        setTextColor(DemoTheme.ERROR)
        background = DemoTheme.interactiveSurface(
            context = this@OfficialNavigationActivity,
            fillColor = DemoTheme.ERROR_SOFT,
            pressedColor = DemoTheme.SURFACE_PRESSED,
            strokeColor = DemoTheme.ERROR_BORDER,
            cornerDp = 11f,
        )
    }

    private fun controlLabel(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 11.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(DemoTheme.TEXT_MUTED)
    }

    private fun styleModeChoice(button: Button, selected: Boolean) {
        button.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        button.setTextColor(if (selected) Color.WHITE else DemoTheme.TEXT_SECONDARY)
        button.background = DemoTheme.interactiveSurface(
            context = this,
            fillColor = if (selected) DemoTheme.BRAND else Color.TRANSPARENT,
            pressedColor = if (selected) DemoTheme.BRAND_DARK else DemoTheme.SURFACE_PRESSED,
            strokeColor = Color.TRANSPARENT,
            cornerDp = 8f,
            strokeDp = 0f,
        )
    }

    private fun styleChoice(button: Button, selected: Boolean) {
        button.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        button.setTextColor(if (selected) DemoTheme.BRAND_DARK else DemoTheme.TEXT_PRIMARY)
        button.background = DemoTheme.interactiveSurface(
            context = this,
            fillColor = if (selected) DemoTheme.BRAND_SOFT else DemoTheme.SURFACE_ELEVATED,
            pressedColor = DemoTheme.SURFACE_PRESSED,
            strokeColor = if (selected) DemoTheme.BRAND else DemoTheme.BORDER,
            cornerDp = 10f,
        )
    }

    private fun setButtonEnabled(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.42f
    }

    private fun divider(): View = View(this).apply { setBackgroundColor(DemoTheme.BORDER) }

    private fun fatalView(message: String): View = TextView(this).apply {
        text = message
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(DemoTheme.ERROR)
        setPadding(dp(24), dp(24), dp(24), dp(24))
    }

    private fun fullWidthHeight(height: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(height),
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatDistance(meters: Int): String =
        if (meters < 1_000) "$meters m" else "%.1f km".format(Locale.CHINA, meters / 1_000.0)

    private fun formatDuration(seconds: Int): String = when {
        seconds < 60 -> "$seconds 秒"
        seconds < 3_600 -> "${seconds / 60} 分钟"
        else -> "${seconds / 3_600} 小时 ${seconds % 3_600 / 60} 分钟"
    }

    private fun Provider?.displayName(): String = when (this) {
        Provider.BAIDU -> "百度"
        Provider.AMAP -> "高德"
        null -> "地图"
    }

    private fun OfficialNavigationMode.displayName(): String = when (this) {
        OfficialNavigationMode.REAL -> "真实导航"
        OfficialNavigationMode.SIMULATED -> "模拟导航"
    }

    private fun TravelMode.displayName(): String = when (canonical()) {
        TravelMode.DRIVING -> "驾车"
        TravelMode.WALKING -> "步行"
        TravelMode.BICYCLE -> "自行车"
        TravelMode.ELECTRIC_BICYCLE -> "电动车"
        TravelMode.TRANSIT -> "公交"
        TravelMode.RIDING -> "自行车"
    }

    private fun OfficialNavigationState.displayName(): String = when (this) {
        OfficialNavigationState.INITIALIZING -> "引擎初始化"
        OfficialNavigationState.READY -> "可以开始"
        OfficialNavigationState.PLANNING -> "路线规划中"
        OfficialNavigationState.NAVIGATING -> "导航中"
        OfficialNavigationState.PAUSED -> "已暂停"
        OfficialNavigationState.ARRIVED -> "已到达"
        OfficialNavigationState.STOPPED -> "已停止"
        OfficialNavigationState.FAILED -> "发生错误"
        OfficialNavigationState.DESTROYED -> "已销毁"
    }

    private fun OfficialNavigationDayNightMode.displayName(): String = when (this) {
        OfficialNavigationDayNightMode.AUTO -> "自动"
        OfficialNavigationDayNightMode.DAY -> "白天"
        OfficialNavigationDayNightMode.NIGHT -> "夜间"
    }

    private fun OfficialNavigationDayNightMode.shortDisplayName(): String = when (this) {
        OfficialNavigationDayNightMode.AUTO -> "自动色"
        OfficialNavigationDayNightMode.DAY -> "白天色"
        OfficialNavigationDayNightMode.NIGHT -> "夜间色"
    }

    companion object {
        private const val EXTRA_PROVIDER = "mapfusion.official_navi.provider"
        private const val EXTRA_ORIGIN_PREFIX = "mapfusion.official_navi.origin"
        private const val EXTRA_DESTINATION_PREFIX = "mapfusion.official_navi.destination"
        private const val STATE_NAVIGATOR = "mapfusion.official_navi.navigator"
        private const val MIN_SIMULATION_SPEED = 20
        private const val MAX_SIMULATION_SPEED = 120
        private const val STATE_POLL_INTERVAL_MS = 150L

        private val DEFAULT_ORIGIN = LatLng(39.9087, 116.3975, CoordType.WGS84)
        private val DEFAULT_DESTINATION = LatLng(39.9163, 116.4172, CoordType.WGS84)
        private val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        private val TRAVEL_MODE_ORDER = listOf(
            TravelMode.DRIVING,
            TravelMode.WALKING,
            TravelMode.BICYCLE,
            TravelMode.ELECTRIC_BICYCLE,
        )
        private val ACTIVE_STATES = setOf(
            OfficialNavigationState.PLANNING,
            OfficialNavigationState.NAVIGATING,
            OfficialNavigationState.PAUSED,
        )
        private val ADVANCED_CONTROL_FEATURES = setOf(
            OfficialNavigationFeature.MANUAL_REROUTE,
            OfficialNavigationFeature.ROUTE_OVERVIEW,
            OfficialNavigationFeature.RESTORE_FOLLOWING,
            OfficialNavigationFeature.VOICE_CONTROL,
            OfficialNavigationFeature.DAY_NIGHT_MODE,
        )
        private val COLOR_PAGE = Color.rgb(244, 247, 248)
        private val COLOR_MAP = Color.rgb(225, 231, 234)
        private val COLOR_TEXT = Color.rgb(35, 39, 43)
        private val COLOR_MUTED = Color.rgb(99, 107, 116)
        private val COLOR_BORDER = Color.rgb(210, 217, 222)
        private val COLOR_BORDER_LIGHT = Color.rgb(229, 234, 237)
        private val COLOR_CONTROL = Color.rgb(248, 250, 251)
        private val COLOR_ACCENT = Color.rgb(0, 105, 92)
        private val COLOR_ACCENT_SOFT = Color.rgb(226, 242, 239)
        private val COLOR_ERROR = Color.rgb(198, 40, 40)

        internal fun createIntent(
            context: Context,
            provider: Provider,
            origin: LatLng,
            destination: LatLng,
        ): Intent = Intent(context, OfficialNavigationActivity::class.java).apply {
            putExtra(EXTRA_PROVIDER, provider.name)
            putPoint(EXTRA_ORIGIN_PREFIX, origin)
            putPoint(EXTRA_DESTINATION_PREFIX, destination)
        }

        private fun Intent.putPoint(prefix: String, point: LatLng) {
            putExtra("$prefix.latitude", point.latitude)
            putExtra("$prefix.longitude", point.longitude)
            putExtra("$prefix.coord_type", point.coordType.name)
        }

        private fun Intent.readPoint(prefix: String): LatLng? {
            if (!hasExtra("$prefix.latitude") || !hasExtra("$prefix.longitude")) return null
            val coordType = getStringExtra("$prefix.coord_type")
                ?.let { value -> runCatching { CoordType.valueOf(value) }.getOrNull() }
                ?: return null
            return LatLng(
                latitude = getDoubleExtra("$prefix.latitude", 0.0),
                longitude = getDoubleExtra("$prefix.longitude", 0.0),
                coordType = coordType,
            )
        }
    }
}
