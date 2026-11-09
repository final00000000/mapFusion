package com.mapfusion.demo.main

import com.mapfusion.api.model.EmbeddedNavigationEvent
import com.mapfusion.api.model.EmbeddedNavigationMode
import com.mapfusion.api.model.EmbeddedNavigationOptions
import com.mapfusion.api.model.EmbeddedNavigationRequest
import com.mapfusion.api.model.EmbeddedNavigationState
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.MarkerIcon
import com.mapfusion.api.model.RouteRequest
import com.mapfusion.api.model.RouteResult
import com.mapfusion.api.model.TravelMode

/** 路线规划与轻量导航功能；不创建官方导航 UI，也不负责系统权限申请。 */
internal class MainRouteFeature(private val host: MainMapHost, private val releaseTrack: () -> Unit) {
    /** 请求统一路线接口并托管同键替换，厂商不支持的出行方式明确失败。 */
    fun plan(mode: TravelMode) = host.request("${mode.displayName()}路线") { epoch ->
        val planner = requireNotNull(host.session?.routePlanner) { "当前厂商不支持路线规划" }
        if (!planner.supportsMode(mode)) {
            host.messages.mapError("路线规划失败", MapError(ErrorType.UNSUPPORTED,
                "${host.provider?.provider?.displayName()}原生 SDK 不支持该方式"))
            return@request
        }
        val request = routeRequest(mode)
        host.requests.execute("route", { callback -> planner.plan(request, callback) }, MapCallback { result ->
            host.deliver(epoch) { when (result) {
                is MapResult.Success -> showRoute(request, result.data)
                is MapResult.Failure -> host.messages.mapError("路线规划失败", result.error)
            } }
        })
    }

    /** 只绘制选定方案的 Demo 路线；没有路线或绘制异常均保留失败状态。 */
    private fun showRoute(request: RouteRequest, result: RouteResult) {
        val path = result.paths.firstOrNull()
        if (path == null) { host.messages.error("路线规划失败", "厂商返回成功但没有路线"); return }
        val drawError = runCatching {
            host.controller?.apply {
                host.overlays.clear(); addRoute(request, path, DemoMapAssets.routeOptions())
                fitPoints(path.polyline.ifEmpty { path.steps.flatMap { it.polyline } }, paddingPixels = host.dp(56))
            }
        }.exceptionOrNull()
        if (drawError != null) { host.messages.error("路线绘制失败", drawError.message ?: drawError::class.java.simpleName); return }
        host.messages.success("${result.mode.displayName()}路线成功 · ${result.paths.size} 条方案",
            "${DemoLabels.distance(path.distanceMeters)} · ${DemoLabels.duration(path.durationSeconds)} · ${path.steps.size} 个步骤")
    }

    /** 从当前定位或公开示例点生成请求，公交仍保留原 Demo 的城市参数。 */
    private fun routeRequest(mode: TravelMode): RouteRequest {
        val origin = host.coordinates.center()
        return RouteRequest(mode = mode, origin = origin, destination = host.coordinates.destination(origin),
            city = "北京".takeIf { mode.canonical() == TravelMode.TRANSIT })
    }

    /** 导航前显式释放轨迹与定位展示；启动时捕获会话代次和身份用于迟到事件隔离。 */
    fun start(mode: TravelMode) = host.perform("发起${mode.displayName()}导航") {
        val session = requireNotNull(host.session) { "地图尚未初始化" }
        val navigator = requireNotNull(session.embeddedNavigator) { "当前厂商必须同时支持路线规划和连续定位" }
        check(session.routePlanner?.supportsMode(mode) != false) { "当前厂商不支持${mode.displayName()}导航" }
        session.locationDisplay?.stop(); releaseTrack()
        val navigationMode = host.viewModel.state.value.navigationMode
        val request = EmbeddedNavigationRequest(routeRequest(mode), EmbeddedNavigationOptions(
            routeOverlay = DemoMapAssets.routeOptions(), showCurrentMarker = true,
            currentMarkerIcon = MarkerIcon.Bytes(DemoMapAssets.endpoint("我", DemoMapAssets.current)),
            followLocation = true, followZoom = 17f, navigationMode = navigationMode,
            simulationSpeedMetersPerSecond = mode.simulationSpeedMetersPerSecond(), simulationIntervalMillis = 500L))
        val epoch = host.requests.generation
        when (val result = navigator.start(request) { event -> host.deliver(epoch) {
            if (host.session === session) handleEvent(mode, navigationMode, event)
        } }) {
            is MapResult.Success -> {
                host.viewModel.setNavigationActive(navigator.state in ACTIVE_STATES)
                host.messages.info("${mode.displayName()}${navigationMode.displayName()}规划中", "路线将在当前地图内展示")
            }
            is MapResult.Failure -> host.messages.mapError("${mode.displayName()}导航启动失败", result.error)
        }
    }

    /** 根据组件真实状态选择暂停或继续，不根据按钮文字推断导航状态。 */
    fun pauseOrResume() {
        if (host.session?.embeddedNavigator?.state == EmbeddedNavigationState.PAUSED) resume() else pause()
    }

    /** 暂停导航推进，SDK 失败不更新为已暂停。 */
    fun pause() = control(pause = true)

    /** 继续导航推进，SDK 失败不更新为已继续。 */
    fun resume() = control(pause = false)

    /** 统一暂停和继续结果处理，原生组件仍由 Session 拥有。 */
    private fun control(pause: Boolean) = host.perform(if (pause) "暂停导航" else "继续导航") {
        val navigator = requireNotNull(host.session?.embeddedNavigator) { "请先开始导航" }
        when (val result = if (pause) navigator.pause() else navigator.resume()) {
            is MapResult.Success -> host.messages.info(if (pause) "导航已暂停" else "导航已继续",
                if (pause) "导航推进已停止" else "正在恢复导航推进")
            is MapResult.Failure -> host.messages.mapError(if (pause) "暂停导航失败" else "继续导航失败", result.error)
        }
    }

    /** 用户显式停止轻导航，成功后清除运行标记但不清普通 Demo 覆盖物。 */
    fun stop() = host.perform("停止导航") {
        when (val result = requireNotNull(host.session?.embeddedNavigator) { "请先开始导航" }.stop()) {
            is MapResult.Success -> { host.viewModel.setNavigationActive(false); host.messages.info("导航已停止", "已移除导航路线和当前位置覆盖物") }
            is MapResult.Failure -> host.messages.mapError("停止导航失败", result.error)
        }
    }

    /** 映射导航事件到结果状态，路线就绪时适配视野，终态清除活跃标记。 */
    private fun handleEvent(mode: TravelMode, navigation: EmbeddedNavigationMode, event: EmbeddedNavigationEvent) {
        when (event) {
            is EmbeddedNavigationEvent.StateChanged -> {
                host.viewModel.setNavigationActive(event.state in ACTIVE_STATES)
                val detail = when (event.state) {
                    EmbeddedNavigationState.PLANNING -> "正在规划路线"
                    EmbeddedNavigationState.NAVIGATING -> if (navigation == EmbeddedNavigationMode.REAL) "正在跟随真实定位" else "正在沿规划路线模拟推进"
                    EmbeddedNavigationState.PAUSED -> "导航推进已暂停"; EmbeddedNavigationState.REROUTING -> "偏航，正在重新规划"
                    EmbeddedNavigationState.ARRIVED -> "已到达目的地"; EmbeddedNavigationState.STOPPED -> "导航已停止"
                    EmbeddedNavigationState.FAILED -> "导航失败"; EmbeddedNavigationState.IDLE -> "等待开始"
                    EmbeddedNavigationState.DESTROYED -> "会话已释放"
                }
                host.messages.info("${mode.displayName()}${navigation.displayName()} · ${event.state}", detail)
            }
            is EmbeddedNavigationEvent.RouteReady -> {
                val path = event.selectedPath
                host.controller?.fitPoints(path.polyline.ifEmpty { path.steps.flatMap { it.polyline } }, paddingPixels = host.dp(56))
                host.messages.success("${mode.displayName()}导航路线已就绪", "${DemoLabels.distance(path.distanceMeters)} · ${DemoLabels.duration(path.durationSeconds)}")
            }
            is EmbeddedNavigationEvent.Progress -> {
                val progress = event.value
                host.messages.info("${mode.displayName()}导航进行中", "${progress.navigationMode.displayName()} · " +
                    "剩余 ${DemoLabels.distance(progress.remainingDistanceMeters.toInt())} · ${DemoLabels.duration(progress.remainingDurationSeconds)} · " +
                    (progress.currentInstruction ?: "步骤 ${progress.currentStepIndex + 1}"))
            }
            is EmbeddedNavigationEvent.RerouteStarted -> host.messages.info("导航偏航", "正在从当前位置重新规划")
            is EmbeddedNavigationEvent.Arrived -> { host.viewModel.setNavigationActive(false); host.messages.success("导航已到达", "已抵达目的地") }
            is EmbeddedNavigationEvent.Error -> { host.viewModel.setNavigationActive(false); host.messages.mapError("导航错误", event.error) }
        }
    }

    /** 轻导航仍在占用运行资源的状态集合，不包含到达、失败或销毁。 */
    private companion object {
        val ACTIVE_STATES = setOf(EmbeddedNavigationState.PLANNING, EmbeddedNavigationState.NAVIGATING,
            EmbeddedNavigationState.PAUSED, EmbeddedNavigationState.REROUTING)
    }
}
