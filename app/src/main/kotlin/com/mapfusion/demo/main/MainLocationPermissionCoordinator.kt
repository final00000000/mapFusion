package com.mapfusion.demo.main

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mapfusion.demo.presentation.main.LocationPermissionAction
import com.mapfusion.demo.presentation.main.MainViewModel

/** 系统位置授权协调器：宿主注册结果回调，状态归 ViewModel，SDK 动作由功能控制器执行。 */
internal class MainLocationPermissionCoordinator(private val activity: ComponentActivity,
    private val viewModel: MainViewModel, private val messages: MainStatusReporter,
    private val ensureConsent: () -> Boolean, private val execute: (LocationPermissionAction) -> Unit) : AutoCloseable {
    private var settingsDialog: AlertDialog? = null
    private var closed = false
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (!closed && !activity.isFinishing && !activity.isDestroyed) onPermissionResult(grants)
    }

    /** 精确或大致位置任一授权即可定位；真实官方导航仍使用自己的精确授权契约。 */
    fun hasPermission(): Boolean = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    /** 根据已有授权、在途请求和永久拒绝状态决定执行、申请或引导设置。 */
    fun request(action: LocationPermissionAction, fromUser: Boolean) {
        if (closed || !ensureConsent()) return
        if (hasPermission()) { viewModel.completeLocationPermission(true); execute(action); return }
        val state = viewModel.state.value
        if (state.permissionRequestInFlight) {
            if (state.pendingLocationAction == null) viewModel.awaitLocationPermission(action, true)
            messages.info("定位权限", "等待系统授权"); return
        }
        viewModel.awaitLocationPermission(action, false)
        if (!fromUser && state.permissionDenied) {
            viewModel.cancelPendingLocationAction()
            messages.error("未获得定位权限", "点击“定位”可重新授权并定位当前位置"); return
        }
        if (state.permissionPermanentlyDenied) {
            if (fromUser) showSettings() else {
                viewModel.cancelPendingLocationAction()
                messages.error("未获得定位权限", "点击“定位”可前往应用设置开启权限")
            }
            return
        }
        viewModel.awaitLocationPermission(action, true)
        messages.info("定位权限", "请允许精确或大致位置权限")
        launcher.launch(PERMISSIONS)
    }

    /** 系统结果到达时重新复核隐私；只消费成功授权后仍有效的用户操作。 */
    private fun onPermissionResult(grants: Map<String, Boolean>) {
        if (!ensureConsent()) {
            viewModel.cancelPendingLocationAction()
            return
        }
        val granted = grants.values.any { it } || hasPermission()
        val permanentlyDenied = !granted && PERMISSIONS.all { !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        val action = viewModel.completeLocationPermission(granted, permanentlyDenied)
        if (action != null) execute(action)
        else if (!granted) messages.error("定位权限被拒绝", if (permanentlyDenied)
            "权限已被永久拒绝，点击“定位”可前往应用设置开启" else "点击“定位”可重新申请精确或大致位置权限")
    }

    /** 从应用设置返回后续接 pending 操作；消费前同时检查宿主与最新隐私决定。 */
    fun onResume() {
        val state = viewModel.state.value
        if (!closed && state.pendingLocationAction != null && !state.permissionRequestInFlight && hasPermission() && ensureConsent()) {
            viewModel.completeLocationPermission(true)?.let { execute(it) }
        }
    }

    /** 永久拒绝时显示系统设置入口，取消后清除自动续接意图。 */
    private fun showSettings() {
        if (settingsDialog?.isShowing == true || activity.isFinishing || activity.isDestroyed) return
        val created = AlertDialog.Builder(activity).setTitle("需要定位权限")
            .setMessage("定位权限已被永久拒绝，请在应用设置中开启“位置信息”，返回后将自动继续当前操作。")
            .setNegativeButton("取消") { _, _ -> viewModel.cancelPendingLocationAction() }
            .setPositiveButton("去设置") { _, _ -> activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null))) }.create()
        settingsDialog = created
        created.setOnDismissListener { if (settingsDialog === created) settingsDialog = null }
        created.setOnCancelListener { viewModel.cancelPendingLocationAction() }
        created.show()
    }

    /** 销毁时释放设置窗口并注销系统结果监听；ViewModel 仍保存必要的恢复选择。 */
    override fun close() { closed = true; settingsDialog?.dismiss(); settingsDialog = null; launcher.unregister() }

    /** Android 系统位置权限名称，不包含其他高敏权限。 */
    private companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
