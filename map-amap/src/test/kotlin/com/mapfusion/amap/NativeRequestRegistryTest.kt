package com.mapfusion.amap

import com.mapfusion.api.async.AsyncRuntime
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapResult
import java.util.concurrent.Executor
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
        assertFalse(registry.register(request))
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
    fun asyncCancellationReleasesNativeAndRejectsLateResult() {
        val released = mutableListOf<Any>()
        val results = mutableListOf<MapResult<String>>()
        val registry = NativeRequestRegistry<Any>(released::add)
        val native = Any()
        val runtime = AsyncRuntime(Executor(Runnable::run))
        val request = runtime.createRequest(
            callback = results::add,
            options = AsyncCallOptions(callbackExecutor = Executor(Runnable::run)),
            terminalAction = Runnable { registry.release(native) },
        )

        assertTrue(registry.register(native, request))
        assertTrue(request.cancel())
        assertEquals(listOf(native), released)
        assertEquals(ErrorType.CANCELLED, (results.single() as MapResult.Failure).error.type)
        assertFalse(registry.complete(native, MapResult.Success("late")))
        assertFalse(request.cancel())
    }

    @Test
    fun destroyDisposesAsyncRequestWithoutCallback() {
        val released = mutableListOf<Any>()
        val results = mutableListOf<MapResult<String>>()
        val registry = NativeRequestRegistry<Any>(released::add)
        val native = Any()
        val request = AsyncRuntime(Executor(Runnable::run)).createRequest(
            callback = results::add,
            terminalAction = Runnable { registry.release(native) },
        )
        assertTrue(registry.register(native, request))

        registry.destroy()
        registry.destroy()

        assertTrue(request.isDone)
        assertFalse(request.isCancelled)
        assertTrue(results.isEmpty())
        assertEquals(listOf(native), released)
    }
}
