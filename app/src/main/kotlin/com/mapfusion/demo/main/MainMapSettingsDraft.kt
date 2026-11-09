package com.mapfusion.demo.main

import com.mapfusion.api.model.MapLanguage
import com.mapfusion.api.model.MapType
import com.mapfusion.demo.presentation.main.DemoMapSettings

/** 地图设置弹窗的纯草稿；不持有 ViewModel，取消或销毁时无需回滚已应用状态。 */
internal class MainMapSettingsDraft(initial: DemoMapSettings, supportedTypes: Set<MapType>, supportedLanguages: Set<MapLanguage>) {
    val mapTypes = MapType.entries.filter(supportedTypes::contains).ifEmpty { listOf(MapType.NORMAL) }
    val mapLanguages = MapLanguage.entries.filter(supportedLanguages::contains)
    private var value = initial.copy(mapType = initial.mapType.takeIf(mapTypes::contains) ?: mapTypes.first(),
        mapLanguage = initial.mapLanguage.takeIf(mapLanguages::contains) ?: mapLanguages.firstOrNull() ?: initial.mapLanguage)

    /** 返回不可变草稿快照，调用者取得它不会获得可修改已应用状态的引用。 */
    fun snapshot(): DemoMapSettings = value

    /** 开关只改变草稿；能力受限的地图类型和语言仍保持在支持集合内。 */
    fun update(transform: (DemoMapSettings) -> DemoMapSettings) {
        val next = transform(value)
        value = next.copy(mapType = next.mapType.takeIf(mapTypes::contains) ?: value.mapType,
            mapLanguage = next.mapLanguage.takeIf(mapLanguages::contains) ?: value.mapLanguage)
    }

    /** 应用按钮读取选择器值时再次校验，非法选项不越过当前厂商能力边界。 */
    fun select(type: MapType, language: MapLanguage?) = update {
        it.copy(mapType = type, mapLanguage = language ?: it.mapLanguage)
    }
}
