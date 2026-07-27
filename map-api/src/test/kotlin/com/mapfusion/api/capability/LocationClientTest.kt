package com.mapfusion.api.capability

import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.LocationOptions
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapLocation
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.RequestHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocationClientTest {

    @Test
    fun requestSingleLocation_defaultOverloadUsesLocationTimeout() {
        val client = RecordingLocationClient()
        val options = LocationOptions(timeoutMs = 4_321)

        client.requestSingleLocation(options, NO_OP_CALLBACK)

        val call = client.singleCalls.single()
        assertEquals(options, call.options)
        assertEquals(4_321, call.asyncOptions.timeoutMillis)
    }

    @Test
    fun startContinuousLocation_defaultOverloadUsesLocationTimeout() {
        val client = RecordingLocationClient()
        val options = LocationOptions(timeoutMs = 5_678)

        client.startContinuousLocation(options, NO_OP_CALLBACK)

        val call = client.continuousCalls.single()
        assertEquals(options, call.options)
        assertEquals(5_678, call.asyncOptions.timeoutMillis)
    }

    @Test
    fun nonPositiveSingleTimeout_reachesImplementationWithoutConstructionFailure() {
        listOf(0L, -1L).forEach { timeoutMs ->
            val client = RecordingLocationClient()
            val options = LocationOptions(timeoutMs = timeoutMs)

            client.requestSingleLocation(options, NO_OP_CALLBACK)

            val call = client.singleCalls.single()
            assertEquals(timeoutMs, call.options.timeoutMs)
            assertEquals(AsyncCallOptions.DEFAULT_TIMEOUT_MILLIS, call.asyncOptions.timeoutMillis)
            assertNotNull(call.handle)
        }
    }

    @Test
    fun nonPositiveContinuousTimeout_reachesImplementationWithoutConstructionFailure() {
        listOf(0L, -1L).forEach { timeoutMs ->
            val client = RecordingLocationClient()
            val options = LocationOptions(timeoutMs = timeoutMs)

            client.startContinuousLocation(options, NO_OP_CALLBACK)

            val call = client.continuousCalls.single()
            assertEquals(timeoutMs, call.options.timeoutMs)
            assertEquals(AsyncCallOptions.DEFAULT_TIMEOUT_MILLIS, call.asyncOptions.timeoutMillis)
            assertNotNull(call.handle)
        }
    }

    private companion object {
        val NO_OP_CALLBACK = MapCallback<MapLocation> { _: MapResult<MapLocation> -> }
    }
}

private class RecordingLocationClient : LocationClient {

    data class Call(
        val options: LocationOptions,
        val asyncOptions: AsyncCallOptions,
        val handle: RequestHandle,
    )

    val singleCalls = mutableListOf<Call>()
    val continuousCalls = mutableListOf<Call>()

    override fun requestSingleLocation(
        options: LocationOptions,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<MapLocation>,
    ): RequestHandle = RecordingHandle().also { handle ->
        singleCalls += Call(options, asyncOptions, handle)
    }

    override fun startContinuousLocation(
        options: LocationOptions,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<MapLocation>,
    ): RequestHandle = RecordingHandle().also { handle ->
        continuousCalls += Call(options, asyncOptions, handle)
    }

    override fun stopContinuousLocation() = Unit

    override fun destroy() = Unit
}

private class RecordingHandle : RequestHandle {
    override val isDone: Boolean = false
    override val isCancelled: Boolean = false
    override fun cancel(): Boolean = false
}
