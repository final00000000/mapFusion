package com.mapfusion.demo.main

import com.mapfusion.api.model.LocationAccuracyStyle
import com.mapfusion.api.model.LocationDisplayEvent
import com.mapfusion.api.model.LocationDisplayOptions
import com.mapfusion.api.model.LocationDisplayStyle
import com.mapfusion.api.model.LocationFollowMode
import com.mapfusion.api.model.LocationMarkerStyle
import com.mapfusion.api.model.LocationOptions
import com.mapfusion.api.model.MapLocation
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.MarkerIcon
import java.util.Locale

/** 定位展示功能：配置连续定位并将结果写入 UiState，系统授权由宿主协调器完成。 */
internal class MainLocationFeature(
    /** Activity 绑定的地图宿主；会话守卫、请求代次与消息投递都经它完成。 */
    private val host: MainMapHost
) {
    /** 开启首点跟随的连续定位；复用 Session 组件，不把定位句柄保存进 ViewModel。 */
    fun start() = host.perform("定位") {
        val session = requireNotNull(host.session) { "地图尚未初始化" }
        val display = requireNotNull(session.locationDisplay) { "当前厂商不支持定位展示" }
        display.stop()
        val epoch = host.requests.generation
        val result = display.start(LocationDisplayOptions(
            locationOptions = LocationOptions(onceOnly = false, intervalMs = 2_000, timeoutMs = 15_000,
                needAddress = true, useCache = true),
            style = LocationDisplayStyle(marker = LocationMarkerStyle(
                icon = MarkerIcon.Bytes(DemoMapAssets.endpoint("我", DemoMapAssets.current)), anchorU = 0.5f,
                anchorV = 0.5f, flat = true, rotateWithBearing = true),
                accuracy = LocationAccuracyStyle(strokeWidth = host.dp(1).toFloat(), strokeColor = DemoMapAssets.current,
                    fillColor = 0x2231A354)),
            followMode = LocationFollowMode.FIRST_FIX, followZoom = 17f, maxAccuracyMeters = 100f,
            pauseWhenBackground = true)) { event -> host.deliver(epoch) {
                if (host.session === session) handle(event)
            } }
        if (result is MapResult.Failure) host.messages.mapError("定位启动失败", result.error)
    }

    /** 分别处理有效定位、精度过滤和失败事件；组件状态变化不伪造定位成功。 */
    private fun handle(event: LocationDisplayEvent) {
        when (event) {
            is LocationDisplayEvent.LocationUpdated -> showLocation(event.location)
            is LocationDisplayEvent.AccuracyRejected -> host.messages.info("等待更精确定位", "当前精度 ${event.location.accuracy.toInt()} m")
            is LocationDisplayEvent.Failure -> host.messages.mapError("定位失败", event.error)
            is LocationDisplayEvent.StateChanged -> Unit
        }
    }

    /** 将当前定位作为业务值写入 ViewModel，并生成页面摘要，不把真实位置落盘。 */
    private fun showLocation(location: MapLocation) {
        host.viewModel.setLocation(location)
        val point = "%.6f, %.6f".format(Locale.US, location.position.latitude, location.position.longitude)
        host.messages.success("定位成功 · 精度 ${location.accuracy.toInt()} m", "$point · ${location.address?.takeIf(String::isNotBlank) ?: "未返回地址"}")
    }
}
