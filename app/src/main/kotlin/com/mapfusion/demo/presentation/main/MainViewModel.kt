package com.mapfusion.demo.presentation.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.EmbeddedNavigationMode
import com.mapfusion.api.model.TravelMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns page data only: no Activity, View, Context, provider or map session references. */
internal class MainViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState(
        preferredProvider = enumOrDefault(savedState[PROVIDER], Provider.BAIDU),
        routeMode = enumOrDefault(savedState[ROUTE_MODE], TravelMode.DRIVING),
        navigationMode = enumOrDefault(savedState[NAVIGATION_MODE], EmbeddedNavigationMode.REAL),
        toolCategory = enumOrDefault(savedState[TOOL_CATEGORY], DemoToolCategory.COMMON),
        mapSettings = DemoMapSettings.decode(savedState[MAP_SETTINGS]),
        pendingLocationAction = savedState.get<String>(PENDING_ACTION)?.let {
            runCatching { LocationPermissionAction.valueOf(it) }.getOrNull()
        },
        permissionRequestInFlight = savedState[PERMISSION_IN_FLIGHT] ?: false,
        permissionDenied = savedState[PERMISSION_DENIED] ?: false,
        permissionPermanentlyDenied = savedState[PERMISSION_PERMANENT] ?: false,
    ))
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    fun update(transform: (MainUiState) -> MainUiState) {
        val next = transform(mutableState.value)
        mutableState.value = next
        savedState[PROVIDER] = next.preferredProvider.name
        savedState[ROUTE_MODE] = next.routeMode.name
        savedState[NAVIGATION_MODE] = next.navigationMode.name
        savedState[TOOL_CATEGORY] = next.toolCategory.name
        savedState[MAP_SETTINGS] = next.mapSettings.encode()
        savedState[PENDING_ACTION] = next.pendingLocationAction?.name
        savedState[PERMISSION_IN_FLIGHT] = next.permissionRequestInFlight
        savedState[PERMISSION_DENIED] = next.permissionDenied
        savedState[PERMISSION_PERMANENT] = next.permissionPermanentlyDenied
    }

    fun configureProviders(default: Provider, supported: Set<Provider>) {
        require(default in supported)
        val candidate = if (savedState.contains(PROVIDER)) state.value.preferredProvider else default
        selectProvider(candidate.takeIf { it in supported } ?: default)
    }

    fun selectProvider(provider: Provider) = update {
        if (provider == it.preferredProvider) it
        else it.copy(preferredProvider = provider, mapSettings = DemoMapSettings(), lastLocation = null,
            embeddedNavigationActive = false, pendingLocationAction = null)
    }

    /** A recreated Activity owns a new SDK session: never resume phantom runtime state. */
    fun resetSessionState() = update { it.copy(lastLocation = null, embeddedNavigationActive = false) }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    internal companion object {
        const val PROVIDER = "main.provider"
        const val ROUTE_MODE = "main.routeMode"
        const val NAVIGATION_MODE = "main.navigationMode"
        const val TOOL_CATEGORY = "main.toolCategory"
        const val MAP_SETTINGS = "main.mapSettings"
        const val PENDING_ACTION = "main.pendingAction"
        const val PERMISSION_IN_FLIGHT = "main.permissionInFlight"
        const val PERMISSION_DENIED = "main.permissionDenied"
        const val PERMISSION_PERMANENT = "main.permissionPermanent"
    }
}
