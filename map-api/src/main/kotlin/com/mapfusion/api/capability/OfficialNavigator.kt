package com.mapfusion.api.capability

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.OfficialNavigationCapabilities
import com.mapfusion.api.model.OfficialNavigationDayNightMode
import com.mapfusion.api.model.OfficialNavigationListener
import com.mapfusion.api.model.OfficialNavigationRequest
import com.mapfusion.api.model.OfficialNavigationState
import com.mapfusion.api.model.RoutePreference

/**
 * 厂商官方 Navi 引擎会话。它拥有 [view] 和底层导航引擎，必须与 Activity 生命周期同步。
 * 同一进程通常只能持有一个同厂商会话；[destroy] 后不得再投递监听事件。
 */
interface OfficialNavigator : AutoCloseable {
    val capabilities: OfficialNavigationCapabilities
    val state: OfficialNavigationState

    /**
     * 最近一次使会话进入 [OfficialNavigationState.FAILED] 的错误。
     *
     * 厂商引擎可能在 [start] 注册监听器之前异步初始化失败。宿主可在观察到
     * [state] 为 [OfficialNavigationState.FAILED] 时读取此属性，获得鉴权码、
     * 包名/SHA1 或其他原始诊断信息。旧实现默认返回 `null`。
     */
    val lastError: MapError? get() = null

    val view: View

    /** 异步规划并启动官方真实或模拟导航。 */
    fun start(
        request: OfficialNavigationRequest,
        listener: OfficialNavigationListener,
    ): MapResult<Unit>

    fun selectRoute(index: Int): MapResult<Unit>
    fun pause(): MapResult<Unit>
    fun resume(): MapResult<Unit>
    fun stop(): MapResult<Unit>

    /** 使用新的路线偏好主动重算当前路线。异步完成结果通过原有监听器回传。 */
    fun recalculateRoute(preference: RoutePreference = RoutePreference.DEFAULT): MapResult<Unit> =
        unsupportedOfficialNavigationControl("主动路线重算")

    fun showRouteOverview(): MapResult<Unit> = unsupportedOfficialNavigationControl("路线全览")
    fun resumeFollowing(): MapResult<Unit> = unsupportedOfficialNavigationControl("恢复车标跟随")

    /** 仅对已经运行的模拟导航生效，单位为 km/h。 */
    fun setSimulationSpeed(kilometersPerHour: Int): MapResult<Unit> =
        unsupportedOfficialNavigationControl("运行时模拟速度")

    fun setVoiceGuidanceEnabled(enabled: Boolean): MapResult<Unit> =
        unsupportedOfficialNavigationControl("语音控制")

    fun setDayNightMode(mode: OfficialNavigationDayNightMode): MapResult<Unit> =
        unsupportedOfficialNavigationControl("昼夜模式")

    fun onStart() = Unit
    fun onResume()
    fun onPause()
    fun onStop() = Unit
    fun onSaveInstanceState(outState: Bundle) = Unit
    fun onConfigurationChanged(configuration: Configuration) = Unit
    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) = Unit
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) = Unit
    fun onBackPressed(): Boolean = false
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = false

    fun destroy()
    override fun close() = destroy()
}

private fun unsupportedOfficialNavigationControl(name: String): MapResult<Unit> = MapResult.Failure(
    MapError(ErrorType.UNSUPPORTED, "当前官方导航厂商不支持$name"),
)

/** 可选 Navi 模块的统一创建入口。创建失败时返回可诊断的 [MapResult.Failure]。 */
interface OfficialNavigatorFactory {
    val capabilities: OfficialNavigationCapabilities

    fun create(
        activity: Activity,
        savedState: Bundle? = null,
    ): MapResult<OfficialNavigator>
}
