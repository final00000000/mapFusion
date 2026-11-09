package com.mapfusion.demo

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.EmbeddedNavigationMode
import com.mapfusion.api.model.MapLanguage
import com.mapfusion.api.model.MapType
import com.mapfusion.api.model.TravelMode
import com.mapfusion.demo.data.DemoConsentStore
import com.mapfusion.demo.data.PrivacyDecision
import com.mapfusion.demo.presentation.main.DemoMapSettings
import com.mapfusion.demo.presentation.main.DemoToolCategory
import com.mapfusion.demo.presentation.main.MainViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Runs without vendor initialization; restores the existing host consent after each test. */
class DemoStateRestorationTest {
    private lateinit var consent: DemoConsentStore
    private lateinit var previous: PrivacyDecision

    @Before fun disableVendorInitialization() {
        consent = DemoConsentStore(InstrumentationRegistry.getInstrumentation().targetContext)
        previous = consent.read()
        assertTrue(consent.save(PrivacyDecision.DECLINED))
    }
    @After fun restoreHostConsent() { assertTrue(consent.save(previous)) }

    @Test fun recreated_activity_keeps_options_but_drops_old_navigation_runtime() {
        val settings = DemoMapSettings(mapType = MapType.SATELLITE, mapLanguage = MapLanguage.ENGLISH,
            trafficEnabled = true, buildingsEnabled = false, indoorEnabled = true,
            mapPoiEnabled = false, compassEnabled = false, zoomControlsEnabled = false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                vm.update { it.copy(preferredProvider = Provider.AMAP, routeMode = TravelMode.WALKING,
                    navigationMode = EmbeddedNavigationMode.SIMULATED, toolCategory = DemoToolCategory.OVERLAY,
                    mapSettings = settings, embeddedNavigationActive = true) }
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[MainViewModel::class.java].state.value
                assertEquals(Provider.AMAP, state.preferredProvider)
                assertEquals(TravelMode.WALKING, state.routeMode)
                assertEquals(EmbeddedNavigationMode.SIMULATED, state.navigationMode)
                assertEquals(DemoToolCategory.OVERLAY, state.toolCategory)
                assertEquals(settings, state.mapSettings)
                assertFalse(state.embeddedNavigationActive)
            }
        }
    }

    @Test fun declined_consent_stays_declined_after_recreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[MainViewModel::class.java].state.value
                assertEquals(PrivacyDecision.DECLINED, state.privacyDecision)
                assertFalse(state.embeddedNavigationActive)
                assertNull(state.lastLocation)
                assertFalse(consent.hasConsent())
            }
        }
    }
}
