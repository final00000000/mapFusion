package com.mapfusion.factory

import android.content.Context
import android.os.Bundle
import com.mapfusion.api.MapConfig
import com.mapfusion.api.MapProvider
import com.mapfusion.api.MapProviderFactory
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.capability.LocationDisplay
import com.mapfusion.api.capability.TrackRecorder
import com.mapfusion.api.capability.MapController
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult

/**
 * 统一入口(门面)。业务方只跟这个类打交道:
 *
 * ```
 * // App 启动时(通常在 Application.onCreate),注册想用的适配器:
 * ProviderRegistry.register(BaiduProviderFactory())
 * ProviderRegistry.register(AmapProviderFactory())
 *
 * // 使用时按配置创建:
 * val map = MapFusion.create(
 *     context,
 *     MapConfig(
 *         provider = Provider.AMAP,
 *         apiKey = "你的高德Key",
 *         enablePrivacyCompliance = true,
 *     )
 * )
 * ```
 *
 * 换厂商 = 改 [MapConfig.provider] 一处 + 换 apiKey,业务代码其余不动。
 */
object MapFusion {

    /** 批量注册适配器,宿主 Application 中通常只需调用一次。 */
    fun register(vararg factories: MapProviderFactory) {
        factories.forEach(ProviderRegistry::register)
    }

    /**
     * 按配置创建厂商实现。
     *
     * @throws ProviderNotRegisteredException 目标厂商未注册(通常是没引入对应适配器模块,
     *         或忘了在启动时 register)。
     */
    fun create(context: Context, config: MapConfig): MapProvider {
        val factory = ProviderRegistry.factoryOf(config.provider)
            ?: throw ProviderNotRegisteredException(config.provider)
        return factory.create(context.applicationContext, config)
    }

    /**
     * 尝试创建;厂商未注册时返回 null 而非抛异常,便于做「优先 A,回退 B」的降级逻辑。
     */
    fun createOrNull(context: Context, config: MapConfig): MapProvider? {
        val factory = ProviderRegistry.factoryOf(config.provider) ?: return null
        return factory.create(context.applicationContext, config)
    }

    /**
     * 快速接入入口:创建地图会话并完成 MapController.onCreate。
     * 会话销毁时会释放地图与已按需创建的业务能力。
     */
    fun openSession(context: Context, config: MapConfig, savedState: Bundle? = null): MapFusionSession {
        val provider = create(context, config)
        val controller = try {
            requireNotNull(provider.createMapController(context)) {
                "${config.provider} 未提供地图控件"
            }
        } catch (error: Throwable) {
            // 控制器尚未创建成功,只需释放 Provider;清理异常不能覆盖原始初始化异常。
            addCleanupFailure(error) { provider.destroy() }
            throw error
        }

        return try {
            controller.onCreate(savedState)
            MapFusionSession(provider, controller)
        } catch (error: Throwable) {
            // onCreate 可能已经部分初始化底层 MapView,必须先销毁控制器再释放 Provider。
            addCleanupFailure(error) { controller.onDestroy() }
            addCleanupFailure(error) { provider.destroy() }
            throw error
        }
    }

    /** 当前可用(已注册适配器)的厂商。 */
    fun availableProviders(): Set<Provider> = ProviderRegistry.availableProviders()

    /**
     * 把隐私同意或撤回状态同步给指定厂商。该调用不创建 Provider 或其他业务能力。
     * 撤回前宿主必须先销毁仍在使用的 [MapFusionSession]。
     */
    fun updatePrivacyConsent(
        context: Context,
        provider: Provider,
        consentGranted: Boolean,
    ): MapResult<Unit> {
        val factory = ProviderRegistry.factoryOf(provider) ?: return MapResult.Failure(
            MapError(ErrorType.UNSUPPORTED, "厂商 $provider 未注册，无法更新隐私状态"),
        )
        return runCatching {
            factory.updatePrivacyConsent(context, consentGranted)
            MapResult.Success(Unit)
        }.getOrElse { error ->
            MapResult.Failure(
                MapError(
                    type = ErrorType.UNKNOWN,
                    message = "厂商 $provider 隐私状态更新失败：${error.message.orEmpty()}",
                    cause = error,
                ),
            )
        }
    }

    /** 更新所有已注册适配器，并分别返回结果；单个厂商失败不会跳过其他厂商。 */
    fun updatePrivacyConsent(
        context: Context,
        consentGranted: Boolean,
    ): Map<Provider, MapResult<Unit>> = availableProviders().associateWith { provider ->
        updatePrivacyConsent(context, provider, consentGranted)
    }

    /**
     * 创建厂商无关的轨迹记录器。返回 null 表示当前 Provider 不支持定位。
     * 记录器拥有内部 LocationClient 的生命周期,使用结束后必须调用 destroy()。
     */
    fun createTrackRecorder(provider: MapProvider, mapController: MapController? = null): TrackRecorder? =
        provider.locationClient()?.let { DefaultTrackRecorder(it, mapController) }

    /**
     * 创建可定制的实时定位展示。组件独占并拥有一个 LocationClient，避免与业务定位、
     * 导航或轨迹请求相互停止；使用结束后必须调用 destroy()。
     */
    fun createLocationDisplay(
        provider: MapProvider,
        mapController: MapController,
    ): LocationDisplay? = provider.locationClient()?.let { DefaultLocationDisplay(it, mapController) }
}

/** 执行初始化失败后的清理,保留原始异常并记录清理异常。 */
private inline fun addCleanupFailure(primary: Throwable, cleanup: () -> Unit) {
    try {
        cleanup()
    } catch (cleanupError: Throwable) {
        if (cleanupError !== primary) primary.addSuppressed(cleanupError)
    }
}

/** 目标厂商未注册时抛出。 */
class ProviderNotRegisteredException(val provider: Provider) : IllegalStateException(
    "厂商 $provider 未注册。请确认已引入对应适配器模块,并在启动时调用 " +
        "ProviderRegistry.register(...)。当前可用:${ProviderRegistry.availableProviders()}"
)
