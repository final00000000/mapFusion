package com.mapfusion.factory

import android.content.Context
import android.app.Application
import com.mapfusion.api.MapConfig
import com.mapfusion.api.MapProvider
import com.mapfusion.api.MapProviderFactory
import com.mapfusion.api.capability.Capability
import com.mapfusion.api.capability.Geocoder
import com.mapfusion.api.capability.LocationClient
import com.mapfusion.api.capability.MapController
import com.mapfusion.api.capability.Navigator
import com.mapfusion.api.capability.PoiSearcher
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.capability.RoutePlanner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import com.mapfusion.api.model.MapResult
import org.junit.Before
import org.junit.Test

/**
 * 验证注册表 + 工厂选择的切换逻辑。全程用假的 MapProvider/Factory,不依赖任何真实厂商 SDK。
 *
 * 注意:本测试聚焦「注册表」这个 Context-free 的可单测核心——注册/注销/查询/覆盖/切换。
 * [MapFusion.create] 因需要 Android Context(用于 SDK 初始化)不在纯 JVM 单测覆盖范围,
 * 由 app 演示模块 / 仪器测试验证。这里改为直接断言 [ProviderRegistry.factoryOf] 的选择结果,
 * 等价地覆盖了「按 provider 切换到正确工厂」这一核心行为。
 */
class ProviderRegistryTest {

    /** 假实现:所有能力返回 null,仅用于占位与断言 provider 标识。 */
    private class FakeProvider(private val p: Provider) : MapProvider {
        override val provider: Provider get() = p
        override fun capabilities(): Set<Capability> = setOf(Capability.MAP_CONTROLLER)
        override fun mapController(mapView: Any): MapController? = null
        override fun locationClient(): LocationClient? = null
        override fun geocoder(): Geocoder? = null
        override fun poiSearcher(): PoiSearcher? = null
        override fun routePlanner(): RoutePlanner? = null
        override fun navigator(): Navigator? = null
        override fun rawProvider(): Any? = null
        override fun destroy() {}
    }

    private class FakeFactory(override val provider: Provider) : MapProviderFactory {
        /** 记录最后一次收到的配置,便于断言 apiKey 透传语义 */
        var lastConfig: MapConfig? = null
        val privacyUpdates = mutableListOf<Boolean>()
        override fun updatePrivacyConsent(context: Context, consentGranted: Boolean) {
            privacyUpdates += consentGranted
        }
        override fun create(context: Context, config: MapConfig): MapProvider {
            lastConfig = config
            return FakeProvider(config.provider)
        }
    }

    @Before
    fun setUp() {
        ProviderRegistry.clear()
    }

    @After
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun register_then_available() {
        assertFalse(ProviderRegistry.isRegistered(Provider.BAIDU))
        ProviderRegistry.register(FakeFactory(Provider.BAIDU))
        assertTrue(ProviderRegistry.isRegistered(Provider.BAIDU))
        assertTrue(ProviderRegistry.availableProviders().contains(Provider.BAIDU))
    }

    @Test
    fun map_fusion_bulk_registers_factories() {
        MapFusion.register(FakeFactory(Provider.BAIDU), FakeFactory(Provider.AMAP))
        assertEquals(setOf(Provider.BAIDU, Provider.AMAP), MapFusion.availableProviders())
    }

    @Test
    fun switch_between_providers_by_config() {
        val baiduFactory = FakeFactory(Provider.BAIDU)
        val amapFactory = FakeFactory(Provider.AMAP)
        ProviderRegistry.register(baiduFactory)
        ProviderRegistry.register(amapFactory)

        // 按 provider 解析到正确的工厂——这是 MapFusion.create 内部依赖的核心选择逻辑
        assertSame(baiduFactory, ProviderRegistry.factoryOf(Provider.BAIDU))
        assertSame(amapFactory, ProviderRegistry.factoryOf(Provider.AMAP))
        assertEquals(
            setOf(Provider.BAIDU, Provider.AMAP),
            ProviderRegistry.availableProviders()
        )
    }

    @Test
    fun unregistered_provider_resolves_to_null() {
        // 未注册的厂商,factoryOf 返回 null;MapFusion.create 据此抛 ProviderNotRegisteredException
        assertNull(ProviderRegistry.factoryOf(Provider.AMAP))
    }

    @Test
    fun last_registration_wins() {
        val first = FakeFactory(Provider.BAIDU)
        val second = FakeFactory(Provider.BAIDU)
        ProviderRegistry.register(first)
        ProviderRegistry.register(second)
        assertSame(second, ProviderRegistry.factoryOf(Provider.BAIDU))
    }

    @Test
    fun unregister_removes_factory() {
        ProviderRegistry.register(FakeFactory(Provider.BAIDU))
        ProviderRegistry.unregister(Provider.BAIDU)
        assertFalse(ProviderRegistry.isRegistered(Provider.BAIDU))
        assertNull(ProviderRegistry.factoryOf(Provider.BAIDU))
    }

    @Test
    fun privacy_updates_are_dispatched_per_registered_provider() {
        val baidu = FakeFactory(Provider.BAIDU)
        val amap = FakeFactory(Provider.AMAP)
        MapFusion.register(baidu, amap)

        val results = MapFusion.updatePrivacyConsent(Application(), consentGranted = false)

        assertEquals(setOf(Provider.BAIDU, Provider.AMAP), results.keys)
        assertTrue(results.values.all { it is MapResult.Success })
        assertEquals(listOf(false), baidu.privacyUpdates)
        assertEquals(listOf(false), amap.privacyUpdates)
    }
}
