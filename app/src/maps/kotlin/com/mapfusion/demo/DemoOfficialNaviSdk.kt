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
import com.mapfusion.factory.MapFusion
import com.mapfusion.full.MapFusionFull

/** 双基础地图变体不装载厂商 Navi 原生库。 */
internal object DemoOfficialNaviSdk {
    val defaultProvider = Provider.BAIDU

    /** 双地图变体允许切换任一基础地图厂商，不因此注册或初始化 SDK。 */
    fun supportsMapProvider(provider: Provider): Boolean = provider in Provider.entries
    /** 本变体没有官方导航库，始终明确返回不支持。 */
    fun supportsOfficialNavigation(provider: Provider): Boolean = false
    /** 用户同意后才由宿主调用，注册双厂商基础地图工厂。 */
    fun install() = MapFusionFull.install()
    /** 未装载官方导航，返回 null 而不伪造可用能力表。 */
    fun capabilities(provider: Provider): OfficialNavigationCapabilities? = null

    /** 通过统一工厂同步指定厂商的隐私决定，保留实际失败结果。 */
    fun updatePrivacyConsent(context: Context, provider: Provider, consentGranted: Boolean): MapResult<Unit> =
        MapFusion.updatePrivacyConsent(context, provider, consentGranted)

    /** 阻止 maps 变体创建官方导航会话，返回标准 UNSUPPORTED 错误。 */
    fun createNavigator(
        activity: Activity,
        provider: Provider,
        savedState: Bundle?,
    ): MapResult<OfficialNavigator> = MapResult.Failure(
        MapError(ErrorType.UNSUPPORTED, "maps 变体未装载官方 Navi 模块"),
    )
}
