package com.mapfusion.baidu

import com.mapfusion.api.model.MapType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BaiduMapTypeContractTest {

    @Test
    fun supportedMapTypes_excludesTypesThatBaiduWouldHaveToFallback() {
        assertEquals(
            setOf(MapType.NORMAL, MapType.SATELLITE, MapType.NONE),
            BAIDU_SUPPORTED_MAP_TYPES,
        )
        assertFalse(MapType.NIGHT in BAIDU_SUPPORTED_MAP_TYPES)
        assertFalse(MapType.NAVIGATION in BAIDU_SUPPORTED_MAP_TYPES)
    }
}
