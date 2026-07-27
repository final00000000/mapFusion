package com.mapfusion.factory

import java.util.ArrayDeque
import java.util.concurrent.Executor

/** 在任意 Executor 上将单会话回调收敛成一条有序事件流。 */
internal class SerialCallbackDispatcher<T>(
    private val executor: Executor,
    private val consumer: (T) -> Unit,
) {
    private val lock = Any()
    private val queue = ArrayDeque<T>()
    private var drainScheduled = false
    private var closed = false

    fun dispatch(events: List<T>) {
        if (events.isEmpty()) return
        val shouldSchedule = synchronized(lock) {
            if (closed) return
            events.forEach(queue::addLast)
            if (drainScheduled) {
                false
            } else {
                drainScheduled = true
                true
            }
        }
        if (shouldSchedule) scheduleDrain()
    }

    /** 关闭后清空尚未开始的事件；已经进入 listener 的调用无法被强行中断。 */
    fun close() {
        synchronized(lock) {
            closed = true
            queue.clear()
        }
    }

    private fun scheduleDrain() {
        try {
            executor.execute(::drain)
        } catch (_: Throwable) {
            synchronized(lock) {
                queue.clear()
                drainScheduled = false
            }
        }
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                when {
                    closed -> {
                        queue.clear()
                        drainScheduled = false
                        null
                    }

                    queue.isEmpty() -> {
                        drainScheduled = false
                        null
                    }

                    else -> queue.removeFirst()
                }
            } ?: return
            runCatching { consumer(next) }
        }
    }
}
