package com.mapfusion.amap.navi

import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviView
import com.mapfusion.api.capability.OfficialNavigator

/**
 * 高德官方导航高级能力访问口。
 *
 * 返回对象由 [OfficialNavigator] 会话持有，调用方不得销毁或重新初始化它们；会话销毁后也不得继续使用。
 */
class AmapNaviNativeAccess internal constructor() {
    fun engineOf(navigator: OfficialNavigator): AMapNavi? =
        (navigator as? AmapOfficialNavigator)?.takeIf { it.nativeAccessAvailable }?.nativeEngine

    fun viewOf(navigator: OfficialNavigator): AMapNaviView? =
        (navigator as? AmapOfficialNavigator)?.takeIf { it.nativeAccessAvailable }?.view
}
