package com.mapfusion.demo

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.mapfusion.api.capability.OfficialNavigator
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.OfficialNavigationCapabilities
import com.mapfusion.baidu.navi.BaiduNaviSdk

/** 百度官方导航变体入口，只装载百度兼容组合，不混入高德原生库。 */
internal object DemoOfficialNaviSdk {
    val defaultProvider = Provider.BAIDU

    /** 限定当前变体可用地图厂商，不静默回退到未打包的 SDK。 */
    fun supportsMapProvider(provider: Provider): Boolean = provider == Provider.BAIDU
    /** 官方导航只对百度开放，页面据此决定是否展示入口。 */
    fun supportsOfficialNavigation(provider: Provider): Boolean = provider == Provider.BAIDU
    /** 宿主完成隐私同意后注册百度地图及官方导航工厂。 */
    fun install() = BaiduNaviSdk.install()

    /** 返回锁定百度 SDK 的真实能力表，其他厂商不返回伪造能力。 */
    fun capabilities(provider: Provider): OfficialNavigationCapabilities? =
        BaiduNaviSdk.capabilities().takeIf { provider == Provider.BAIDU }

    /** 仅向打包的百度组合提交隐私决定，其他厂商明确失败。 */
    fun updatePrivacyConsent(context: Context, provider: Provider, consentGranted: Boolean): MapResult<Unit> =
        if (provider == Provider.BAIDU) {
            BaiduNaviSdk.updatePrivacyConsent(context, consentGranted)
        } else {
            unsupported(provider)
        }

    /** 在传入 Activity 上创建百度官方导航宿主，原生 Bundle 由页面转发。 */
    fun createNavigator(
        activity: Activity,
        provider: Provider,
        savedState: Bundle?,
    ): MapResult<OfficialNavigator> = if (provider == Provider.BAIDU) {
        BaiduNaviSdk.createNavigator(activity, savedState)
    } else {
        unsupported(provider)
    }

    /** 为当前变体没有的厂商返回统一不支持结果。 */
    private fun <T> unsupported(provider: Provider): MapResult<T> = MapResult.Failure(
        MapError(ErrorType.UNSUPPORTED, "baiduNavi 变体不包含 $provider"),
    )
}
