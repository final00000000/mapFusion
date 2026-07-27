package com.mapfusion.factory

import com.mapfusion.api.model.RequestHandle

/** JVM Fake 能力使用的可控请求句柄；默认仍是立即完成句柄。 */
internal class TestRequestHandle private constructor(
    initiallyDone: Boolean,
    private val onCancel: (() -> Unit)?,
    private val cancelFailure: Throwable?,
) : RequestHandle {
    @Volatile
    override var isDone: Boolean = initiallyDone
        private set

    @Volatile
    override var isCancelled: Boolean = false
        private set

    constructor() : this(initiallyDone = true, onCancel = null, cancelFailure = null)

    @Synchronized
    override fun cancel(): Boolean {
        if (isDone) return false
        isDone = true
        isCancelled = true
        try {
            cancelFailure?.let { throw it }
        } finally {
            onCancel?.invoke()
        }
        return true
    }

    @Synchronized
    fun complete(): Boolean {
        if (isDone) return false
        isDone = true
        return true
    }

    companion object {
        fun active(
            cancelFailure: Throwable? = null,
            onCancel: () -> Unit = {},
        ): TestRequestHandle = TestRequestHandle(
            initiallyDone = false,
            onCancel = onCancel,
            cancelFailure = cancelFailure,
        )
    }
}
