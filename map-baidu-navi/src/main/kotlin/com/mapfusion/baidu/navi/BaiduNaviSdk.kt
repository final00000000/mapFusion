package com.mapfusion.baidu.navi

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.mapfusion.api.MapConfig
import com.mapfusion.api.capability.OfficialNavigator
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.OfficialNavigationCapabilities
import com.mapfusion.baidu.BaiduProviderFactory
import com.mapfusion.factory.MapFusion
import com.mapfusion.factory.MapFusionSession

/** 百度地图与官方 Navi 组合包的一步接入入口。 */
object BaiduNaviSdk {
    private val navigatorFactory = BaiduOfficialNavigatorFactory()
    private val nativeAccessInstance = BaiduNaviNativeAccess()

    @JvmStatic
    fun install() = MapFusion.register(BaiduProviderFactory())

    @JvmStatic
    fun updatePrivacyConsent(context: Context, consentGranted: Boolean): MapResult<Unit> {
        install()
        return MapFusion.updatePrivacyConsent(context, Provider.BAIDU, consentGranted)
    }

    @JvmStatic
    @JvmOverloads
    fun openMapSession(
        context: Context,
        config: MapConfig,
        savedState: Bundle? = null,
    ): MapFusionSession {
        require(config.provider == Provider.BAIDU) { "BaiduNaviSdk 只接受 Provider.BAIDU 配置" }
        install()
        return MapFusion.openSession(context, config, savedState)
    }

    @JvmStatic
    @JvmOverloads
    fun createNavigator(
        activity: Activity,
        savedState: Bundle? = null,
    ): MapResult<OfficialNavigator> = navigatorFactory.create(activity, savedState)

    @JvmStatic
    fun capabilities(): OfficialNavigationCapabilities = navigatorFactory.capabilities

    /** 获取复用当前统一导航会话的百度原生 API 访问口。 */
    @JvmStatic
    fun nativeAccess(): BaiduNaviNativeAccess = nativeAccessInstance
}
