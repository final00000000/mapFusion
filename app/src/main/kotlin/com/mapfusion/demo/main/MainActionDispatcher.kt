package com.mapfusion.demo.main

import com.mapfusion.demo.presentation.main.LocationPermissionAction

/** 首页用户意图路由；只选择独立功能入口，不包含 SDK 请求、布局或状态副本。 */
internal class MainActionDispatcher(private val features: MainFeatures,
    private val requestLocation: (LocationPermissionAction) -> Unit,
    private val showMapSettings: () -> Unit, private val showRouteOptions: () -> Unit,
    private val openOfficialNavigation: () -> Unit) {

    /** 对每个可见入口进行显式分发，编译器检查新增 MainAction 是否遗漏处理。 */
    fun dispatch(action: MainAction) {
        when (action) {
            MainAction.LOCATE -> requestLocation(LocationPermissionAction.LOCATE)
            MainAction.MAP_SETTINGS -> showMapSettings()
            MainAction.SNAPSHOT -> features.overlay.snapshot()
            MainAction.ROUTE_OPTIONS -> showRouteOptions()
            MainAction.OFFICIAL_NAVIGATION -> openOfficialNavigation()
            MainAction.NAVIGATION_PAUSE_RESUME -> features.route.pauseOrResume()
            MainAction.NAVIGATION_STOP -> features.route.stop()
            MainAction.GEOCODE -> features.search.geocode()
            MainAction.REVERSE_GEOCODE -> features.search.reverseGeocode()
            MainAction.POI -> features.search.poi()
            MainAction.DISTRICT -> features.search.district()
            MainAction.WEATHER_NOW -> features.search.weatherNow()
            MainAction.WEATHER_FORECAST -> features.search.weatherForecast()
            MainAction.MARKER, MainAction.MULTI_POINT, MainAction.POLYLINE, MainAction.POLYGON,
            MainAction.CIRCLE, MainAction.TEXT, MainAction.GROUND, MainAction.HEAT_MAP, MainAction.CLEAR_MAP -> features.overlay.show(action)
            MainAction.TRACK_START -> requestLocation(LocationPermissionAction.START_TRACK)
            MainAction.TRACK_PAUSE_RESUME -> features.track.pauseOrResume()
            MainAction.TRACK_STOP -> features.track.stop()
            MainAction.TRACK_CLEAR -> features.track.clear()
        }
    }
}
