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

/** 高德官方导航变体入口，只装载高德兼容组合，不混入百度原生库。 */
internal object DemoOfficialNaviSdk {
    /** 高德 Navi 变体默认/唯一允许的地图厂商，用于规范化恢复选择，不是引擎实例。 */
    val defaultProvider = Provider.AMAP

    /** 限定当前变体可用地图厂商，不静默回退到未打包的 SDK。 */
    fun supportsMapProvider(provider: Provider): Boolean = provider == Provider.AMAP
    /** 官方导航只对高德开放，页面据此决定是否展示入口。 */
    fun supportsOfficialNavigation(provider: Provider): Boolean = provider == Provider.AMAP
    /** 宿主完成隐私同意后注册高德地图及官方导航工厂。 */
    fun install() = AmapNaviSdk.install()

    /** 返回锁定高德 SDK 的真实能力表，其他厂商不返回伪造能力。 */
    fun capabilities(provider: Provider): OfficialNavigationCapabilities? =
        AmapNaviSdk.capabilities().takeIf { provider == Provider.AMAP }

    /** 仅向打包的高德组合提交隐私决定，其他厂商明确失败。 */
    fun updatePrivacyConsent(context: Context, provider: Provider, consentGranted: Boolean): MapResult<Unit> =
        if (provider == Provider.AMAP) {
            AmapNaviSdk.updatePrivacyConsent(context, consentGranted)
        } else {
            unsupported(provider)
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

    /** 为当前变体没有的厂商返回统一不支持结果。 */
    private fun <T> unsupported(provider: Provider): MapResult<T> = MapResult.Failure(
        MapError(ErrorType.UNSUPPORTED, "amapNavi 变体不包含 $provider"),
    )
}
