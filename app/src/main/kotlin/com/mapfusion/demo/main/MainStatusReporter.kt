package com.mapfusion.demo.main

import android.util.Log
import com.mapfusion.api.model.MapError
import com.mapfusion.demo.presentation.main.DemoStatus
import com.mapfusion.demo.presentation.main.DemoStatusKind
import com.mapfusion.demo.presentation.main.MainViewModel

/** 业务结果到 UiState 的唯一消息入口；不直接访问 TextView 或其他视图。 */
internal class MainStatusReporter(
    /** 页面状态所有者；消息以不可变快照写入 UiState，由状态收集器渲染。 */
    private val viewModel: MainViewModel
) {
    /** 发布过程消息，供权限等待、规划中等非最终状态使用。 */
    fun info(title: String, detail: String) = report(title, detail, DemoStatusKind.INFO)

    /** 发布成功结果；成功文案必须由实际 SDK 返回或已完成的本地动作产生。 */
    fun success(title: String, detail: String) = report(title, detail, DemoStatusKind.SUCCESS)

    /** 发布失败结果，不将异常回退伪装成业务成功。 */
    fun error(title: String, detail: String) = report(title, detail, DemoStatusKind.ERROR)

    /** 保留 SDK 的错误类型、原始码与消息用于页面诊断，不输出任何配置 Key。 */
    fun mapError(title: String, error: MapError) {
        val code = error.rawCode?.let { " · code=$it" }.orEmpty()
        val raw = error.rawMessage?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
        Log.e("MapFusionDemo", "$title [${error.type}]${error.message}$code$raw", error.cause)
        this.error("$title · ${error.type}$code", "${error.message}$raw")
    }

    /** 合成不可变消息快照，由生命周期内的状态收集器负责渲染。 */
    private fun report(title: String, detail: String, kind: DemoStatusKind) =
        viewModel.showStatus(DemoStatus(title, detail, kind))
}
