package com.mapfusion.baidu.navi

import com.baidu.navisdk.adapter.BaiduNaviManagerFactory
import com.baidu.navisdk.adapter.IBaiduNaviManager
import com.baidu.navisdk.adapter.IBNMapManager
import com.baidu.navisdk.adapter.IBNOuterSettingManager
import com.baidu.navisdk.adapter.IBNRouteGuideManager
import com.baidu.navisdk.adapter.IBNRoutePlanManager
import com.baidu.navisdk.adapter.IBNTTSManager
import com.mapfusion.api.capability.OfficialNavigator

/**
 * 百度官方导航高级能力访问口。
 *
 * 返回对象由百度单例引擎和 [OfficialNavigator] 会话持有，调用方不得重新初始化或销毁它们。
 */
class BaiduNaviNativeAccess internal constructor() {
    private fun sessionOf(navigator: OfficialNavigator): BaiduOfficialNavigator? =
        (navigator as? BaiduOfficialNavigator)?.takeIf { it.nativeAccessAvailable }

    fun naviManagerOf(navigator: OfficialNavigator): IBaiduNaviManager? = sessionOf(navigator)?.naviManager
    fun routePlanManagerOf(navigator: OfficialNavigator): IBNRoutePlanManager? = sessionOf(navigator)?.routePlanManager
    fun routeGuideManagerOf(navigator: OfficialNavigator): IBNRouteGuideManager? = sessionOf(navigator)?.routeGuideManager
    fun professionalSettingOf(navigator: OfficialNavigator): IBNOuterSettingManager.IBNProfessionalNaviSetting? =
        sessionOf(navigator)?.let { BaiduNaviManagerFactory.getProfessionalNaviSettingManager() }
    fun commonSettingOf(navigator: OfficialNavigator): IBNOuterSettingManager.IBNCommonSetting? =
        sessionOf(navigator)?.let { BaiduNaviManagerFactory.getCommonSettingManager() }
    fun ttsManagerOf(navigator: OfficialNavigator): IBNTTSManager? =
        sessionOf(navigator)?.let { BaiduNaviManagerFactory.getTTSManager() }
    fun mapManagerOf(navigator: OfficialNavigator): IBNMapManager? =
        sessionOf(navigator)?.let { BaiduNaviManagerFactory.getMapManager() }
}
