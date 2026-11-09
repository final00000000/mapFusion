package com.mapfusion.demo.presentation.main

import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.EmbeddedNavigationMode
import com.mapfusion.api.model.MapLanguage
import com.mapfusion.api.model.MapLocation
import com.mapfusion.api.model.MapType
import com.mapfusion.api.model.TravelMode
import com.mapfusion.demo.data.PrivacyDecision

internal enum class LocationPermissionAction { LOCATE, START_TRACK, START_NAVIGATION }
internal enum class DemoToolCategory(val title: String) {
    COMMON("常用"), NAVIGATION("导航"), SEARCH("查询服务"), OVERLAY("覆盖物"), TRACK("轨迹")
}
internal enum class DemoStatusKind { NEUTRAL, INFO, SUCCESS, ERROR }
internal data class DemoStatus(
    val title: String = "等待操作",
    val detail: String = "点击下方功能卡片体验统一地图 API",
    val kind: DemoStatusKind = DemoStatusKind.NEUTRAL,
)

/** Immutable settings. Dialog edits are copies, never mutations of the live UI state. */
internal data class DemoMapSettings(
    val mapType: MapType = MapType.NORMAL,
    val mapLanguage: MapLanguage = MapLanguage.CHINESE,
    val trafficEnabled: Boolean = false,
    val buildingsEnabled: Boolean = true,
    val indoorEnabled: Boolean = false,
    val mapPoiEnabled: Boolean = true,
    val compassEnabled: Boolean = true,
    val zoomControlsEnabled: Boolean = true,
) {
    fun encode(): String = listOf(mapType.name, mapLanguage.name, trafficEnabled, buildingsEnabled,
        indoorEnabled, mapPoiEnabled, compassEnabled, zoomControlsEnabled).joinToString("|")

    companion object {
        fun decode(value: String?): DemoMapSettings {
            val parts = value?.split('|') ?: return DemoMapSettings()
            if (parts.size != 8) return DemoMapSettings()
            return runCatching {
                DemoMapSettings(MapType.valueOf(parts[0]), MapLanguage.valueOf(parts[1]),
                    parts[2].toBooleanStrict(), parts[3].toBooleanStrict(), parts[4].toBooleanStrict(),
                    parts[5].toBooleanStrict(), parts[6].toBooleanStrict(), parts[7].toBooleanStrict())
            }.getOrDefault(DemoMapSettings())
        }
    }
}

internal data class MainUiState(
    val preferredProvider: Provider = Provider.BAIDU,
    val routeMode: TravelMode = TravelMode.DRIVING,
    val navigationMode: EmbeddedNavigationMode = EmbeddedNavigationMode.REAL,
    val toolCategory: DemoToolCategory = DemoToolCategory.COMMON,
    val mapSettings: DemoMapSettings = DemoMapSettings(),
    val pendingLocationAction: LocationPermissionAction? = null,
    val permissionRequestInFlight: Boolean = false,
    val permissionDenied: Boolean = false,
    val permissionPermanentlyDenied: Boolean = false,
    val privacyDecision: PrivacyDecision = PrivacyDecision.UNDECIDED,
    val embeddedNavigationActive: Boolean = false,
    val lastLocation: MapLocation? = null,
    val status: DemoStatus = DemoStatus(),
)
