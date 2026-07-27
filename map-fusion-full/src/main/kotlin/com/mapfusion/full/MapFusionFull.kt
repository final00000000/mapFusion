package com.mapfusion.full

import android.content.Context
import android.os.Bundle
import com.mapfusion.amap.AmapProviderFactory
import com.mapfusion.api.MapConfig
import com.mapfusion.api.MapProvider
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.MapResult
import com.mapfusion.baidu.BaiduProviderFactory
import com.mapfusion.factory.MapFusion
import com.mapfusion.factory.MapFusionSession

/**
 * 同时携带百度和高德适配器的快速入口。
 *
 * [install] 仅把工厂放入内存注册表，不初始化厂商 SDK；真正初始化仍发生在宿主完成
 * 隐私告知并以 `enablePrivacyCompliance=true` 调用 [create] 或 [openSession] 时。
 */
object MapFusionFull {

    /** 幂等注册双厂商适配器。 */
    @JvmStatic
    @Synchronized
    fun install() = MapFusion.register(BaiduProviderFactory(), AmapProviderFactory())

    @JvmStatic
    fun create(context: Context, config: MapConfig): MapProvider {
        install()
        return MapFusion.create(context, config)
    }

    /** 一步创建带生命周期和能力托管的地图会话。 */
    @JvmStatic
    @JvmOverloads
    fun openSession(
        context: Context,
        config: MapConfig,
        savedState: Bundle? = null,
    ): MapFusionSession {
        install()
        return MapFusion.openSession(context, config, savedState)
    }

    /** 同步所有已注册厂商的隐私同意/撤回标记，不创建地图实例。 */
    @JvmStatic
    fun updatePrivacyConsent(
        context: Context,
        consentGranted: Boolean,
    ): Map<Provider, MapResult<Unit>> {
        install()
        return MapFusion.updatePrivacyConsent(context, consentGranted)
    }
}
