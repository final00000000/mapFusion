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

internal object DemoOfficialNaviSdk {
    val defaultProvider = Provider.AMAP

    fun supportsMapProvider(provider: Provider): Boolean = provider == Provider.AMAP
    fun supportsOfficialNavigation(provider: Provider): Boolean = provider == Provider.AMAP
    fun install() = AmapNaviSdk.install()

    fun capabilities(provider: Provider): OfficialNavigationCapabilities? =
        AmapNaviSdk.capabilities().takeIf { provider == Provider.AMAP }

    fun updatePrivacyConsent(context: Context, provider: Provider, consentGranted: Boolean): MapResult<Unit> =
        if (provider == Provider.AMAP) {
            AmapNaviSdk.updatePrivacyConsent(context, consentGranted)
        } else {
            unsupported(provider)
        }

    fun createNavigator(
        activity: Activity,
        provider: Provider,
        savedState: Bundle?,
    ): MapResult<OfficialNavigator> = if (provider == Provider.AMAP) {
        AmapNaviSdk.createNavigator(activity, savedState)
    } else {
        unsupported(provider)
    }

    private fun <T> unsupported(provider: Provider): MapResult<T> = MapResult.Failure(
        MapError(ErrorType.UNSUPPORTED, "amapNavi 变体不包含 $provider"),
    )
}
