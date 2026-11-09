package com.mapfusion.demo.main

import com.mapfusion.demo.presentation.main.DemoToolCategory
import org.junit.Assert.*
import org.junit.Test

/** 核对纯入口目录，不需要 Android 或厂商初始化。 */
class MainActionCatalogTest {
    /** 五类入口并集应包含全部意图且没有重复。 */
    @Test fun every_action_has_one_entry_when_official_navigation_is_available() {
        val actions = DemoToolCategory.entries.flatMap { MainActionCatalog.actions(it, true) }
        assertEquals(MainAction.entries.toSet(), actions.map { it.action }.toSet())
        assertEquals(MainAction.entries.size, actions.size)
        assertTrue(actions.all { it.title.isNotBlank() })
    }

    /** 当前厂商没有官方导航时，只删除官方入口，其他按钮保持不变。 */
    @Test fun capability_filter_removes_only_official_navigation() {
        val enabled = DemoToolCategory.entries.flatMap { MainActionCatalog.actions(it, true) }.map { it.action }.toSet()
        val disabled = DemoToolCategory.entries.flatMap { MainActionCatalog.actions(it, false) }.map { it.action }.toSet()
        assertEquals(setOf(MainAction.OFFICIAL_NAVIGATION), enabled - disabled)
        assertEquals(emptySet<MainAction>(), disabled - enabled)
    }

    /** 危险操作的视觉语义不随分类或能力发生变化。 */
    @Test fun destructive_actions_keep_danger_style() {
        val danger = DemoToolCategory.entries.flatMap { MainActionCatalog.actions(it, true) }
            .filter { it.style == MainActionStyle.DANGER }.map { it.action }.toSet()
        assertEquals(setOf(MainAction.NAVIGATION_STOP, MainAction.CLEAR_MAP, MainAction.TRACK_CLEAR), danger)
    }

    /** 四列网格在边界数量上保持至少一行。 */
    @Test fun tool_row_count_handles_four_column_boundaries() {
        listOf(0 to 1, 1 to 1, 4 to 1, 5 to 2, 8 to 2, 9 to 3).forEach { (count, rows) ->
            assertEquals(rows, MainActionCatalog.rowCount(count))
        }
    }
}
