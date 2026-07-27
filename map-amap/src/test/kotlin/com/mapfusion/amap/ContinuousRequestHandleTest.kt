package com.mapfusion.amap

import com.mapfusion.api.async.AsyncRuntime
import com.mapfusion.api.async.TimeoutHandle
import com.mapfusion.api.async.TimeoutScheduler
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapResult
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousRequestHandleTest {

    private val direct = Executor(Runnable::run)

    @Test
    fun immediateTimeoutDuringConstruction_completesAndReleasesOnce() {
        val scheduler = ImmediateTimeoutScheduler()
        val released = AtomicInteger()
        val results = mutableListOf<MapResult<Int>>()

        val handle = ContinuousRequestHandle(
            callback = results::add,
            options = AsyncCallOptions(timeoutMillis = 1, callbackExecutor = direct),
            runtime = AsyncRuntime(direct, scheduler),
            releaseAction = { released.incrementAndGet() },
        )

        assertTrue(handle.isDone)
        assertFalse(handle.isCancelled)
        assertEquals(1, released.get())
        assertEquals(ErrorType.TIMEOUT, (results.single() as MapResult.Failure).error.type)
        assertFalse(handle.emit(MapResult.Success(1)))
        assertFalse(handle.cancel())
    }

    @Test
    fun firstSuccessEndsOnlyTimer_andSubscriptionKeepsDelivering() {
        val scheduler = ManualTimeoutScheduler()
        val released = AtomicInteger()
        val results = mutableListOf<MapResult<Int>>()
        val handle = ContinuousRequestHandle(
            callback = results::add,
            options = AsyncCallOptions(callbackExecutor = direct),
            runtime = AsyncRuntime(direct, scheduler),
            releaseAction = { released.incrementAndGet() },
        )

        assertTrue(handle.emit(MapResult.Success(1)))
        scheduler.fire(includeCancelled = true)
        assertTrue(handle.emit(MapResult.Success(2)))

        assertFalse(handle.isDone)
        assertEquals(listOf(1, 2), results.map { (it as MapResult.Success).data })
        assertEquals(0, released.get())

        assertTrue(handle.cancel())
        assertTrue(handle.isDone)
        assertTrue(handle.isCancelled)
        assertEquals(1, released.get())
        assertEquals(ErrorType.CANCELLED, (results.last() as MapResult.Failure).error.type)
        assertFalse(handle.cancel())
    }

    @Test
    fun firstNativeFailureEndsOnlyTimer_andSubscriptionCanRecover() {
        val scheduler = ManualTimeoutScheduler()
        val released = AtomicInteger()
        val results = mutableListOf<MapResult<Int>>()
        val handle = ContinuousRequestHandle(
            callback = results::add,
            options = AsyncCallOptions(callbackExecutor = direct),
            runtime = AsyncRuntime(direct, scheduler),
            releaseAction = { released.incrementAndGet() },
        )

        assertTrue(handle.emit(MapResult.Failure(com.mapfusion.api.model.MapError(ErrorType.NETWORK, "offline"))))
        scheduler.fire(includeCancelled = true)
        assertTrue(handle.emit(MapResult.Success(2)))

        assertFalse(handle.isDone)
        assertEquals(ErrorType.NETWORK, (results.first() as MapResult.Failure).error.type)
        assertEquals(2, (results.last() as MapResult.Success).data)
        assertEquals(0, released.get())
    }

    @Test
    fun disposeIsSilentAndDropsLateNativeAndTimeoutResults() {
        val scheduler = ManualTimeoutScheduler()
        val released = AtomicInteger()
        val results = mutableListOf<MapResult<Int>>()
        val handle = ContinuousRequestHandle(
            callback = results::add,
            options = AsyncCallOptions(callbackExecutor = direct),
            runtime = AsyncRuntime(direct, scheduler),
            releaseAction = { released.incrementAndGet() },
        )

        assertTrue(handle.dispose())
        scheduler.fire(includeCancelled = true)

        assertTrue(handle.isDone)
        assertFalse(handle.isCancelled)
        assertEquals(1, released.get())
        assertTrue(results.isEmpty())
        assertFalse(handle.emit(MapResult.Success(7)))
        assertFalse(handle.dispose())
    }

    @Test
    fun failureBeforeFirstResultEndsSubscriptionExactlyOnce() {
        val scheduler = ManualTimeoutScheduler()
        val released = AtomicInteger()
        val results = mutableListOf<MapResult<Int>>()
        val handle = ContinuousRequestHandle(
            callback = results::add,
            options = AsyncCallOptions(callbackExecutor = direct),
            runtime = AsyncRuntime(direct, scheduler),
            releaseAction = { released.incrementAndGet() },
        )

        assertTrue(handle.fail(com.mapfusion.api.model.MapError(ErrorType.PERMISSION, "denied")))
        scheduler.fire(includeCancelled = true)

        assertTrue(handle.isDone)
        assertEquals(1, released.get())
        assertEquals(ErrorType.PERMISSION, (results.single() as MapResult.Failure).error.type)
    }
}

private class ImmediateTimeoutScheduler : TimeoutScheduler {
    override fun schedule(delayMillis: Long, task: Runnable): TimeoutHandle {
        task.run()
        return TimeoutHandle { true }
    }
}

private class ManualTimeoutScheduler : TimeoutScheduler {
    private var task: Runnable? = null
    private var cancelled = false

    override fun schedule(delayMillis: Long, task: Runnable): TimeoutHandle {
        this.task = task
        return TimeoutHandle {
            val changed = !cancelled
            cancelled = true
            changed
        }
    }

    fun fire(includeCancelled: Boolean = false) {
        if (includeCancelled || !cancelled) task?.run()
    }
}
