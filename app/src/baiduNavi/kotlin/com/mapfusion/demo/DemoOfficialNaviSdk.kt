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

internal object DemoOfficialNaviSdk {
    val defaultProvider = Provider.BAIDU

    fun supportsMapProvider(provider: Provider): Boolean = provider == Provider.BAIDU
    fun supportsOfficialNavigation(provider: Provider): Boolean = provider == Provider.BAIDU
    fun install() = BaiduNaviSdk.install()

    fun capabilities(provider: Provider): OfficialNavigationCapabilities? =
        BaiduNaviSdk.capabilities().takeIf { provider == Provider.BAIDU }

    fun updatePrivacyConsent(context: Context, provider: Provider, consentGranted: Boolean): MapResult<Unit> =
        if (provider == Provider.BAIDU) {
            BaiduNaviSdk.updatePrivacyConsent(context, consentGranted)
        } else {
            unsupported(provider)
        }

    fun createNavigator(
        activity: Activity,
        provider: Provider,
        savedState: Bundle?,
    ): MapResult<OfficialNavigator> = if (provider == Provider.BAIDU) {
        BaiduNaviSdk.createNavigator(activity, savedState)
    } else {
        unsupported(provider)
    }

    private fun <T> unsupported(provider: Provider): MapResult<T> = MapResult.Failure(
        MapError(ErrorType.UNSUPPORTED, "baiduNavi 变体不包含 $provider"),
    )
}
