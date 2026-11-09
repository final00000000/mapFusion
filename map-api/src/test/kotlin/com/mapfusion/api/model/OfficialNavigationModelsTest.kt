package com.mapfusion.api.model

import android.view.View
import com.mapfusion.api.capability.OfficialNavigator
import com.mapfusion.api.capability.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialNavigationModelsTest {
    @Test
    fun capabilitiesCanonicalizeLegacyRidingMode() {
        val capabilities = OfficialNavigationCapabilities(
            provider = Provider.AMAP,
            features = setOf(OfficialNavigationFeature.IN_APP_UI),
            modes = setOf(OfficialNavigationMode.REAL),
            travelModes = setOf(TravelMode.BICYCLE),
            maxWaypoints = 3,
        )

        @Suppress("DEPRECATION")
        assertTrue(capabilities.supports(TravelMode.RIDING))
        assertTrue(capabilities.supports(OfficialNavigationFeature.IN_APP_UI))
        assertFalse(capabilities.supports(OfficialNavigationMode.SIMULATED))
    }

    @Test
    fun detailedNavigationEventsKeepVendorValuesWithoutNativeTypes() {
        val traffic = OfficialNavigationEvent.TrafficStatus(
            distanceMeters = 1_200,
            segments = listOf(OfficialTrafficSegment(lengthMeters = 320, vendorStatus = 3)),
        )
        val serviceAreas = OfficialNavigationEvent.ServiceAreas(
            listOf(OfficialServiceArea(name = "测试服务区", remainingDistanceMeters = 8_000, vendorType = 2)),
        )
        val progress = OfficialNavigationEvent.Progress(
            OfficialNavigationProgress(
                remainingDistanceMeters = 9_800,
                remainingDurationSeconds = 720,
                travelledDistanceMeters = 1_600,
                remainingTrafficLightCount = 5,
            ),
        )
        val waypoint = OfficialNavigationEvent.WaypointProgress(
            listOf(OfficialWaypointProgress(0, 3_200, 240, remainingTrafficLightCount = 2)),
        )
        val highway = OfficialNavigationEvent.HighwayGuidance(
            OfficialHighwayGuidance(
                visible = true,
                direction = "机场方向",
                exitName = "机场出口",
                exitRemainingDistanceMeters = 1_500,
            ),
        )
        val notice = OfficialNavigationEvent.RouteNotice(
            OfficialRouteNotice(message = "前方道路施工", vendorType = 7),
        )
        val reroute = OfficialNavigationEvent.RerouteCompleted(success = true)

        assertEquals(3, traffic.segments.single().vendorStatus)
        assertEquals(8_000, serviceAreas.areas.single().remainingDistanceMeters)
        assertEquals(1_600, progress.value.travelledDistanceMeters)
        assertEquals(2, waypoint.waypoints.single().remainingTrafficLightCount)
        assertEquals("机场出口", highway.value.exitName)
        assertEquals(7, notice.value.vendorType)
        assertTrue(reroute.success)
    }

    @Test
    fun dayNightModeIsAnExplicitStableContract() {
        assertEquals(
            listOf(
                OfficialNavigationDayNightMode.AUTO,
                OfficialNavigationDayNightMode.DAY,
                OfficialNavigationDayNightMode.NIGHT,
            ),
            OfficialNavigationDayNightMode.entries,
        )
    }

    @Test
    fun newRuntimeControlsDefaultToUnsupportedForOlderImplementations() {
        val navigator = object : OfficialNavigator {
            override val capabilities = OfficialNavigationCapabilities(
                provider = Provider.AMAP,
                features = emptySet(),
                modes = emptySet(),
                travelModes = emptySet(),
                maxWaypoints = 0,
            )
            override val state = OfficialNavigationState.READY
            override val view: View get() = error("not used by this contract test")
            override fun start(
                request: OfficialNavigationRequest,
                listener: OfficialNavigationListener,
            ) = MapResult.Success(Unit)
            override fun selectRoute(index: Int) = MapResult.Success(Unit)
            override fun pause() = MapResult.Success(Unit)
            override fun resume() = MapResult.Success(Unit)
            override fun stop() = MapResult.Success(Unit)
            override fun onResume() = Unit
            override fun onPause() = Unit
            override fun destroy() = Unit
        }

        val results = listOf(
            navigator.recalculateRoute(),
            navigator.showRouteOverview(),
            navigator.resumeFollowing(),
            navigator.setSimulationSpeed(60),
            navigator.setVoiceGuidanceEnabled(false),
            navigator.setDayNightMode(OfficialNavigationDayNightMode.AUTO),
        )
        assertEquals(null, navigator.lastError)
        assertTrue(results.all { it is MapResult.Failure && it.error.type == ErrorType.UNSUPPORTED })
    }
}
