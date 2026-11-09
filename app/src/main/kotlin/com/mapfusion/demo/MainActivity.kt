package com.mapfusion.demo

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.EmbeddedNavigationMode
import com.mapfusion.api.model.TravelMode
import com.mapfusion.demo.data.DemoConsentStore
import com.mapfusion.demo.data.PrivacyDecision
import com.mapfusion.demo.main.MainActionDispatcher
import com.mapfusion.demo.main.MainDialogOwner
import com.mapfusion.demo.main.MainFeatures
import com.mapfusion.demo.main.MainLocationPermissionCoordinator
import com.mapfusion.demo.main.MainMapDialogs
import com.mapfusion.demo.main.MainMapHost
import com.mapfusion.demo.main.MainPrivacyController
import com.mapfusion.demo.main.MainRouteDialog
import com.mapfusion.demo.main.MainScreen
import com.mapfusion.demo.main.displayName
import com.mapfusion.demo.presentation.main.LocationPermissionAction
import com.mapfusion.demo.presentation.main.MainViewModel
import kotlinx.coroutines.launch

/** 首页 Android 宿主：装配状态、视图和页面级功能，转发生命周期，不实现地图演示业务。 */
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val consentStore by lazy { DemoConsentStore(this) }
    private lateinit var screen: MainScreen
    private lateinit var mapHost: MainMapHost
    private lateinit var features: MainFeatures
    private lateinit var privacy: MainPrivacyController
    private lateinit var permissions: MainLocationPermissionCoordinator
    private lateinit var dialogOwner: MainDialogOwner
    private lateinit var actions: MainActionDispatcher
    private var pendingMapState: Bundle? = null

    /** 恢复业务选择并装配页面；未同意前只显示本地占位，不创建厂商会话。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val supported = Provider.entries.filter(DemoOfficialNaviSdk::supportsMapProvider).toSet()
        viewModel.configureProviders(DemoOfficialNaviSdk.defaultProvider, supported)
        viewModel.resetSessionState()
        viewModel.setPrivacyDecision(consentStore.read())
        pendingMapState = savedInstanceState?.getBundle(STATE_MAP)
        assemblePage(supported)
        setContentView(screen.root)
        screen.render(viewModel.state.value)
        collectState()
        if (privacy.hasConsent()) openPreferredMap() else {
            privacy.showUnavailable()
            if (viewModel.state.value.privacyDecision == PrivacyDecision.UNDECIDED)
                screen.mapContainer.post { privacy.showConsent(firstRun = true) }
        }
    }

    /** 只在此处建立页面依赖，功能类之间通过显式回调连接，不把资源注入 ViewModel。 */
    private fun assemblePage(supported: Set<Provider>) {
        screen = MainScreen(this, supported, ::switchProvider, viewModel::selectToolCategory,
            onAction = { actions.dispatch(it) }, onPrivacy = { privacy.showSettings() })
        mapHost = MainMapHost(this, viewModel, screen.mapContainer, consentStore) { privacy.ensureConsent() }
        features = MainFeatures(mapHost)
        dialogOwner = MainDialogOwner(this)
        val mapDialogs = MainMapDialogs(this, mapHost, dialogOwner)
        val routeDialog = MainRouteDialog(this, mapHost, dialogOwner, features.route, ::startNavigation)
        permissions = MainLocationPermissionCoordinator(this, viewModel, mapHost.messages,
            ensureConsent = { privacy.ensureConsent() }, execute = features::executeLocationAction)
        privacy = MainPrivacyController(this, viewModel, consentStore, mapHost.messages,
            ::openPreferredMap, ::disableMap, mapHost::revokeVendorConsent)
        actions = MainActionDispatcher(features, requestLocation = { permissions.request(it, fromUser = true) },
            showMapSettings = mapDialogs::settings, showRouteOptions = routeDialog::show,
            openOfficialNavigation = ::openOfficialNavigation)
        mapHost.onReady = { permissions.request(LocationPermissionAction.LOCATE, fromUser = false) }
    }

    /** 页面 STARTED 内单向收集 UiState，停止后不继续访问已不可见的控件。 */
    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect { screen.render(it) } }
        }
    }

    /** 同一厂商可消费恢复 Bundle；真实换厂商丢弃旧原生状态，避免交叉恢复。 */
    private fun switchProvider(provider: Provider) {
        if (provider != viewModel.state.value.preferredProvider) pendingMapState = null
        mapHost.switchProvider(provider)
    }

    /** 首次启动或重新同意后打开已选厂商，原生恢复数据仅消费一次。 */
    private fun openPreferredMap() {
        val savedState = pendingMapState
        pendingMapState = null
        mapHost.switchProvider(viewModel.state.value.preferredProvider, savedState)
    }

    /** 撤回时清除待续接操作和原生恢复数据，并使全部旧会话回调失效。 */
    private fun disableMap() {
        pendingMapState = null
        viewModel.cancelPendingLocationAction()
        mapHost.releaseSession()
    }

    /** 保存已提交的选择；真实轻导航先授权，模拟模式不申请系统位置权限。 */
    private fun startNavigation(mode: TravelMode, navigation: EmbeddedNavigationMode) {
        viewModel.selectRouteOptions(mode, navigation)
        if (navigation == EmbeddedNavigationMode.REAL)
            permissions.request(LocationPermissionAction.START_NAVIGATION, fromUser = true)
        else features.route.start(mode)
    }

    /** 以统一 Intent 协议进入官方导航宿主，厂商能力和隐私必须先满足。 */
    private fun openOfficialNavigation() {
        if (!privacy.ensureConsent()) return
        val provider = mapHost.provider?.provider
        if (provider == null) { mapHost.messages.error("官方导航不可用", "地图尚未初始化"); return }
        if (!DemoOfficialNaviSdk.supportsOfficialNavigation(provider)) {
            mapHost.messages.error("官方导航不可用", "当前 Demo 变体未装载${provider.displayName()} Navi 模块"); return
        }
        val origin = mapHost.coordinates.center()
        startActivity(OfficialNavigationActivity.createIntent(this, provider, origin, mapHost.coordinates.destination(origin)))
    }

    /** 回前台先复查共享隐私，再恢复地图和从系统设置返回的授权操作。 */
    override fun onResume() {
        super.onResume()
        privacy.refresh()
        mapHost.onResume()
        permissions.onResume()
    }

    /** 仅向地图宿主转发后台事件，组件内部负责各自暂停行为。 */
    override fun onPause() {
        mapHost.onPause()
        super.onPause()
    }

    /** 将 Android 内存压力转发给当前地图 Session。 */
    override fun onLowMemory() {
        if (::mapHost.isInitialized) mapHost.onLowMemory()
        super.onLowMemory()
    }

    /** 转存原生 Bundle；ViewModel 的选择由 SavedStateHandle 自行恢复。 */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::mapHost.isInitialized) outState.putBundle(STATE_MAP, mapHost.saveState())
    }

    /** 按页面所有权释放窗口、结果监听、请求和 SDK 资源，不让清理异常跳过其他释放。 */
    override fun onDestroy() {
        if (::privacy.isInitialized) closeSafely(privacy, "隐私窗口")
        if (::dialogOwner.isInitialized) closeSafely(dialogOwner, "功能窗口")
        if (::permissions.isInitialized) closeSafely(permissions, "权限协调器")
        if (::mapHost.isInitialized) closeSafely(mapHost, "地图宿主")
        if (::features.isInitialized) closeSafely(features, "地图功能")
        super.onDestroy()
    }

    /** 清理失败只记录资源类别，仍继续释放下一项并完成 Activity 销毁。 */
    private fun closeSafely(resource: AutoCloseable, name: String) {
        runCatching(resource::close).onFailure { Log.w("MapFusionDemo", "释放$name 失败", it) }
    }

    /** 保留已有原生状态键，避免本轮拆分改变 Activity 的恢复协议。 */
    private companion object { const val STATE_MAP = "mapfusion.map_state" }
}
