package com.mapfusion.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MapAppearanceTest {

    @Test
    fun customStyle_requiresSourceOnlyWhenEnabled() {
        assertEquals(false, MapCustomStyleOptions(enabled = false).enabled)

        assertThrows(IllegalArgumentException::class.java) {
            MapCustomStyleOptions(enabled = true)
        }
        assertEquals(
            "style-id",
            MapCustomStyleOptions(enabled = true, styleId = "style-id").styleId,
        )
    }

    @Test
    fun customStyle_rejectsBlankConfiguredValues() {
        assertThrows(IllegalArgumentException::class.java) {
            MapCustomStyleOptions(enabled = false, localStylePath = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MapCustomStyleOptions(enabled = false, styleId = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MapCustomStyleOptions(enabled = false, localTexturePath = "\t")
        }
    }
}
