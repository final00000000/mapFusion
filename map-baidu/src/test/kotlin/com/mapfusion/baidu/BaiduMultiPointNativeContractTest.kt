package com.mapfusion.baidu

import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.MultiPoint
import com.baidu.mapapi.map.MultiPointItem
import com.baidu.mapapi.map.MultiPointOption
import org.junit.Assert.assertEquals
import org.junit.Test

/** 锁定当前百度依赖真实提供的海量点 API，升级厂商版本时可立即发现签名变化。 */
class BaiduMultiPointNativeContractTest {

    @Test
    fun pinnedSdkProvidesNativeBatchUpdateStyleAndClickApis() {
        assertEquals(
            Void.TYPE,
            MultiPoint::class.java.getMethod("setMultiPointItems", List::class.java).returnType,
        )
        MultiPoint::class.java.getMethod("setIcon", com.baidu.mapapi.map.BitmapDescriptor::class.java)
        MultiPoint::class.java.getMethod("anchor", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        MultiPointOption::class.java.getMethod("setMultiPointItems", List::class.java)
        MultiPointItem::class.java.getMethod("getPoint")
        BaiduMap::class.java.getMethod(
            "setOnMultiPointClickListener",
            BaiduMap.OnMultiPointClickListener::class.java,
        )
    }
}
