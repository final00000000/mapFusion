package com.mapfusion.demo.presentation.main

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.mapfusion.api.model.EmbeddedNavigationMode
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapLocation
import com.mapfusion.api.model.TravelMode
import com.mapfusion.demo.data.PrivacyDecision
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 验证明确的用户意图归约，而不是让页面调用通用 copy 修改状态。 */
class MainViewModelInteractionTest {
    @get:Rule val mainThread = InstantTaskExecutorRule()

    /** 分类、路线和地图设置通过明确操作保存并恢复。 */
    @Test fun explicit_selections_restore_into_a_new_viewmodel() {
        val saved = SavedStateHandle()
        val vm = MainViewModel(saved)
        vm.selectToolCategory(DemoToolCategory.TRACK)
        vm.selectRouteOptions(TravelMode.WALKING, EmbeddedNavigationMode.SIMULATED)
        vm.applyMapSettings(DemoMapSettings(trafficEnabled = true, compassEnabled = false))
        val restored = MainViewModel(snapshot(saved)).state.value
        assertEquals(DemoToolCategory.TRACK, restored.toolCategory)
        assertEquals(TravelMode.WALKING, restored.routeMode)
        assertEquals(EmbeddedNavigationMode.SIMULATED, restored.navigationMode)
        assertEquals(vm.state.value.mapSettings, restored.mapSettings)
    }

    /** 地图阶段、定位、导航和隐私缓存不能伪装成新会话已就绪。 */
    @Test fun runtime_values_are_not_persisted() {
        val saved = SavedStateHandle()
        val vm = MainViewModel(saved)
        vm.setMapPhase(MainMapPhase.READY, 10)
        vm.setLocation(MapLocation(LatLng(10.0, 20.0)))
        vm.setNavigationActive(true)
        vm.setPrivacyDecision(PrivacyDecision.ACCEPTED)
        vm.showStatus(DemoStatus("合成结果", "当前会话", DemoStatusKind.SUCCESS))
        val restored = MainViewModel(snapshot(saved)).state.value
        assertEquals(MainMapPhase.DISABLED, restored.mapPhase)
        assertEquals(0, restored.capabilityCount)
        assertNull(restored.lastLocation)
        assertFalse(restored.embeddedNavigationActive)
        assertEquals(PrivacyDecision.UNDECIDED, restored.privacyDecision)
        assertEquals(DemoStatus(), restored.status)
    }

    /** 新会话清运行态但保留用户选择与授权待办。 */
    @Test fun reset_session_retains_choices_and_permission_workflow() {
        val vm = MainViewModel(SavedStateHandle())
        vm.selectToolCategory(DemoToolCategory.SEARCH)
        vm.awaitLocationPermission(LocationPermissionAction.START_TRACK, true)
        vm.setMapPhase(MainMapPhase.READY, 10)
        vm.setLocation(MapLocation(LatLng(10.0, 20.0)))
        vm.setNavigationActive(true)
        vm.resetSessionState()
        assertEquals(DemoToolCategory.SEARCH, vm.state.value.toolCategory)
        assertEquals(LocationPermissionAction.START_TRACK, vm.state.value.pendingLocationAction)
        assertTrue(vm.state.value.permissionRequestInFlight)
        assertEquals(MainMapPhase.DISABLED, vm.state.value.mapPhase)
        assertNull(vm.state.value.lastLocation)
        assertFalse(vm.state.value.embeddedNavigationActive)
    }

    /** 授权成功只返回一次待办并清除历史拒绝标记。 */
    @Test fun permission_grant_consumes_pending_action_once() {
        val vm = MainViewModel(SavedStateHandle())
        vm.awaitLocationPermission(LocationPermissionAction.LOCATE, true)
        vm.completeLocationPermission(false, true)
        vm.awaitLocationPermission(LocationPermissionAction.START_NAVIGATION, true)
        assertEquals(LocationPermissionAction.START_NAVIGATION, vm.completeLocationPermission(true))
        assertNull(vm.completeLocationPermission(true))
        assertFalse(vm.state.value.permissionRequestInFlight)
        assertFalse(vm.state.value.permissionDenied)
        assertFalse(vm.state.value.permissionPermanentlyDenied)
    }

    /** 拒绝和永久拒绝均不返回可执行动作，但保留准确的错误类别。 */
    @Test fun permission_denial_never_returns_an_action() {
        val vm = MainViewModel(SavedStateHandle())
        vm.awaitLocationPermission(LocationPermissionAction.START_TRACK, true)
        assertNull(vm.completeLocationPermission(false))
        assertTrue(vm.state.value.permissionDenied)
        assertFalse(vm.state.value.permissionPermanentlyDenied)
        vm.awaitLocationPermission(LocationPermissionAction.START_TRACK, true)
        assertNull(vm.completeLocationPermission(false, true))
        assertTrue(vm.state.value.permissionPermanentlyDenied)
        assertNull(vm.state.value.pendingLocationAction)
    }

    /** 撤回或取消后即便授权返回成功，也不能继续旧操作。 */
    @Test fun canceled_permission_cannot_continue_after_a_late_grant() {
        val vm = MainViewModel(SavedStateHandle())
        vm.awaitLocationPermission(LocationPermissionAction.START_NAVIGATION, true)
        vm.cancelPendingLocationAction()
        assertNull(vm.completeLocationPermission(true))
        assertNull(vm.state.value.pendingLocationAction)
        assertFalse(vm.state.value.permissionRequestInFlight)
    }

    /** 在途授权恢复后仍可一次性消费，不把运行中的 SDK 状态恢复回来。 */
    @Test fun pending_permission_survives_state_restoration() {
        val saved = SavedStateHandle()
        MainViewModel(saved).awaitLocationPermission(LocationPermissionAction.LOCATE, true)
        val restored = MainViewModel(snapshot(saved))
        assertTrue(restored.state.value.permissionRequestInFlight)
        assertEquals(LocationPermissionAction.LOCATE, restored.completeLocationPermission(true))
        assertNull(restored.completeLocationPermission(true))
    }

    /** 复制 SavedStateHandle 的可序列化值模拟新的状态所有者。 */
    private fun snapshot(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
}
