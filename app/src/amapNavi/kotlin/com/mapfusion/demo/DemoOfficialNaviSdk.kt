package com.mapfusion.demo

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.mapfusion.amap.navi.AmapNaviSdk
import com.mapfusion.api.capability.OfficialNavigator
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.OfficialNavigationCapabilities
import com.mapfusion.baidu.BaiduProviderFactory
import com.mapfusion.factory.MapFusion

/** 高德官方导航变体入口：同时注册高德与百度基础地图工厂以支持运行时切换，官方导航由高德 Navi 承接。 */
internal object DemoOfficialNaviSdk {
    /** 高德 Navi 变体冷启动默认选择的地图厂商，用于规范化恢复选择，不是引擎实例。 */
    val defaultProvider = Provider.AMAP

    /** 双厂商基础地图均已打包，允许运行时动态切换任一基础地图厂商。 */
    fun supportsMapProvider(provider: Provider): Boolean = provider in Provider.entries

    /** 官方导航只对高德开放，页面据此决定是否展示入口。 */
    fun supportsOfficialNavigation(provider: Provider): Boolean = provider == Provider.AMAP

    /** 宿主完成隐私同意后注册高德官方导航及百度基础地图工厂。 */
    fun install() {
        AmapNaviSdk.install()
        MapFusion.register(BaiduProviderFactory())
    }

    /** 返回锁定高德 SDK 的真实能力表，其他厂商不返回伪造能力。 */
    fun capabilities(provider: Provider): OfficialNavigationCapabilities? =
        AmapNaviSdk.capabilities().takeIf { provider == Provider.AMAP }

    /** 向指定厂商提交隐私决定；高德同步官方导航隐私，百度同步基础地图隐私。 */
    fun updatePrivacyConsent(context: Context, provider: Provider, consentGranted: Boolean): MapResult<Unit> =
        if (provider == Provider.AMAP) {
            AmapNaviSdk.updatePrivacyConsent(context, consentGranted)
        } else {
            MapFusion.updatePrivacyConsent(context, provider, consentGranted)
        }

    /** 在传入 Activity 上创建高德官方导航宿主，原生 Bundle 由页面转发。 */
    fun createNavigator(
        activity: Activity,
        provider: Provider,
        savedState: Bundle?,
    ): MapResult<OfficialNavigator> = if (provider == Provider.AMAP) {
        AmapNaviSdk.createNavigator(activity, savedState)
    } else {
        unsupported(provider)
    }

    /** 为当前变体没有官方导航的厂商返回统一不支持结果。 */
    private fun <T> unsupported(provider: Provider): MapResult<T> = MapResult.Failure(
        MapError(ErrorType.UNSUPPORTED, "amapNavi 变体不包含 $provider"),
    )
}
