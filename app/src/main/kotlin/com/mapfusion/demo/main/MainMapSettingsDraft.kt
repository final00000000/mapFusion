package com.mapfusion.demo.main

import com.mapfusion.api.model.MapLanguage
import com.mapfusion.api.model.MapType
import com.mapfusion.demo.presentation.main.DemoMapSettings

/** 地图设置弹窗的纯草稿；不持有 ViewModel，取消或销毁时无需回滚已应用状态。
 *
 * @param initial 弹窗打开时的完整设置快照，调用方可合并控制器当前类型；不保证已全部应用到 SDK。
 * @param supportedTypes 当前厂商声明支持的地图类型集合，用于裁剪候选项。
 * @param supportedLanguages 当前厂商支持的底图语言集合；为空时不显示语言选择器。
 */
internal class MainMapSettingsDraft(
    initial: DemoMapSettings, supportedTypes: Set<MapType>, supportedLanguages: Set<MapLanguage>
) {
    /** 可选地图类型；按枚举顺序裁剪厂商支持集，空集时回退为普通底图。 */
    val mapTypes = MapType.entries.filter(supportedTypes::contains).ifEmpty { listOf(MapType.NORMAL) }
    /** 可选底图语言；按枚举顺序裁剪支持集，空列表表示当前厂商不提供语言切换。 */
    val mapLanguages = MapLanguage.entries.filter(supportedLanguages::contains)
    /** 当前草稿值；构造时校正非法类型与语言，之后只由开关和选择器更新。 */
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
