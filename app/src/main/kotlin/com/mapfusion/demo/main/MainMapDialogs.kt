package com.mapfusion.demo.main

import android.app.AlertDialog
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.mapfusion.api.diagnostics.MapSdkIdentity
import com.mapfusion.api.model.MapResult
import com.mapfusion.demo.BuildConfig
import com.mapfusion.demo.DemoTheme
import com.mapfusion.demo.map.DemoMapSettingsApplier
import com.mapfusion.demo.presentation.main.DemoMapSettings

/** 地图设置、室内楼层及接入诊断弹窗；布局与地图宿主分离，不拥有原生地图会话。 */
internal class MainMapDialogs(
    /** 宿主 Activity；用于构建弹窗视图与读取主题资源。 */
    private val activity: ComponentActivity,
    /** 地图宿主；提供当前控制器、已应用设置与统一消息入口。 */
    private val host: MainMapHost,
    /** 页面级弹窗所有者；窗口经它登记并随页面销毁统一关闭。 */
    private val owner: MainDialogOwner
) {
    /** 小型控件工厂；只创建视图与点击绑定，不参与地图业务或状态保存。 */
    private val widgets = MainWidgets(activity)

    /** 创建厂商能力驱动的设置草稿，只有应用按钮才将设置提交给 SDK。 */
    fun settings() = host.perform("地图设置") {
        val controller = host.controller
        val initial = host.viewModel.state.value.mapSettings
        val draft = MainMapSettingsDraft(initial.copy(mapType = controller?.getMapType() ?: initial.mapType),
            controller?.supportedMapTypes().orEmpty(), controller?.supportedMapLanguages().orEmpty())
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(widgets.dp(18), widgets.dp(8), widgets.dp(18), widgets.dp(4))
        }
        val selectors = DemoTheme.dialogSectionCard(activity)
        selectors.addView(widgets.label("地图类型"))
        val type = widgets.spinner(draft.mapTypes.map { it.displayName() }, draft.mapTypes.indexOf(draft.snapshot().mapType))
        selectors.addView(type, LinearLayout.LayoutParams(-1, widgets.dp(44)))
        val language = if (draft.mapLanguages.isNotEmpty()) {
            selectors.addView(widgets.label("底图语言"))
            widgets.spinner(draft.mapLanguages.map { it.displayName() }, draft.mapLanguages.indexOf(draft.snapshot().mapLanguage)).also {
                selectors.addView(it, LinearLayout.LayoutParams(-1, widgets.dp(44)))
            }
        } else null
        panel.addView(selectors)
        panel.addView(buildSwitches(draft), LinearLayout.LayoutParams(-1, -2).apply { topMargin = widgets.dp(10) })
        panel.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(widgets.button("SDK 接入诊断", ::identity), LinearLayout.LayoutParams(0, widgets.dp(40), 1f))
            addView(widgets.button("室内楼层", ::indoor), LinearLayout.LayoutParams(0, widgets.dp(40), 1f).apply {
                marginStart = widgets.dp(8)
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = widgets.dp(10) })
        controller?.legalInfo()?.displaySummary()?.takeIf(String::isNotBlank)?.let { summary ->
            panel.addView(widgets.label("官方地图信息"))
            panel.addView(TextView(activity).apply {
                text = summary; textSize = 12f; setTextColor(DemoTheme.TEXT_SECONDARY)
                setPadding(widgets.dp(4), widgets.dp(4), widgets.dp(4), widgets.dp(8))
            })
        }
        owner.show(AlertDialog.Builder(activity).setTitle("地图基础设置")
            .setView(ScrollView(activity).apply { isFillViewport = true; addView(panel) })
            .setNegativeButton("取消", null).setPositiveButton("应用") { _, _ ->
                draft.select(draft.mapTypes.getOrElse(type.selectedItemPosition) { draft.mapTypes.first() },
                    language?.let { draft.mapLanguages.getOrNull(it.selectedItemPosition) })
                apply(draft.snapshot())
            })
    }

    /** 所有开关仅修改局部草稿，取消弹窗无需向 ViewModel 写回默认值。 */
    private fun buildSwitches(draft: MainMapSettingsDraft): LinearLayout = DemoTheme.dialogSectionCard(activity).apply {
        setPadding(widgets.dp(12), widgets.dp(6), widgets.dp(12), widgets.dp(6))
        val value = draft.snapshot()
        addView(widgets.toggle("实时交通", value.trafficEnabled) { checked -> draft.update { it.copy(trafficEnabled = checked) } })
        addView(widgets.toggle("3D 建筑", value.buildingsEnabled) { checked -> draft.update { it.copy(buildingsEnabled = checked) } })
        addView(widgets.toggle("室内地图", value.indoorEnabled) { checked -> draft.update { it.copy(indoorEnabled = checked) } })
        addView(widgets.toggle("底图 POI", value.mapPoiEnabled) { checked -> draft.update { it.copy(mapPoiEnabled = checked) } })
        addView(widgets.toggle("指南针", value.compassEnabled) { checked -> draft.update { it.copy(compassEnabled = checked) } })
        addView(widgets.toggle("缩放控件", value.zoomControlsEnabled) { checked -> draft.update { it.copy(zoomControlsEnabled = checked) } })
    }

    /** SDK 确认设置成功才替换已应用快照，保持初始化与手动应用使用同一路径。 */
    private fun apply(settings: DemoMapSettings) = host.perform("应用地图设置") {
        val controller = requireNotNull(host.controller) { "地图尚未初始化" }
        when (val result = DemoMapSettingsApplier.apply(controller, settings)) {
            is MapResult.Failure -> host.messages.mapError("地图设置应用失败", result.error)
            is MapResult.Success -> {
                val applied = result.data
                host.viewModel.applyMapSettings(applied)
                val toggles = listOfNotNull("交通".takeIf { applied.trafficEnabled }, "建筑".takeIf { applied.buildingsEnabled },
                    "室内".takeIf { applied.indoorEnabled }, "POI".takeIf { applied.mapPoiEnabled },
                    "指南针".takeIf { applied.compassEnabled }).joinToString("、").ifBlank { "基础图层均关闭" }
                host.messages.success("地图设置已应用 · ${applied.mapType.displayName()}", "$toggles · ${applied.mapLanguage.displayName()}")
            }
        }
    }

    /** 在用户主动打开诊断时读取安装身份，仅在本机弹窗显示，不写入日志或源码。 */
    fun identity() {
        val message = when (val result = MapSdkIdentity.read(activity)) {
            is MapResult.Success -> buildString {
                appendLine("当前安装包：${result.data.packageName}"); appendLine("变体：${BuildConfig.FLAVOR}")
                appendLine("SHA1：${result.data.signingSha1.joinToString()}")
                appendLine("百度 MCODE：${result.data.baiduMcodes.joinToString()}")
                append("两家控制台都必须绑定上面的实际包名和签名。正式签名另行登记。")
            }
            is MapResult.Failure -> result.error.message
        }
        owner.show(AlertDialog.Builder(activity).setTitle("SDK 接入诊断").setMessage(message).setPositiveButton("关闭", null))
    }

    /** 按当前室内建筑的真实楼层显示选择项，缺少建筑数据时只提示放大查看。 */
    fun indoor() = host.perform("室内楼层") {
        val controller = requireNotNull(host.controller) { "地图尚未初始化" }
        if (!controller.supportsIndoorFloorControl()) { host.messages.error("室内楼层", "当前厂商未提供室内楼层控制"); return@perform }
        controller.setIndoorEnabled(true)
        host.viewModel.applyMapSettings(host.viewModel.state.value.mapSettings.copy(indoorEnabled = true))
        val epoch = host.requests.generation
        controller.setOnIndoorBuildingListener { building -> host.deliver(epoch) {
            host.messages.info("室内地图", building?.let { "建筑 ${it.id} · 当前层 ${it.activeFloorId}" } ?: "已离开室内建筑")
        } }
        val building = controller.activeIndoorBuilding()
        if (building == null || building.floors.isEmpty()) {
            host.messages.info("室内图层已开启", "放大到有室内数据的建筑后，再点击“室内楼层”选择楼层"); return@perform
        }
        owner.show(AlertDialog.Builder(activity).setTitle("楼层 · 当前 ${building.activeFloorId}")
            .setItems(building.floors.map { it.name }.toTypedArray()) { _, index -> host.perform("切换楼层") {
                check(host.controller === controller) { "地图会话已变化，请重新选择楼层" }
                when (val result = controller.switchIndoorFloor(building.id, building.floors[index].id)) {
                    is MapResult.Success -> host.messages.info("切层请求已提交", "等待地图楼层更新")
                    is MapResult.Failure -> host.messages.mapError("切换楼层失败", result.error)
                }
            } }.setNegativeButton("关闭", null))
    }
}
