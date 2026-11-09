package com.mapfusion.demo.presentation.navigation

import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.OfficialNavigationCapabilities
import com.mapfusion.api.model.OfficialNavigationFeature
import com.mapfusion.api.model.OfficialNavigationMode
import com.mapfusion.api.model.OfficialNavigationState
import com.mapfusion.api.model.OfficialRouteSummary
import com.mapfusion.api.model.TravelMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证渲染和命令共享的纯控件策略，不实例化 Android 控件或导航引擎。 */
class OfficialNavigationControlPolicyTest {
    /** 输入能力集合生成最小测试能力表，厂商名仅为统一值对象，不装载厂商依赖。 */
    private fun capabilities(vararg features: OfficialNavigationFeature) = OfficialNavigationCapabilities(
        provider = Provider.AMAP, features = features.toSet(),
        modes = setOf(OfficialNavigationMode.REAL, OfficialNavigationMode.SIMULATED),
        travelModes = setOf(TravelMode.DRIVING), maxWaypoints = 0,
    )

    /** READY 允许一次开始和选择；权限等待与成功启动后的同会话不能重复开始。 */
    @Test
    fun `ready starts once and permission pending blocks choices`() {
        val ready = OfficialNavigationUiState(navigationState = OfficialNavigationState.READY)
        val capabilities = capabilities()
        assertTrue(OfficialNavigationControlPolicy.from(ready, capabilities, true).start)
        val pending = OfficialNavigationControlPolicy.from(ready.copy(pendingRealNavigationStart = true), capabilities, true)
        assertFalse(pending.start)
        assertFalse(pending.choices)
        val arrived = OfficialNavigationControlPolicy.from(ready.copy(
            navigationState = OfficialNavigationState.ARRIVED, navigationStarted = true,
        ), capabilities, true)
        assertFalse(arrived.start)
        assertFalse(arrived.choices)
    }

    /** 规划期只能停止/多路线切换，进入暂停仍允许恢复和高级控制。 */
    @Test
    fun `planning and paused expose different runtime controls`() {
        val routes = listOf(OfficialRouteSummary("a", 100, 10), OfficialRouteSummary("b", 200, 20))
        val planning = OfficialNavigationUiState(navigationState = OfficialNavigationState.PLANNING, routeSummaries = routes)
        val policy = OfficialNavigationControlPolicy.from(planning, capabilities(), true)
        assertTrue(policy.stop)
        assertTrue(policy.route)
        assertFalse(policy.pauseResume)
        assertFalse(policy.advanced)
        assertFalse(OfficialNavigationControlPolicy.from(planning.copy(routeSummaries = routes.take(1)), capabilities(), true).route)
        val paused = OfficialNavigationControlPolicy.from(planning.copy(navigationState = OfficialNavigationState.PAUSED), capabilities(), true)
        assertTrue(paused.pauseResume)
        assertTrue(paused.advanced)
    }

    /** 速度行只在支持模拟速度的模拟模式出现，运行期调速还必须声明独立能力。 */
    @Test
    fun `runtime simulation speed requires both mode and capability`() {
        val running = OfficialNavigationUiState(navigationMode = OfficialNavigationMode.SIMULATED,
            navigationState = OfficialNavigationState.NAVIGATING, navigationStarted = true)
        val basic = capabilities(OfficialNavigationFeature.SIMULATION_SPEED)
        assertTrue(OfficialNavigationControlPolicy.from(running, basic, true).speedVisible)
        assertFalse(OfficialNavigationControlPolicy.from(running, basic, true).speedEnabled)
        val runtime = capabilities(OfficialNavigationFeature.SIMULATION_SPEED, OfficialNavigationFeature.RUNTIME_SIMULATION_SPEED)
        assertTrue(OfficialNavigationControlPolicy.from(running, runtime, true).speedEnabled)
        assertFalse(OfficialNavigationControlPolicy.from(running.copy(navigationMode = OfficialNavigationMode.REAL), runtime, true).speedVisible)
        assertFalse(OfficialNavigationControlPolicy.from(running, capabilities(), true).speedVisible)
    }

    /** 会话缺失或隐私阻断时关闭所有有副作用控件，即使残留业务快照还显示导航中。 */
    @Test
    fun `missing session or blocked controls disables every action`() {
        val running = OfficialNavigationUiState(navigationMode = OfficialNavigationMode.SIMULATED,
            navigationState = OfficialNavigationState.NAVIGATING,
            routeSummaries = listOf(OfficialRouteSummary("a", 100, 10), OfficialRouteSummary("b", 200, 20)))
        val capabilities = capabilities(OfficialNavigationFeature.SIMULATION_SPEED, OfficialNavigationFeature.RUNTIME_SIMULATION_SPEED)
        val policies = listOf(
            OfficialNavigationControlPolicy.from(running, capabilities, false),
            OfficialNavigationControlPolicy.from(running.copy(controlsBlocked = true), capabilities, true),
        )
        policies.forEach {
            assertFalse(it.start); assertFalse(it.pauseResume); assertFalse(it.route); assertFalse(it.stop)
            assertFalse(it.choices); assertFalse(it.advanced); assertFalse(it.settings); assertFalse(it.speedEnabled)
        }
    }
}
