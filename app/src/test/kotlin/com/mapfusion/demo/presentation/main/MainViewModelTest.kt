package com.mapfusion.demo.presentation.main

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.EmbeddedNavigationMode
import com.mapfusion.api.model.MapLanguage
import com.mapfusion.api.model.MapType
import com.mapfusion.api.model.TravelMode
import com.mapfusion.demo.data.PrivacyDecision
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MainViewModelTest {
    @get:Rule val mainThread = InstantTaskExecutorRule()
    private val settings = DemoMapSettings(MapType.SATELLITE, MapLanguage.ENGLISH,
        trafficEnabled = true, buildingsEnabled = false, indoorEnabled = true,
        mapPoiEnabled = false, compassEnabled = false, zoomControlsEnabled = false)

    private fun snapshot(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })

    @Test fun settings_codec_preserves_every_control() {
        assertEquals(settings, DemoMapSettings.decode(settings.encode()))
    }

    @Test fun malformed_settings_restore_safe_defaults() {
        listOf(null, "bad", "UNKNOWN|ENGLISH|true|false|true|false|false|false",
            "SATELLITE|ENGLISH|invalid|false|true|false|false|false").forEach {
            assertEquals(DemoMapSettings(), DemoMapSettings.decode(it))
        }
    }

    @Test fun selections_and_settings_survive_new_viewmodel() {
        val saved = SavedStateHandle()
        val vm = MainViewModel(saved)
        vm.update { it.copy(preferredProvider = Provider.AMAP, routeMode = TravelMode.WALKING,
            navigationMode = EmbeddedNavigationMode.SIMULATED, toolCategory = DemoToolCategory.OVERLAY,
            mapSettings = settings) }
        val restored = MainViewModel(snapshot(saved))
        restored.configureProviders(Provider.BAIDU, Provider.entries.toSet())
        assertEquals(Provider.AMAP, restored.state.value.preferredProvider)
        assertEquals(TravelMode.WALKING, restored.state.value.routeMode)
        assertEquals(EmbeddedNavigationMode.SIMULATED, restored.state.value.navigationMode)
        assertEquals(DemoToolCategory.OVERLAY, restored.state.value.toolCategory)
        assertEquals(settings, restored.state.value.mapSettings)
    }

    @Test fun same_provider_recreation_does_not_reset_settings() {
        val vm = MainViewModel(SavedStateHandle())
        vm.update { it.copy(mapSettings = settings) }
        vm.selectProvider(Provider.BAIDU)
        vm.resetSessionState()
        assertEquals(settings, vm.state.value.mapSettings)
    }

    @Test fun changing_provider_resets_vendor_dependent_settings() {
        val vm = MainViewModel(SavedStateHandle())
        vm.update { it.copy(mapSettings = settings, embeddedNavigationActive = true,
            pendingLocationAction = LocationPermissionAction.START_NAVIGATION) }
        vm.selectProvider(Provider.AMAP)
        assertEquals(DemoMapSettings(), vm.state.value.mapSettings)
        assertFalse(vm.state.value.embeddedNavigationActive)
        assertNull(vm.state.value.pendingLocationAction)
    }

    @Test fun single_vendor_flavor_uses_its_default_on_cold_start() {
        val vm = MainViewModel(SavedStateHandle())
        vm.configureProviders(Provider.AMAP, setOf(Provider.AMAP))
        assertEquals(Provider.AMAP, vm.state.value.preferredProvider)
    }

    @Test fun unsupported_restored_provider_is_normalized() {
        val vm = MainViewModel(SavedStateHandle(mapOf(MainViewModel.PROVIDER to "BAIDU")))
        vm.configureProviders(Provider.AMAP, setOf(Provider.AMAP))
        assertEquals(Provider.AMAP, vm.state.value.preferredProvider)
    }

    @Test fun permission_selection_restores_without_repeating_active_navigation() {
        val saved = SavedStateHandle()
        MainViewModel(saved).update { it.copy(pendingLocationAction = LocationPermissionAction.START_TRACK,
            permissionRequestInFlight = true, permissionDenied = true, permissionPermanentlyDenied = true,
            embeddedNavigationActive = true, privacyDecision = PrivacyDecision.ACCEPTED) }
        val restored = MainViewModel(snapshot(saved)).state.value
        assertEquals(LocationPermissionAction.START_TRACK, restored.pendingLocationAction)
        assertTrue(restored.permissionRequestInFlight)
        assertTrue(restored.permissionDenied)
        assertTrue(restored.permissionPermanentlyDenied)
        assertFalse(restored.embeddedNavigationActive)
        assertEquals(PrivacyDecision.UNDECIDED, restored.privacyDecision)
    }

    @Test fun invalid_enum_does_not_crash_restoration() {
        val vm = MainViewModel(SavedStateHandle(mapOf(MainViewModel.ROUTE_MODE to "future-mode",
            MainViewModel.NAVIGATION_MODE to "future-mode", MainViewModel.TOOL_CATEGORY to "future-category")))
        assertEquals(TravelMode.DRIVING, vm.state.value.routeMode)
        assertEquals(EmbeddedNavigationMode.REAL, vm.state.value.navigationMode)
        assertEquals(DemoToolCategory.COMMON, vm.state.value.toolCategory)
    }

    @Test fun runtime_result_is_not_persisted_as_current_engine_state() {
        val saved = SavedStateHandle()
        val vm = MainViewModel(saved)
        vm.update { it.copy(status = DemoStatus("old result", "old session", DemoStatusKind.SUCCESS)) }
        assertEquals(DemoStatus(), MainViewModel(snapshot(saved)).state.value.status)
    }
}
