package com.mapfusion.demo.main

import com.mapfusion.api.model.MapLanguage
import com.mapfusion.api.model.MapType
import com.mapfusion.demo.presentation.main.DemoMapSettings
import org.junit.Assert.*
import org.junit.Test

/** 草稿的独立性和能力约束无需原生地图即可验证。 */
class MainMapSettingsDraftTest {
    /** 未提交的开关和选择不应修改原始已应用快照。 */
    @Test fun canceling_draft_does_not_mutate_applied_settings() {
        val initial = DemoMapSettings()
        val draft = MainMapSettingsDraft(initial, MapType.entries.toSet(), MapLanguage.entries.toSet())
        val previousSnapshot = draft.snapshot()
        draft.update { it.copy(trafficEnabled = true, indoorEnabled = true, compassEnabled = false) }
        draft.select(MapType.SATELLITE, MapLanguage.ENGLISH)
        assertEquals(DemoMapSettings(), initial)
        assertEquals(initial, previousSnapshot)
        assertTrue(draft.snapshot().trafficEnabled)
        assertEquals(MapType.SATELLITE, draft.snapshot().mapType)
    }

    /** 不支持的恢复值按厂商支持集合归一化。 */
    @Test fun unsupported_initial_selections_are_normalized() {
        val draft = MainMapSettingsDraft(DemoMapSettings(MapType.SATELLITE, MapLanguage.ENGLISH),
            setOf(MapType.NORMAL), setOf(MapLanguage.CHINESE))
        assertEquals(MapType.NORMAL, draft.snapshot().mapType)
        assertEquals(MapLanguage.CHINESE, draft.snapshot().mapLanguage)
    }

    /** 选择器和通用更新均不能把能力外的值写入草稿。 */
    @Test fun unsupported_choices_are_rejected_without_losing_switches() {
        val draft = MainMapSettingsDraft(DemoMapSettings(), setOf(MapType.NORMAL), setOf(MapLanguage.CHINESE))
        draft.select(MapType.SATELLITE, MapLanguage.ENGLISH)
        draft.update { it.copy(mapType = MapType.SATELLITE, mapLanguage = MapLanguage.ENGLISH, trafficEnabled = true) }
        assertEquals(MapType.NORMAL, draft.snapshot().mapType)
        assertEquals(MapLanguage.CHINESE, draft.snapshot().mapLanguage)
        assertTrue(draft.snapshot().trafficEnabled)
    }

    /** 缺少语言能力时保留已有语言，不伪造厂商支持；地图至少提供正常类型草稿。 */
    @Test fun missing_capabilities_have_explicit_safe_fallbacks() {
        val draft = MainMapSettingsDraft(DemoMapSettings(mapLanguage = MapLanguage.ENGLISH), emptySet(), emptySet())
        assertEquals(listOf(MapType.NORMAL), draft.mapTypes)
        assertTrue(draft.mapLanguages.isEmpty())
        draft.select(MapType.NORMAL, null)
        assertEquals(MapLanguage.ENGLISH, draft.snapshot().mapLanguage)
    }
}
