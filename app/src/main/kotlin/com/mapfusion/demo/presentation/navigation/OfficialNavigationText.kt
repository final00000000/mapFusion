package com.mapfusion.demo.presentation.navigation

import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.OfficialNavigationDayNightMode
import com.mapfusion.api.model.OfficialNavigationEvent
import com.mapfusion.api.model.OfficialNavigationMode
import com.mapfusion.api.model.OfficialNavigationProgress
import com.mapfusion.api.model.OfficialNavigationState
import com.mapfusion.api.model.OfficialRouteSummary
import com.mapfusion.api.model.TravelMode
import java.util.Locale

/** 将统一导航值对象转换为中文展示文案；不读取引擎、资源或页面，不改变业务状态。 */
internal object OfficialNavigationText {
    /** 输入米数，千米保留一位小数并固定中文区域，避免设备区域影响事件展示。 */
    fun distance(meters: Int): String =
        if (meters < 1_000) "$meters m" else "%.1f km".format(Locale.CHINA, meters / 1_000.0)

    /** 输入秒数，按秒、分钟、小时分级显示，保持导航面板原有取整规则。 */
    fun duration(seconds: Int): String = when {
        seconds < 60 -> "$seconds 秒"
        seconds < 3_600 -> "${seconds / 60} 分钟"
        else -> "${seconds / 3_600} 小时 ${seconds % 3_600 / 60} 分钟"
    }

    /** 输入厂商或缺省值，返回标题使用的中文名称，不依赖厂商实现。 */
    fun provider(provider: Provider?): String = when (provider) {
        Provider.BAIDU -> "百度"
        Provider.AMAP -> "高德"
        null -> "地图"
    }

    /** 将真实、模拟两种统一模式映射为选择按钮文案。 */
    fun mode(mode: OfficialNavigationMode): String = when (mode) {
        OfficialNavigationMode.REAL -> "真实导航"
        OfficialNavigationMode.SIMULATED -> "模拟导航"
    }

    /** 输入出行方式，先归一骑行别名，确保请求、按钮和事件使用同一中文名称。 */
    fun travel(mode: TravelMode): String = when (mode.canonical()) {
        TravelMode.DRIVING -> "驾车"
        TravelMode.WALKING -> "步行"
        TravelMode.BICYCLE, TravelMode.RIDING -> "自行车"
        TravelMode.ELECTRIC_BICYCLE -> "电动车"
        TravelMode.TRANSIT -> "公交"
    }

    /** 将引擎状态映射为顶部徽章文案；错误细节由详情区独立展示。 */
    fun state(state: OfficialNavigationState): String = when (state) {
        OfficialNavigationState.INITIALIZING -> "引擎初始化"
        OfficialNavigationState.READY -> "可以开始"
        OfficialNavigationState.PLANNING -> "路线规划中"
        OfficialNavigationState.NAVIGATING -> "导航中"
        OfficialNavigationState.PAUSED -> "已暂停"
        OfficialNavigationState.ARRIVED -> "已到达"
        OfficialNavigationState.STOPPED -> "已停止"
        OfficialNavigationState.FAILED -> "发生错误"
        OfficialNavigationState.DESTROYED -> "已销毁"
    }

    /** 输入配色选择，返回完整名称，供设置成功后的详情提示使用。 */
    fun dayNight(mode: OfficialNavigationDayNightMode): String = when (mode) {
        OfficialNavigationDayNightMode.AUTO -> "自动"
        OfficialNavigationDayNightMode.DAY -> "白天"
        OfficialNavigationDayNightMode.NIGHT -> "夜间"
    }

    /** 根据当前配色生成紧凑按钮标签，不修改选择。 */
    fun dayNightButton(mode: OfficialNavigationDayNightMode): String = "${dayNight(mode)}色"

    /** 输入不可变路线列表和零基下标；无有效路线时不产生提示，避免覆盖既有详情。 */
    fun route(routes: List<OfficialRouteSummary>, index: Int): String? = routes.getOrNull(index)?.let {
        "路线 ${index + 1}/${routes.size} · ${distance(it.distanceMeters)} · ${duration(it.durationSeconds)}"
    }

    /** 输入导航进度，优先使用下一道路名称，并以统一单位展示剩余距离与时间。 */
    fun progress(progress: OfficialNavigationProgress): String {
        val road = progress.nextRoadName ?: progress.currentRoadName ?: "导航中"
        return "$road · 剩余 ${distance(progress.remainingDistanceMeters)} · ${duration(progress.remainingDurationSeconds)}"
    }

    /**
     * 输入厂商已归一的事件，返回可展示详情；隐藏的车道/路口、空集合和纯状态事件返回 null。
     * 这里只决定文案，路线下标、终态与 pending 等业务归约由 ViewModel 处理。
     */
    fun event(event: OfficialNavigationEvent): String? = when (event) {
        is OfficialNavigationEvent.StateChanged, is OfficialNavigationEvent.RoutesReady -> null
        is OfficialNavigationEvent.Progress -> progress(event.value)
        is OfficialNavigationEvent.VoicePrompt -> event.text
        is OfficialNavigationEvent.LaneGuidance ->
            if (event.visible) "车道引导 · ${event.laneCount} 条车道" else null
        is OfficialNavigationEvent.JunctionView -> if (event.visible) "前方路口放大图" else null
        is OfficialNavigationEvent.CameraAlerts -> event.cameras.firstOrNull()?.let {
            "前方 ${distance(it.distanceMeters)} 电子眼"
        }
        is OfficialNavigationEvent.IntervalCameraAlert -> {
            val meters = event.camera.remainingDistanceMeters
                ?: event.camera.endDistanceMeters ?: event.camera.startDistanceMeters
            if (meters != null) "区间测速，剩余 ${distance(meters)}" else "进入区间测速路段"
        }
        is OfficialNavigationEvent.ServiceAreas -> event.areas.firstOrNull()?.let {
            "${it.name ?: "前方服务区"} ${distance(it.remainingDistanceMeters)}"
        }
        is OfficialNavigationEvent.TrafficStatus ->
            if (event.segments.isNotEmpty()) "路况已更新：${event.segments.size} 段" else null
        is OfficialNavigationEvent.SpeedLimit -> event.limit?.let {
            "当前 ${event.current} km/h · 限速 $it km/h"
        } ?: "当前 ${event.current} km/h"
        is OfficialNavigationEvent.GpsStatus -> when {
            event.enabled == false -> "GPS 未开启"
            event.signalWeak -> "GPS 信号较弱"
            else -> "GPS 信号正常"
        }
        is OfficialNavigationEvent.WaypointArrived -> "已到达第 ${event.waypointIndex + 1} 个途经点"
        is OfficialNavigationEvent.WaypointProgress -> event.waypoints.firstOrNull()?.let {
            "途经点 ${it.waypointIndex + 1}，剩余 ${distance(it.remainingDistanceMeters)}"
        }
        is OfficialNavigationEvent.HighwayGuidance -> {
            val guidance = event.value
            val labels = listOfNotNull(
                guidance.highwayName, guidance.direction, guidance.exitName, guidance.tollGateName,
            )
            if (guidance.visible && labels.isNotEmpty()) labels.joinToString(" · ") else "高速引导已更新"
        }
        is OfficialNavigationEvent.RouteNotice ->
            event.value.message ?: event.value.subTitle ?: event.value.roadName ?: "路线提醒已更新"
        OfficialNavigationEvent.Rerouting -> "偏航，正在重新规划路线"
        is OfficialNavigationEvent.RerouteCompleted ->
            if (event.success) "路线重新规划完成" else "路线重新规划失败：${event.error?.message ?: "未知错误"}"
        OfficialNavigationEvent.Arrived -> "已到达目的地"
        is OfficialNavigationEvent.Error -> null
    }
}
