package com.mapfusion.demo.main

import com.mapfusion.demo.presentation.main.LocationPermissionAction

/** 首页功能组合根；只装配独立功能及其明确互斥关系，不承接布局或全部业务方法。 */
internal class MainFeatures(
    /** Activity 绑定的地图资源宿主；各功能只经它访问会话、请求守卫与消息入口。 */
    private val host: MainMapHost
) : AutoCloseable {
    /** 定位展示功能；定位配置在此，原生定位句柄仍归 Session 所有。 */
    val location = MainLocationFeature(host)
    /** 轨迹功能，是轨迹录制器的唯一拥有者，切换会话或关闭时显式释放。 */
    val track = MainTrackFeature(host)
    /** 单次查询功能，统一经请求仓库做同键替换与迟到回调隔离。 */
    val search = MainSearchFeature(host)
    /** 路线规划与内嵌导航功能；发起导航前先关闭轨迹和定位展示。 */
    val route = MainRouteFeature(host, track::close)
    /** 覆盖物演示功能；全局清空时同时停止导航、轨迹与定位展示。 */
    val overlay = MainOverlayFeature(host, track::close)

    init { host.beforeSessionRelease = track::close }

    /** 位置授权成功后执行对应功能，真实导航使用 ViewModel 中已提交的出行选择。 */
    fun executeLocationAction(action: LocationPermissionAction) = when (action) {
        LocationPermissionAction.LOCATE -> location.start()
        LocationPermissionAction.START_TRACK -> track.start()
        LocationPermissionAction.START_NAVIGATION -> route.start(host.viewModel.state.value.routeMode)
    }

    /** 释放由功能层单独持有的轨迹句柄；导航和定位展示仍由 Session 统一销毁。 */
    override fun close() { track.close() }
}
