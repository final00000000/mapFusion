package com.mapfusion.demo.presentation.navigation

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.mapfusion.api.model.OfficialNavigationDayNightMode
import com.mapfusion.api.model.OfficialNavigationMode
import com.mapfusion.api.model.OfficialNavigationState
import com.mapfusion.api.model.OfficialRouteSummary
import com.mapfusion.api.model.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** 纯 JVM 测试，不触碰 Android 主线程或导航 SDK。 */
class OfficialNavigationViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule: TestWatcher = MainDispatcherRule()

    /** 恢复已保存选择并验证后续选择写回同一保存协议，可供新页面再次恢复。 */
    @Test
    fun `restores saved options and persists new selections`() {
        val handle = SavedStateHandle(
            mapOf(
                OfficialNavigationViewModel.KEY_NAVIGATION_MODE to "SIMULATED",
                OfficialNavigationViewModel.KEY_TRAVEL_MODE to "WALKING",
                OfficialNavigationViewModel.KEY_SIMULATION_SPEED to 80,
                OfficialNavigationViewModel.KEY_VOICE_GUIDANCE to false,
                OfficialNavigationViewModel.KEY_DAY_NIGHT_MODE to "NIGHT",
            ),
        )
        val viewModel = OfficialNavigationViewModel(handle)
        viewModel.configureCapabilities(
            setOf(OfficialNavigationMode.REAL, OfficialNavigationMode.SIMULATED),
            setOf(TravelMode.DRIVING, TravelMode.WALKING, TravelMode.BICYCLE),
        )
        val restored = viewModel.state.value
        assertEquals(OfficialNavigationMode.SIMULATED, restored.navigationMode)
        assertEquals(TravelMode.WALKING, restored.travelMode)
        assertEquals(80, restored.simulationSpeed)
        assertFalse(restored.voiceGuidanceEnabled)
        assertEquals(OfficialNavigationDayNightMode.NIGHT, restored.dayNightMode)

        viewModel.selectNavigationMode(OfficialNavigationMode.REAL)
        viewModel.selectTravelMode(TravelMode.BICYCLE)
        viewModel.setSimulationSpeed(100)
        viewModel.setVoiceGuidanceEnabled(true)
        viewModel.setDayNightMode(OfficialNavigationDayNightMode.DAY)

        assertEquals("REAL", handle.get<String>(OfficialNavigationViewModel.KEY_NAVIGATION_MODE))
        assertEquals("BICYCLE", handle.get<String>(OfficialNavigationViewModel.KEY_TRAVEL_MODE))
        assertEquals(100, handle.get<Int>(OfficialNavigationViewModel.KEY_SIMULATION_SPEED))
        assertEquals(true, handle.get<Boolean>(OfficialNavigationViewModel.KEY_VOICE_GUIDANCE))
        assertEquals("DAY", handle.get<String>(OfficialNavigationViewModel.KEY_DAY_NIGHT_MODE))

        val recreated = OfficialNavigationViewModel(handle)
        recreated.configureCapabilities(
            setOf(OfficialNavigationMode.REAL, OfficialNavigationMode.SIMULATED),
            setOf(TravelMode.DRIVING, TravelMode.WALKING, TravelMode.BICYCLE),
        )
        assertEquals(OfficialNavigationMode.REAL, recreated.state.value.navigationMode)
        assertEquals(TravelMode.BICYCLE, recreated.state.value.travelMode)
        assertEquals(100, recreated.state.value.simulationSpeed)
    }

    /** 未知枚举和越界速度应确定性降级，不让非法恢复数据进入导航请求。 */
    @Test
    fun `invalid enum names and out of range speed fall back deterministically`() {
        val handle = SavedStateHandle(
            mapOf(
                OfficialNavigationViewModel.KEY_NAVIGATION_MODE to "BOGUS",
                OfficialNavigationViewModel.KEY_TRAVEL_MODE to "TELEPORT",
                OfficialNavigationViewModel.KEY_SIMULATION_SPEED to 9_999,
                OfficialNavigationViewModel.KEY_DAY_NIGHT_MODE to "TWILIGHT",
            ),
        )
        val viewModel = OfficialNavigationViewModel(handle)
        viewModel.configureCapabilities(
            setOf(OfficialNavigationMode.REAL, OfficialNavigationMode.SIMULATED),
            setOf(TravelMode.DRIVING, TravelMode.WALKING),
        )
        val state = viewModel.state.value
        assertEquals(OfficialNavigationMode.REAL, state.navigationMode)
        assertEquals(TravelMode.DRIVING, state.travelMode)
        assertEquals(OFFICIAL_NAVIGATION_MAX_SPEED, state.simulationSpeed)
        assertEquals(OfficialNavigationDayNightMode.AUTO, state.dayNightMode)
    }

    /** 当前变体不支持的保存选项降级到能力表中的合法选择，后续非法选择也被拒绝。 */
    @Test
    fun `unsupported saved options downgrade to first supported choices`() {
        val handle = SavedStateHandle(
            mapOf(
                OfficialNavigationViewModel.KEY_NAVIGATION_MODE to "SIMULATED",
                OfficialNavigationViewModel.KEY_TRAVEL_MODE to "TRANSIT",
            ),
        )
        val viewModel = OfficialNavigationViewModel(handle)
        viewModel.configureCapabilities(
            setOf(OfficialNavigationMode.REAL),
            setOf(TravelMode.DRIVING, TravelMode.WALKING),
        )
        assertEquals(OfficialNavigationMode.REAL, viewModel.state.value.navigationMode)
        assertEquals(TravelMode.DRIVING, viewModel.state.value.travelMode)

        viewModel.selectNavigationMode(OfficialNavigationMode.SIMULATED)
        viewModel.selectTravelMode(TravelMode.TRANSIT)
        assertEquals(OfficialNavigationMode.REAL, viewModel.state.value.navigationMode)
        assertEquals(TravelMode.DRIVING, viewModel.state.value.travelMode)
    }

    /** 所有模拟速度入口统一限幅到 20–120 km/h，范围内的值保持原样。 */
    @Test
    fun `simulation speed clamps to supported range`() {
        val viewModel = OfficialNavigationViewModel(SavedStateHandle())
        viewModel.setSimulationSpeed(5)
        assertEquals(OFFICIAL_NAVIGATION_MIN_SPEED, viewModel.state.value.simulationSpeed)
        viewModel.setSimulationSpeed(500)
        assertEquals(OFFICIAL_NAVIGATION_MAX_SPEED, viewModel.state.value.simulationSpeed)
        viewModel.setSimulationSpeed(75)
        assertEquals(75, viewModel.state.value.simulationSpeed)
    }

    /** 路线下标夹紧到列表范围，清空路线后固定归零，避免渲染和切换越界。 */
    @Test
    fun `route index stays inside route bounds`() {
        val viewModel = OfficialNavigationViewModel(SavedStateHandle())
        val routes = listOf(
            OfficialRouteSummary("r1", 1_000, 120),
            OfficialRouteSummary("r2", 2_000, 240),
            OfficialRouteSummary("r3", 3_000, 360),
        )
        viewModel.onRoutesReady(routes, 99)
        assertEquals(2, viewModel.state.value.selectedRouteIndex)
        viewModel.selectRoute(-1)
        assertEquals(0, viewModel.state.value.selectedRouteIndex)
        viewModel.selectRoute(1)
        assertEquals(1, viewModel.state.value.selectedRouteIndex)
        viewModel.onRoutesReady(emptyList(), 5)
        assertEquals(0, viewModel.state.value.selectedRouteIndex)
        assertTrue(viewModel.state.value.routeSummaries.isEmpty())
    }

    /** 新会话重置旧运行状态与权限等待，但真实/模拟、出行、速度、语音和配色保持恢复值。 */
    @Test
    fun `runtime reset clears phantom navigation but keeps options`() {
        val viewModel = OfficialNavigationViewModel(
            SavedStateHandle(
                mapOf(
                    OfficialNavigationViewModel.KEY_NAVIGATION_MODE to "SIMULATED",
                    OfficialNavigationViewModel.KEY_TRAVEL_MODE to "WALKING",
                    OfficialNavigationViewModel.KEY_SIMULATION_SPEED to 90,
                    OfficialNavigationViewModel.KEY_VOICE_GUIDANCE to false,
                    OfficialNavigationViewModel.KEY_DAY_NIGHT_MODE to "NIGHT",
                ),
            ),
        )
        viewModel.configureCapabilities(
            setOf(OfficialNavigationMode.REAL, OfficialNavigationMode.SIMULATED),
            setOf(TravelMode.DRIVING, TravelMode.WALKING),
        )
        viewModel.update {
            it.copy(
                navigationState = OfficialNavigationState.NAVIGATING,
                routeSummaries = listOf(OfficialRouteSummary("r", 1_000, 60)),
                selectedRouteIndex = 0,
                startRequestPending = true,
                navigationStarted = true,
                pendingRealNavigationStart = true,
            )
        }
        viewModel.resetRuntimeSession()

        val reset = viewModel.state.value
        assertEquals(OfficialNavigationState.INITIALIZING, reset.navigationState)
        assertTrue(reset.routeSummaries.isEmpty())
        assertEquals(0, reset.selectedRouteIndex)
        assertFalse(reset.startRequestPending)
        assertFalse(reset.navigationStarted)
        assertFalse(reset.pendingRealNavigationStart)
        assertFalse(reset.controlsBlocked)
        assertEquals(OfficialNavigationMode.SIMULATED, reset.navigationMode)
        assertEquals(TravelMode.WALKING, reset.travelMode)
        assertEquals(90, reset.simulationSpeed)
        assertFalse(reset.voiceGuidanceEnabled)
        assertEquals(OfficialNavigationDayNightMode.NIGHT, reset.dayNightMode)
    }

    /** 初始化、导航、READY、到达和失败共同验证等待标记与占位层随状态归约更新。 */
    @Test
    fun `state transitions clear pending and drive placeholder`() {
        val viewModel = OfficialNavigationViewModel(SavedStateHandle())
        viewModel.configureCapabilities(
            setOf(OfficialNavigationMode.REAL, OfficialNavigationMode.SIMULATED),
            setOf(TravelMode.DRIVING, TravelMode.WALKING),
        )
        viewModel.onStateChanged(OfficialNavigationState.INITIALIZING)
        viewModel.markStartSucceeded(OfficialNavigationState.PLANNING)
        assertTrue(viewModel.state.value.startRequestPending)
        assertTrue(viewModel.state.value.navigationStarted)

        viewModel.onStateChanged(OfficialNavigationState.NAVIGATING)
        assertEquals(OfficialNavigationState.NAVIGATING, viewModel.state.value.navigationState)
        assertFalse(viewModel.state.value.startRequestPending)
        assertFalse(viewModel.state.value.placeholderVisible)

        viewModel.onStateChanged(OfficialNavigationState.READY)
        assertTrue(viewModel.state.value.placeholderVisible)
        assertEquals("选择导航模式后开始", viewModel.state.value.placeholderText)

        viewModel.markStartFailed()
        assertFalse(viewModel.state.value.startRequestPending)

        viewModel.onArrived()
        assertEquals(OfficialNavigationState.ARRIVED, viewModel.state.value.navigationState)
        assertTrue(viewModel.state.value.placeholderVisible)
        assertEquals("导航已完成", viewModel.state.value.placeholderText)

        viewModel.onStateChanged(OfficialNavigationState.FAILED)
        assertTrue(viewModel.state.value.placeholderVisible)
    }

    /** 导航运行标记不持久化，SavedStateHandle 只保存用户可恢复的选择。 */
    @Test
    fun `runtime flags are not written to saved state`() {
        val handle = SavedStateHandle()
        val viewModel = OfficialNavigationViewModel(handle)
        viewModel.onStateChanged(OfficialNavigationState.NAVIGATING)
        assertNull(handle.get<String>("officialNavigation.navigationState"))
        assertNull(handle.get<Boolean>("officialNavigation.pending"))
        assertNull(handle.get<Boolean>("officialNavigation.started"))
        assertEquals("REAL", handle.get<String>(OfficialNavigationViewModel.KEY_NAVIGATION_MODE))
    }
    /** 宽整数保存值先限幅再转 Int，极大速度不能因溢出落到错误下界。 */
    @Test
    fun `large saved speed clamps before integer conversion`() {
        val viewModel = OfficialNavigationViewModel(SavedStateHandle(mapOf(
            OfficialNavigationViewModel.KEY_SIMULATION_SPEED to Long.MAX_VALUE,
        )))
        assertEquals(OFFICIAL_NAVIGATION_MAX_SPEED, viewModel.state.value.simulationSpeed)
    }

    /** 隐私撤回立即阻断控件并清除启动、导航和权限运行标记。 */
    @Test
    fun `withdrawn consent blocks controls and clears active runtime`() {
        val viewModel = OfficialNavigationViewModel(SavedStateHandle())
        viewModel.update { it.copy(navigationState = OfficialNavigationState.NAVIGATING,
            navigationStarted = true, startRequestPending = true, pendingRealNavigationStart = true) }
        viewModel.blockForConsent("需要重新确认隐私")
        val state = viewModel.state.value
        assertEquals(OfficialNavigationState.DESTROYED, state.navigationState)
        assertTrue(state.controlsBlocked)
        assertTrue(state.placeholderVisible)
        assertFalse(state.navigationStarted)
        assertFalse(state.startRequestPending)
        assertFalse(state.pendingRealNavigationStart)
    }

}

/** 每个 JVM 用例临时替换主协程调度器，结束后恢复，避免跨测试污染。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    /** 输入测试描述，在用例开始前把 Dispatchers.Main 替换为可控测试调度器。 */
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    /** 用例结束后撤销主调度器替换，即使用例失败也不影响后续测试。 */
    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
