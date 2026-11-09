package com.mapfusion.demo.data

import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.RequestHandle
import org.junit.Assert.*
import org.junit.Test

class DemoRequestRepositoryTest {
    private class Handle(private val onCancel: () -> Unit = {}, var done: Boolean = false) : RequestHandle {
        var cancelCount = 0
        override val isDone get() = done
        override var isCancelled = false
            private set
        override fun cancel(): Boolean {
            if (done) return false
            cancelCount++
            done = true
            isCancelled = true
            onCancel()
            return true
        }
    }
    private class Pending {
        lateinit var callback: MapCallback<String>
        val handle = Handle({ callback.onResult(MapResult.Failure(MapError(ErrorType.CANCELLED, "cancelled"))) })
        fun launch(callback: MapCallback<String>): RequestHandle { this.callback = callback; return handle }
        fun succeed(value: String) = callback.onResult(MapResult.Success(value))
    }
    @Test fun replacement_cancels_old_without_delivering_its_cancel_or_late_success() {
        val repo = DemoRequestRepository()
        val received = mutableListOf<MapResult<String>>()
        val old = Pending()
        val fresh = Pending()
        repo.execute("poi", old::launch, MapCallback { received += it })
        repo.execute("poi", fresh::launch, MapCallback { received += it })
        old.succeed("old")
        fresh.succeed("fresh")
        assertEquals(1, old.handle.cancelCount)
        assertEquals(listOf(MapResult.Success("fresh")), received)
    }
    @Test fun session_invalidation_rejects_old_callback_and_epoch() {
        val repo = DemoRequestRepository()
        val oldEpoch = repo.generation
        val old = Pending()
        val received = mutableListOf<MapResult<String>>()
        repo.execute("route", old::launch, MapCallback { received += it })
        repo.invalidateSession()
        old.succeed("old")
        assertFalse(repo.accepts(oldEpoch))
        assertTrue(repo.accepts(repo.generation))
        assertTrue(received.isEmpty())
        assertEquals(1, old.handle.cancelCount)
    }
    @Test fun closing_is_idempotent_and_rejects_new_work() {
        val repo = DemoRequestRepository()
        val pending = Pending()
        repo.execute("weather", pending::launch, MapCallback { fail("closed callback must be dropped") })
        repo.close()
        repo.close()
        pending.succeed("late")
        assertEquals(1, pending.handle.cancelCount)
        assertFalse(repo.accepts(repo.generation))
        assertThrows(IllegalStateException::class.java) {
            repo.execute("next", Pending()::launch, MapCallback {})
        }
    }
    @Test fun replacing_one_key_does_not_cancel_other_services() {
        val repo = DemoRequestRepository()
        val route = Pending()
        repo.execute("route", route::launch, MapCallback {})
        repo.execute("poi", Pending()::launch, MapCallback {})
        repo.execute("poi", Pending()::launch, MapCallback {})
        assertEquals(0, route.handle.cancelCount)
        repo.close()
    }
    @Test fun synchronous_success_is_kept_once() {
        val repo = DemoRequestRepository()
        val received = mutableListOf<MapResult<String>>()
        val handle = Handle(done = true)
        repo.execute<String>("sync", { callback -> callback.onResult(MapResult.Success("ok")); handle },
            MapCallback { received += it })
        assertEquals(listOf(MapResult.Success("ok")), received)
        assertEquals(0, handle.cancelCount)
    }
    @Test fun launch_exception_does_not_leave_key_blocked() {
        val repo = DemoRequestRepository()
        assertThrows(IllegalArgumentException::class.java) {
            repo.execute<String>("poi", { throw IllegalArgumentException("bad request") }, MapCallback {})
        }
        val next = Pending()
        var received: MapResult<String>? = null
        repo.execute("poi", next::launch, MapCallback { received = it })
        next.succeed("ok")
        assertEquals(MapResult.Success("ok"), received)
        repo.close()
    }
}
