package com.mapfusion.demo.presentation.navigation

import com.mapfusion.api.model.OfficialCameraAlert
import com.mapfusion.api.model.OfficialHighwayGuidance
import com.mapfusion.api.model.OfficialIntervalCameraAlert
import com.mapfusion.api.model.OfficialNavigationEvent
import com.mapfusion.api.model.OfficialNavigationProgress
import com.mapfusion.api.model.OfficialNavigationState
import com.mapfusion.api.model.OfficialRouteNotice
import com.mapfusion.api.model.OfficialRouteSummary
import com.mapfusion.api.model.OfficialServiceArea
import com.mapfusion.api.model.OfficialTrafficSegment
import com.mapfusion.api.model.OfficialWaypointProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 验证从 Activity 拆出的中文事件格式器，全部输入都是虚构的距离、道路与统一值对象。 */
class OfficialNavigationTextTest {
    /** 米/千米和秒/分钟/小时边界沿用原有取整规则，千米小数不受设备区域影响。 */
    @Test
    fun `distance and duration preserve unit boundaries`() {
        assertEquals("999 m", OfficialNavigationText.distance(999))
        assertEquals("1.0 km", OfficialNavigationText.distance(1_000))
        assertEquals("1.5 km", OfficialNavigationText.distance(1_500))
        assertEquals("59 秒", OfficialNavigationText.duration(59))
        assertEquals("1 分钟", OfficialNavigationText.duration(60))
        assertEquals("59 分钟", OfficialNavigationText.duration(3_599))
        assertEquals("1 小时 1 分钟", OfficialNavigationText.duration(3_660))
    }

    /** 隐藏/空集合事件和纯状态事件不产生新详情，不能擦掉上一条有效提示。 */
    @Test
    fun `hidden and empty events have no detail`() {
        val events = listOf(
            OfficialNavigationEvent.LaneGuidance(false, 3), OfficialNavigationEvent.JunctionView(false),
            OfficialNavigationEvent.CameraAlerts(emptyList()), OfficialNavigationEvent.ServiceAreas(emptyList()),
            OfficialNavigationEvent.TrafficStatus(segments = emptyList()), OfficialNavigationEvent.WaypointProgress(emptyList()),
            OfficialNavigationEvent.StateChanged(OfficialNavigationState.READY), OfficialNavigationEvent.RoutesReady(emptyList(), 0),
        )
        events.forEach { assertNull(OfficialNavigationText.event(it)) }
        assertNull(OfficialNavigationText.route(emptyList(), 0))
        assertNull(OfficialNavigationText.route(listOf(OfficialRouteSummary("r", 10, 10)), 1))
    }

    /** 区间测速优先剩余距离，再回退终点/起点距离，完全缺失时仍保留进入路段提示。 */
    @Test
    fun `interval camera uses ordered distance fallbacks`() {
        val camera = OfficialIntervalCameraAlert(100, 200, 300, vendorStatus = 0)
        assertEquals("区间测速，剩余 300 m", OfficialNavigationText.event(OfficialNavigationEvent.IntervalCameraAlert(camera)))
        assertEquals("区间测速，剩余 200 m", OfficialNavigationText.event(OfficialNavigationEvent.IntervalCameraAlert(camera.copy(remainingDistanceMeters = null))))
        assertEquals("区间测速，剩余 100 m", OfficialNavigationText.event(OfficialNavigationEvent.IntervalCameraAlert(camera.copy(remainingDistanceMeters = null, endDistanceMeters = null))))
        assertEquals("进入区间测速路段", OfficialNavigationText.event(OfficialNavigationEvent.IntervalCameraAlert(OfficialIntervalCameraAlert(vendorStatus = 0))))
    }

    /** 高速和路线提醒按原优先级拼接字段；GPS 未开启优先于弱信号。 */
    @Test
    fun `highway notice and gps retain fallback precedence`() {
        assertEquals("测试高速 · 北向 · 测试出口", OfficialNavigationText.event(OfficialNavigationEvent.HighwayGuidance(
            OfficialHighwayGuidance(true, highwayName = "测试高速", direction = "北向", exitName = "测试出口"))))
        assertEquals("高速引导已更新", OfficialNavigationText.event(OfficialNavigationEvent.HighwayGuidance(OfficialHighwayGuidance(false, highwayName = "测试高速"))))
        val notice = OfficialRouteNotice(message = "主提醒", subTitle = "副提醒", roadName = "测试路")
        assertEquals("主提醒", OfficialNavigationText.event(OfficialNavigationEvent.RouteNotice(notice)))
        assertEquals("副提醒", OfficialNavigationText.event(OfficialNavigationEvent.RouteNotice(notice.copy(message = null))))
        assertEquals("测试路", OfficialNavigationText.event(OfficialNavigationEvent.RouteNotice(notice.copy(message = null, subTitle = null))))
        assertEquals("路线提醒已更新", OfficialNavigationText.event(OfficialNavigationEvent.RouteNotice(OfficialRouteNotice())))
        assertEquals("GPS 未开启", OfficialNavigationText.event(OfficialNavigationEvent.GpsStatus(false, true)))
        assertEquals("GPS 信号较弱", OfficialNavigationText.event(OfficialNavigationEvent.GpsStatus(null, true)))
        assertEquals("GPS 信号正常", OfficialNavigationText.event(OfficialNavigationEvent.GpsStatus(null, false)))
    }

    /** 路线序号按一基展示，进度优先下一道路、当前道路，缺失时回退导航中。 */
    @Test
    fun `route summary and progress use unified labels`() {
        assertEquals("路线 2/2 · 1.0 km · 2 分钟", OfficialNavigationText.route(
            listOf(OfficialRouteSummary("a", 500, 60), OfficialRouteSummary("b", 1_000, 120)), 1))
        val progress = OfficialNavigationProgress(currentRoadName = "当前测试路", nextRoadName = "下一测试路",
            remainingDistanceMeters = 900, remainingDurationSeconds = 59)
        assertEquals("下一测试路 · 剩余 900 m · 59 秒", OfficialNavigationText.progress(progress))
        assertEquals("当前测试路 · 剩余 900 m · 59 秒", OfficialNavigationText.progress(progress.copy(nextRoadName = null)))
        assertEquals("导航中 · 剩余 900 m · 59 秒", OfficialNavigationText.progress(progress.copy(nextRoadName = null, currentRoadName = null)))
    }

    /** 覆盖车道、路口、电子眼、服务区、路况、限速、途经点、偏航和到达的有效详情。 */
    @Test
    fun `rich guidance events remain visible after extraction`() {
        val cases = listOf(
            OfficialNavigationEvent.LaneGuidance(true, 3) to "车道引导 · 3 条车道",
            OfficialNavigationEvent.JunctionView(true) to "前方路口放大图",
            OfficialNavigationEvent.CameraAlerts(listOf(OfficialCameraAlert(100, vendorType = 0))) to "前方 100 m 电子眼",
            OfficialNavigationEvent.ServiceAreas(listOf(OfficialServiceArea(remainingDistanceMeters = 500))) to "前方服务区 500 m",
            OfficialNavigationEvent.TrafficStatus(segments = listOf(OfficialTrafficSegment(100, 1))) to "路况已更新：1 段",
            OfficialNavigationEvent.SpeedLimit(60, null) to "当前 60 km/h",
            OfficialNavigationEvent.SpeedLimit(60, 80) to "当前 60 km/h · 限速 80 km/h",
            OfficialNavigationEvent.WaypointArrived(0) to "已到达第 1 个途经点",
            OfficialNavigationEvent.WaypointProgress(listOf(OfficialWaypointProgress(1, 700, 60))) to "途经点 2，剩余 700 m",
            OfficialNavigationEvent.VoicePrompt("测试语音") to "测试语音",
            OfficialNavigationEvent.Rerouting to "偏航，正在重新规划路线",
            OfficialNavigationEvent.RerouteCompleted(true) to "路线重新规划完成",
            OfficialNavigationEvent.RerouteCompleted(false) to "路线重新规划失败：未知错误",
            OfficialNavigationEvent.Arrived to "已到达目的地",
        )
        cases.forEach { (event, text) -> assertEquals(text, OfficialNavigationText.event(event)) }
    }
}
