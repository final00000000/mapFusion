package com.mapfusion.demo.presentation.navigation

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.OfficialNavigationDayNightMode
import com.mapfusion.api.model.OfficialNavigationEvent
import com.mapfusion.api.model.OfficialNavigationMode
import com.mapfusion.api.model.OfficialNavigationState
import com.mapfusion.api.model.OfficialRouteSummary
import com.mapfusion.api.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher

/** 验证拆分后事件业务仍归约在 ViewModel，纯展示事件不会改写引擎/路线/持久化选择。 */
class OfficialNavigationEventStateTest {
    @get:Rule val instantTaskExecutorRule = InstantTaskExecutorRule()
    @get:Rule val mainDispatcherRule: TestWatcher = MainDispatcherRule()

    /** 构造只含统一能力值的 ViewModel，使用虚构路线和提示，不创建 Context 或原生会话。 */
    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) = OfficialNavigationViewModel(handle).apply {
        configureCapabilities(setOf(OfficialNavigationMode.REAL, OfficialNavigationMode.SIMULATED),
            setOf(TravelMode.DRIVING, TravelMode.WALKING))
    }

    /** 路线事件夹紧下标并复制厂商列表，空路线保持已有详情且不会产生非法下一下标。 */
    @Test
    fun `route event owns bounds copy and detail in view model`() {
        val viewModel = viewModel()
        val routes = mutableListOf(OfficialRouteSummary("a", 500, 60), OfficialRouteSummary("b", 1_000, 120))
        viewModel.onNavigationEvent(OfficialNavigationEvent.RoutesReady(routes, 99), OfficialNavigationState.PLANNING)
        assertEquals(1, viewModel.state.value.selectedRouteIndex)
        assertEquals("路线 2/2 · 1.0 km · 2 分钟", viewModel.state.value.detailText)
        assertEquals(0, viewModel.nextRouteIndex())
        routes.clear()
        assertEquals(2, viewModel.state.value.routeSummaries.size)
        val detail = viewModel.state.value.detailText
        viewModel.onNavigationEvent(OfficialNavigationEvent.RoutesReady(emptyList(), -99), OfficialNavigationState.PLANNING)
        assertEquals(0, viewModel.state.value.selectedRouteIndex)
        assertNull(viewModel.nextRouteIndex())
        assertEquals(detail, viewModel.state.value.detailText)
    }

    /** 仅展示的语音事件更新详情；隐藏车道事件保留文案，不复制控制器中的业务状态。 */
    @Test
    fun `presentation events update only detail and hidden events preserve it`() {
        val viewModel = viewModel()
        viewModel.markStartSucceeded(OfficialNavigationState.NAVIGATING)
        viewModel.onStateChanged(OfficialNavigationState.NAVIGATING)
        val before = viewModel.state.value
        viewModel.onNavigationEvent(OfficialNavigationEvent.VoicePrompt("测试提示"), OfficialNavigationState.NAVIGATING)
        assertEquals(before.copy(detailText = "测试提示", detailTone = NaviTone.INFO), viewModel.state.value)
        val spoken = viewModel.state.value
        viewModel.onNavigationEvent(OfficialNavigationEvent.LaneGuidance(false, 3), OfficialNavigationState.NAVIGATING)
        assertEquals(spoken, viewModel.state.value)
    }

    /** start 内同步导航事件解除等待并隐藏占位，返回成功不能重新置等待或擦掉事件详情。 */
    @Test
    fun `synchronous start events are not overwritten by success result`() {
        val viewModel = viewModel()
        viewModel.onStateChanged(OfficialNavigationState.READY)
        viewModel.prepareStart()
        assertTrue(viewModel.state.value.startRequestPending)
        viewModel.onNavigationEvent(OfficialNavigationEvent.StateChanged(OfficialNavigationState.NAVIGATING), OfficialNavigationState.NAVIGATING)
        viewModel.onNavigationEvent(OfficialNavigationEvent.VoicePrompt("引导已开始"), OfficialNavigationState.NAVIGATING)
        viewModel.markStartSucceeded(OfficialNavigationState.NAVIGATING)
        assertFalse(viewModel.state.value.startRequestPending)
        assertFalse(viewModel.state.value.placeholderVisible)
        assertTrue(viewModel.state.value.navigationStarted)
        assertEquals("引导已开始", viewModel.state.value.detailText)
    }

    /** 到达事件统一更新终态、详情和占位；运行期到达标记不写 SavedStateHandle。 */
    @Test
    fun `arrived event owns terminal business state`() {
        val handle = SavedStateHandle()
        val viewModel = viewModel(handle)
        viewModel.markStartSucceeded(OfficialNavigationState.PLANNING)
        viewModel.onNavigationEvent(OfficialNavigationEvent.Arrived, OfficialNavigationState.ARRIVED)
        assertEquals(OfficialNavigationState.ARRIVED, viewModel.state.value.navigationState)
        assertFalse(viewModel.state.value.startRequestPending)
        assertTrue(viewModel.state.value.placeholderVisible)
        assertEquals("导航已完成", viewModel.state.value.placeholderText)
        assertEquals("已到达目的地", viewModel.state.value.detailText)
        assertNull(handle.get<String>("officialNavigation.navigationState"))
    }

    /** 统一错误使用宿主提供的引擎快照并解除 pending，保留错误占位而非伪造第二套引擎状态。 */
    @Test
    fun `error event uses engine snapshot and error tone`() {
        val viewModel = viewModel()
        viewModel.prepareStart()
        viewModel.onNavigationEvent(OfficialNavigationEvent.Error(MapError(ErrorType.NETWORK, "测试网络异常")), OfficialNavigationState.FAILED)
        assertEquals(OfficialNavigationState.FAILED, viewModel.state.value.navigationState)
        assertFalse(viewModel.state.value.startRequestPending)
        assertEquals(NaviTone.ERROR, viewModel.state.value.detailTone)
        assertEquals("官方导航错误：测试网络异常", viewModel.state.value.placeholderText)
        assertTrue(viewModel.state.value.placeholderVisible)
    }

    /** 监听器尚未安装时，初始化失败快照使用 lastError 或缺省说明；READY 也必须解除初始化。 */
    @Test
    fun `initialization snapshots expose ready and failure details`() {
        val viewModel = viewModel()
        viewModel.onEngineSnapshot(OfficialNavigationState.READY, null)
        assertEquals("选择导航模式后开始", viewModel.state.value.placeholderText)
        viewModel.onEngineSnapshot(OfficialNavigationState.FAILED, MapError(ErrorType.UNKNOWN, "测试初始化失败"))
        assertEquals("官方导航失败：测试初始化失败", viewModel.state.value.detailText)
        viewModel.onEngineSnapshot(OfficialNavigationState.FAILED, null)
        assertEquals("官方导航初始化失败，请返回后重试", viewModel.state.value.detailText)
    }

    /** 撤回清空运行期路线与权限，后到状态、路线、语音、到达、错误及成功结果均不能复活页面。 */
    @Test
    fun `withdrawal blocks late events and pending permission`() {
        val viewModel = viewModel()
        viewModel.awaitRealNavigationPermission()
        viewModel.onRoutesReady(listOf(OfficialRouteSummary("a", 500, 60)), 0)
        viewModel.blockForConsent("需要重新同意")
        val blocked = viewModel.state.value
        val events = listOf(
            OfficialNavigationEvent.StateChanged(OfficialNavigationState.NAVIGATING),
            OfficialNavigationEvent.RoutesReady(listOf(OfficialRouteSummary("old", 100, 10)), 0),
            OfficialNavigationEvent.VoicePrompt("旧会话提示"), OfficialNavigationEvent.Arrived,
            OfficialNavigationEvent.Error(MapError(ErrorType.UNKNOWN, "旧会话错误")),
        )
        events.forEach { viewModel.onNavigationEvent(it, OfficialNavigationState.NAVIGATING) }
        viewModel.onEngineSnapshot(OfficialNavigationState.READY, null)
        viewModel.markStartSucceeded(OfficialNavigationState.NAVIGATING)
        assertFalse(viewModel.consumePermissionRequest())
        assertEquals(blocked, viewModel.state.value)
        assertTrue(blocked.routeSummaries.isEmpty())
    }

    /** 权限等待阻断模式切换，消费后解除；页面重建不会恢复旧权限启动请求。 */
    @Test
    fun `permission waiting is one shot and not restored`() {
        val handle = SavedStateHandle()
        val viewModel = viewModel(handle)
        viewModel.awaitRealNavigationPermission()
        viewModel.selectNavigationMode(OfficialNavigationMode.SIMULATED)
        assertEquals(OfficialNavigationMode.REAL, viewModel.state.value.navigationMode)
        assertTrue(viewModel.consumePermissionRequest())
        assertFalse(viewModel.consumePermissionRequest())
        viewModel.selectNavigationMode(OfficialNavigationMode.SIMULATED)
        assertEquals(OfficialNavigationMode.SIMULATED, viewModel.state.value.navigationMode)
        viewModel.awaitRealNavigationPermission()
        val recreated = this.viewModel(handle)
        recreated.resetRuntimeSession()
        assertFalse(recreated.consumePermissionRequest())
    }

    /** 下一配色只是纯预览，不提前写保存状态；厂商成功确认后按自动/白天/夜间循环。 */
    @Test
    fun `day night preview does not persist before confirmation`() {
        val handle = SavedStateHandle()
        val viewModel = viewModel(handle)
        assertEquals(OfficialNavigationDayNightMode.DAY, viewModel.nextDayNightMode())
        assertEquals("AUTO", handle.get<String>(OfficialNavigationViewModel.KEY_DAY_NIGHT_MODE))
        viewModel.setDayNightMode(viewModel.nextDayNightMode())
        assertEquals(OfficialNavigationDayNightMode.NIGHT, viewModel.nextDayNightMode())
        viewModel.setDayNightMode(viewModel.nextDayNightMode())
        assertEquals(OfficialNavigationDayNightMode.AUTO, viewModel.nextDayNightMode())
    }
}
