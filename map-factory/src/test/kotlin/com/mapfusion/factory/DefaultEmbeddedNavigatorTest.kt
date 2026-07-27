package com.mapfusion.factory

import android.os.Bundle
import android.view.View
import com.mapfusion.api.async.TimeoutHandle
import com.mapfusion.api.async.TimeoutScheduler
import com.mapfusion.api.capability.LocationClient
import com.mapfusion.api.capability.MapController
import com.mapfusion.api.capability.RoutePlanner
import com.mapfusion.api.coordinate.DefaultCoordinateConverter
import com.mapfusion.api.model.CameraPosition
import com.mapfusion.api.model.CameraUpdate
import com.mapfusion.api.model.CircleOptions
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.EmbeddedNavigationEvent
import com.mapfusion.api.model.EmbeddedNavigationMode
import com.mapfusion.api.model.EmbeddedNavigationOptions
import com.mapfusion.api.model.EmbeddedNavigationRequest
import com.mapfusion.api.model.EmbeddedNavigationState
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.GroundOverlayOptions
import com.mapfusion.api.model.HeatMapOptions
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.LatLngBounds
import com.mapfusion.api.model.LocationOptions
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapCircle
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapGroundOverlay
import com.mapfusion.api.model.MapHeatMap
import com.mapfusion.api.model.MapLocation
import com.mapfusion.api.model.MapMarker
import com.mapfusion.api.model.MapOverlay
import com.mapfusion.api.model.MapPolygon
import com.mapfusion.api.model.MapPolyline
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.MapSnapshot
import com.mapfusion.api.model.MapTextOverlay
import com.mapfusion.api.model.MapTileOverlay
import com.mapfusion.api.model.MapType
import com.mapfusion.api.model.MapUiOptions
import com.mapfusion.api.model.MarkerOptions
import com.mapfusion.api.model.PolygonOptions
import com.mapfusion.api.model.PolylineOptions
import com.mapfusion.api.model.RoutePath
import com.mapfusion.api.model.RouteRequest
import com.mapfusion.api.model.RouteResult
import com.mapfusion.api.model.RouteStep
import com.mapfusion.api.model.TextOverlayOptions
import com.mapfusion.api.model.TileOverlayOptions
import com.mapfusion.api.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class DefaultEmbeddedNavigatorTest {

    private val directExecutor = Executor { command -> command.run() }
    private val origin = LatLng(39.90, 116.39, CoordType.GCJ02)
    private val destination = LatLng(39.91, 116.40, CoordType.GCJ02)

    @Test
    fun startDrawProgressAndStopOwnOverlays() {
        val location = FakeLocationClient()
        val controller = FakeMapController()
        val planner = FakeRoutePlanner(immediate = true)
        val navigator = DefaultEmbeddedNavigator(controller, location, planner)
        val events = mutableListOf<EmbeddedNavigationEvent>()

        val accepted = navigator.start(request()) { events += it }

        assertTrue(accepted is MapResult.Success)
        assertEquals(EmbeddedNavigationState.NAVIGATING, navigator.state)
        assertEquals(1, planner.requests.size)
        assertEquals(1, location.startCount)
        assertEquals(2, controller.markers.size)
        assertEquals(1, controller.polylines.size)

        location.success(MapLocation(position = LatLng(39.905, 116.395, CoordType.GCJ02)))

        assertTrue(events.any { it is EmbeddedNavigationEvent.Progress })
        assertEquals(
            EmbeddedNavigationMode.REAL,
            events.filterIsInstance<EmbeddedNavigationEvent.Progress>().single().value.navigationMode,
        )
        assertEquals(3, controller.markers.size)
        assertEquals(LatLng(39.905, 116.395, CoordType.GCJ02), controller.lastCamera?.target)

        navigator.stop()

        assertEquals(EmbeddedNavigationState.STOPPED, navigator.state)
        assertTrue(controller.markers.all { it.removed })
        assertTrue(controller.polylines.all { it.removed })
    }

    @Test
    fun destroyIgnoresLateRouteCallback() {
        val location = FakeLocationClient()
        val controller = FakeMapController()
        val planner = FakeRoutePlanner(immediate = false)
        val navigator = DefaultEmbeddedNavigator(controller, location, planner)
        val events = mutableListOf<EmbeddedNavigationEvent>()

        navigator.start(request()) { events += it }
        val eventCountAtDestroy = events.size
        navigator.destroy()
        assertTrue(planner.handles.single().isCancelled)
        planner.completeLatest()

        assertEquals(EmbeddedNavigationState.DESTROYED, navigator.state)
        assertEquals(eventCountAtDestroy, events.size)
        assertEquals(0, location.startCount)
        assertTrue(controller.polylines.isEmpty())
    }

    @Test
    fun offRouteLocationReplansFromCurrentPosition() {
        var now = 1_000L
        val location = FakeLocationClient()
        val controller = FakeMapController()
        val planner = FakeRoutePlanner(immediate = true)
        val navigator = DefaultEmbeddedNavigator(controller, location, planner) { now }

        navigator.start(request()) { }
        val offRoute = LatLng(40.10, 116.60, CoordType.GCJ02)
        location.success(MapLocation(position = offRoute))

        assertEquals(2, planner.requests.size)
        assertEquals(offRoute, planner.requests.last().origin)
        assertEquals(EmbeddedNavigationState.NAVIGATING, navigator.state)
        assertEquals(2, location.handles.size)
        assertTrue(location.handles.first().isCancelled)
        assertFalse(location.handles.last().isCancelled)
        assertEquals(0, location.stopCount)
        assertTrue(controller.polylines.first().removed)
        assertFalse(controller.polylines.last().removed)
    }

    @Test
    fun arrivalStopsLocationAndReportsArrived() {
        val location = FakeLocationClient()
        val controller = FakeMapController()
        val planner = FakeRoutePlanner(immediate = true)
        val navigator = DefaultEmbeddedNavigator(controller, location, planner)
        val events = mutableListOf<EmbeddedNavigationEvent>()

        navigator.start(request()) { events += it }
        location.success(MapLocation(position = destination))

        assertEquals(EmbeddedNavigationState.ARRIVED, navigator.state)
        assertTrue(location.handles.single().isCancelled)
        assertEquals(0, location.stopCount)
        assertTrue(events.any { it is EmbeddedNavigationEvent.Arrived })
    }

    @Test
    fun arrivalNormalizesWgsRequestGcjRouteAndBdLocation() {
        val location = FakeLocationClient()
        val controller = FakeMapController()
        val planner = FakeRoutePlanner(immediate = true) {
            listOf(origin, destination)
        }
        val navigator = DefaultEmbeddedNavigator(controller, location, planner)
        val events = mutableListOf<EmbeddedNavigationEvent>()
        val wgsRequest = EmbeddedNavigationRequest(
            RouteRequest(
                mode = TravelMode.DRIVING,
                origin = origin.convertTo(CoordType.WGS84),
                destination = destination.convertTo(CoordType.WGS84),
            ),
            EmbeddedNavigationOptions(callbackExecutor = directExecutor),
        )

        navigator.start(wgsRequest) { events += it }
        location.success(MapLocation(position = destination.convertTo(CoordType.BD09)))

        assertEquals(EmbeddedNavigationState.ARRIVED, navigator.state)
        val arrived = events.filterIsInstance<EmbeddedNavigationEvent.Arrived>().single()
        assertEquals(CoordType.BD09, arrived.progress.location.position.coordType)
        assertEquals(CoordType.GCJ02, arrived.progress.path.polyline.first().coordType)
    }

    @Test
    fun rerouteNormalizesOriginAndDestinationToLocationCoordinateSystem() {
        var now = 1_000L
        val location = FakeLocationClient()
        val planner = FakeRoutePlanner(immediate = true) {
            listOf(origin.convertTo(CoordType.BD09), destination.convertTo(CoordType.BD09))
        }
        val navigator = DefaultEmbeddedNavigator(FakeMapController(), location, planner) { now }
        val request = EmbeddedNavigationRequest(
            RouteRequest(
                mode = TravelMode.DRIVING,
                origin = origin.convertTo(CoordType.WGS84),
                destination = destination.convertTo(CoordType.WGS84),
            ),
            EmbeddedNavigationOptions(callbackExecutor = directExecutor),
        )
        val offRoute = LatLng(40.10, 116.60, CoordType.GCJ02)

        navigator.start(request) { }
        location.success(MapLocation(position = offRoute))

        val reroute = planner.requests.last()
        assertEquals(2, planner.requests.size)
        assertEquals(offRoute, reroute.origin)
        assertEquals(CoordType.GCJ02, reroute.destination.coordType)
        assertEquals(destination.latitude, reroute.destination.latitude, 0.000_001)
        assertEquals(destination.longitude, reroute.destination.longitude, 0.000_001)
    }

    @Test
    fun unknownLocationCoordinateReportsErrorWithoutCalculatingProgress() {
        val location = FakeLocationClient()
        val events = mutableListOf<EmbeddedNavigationEvent>()
        val navigator = DefaultEmbeddedNavigator(
            FakeMapController(),
            location,
            FakeRoutePlanner(immediate = true),
        )

        navigator.start(request()) { events += it }
        location.success(MapLocation(position = destination.copy(coordType = CoordType.UNKNOWN)))

        assertEquals(EmbeddedNavigationState.NAVIGATING, navigator.state)
        assertTrue(events.any { it is EmbeddedNavigationEvent.Error && it.error.type == ErrorType.INVALID_PARAM })
        assertFalse(events.any { it is EmbeddedNavigationEvent.Progress })
    }

    @Test
    fun unknownRouteCoordinateFailsBeforeStartingLocation() {
        val location = FakeLocationClient()
        val controller = FakeMapController()
        val events = mutableListOf<EmbeddedNavigationEvent>()
        val navigator = DefaultEmbeddedNavigator(
            controller,
            location,
            FakeRoutePlanner(immediate = true) {
                listOf(origin, destination.copy(coordType = CoordType.UNKNOWN))
            },
        )

        navigator.start(request()) { events += it }

        assertEquals(EmbeddedNavigationState.FAILED, navigator.state)
        assertEquals(0, location.startCount)
        assertTrue(controller.polylines.isEmpty())
        assertTrue(events.any { it is EmbeddedNavigationEvent.Error && it.error.type == ErrorType.INVALID_PARAM })
    }

    @Test
    fun manualPauseIsNotResumedByHostLifecycle() {
        val location = FakeLocationClient()
        val navigator = DefaultEmbeddedNavigator(
            FakeMapController(),
            location,
            FakeRoutePlanner(immediate = true),
        )

        navigator.start(request()) { }
        navigator.pause()
        navigator.onResume()

        assertEquals(EmbeddedNavigationState.PAUSED, navigator.state)
        assertEquals(1, location.startCount)
        assertTrue(location.handles.single().isCancelled)
    }

    @Test
    fun lifecyclePauseResumesOnlyAfterHostResume() {
        val location = FakeLocationClient()
        val navigator = DefaultEmbeddedNavigator(
            FakeMapController(),
            location,
            FakeRoutePlanner(immediate = true),
        )

        navigator.start(request()) { }
        navigator.onPause()
        assertEquals(EmbeddedNavigationState.PAUSED, navigator.state)
        navigator.onResume()

        assertEquals(EmbeddedNavigationState.NAVIGATING, navigator.state)
        assertEquals(2, location.startCount)
        assertTrue(location.handles.first().isCancelled)
        assertFalse(location.handles.last().isCancelled)
    }

    @Test
    fun unknownCoordinateIsRejectedBeforePlanning() {
        val planner = FakeRoutePlanner(immediate = true)
        val navigator = DefaultEmbeddedNavigator(FakeMapController(), FakeLocationClient(), planner)
        val result = navigator.start(
            EmbeddedNavigationRequest(
                RouteRequest(TravelMode.DRIVING, origin.copy(coordType = CoordType.UNKNOWN), destination),
                EmbeddedNavigationOptions(callbackExecutor = directExecutor),
            ),
        ) { }

        assertTrue(result is MapResult.Failure)
        assertEquals(0, planner.requests.size)
        assertEquals(EmbeddedNavigationState.IDLE, navigator.state)
    }

    @Test
    fun listenerCanStopDuringPlanningEventWithoutStartingLocation() {
        val location = FakeLocationClient()
        val planner = FakeRoutePlanner(immediate = true)
        val navigator = DefaultEmbeddedNavigator(FakeMapController(), location, planner)

        navigator.start(request()) { event ->
            if (event == EmbeddedNavigationEvent.StateChanged(EmbeddedNavigationState.PLANNING)) {
                navigator.stop()
            }
        }

        assertEquals(EmbeddedNavigationState.STOPPED, navigator.state)
        assertEquals(0, planner.requests.size)
        assertEquals(0, location.startCount)
    }

    @Test
    fun stopDuringPendingPlanningCancelsOwnedHandleAndIgnoresLateResults() {
        val planner = FakeRoutePlanner(immediate = false)
        val location = FakeLocationClient()
        val navigator = DefaultEmbeddedNavigator(FakeMapController(), location, planner)
        val events = mutableListOf<EmbeddedNavigationEvent>()

        navigator.start(request()) { events += it }
        navigator.stop()

        assertTrue(planner.handles.single().isCancelled)
        assertEquals(0, location.stopCount)
        assertFalse(events.any { it is EmbeddedNavigationEvent.Error && it.error.type == ErrorType.CANCELLED })
        val eventCountAfterStop = events.size

        // 模拟厂商在取消后仍送达成功回调，generation 必须将其屏蔽。
        planner.completeLatest()

        assertEquals(EmbeddedNavigationState.STOPPED, navigator.state)
        assertEquals(eventCountAfterStop, events.size)
        assertTrue(location.handles.isEmpty())
    }

    @Test
    fun routeCreationFailureTransitionsToFailedAndDoesNotLeakOverlays() {
        val controller = FakeMapController().also { it.failOnMarker = true }
        val events = mutableListOf<EmbeddedNavigationEvent>()
        val navigator = DefaultEmbeddedNavigator(
            controller,
            FakeLocationClient(),
            FakeRoutePlanner(immediate = true),
        )

        navigator.start(request()) { events += it }

        assertEquals(EmbeddedNavigationState.FAILED, navigator.state)
        assertTrue(events.any { it is EmbeddedNavigationEvent.Error })
        assertTrue(controller.polylines.all { it.removed })
    }

    @Test
    fun controlMethodsQueueCallbacksAndPreserveSessionOrder() {
        val executor = ManualExecutor()
        val navigator = DefaultEmbeddedNavigator(
            FakeMapController(),
            FakeLocationClient(),
            FakeRoutePlanner(immediate = true),
        )
        val states = mutableListOf<EmbeddedNavigationState>()

        navigator.start(request(executor)) { event ->
            if (event is EmbeddedNavigationEvent.StateChanged) states += event.state
        }
        navigator.pause()
        navigator.resume()
        navigator.stop()

        assertTrue(states.isEmpty())
        assertEquals(1, executor.pendingCount)

        executor.runAll()

        assertEquals(
            listOf(
                EmbeddedNavigationState.PLANNING,
                EmbeddedNavigationState.NAVIGATING,
                EmbeddedNavigationState.PAUSED,
                EmbeddedNavigationState.NAVIGATING,
                EmbeddedNavigationState.STOPPED,
            ),
            states,
        )
    }

    @Test
    fun destroyDropsCallbacksAlreadyQueuedOnExecutor() {
        val executor = ManualExecutor()
        val navigator = DefaultEmbeddedNavigator(
            FakeMapController(),
            FakeLocationClient(),
            FakeRoutePlanner(immediate = true),
        )
        val events = mutableListOf<EmbeddedNavigationEvent>()

        navigator.start(request(executor)) { events += it }
        assertEquals(1, executor.pendingCount)

        navigator.destroy()
        executor.runAll()

        assertTrue(events.isEmpty())
    }

    @Test
    fun simulatedNavigationUsesRouteWithoutStartingLocation() {
        val scheduler = ManualSimulationScheduler()
        val location = FakeLocationClient()
        val events = mutableListOf<EmbeddedNavigationEvent>()
        val navigator = DefaultEmbeddedNavigator(
            mapController = FakeMapController(),
            locationClient = location,
            routePlanner = FakeRoutePlanner(immediate = true),
            simulationScheduler = scheduler,
            simulationExecutor = directExecutor,
        )

        val result = navigator.start(simulatedRequest()) { events += it }

        assertTrue(result is MapResult.Success)
        assertEquals(EmbeddedNavigationState.NAVIGATING, navigator.state)
        assertEquals(0, location.startCount)
        assertEquals(1, scheduler.pendingCount)
        val initial = events.filterIsInstance<EmbeddedNavigationEvent.Progress>().single().value
        assertEquals(origin, initial.location.position)
        assertEquals(EmbeddedNavigationMode.SIMULATED, initial.navigationMode)
    }

    @Test
    fun simulatedNavigationAdvancesOnTicksAndArrives() {
        val scheduler = ManualSimulationScheduler()
        val events = mutableListOf<EmbeddedNavigationEvent>()
        val navigator = DefaultEmbeddedNavigator(
            mapController = FakeMapController(),
            locationClient = FakeLocationClient(),
            routePlanner = FakeRoutePlanner(immediate = true),
            simulationScheduler = scheduler,
            simulationExecutor = directExecutor,
        )

        navigator.start(simulatedRequest(speedMetersPerSecond = 500f)) { events += it }
        val initial = events.filterIsInstance<EmbeddedNavigationEvent.Progress>().single().value

        scheduler.runNext()

        val advanced = events.filterIsInstance<EmbeddedNavigationEvent.Progress>().last().value
        assertTrue(advanced.distanceAlongRouteMeters > initial.distanceAlongRouteMeters)
        assertTrue(advanced.remainingDistanceMeters < initial.remainingDistanceMeters)

        repeat(10) {
            if (navigator.state == EmbeddedNavigationState.NAVIGATING) scheduler.runNext()
        }

        assertEquals(EmbeddedNavigationState.ARRIVED, navigator.state)
        assertEquals(0, scheduler.pendingCount)
        assertTrue(events.any { it is EmbeddedNavigationEvent.Arrived })
    }

    @Test
    fun simulatedNavigationPauseAndResumeKeepsProgressAndIgnoresLateTick() {
        val scheduler = ManualSimulationScheduler()
        val events = mutableListOf<EmbeddedNavigationEvent>()
        val navigator = DefaultEmbeddedNavigator(
            mapController = FakeMapController(),
            locationClient = FakeLocationClient(),
            routePlanner = FakeRoutePlanner(immediate = true),
            simulationScheduler = scheduler,
            simulationExecutor = directExecutor,
        )

        navigator.start(simulatedRequest(speedMetersPerSecond = 100f)) { events += it }
        scheduler.runNext()
        val beforePause = events.filterIsInstance<EmbeddedNavigationEvent.Progress>().last().value

        navigator.pause()
        val eventCountAtPause = events.size
        assertEquals(EmbeddedNavigationState.PAUSED, navigator.state)
        assertEquals(0, scheduler.pendingCount)

        scheduler.runNextEvenIfCancelled()
        assertEquals(eventCountAtPause, events.size)
        assertEquals(EmbeddedNavigationState.PAUSED, navigator.state)

        navigator.resume()
        assertEquals(1, scheduler.pendingCount)
        scheduler.runNext()

        val afterResume = events.filterIsInstance<EmbeddedNavigationEvent.Progress>().last().value
        assertTrue(afterResume.distanceAlongRouteMeters > beforePause.distanceAlongRouteMeters)
        assertEquals(EmbeddedNavigationMode.SIMULATED, afterResume.navigationMode)
    }

    @Test
    fun stopIgnoresLateSimulationTick() {
        val scheduler = ManualSimulationScheduler()
        val events = mutableListOf<EmbeddedNavigationEvent>()
        val navigator = DefaultEmbeddedNavigator(
            mapController = FakeMapController(),
            locationClient = FakeLocationClient(),
            routePlanner = FakeRoutePlanner(immediate = true),
            simulationScheduler = scheduler,
            simulationExecutor = directExecutor,
        )

        navigator.start(simulatedRequest()) { events += it }
        navigator.stop()
        val eventCountAtStop = events.size

        scheduler.runNextEvenIfCancelled()

        assertEquals(EmbeddedNavigationState.STOPPED, navigator.state)
        assertEquals(eventCountAtStop, events.size)
    }

    @Test
    fun destroyIgnoresLateSimulationTick() {
        val scheduler = ManualSimulationScheduler()
        val events = mutableListOf<EmbeddedNavigationEvent>()
        val navigator = DefaultEmbeddedNavigator(
            mapController = FakeMapController(),
            locationClient = FakeLocationClient(),
            routePlanner = FakeRoutePlanner(immediate = true),
            simulationScheduler = scheduler,
            simulationExecutor = directExecutor,
        )

        navigator.start(simulatedRequest()) { events += it }
        navigator.destroy()
        val eventCountAtDestroy = events.size

        scheduler.runNextEvenIfCancelled()

        assertEquals(EmbeddedNavigationState.DESTROYED, navigator.state)
        assertEquals(eventCountAtDestroy, events.size)
    }

    @Test
    fun invalidSimulationSettingsAreRejectedBeforePlanning() {
        val planner = FakeRoutePlanner(immediate = true)
        val navigator = DefaultEmbeddedNavigator(FakeMapController(), FakeLocationClient(), planner)

        val invalidSpeed = navigator.start(
            simulatedRequest(speedMetersPerSecond = Float.NaN),
        ) { }
        val invalidInterval = navigator.start(
            simulatedRequest(intervalMillis = 0L),
        ) { }

        assertTrue(invalidSpeed is MapResult.Failure)
        assertTrue(invalidInterval is MapResult.Failure)
        assertEquals(EmbeddedNavigationState.IDLE, navigator.state)
        assertTrue(planner.requests.isEmpty())
    }

    private fun request(callbackExecutor: Executor = directExecutor) = EmbeddedNavigationRequest(
        RouteRequest(
            mode = TravelMode.DRIVING,
            origin = origin,
            destination = destination,
        ),
        EmbeddedNavigationOptions(callbackExecutor = callbackExecutor),
    )

    private fun simulatedRequest(
        speedMetersPerSecond: Float = 100f,
        intervalMillis: Long = 1_000L,
    ) = EmbeddedNavigationRequest(
        routeRequest = RouteRequest(
            mode = TravelMode.DRIVING,
            origin = origin,
            destination = destination,
        ),
        options = EmbeddedNavigationOptions(
            callbackExecutor = directExecutor,
            navigationMode = EmbeddedNavigationMode.SIMULATED,
            simulationSpeedMetersPerSecond = speedMetersPerSecond,
            simulationIntervalMillis = intervalMillis,
        ),
    )

    private class ManualExecutor : Executor {
        private val commands = ArrayDeque<Runnable>()

        val pendingCount: Int get() = commands.size

        override fun execute(command: Runnable) {
            commands.addLast(command)
        }

        fun runAll() {
            while (commands.isNotEmpty()) commands.removeFirst().run()
        }
    }

    private class ManualSimulationScheduler : TimeoutScheduler {
        private val tasks = ArrayDeque<ScheduledTask>()

        val pendingCount: Int get() = tasks.count { !it.cancelled }

        override fun schedule(delayMillis: Long, task: Runnable): TimeoutHandle {
            require(delayMillis > 0L)
            val scheduled = ScheduledTask(task)
            tasks.addLast(scheduled)
            return TimeoutHandle {
                val changed = !scheduled.cancelled
                scheduled.cancelled = true
                changed
            }
        }

        fun runNext() {
            while (tasks.isNotEmpty()) {
                val scheduled = tasks.removeFirst()
                if (!scheduled.cancelled) {
                    scheduled.task.run()
                    return
                }
            }
            error("没有待执行的模拟导航任务")
        }

        fun runNextEvenIfCancelled() {
            check(tasks.isNotEmpty()) { "没有可模拟迟到回调的任务" }
            tasks.removeFirst().task.run()
        }

        private data class ScheduledTask(
            val task: Runnable,
            var cancelled: Boolean = false,
        )
    }

    private fun LatLng.convertTo(target: CoordType): LatLng =
        (DefaultCoordinateConverter.convert(this, target) as MapResult.Success).data

    private inner class FakeRoutePlanner(
        private val immediate: Boolean,
        private val pathPoints: (RouteRequest) -> List<LatLng> = {
            listOf(it.origin, it.destination)
        },
    ) : RoutePlanner {
        val requests = mutableListOf<RouteRequest>()
        private val calls = mutableListOf<RouteCall>()
        val handles: List<TestRequestHandle> get() = calls.map(RouteCall::handle)

        override fun plan(
            request: RouteRequest,
            asyncOptions: com.mapfusion.api.model.AsyncCallOptions,
            callback: MapCallback<RouteResult>,
        ): com.mapfusion.api.model.RequestHandle {
            requests += request
            val handle = TestRequestHandle.active(
                onCancel = {
                    callback.onResult(MapResult.Failure(MapError(ErrorType.CANCELLED, "规划已取消")))
                },
            )
            calls += RouteCall(request, callback, handle)
            if (immediate) completeLatest()
            return handle
        }

        fun completeLatest() {
            val call = calls.last()
            call.handle.complete()
            // 即使句柄已取消也继续回调，用于模拟无法真正取消的厂商请求。
            val points = pathPoints(call.request)
            call.callback.onResult(
                MapResult.Success(
                    RouteResult(
                        mode = call.request.mode,
                        paths = listOf(
                            RoutePath(
                                distanceMeters = 1_400,
                                durationSeconds = 600,
                                steps = listOf(
                                    RouteStep(
                                        "直行",
                                        1_400,
                                        600,
                                        points,
                                    ),
                                ),
                                polyline = points,
                            ),
                        ),
                    ),
                ),
            )
        }

        override fun destroy() = Unit
    }

    private data class RouteCall(
        val request: RouteRequest,
        val callback: MapCallback<RouteResult>,
        val handle: TestRequestHandle,
    )

    private class FakeLocationClient : LocationClient {
        private val subscriptions = mutableListOf<LocationSubscription>()
        var startCount = 0
        var stopCount = 0
        val handles: List<TestRequestHandle> get() = subscriptions.map(LocationSubscription::handle)

        override fun requestSingleLocation(
            options: LocationOptions,
            asyncOptions: com.mapfusion.api.model.AsyncCallOptions,
            callback: MapCallback<MapLocation>,
        ): com.mapfusion.api.model.RequestHandle = TestRequestHandle()

        override fun startContinuousLocation(
            options: LocationOptions,
            asyncOptions: com.mapfusion.api.model.AsyncCallOptions,
            callback: MapCallback<MapLocation>,
        ): com.mapfusion.api.model.RequestHandle {
            startCount++
            val handle = TestRequestHandle.active(
                onCancel = {
                    callback.onResult(MapResult.Failure(MapError(ErrorType.CANCELLED, "定位已取消")))
                },
            )
            subscriptions += LocationSubscription(callback, handle)
            return handle
        }

        override fun stopContinuousLocation() {
            stopCount++
        }

        override fun destroy() = Unit

        fun success(location: MapLocation) {
            subscriptions.lastOrNull()?.callback?.onResult(MapResult.Success(location))
        }

        private data class LocationSubscription(
            val callback: MapCallback<MapLocation>,
            val handle: TestRequestHandle,
        )
    }

    private class FakeMapController : MapController {
        val markers = mutableListOf<FakeMarker>()
        val polylines = mutableListOf<FakePolyline>()
        var lastCamera: CameraUpdate? = null
        var failOnMarker = false

        override val view: View get() = error("view is not used in this test")
        override fun onCreate(savedState: Bundle?) = Unit
        override fun onResume() = Unit
        override fun onPause() = Unit
        override fun onDestroy() = Unit
        override fun onSaveInstanceState(outState: Bundle) = Unit
        override fun onLowMemory() = Unit
        override fun moveCamera(update: CameraUpdate) {
            lastCamera = update
        }
        override fun getCameraPosition(): CameraPosition = error("camera is not used in this test")
        override fun setCameraBounds(bounds: LatLngBounds?) = Unit
        override fun setZoomRange(minZoom: Float, maxZoom: Float) = Unit
        override fun addMarker(options: MarkerOptions): MapMarker {
            if (failOnMarker) error("marker creation failed")
            return FakeMarker(options).also(markers::add)
        }
        override fun addPolyline(options: PolylineOptions): MapPolyline = FakePolyline(options).also(polylines::add)
        override fun addPolygon(options: PolygonOptions): MapPolygon = error("overlay is not used in this test")
        override fun addCircle(options: CircleOptions): MapCircle = error("overlay is not used in this test")
        override fun addGroundOverlay(options: GroundOverlayOptions): MapGroundOverlay =
            error("overlay is not used in this test")
        override fun addText(options: TextOverlayOptions): MapTextOverlay = error("overlay is not used in this test")
        override fun addTileOverlay(options: TileOverlayOptions): MapTileOverlay =
            error("overlay is not used in this test")
        override fun addHeatMap(options: HeatMapOptions): MapHeatMap = error("overlay is not used in this test")
        override fun clearMarkers() = Unit
        override fun clearOverlays() = Unit
        override fun setMyLocationEnabled(enabled: Boolean) = Unit
        override fun setTrafficEnabled(enabled: Boolean) = Unit
        override fun setZoomControlsEnabled(enabled: Boolean) = Unit
        override fun setBuildingsEnabled(enabled: Boolean) = Unit
        override fun setIndoorEnabled(enabled: Boolean) = Unit
        override fun setMapPoiEnabled(enabled: Boolean) = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun setMapType(type: MapType) = Unit
        override fun getMapType(): MapType = MapType.NORMAL
        override fun setUiOptions(options: MapUiOptions) = Unit
        override fun snapshot(
            asyncOptions: com.mapfusion.api.model.AsyncCallOptions,
            callback: MapCallback<MapSnapshot>,
        ): com.mapfusion.api.model.RequestHandle = TestRequestHandle()
        override fun setOnMapClickListener(listener: ((LatLng) -> Unit)?) = Unit
        override fun setOnMapLongClickListener(listener: ((LatLng) -> Unit)?) = Unit
        override fun setOnMarkerClickListener(listener: ((MapMarker) -> Boolean)?) = Unit
        override fun setOnOverlayClickListener(listener: ((MapOverlay) -> Boolean)?) = Unit
        override fun setOnCameraIdleListener(listener: ((CameraPosition) -> Unit)?) = Unit
        override fun setOnMapLoadedListener(listener: (() -> Unit)?) = Unit
    }

    private class FakeMarker(options: MarkerOptions) : MapMarker {
        override val id: String = "marker-${options.position}"
        override var position = options.position
        override var title = options.title
        override var snippet = options.snippet
        override var rotation = options.rotation
        override var alpha = options.alpha
        override var flat = options.flat
        override var visible = options.visible
        override var zIndex = options.zIndex
        override var tag = options.tag
        override var isRemoved = false
            private set
        val removed: Boolean get() = isRemoved
        override fun remove() {
            isRemoved = true
        }
        override fun rawOverlay(): Any = this
        override fun showInfoWindow() = Unit
        override fun hideInfoWindow() = Unit
    }

    private class FakePolyline(options: PolylineOptions) : MapPolyline {
        override val id: String = "route"
        override var points = options.points
        override var width = options.width
        override var color = options.color
        override var visible = options.visible
        override var zIndex = options.zIndex
        override var tag = options.tag
        var removed = false
        override fun remove() {
            removed = true
        }
        override fun rawOverlay(): Any = this
    }
}
