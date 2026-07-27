package com.mapfusion.amap

import com.mapfusion.api.model.MapType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AmapMapTypeContractTest {

    @Test
    fun supportedMapTypes_excludesNoneThatAmapWouldHaveToFallback() {
        assertEquals(
            setOf(MapType.NORMAL, MapType.SATELLITE, MapType.NIGHT, MapType.NAVIGATION),
            AMAP_SUPPORTED_MAP_TYPES,
        )
        assertFalse(MapType.NONE in AMAP_SUPPORTED_MAP_TYPES)
    }
}
