package com.mapfusion.baidu

import com.mapfusion.api.async.AsyncRuntime
import com.mapfusion.api.async.TimeoutHandle
import com.mapfusion.api.async.TimeoutScheduler
import com.mapfusion.api.model.MapResult
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRequestRegistryTest {

    @Test
    fun completeReleasesRequestOnlyOnce() {
        val released = mutableListOf<Any>()
        val registry = NativeRequestRegistry<Any>(released::add)
        val request = Any()

        assertTrue(registry.register(request))
        assertTrue(registry.complete(request))
        assertFalse(registry.complete(request))
        assertEquals(listOf(request), released)
    }

    @Test
    fun destroyIsIdempotentAndRejectsNewRequests() {
        val released = mutableListOf<Any>()
        val registry = NativeRequestRegistry<Any>(released::add)
        val first = Any()
        val second = Any()

        assertTrue(registry.register(first))
        assertTrue(registry.register(second))
        registry.destroy()
        registry.destroy()

        assertEquals(setOf(first, second), released.toSet())
        assertFalse(registry.register(Any()))
        assertFalse(registry.complete(first))
    }

    @Test
    fun searchRequestCompletionCancellationAndDestroyRace_releasesNativeExactlyOnce() {
        val workers = Executors.newFixedThreadPool(3)
        try {
            repeat(100) {
                val releases = AtomicInteger()
                val callbacks = ConcurrentLinkedQueue<MapResult<String>>()
                val registry = NativeRequestRegistry<Any> { releases.incrementAndGet() }
                val nativeSearch = Any()
                val runtime = AsyncRuntime(Executor(Runnable::run), NeverTimeoutScheduler)
                val request = requireNotNull(
                    registry.trackedRequest(
                        native = nativeSearch,
                        runtime = runtime,
                        options = com.mapfusion.api.model.AsyncCallOptions(),
                        callback = { callbacks += it },
                    ),
                )
                val ready = CountDownLatch(3)
                val start = CountDownLatch(1)
                val done = CountDownLatch(3)

                listOf(
                    Runnable {
                        ready.countDown()
                        start.await()
                        request.success("poi")
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
                        registry.destroy()
                        done.countDown()
                    },
                ).forEach(workers::execute)

                assertTrue(ready.await(2, TimeUnit.SECONDS))
                start.countDown()
                assertTrue(done.await(2, TimeUnit.SECONDS))
                assertTrue(request.isDone)
                assertTrue(callbacks.size <= 1)
                assertEquals(1, releases.get())
                assertFalse(request.success("late"))
            }
        } finally {
            workers.shutdownNow()
        }
    }
}

private object NeverTimeoutScheduler : TimeoutScheduler {
    override fun schedule(delayMillis: Long, task: Runnable): TimeoutHandle = TimeoutHandle { true }
}
