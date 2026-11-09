package com.mapfusion.baidu.navi

import com.mapfusion.api.model.OfficialNavigationFeature
import com.mapfusion.api.model.OfficialNavigationMode
import com.mapfusion.api.model.TravelMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaiduOfficialNavigatorCapabilitiesTest {
    private val capabilities = BaiduOfficialNavigatorFactory().capabilities

    @Test
    fun declaresOnlyImplementedModesAndFeatures() {
        assertTrue(capabilities.supports(OfficialNavigationMode.REAL))
        assertTrue(capabilities.supports(OfficialNavigationMode.SIMULATED))
        assertTrue(capabilities.supports(TravelMode.DRIVING))
        assertFalse(capabilities.supports(TravelMode.WALKING))
        assertFalse(capabilities.supports(OfficialNavigationFeature.SIMULATION_SPEED))
        assertTrue(capabilities.supports(OfficialNavigationFeature.WAYPOINT_ARRIVAL))
        assertTrue(capabilities.supports(OfficialNavigationFeature.SERVICE_AREAS))
        assertTrue(capabilities.supports(OfficialNavigationFeature.WAYPOINT_PROGRESS))
        assertTrue(capabilities.supports(OfficialNavigationFeature.HIGHWAY_GUIDANCE))
        assertTrue(capabilities.supports(OfficialNavigationFeature.ROUTE_NOTICES))
        assertTrue(capabilities.supports(OfficialNavigationFeature.MANUAL_REROUTE))
        assertTrue(capabilities.supports(OfficialNavigationFeature.ROUTE_OVERVIEW))
        assertTrue(capabilities.supports(OfficialNavigationFeature.RESTORE_FOLLOWING))
        assertTrue(capabilities.supports(OfficialNavigationFeature.VOICE_CONTROL))
        assertTrue(capabilities.supports(OfficialNavigationFeature.DAY_NIGHT_MODE))
        assertFalse(capabilities.supports(OfficialNavigationFeature.GPS_STATUS))
        assertFalse(capabilities.supports(OfficialNavigationFeature.INTERVAL_CAMERA_ALERTS))
        assertFalse(capabilities.supports(OfficialNavigationFeature.BACKGROUND_GUIDANCE))
    }
}
