package com.mapfusion.demo.main

import android.app.AlertDialog
import androidx.activity.ComponentActivity

/** 普通功能弹窗的页面级所有者，避免 Activity 重建后遗留旧窗口与点击回调。 */
internal class MainDialogOwner(private val activity: ComponentActivity) : AutoCloseable {
    private val dialogs = mutableSetOf<AlertDialog>()
    private var closed = false

    /** 有效宿主上才创建窗口，登记所有权并在关闭后移除对应句柄。 */
    fun show(builder: AlertDialog.Builder) {
        if (closed || activity.isFinishing || activity.isDestroyed) return
        val dialog = builder.create()
        dialogs += dialog
        dialog.setOnDismissListener { dialogs -= dialog }
        dialog.show()
    }

    /** 页面销毁时清空窗口集合并关闭所有弹窗，禁止旧页面继续创建窗口。 */
    override fun close() {
        closed = true
        val pending = dialogs.toList(); dialogs.clear()
        pending.forEach { it.dismiss() }
    }
}
