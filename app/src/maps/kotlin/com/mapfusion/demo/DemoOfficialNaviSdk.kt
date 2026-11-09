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

    fun supportsMapProvider(provider: Provider): Boolean = provider in Provider.entries
    fun supportsOfficialNavigation(provider: Provider): Boolean = false
    fun install() = MapFusionFull.install()
    fun capabilities(provider: Provider): OfficialNavigationCapabilities? = null

    fun updatePrivacyConsent(context: Context, provider: Provider, consentGranted: Boolean): MapResult<Unit> =
        MapFusion.updatePrivacyConsent(context, provider, consentGranted)

    fun createNavigator(
        activity: Activity,
        provider: Provider,
        savedState: Bundle?,
    ): MapResult<OfficialNavigator> = MapResult.Failure(
        MapError(ErrorType.UNSUPPORTED, "maps 变体未装载官方 Navi 模块"),
    )
}
