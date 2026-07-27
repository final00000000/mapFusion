package com.mapfusion.api.async

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** 将任务统一投递到 Android 主线程的默认回调执行器。 */
object AndroidMainThreadExecutor : Executor {
    private val handler: Handler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(Looper.getMainLooper())
    }

    override fun execute(command: Runnable) {
        if (!handler.post(command)) {
            throw RejectedExecutionException("Android 主线程已拒绝 Map Fusion 回调")
        }
    }
}
