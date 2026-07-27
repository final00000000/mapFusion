package com.mapfusion.full

import com.mapfusion.api.capability.Provider
import com.mapfusion.factory.MapFusion
import com.mapfusion.factory.ProviderRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class MapFusionFullTest {

    @After
    fun tearDown() = ProviderRegistry.clear()

    @Test
    fun installRestoresBothFactoriesAndCanBeRepeated() {
        MapFusionFull.install()
        assertEquals(setOf(Provider.BAIDU, Provider.AMAP), MapFusion.availableProviders())

        ProviderRegistry.clear()
        MapFusionFull.install()

        assertEquals(setOf(Provider.BAIDU, Provider.AMAP), MapFusion.availableProviders())
    }
}
