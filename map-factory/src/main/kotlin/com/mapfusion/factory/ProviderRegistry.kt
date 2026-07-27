package com.mapfusion.factory

import com.mapfusion.api.MapProviderFactory
import com.mapfusion.api.capability.Provider

/**
 * 厂商工厂注册表。可插拔:各适配器模块在启动时把自己的 [MapProviderFactory]
 * 注册进来;未注册的厂商视为不可用。
 *
 * 线程安全:注册通常在 App 启动早期完成,这里用同步保证可见性。
 */
object ProviderRegistry {

    private val factories = LinkedHashMap<Provider, MapProviderFactory>()

    /** 注册一个厂商工厂。重复注册同一厂商会覆盖旧的。 */
    @Synchronized
    fun register(factory: MapProviderFactory) {
        factories[factory.provider] = factory
    }

    /** 注销某厂商工厂。 */
    @Synchronized
    fun unregister(provider: Provider) {
        factories.remove(provider)
    }

    /** 取某厂商工厂,未注册返回 null。 */
    @Synchronized
    fun factoryOf(provider: Provider): MapProviderFactory? = factories[provider]

    /** 当前已注册(可用)的厂商集合。 */
    @Synchronized
    fun availableProviders(): Set<Provider> = factories.keys.toSet()

    /** 是否已注册某厂商。 */
    @Synchronized
    fun isRegistered(provider: Provider): Boolean = factories.containsKey(provider)

    /** 清空(主要供测试用)。 */
    @Synchronized
    fun clear() {
        factories.clear()
    }
}
