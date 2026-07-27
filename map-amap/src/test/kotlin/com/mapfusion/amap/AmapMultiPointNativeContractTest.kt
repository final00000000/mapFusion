package com.mapfusion.amap

import com.amap.api.maps.AMap
import com.amap.api.maps.model.MultiPointItem
import com.amap.api.maps.model.MultiPointOverlay
import com.amap.api.maps.model.MultiPointOverlayOptions
import org.junit.Assert.assertEquals
import org.junit.Test

/** 锁定当前高德组合包真实提供的海量点 API，避免适配器依赖猜测式签名。 */
class AmapMultiPointNativeContractTest {

    @Test
    fun pinnedSdkProvidesNativeBatchUpdateStyleAndClickApis() {
        assertEquals(
            Void.TYPE,
            MultiPointOverlay::class.java.getMethod("setItems", List::class.java).returnType,
        )
        MultiPointOverlay::class.java.getMethod(
            "setAnchor",
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
        )
        MultiPointOverlayOptions::class.java.getMethod("setMultiPointItems", List::class.java)
        MultiPointItem::class.java.getMethod("getCustomerId")
        AMap::class.java.getMethod(
            "setOnMultiPointClickListener",
            AMap.OnMultiPointClickListener::class.java,
        )
    }
}
