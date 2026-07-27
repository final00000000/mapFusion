package com.mapfusion.demo

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.mapfusion.api.MapConfig
import com.mapfusion.api.MapProvider
import com.mapfusion.api.async.RequestScope
import com.mapfusion.api.capability.MapController
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.capability.TrackListener
import com.mapfusion.api.capability.TrackRecorder
import com.mapfusion.api.capability.TrackState
import com.mapfusion.api.coordinate.DefaultCoordinateConverter
import com.mapfusion.api.model.CameraUpdate
import com.mapfusion.api.model.CircleOptions
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.DistrictSearchRequest
import com.mapfusion.api.model.DistrictSearchResult
import com.mapfusion.api.model.EmbeddedNavigationEvent
import com.mapfusion.api.model.EmbeddedNavigationMode
import com.mapfusion.api.model.EmbeddedNavigationOptions
import com.mapfusion.api.model.EmbeddedNavigationRequest
import com.mapfusion.api.model.EmbeddedNavigationState
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.GeocodeRequest
import com.mapfusion.api.model.GroundOverlayOptions
import com.mapfusion.api.model.HeatMapOptions
import com.mapfusion.api.model.HeatPoint
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.LocationOptions
import com.mapfusion.api.model.LocationAccuracyStyle
import com.mapfusion.api.model.LocationDisplayEvent
import com.mapfusion.api.model.LocationDisplayOptions
import com.mapfusion.api.model.LocationDisplayStyle
import com.mapfusion.api.model.LocationFollowMode
import com.mapfusion.api.model.LocationMarkerStyle
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapImage
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.MapUiOptions
import com.mapfusion.api.model.MapLocation
import com.mapfusion.api.model.MapType
import com.mapfusion.api.model.MarkerIcon
import com.mapfusion.api.model.MarkerOptions
import com.mapfusion.api.model.MultiPointItem
import com.mapfusion.api.model.MultiPointOverlayOptions
import com.mapfusion.api.model.PoiSearchRequest
import com.mapfusion.api.model.PoiSearchResult
import com.mapfusion.api.model.PoiSearchType
import com.mapfusion.api.model.PoiSort
import com.mapfusion.api.model.PolygonOptions
import com.mapfusion.api.model.PolylineOptions
import com.mapfusion.api.model.RouteRequest
import com.mapfusion.api.model.RouteResult
import com.mapfusion.api.model.RouteMarkerOptions
import com.mapfusion.api.model.RouteOverlayOptions
import com.mapfusion.api.model.TextOverlayOptions
import com.mapfusion.api.model.TrackOptions
import com.mapfusion.api.model.TrackSnapshot
import com.mapfusion.api.model.TravelMode
import com.mapfusion.api.model.WeatherForecast
import com.mapfusion.api.model.WeatherNow
import com.mapfusion.api.model.WeatherRequest
import com.mapfusion.factory.MapFusion
import com.mapfusion.factory.MapFusionSession
import com.mapfusion.full.MapFusionFull
import java.io.ByteArrayOutputStream
import java.util.Locale

private enum class LocationPermissionAction { LOCATE, START_TRACK, START_NAVIGATION }

private enum class PrivacyDecision { UNDECIDED, ACCEPTED, DECLINED }

private data class DemoMapSettings(
    var mapType: MapType = MapType.NORMAL,
    var trafficEnabled: Boolean = false,
    var buildingsEnabled: Boolean = true,
    var indoorEnabled: Boolean = false,
    var mapPoiEnabled: Boolean = true,
    var compassEnabled: Boolean = true,
    var zoomControlsEnabled: Boolean = true,
)

/** 同一套业务调用通过唯一配置点切换百度/高德。 */
class MainActivity : ComponentActivity() {

    private lateinit var mapContainer: FrameLayout
    private lateinit var providerStatusView: TextView
    private lateinit var resultView: TextView
    private lateinit var baiduButton: Button
    private lateinit var amapButton: Button
    private lateinit var privacyButton: Button

    private var currentProvider: MapProvider? = null
    private var mapController: MapController? = null
    private var mapSession: MapFusionSession? = null
    private var trackRecorder: TrackRecorder? = null
    private var lastLocation: MapLocation? = null
    private var activityResumed = false
    private var providerEpoch = 0
    private var selectedRouteMode = TravelMode.DRIVING
    private var selectedNavigationMode = EmbeddedNavigationMode.REAL
    private var embeddedNavigationActive = false
    private var mapSettings = DemoMapSettings()
    private var pendingLocationAction: LocationPermissionAction? = null
    private var locationPermissionRequestInFlight = false
    private var locationPermissionDenied = false
    private var locationPermissionPermanentlyDenied = false
    private var locationSettingsDialog: AlertDialog? = null
    private var privacyDialog: AlertDialog? = null
    private var privacyDecision = PrivacyDecision.UNDECIDED
    private var preferredProvider = Provider.BAIDU
    private var pendingMapSavedState: Bundle? = null
    private var factoriesRegistered = false
    private val requestScope = RequestScope()

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        locationPermissionRequestInFlight = false
        val action = pendingLocationAction
        pendingLocationAction = null
        if ((grants.values.any { it } || hasLocationPermission()) && hasPrivacyConsent()) {
            locationPermissionDenied = false
            locationPermissionPermanentlyDenied = false
            action?.let(::executeLocationAction)
        } else {
            locationPermissionDenied = true
            locationPermissionPermanentlyDenied = LOCATION_PERMISSIONS.all {
                !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
            }
            val detail = if (locationPermissionPermanentlyDenied) {
                "权限已被永久拒绝，点击“定位”可前往应用设置开启"
            } else {
                "点击“定位”可重新申请精确或大致位置权限"
            }
            showError("定位权限被拒绝", detail)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingLocationAction = savedInstanceState
            ?.getString(STATE_PENDING_LOCATION_ACTION)
            ?.let { value -> runCatching { LocationPermissionAction.valueOf(value) }.getOrNull() }
        locationPermissionRequestInFlight = savedInstanceState
            ?.getBoolean(STATE_LOCATION_PERMISSION_REQUEST_IN_FLIGHT)
            ?: false
        locationPermissionDenied = savedInstanceState?.getBoolean(STATE_LOCATION_PERMISSION_DENIED) ?: false
        locationPermissionPermanentlyDenied = savedInstanceState
            ?.getBoolean(STATE_LOCATION_PERMISSION_PERMANENTLY_DENIED)
            ?: false
        selectedRouteMode = savedInstanceState
            ?.getString(STATE_ROUTE_MODE)
            ?.let { value -> runCatching { TravelMode.valueOf(value) }.getOrNull() }
            ?: TravelMode.DRIVING
        selectedNavigationMode = savedInstanceState
            ?.getString(STATE_NAVIGATION_MODE)
            ?.let { value -> runCatching { EmbeddedNavigationMode.valueOf(value) }.getOrNull() }
            ?: EmbeddedNavigationMode.REAL
        privacyDecision = readPrivacyDecision()
        preferredProvider = savedInstanceState
            ?.getString(STATE_PREFERRED_PROVIDER)
            ?.let { value -> runCatching { Provider.valueOf(value) }.getOrNull() }
            ?: savedInstanceState
                ?.getString(STATE_PROVIDER)
                ?.let { value -> runCatching { Provider.valueOf(value) }.getOrNull() }
            ?: Provider.BAIDU
        pendingMapSavedState = savedInstanceState?.getBundle(STATE_MAP)
        setContentView(buildContentView())
        if (hasPrivacyConsent()) {
            switchProvider(preferredProvider, pendingMapSavedState)
        } else {
            showPrivacyUnavailableState()
            if (privacyDecision == PrivacyDecision.UNDECIDED) {
                mapContainer.post { showPrivacyConsentDialog(firstRun = true) }
            }
        }
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            setBackgroundColor(COLOR_PAGE)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        providerStatusView = TextView(this).apply {
            textSize = 14f
            setTextColor(COLOR_TEXT)
            text = "地图服务准备中"
            maxLines = 2
        }
        header.addView(
            providerStatusView,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { gravity = Gravity.CENTER_VERTICAL },
        )
        baiduButton = providerButton("百度") { switchProvider(Provider.BAIDU) }
        amapButton = providerButton("高德") { switchProvider(Provider.AMAP) }
        privacyButton = providerButton("隐私设置", ::showPrivacySettingsDialog).apply {
            setTextColor(COLOR_TEXT)
            background = panelBackground(Color.WHITE, COLOR_BORDER)
        }
        header.addView(baiduButton, LinearLayout.LayoutParams(dp(58), dp(38)))
        header.addView(amapButton, LinearLayout.LayoutParams(dp(58), dp(38)).apply { marginStart = dp(5) })
        header.addView(privacyButton, LinearLayout.LayoutParams(dp(78), dp(38)).apply { marginStart = dp(5) })
        root.addView(header)

        resultView = TextView(this).apply {
            textSize = 13f
            setTextColor(COLOR_MUTED)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 3
            background = panelBackground(COLOR_PANEL, COLOR_BORDER)
            text = "等待操作"
        }
        root.addView(
            resultView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)).apply {
                bottomMargin = dp(8)
            },
        )

        mapContainer = FrameLayout(this).apply { setBackgroundColor(Color.rgb(225, 230, 234)) }
        root.addView(
            mapContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        root.addView(sectionLabel("业务能力"))
        root.addView(
            actionStrip(
                "定位" to ::demoLocation,
                "路线导航" to ::showRouteNavigationDialog,
                "地图设置" to ::showMapSettingsDialog,
                "地址解析" to ::demoGeocode,
                "逆地理" to ::demoReverseGeocode,
                "周边 POI" to ::demoPoiSearch,
                "行政区" to ::demoDistrict,
                "实时天气" to ::demoWeatherNow,
                "天气预报" to ::demoWeatherForecast,
                "截图" to ::demoSnapshot,
            ),
        )
        root.addView(sectionLabel("轨迹记录"))
        root.addView(
            actionStrip(
                "开始" to ::demoStartTrack,
                "暂停/继续" to ::demoPauseOrResumeTrack,
                "结束" to ::demoStopTrack,
                "清除" to ::demoClearTrack,
            ),
        )
        root.addView(sectionLabel("覆盖物"))
        root.addView(
            actionStrip(
                "Marker" to ::demoMarker,
                "海量点" to ::demoMultiPoint,
                "折线" to ::demoPolyline,
                "多边形" to ::demoPolygon,
                "圆" to ::demoCircle,
                "文字" to ::demoText,
                "地面图" to ::demoGroundOverlay,
                "热力图" to ::demoHeatMap,
                "清空" to ::clearMap,
            ),
        )
        return root
    }

    /**
     * Demo 由宿主负责隐私告知和同意记录；未同意前不注册工厂，也不读取 Key 或打开厂商 Session。
     */
    private fun showPrivacyConsentDialog(firstRun: Boolean) {
        if (privacyDialog?.isShowing == true || isFinishing || isDestroyed) return
        val dialog = AlertDialog.Builder(this)
            .setTitle("地图服务隐私说明")
            .setMessage(PRIVACY_NOTICE)
            .setNegativeButton(if (firstRun) "不同意" else "暂不启用") { _, _ ->
                persistPrivacyDecision(PrivacyDecision.DECLINED)
                showPrivacyUnavailableState()
            }
            .setPositiveButton("同意并启用") { _, _ -> grantPrivacyConsent() }
            .setCancelable(!firstRun)
            .create()
        privacyDialog = dialog
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            if (privacyDialog === dialog) privacyDialog = null
        }
        dialog.show()
    }

    private fun showPrivacySettingsDialog() {
        if (!hasPrivacyConsent()) {
            showPrivacyConsentDialog(firstRun = false)
            return
        }
        if (privacyDialog?.isShowing == true || isFinishing || isDestroyed) return
        val dialog = AlertDialog.Builder(this)
            .setTitle("隐私设置")
            .setMessage(
                "当前已同意地图服务隐私说明。撤回后会立即停止定位、导航、轨迹及异步请求，" +
                    "销毁当前地图会话；重新同意前不会再次初始化百度或高德地图 SDK。",
            )
            .setNegativeButton("撤回同意") { _, _ -> revokePrivacyConsent() }
            .setPositiveButton("保留同意", null)
            .create()
        privacyDialog = dialog
        dialog.setOnDismissListener {
            if (privacyDialog === dialog) privacyDialog = null
        }
        dialog.show()
    }

    private fun grantPrivacyConsent() {
        if (!persistPrivacyDecision(PrivacyDecision.ACCEPTED)) {
            showError("隐私设置保存失败", "无法持久化同意状态，地图 SDK 未初始化")
            return
        }
        showInfo("已同意地图服务隐私说明", "正在初始化${preferredProvider.displayName()}地图")
        val savedState = pendingMapSavedState
        pendingMapSavedState = null
        switchProvider(preferredProvider, savedState)
    }

    private fun revokePrivacyConsent() {
        if (!persistPrivacyDecision(PrivacyDecision.DECLINED)) {
            showError("隐私设置保存失败", "未执行撤回，请稍后重试")
            return
        }
        stopMapDataProcessing()
        showPrivacyUnavailableState()
        if (factoriesRegistered) {
            val failures = MapFusion.updatePrivacyConsent(this, consentGranted = false)
                .mapNotNull { (provider, result) ->
                    (result as? MapResult.Failure)?.let { provider to it.error }
                }
            failures.forEach { (provider, error) ->
                Log.e(TAG, "撤回 ${provider.displayName()} 隐私同意失败", error.cause)
            }
            if (failures.isNotEmpty()) {
                showError(
                    "厂商隐私状态更新失败",
                    failures.joinToString { (provider, error) ->
                        "${provider.displayName()}：${error.message}"
                    },
                )
            }
        }
    }

    private fun readPrivacyDecision(): PrivacyDecision {
        val value = getSharedPreferences(PRIVACY_PREFERENCES, MODE_PRIVATE)
            .getString(PRIVACY_DECISION_KEY, null)
            ?: return PrivacyDecision.UNDECIDED
        return runCatching { PrivacyDecision.valueOf(value) }.getOrDefault(PrivacyDecision.UNDECIDED)
    }

    /** 使用同步提交，只有状态确实落盘后才允许初始化或确认撤回。 */
    private fun persistPrivacyDecision(decision: PrivacyDecision): Boolean {
        val saved = getSharedPreferences(PRIVACY_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(PRIVACY_DECISION_KEY, decision.name)
            .commit()
        if (saved) privacyDecision = decision
        return saved
    }

    private fun hasPrivacyConsent(): Boolean = privacyDecision == PrivacyDecision.ACCEPTED

    private fun ensurePrivacyConsentForAction(): Boolean {
        if (hasPrivacyConsent()) return true
        showPrivacyUnavailableState()
        showPrivacyConsentDialog(firstRun = privacyDecision == PrivacyDecision.UNDECIDED)
        return false
    }

    private fun registerFactoriesIfNeeded() {
        if (factoriesRegistered) return
        MapFusionFull.install()
        factoriesRegistered = true
    }

    private fun showPrivacyUnavailableState() {
        mapContainer.removeAllViews()
        mapContainer.addView(
            TextView(this).apply {
                text = getString(R.string.map_service_disabled)
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(COLOR_MUTED)
                setPadding(dp(24), dp(24), dp(24), dp(24))
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        providerStatusView.text = "地图服务未启用"
        styleProviderButton(baiduButton, selected = false)
        styleProviderButton(amapButton, selected = false)
        showInfo("地图功能不可用", "未同意隐私说明，尚未初始化任何地图厂商 SDK")
    }

    /** 撤回同意时主动终止所有会话资源，并通过 epoch 丢弃已在途的迟到回调。 */
    private fun stopMapDataProcessing() {
        providerEpoch++
        requestScope.cancelAll()
        preferredProvider = currentProvider?.provider ?: preferredProvider
        pendingMapSavedState = null
        pendingLocationAction = null
        locationPermissionRequestInFlight = false
        val session = mapSession
        mapSession = null
        currentProvider = null
        mapController = null
        lastLocation = null
        embeddedNavigationActive = false
        mapSettings = DemoMapSettings()
        runCatching(::releaseTrackRecorder).onFailure { error ->
            Log.w(TAG, "隐私撤回时释放轨迹失败", error)
        }
        if (activityResumed) {
            runCatching { session?.onPause() }.onFailure { error ->
                Log.w(TAG, "隐私撤回时暂停地图失败", error)
            }
        }
        runCatching { session?.destroy() }.onFailure { error ->
            Log.w(TAG, "隐私撤回时销毁地图会话失败", error)
        }
        mapContainer.removeAllViews()
    }

    /** 唯一厂商切换点；地图就绪后自动定位当前位置，不自动添加演示覆盖物。 */
    private fun switchProvider(provider: Provider, savedState: Bundle? = null) {
        preferredProvider = provider
        if (!hasPrivacyConsent()) {
            pendingMapSavedState = savedState ?: pendingMapSavedState
            ensurePrivacyConsentForAction()
            return
        }
        registerFactoriesIfNeeded()
        pendingMapSavedState = null
        providerEpoch++
        requestScope.cancelAll()
        val previousSession = mapSession
        mapSession = null
        currentProvider = null
        mapController = null
        lastLocation = null
        embeddedNavigationActive = false
        mapSettings = DemoMapSettings()
        runCatching(::releaseTrackRecorder).onFailure { error ->
            Log.w(TAG, "释放旧轨迹失败", error)
        }
        if (activityResumed) {
            runCatching { previousSession?.onPause() }.onFailure { error ->
                Log.w(TAG, "暂停旧地图失败", error)
            }
        }
        runCatching { previousSession?.destroy() }.onFailure { error ->
            Log.w(TAG, "销毁旧地图失败", error)
        }
        mapContainer.removeAllViews()
        providerStatusView.text = getString(R.string.provider_initializing, provider.displayName())
        styleProviderButton(baiduButton, selected = false)
        styleProviderButton(amapButton, selected = false)

        var candidateSession: MapFusionSession? = null
        runCatching {
            val config = MapConfig(
                provider = provider,
                apiKey = when (provider) {
                    Provider.BAIDU -> BuildConfig.BAIDU_MAP_API_KEY
                    Provider.AMAP -> BuildConfig.AMAP_API_KEY
                },
                // Demo 的隐私说明页已由宿主完成；生产应用必须在用户明确同意后再传 true。
                enablePrivacyCompliance = true,
            )
            val session = MapFusion.openSession(this, config, savedState)
            candidateSession = session
            val createdProvider = session.provider
            val controller = session.mapController
            if (activityResumed) session.onResume()
            (controller.view.parent as? ViewGroup)?.removeView(controller.view)
            mapContainer.addView(
                controller.view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            controller.setUiOptions(MapUiOptions(scaleControlsEnabled = true, compassEnabled = true))
            controller.moveCamera(CameraUpdate(target = defaultPoint(provider), zoom = 14f, animated = false))
            currentProvider = createdProvider
            mapController = controller
            mapSession = session
            updateProviderHeader(provider, createdProvider.capabilities().size)
            showInfo("${provider.displayName()}地图已就绪", "正在定位当前位置")
            val epoch = providerEpoch
            mapContainer.post {
                if (epoch == providerEpoch && !isFinishing && !isDestroyed) {
                    requestCurrentLocation(fromUser = false)
                }
            }
        }.onFailure { error ->
            if (activityResumed) {
                runCatching { candidateSession?.onPause() }
            }
            runCatching { candidateSession?.destroy() }.onFailure { destroyError ->
                error.addSuppressed(destroyError)
            }
            candidateSession = null
            mapSession = null
            currentProvider = null
            mapController = null
            mapContainer.removeAllViews()
            providerStatusView.text = getString(
                R.string.provider_initialization_failed,
                provider.displayName(),
            )
            Log.e(TAG, "切换 $provider 失败", error)
            showError("地图初始化失败", error.message ?: error::class.java.simpleName)
        }
    }

    private fun demoLocation() = requestLocationAccess(LocationPermissionAction.LOCATE, fromUser = true)

    /** 真实模式需要位置权限；模拟模式只使用规划路线，不触发系统位置权限申请。 */
    private fun demoStartNavigation(mode: TravelMode) {
        selectedRouteMode = mode
        when (selectedNavigationMode) {
            EmbeddedNavigationMode.REAL -> {
                requestLocationAccess(LocationPermissionAction.START_NAVIGATION, fromUser = true)
            }
            EmbeddedNavigationMode.SIMULATED -> startEmbeddedNavigation(mode)
        }
    }

    private fun requestCurrentLocation(fromUser: Boolean) {
        requestLocationAccess(LocationPermissionAction.LOCATE, fromUser)
    }

    private fun requestLocationAccess(action: LocationPermissionAction, fromUser: Boolean) {
        if (!ensurePrivacyConsentForAction()) return
        if (hasLocationPermission()) {
            pendingLocationAction = null
            locationPermissionDenied = false
            locationPermissionPermanentlyDenied = false
            executeLocationAction(action)
            return
        }

        if (locationPermissionRequestInFlight) {
            if (pendingLocationAction == null) pendingLocationAction = action
            showInfo("定位权限", "等待系统授权")
            return
        }
        pendingLocationAction = action
        if (!fromUser && locationPermissionDenied) {
            pendingLocationAction = null
            showError("未获得定位权限", "点击“定位”可重新授权并定位当前位置")
            return
        }
        if (locationPermissionPermanentlyDenied) {
            if (fromUser) showLocationSettingsDialog()
            else {
                pendingLocationAction = null
                showError("未获得定位权限", "点击“定位”可前往应用设置开启权限")
            }
            return
        }

        locationPermissionRequestInFlight = true
        showInfo("定位权限", "请允许精确或大致位置权限")
        locationPermissionLauncher.launch(LOCATION_PERMISSIONS)
    }

    private fun executeLocationAction(action: LocationPermissionAction) = when (action) {
        LocationPermissionAction.LOCATE -> performLocation()
        LocationPermissionAction.START_TRACK -> startTrackInternal()
        LocationPermissionAction.START_NAVIGATION -> startEmbeddedNavigation(selectedRouteMode)
    }

    private fun showLocationSettingsDialog() {
        if (locationSettingsDialog?.isShowing == true) return
        locationSettingsDialog = AlertDialog.Builder(this)
            .setTitle("需要定位权限")
            .setMessage("定位权限已被永久拒绝，请在应用设置中开启“位置信息”，返回后将自动继续当前操作。")
            .setNegativeButton("取消") { _, _ -> pendingLocationAction = null }
            .setPositiveButton("去设置") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null),
                    ),
                )
            }
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { locationSettingsDialog = null }
                dialog.show()
            }
    }

    private fun performLocation() = startSync("定位") {
        val display = requireNotNull(mapSession?.locationDisplay) { "当前厂商不支持定位展示" }
        display.stop()
        val epoch = providerEpoch
        val result = display.start(
            LocationDisplayOptions(
                locationOptions = LocationOptions(
                    onceOnly = false,
                    intervalMs = 2_000,
                    timeoutMs = 15_000,
                    needAddress = true,
                    useCache = true,
                ),
                style = LocationDisplayStyle(
                    marker = LocationMarkerStyle(
                        icon = MarkerIcon.Bytes(routeEndpointIconBytes("我", COLOR_CURRENT)),
                        anchorU = 0.5f,
                        anchorV = 0.5f,
                        flat = true,
                        rotateWithBearing = true,
                    ),
                    accuracy = LocationAccuracyStyle(
                        strokeWidth = dp(1).toFloat(),
                        strokeColor = COLOR_CURRENT,
                        fillColor = 0x2231A354,
                    ),
                ),
                followMode = LocationFollowMode.FIRST_FIX,
                followZoom = 17f,
                maxAccuracyMeters = 100f,
                pauseWhenBackground = true,
            ),
        ) { event ->
            deliver(epoch) {
                when (event) {
                    is LocationDisplayEvent.LocationUpdated -> showLocation(event.location)
                    is LocationDisplayEvent.AccuracyRejected -> showInfo(
                        "等待更精确定位",
                        "当前精度 ${event.location.accuracy.toInt()} m",
                    )
                    is LocationDisplayEvent.Failure -> showMapError("定位失败", event.error)
                    is LocationDisplayEvent.StateChanged -> Unit
                }
            }
        }
        if (result is MapResult.Failure) showMapError("定位启动失败", result.error)
    }

    private fun showLocation(location: MapLocation) {
        lastLocation = location
        val coordinate = "%.6f, %.6f".format(Locale.US, location.position.latitude, location.position.longitude)
        val address = location.address?.takeIf(String::isNotBlank) ?: "未返回地址"
        showSuccess("定位成功 · 精度 ${location.accuracy.toInt()} m", "$coordinate · $address")
    }

    private fun demoStartTrack() = requestLocationAccess(LocationPermissionAction.START_TRACK, fromUser = true)

    private fun startTrackInternal() = startSync("开始轨迹") {
        val provider = requireNotNull(currentProvider) { "地图尚未初始化" }
        val controller = requireNotNull(mapController) { "地图尚未初始化" }
        mapSession?.locationDisplay?.stop()
        controller.clearDemoOverlays()
        val recorder = requireNotNull(mapSession?.createTrackRecorder()) {
            "当前厂商不支持连续定位"
        }
        val epoch = providerEpoch
        trackRecorder = recorder
        showInfo("轨迹启动中", provider.provider.displayName())
        recorder.start(
            TrackOptions(
                locationOptions = LocationOptions(
                    onceOnly = false,
                    intervalMs = 2_000,
                    needAddress = false,
                    useCache = false,
                    distanceFilterMeters = 1f,
                ),
                minPointDistanceMeters = 1.5,
                maxAccuracyMeters = 100f,
                drawOnMap = true,
                followLocation = true,
                followZoom = 17f,
                polylineWidth = 14f,
                polylineColor = COLOR_ROUTE,
            ),
            object : TrackListener {
                override fun onStateChanged(snapshot: TrackSnapshot) {
                    deliver(epoch) { showTrackState(snapshot) }
                }

                override fun onPointAdded(snapshot: TrackSnapshot) {
                    deliver(epoch) { showTrackSnapshot("轨迹记录中", snapshot) }
                }

                override fun onError(error: MapError) {
                    deliver(epoch) { showMapError("轨迹记录失败", error) }
                }
            },
        )
    }

    private fun demoPauseOrResumeTrack() = startSync("暂停/继续轨迹") {
        val recorder = requireNotNull(trackRecorder) { "请先开始轨迹记录" }
        when (recorder.state) {
            TrackState.RECORDING -> recorder.pause()
            TrackState.PAUSED -> recorder.resume()
            TrackState.IDLE, TrackState.STOPPED -> error("当前没有可暂停或继续的轨迹")
        }
    }

    private fun demoStopTrack() = startSync("结束轨迹") {
        val recorder = requireNotNull(trackRecorder) { "请先开始轨迹记录" }
        showTrackSnapshot("轨迹已结束", recorder.stop())
    }

    private fun demoClearTrack() = startSync("清除轨迹") {
        releaseTrackRecorder()
        showSuccess("轨迹已清除", "仅移除轨迹组件创建的折线与当前位置 Marker")
    }

    private fun releaseTrackRecorder() {
        trackRecorder?.destroy()
        trackRecorder = null
    }

    private fun MapController.clearDemoOverlays() {
        if (embeddedNavigationActive) {
            mapSession?.embeddedNavigator?.stop()
            embeddedNavigationActive = false
        }
        releaseTrackRecorder()
        clearOverlays()
    }

    private fun showTrackState(snapshot: TrackSnapshot) {
        when (snapshot.state) {
            TrackState.IDLE -> showInfo("轨迹已清除", "等待开始")
            TrackState.RECORDING -> showTrackSnapshot("轨迹记录中", snapshot)
            TrackState.PAUSED -> showTrackSnapshot("轨迹已暂停", snapshot)
            TrackState.STOPPED -> showTrackSnapshot("轨迹已结束", snapshot)
        }
    }

    private fun showTrackSnapshot(title: String, snapshot: TrackSnapshot) {
        showSuccess(
            "$title · ${snapshot.points.size} 点",
            "${formatTrackDistance(snapshot.distanceMeters)} · " +
                "${formatDuration((snapshot.elapsedTimeMillis / 1_000).toInt())} · " +
                "过滤 ${snapshot.rejectedPointCount} 点",
        )
    }

    private fun formatTrackDistance(meters: Double): String = when {
        meters < 1_000.0 -> "%.0f m".format(Locale.CHINA, meters)
        else -> "%.2f km".format(Locale.CHINA, meters / 1_000.0)
    }

    private fun demoGeocode() = startAsync("地址解析") { epoch ->
        val geocoder = requireNotNull(mapSession?.geocoder) { "当前厂商不支持地理编码" }
        requestScope.replace("geocode", geocoder.geocode(
            GeocodeRequest(address = "天安门", city = "北京"),
            MapCallback { result ->
                deliver(epoch) {
                    when (result) {
                        is MapResult.Success -> {
                            mapController?.apply {
                                clearDemoOverlays()
                                addMarker(MarkerOptions(result.data.location, "天安门", result.data.formattedAddress))
                                moveCamera(CameraUpdate(target = result.data.location, zoom = 17f))
                            }
                            showSuccess("地址解析成功", result.data.formattedAddress ?: result.data.location.toString())
                        }
                        is MapResult.Failure -> showMapError("地址解析失败", result.error)
                    }
                }
            },
        ))
    }

    private fun demoReverseGeocode() = startAsync("逆地理") { epoch ->
        val point = operationCenter()
        val geocoder = requireNotNull(mapSession?.geocoder) { "当前厂商不支持逆地理编码" }
        requestScope.replace("reverseGeocode", geocoder.reverseGeocode(
            point,
            MapCallback { result ->
                deliver(epoch) {
                    when (result) {
                        is MapResult.Success -> showSuccess(
                            "逆地理成功",
                            "${result.data.formattedAddress} · 周边 ${result.data.pois.size} 个 POI",
                        )
                        is MapResult.Failure -> showMapError("逆地理失败", result.error)
                    }
                }
            },
        ))
    }

    private fun demoPoiSearch() = startAsync("周边 POI") { epoch ->
        val center = operationCenter()
        val searcher = requireNotNull(mapSession?.poiSearcher) { "当前厂商不支持 POI 搜索" }
        requestScope.replace("poi", searcher.search(
            PoiSearchRequest(
                type = PoiSearchType.NEARBY,
                keyword = "咖啡",
                center = center,
                radiusMeters = 2_000,
                pageSize = 15,
                sort = PoiSort.DISTANCE,
            ),
            MapCallback { result ->
                deliver(epoch) {
                    when (result) {
                        is MapResult.Success -> showPoiResult(center, result.data)
                        is MapResult.Failure -> showMapError("POI 搜索失败", result.error)
                    }
                }
            },
        ))
    }

    private fun showPoiResult(center: LatLng, result: PoiSearchResult) {
        val items = result.items.take(12)
        mapController?.apply {
            clearDemoOverlays()
            addMarker(MarkerOptions(center, "搜索中心", zIndex = 30f))
            items.forEach { poi ->
                addMarker(MarkerOptions(poi.location, poi.name, poi.address, zIndex = 10f))
            }
            fitPoints(listOf(center) + items.map { it.location }, paddingPixels = dp(56))
        }
        val names = items.take(3).joinToString("、") { it.name }
        showSuccess("POI 搜索成功 · ${result.items.size}/${result.totalCount}", names.ifBlank { "无可展示名称" })
    }

    private fun demoDistrict() = startAsync("行政区搜索") { epoch ->
        val searcher = requireNotNull(mapSession?.districtSearcher) { "当前厂商不支持行政区搜索" }
        requestScope.replace("district", searcher.search(
            DistrictSearchRequest(keyword = "海淀区", city = "北京", showBoundary = true),
            MapCallback { result ->
                deliver(epoch) {
                    when (result) {
                        is MapResult.Success -> showDistrictResult(result.data)
                        is MapResult.Failure -> showMapError("行政区搜索失败", result.error)
                    }
                }
            },
        ))
    }

    private fun showDistrictResult(result: DistrictSearchResult) {
        val district = result.districts.firstOrNull()
        if (district == null) {
            showError("行政区搜索失败", "厂商返回成功但没有行政区数据")
            return
        }
        val rings = district.boundaries.filter { it.size >= 3 }
        val boundaryPoints = rings.flatten()
        mapController?.apply {
            clearDemoOverlays()
            rings.forEach { ring ->
                addPolygon(
                    PolygonOptions(
                        points = ring,
                        strokeWidth = 6f,
                        strokeColor = COLOR_ACCENT,
                        fillColor = Color.argb(42, 25, 118, 210),
                        zIndex = 5f,
                    ),
                )
            }
            when {
                boundaryPoints.isNotEmpty() -> fitPoints(boundaryPoints, paddingPixels = dp(56))
                district.center != null -> moveCamera(CameraUpdate(target = district.center, zoom = 12f))
            }
        }
        val code = district.adCode ?: district.cityCode ?: "无行政区编码"
        showSuccess(
            "行政区搜索成功 · ${district.name}",
            "$code · ${rings.size} 条边界 · ${district.children.size} 个下级行政区",
        )
    }

    private fun demoWeatherNow() = startAsync("实时天气") { epoch ->
        val service = requireNotNull(mapSession?.weatherService) { "当前厂商不支持天气服务" }
        requestScope.replace("weatherNow", service.current(
            demoWeatherRequest(),
            MapCallback { result ->
                deliver(epoch) {
                    when (result) {
                        is MapResult.Success -> showWeatherNow(result.data)
                        is MapResult.Failure -> showMapError("实时天气失败", result.error)
                    }
                }
            },
        ))
    }

    private fun showWeatherNow(weather: WeatherNow) {
        val temperature = weather.temperatureC?.let { "${it.toInt()} °C" } ?: "温度未知"
        val condition = weather.condition?.takeIf(String::isNotBlank) ?: "天气未知"
        val humidity = weather.humidityPercent?.let { "湿度 ${it.toInt()}%" } ?: "湿度未知"
        val wind = listOfNotNull(weather.windDirection, weather.windPower)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .ifBlank { "风况未知" }
        showSuccess("${weather.city} · $condition · $temperature", "$humidity · $wind · ${weather.reportTime.orEmpty()}")
    }

    private fun demoWeatherForecast() = startAsync("天气预报") { epoch ->
        val service = requireNotNull(mapSession?.weatherService) { "当前厂商不支持天气服务" }
        requestScope.replace("weatherForecast", service.forecast(
            demoWeatherRequest(),
            MapCallback { result ->
                deliver(epoch) {
                    when (result) {
                        is MapResult.Success -> showWeatherForecast(result.data)
                        is MapResult.Failure -> showMapError("天气预报失败", result.error)
                    }
                }
            },
        ))
    }

    private fun showWeatherForecast(forecast: WeatherForecast) {
        val summary = forecast.days.take(3).joinToString("；") { day ->
            val condition = day.dayCondition ?: day.nightCondition ?: "天气未知"
            val high = day.dayTemperatureC?.toInt()?.toString() ?: "?"
            val low = day.nightTemperatureC?.toInt()?.toString() ?: "?"
            "${day.date} $condition $low~$high °C"
        }
        showSuccess(
            "${forecast.city}天气预报 · ${forecast.days.size} 天",
            summary.ifBlank { "厂商未返回逐日预报" },
        )
    }

    private fun demoWeatherRequest() = WeatherRequest(city = "北京", adCode = "110100")

    private fun showRouteNavigationDialog() {
        if (!ensurePrivacyConsentForAction()) return
        val modes = listOf(
            TravelMode.DRIVING,
            TravelMode.WALKING,
            TravelMode.BICYCLE,
            TravelMode.ELECTRIC_BICYCLE,
            TravelMode.TRANSIT,
        )
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
        }
        panel.addView(dialogLabel("出行方式"))
        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                modes.map { it.displayName() },
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(modes.indexOf(selectedRouteMode).coerceAtLeast(0))
        }
        panel.addView(modeSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        panel.addView(dialogLabel("导航模式"))
        val navigationModes = listOf(
            EmbeddedNavigationMode.REAL,
            EmbeddedNavigationMode.SIMULATED,
        )
        val navigationModeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                navigationModes.map { it.displayName() },
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(navigationModes.indexOf(selectedNavigationMode).coerceAtLeast(0))
        }
        panel.addView(
            navigationModeSpinner,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)),
        )
        panel.addView(
            TextView(this).apply {
                text = getString(R.string.route_navigation_summary)
                textSize = 13f
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(8), 0, dp(4))
            },
        )
        panel.addView(
            actionStrip(
                "暂停导航" to ::pauseEmbeddedNavigation,
                "继续导航" to ::resumeEmbeddedNavigation,
                "停止导航" to ::stopEmbeddedNavigation,
            ),
        )
        AlertDialog.Builder(this)
            .setTitle("路线与导航")
            .setView(panel)
            .setNegativeButton("取消", null)
            .setNeutralButton("发起导航") { _, _ ->
                selectedRouteMode = modes[modeSpinner.selectedItemPosition]
                selectedNavigationMode = navigationModes[navigationModeSpinner.selectedItemPosition]
                demoStartNavigation(selectedRouteMode)
            }
            .setPositiveButton("规划路线") { _, _ ->
                selectedRouteMode = modes[modeSpinner.selectedItemPosition]
                demoRoute(selectedRouteMode)
            }
            .show()
    }

    private fun showMapSettingsDialog() {
        if (!ensurePrivacyConsentForAction()) return
        val mapTypes = mapController
            ?.supportedMapTypes()
            ?.let { supported -> MapType.entries.filter(supported::contains) }
            ?.ifEmpty { listOf(MapType.NORMAL) }
            ?: listOf(MapType.NORMAL)
        val draft = mapSettings.copy(mapType = mapController?.getMapType() ?: mapSettings.mapType)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
        }
        panel.addView(dialogLabel("地图类型"))
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                mapTypes.map { it.displayName() },
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(mapTypes.indexOf(draft.mapType).coerceAtLeast(0))
        }
        panel.addView(typeSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        panel.addView(settingSwitch("实时交通", draft.trafficEnabled) { draft.trafficEnabled = it })
        panel.addView(settingSwitch("3D 建筑", draft.buildingsEnabled) { draft.buildingsEnabled = it })
        panel.addView(settingSwitch("室内地图", draft.indoorEnabled) { draft.indoorEnabled = it })
        panel.addView(settingSwitch("底图 POI", draft.mapPoiEnabled) { draft.mapPoiEnabled = it })
        panel.addView(settingSwitch("指南针", draft.compassEnabled) { draft.compassEnabled = it })
        panel.addView(settingSwitch("缩放控件", draft.zoomControlsEnabled) { draft.zoomControlsEnabled = it })
        AlertDialog.Builder(this)
            .setTitle("地图基础设置")
            .setView(panel)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                draft.mapType = mapTypes[typeSpinner.selectedItemPosition]
                applyMapSettings(draft)
            }
            .show()
    }

    private fun applyMapSettings(settings: DemoMapSettings) = startSync("应用地图设置") {
        val controller = requireNotNull(mapController) { "地图尚未初始化" }
        val appliedType = when (val result = controller.applyMapType(settings.mapType)) {
            is MapResult.Success -> result.data
            is MapResult.Failure -> {
                showMapError("地图类型应用失败", result.error)
                return@startSync
            }
        }
        controller.setTrafficEnabled(settings.trafficEnabled)
        controller.setBuildingsEnabled(settings.buildingsEnabled)
        controller.setIndoorEnabled(settings.indoorEnabled)
        controller.setMapPoiEnabled(settings.mapPoiEnabled)
        controller.setUiOptions(
            MapUiOptions(
                zoomControlsEnabled = settings.zoomControlsEnabled,
                scaleControlsEnabled = true,
                compassEnabled = settings.compassEnabled,
            ),
        )
        mapSettings = settings.copy(mapType = appliedType)
        val toggles = listOfNotNull(
            "交通".takeIf { settings.trafficEnabled },
            "建筑".takeIf { settings.buildingsEnabled },
            "室内".takeIf { settings.indoorEnabled },
            "POI".takeIf { settings.mapPoiEnabled },
            "指南针".takeIf { settings.compassEnabled },
        ).joinToString("、").ifBlank { "基础图层均关闭" }
        showSuccess("地图设置已应用 · ${appliedType.displayName()}", toggles)
    }

    private fun dialogLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(COLOR_MUTED)
        setPadding(0, dp(8), 0, 0)
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun settingSwitch(text: String, checked: Boolean, onChanged: (Boolean) -> Unit) =
        Switch(this).apply {
            this.text = text
            isChecked = checked
            textSize = 14f
            setTextColor(COLOR_TEXT)
            setPadding(0, 0, 0, 0)
            setOnCheckedChangeListener { _, value -> onChanged(value) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        }

    private fun demoRoute(mode: TravelMode) = startAsync("${mode.displayName()}路线") { epoch ->
        val origin = operationCenter()
        val destination = routeDestination(origin)
        val planner = requireNotNull(mapSession?.routePlanner) { "当前厂商不支持路线规划" }
        if (!planner.supportsMode(mode)) {
            showMapError(
                "${mode.displayName()}路线不支持",
                MapError(ErrorType.UNSUPPORTED, "${currentProvider?.provider?.displayName()}原生 SDK 不支持该方式"),
            )
            return@startAsync
        }
        val request = RouteRequest(
            mode = mode,
            origin = origin,
            destination = destination,
            city = "北京".takeIf { mode.canonical() == TravelMode.TRANSIT },
        )
        requestScope.replace("route", planner.plan(
            request,
            MapCallback { result ->
                deliver(epoch) {
                    when (result) {
                        is MapResult.Success -> showRouteResult(request, result.data)
                        is MapResult.Failure -> showMapError("路线规划失败", result.error)
                    }
                }
            },
        ))
    }

    private fun showRouteResult(request: RouteRequest, result: RouteResult) {
        val path = result.paths.firstOrNull()
        if (path == null) {
            showError("路线规划失败", "厂商返回成功但没有路线")
            return
        }
        val drawError = runCatching {
            mapController?.apply {
                clearDemoOverlays()
                addRoute(request, path, demoRouteOverlayOptions())
                fitPoints(
                    path.polyline.ifEmpty { path.steps.flatMap { it.polyline } },
                    paddingPixels = dp(56),
                )
            }
        }.exceptionOrNull()
        if (drawError != null) {
            showError("路线绘制失败", drawError.message ?: drawError::class.java.simpleName)
            return
        }
        showSuccess(
            "${result.mode.displayName()}路线成功 · ${result.paths.size} 条方案",
            "${formatDistance(path.distanceMeters)} · ${formatDuration(path.durationSeconds)} · ${path.steps.size} 个步骤",
        )
    }

    private fun startEmbeddedNavigation(mode: TravelMode) = startSync("发起${mode.displayName()}导航") {
        val session = requireNotNull(mapSession) { "地图尚未初始化" }
        val navigator = requireNotNull(session.embeddedNavigator) {
            "当前厂商必须同时支持路线规划和连续定位"
        }
        session.routePlanner?.let { planner ->
            if (!planner.supportsMode(mode)) {
                error("${currentProvider?.provider?.displayName()}不支持${mode.displayName()}导航")
            }
        }
        mapSession?.locationDisplay?.stop()
        releaseTrackRecorder()
        val navigationMode = selectedNavigationMode
        val origin = operationCenter()
        val request = EmbeddedNavigationRequest(
            routeRequest = RouteRequest(
                mode = mode,
                origin = origin,
                destination = routeDestination(origin),
                city = "北京".takeIf { mode.canonical() == TravelMode.TRANSIT },
            ),
            options = EmbeddedNavigationOptions(
                routeOverlay = demoRouteOverlayOptions(),
                showCurrentMarker = true,
                currentMarkerIcon = MarkerIcon.Bytes(routeEndpointIconBytes("我", COLOR_CURRENT)),
                followLocation = true,
                followZoom = 17f,
                navigationMode = navigationMode,
                simulationSpeedMetersPerSecond = mode.simulationSpeedMetersPerSecond(),
                simulationIntervalMillis = 500L,
            ),
        )
        when (val result = navigator.start(request) { event ->
            deliver(providerEpoch) { showEmbeddedNavigationEvent(mode, navigationMode, event) }
        }) {
            is MapResult.Success -> {
                embeddedNavigationActive = true
                showInfo(
                    "${mode.displayName()}${navigationMode.displayName()}规划中",
                    "路线将在当前地图内展示",
                )
            }
            is MapResult.Failure -> showMapError("${mode.displayName()}导航启动失败", result.error)
        }
    }

    private fun pauseEmbeddedNavigation() = startSync("暂停导航") {
        when (val result = requireNotNull(mapSession?.embeddedNavigator).pause()) {
            is MapResult.Success -> showInfo("导航已暂停", "导航推进已停止")
            is MapResult.Failure -> showMapError("暂停导航失败", result.error)
        }
    }

    private fun resumeEmbeddedNavigation() = startSync("继续导航") {
        when (val result = requireNotNull(mapSession?.embeddedNavigator).resume()) {
            is MapResult.Success -> showInfo("导航已继续", "正在恢复导航推进")
            is MapResult.Failure -> showMapError("继续导航失败", result.error)
        }
    }

    private fun stopEmbeddedNavigation() = startSync("停止导航") {
        when (val result = requireNotNull(mapSession?.embeddedNavigator).stop()) {
            is MapResult.Success -> {
                embeddedNavigationActive = false
                showInfo("导航已停止", "已移除导航路线和当前位置覆盖物")
            }
            is MapResult.Failure -> showMapError("停止导航失败", result.error)
        }
    }

    private fun showEmbeddedNavigationEvent(
        mode: TravelMode,
        navigationMode: EmbeddedNavigationMode,
        event: EmbeddedNavigationEvent,
    ) {
        when (event) {
            is EmbeddedNavigationEvent.StateChanged -> {
                if (event.state in setOf(
                        EmbeddedNavigationState.ARRIVED,
                        EmbeddedNavigationState.STOPPED,
                        EmbeddedNavigationState.FAILED,
                        EmbeddedNavigationState.DESTROYED,
                    )
                ) {
                    embeddedNavigationActive = false
                }
                val detail = when (event.state) {
                    EmbeddedNavigationState.PLANNING -> "正在规划路线"
                    EmbeddedNavigationState.NAVIGATING -> when (navigationMode) {
                        EmbeddedNavigationMode.REAL -> "正在跟随真实定位"
                        EmbeddedNavigationMode.SIMULATED -> "正在沿规划路线模拟推进"
                    }
                    EmbeddedNavigationState.PAUSED -> "导航推进已暂停"
                    EmbeddedNavigationState.REROUTING -> "偏航，正在重新规划"
                    EmbeddedNavigationState.ARRIVED -> "已到达目的地"
                    EmbeddedNavigationState.STOPPED -> "导航已停止"
                    EmbeddedNavigationState.FAILED -> "导航失败"
                    EmbeddedNavigationState.IDLE -> "等待开始"
                    EmbeddedNavigationState.DESTROYED -> "会话已释放"
                }
                showInfo("${mode.displayName()}${navigationMode.displayName()} · ${event.state}", detail)
            }
            is EmbeddedNavigationEvent.RouteReady -> {
                val path = event.selectedPath
                mapController?.fitPoints(
                    path.polyline.ifEmpty { path.steps.flatMap { it.polyline } },
                    paddingPixels = dp(56),
                )
                showSuccess(
                    "${mode.displayName()}导航路线已就绪",
                    "${formatDistance(path.distanceMeters)} · ${formatDuration(path.durationSeconds)}",
                )
            }
            is EmbeddedNavigationEvent.Progress -> {
                val progress = event.value
                showInfo(
                    "${mode.displayName()}导航进行中",
                    "${progress.navigationMode.displayName()} · " +
                        "剩余 ${formatDistance(progress.remainingDistanceMeters.toInt())} · " +
                        "${formatDuration(progress.remainingDurationSeconds)} · " +
                        (progress.currentInstruction ?: "步骤 ${progress.currentStepIndex + 1}"),
                )
            }
            is EmbeddedNavigationEvent.RerouteStarted -> showInfo("导航偏航", "正在从当前位置重新规划")
            is EmbeddedNavigationEvent.Arrived -> showSuccess("导航已到达", "已抵达目的地")
            is EmbeddedNavigationEvent.Error -> showMapError("导航错误", event.error)
        }
    }

    private fun demoRouteOverlayOptions(): RouteOverlayOptions = RouteOverlayOptions(
        startMarker = RouteMarkerOptions(
            icon = MarkerIcon.Bytes(routeEndpointIconBytes("起", COLOR_START)),
            title = "起点",
            anchorU = 0.5f,
            anchorV = 0.5f,
            zIndex = 101f,
        ),
        endMarker = RouteMarkerOptions(
            icon = MarkerIcon.Bytes(routeEndpointIconBytes("终", COLOR_END)),
            title = "终点",
            anchorU = 0.5f,
            anchorV = 0.5f,
            zIndex = 101f,
        ),
        lineWidth = 14f,
        lineColor = COLOR_ROUTE,
        lineClickable = true,
        lineZIndex = 100f,
    )

    private fun routeEndpointIconBytes(label: String, color: Int): ByteArray {
        val bitmap = createBitmap(84, 84, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawCircle(42f, 42f, 38f, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        canvas.drawCircle(42f, 42f, 35f, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 34f
        canvas.drawText(label, 42f, 54f, paint)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun demoSnapshot() = startAsync("地图截图") { epoch ->
        requestScope.replace("snapshot", requireNotNull(mapController) { "地图尚未初始化" }.snapshot(
            MapCallback { result ->
                deliver(epoch) {
                    when (result) {
                        is MapResult.Success -> showSuccess(
                            "截图成功 · ${result.data.width} x ${result.data.height}",
                            "PNG ${result.data.pngBytes.size / 1024} KB",
                        )
                        is MapResult.Failure -> showMapError("截图失败", result.error)
                    }
                }
            },
        ))
    }

    private fun demoMarker() = replaceOverlay("Marker", 16f) { center, _, _, _ ->
        addMarker(MarkerOptions(center, "Map Fusion", "统一 Marker", draggable = true))
    }

    private fun demoMultiPoint() = startSync("海量点") {
        val controller = requireNotNull(mapController) { "地图尚未初始化" }
        val center = operationCenter()
        val side = 25
        val spacing = 0.001
        val items = List(side * side) { index ->
            val row = index / side
            val column = index % side
            MultiPointItem(
                id = "demo-$index",
                position = LatLng(
                    latitude = center.latitude + (row - side / 2) * spacing,
                    longitude = center.longitude + (column - side / 2) * spacing,
                    coordType = center.coordType,
                ),
                title = "海量点 ${index + 1}",
                tag = index,
            )
        }
        controller.clearDemoOverlays()
        controller.setOnMultiPointClickListener { overlay, item ->
            showSuccess(item.title ?: item.id, "id=${item.id} · 图层=${overlay.id}")
            true
        }
        controller.addMultiPointOverlay(
            MultiPointOverlayOptions(
                items = items,
                icon = MarkerIcon.Bytes(demoMultiPointIconBytes()),
                anchorU = 0.5f,
                anchorV = 0.5f,
                tag = "demo-multipoint",
            ),
        )
        controller.fitPoints(items.map(MultiPointItem::position), paddingPixels = dp(40))
        showSuccess("海量点已显示", "通过厂商原生图层一次提交 ${items.size} 个点，点击点位可读取统一 tag")
    }

    private fun demoPolyline() = replaceOverlay("折线", 14f) { center, east, _, west ->
        addPolyline(
            PolylineOptions(listOf(west, center, east), width = 14f, color = COLOR_ROUTE, clickable = true),
        )
    }

    private fun demoPolygon() = replaceOverlay("多边形", 14f) { center, east, north, _ ->
        addPolygon(
            PolygonOptions(
                listOf(center, east, north),
                strokeColor = COLOR_GREEN,
                fillColor = Color.argb(72, 46, 125, 50),
                clickable = true,
            ),
        )
    }

    private fun demoCircle() = replaceOverlay("圆", 14f) { _, _, _, west ->
        addCircle(
            CircleOptions(
                west,
                500.0,
                strokeColor = COLOR_ORANGE,
                fillColor = Color.argb(66, 239, 108, 0),
                clickable = true,
            ),
        )
    }

    private fun demoText() = replaceOverlay("文字", 16f) { center, _, _, _ ->
        addText(
            TextOverlayOptions(
                text = "Map Fusion",
                position = center,
                fontSizePixels = 38,
                fontColor = Color.WHITE,
                backgroundColor = COLOR_ACCENT,
                zIndex = 20f,
            ),
        )
    }

    private fun demoGroundOverlay() = replaceOverlay("地面图", 16f) { center, _, _, _ ->
        addGroundOverlay(
            GroundOverlayOptions(
                image = MapImage.Bytes(demoGroundImageBytes()),
                position = center,
                widthMeters = 650f,
                heightMeters = 320f,
                transparency = 0.08f,
                zIndex = 3f,
            ),
        )
    }

    private fun demoHeatMap() = replaceOverlay("热力图", 14f) { center, east, north, west ->
        addHeatMap(
            HeatMapOptions(
                listOf(
                    HeatPoint(center, 1.0),
                    HeatPoint(east, 0.8),
                    HeatPoint(north, 0.7),
                    HeatPoint(west, 0.6),
                ),
                radiusPixels = 30,
                opacity = 0.62f,
                zIndex = 1f,
            ),
        )
    }

    private fun replaceOverlay(
        name: String,
        zoom: Float,
        draw: MapController.(LatLng, LatLng, LatLng, LatLng) -> Unit,
    ) = startSync(name) {
        val controller = requireNotNull(mapController) { "地图尚未初始化" }
        val center = operationCenter()
        val east = LatLng(center.latitude, center.longitude + 0.012, center.coordType)
        val north = LatLng(center.latitude + 0.009, center.longitude + 0.004, center.coordType)
        val west = LatLng(center.latitude + 0.002, center.longitude - 0.010, center.coordType)
        controller.clearDemoOverlays()
        controller.moveCamera(CameraUpdate(target = center, zoom = zoom))
        controller.draw(center, east, north, west)
        showSuccess("$name 已显示", "当前地图仅保留该功能的演示覆盖物")
    }

    private fun clearMap() = startSync("清空地图") {
        requireNotNull(mapController) { "地图尚未初始化" }.clearDemoOverlays()
        showSuccess("地图已清空", "没有业务覆盖物")
    }

    private fun operationCenter(): LatLng = lastLocation?.position ?: defaultPoint()

    /** 业务默认点保存为 WGS84，显示/检索前显式转换到当前厂商坐标。 */
    private fun defaultPoint(): LatLng = defaultPoint(currentProvider?.provider)

    private fun defaultPoint(provider: Provider?): LatLng = convertForProvider(
        LatLng(39.9087, 116.3975, CoordType.WGS84),
        provider,
    )

    private fun routeDestination(origin: LatLng): LatLng = lastLocation?.let {
        LatLng(origin.latitude + 0.012, origin.longitude + 0.015, origin.coordType)
    } ?: convertForCurrentProvider(LatLng(39.9163, 116.4172, CoordType.WGS84))

    private fun convertForCurrentProvider(point: LatLng): LatLng = convertForProvider(point, currentProvider?.provider)

    private fun convertForProvider(point: LatLng, provider: Provider?): LatLng =
        when (val result = DefaultCoordinateConverter.convert(point, provider.coordType())) {
            is MapResult.Success -> result.data
            is MapResult.Failure -> error(result.error.message)
        }

    private fun Provider?.coordType(): CoordType = when (this) {
        Provider.BAIDU -> CoordType.BD09
        Provider.AMAP -> CoordType.GCJ02
        null -> CoordType.UNKNOWN
    }

    private fun demoGroundImageBytes(): ByteArray {
        val bitmap = createBitmap(360, 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.argb(235, 255, 255, 255))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ACCENT
            textSize = 42f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Map Fusion", 180f, 82f, paint)
        paint.color = Color.rgb(0, 121, 107)
        paint.textSize = 27f
        canvas.drawText("GroundOverlay", 180f, 132f, paint)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun demoMultiPointIconBytes(): ByteArray {
        val bitmap = createBitmap(36, 36, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ACCENT
            style = Paint.Style.FILL
        }
        canvas.drawCircle(18f, 18f, 13f, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        canvas.drawCircle(18f, 18f, 11f, paint)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun startAsync(action: String, block: (Int) -> Unit) {
        if (!ensurePrivacyConsentForAction()) return
        val epoch = providerEpoch
        showInfo("$action 请求中", currentProvider?.provider?.displayName().orEmpty())
        runCatching { block(epoch) }.onFailure { error ->
            Log.e(TAG, "$action 发起失败", error)
            showError("$action 发起失败", error.message ?: error::class.java.simpleName)
        }
    }

    private fun startSync(action: String, block: () -> Unit) {
        if (!ensurePrivacyConsentForAction()) return
        runCatching(block).onFailure { error ->
            Log.e(TAG, "$action 失败", error)
            showError("$action 失败", error.message ?: error::class.java.simpleName)
        }
    }

    private fun deliver(epoch: Int, block: () -> Unit) = runOnUiThread {
        if (epoch == providerEpoch && !isFinishing && !isDestroyed) block()
    }

    private fun showMapError(title: String, error: MapError) {
        val code = error.rawCode?.let { " · code=$it" }.orEmpty()
        val raw = error.rawMessage?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
        Log.e(TAG, "$title [${error.type}]${error.message}$code$raw", error.cause)
        showError("$title · ${error.type}$code", "${error.message}$raw")
    }

    private fun showInfo(title: String, detail: String) = showResult(title, detail, COLOR_ACCENT)
    private fun showSuccess(title: String, detail: String) = showResult(title, detail, COLOR_SUCCESS)
    private fun showError(title: String, detail: String) = showResult(title, detail, COLOR_ERROR)

    private fun showResult(title: String, detail: String, accent: Int) {
        resultView.text = getString(R.string.result_message, title, detail)
        resultView.setTextColor(COLOR_TEXT)
        resultView.background = panelBackground(Color.WHITE, accent)
    }

    private fun updateProviderHeader(provider: Provider, capabilityCount: Int) {
        providerStatusView.text = getString(
            R.string.provider_capability_summary,
            provider.displayName(),
            capabilityCount,
        )
        val selected = provider == Provider.BAIDU
        styleProviderButton(baiduButton, selected)
        styleProviderButton(amapButton, !selected)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(COLOR_MUTED)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(5), 0, dp(2))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(25))
    }

    private fun actionStrip(vararg actions: Pair<String, () -> Unit>): HorizontalScrollView =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    actions.forEachIndexed { index, (label, action) ->
                        addView(
                            actionButton(label, action),
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply {
                                if (index > 0) marginStart = dp(6)
                            },
                        )
                    }
                },
            )
        }

    private fun actionButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        minWidth = dp(82)
        minimumHeight = 0
        minHeight = 0
        setPadding(dp(14), 0, dp(14), 0)
        setTextColor(COLOR_TEXT)
        backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun providerButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        minWidth = 0
        minimumWidth = 0
        minimumHeight = 0
        minHeight = 0
        stateListAnimator = null
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { onClick() }
    }

    private fun styleProviderButton(button: Button, selected: Boolean) {
        button.setTextColor(if (selected) Color.WHITE else COLOR_TEXT)
        button.background = panelBackground(if (selected) COLOR_ACCENT else Color.WHITE, COLOR_BORDER)
    }

    private fun panelBackground(fillColor: Int, strokeColor: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(6).toFloat()
        setColor(fillColor)
        setStroke(dp(1), strokeColor)
    }

    private fun Provider.displayName(): String = when (this) {
        Provider.BAIDU -> "百度"
        Provider.AMAP -> "高德"
    }

    private fun MapType.displayName(): String = when (this) {
        MapType.NORMAL -> "普通"
        MapType.SATELLITE -> "卫星"
        MapType.NIGHT -> "夜间"
        MapType.NAVIGATION -> "导航"
        MapType.NONE -> "无底图"
    }

    private fun TravelMode.displayName(): String = when (canonical()) {
        TravelMode.DRIVING -> "驾车"
        TravelMode.WALKING -> "步行"
        TravelMode.BICYCLE -> "自行车"
        TravelMode.ELECTRIC_BICYCLE -> "电动车"
        TravelMode.TRANSIT -> "公交"
        TravelMode.RIDING -> "自行车"
    }

    private fun EmbeddedNavigationMode.displayName(): String = when (this) {
        EmbeddedNavigationMode.REAL -> "真实导航（GPS）"
        EmbeddedNavigationMode.SIMULATED -> "模拟导航"
    }

    private fun TravelMode.simulationSpeedMetersPerSecond(): Float = when (canonical()) {
        TravelMode.DRIVING -> 16.7f
        TravelMode.WALKING -> 1.4f
        TravelMode.BICYCLE -> 5.0f
        TravelMode.ELECTRIC_BICYCLE -> 6.9f
        TravelMode.TRANSIT -> 12.0f
        TravelMode.RIDING -> 5.0f
    }

    private fun formatDistance(meters: Int): String =
        if (meters < 1_000) "$meters m" else "%.1f km".format(Locale.CHINA, meters / 1_000.0)

    private fun formatDuration(seconds: Int): String = when {
        seconds < 60 -> "$seconds 秒"
        seconds < 3_600 -> "${seconds / 60} 分钟"
        else -> "${seconds / 3_600} 小时 ${seconds % 3_600 / 60} 分钟"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        activityResumed = true
        mapSession?.onResume()
        val action = pendingLocationAction
        if (action != null && !locationPermissionRequestInFlight && hasLocationPermission()) {
            pendingLocationAction = null
            mapContainer.post { executeLocationAction(action) }
        }
    }

    override fun onPause() {
        mapSession?.onPause()
        activityResumed = false
        super.onPause()
    }

    override fun onLowMemory() {
        mapSession?.onLowMemory()
        super.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentProvider?.provider?.let { outState.putString(STATE_PROVIDER, it.name) }
        outState.putString(STATE_PREFERRED_PROVIDER, preferredProvider.name)
        pendingLocationAction?.let { outState.putString(STATE_PENDING_LOCATION_ACTION, it.name) }
        outState.putString(STATE_ROUTE_MODE, selectedRouteMode.name)
        outState.putString(STATE_NAVIGATION_MODE, selectedNavigationMode.name)
        outState.putBoolean(STATE_LOCATION_PERMISSION_REQUEST_IN_FLIGHT, locationPermissionRequestInFlight)
        outState.putBoolean(STATE_LOCATION_PERMISSION_DENIED, locationPermissionDenied)
        outState.putBoolean(STATE_LOCATION_PERMISSION_PERMANENTLY_DENIED, locationPermissionPermanentlyDenied)
        val mapState = Bundle()
        mapSession?.onSaveInstanceState(mapState)
        outState.putBundle(STATE_MAP, mapState)
    }

    override fun onDestroy() {
        providerEpoch++
        requestScope.close()
        privacyDialog?.dismiss()
        privacyDialog = null
        locationSettingsDialog?.dismiss()
        locationSettingsDialog = null
        val session = mapSession
        mapSession = null
        mapController = null
        currentProvider = null
        embeddedNavigationActive = false
        runCatching(::releaseTrackRecorder).onFailure { error ->
            Log.w(TAG, "销毁轨迹失败", error)
        }
        runCatching { session?.destroy() }.onFailure { error ->
            Log.w(TAG, "销毁地图会话失败", error)
        }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MapFusionDemo"
        const val STATE_PROVIDER = "mapfusion.provider"
        const val STATE_PREFERRED_PROVIDER = "mapfusion.preferred_provider"
        const val STATE_MAP = "mapfusion.map_state"
        const val STATE_PENDING_LOCATION_ACTION = "mapfusion.pending_location_action"
        const val STATE_ROUTE_MODE = "mapfusion.route_mode"
        const val STATE_NAVIGATION_MODE = "mapfusion.navigation_mode"
        const val STATE_LOCATION_PERMISSION_REQUEST_IN_FLIGHT = "mapfusion.location_permission_in_flight"
        const val STATE_LOCATION_PERMISSION_DENIED = "mapfusion.location_permission_denied"
        const val STATE_LOCATION_PERMISSION_PERMANENTLY_DENIED = "mapfusion.location_permission_permanently_denied"
        const val PRIVACY_PREFERENCES = "mapfusion.demo.privacy"
        const val PRIVACY_DECISION_KEY = "privacy_decision"
        const val PRIVACY_NOTICE =
            "Map Fusion Demo 使用百度地图和高德地图服务。只有在你点击“同意并启用”后，" +
                "应用才会初始化当前选择的地图厂商 SDK。\n\n" +
                "地图展示、定位、路线规划、POI、天气、内嵌导航和轨迹功能可能处理精确或大致位置信息、" +
                "设备与网络信息及服务日志，并由当前选择的地图厂商按其隐私政策处理。" +
                "位置权限仍会由 Android 系统另行询问。\n\n" +
                "不同意不会申请位置权限，也不会初始化地图 SDK。你可以随时通过顶部“隐私设置”撤回同意；" +
                "撤回后应用会立即销毁地图会话并停止定位、导航和轨迹处理。"
        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val COLOR_PAGE = Color.rgb(244, 246, 248)
        val COLOR_PANEL = Color.rgb(250, 251, 252)
        val COLOR_BORDER = Color.rgb(216, 221, 226)
        val COLOR_TEXT = Color.rgb(35, 39, 43)
        val COLOR_MUTED = Color.rgb(99, 107, 116)
        val COLOR_ACCENT = Color.rgb(0, 121, 107)
        val COLOR_SUCCESS = Color.rgb(46, 125, 50)
        val COLOR_ERROR = Color.rgb(198, 40, 40)
        val COLOR_ROUTE = Color.rgb(25, 118, 210)
        val COLOR_GREEN = Color.rgb(46, 125, 50)
        val COLOR_ORANGE = Color.rgb(239, 108, 0)
        val COLOR_START = Color.rgb(46, 125, 50)
        val COLOR_END = Color.rgb(198, 40, 40)
        val COLOR_CURRENT = Color.rgb(0, 121, 107)
    }
}
