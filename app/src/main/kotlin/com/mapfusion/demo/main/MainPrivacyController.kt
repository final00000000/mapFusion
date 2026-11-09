package com.mapfusion.demo.main

import android.app.AlertDialog
import androidx.activity.ComponentActivity
import com.mapfusion.demo.data.DemoConsentStore
import com.mapfusion.demo.data.PrivacyDecision
import com.mapfusion.demo.presentation.main.MainMapPhase
import com.mapfusion.demo.presentation.main.MainViewModel

/** 宿主隐私流程：统一告知、持久化和撤回，不直接创建任何厂商地图。 */
internal class MainPrivacyController(
    /** 宿主 Activity；用于展示告知弹窗并检查页面是否仍有效。 */
    private val activity: ComponentActivity,
    /** 页面状态所有者；保存当前隐私决定与地图阶段，不保存告知文本。 */
    private val viewModel: MainViewModel,
    /** 隐私决定的共享存储；首次告知、撤回与跨页面复核都以此为准。 */
    private val store: DemoConsentStore,
    /** 消息入口，展示保存失败或功能不可用说明。 */
    private val messages: MainStatusReporter,
    /** 同意落盘后的启用回调；由页面负责创建厂商地图会话。 */
    private val onEnable: () -> Unit,
    /** 撤回后的停用回调；先销毁会话，再由页面渲染未启用占位。 */
    private val onDisable: () -> Unit,
    /** 向厂商同步撤回隐私的回调；仅在撤回保存成功后调用。 */
    private val onVendorRevoke: () -> Unit
) : AutoCloseable {
    /** 当前隐私弹窗句柄；同一时刻最多一个，消失后置 null。 */
    private var dialog: AlertDialog? = null
    /** 是否已关闭；关闭后禁止旧页面的点击回调再次启用地图。 */
    private var closed = false

    /** 同时检查页面决定和真实存储，避免另一个页面撤回后继续相信旧缓存。 */
    fun hasConsent(): Boolean = viewModel.state.value.privacyDecision == PrivacyDecision.ACCEPTED && store.hasConsent()

    /** 宿主回到前台时重新读取决定；发现外部撤回必须先停止会话再渲染禁用状态。 */
    fun refresh() {
        val latest = store.read()
        val wasAccepted = viewModel.state.value.privacyDecision == PrivacyDecision.ACCEPTED
        viewModel.setPrivacyDecision(latest)
        if (wasAccepted && latest != PrivacyDecision.ACCEPTED) { onDisable(); showUnavailable() }
    }

    /** 用户操作的隐私门禁；不同意只展示本地说明，不触发位置权限或 SDK 初始化。 */
    fun ensureConsent(): Boolean {
        if (closed || activity.isFinishing || activity.isDestroyed) return false
        if (hasConsent()) return true
        refresh(); showUnavailable()
        showConsent(viewModel.state.value.privacyDecision == PrivacyDecision.UNDECIDED)
        return false
    }

    /** 展示第一次告知或重新启用说明，已有弹窗时不叠加窗口。 */
    fun showConsent(firstRun: Boolean) {
        if (!canShowDialog()) return
        val created = AlertDialog.Builder(activity).setTitle("地图服务隐私说明").setMessage(PRIVACY_NOTICE)
            .setNegativeButton(if (firstRun) "不同意" else "暂不启用") { _, _ ->
                if (persist(PrivacyDecision.DECLINED)) showUnavailable()
                else messages.error("隐私设置保存失败", "无法保存当前选择，请稍后重试")
            }.setPositiveButton("同意并启用") { _, _ -> grant() }.setCancelable(!firstRun).create()
        created.setCanceledOnTouchOutside(false)
        show(created)
    }

    /** 已同意时提供显式撤回入口，否则回到完整告知流程。 */
    fun showSettings() {
        if (!hasConsent()) { ensureConsent(); return }
        if (!canShowDialog()) return
        show(AlertDialog.Builder(activity).setTitle("隐私设置")
            .setMessage("当前已同意地图服务隐私说明。撤回后会立即停止定位、导航、轨迹及异步请求，销毁当前地图会话；重新同意前不会再次初始化百度或高德地图 SDK。")
            .setNegativeButton("撤回同意") { _, _ -> revoke() }.setPositiveButton("保留同意", null).create())
    }

    /** 只有同意决定确实落盘后才允许宿主创建地图会话。 */
    private fun grant() {
        if (!persist(PrivacyDecision.ACCEPTED)) {
            messages.error("隐私设置保存失败", "无法持久化同意状态，地图 SDK 未初始化"); return
        }
        messages.info("已同意地图服务隐私说明", "正在初始化${viewModel.state.value.preferredProvider.displayName()}地图")
        onEnable()
    }

    /** 撤回保存失败时保留现有会话；成功后先释放资源，再向厂商同步 false。 */
    private fun revoke() {
        if (!persist(PrivacyDecision.DECLINED)) {
            messages.error("隐私设置保存失败", "未执行撤回，请稍后重试"); return
        }
        onDisable(); showUnavailable(); onVendorRevoke()
    }

    /** 同步保存成功才更新 UiState，避免界面状态与磁盘决定分叉。 */
    private fun persist(decision: PrivacyDecision): Boolean = store.save(decision).also {
        if (it) viewModel.setPrivacyDecision(decision)
    }

    /** 将未启用阶段写入状态，具体占位卡片由 MainScreen 渲染。 */
    fun showUnavailable() {
        viewModel.setMapPhase(MainMapPhase.DISABLED)
        messages.info("地图功能不可用", "未同意隐私说明，当前未启用地图厂商会话")
    }

    /** 仅在有效宿主且没有现有弹窗时创建新窗口。 */
    private fun canShowDialog(): Boolean = !closed && dialog?.isShowing != true && !activity.isFinishing && !activity.isDestroyed

    /** 登记弹窗所有权并在消失时清空句柄，避免多个隐私窗口同时存在。 */
    private fun show(created: AlertDialog) {
        dialog = created
        created.setOnDismissListener { if (dialog === created) dialog = null }
        created.show()
    }

    /** 宿主销毁时释放窗口，禁止旧点击回调继续启用地图。 */
    override fun close() { closed = true; dialog?.dismiss(); dialog = null }

    /** 本地告知文案，只描述服务和处理范围，不包含 Key 或用户实际位置。 */
    private companion object {
        /** 首次告知与重新启用共用的本地说明；只描述服务范围，不含 Key 或实际位置。 */
        const val PRIVACY_NOTICE = "Map Fusion Demo 使用百度地图和高德地图服务。只有在你点击“同意并启用”后，" +
            "应用才会初始化当前选择的地图厂商 SDK。\n\n地图展示、定位、路线规划、POI、天气、内嵌导航和轨迹功能可能处理精确或大致位置信息、" +
            "设备与网络信息及服务日志，并由当前选择的地图厂商按其隐私政策处理。位置权限仍会由 Android 系统另行询问。\n\n" +
            "不同意不会申请位置权限，也不会初始化地图 SDK。你可以随时通过顶部“隐私设置”撤回同意；撤回后应用会立即销毁地图会话并停止定位、导航和轨迹处理。"
    }
}
