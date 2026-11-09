package com.mapfusion.demo.map

import com.mapfusion.api.capability.MapController
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.MapUiOptions
import com.mapfusion.demo.presentation.main.DemoMapSettings

/** Same settings path for initial creation, configuration restore and explicit user edits. */
internal object DemoMapSettingsApplier {
    fun apply(controller: MapController, settings: DemoMapSettings): MapResult<DemoMapSettings> {
        val type = when (val result = controller.applyMapType(settings.mapType)) {
            is MapResult.Success -> result.data
            is MapResult.Failure -> return result
        }
        val languages = controller.supportedMapLanguages()
        val language = if (languages.isEmpty()) settings.mapLanguage else {
            val requested = settings.mapLanguage.takeIf(languages::contains) ?: languages.first()
            when (val result = controller.applyMapLanguage(requested)) {
                is MapResult.Success -> result.data
                is MapResult.Failure -> return result
            }
        }
        controller.setTrafficEnabled(settings.trafficEnabled)
        controller.setBuildingsEnabled(settings.buildingsEnabled)
        controller.setIndoorEnabled(settings.indoorEnabled)
        controller.setMapPoiEnabled(settings.mapPoiEnabled)
        when (val result = controller.applyUiOptions(MapUiOptions(
            zoomControlsEnabled = settings.zoomControlsEnabled,
            scaleControlsEnabled = true,
            compassEnabled = settings.compassEnabled,
        ))) {
            is MapResult.Failure -> return result
            is MapResult.Success -> return MapResult.Success(settings.copy(mapType = type, mapLanguage = language,
                zoomControlsEnabled = result.data.zoomControlsEnabled, compassEnabled = result.data.compassEnabled))
        }
    }
}
