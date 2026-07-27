package com.mapfusion.amap

import android.content.Context
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings
import com.mapfusion.api.MapConfig
import com.mapfusion.api.MapProvider
import com.mapfusion.api.MapProviderFactory
import com.mapfusion.api.capability.Provider

/** 高德地图适配器工厂，负责各组件隐私声明、Key 注入与初始化。 */
class AmapProviderFactory : MapProviderFactory {

    override val provider: Provider = Provider.AMAP

    override fun updatePrivacyConsent(context: Context, consentGranted: Boolean) {
        val appContext = context.applicationContext
        MapsInitializer.updatePrivacyShow(appContext, true, true)
        MapsInitializer.updatePrivacyAgree(appContext, consentGranted)
        AMapLocationClient.updatePrivacyShow(appContext, true, true)
        AMapLocationClient.updatePrivacyAgree(appContext, consentGranted)
        ServiceSettings.updatePrivacyShow(appContext, true, true)
        ServiceSettings.updatePrivacyAgree(appContext, consentGranted)
    }

    override fun create(context: Context, config: MapConfig): MapProvider {
        require(config.provider == provider) {
            "AmapProviderFactory 只能创建 AMAP 实例，实际配置为 ${config.provider}"
        }
        config.requirePrivacyConsent()
        val appContext = context.applicationContext
        updatePrivacyConsent(appContext, true)

        MapsInitializer.setApiKey(config.apiKey)
        AMapLocationClient.setApiKey(config.apiKey)
        ServiceSettings.getInstance().setApiKey(config.apiKey)
        MapsInitializer.initialize(appContext)
        return AmapMapProvider(appContext, config.apiKey)
    }
}
