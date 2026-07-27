package com.mapfusion.api.async

import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.MapResult
import java.util.concurrent.Executor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestScopeTest {

    private val direct = Executor(Runnable::run)

    @Test
    fun replaceCancelsOnlyPreviousRequestForSameKey() {
        val scope = RequestScope()
        val first = request()
        val second = request()

        scope.replace("search", first)
        scope.replace("search", second)

        assertTrue(first.isCancelled)
        assertFalse(second.isCancelled)
        scope.cancelAll()
        assertTrue(second.isCancelled)
    }

    @Test
    fun differentKeysRemainIndependent() {
        val scope = RequestScope()
        val location = request()
        val route = request()

        scope.replace("location", location)
        scope.replace("route", route)
        scope.cancel("location")

        assertTrue(location.isCancelled)
        assertFalse(route.isCancelled)
    }

    @Test
    fun closedScopeCancelsNewRequestImmediately() {
        val scope = RequestScope()
        scope.close()
        val handle = request()

        scope.replace("late", handle)

        assertTrue(handle.isCancelled)
    }

    private fun request() = AsyncRuntime(direct).createRequest(
        callback = { _: MapResult<Unit> -> },
        options = AsyncCallOptions(callbackExecutor = direct),
    )
}
