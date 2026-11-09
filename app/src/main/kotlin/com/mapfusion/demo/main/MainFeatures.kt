package com.mapfusion.demo.main

import com.mapfusion.demo.presentation.main.LocationPermissionAction

/** 首页功能组合根；只装配独立功能及其明确互斥关系，不承接布局或全部业务方法。 */
internal class MainFeatures(private val host: MainMapHost) : AutoCloseable {
    val location = MainLocationFeature(host)
    val track = MainTrackFeature(host)
    val search = MainSearchFeature(host)
    val route = MainRouteFeature(host, track::close)
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
