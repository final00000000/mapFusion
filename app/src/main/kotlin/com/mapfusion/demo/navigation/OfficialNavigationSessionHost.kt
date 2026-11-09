package com.mapfusion.demo.navigation

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import com.mapfusion.api.capability.OfficialNavigator
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.OfficialNavigationEvent
import com.mapfusion.api.model.OfficialNavigationFeature
import com.mapfusion.api.model.OfficialNavigationRequest
import com.mapfusion.api.model.OfficialNavigationState
import com.mapfusion.demo.DemoOfficialNaviSdk
import com.mapfusion.demo.data.DemoConsentStore
import com.mapfusion.demo.presentation.navigation.OfficialNavigationViewModel

/**
 * 页面级原生会话宿主：负责创建、隐私复核、身份栅栏、事件线程桥接与初始化轮询。
 * 可持有 Activity 和原生 View，但不复制业务状态；Activity 必须转发生命周期并显式 close。
 */
internal class OfficialNavigationSessionHost(
    private val activity: ComponentActivity,
    private val provider: Provider,
    private val container: FrameLayout,
    private val viewModel: OfficialNavigationViewModel,
) : AutoCloseable {
    private val consentStore = DemoConsentStore(activity)
    private val fence = NavigationSessionFence<OfficialNavigator>()
    private val handler = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null
    private var started = false
    @Volatile private var closed = false
    private var consentGranted = false
    /** 会话可用性仅供 UI 策略输入，不把原生引用暴露给 ViewModel。 */
    val sessionAvailable: Boolean get() = !closed && fence.current != null

    /** 输入厂商原生恢复 Bundle；先复核并同步隐私，再挂载原生 View、应用保存的语音和配色。 */
    fun create(savedState: Bundle?) {
        if (closed || sessionAvailable || activity.isFinishing || activity.isDestroyed || !ensureConsent()) return
        when (val privacy = DemoOfficialNaviSdk.updatePrivacyConsent(activity, provider, true)) {
            is MapResult.Failure -> {
                viewModel.showMapError("厂商隐私状态同步失败", privacy.error, OfficialNavigationState.FAILED)
                viewModel.blockControls()
                return
            }
            is MapResult.Success -> Unit
        }
        when (val result = DemoOfficialNaviSdk.createNavigator(activity, provider, savedState)) {
            is MapResult.Failure -> {
                viewModel.showMapError("官方导航引擎初始化失败", result.error, OfficialNavigationState.FAILED)
                viewModel.blockControls()
            }
            is MapResult.Success -> {
                val session = result.data
                fence.attach(session)
                if (!ensureConsent()) return
                val state = viewModel.state.value
                if (session.capabilities.supports(OfficialNavigationFeature.VOICE_CONTROL)) {
                    session.setVoiceGuidanceEnabled(state.voiceGuidanceEnabled)
                }
                if (session.capabilities.supports(OfficialNavigationFeature.DAY_NIGHT_MODE)) {
                    session.setDayNightMode(state.dayNightMode)
                }
                (session.view.parent as? ViewGroup)?.removeView(session.view)
                container.addView(session.view, 0, FrameLayout.LayoutParams(-1, -1))
                syncSnapshot()
                schedulePolling()
            }
        }
    }

    /**
     * 每次开始、onStart、UI 操作或事件前直接读取隐私存储；撤回先使代次失效、清空会话，
     * 再 stop/destroy，最后发布阻断状态，避免销毁产生的同步尾事件污染页面。
     */
    fun ensureConsent(): Boolean {
        if (closed) return false
        consentGranted = consentStore.hasConsent()
        if (!consentGranted) {
            releaseSession(stop = true)
            viewModel.blockForConsent("地图隐私同意已失效，请返回首页重新确认")
        }
        return consentGranted
    }

    /** 输入页面回调，捕获绑定时的会话身份和代次；退出、撤回或换代后旧控件/权限回调失效。 */
    fun <T> guardUiCallback(callback: (T) -> Unit): (T) -> Unit {
        val token = fence.token()
        return { value ->
            if (accepts(token) && ensureConsent() && accepts(token)) callback(value)
        }
    }

    /** 输入统一引擎操作与结果处理；操作前后均复核身份和隐私，只把值快照交回业务层。 */
    fun perform(
        operation: (OfficialNavigator) -> MapResult<Unit>,
        onResult: (MapResult<Unit>, OfficialNavigationState) -> Unit,
    ) {
        val token = fence.token() ?: return
        if (!accepts(token) || !ensureConsent() || !accepts(token)) return
        val result = operation(token.identity)
        if (accepts(token) && ensureConsent() && accepts(token)) onResult(result, token.identity.state)
    }

    /** 输入导航请求并安装受身份保护的事件桥；同步与异步事件都先经主线程和隐私门禁。 */
    fun start(request: OfficialNavigationRequest, onResult: (MapResult<Unit>, OfficialNavigationState) -> Unit) {
        val token = fence.token() ?: return
        perform({ session -> session.start(request) { event -> dispatchEvent(token, event) } }, onResult)
    }

    /** 输入 SDK 事件和注册时的令牌；主线程校验后只提交统一事件与状态值，不向 VM 传原生引用。 */
    private fun dispatchEvent(token: NavigationSessionToken<OfficialNavigator>, event: OfficialNavigationEvent) {
        if (closed) return
        val deliver = Runnable {
            if (accepts(token) && ensureConsent() && accepts(token)) {
                viewModel.onNavigationEvent(event, token.identity.state)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) deliver.run() else handler.post(deliver)
    }

    /** 输入捕获令牌，统一校验代次、会话引用、宿主关闭与 Activity 退出状态。 */
    private fun accepts(token: NavigationSessionToken<OfficialNavigator>?): Boolean =
        !closed && fence.accepts(token, activity.isFinishing, activity.isDestroyed)

    /** 读取当前引擎值快照，补齐监听器安装前异步初始化状态；隐私失效时立即释放会话。 */
    private fun syncSnapshot() {
        if (!ensureConsent()) return
        val token = fence.token() ?: return
        if (accepts(token)) viewModel.onEngineSnapshot(token.identity.state, token.identity.lastError)
    }

    /** 只为 STARTED 中仍初始化的当前会话安排 150ms 轮询，每次调度先移除旧任务。 */
    private fun schedulePolling() {
        cancelPolling()
        val token = fence.token() ?: return
        if (!NavigationPollingPolicy.shouldPoll(
                started && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                accepts(token), consentGranted, token.identity.state == OfficialNavigationState.INITIALIZING,
            )) return
        poll =
            /** 仅绑定当前令牌的初始化补偿任务，由宿主停止或换代时移除。 */
            object : Runnable {
                /** 轮询触发时重验 STARTED 与令牌，读取隐私和快照后只在初始化仍未完成时继续。 */
                override fun run() {
                    if (!started || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || !accepts(token)) return
                    syncSnapshot()
                    schedulePolling()
                }
            }
        handler.postDelayed(checkNotNull(poll), NavigationPollingPolicy.INTERVAL_MS)
    }

    /** 取消且清空初始化任务；onStop 先调用，避免父生命周期尚未降级时再次排入队列。 */
    private fun cancelPolling() {
        poll?.let(handler::removeCallbacks)
        poll = null
    }

    /** Android 生命周期确认为 STARTED 后补启轮询，避免 onStart 内生命周期尚未推进时漏调度。 */
    fun onLifecycleStarted() {
        if (!started || closed) return
        syncSnapshot()
        schedulePolling()
    }

    /** Activity 进入可见状态时复核隐私、转发原生 onStart，并恢复仅初始化期的轮询。 */
    fun onStart() {
        started = true
        if (!ensureConsent()) return
        fence.current?.onStart()
        syncSnapshot()
        schedulePolling()
    }

    /** Activity 恢复交互时转发原生导航 View 的恢复钩子，不自行订阅生命周期。 */
    fun onResume() { fence.current?.onResume() }

    /** Activity 暂停前转发原生暂停钩子，保留会话以支持后台再回前台。 */
    fun onPause() { fence.current?.onPause() }

    /** Activity 不可见时先关闭轮询门禁并移除任务，再转发厂商 onStop。 */
    fun onStop() {
        started = false
        cancelPolling()
        fence.current?.onStop()
    }

    /** 输入新配置并原样交给厂商导航 View，不重建业务选择或更改 SDK 配置协议。 */
    fun onConfigurationChanged(configuration: Configuration) { fence.current?.onConfigurationChanged(configuration) }

    /** 输入独立的原生 Bundle，由厂商写入可恢复数据；业务选择仍由 SavedStateHandle 保存。 */
    fun saveState(outState: Bundle) { fence.current?.onSaveInstanceState(outState) }

    /** 输入原始 Activity 结果，原样转发请求码、结果码和 Intent，保留厂商页面协议。 */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        fence.current?.onActivityResult(requestCode, resultCode, data)
    }

    /** 输入权限回调的 Array<String> 契约和授权结果，原样转发，不替代页面精确定位判断。 */
    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        fence.current?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    /** 让厂商优先消费系统返回；返回 false 时由 Activity 继续系统返回流程。 */
    fun onBackPressed(): Boolean = fence.current?.onBackPressed() == true

    /** 输入按键及事件交给厂商，未消费的按键仍由 Activity 调用系统实现。 */
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = fence.current?.onKeyDown(keyCode, event) == true

    /** 页面主动返回前复核隐私，并通过身份门禁尽力停止导航，失败不阻止用户离开。 */
    fun stopForReturn() {
        if (ensureConsent() && viewModel.state.value.busy) runCatching {
            perform({ it.stop() }) { _, _ -> Unit }
        }
    }

    /** 输入是否先停止，先清空引用/代次和页面任务，再分离 View 并尽力停止、销毁旧会话。 */
    private fun releaseSession(stop: Boolean) {
        cancelPolling()
        handler.removeCallbacksAndMessages(null)
        val session = fence.invalidate() ?: return
        runCatching { (session.view.parent as? ViewGroup)?.removeView(session.view) }
        if (stop) runCatching { session.stop() }
        runCatching { session.destroy() }
    }

    /** Activity 销毁时显式调用；幂等关闭宿主和全部任务，令所有已捕获回调不可再提交状态。 */
    override fun close() {
        if (closed) return
        closed = true
        started = false
        releaseSession(stop = false)
    }
}
