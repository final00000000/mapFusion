package com.mapfusion.demo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.OfficialNavigationMode
import com.mapfusion.demo.navigation.OfficialNavigationCommands
import com.mapfusion.demo.navigation.OfficialNavigationSessionHost
import com.mapfusion.demo.presentation.navigation.OfficialNavigationAction
import com.mapfusion.demo.presentation.navigation.OfficialNavigationPage
import com.mapfusion.demo.presentation.navigation.OfficialNavigationText
import com.mapfusion.demo.presentation.navigation.OfficialNavigationViewModel
import kotlinx.coroutines.launch

/**
 * 官方导航页面的 Android 连接点：解析入口、取得 ViewModel、收集状态、处理权限和页面返回，
 * 明确连接原生会话生命周期；布局、渲染、事件文案、业务归约和会话操作由独立职责承接。
 */
class OfficialNavigationActivity : ComponentActivity() {
    /** Android 持有的导航状态所有者；只保留选择和值状态，不接收页面/原生会话引用。 */
    private val viewModel: OfficialNavigationViewModel by viewModels()
    /** 当前页面 UI 宿主；null 表示未装配或已释放，onDestroy 先清引用再 close。 */
    private var page: OfficialNavigationPage? = null
    /** 当前 Activity 的原生会话所有者；null 表示无宿主，onDestroy 清引用后关闭事件桥和 SDK。 */
    private var sessionHost: OfficialNavigationSessionHost? = null
    /** 当前会话命令入口；null 表示未可用或页面已销毁，不能在重建后的页面继续复用。 */
    private var commands: OfficialNavigationCommands? = null
    /** 精确授权后的单次续接闭包；捕获会话令牌，结果消费/销毁时置 null，不能持久化。 */
    private var permissionContinuation: (() -> Unit)? = null
    /** 此 Activity 注册的系统位置权限启动器；回调只消费当前 VM 待办，不改厂商权限协议。 */
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onLocationPermissionResult() }

    /** 输入 Android 恢复状态，校验变体能力后组装页面级对象；不把原生对象放入 ViewModel。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectSystemBack()
        val provider = intent.getStringExtra(EXTRA_PROVIDER)
            ?.let { value -> runCatching { Provider.valueOf(value) }.getOrNull() }
        if (provider == null) {
            setContentView(OfficialNavigationPage.fatal(this, "缺少官方导航厂商参数"))
            return
        }
        val capabilities = DemoOfficialNaviSdk.capabilities(provider)
        if (capabilities == null) {
            setContentView(OfficialNavigationPage.fatal(this,
                "当前 Demo 变体未装载${OfficialNavigationText.provider(provider)}官方导航"))
            return
        }
        val origin = intent.readPoint(EXTRA_ORIGIN_PREFIX) ?: DEFAULT_ORIGIN
        val destination = intent.readPoint(EXTRA_DESTINATION_PREFIX) ?: DEFAULT_DESTINATION
        viewModel.configureCapabilities(capabilities.modes, capabilities.travelModes)
        viewModel.resetRuntimeSession()
        val navigationPage = OfficialNavigationPage(this, provider, capabilities, ::stopAndFinish)
        page = navigationPage
        setContentView(navigationPage.root)
        val host = OfficialNavigationSessionHost(this, provider, navigationPage.navigatorContainer, viewModel)
        sessionHost = host
        commands = OfficialNavigationCommands(host, viewModel, capabilities, origin, destination, ::finish)
        host.create(savedInstanceState?.getBundle(STATE_NAVIGATOR))
        navigationPage.bindActions(host.guardUiCallback(::onPageAction))
        navigationPage.render(viewModel.state.value, host.sessionAvailable)
        collectPageState()
    }

    /** 系统返回先让厂商 View 消费，未消费时关闭当前拦截并继续 Android 返回栈。 */
    private fun connectSystemBack() {
        onBackPressedDispatcher.addCallback(this,
            /** Activity 级返回桥，不持久化厂商会话，销毁后由生命周期自动移除。 */
            object : OnBackPressedCallback(true) {
                /** 厂商未处理返回时不重复拦截；页面销毁仍统一释放原生会话和 UI。 */
                override fun handleOnBackPressed() {
                    if (sessionHost?.onBackPressed() != true) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    /** 只在 STARTED 收集只读状态；停止时自动取消收集，退出/销毁后不再访问 UI。 */
    private fun collectPageState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionHost?.onLifecycleStarted()
                viewModel.state.collect { state ->
                    if (!isFinishing && !isDestroyed) page?.render(state, sessionHost?.sessionAvailable == true)
                }
            }
        }
    }

    /** 接收已通过会话身份/代次检查的意图；开始连接权限，其余交给统一命令层。 */
    private fun onPageAction(action: OfficialNavigationAction) {
        if (action == OfficialNavigationAction.Start) requestNavigationStart() else commands?.handle(action)
    }

    /** 开始前复核隐私和重复请求门禁；真实导航缺少精确权限时捕获当前会话的权限续接。 */
    private fun requestNavigationStart() {
        val operations = commands ?: return
        val host = sessionHost ?: return
        if (!operations.canStart()) return
        if (viewModel.state.value.navigationMode == OfficialNavigationMode.REAL && !hasFineLocationPermission()) {
            viewModel.awaitRealNavigationPermission()
            val guarded = host.guardUiCallback<Unit> {
                if (hasFineLocationPermission()) operations.start()
                else viewModel.showErrorDetail("真实导航需要精确位置权限；可切换到模拟导航继续体验")
            }
            permissionContinuation = { guarded(Unit) }
            locationPermissionLauncher.launch(LOCATION_PERMISSIONS)
        } else operations.start()
    }

    /** 消费当前权限等待并清空续接引用；撤回、退出、重建或代次失效后不能启动旧请求。 */
    private fun onLocationPermissionResult() {
        val continuation = permissionContinuation
        permissionContinuation = null
        if (!isFinishing && !isDestroyed && viewModel.consumePermissionRequest()) continuation?.invoke()
    }

    /** 只接受精确定位权限；仅粗略授权仍提示用户使用模拟导航，不向 SDK 改写权限结果。 */
    private fun hasFineLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    /** 页面返回按钮尽力停止导航后结束 Activity；无会话或隐私已撤回也允许离开。 */
    private fun stopAndFinish() {
        if (isFinishing || isDestroyed) return
        sessionHost?.stopForReturn()
        finish()
    }

    /** 页面可见后由宿主复核 DemoConsentStore、转发 onStart，并恢复 150ms 初始化轮询。 */
    override fun onStart() {
        super.onStart()
        sessionHost?.onStart()
    }

    /** Activity 恢复交互后转发原生导航 View 的 onResume。 */
    override fun onResume() {
        super.onResume()
        sessionHost?.onResume()
    }

    /** 在父暂停回调前转发原生暂停，保留会话供返回前台使用。 */
    override fun onPause() {
        sessionHost?.onPause()
        super.onPause()
    }

    /** 在父停止回调前取消宿主轮询并转发原生 onStop，禁止不可见页面继续初始化轮询。 */
    override fun onStop() {
        sessionHost?.onStop()
        super.onStop()
    }

    /** 输入 Android 配置变化，保留系统处理并原样转发厂商导航 View。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sessionHost?.onConfigurationChanged(newConfig)
    }

    /** 保存独立原生 Bundle 并保留原有键；ViewModel 的可恢复选择由 SavedStateHandle 管理。 */
    override fun onSaveInstanceState(outState: Bundle) {
        val navigatorState = Bundle()
        sessionHost?.saveState(navigatorState)
        outState.putBundle(STATE_NAVIGATOR, navigatorState)
        super.onSaveInstanceState(outState)
    }

    /** 保留旧式厂商 Activity 结果转发，不拦截 AndroidX 自身的结果处理。 */
    @Deprecated("保留厂商导航 View 的旧式结果转发")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        sessionHost?.onActivityResult(requestCode, resultCode, data)
    }

    /** 保留 Array<String> 权限签名，先让 AndroidX 处理再原样传递给厂商导航 View。 */
    @Deprecated("保留厂商导航 View 的权限结果转发")
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        sessionHost?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    /** 厂商先消费按键，未消费时继续系统行为，不改变统一 SDK 的返回契约。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        sessionHost?.onKeyDown(keyCode, event) == true || super.onKeyDown(keyCode, event)

    /** 先断开页面字段，再显式关闭会话与 UI；销毁产生的旧事件不能更新存活的 ViewModel。 */
    override fun onDestroy() {
        permissionContinuation = null
        commands = null
        val host = sessionHost
        sessionHost = null
        host?.close()
        val oldPage = page
        page = null
        oldPage?.close()
        super.onDestroy()
    }

    /** 与首页共享的 Intent 和原生状态协议；只含常量及值对象，不保存页面或会话。 */
    companion object {
        /** Intent 中厂商枚举 name 的旧键，不传递 MapProvider/导航器实例。 */
        private const val EXTRA_PROVIDER = "mapfusion.official_navi.provider"
        /** 起点 extra 的旧前缀，下接 latitude、longitude、coord_type 三个值键。 */
        private const val EXTRA_ORIGIN_PREFIX = "mapfusion.official_navi.origin"
        /** 终点 extra 的旧前缀，下接经纬度和坐标系值，保持首页跳转协议。 */
        private const val EXTRA_DESTINATION_PREFIX = "mapfusion.official_navi.destination"
        /** Android 原生导航 Bundle 的旧恢复键，与业务选择的 SavedStateHandle 键分离。 */
        private const val STATE_NAVIGATOR = "mapfusion.official_navi.navigator"
        /** 缺少合法入口起点时的公开演示点，坐标系 WGS84；不是设备真实定位。 */
        private val DEFAULT_ORIGIN = LatLng(39.9087, 116.3975, CoordType.WGS84)
        /** 缺少合法入口终点时的公开演示点，坐标系 WGS84；不作为导航结果持久化。 */
        private val DEFAULT_DESTINATION = LatLng(39.9163, 116.4172, CoordType.WGS84)
        /** 系统位置权限请求集合；真实导航仍单独要求 FINE，只有 COARSE 不足以开始真实导航。 */
        private val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        /** 输入厂商和请求端点创建首页入口，保留既有 extra 键及坐标系协议，不触碰真实 Key。 */
        internal fun createIntent(context: Context, provider: Provider, origin: LatLng, destination: LatLng): Intent =
            Intent(context, OfficialNavigationActivity::class.java).apply {
                putExtra(EXTRA_PROVIDER, provider.name)
                putPoint(EXTRA_ORIGIN_PREFIX, origin)
                putPoint(EXTRA_DESTINATION_PREFIX, destination)
            }

        /** 把输入端点拆成原有经纬度和坐标系 extra，避免引入 Parcelable 或变更入口协议。 */
        private fun Intent.putPoint(prefix: String, point: LatLng) {
            putExtra("$prefix.latitude", point.latitude)
            putExtra("$prefix.longitude", point.longitude)
            putExtra("$prefix.coord_type", point.coordType.name)
        }

        /** 读取指定端点前缀，缺少坐标或非法坐标系时返回 null，由入口使用原有演示默认值。 */
        private fun Intent.readPoint(prefix: String): LatLng? {
            if (!hasExtra("$prefix.latitude") || !hasExtra("$prefix.longitude")) return null
            val coordType = getStringExtra("$prefix.coord_type")
                ?.let { value -> runCatching { CoordType.valueOf(value) }.getOrNull() } ?: return null
            return LatLng(getDoubleExtra("$prefix.latitude", 0.0), getDoubleExtra("$prefix.longitude", 0.0), coordType)
        }
    }
}
