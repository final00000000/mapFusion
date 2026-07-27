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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapSingleLocationRequestTest {

    private val direct = Executor(Runnable::run)

    @Test
    fun locationTimeoutCapsRequestAndLateNativeResultIsDropped() {
        val scheduler = RecordingTimeoutScheduler()
        val released = AtomicInteger()
        val results = mutableListOf<MapResult<Int>>()
        val request = AsyncRuntime(direct, scheduler).createAmapSingleLocationRequest(
            locationTimeoutMillis = 25_000,
            callback = results::add,
            options = AsyncCallOptions(timeoutMillis = 60_000, callbackExecutor = direct),
            terminalAction = Runnable { released.incrementAndGet() },
        )

        assertEquals(25_000L, scheduler.delayMillis)
        scheduler.fire()

        assertTrue(request.isDone)
        assertEquals(1, released.get())
        assertEquals(ErrorType.TIMEOUT, (results.single() as MapResult.Failure).error.type)
        assertFalse(request.success(1))
        assertEquals(1, results.size)
    }

    @Test
    fun shorterCallTimeoutRemainsEffective() {
        val scheduler = RecordingTimeoutScheduler()
        val request = AsyncRuntime(direct, scheduler).createAmapSingleLocationRequest<Int>(
            locationTimeoutMillis = 25_000,
            callback = {},
            options = AsyncCallOptions(timeoutMillis = 3_000, callbackExecutor = direct),
        )

        assertEquals(3_000L, scheduler.delayMillis)
        request.dispose()
    }

    @Test
    fun cancelIsIdempotentAndWinsAgainstTimeout() {
        val scheduler = RecordingTimeoutScheduler()
        val released = AtomicInteger()
        val results = mutableListOf<MapResult<Int>>()
        val request = AsyncRuntime(direct, scheduler).createAmapSingleLocationRequest(
            locationTimeoutMillis = 5_000,
            callback = results::add,
            options = AsyncCallOptions(callbackExecutor = direct),
            terminalAction = Runnable { released.incrementAndGet() },
        )

        assertTrue(request.cancel())
        assertFalse(request.cancel())
        scheduler.fire(includeCancelled = true)

        assertTrue(request.isDone)
        assertTrue(request.isCancelled)
        assertEquals(1, released.get())
        assertEquals(ErrorType.CANCELLED, (results.single() as MapResult.Failure).error.type)
        assertFalse(request.failure(com.mapfusion.api.model.MapError(ErrorType.NETWORK, "late")))
        assertEquals(1, results.size)
    }

    @Test
    fun disposeIsSilentIdempotentAndSuppressesLateResults() {
        val scheduler = RecordingTimeoutScheduler()
        val released = AtomicInteger()
        val results = mutableListOf<MapResult<Int>>()
        val request = AsyncRuntime(direct, scheduler).createAmapSingleLocationRequest(
            locationTimeoutMillis = 5_000,
            callback = results::add,
            options = AsyncCallOptions(callbackExecutor = direct),
            terminalAction = Runnable { released.incrementAndGet() },
        )

        assertTrue(request.dispose())
        assertFalse(request.dispose())
        scheduler.fire(includeCancelled = true)

        assertTrue(request.isDone)
        assertFalse(request.isCancelled)
        assertEquals(1, released.get())
        assertTrue(results.isEmpty())
        assertFalse(request.success(1))
    }

    @Test
    fun nonPositiveLocationTimeoutIsRejectedBeforeScheduling() {
        val scheduler = RecordingTimeoutScheduler()

        assertThrows(IllegalArgumentException::class.java) {
            AsyncRuntime(direct, scheduler).createAmapSingleLocationRequest<Int>(
                locationTimeoutMillis = 0,
                callback = {},
                options = AsyncCallOptions(callbackExecutor = direct),
            )
        }
        assertEquals(null, scheduler.delayMillis)
    }
}

private class RecordingTimeoutScheduler : TimeoutScheduler {
    private var task: Runnable? = null
    private var cancelled = false
    var delayMillis: Long? = null
        private set

    override fun schedule(delayMillis: Long, task: Runnable): TimeoutHandle {
        this.delayMillis = delayMillis
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
