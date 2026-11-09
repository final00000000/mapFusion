package com.mapfusion.amap

import com.mapfusion.api.model.MapLanguage
import com.mapfusion.api.model.MapCustomStyleFeature
import com.mapfusion.api.model.MapType
import com.mapfusion.api.model.MapUiFeature
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

    @Test
    fun supportedMapLanguages_includesOfficialLocalMode() {
        assertEquals(MapLanguage.entries.toSet(), AMAP_SUPPORTED_MAP_LANGUAGES)
    }

    @Test
    fun supportedUiFeatures_includeAmapSpecificControls() {
        assertEquals(
            setOf(
                MapUiFeature.ZOOM_CONTROLS,
                MapUiFeature.SCALE_CONTROLS,
                MapUiFeature.COMPASS,
                MapUiFeature.MY_LOCATION_BUTTON,
                MapUiFeature.SCROLL_GESTURES,
                MapUiFeature.ZOOM_GESTURES,
                MapUiFeature.ROTATE_GESTURES,
                MapUiFeature.TILT_GESTURES,
                MapUiFeature.INDOOR_SWITCH,
                MapUiFeature.ZOOM_IN_BY_SCREEN_CENTER,
                MapUiFeature.GESTURE_SCALE_BY_MAP_CENTER,
            ),
            AMAP_SUPPORTED_UI_FEATURES,
        )
    }

    @Test
    fun supportedCustomStyleFeatures_includeAmapTextureFiles() {
        assertEquals(MapCustomStyleFeature.entries.toSet(), AMAP_SUPPORTED_CUSTOM_STYLE_FEATURES)
    }
}
