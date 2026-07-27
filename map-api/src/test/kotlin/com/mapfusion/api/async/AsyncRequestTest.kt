package com.mapfusion.api.async

import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapResult
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncRequestTest {

    @Test
    fun complete_isExactlyOnceAndReleasesResourcesBeforeDispatch() {
        val scheduler = ManualTimeoutScheduler()
        val callbacks = QueuedExecutor()
        val releases = AtomicInteger()
        val results = mutableListOf<MapResult<String>>()
        val request = AsyncRuntime(callbacks, scheduler).createRequest<String>(
            callback = { result -> results += result },
            terminalAction = Runnable { releases.incrementAndGet() },
        )

        assertTrue(request.success("ok"))
        assertFalse(request.success("late"))
        assertFalse(request.cancel())
        assertTrue(request.isDone)
        assertFalse(request.isCancelled)
        assertEquals(1, releases.get())
        assertEquals(0, results.size)
        assertTrue(scheduler.onlyTaskIsCancelled())

        callbacks.runAll()

        assertEquals(listOf(MapResult.Success("ok")), results)
    }

    @Test
    fun cancel_isIdempotentAndUsesConfiguredExecutor() {
        val scheduler = ManualTimeoutScheduler()
        val defaultExecutor = QueuedExecutor()
        val customExecutor = QueuedExecutor()
        var result: MapResult<String>? = null
        val request = AsyncRuntime(defaultExecutor, scheduler).createRequest<String>(
            callback = { result = it },
            options = AsyncCallOptions(callbackExecutor = customExecutor),
        )

        assertTrue(request.cancel())
        assertFalse(request.cancel())
        assertTrue(request.isDone)
        assertTrue(request.isCancelled)
        assertEquals(0, defaultExecutor.size)
        assertEquals(1, customExecutor.size)
        assertNull(result)

        customExecutor.runAll()

        val failure = result as MapResult.Failure
        assertEquals(ErrorType.CANCELLED, failure.error.type)
    }

    @Test
    fun timeout_releasesResourcesAndDropsLateNativeResult() {
        val scheduler = ManualTimeoutScheduler()
        val releases = AtomicInteger()
        val results = mutableListOf<MapResult<Int>>()
        val request = AsyncRuntime(Executor(Runnable::run), scheduler).createRequest<Int>(
            callback = { results += it },
            options = AsyncCallOptions(timeoutMillis = 25),
            terminalAction = Runnable { releases.incrementAndGet() },
        )

        scheduler.fireOnlyTask()

        assertTrue(request.isDone)
        assertFalse(request.isCancelled)
        assertEquals(1, releases.get())
        assertFalse(request.success(42))
        assertEquals(1, results.size)
        assertEquals(ErrorType.TIMEOUT, (results.single() as MapResult.Failure).error.type)
    }

    @Test
    fun dispose_isSilentAndIdempotent() {
        val scheduler = ManualTimeoutScheduler()
        val releases = AtomicInteger()
        val results = mutableListOf<MapResult<Unit>>()
        val request = AsyncRuntime(Executor(Runnable::run), scheduler).createRequest<Unit>(
            callback = { results += it },
            terminalAction = Runnable { releases.incrementAndGet() },
        )

        assertTrue(request.dispose())
        assertFalse(request.dispose())
        assertFalse(request.cancel())
        scheduler.fireOnlyTask(includeCancelled = true)

        assertTrue(request.isDone)
        assertFalse(request.isCancelled)
        assertEquals(1, releases.get())
        assertTrue(results.isEmpty())
    }

    @Test
    fun completionCancellationAndTimeoutRace_hasOneTerminalWinner() {
        val workers = Executors.newFixedThreadPool(3)
        try {
            repeat(100) {
                val scheduler = ManualTimeoutScheduler()
                val results = ConcurrentLinkedQueue<MapResult<Int>>()
                val releases = AtomicInteger()
                val request = AsyncRuntime(Executor(Runnable::run), scheduler).createRequest<Int>(
                    callback = { results += it },
                    terminalAction = Runnable { releases.incrementAndGet() },
                )
                val ready = CountDownLatch(3)
                val start = CountDownLatch(1)
                val done = CountDownLatch(3)

                listOf(
                    Runnable {
                        ready.countDown()
                        start.await()
                        request.success(7)
                        done.countDown()
                    },
                    Runnable {
                        ready.countDown()
                        start.await()
                        request.cancel()
                        done.countDown()
                    },
                    Runnable {
                        ready.countDown()
                        start.await()
                        scheduler.fireOnlyTask(includeCancelled = true)
                        done.countDown()
                    },
                ).forEach(workers::execute)

                assertTrue(ready.await(2, TimeUnit.SECONDS))
                start.countDown()
                assertTrue(done.await(2, TimeUnit.SECONDS))

                assertTrue(request.isDone)
                assertEquals(1, releases.get())
                assertEquals(1, results.size)
                assertFalse(request.success(8))
                assertFalse(request.cancel())
            }
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun callbackExecutorFailure_doesNotReopenOrRepeatRequest() {
        val scheduler = ManualTimeoutScheduler()
        val rejection = IllegalStateException("executor rejected callback")
        val request = AsyncRuntime(Executor { throw rejection }, scheduler).createRequest<String>(
            callback = { error("不应执行") },
        )

        val actual = assertThrows(IllegalStateException::class.java) {
            request.success("ok")
        }

        assertEquals(rejection, actual)
        assertTrue(request.isDone)
        assertFalse(request.cancel())
        assertFalse(request.failure(com.mapfusion.api.model.MapError(ErrorType.UNKNOWN, "late")))
    }

    @Test
    fun options_rejectNonPositiveTimeout() {
        assertThrows(IllegalArgumentException::class.java) {
            AsyncCallOptions(timeoutMillis = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AsyncCallOptions(timeoutMillis = -1)
        }
    }
}

private class QueuedExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()

    val size: Int
        get() = tasks.size

    override fun execute(command: Runnable) {
        tasks.addLast(command)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}

private class ManualTimeoutScheduler : TimeoutScheduler {
    private val tasks = mutableListOf<ManualTask>()

    override fun schedule(delayMillis: Long, task: Runnable): TimeoutHandle {
        require(delayMillis > 0)
        return ManualTask(task).also(tasks::add)
    }

    fun onlyTaskIsCancelled(): Boolean {
        assertEquals(1, tasks.size)
        return tasks.single().cancelled.get()
    }

    fun fireOnlyTask(includeCancelled: Boolean = false) {
        assertEquals(1, tasks.size)
        tasks.single().run(includeCancelled)
    }
}

private class ManualTask(
    private val action: Runnable,
) : TimeoutHandle {
    val cancelled = AtomicBoolean(false)
    private val invoked = AtomicBoolean(false)

    override fun cancel(): Boolean = !invoked.get() && cancelled.compareAndSet(false, true)

    fun run(includeCancelled: Boolean) {
        if (!invoked.compareAndSet(false, true)) return
        if (includeCancelled || !cancelled.get()) action.run()
    }
}
