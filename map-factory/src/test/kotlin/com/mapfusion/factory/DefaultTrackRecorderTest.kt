package com.mapfusion.factory

import com.mapfusion.api.capability.LocationClient
import com.mapfusion.api.capability.TrackListener
import com.mapfusion.api.capability.TrackState
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.LocationOptions
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapLocation
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.TrackOptions
import com.mapfusion.api.model.TrackSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class DefaultTrackRecorderTest {

    private val directExecutor = Executor { command -> command.run() }

    @Test
    fun records_filters_pauses_and_resumes() {
        val locationClient = FakeLocationClient()
        var now = 1_000L
        val recorder = DefaultTrackRecorder(locationClient, null) { now }
        val errors = mutableListOf<MapError>()
        recorder.start(
            TrackOptions(
                minPointDistanceMeters = 10.0,
                maxAccuracyMeters = 50f,
                drawOnMap = false,
                callbackExecutor = directExecutor,
            ),
            object : TrackListener {
                override fun onError(error: MapError) {
                    errors += error
                }
            },
        )

        locationClient.success(location(39.0, 116.0, accuracy = 120f))
        locationClient.success(location(39.0, 116.0))
        locationClient.success(location(39.00001, 116.0))
        locationClient.success(location(39.00020, 116.0))

        var snapshot = recorder.snapshot()
        assertEquals(TrackState.RECORDING, snapshot.state)
        assertEquals(2, snapshot.points.size)
        assertEquals(2, snapshot.rejectedPointCount)
        assertTrue(snapshot.distanceMeters in 20.0..23.0)
        assertTrue(errors.isEmpty())

        now = 4_000L
        recorder.pause()
        locationClient.success(location(39.00100, 116.0))
        snapshot = recorder.snapshot()
        assertEquals(TrackState.PAUSED, snapshot.state)
        assertEquals(2, snapshot.points.size)
        assertEquals(3_000L, snapshot.elapsedTimeMillis)

        now = 10_000L
        recorder.resume()
        locationClient.success(location(39.00040, 116.0))
        now = 12_000L
        snapshot = recorder.stop()
        assertEquals(TrackState.STOPPED, snapshot.state)
        assertEquals(3, snapshot.points.size)
        assertEquals(5_000L, snapshot.elapsedTimeMillis)
        assertTrue(locationClient.startCount == 2)
        assertEquals(0, locationClient.stopCount)
        assertTrue(locationClient.handles.all { it.isCancelled })
    }

    @Test
    fun location_error_stops_recording_and_is_forwarded() {
        val locationClient = FakeLocationClient()
        var now = 2_000L
        val recorder = DefaultTrackRecorder(locationClient, null) { now }
        val errors = mutableListOf<MapError>()
        recorder.start(
            TrackOptions(drawOnMap = false, callbackExecutor = directExecutor),
            object : TrackListener {
                override fun onError(error: MapError) {
                    errors += error
                }
            },
        )

        now = 3_500L
        locationClient.failure(MapError(ErrorType.NETWORK, "定位网络异常"))

        val snapshot = recorder.snapshot()
        assertEquals(TrackState.STOPPED, snapshot.state)
        assertEquals(1_500L, snapshot.elapsedTimeMillis)
        assertEquals(ErrorType.NETWORK, errors.single().type)
    }

    @Test
    fun clear_resets_session_and_destroy_releases_client() {
        val locationClient = FakeLocationClient()
        val recorder = DefaultTrackRecorder(locationClient, null)
        recorder.start(TrackOptions(drawOnMap = false, callbackExecutor = directExecutor))
        locationClient.success(location(39.0, 116.0))

        recorder.clear()
        assertEquals(TrackState.IDLE, recorder.state)
        assertTrue(recorder.snapshot().points.isEmpty())
        assertTrue(locationClient.handles.single().isCancelled)
        assertEquals(0, locationClient.stopCount)

        recorder.destroy()
        assertTrue(locationClient.destroyed)
    }

    @Test
    fun destroy_attempts_client_cleanup_when_request_cancel_fails() {
        val locationClient = FakeLocationClient(cancelFailure = true)
        val recorder = DefaultTrackRecorder(locationClient, null)
        recorder.start(TrackOptions(drawOnMap = false, callbackExecutor = directExecutor))

        val failure = runCatching { recorder.destroy() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(locationClient.destroyed)
        assertEquals(TrackState.STOPPED, recorder.state)
    }

    @Test
    fun destroyCancelsOwnedSubscriptionAndIgnoresLateCallback() {
        val locationClient = FakeLocationClient()
        val recorder = DefaultTrackRecorder(locationClient, null)
        var addedPoints = 0
        val errors = mutableListOf<MapError>()
        recorder.start(
            TrackOptions(drawOnMap = false, callbackExecutor = directExecutor),
            object : TrackListener {
                override fun onPointAdded(snapshot: com.mapfusion.api.model.TrackSnapshot) {
                    addedPoints++
                }

                override fun onError(error: MapError) {
                    errors += error
                }
            },
        )

        recorder.destroy()
        locationClient.successFrom(0, location(39.0, 116.0))

        assertTrue(locationClient.handles.single().isCancelled)
        assertEquals(0, locationClient.stopCount)
        assertEquals(0, addedPoints)
        assertTrue(errors.isEmpty())
        assertTrue(recorder.snapshot().points.isEmpty())
    }

    @Test
    fun invalid_options_do_not_start_location() {
        val locationClient = FakeLocationClient()
        val recorder = DefaultTrackRecorder(locationClient, null)
        val errors = mutableListOf<MapError>()

        recorder.start(
            TrackOptions(minPointDistanceMeters = -1.0, callbackExecutor = directExecutor),
            object : TrackListener {
                override fun onError(error: MapError) {
                    errors += error
                }
            },
        )

        assertEquals(TrackState.IDLE, recorder.state)
        assertEquals(0, locationClient.startCount)
        assertEquals(ErrorType.INVALID_PARAM, errors.single().type)
    }

    @Test
    fun controlMethodsQueueCallbacksAndPreserveSessionOrder() {
        val executor = ManualExecutor()
        val recorder = DefaultTrackRecorder(FakeLocationClient(), null)
        val states = mutableListOf<TrackState>()

        recorder.start(
            TrackOptions(drawOnMap = false, callbackExecutor = executor),
            object : TrackListener {
                override fun onStateChanged(snapshot: TrackSnapshot) {
                    states += snapshot.state
                }
            },
        )
        recorder.pause()
        recorder.resume()
        recorder.stop()

        assertTrue(states.isEmpty())
        assertEquals(1, executor.pendingCount)

        executor.runAll()

        assertEquals(
            listOf(TrackState.RECORDING, TrackState.PAUSED, TrackState.RECORDING, TrackState.STOPPED),
            states,
        )
    }

    @Test
    fun destroyDropsCallbacksAlreadyQueuedOnExecutor() {
        val executor = ManualExecutor()
        val locationClient = FakeLocationClient()
        val recorder = DefaultTrackRecorder(locationClient, null)
        val states = mutableListOf<TrackState>()
        var addedPoints = 0

        recorder.start(
            TrackOptions(drawOnMap = false, callbackExecutor = executor),
            object : TrackListener {
                override fun onStateChanged(snapshot: TrackSnapshot) {
                    states += snapshot.state
                }

                override fun onPointAdded(snapshot: TrackSnapshot) {
                    addedPoints++
                }
            },
        )
        locationClient.success(location(39.0, 116.0))
        assertEquals(1, executor.pendingCount)

        recorder.destroy()
        executor.runAll()

        assertTrue(states.isEmpty())
        assertEquals(0, addedPoints)
    }

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

    private fun location(latitude: Double, longitude: Double, accuracy: Float = 5f) = MapLocation(
        position = LatLng(latitude, longitude, CoordType.GCJ02),
        accuracy = accuracy,
        time = 1L,
    )

    private class FakeLocationClient(
        private val cancelFailure: Boolean = false,
    ) : LocationClient {
        private val subscriptions = mutableListOf<LocationSubscription>()
        var startCount = 0
        var stopCount = 0
        var destroyed = false
        val handles: List<TestRequestHandle> get() = subscriptions.map(LocationSubscription::handle)

        override fun requestSingleLocation(
            options: LocationOptions,
            asyncOptions: com.mapfusion.api.model.AsyncCallOptions,
            callback: MapCallback<MapLocation>,
        ): com.mapfusion.api.model.RequestHandle {
            return TestRequestHandle()
        }

        override fun startContinuousLocation(
            options: LocationOptions,
            asyncOptions: com.mapfusion.api.model.AsyncCallOptions,
            callback: MapCallback<MapLocation>,
        ): com.mapfusion.api.model.RequestHandle {
            startCount++
            val failure = if (cancelFailure) IllegalStateException("cancel failed") else null
            val handle = TestRequestHandle.active(
                cancelFailure = failure,
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

        override fun destroy() {
            destroyed = true
        }

        fun success(location: MapLocation) {
            subscriptions.lastOrNull()?.callback?.onResult(MapResult.Success(location))
        }

        fun successFrom(index: Int, location: MapLocation) {
            subscriptions[index].callback.onResult(MapResult.Success(location))
        }

        fun failure(error: MapError) {
            subscriptions.lastOrNull()?.callback?.onResult(MapResult.Failure(error))
        }

        private data class LocationSubscription(
            val callback: MapCallback<MapLocation>,
            val handle: TestRequestHandle,
        )
    }
}
