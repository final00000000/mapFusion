package com.mapfusion.baidu

import com.mapfusion.api.model.MapLanguage
import com.mapfusion.api.model.MapCustomStyleFeature
import com.mapfusion.api.model.MapType
import com.mapfusion.api.model.MapUiFeature
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

    @Test
    fun supportedMapLanguages_onlyAdvertisesNativeUnifiedLanguages() {
        assertEquals(
            setOf(MapLanguage.CHINESE, MapLanguage.ENGLISH),
            BAIDU_SUPPORTED_MAP_LANGUAGES,
        )
        assertFalse(MapLanguage.LOCAL in BAIDU_SUPPORTED_MAP_LANGUAGES)
    }

    @Test
    fun supportedUiFeatures_includeBaiduSpecificGestures() {
        assertEquals(
            setOf(
                MapUiFeature.ZOOM_CONTROLS,
                MapUiFeature.SCALE_CONTROLS,
                MapUiFeature.COMPASS,
                MapUiFeature.SCROLL_GESTURES,
                MapUiFeature.ZOOM_GESTURES,
                MapUiFeature.ROTATE_GESTURES,
                MapUiFeature.TILT_GESTURES,
                MapUiFeature.DOUBLE_CLICK_ZOOM,
                MapUiFeature.TWO_TOUCH_CLICK_ZOOM,
                MapUiFeature.DOUBLE_CLICK_MOVE_ZOOM,
                MapUiFeature.FLING,
                MapUiFeature.INERTIAL_ANIMATION,
            ),
            BAIDU_SUPPORTED_UI_FEATURES,
        )
        assertFalse(MapUiFeature.MY_LOCATION_BUTTON in BAIDU_SUPPORTED_UI_FEATURES)
    }

    @Test
    fun supportedCustomStyleFeatures_excludeUnsupportedTextureFiles() {
        assertEquals(
            setOf(MapCustomStyleFeature.LOCAL_STYLE_FILE, MapCustomStyleFeature.STYLE_ID),
            BAIDU_SUPPORTED_CUSTOM_STYLE_FEATURES,
        )
        assertFalse(MapCustomStyleFeature.LOCAL_TEXTURE_FILE in BAIDU_SUPPORTED_CUSTOM_STYLE_FEATURES)
    }
}
