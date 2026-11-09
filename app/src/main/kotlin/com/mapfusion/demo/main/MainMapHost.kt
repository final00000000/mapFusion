package com.mapfusion.demo.main

import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.mapfusion.api.MapConfig
import com.mapfusion.api.MapProvider
import com.mapfusion.api.capability.MapController
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.CameraUpdate
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.MapTouchAction
import com.mapfusion.demo.BuildConfig
import com.mapfusion.demo.DemoOfficialNaviSdk
import com.mapfusion.demo.data.DemoConsentStore
import com.mapfusion.demo.data.DemoRequestRepository
import com.mapfusion.demo.data.PrivacyDecision
import com.mapfusion.demo.map.DemoMapSettingsApplier
import com.mapfusion.demo.map.DemoOverlayController
import com.mapfusion.demo.map.DemoOverlayStore
import com.mapfusion.demo.presentation.main.MainMapPhase
import com.mapfusion.demo.presentation.main.MainViewModel
import com.mapfusion.factory.MapFusion
import com.mapfusion.factory.MapFusionSession
import java.util.Locale

/** Activity 绑定的地图资源宿主：统一会话创建、切换、释放和异步投递守卫，不拥有页面布局。 */
internal class MainMapHost(
    /** 宿主 Activity；地图 View 挂载、生命周期转发与主线程投递都依赖它。 */
    private val activity: ComponentActivity,
    /** 页面状态所有者；选择可恢复，地图阶段/最近定位只属当前运行态，不保存原生句柄。 */
    val viewModel: MainViewModel,
    /** 原生地图 View 的挂载容器；切换会话时先移除旧 View 再挂载新 View。 */
    private val container: FrameLayout,
    /** 隐私决定的共享持久化存储；用于跨页面复核真实同意状态。 */
    private val consent: DemoConsentStore,
    /** 隐私门禁；创建会话和发起动作前都必须返回 true。 */
    private val ensureConsent: () -> Boolean
) : AutoCloseable {
    /** 唯一消息出口；将业务结果写入 UiState，不直接操作视图。 */
    val messages = MainStatusReporter(viewModel)
    /** 单次请求仓库；负责同键替换、请求代次与会话失效。 */
    val requests = DemoRequestRepository()
    /** Demo 自有覆盖物句柄登记表；与会话原生资源分离，可单独清理。 */
    val overlays = DemoOverlayStore()
    /** 坐标策略；优先读取最近定位，未定位时使用公开示例点并转换厂商坐标系。 */
    val coordinates = DemoCoordinates({ provider?.provider ?: viewModel.state.value.preferredProvider },
        { viewModel.state.value.lastLocation })
    /** 当前会话所属厂商；仅在会话有效期间非空，释放或初始化失败后置 null。 */
    var provider: MapProvider? = null
        private set
    /** 当前地图控制器；由演示覆盖物控制器包装，随会话创建与释放。 */
    var controller: MapController? = null
        private set
    /** 当前地图会话；原生资源的唯一所有者，必须经 releaseSession 销毁。 */
    var session: MapFusionSession? = null
        private set
    /** 引用/代次失效后、旧 SDK 资源销毁前的回调；供功能释放轨迹自有句柄，默认空实现。 */
    var beforeSessionRelease: () -> Unit = {}
    /** 地图就绪后的回调；由页面在容器 post 后执行，避免创建过程中操作 View。 */
    var onReady: () -> Unit = {}
    /** 本宿主是否已调用工厂安装，默认 false；用于避免重复安装/决定撤回同步，不代表 SDK 就绪。 */
    private var factoriesRegistered = false
    /** 页面是否处于前台；晚创建的会话据此补发 onResume。 */
    private var resumed = false
    /** 是否永久关闭；关闭后拒绝新动作与迟到投递，不可逆。 */
    private var closed = false

    /** 获取当前密度下的布局像素，供地图视野 padding 和定位样式使用。 */
    fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    /** 唯一厂商切换点；未同意时不注册工厂、不读取 Key、不创建 Session。 */
    fun switchProvider(provider: Provider, savedState: Bundle? = null) {
        if (closed || activity.isFinishing || activity.isDestroyed) return
        if (!DemoOfficialNaviSdk.supportsMapProvider(provider)) {
            messages.error("地图厂商不可用", "当前 Demo 变体未装载${provider.displayName()} SDK")
            return
        }
        viewModel.selectProvider(provider)
        if (!ensureConsent()) return
        if (!factoriesRegistered) { DemoOfficialNaviSdk.install(); factoriesRegistered = true }
        releaseSession()
        viewModel.setMapPhase(MainMapPhase.INITIALIZING)
        var candidate: MapFusionSession? = null
        runCatching {
            val config = MapConfig(provider = provider, apiKey = when (provider) {
                Provider.BAIDU -> BuildConfig.BAIDU_MAP_API_KEY
                Provider.AMAP -> BuildConfig.AMAP_API_KEY
            }, enablePrivacyCompliance = true)
            val created = MapFusion.openSession(activity, config, savedState)
            candidate = created
            val map = DemoOverlayController(created.mapController, overlays)
            if (resumed) created.onResume()
            (map.view.parent as? ViewGroup)?.removeView(map.view)
            container.addView(map.view, FrameLayout.LayoutParams(-1, -1))
            when (val applied = DemoMapSettingsApplier.apply(map, viewModel.state.value.mapSettings)) {
                is MapResult.Success -> viewModel.applyMapSettings(applied.data)
                is MapResult.Failure -> error("地图设置恢复失败：${applied.error.message}")
            }
            if (savedState == null) map.moveCamera(CameraUpdate(target = coordinates.defaultPoint(provider), zoom = 14f, animated = false))
            this.provider = created.provider; controller = map; session = created
            bindInteractions(map)
            viewModel.setMapPhase(MainMapPhase.READY, created.provider.capabilities().size)
            messages.info("${provider.displayName()}地图已就绪", "正在定位当前位置")
            val epoch = requests.generation
            container.post { deliver(epoch, onReady) }
        }.onFailure { error ->
            if (resumed) runCatching { candidate?.onPause() }
            runCatching { candidate?.destroy() }.onFailure { if (it !== error) error.addSuppressed(it) }
            this.provider = null; controller = null; session = null
            clearDemoOverlaysSafely(); container.removeAllViews()
            viewModel.setMapPhase(MainMapPhase.FAILED)
            Log.e("MapFusionDemo", "切换 $provider 失败", error)
            messages.error("地图初始化失败", error.message ?: error::class.java.simpleName)
        }
    }

    /** 先使全部旧回调失效，再释放组件与会话；任何单个清理异常不跳过后续释放。 */
    fun releaseSession() {
        requests.invalidateSession()
        val previous = session
        session = null; controller = null; provider = null
        viewModel.resetSessionState()
        runCatching(beforeSessionRelease).onFailure { Log.w("MapFusionDemo", "释放地图组件失败", it) }
        clearDemoOverlaysSafely()
        if (resumed) runCatching { previous?.onPause() }.onFailure { Log.w("MapFusionDemo", "暂停地图失败", it) }
        runCatching { previous?.destroy() }.onFailure { Log.w("MapFusionDemo", "销毁地图失败", it) }
        container.removeAllViews()
    }

    /** 清理 Demo 自有句柄，不影响 Session 组件；销毁流程中捕获异常继续释放资源。 */
    private fun clearDemoOverlaysSafely() {
        runCatching(overlays::clear).onFailure { Log.w("MapFusionDemo", "释放演示覆盖物失败", it) }
    }

    /** 在 SDK 工厂已注册时同步撤回，失败保留为明确状态而不是继续初始化。 */
    fun revokeVendorConsent() {
        if (!factoriesRegistered) return
        val failures = MapFusion.updatePrivacyConsent(activity, consentGranted = false).mapNotNull { (provider, result) ->
            (result as? MapResult.Failure)?.let { "${provider.displayName()}：${it.error.message}" }
        }
        if (failures.isNotEmpty()) messages.error("厂商隐私状态更新失败", failures.joinToString())
    }

    /** 同步地图操作的统一入口，先确认隐私并将异常转换为页面失败消息。 */
    fun perform(action: String, block: () -> Unit) {
        if (!canStartAction()) return
        runCatching(block).onFailure {
            Log.e("MapFusionDemo", "$action 失败", it)
            messages.error("$action 失败", it.message ?: it::class.java.simpleName)
        }
    }

    /** 单次请求启动前捕获会话代次，控制器必须将该代次用于最终 UI 投递。 */
    fun request(action: String, block: (Int) -> Unit) {
        if (!canStartAction()) return
        val epoch = requests.generation
        messages.info("$action 请求中", provider?.provider?.displayName().orEmpty())
        runCatching { block(epoch) }.onFailure {
            Log.e("MapFusionDemo", "$action 发起失败", it)
            messages.error("$action 发起失败", it.message ?: it::class.java.simpleName)
        }
    }

    /** 拒绝销毁宿主上的新操作；真实隐私决定由共享存储复核，不能只信缓存。 */
    private fun canStartAction(): Boolean = !closed && !activity.isFinishing && !activity.isDestroyed && ensureConsent()

    /** UI 线程二次验证代次、宿主和持久隐私状态，拒绝旧会话或撤回后的迟到回调。 */
    fun deliver(epoch: Int, block: () -> Unit) = activity.runOnUiThread {
        if (!closed && requests.accepts(epoch) && !activity.isFinishing && !activity.isDestroyed &&
            viewModel.state.value.privacyDecision == PrivacyDecision.ACCEPTED && consent.hasConsent()) block()
    }

    /** 给当前会话绑定地图交互反馈，全部回调经过同一会话守卫。 */
    private fun bindInteractions(map: MapController) {
        val epoch = requests.generation
        map.setOnMapPoiClickListener { poi -> deliver(epoch) {
            messages.success(poi.name ?: "底图 POI", "id=${poi.id ?: "-"} · ${poi.position.latitude}, ${poi.position.longitude}")
        } }
        map.setOnMarkerDragListener { event -> deliver(epoch) {
            messages.info("Marker ${event.phase.name.lowercase(Locale.ROOT)}", "${event.position.latitude}, ${event.position.longitude}")
        } }
        map.setOnMapTouchListener { event ->
            if (event.action == MapTouchAction.DOWN && requests.accepts(epoch))
                Log.d("MapFusionDemo", "地图触摸 DOWN x=${event.x}, y=${event.y}, pointers=${event.pointerCount}")
        }
    }

    /** 转发前台生命周期，晚创建的地图也使用相同的 resumed 标记。 */
    fun onResume() { resumed = true; session?.onResume() }

    /** 转发后台生命周期，不把暂停后的原生 View 放入 ViewModel。 */
    fun onPause() { session?.onPause(); resumed = false }

    /** 将宿主内存压力通知当前地图会话。 */
    fun onLowMemory() { session?.onLowMemory() }

    /** 仅保存原生地图 Bundle，业务选择由 SavedStateHandle 独立保存。 */
    fun saveState(): Bundle = Bundle().also { session?.onSaveInstanceState(it) }

    /** 永久关闭请求并幂等释放会话，清空组件回调，禁止销毁后再次启动。 */
    override fun close() {
        if (closed) return
        closed = true
        requests.close()
        releaseSession()
        beforeSessionRelease = {}; onReady = {}
    }
}
