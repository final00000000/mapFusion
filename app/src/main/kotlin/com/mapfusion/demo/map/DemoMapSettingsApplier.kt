package com.mapfusion.demo.map

import com.mapfusion.api.capability.MapController
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.MapUiOptions
import com.mapfusion.demo.presentation.main.DemoMapSettings

/** 初始化、重建恢复及用户应用共用的地图设置路径，避免 UI 与原生地图各用默认值。 */
internal object DemoMapSettingsApplier {
    /** 按顺序应用设置并返回 SDK 接受的快照；原生操作非事务，失败不宣称已全部生效。 */
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
