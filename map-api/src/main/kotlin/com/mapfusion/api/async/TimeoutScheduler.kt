package com.mapfusion.api.async

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 可取消的单次超时任务。 */
fun interface TimeoutHandle {
    fun cancel(): Boolean
}

/**
 * 单次超时调度器。抽象该依赖后，Provider 可以在测试中使用手动时钟稳定复现竞态。
 */
fun interface TimeoutScheduler {
    fun schedule(delayMillis: Long, task: Runnable): TimeoutHandle
}

/** SDK 进程级共享超时调度器。内部线程不持有 Context。 */
object SharedTimeoutScheduler : TimeoutScheduler {
    private val executor = ScheduledThreadPoolExecutor(1, TimeoutThreadFactory()).apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
    }

    override fun schedule(delayMillis: Long, task: Runnable): TimeoutHandle {
        require(delayMillis > 0) { "delayMillis 必须大于 0" }
        val future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
        return TimeoutHandle { future.cancel(false) }
    }
}

private class TimeoutThreadFactory : ThreadFactory {
    private val nextId = AtomicInteger(1)

    override fun newThread(task: Runnable): Thread =
        Thread(task, "MapFusion-Timeout-${nextId.getAndIncrement()}").apply {
            isDaemon = true
        }
}
