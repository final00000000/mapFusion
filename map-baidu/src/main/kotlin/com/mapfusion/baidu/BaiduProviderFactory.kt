package com.mapfusion.baidu

import android.content.Context
import com.baidu.location.LocationClient as NativeLocationClient
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer
import com.mapfusion.api.MapConfig
import com.mapfusion.api.MapProvider
import com.mapfusion.api.MapProviderFactory
import com.mapfusion.api.capability.Provider

/**
 * 百度地图适配器工厂。
 *
 * 负责百度地图、定位 SDK 的隐私声明、AK 注入与全局初始化。
 */
class BaiduProviderFactory : MapProviderFactory {

    override val provider: Provider = Provider.BAIDU

    override fun updatePrivacyConsent(context: Context, consentGranted: Boolean) {
        val appContext = context.applicationContext
        SDKInitializer.setAgreePrivacy(appContext, consentGranted)
        NativeLocationClient.setAgreePrivacy(consentGranted)
    }

    override fun create(context: Context, config: MapConfig): MapProvider {
        require(config.provider == provider) {
            "BaiduProviderFactory 只能创建 BAIDU 实例，实际配置为 ${config.provider}"
        }
        config.requirePrivacyConsent()
        val appContext = context.applicationContext
        updatePrivacyConsent(appContext, true)
        SDKInitializer.setApiKey(config.apiKey)
        NativeLocationClient.setKey(config.apiKey)
        SDKInitializer.setCoordType(CoordType.BD09LL)
        SDKInitializer.setDebugMode(config.debug)
        if (!SDKInitializer.isInitialized()) {
            SDKInitializer.initialize(appContext)
        }
        return BaiduMapProvider(appContext, config.apiKey)
    }
}
