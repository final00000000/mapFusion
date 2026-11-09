package com.mapfusion.demo.main

import android.graphics.Color
import com.mapfusion.api.model.CameraUpdate
import com.mapfusion.api.model.CircleOptions
import com.mapfusion.api.model.GroundOverlayOptions
import com.mapfusion.api.model.HeatMapOptions
import com.mapfusion.api.model.HeatPoint
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapImage
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.MarkerIcon
import com.mapfusion.api.model.MarkerOptions
import com.mapfusion.api.model.MultiPointItem
import com.mapfusion.api.model.MultiPointOverlayOptions
import com.mapfusion.api.model.PolygonOptions
import com.mapfusion.api.model.PolylineOptions
import com.mapfusion.api.model.TextOverlayOptions

/** 覆盖物演示功能；所有新增句柄由 DemoOverlayController 登记，与 Session 组件隔离。 */
internal class MainOverlayFeature(
    /** Activity 绑定的地图宿主；控制器、坐标、覆盖物登记与消息都经它访问。 */
    private val host: MainMapHost,
    /** 释放轨迹的互斥回调；发起导航或全局清空时先结束轨迹录制。 */
    private val releaseTrack: () -> Unit
) {
    /** 分发覆盖物用户意图；全局清空与普通演示替换使用不同释放规则。 */
    fun show(action: MainAction) = when (action) {
        MainAction.MULTI_POINT -> multiPoint()
        MainAction.CLEAR_MAP -> clearMap()
        else -> replace(action)
    }

    /** 只替换 Demo 自有句柄并绘制对应覆盖物，不静默停止轨迹或导航。 */
    private fun replace(action: MainAction) = host.perform("覆盖物演示") {
        val controller = requireNotNull(host.controller) { "地图尚未初始化" }
        val center = host.coordinates.center()
        val east = LatLng(center.latitude, center.longitude + 0.012, center.coordType)
        val north = LatLng(center.latitude + 0.009, center.longitude + 0.004, center.coordType)
        val west = LatLng(center.latitude + 0.002, center.longitude - 0.010, center.coordType)
        val title = when (action) {
            MainAction.MARKER -> "Marker"; MainAction.POLYLINE -> "折线"; MainAction.POLYGON -> "多边形"
            MainAction.CIRCLE -> "圆"; MainAction.TEXT -> "文字"; MainAction.GROUND -> "地面图"
            MainAction.HEAT_MAP -> "热力图"; else -> error("不是覆盖物操作")
        }
        host.overlays.clear()
        controller.moveCamera(CameraUpdate(target = center,
            zoom = if (action in setOf(MainAction.MARKER, MainAction.TEXT, MainAction.GROUND)) 16f else 14f))
        when (action) {
            MainAction.MARKER -> controller.addMarker(MarkerOptions(center, "Map Fusion", "统一 Marker", draggable = true))
            MainAction.POLYLINE -> controller.addPolyline(PolylineOptions(listOf(west, center, east), width = 14f,
                color = DemoMapAssets.route, clickable = true))
            MainAction.POLYGON -> controller.addPolygon(PolygonOptions(listOf(center, east, north),
                strokeColor = DemoMapAssets.start, fillColor = Color.argb(72, 46, 125, 50), clickable = true))
            MainAction.CIRCLE -> controller.addCircle(CircleOptions(west, 500.0, strokeColor = Color.rgb(239, 108, 0),
                fillColor = Color.argb(66, 239, 108, 0), clickable = true))
            MainAction.TEXT -> controller.addText(TextOverlayOptions("Map Fusion", center, fontSizePixels = 38,
                fontColor = Color.WHITE, backgroundColor = DemoMapAssets.accent, zIndex = 20f))
            MainAction.GROUND -> controller.addGroundOverlay(GroundOverlayOptions(image = MapImage.Bytes(DemoMapAssets.ground()),
                position = center, widthMeters = 650f, heightMeters = 320f, transparency = 0.08f, zIndex = 3f))
            MainAction.HEAT_MAP -> controller.addHeatMap(HeatMapOptions(listOf(HeatPoint(center), HeatPoint(east, 0.8),
                HeatPoint(north, 0.7), HeatPoint(west, 0.6)), radiusPixels = 30, opacity = 0.62f, zIndex = 1f))
            else -> error("不是覆盖物操作")
        }
        host.messages.success("$title 已显示", "当前地图仅保留该功能的演示覆盖物")
    }

    /** 一次提交 625 个公开演示点，点击反馈仍经过当前会话守卫。 */
    private fun multiPoint() = host.perform("海量点") {
        val controller = requireNotNull(host.controller) { "地图尚未初始化" }
        val center = host.coordinates.center()
        val items = List(625) { index -> MultiPointItem("demo-$index", LatLng(
            center.latitude + (index / 25 - 12) * 0.001, center.longitude + (index % 25 - 12) * 0.001,
            center.coordType), title = "海量点 ${index + 1}", tag = index) }
        host.overlays.clear()
        val epoch = host.requests.generation
        controller.setOnMultiPointClickListener { overlay, item ->
            host.deliver(epoch) { host.messages.success(item.title ?: item.id, "id=${item.id} · 图层=${overlay.id}") }; true
        }
        controller.addMultiPointOverlay(MultiPointOverlayOptions(items, icon = MarkerIcon.Bytes(DemoMapAssets.multiPoint()),
            anchorU = 0.5f, anchorV = 0.5f, tag = "demo-multipoint"))
        controller.fitPoints(items.map(MultiPointItem::position), paddingPixels = host.dp(40))
        host.messages.success("海量点已显示", "通过厂商原生图层一次提交 ${items.size} 个点，点击点位可读取统一 tag")
    }

    /** 全局清空明确停止导航、轨迹及定位展示，再清除所有地图覆盖物。 */
    private fun clearMap() = host.perform("清空地图") {
        val controller = requireNotNull(host.controller) { "地图尚未初始化" }
        host.session?.embeddedNavigator?.stop(); host.viewModel.setNavigationActive(false)
        releaseTrack(); host.session?.locationDisplay?.stop()
        host.overlays.clear(); controller.clearOverlays()
        host.messages.success("地图已清空", "已停止导航、轨迹和定位展示，移除全部覆盖物")
    }

    /** 截图作为单次请求托管，只显示尺寸和字节数，不保存截图或真实位置产物。 */
    fun snapshot() = host.request("地图截图") { epoch ->
        val controller = requireNotNull(host.controller) { "地图尚未初始化" }
        host.requests.execute("snapshot", { callback -> controller.snapshot(callback) }, MapCallback { result ->
            host.deliver(epoch) { when (result) {
                is MapResult.Success -> host.messages.success("截图成功 · ${result.data.width} x ${result.data.height}",
                    "PNG ${result.data.pngBytes.size / 1024} KB")
                is MapResult.Failure -> host.messages.mapError("截图失败", result.error)
            } }
        })
    }
}
