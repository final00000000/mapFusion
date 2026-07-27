package com.mapfusion.api.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TravelModeTest {

    @Suppress("DEPRECATION")
    @Test
    fun legacyRidingCanonicalizesToBicycle() {
        assertEquals(TravelMode.BICYCLE, TravelMode.RIDING.canonical())
        assertEquals(TravelMode.ELECTRIC_BICYCLE, TravelMode.ELECTRIC_BICYCLE.canonical())
    }
}
