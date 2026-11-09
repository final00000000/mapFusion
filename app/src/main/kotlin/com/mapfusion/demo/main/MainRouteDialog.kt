package com.mapfusion.demo.main

import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.mapfusion.api.model.EmbeddedNavigationMode
import com.mapfusion.api.model.TravelMode
import com.mapfusion.demo.DemoTheme
import com.mapfusion.demo.R

/** 路线选择弹窗：只收集模式和用户意图，授权及 SDK 调用由宿主与功能控制器负责。 */
internal class MainRouteDialog(
    /** 宿主 Activity；用于构建弹窗控件与读取字符串资源。 */
    private val activity: ComponentActivity,
    /** 地图宿主；提供已提交的路线选择与统一消息入口。 */
    private val host: MainMapHost,
    /** 页面级弹窗所有者；窗口随页面销毁统一关闭。 */
    private val owner: MainDialogOwner,
    /** 路线与导航功能；规划、暂停、继续和停止都委托给它。 */
    private val route: MainRouteFeature,
    /** 用户确认“发起导航”后的回调；把出行方式与导航模式交给权限流程。 */
    private val onNavigate: (TravelMode, EmbeddedNavigationMode) -> Unit
) {
    /** 小型控件工厂；只创建弹窗控件与点击绑定，不保存业务选择。 */
    private val widgets = MainWidgets(activity)

    /** 创建局部选择器，取消不写状态；规划与开始导航分别提交各自需要的选择。 */
    fun show() = host.perform("路线与导航") {
        val modes = listOf(TravelMode.DRIVING, TravelMode.WALKING, TravelMode.BICYCLE, TravelMode.ELECTRIC_BICYCLE, TravelMode.TRANSIT)
        val navigationModes = EmbeddedNavigationMode.entries
        val state = host.viewModel.state.value
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(widgets.dp(18), widgets.dp(8), widgets.dp(18), widgets.dp(4))
        }
        val selectors = DemoTheme.dialogSectionCard(activity)
        selectors.addView(widgets.label("出行方式"))
        val travel = widgets.spinner(modes.map { it.displayName() }, modes.indexOf(state.routeMode.canonical()))
        selectors.addView(travel, LinearLayout.LayoutParams(-1, widgets.dp(44)))
        selectors.addView(widgets.label("导航模式"))
        val navigation = widgets.spinner(navigationModes.map { it.displayName() }, navigationModes.indexOf(state.navigationMode))
        selectors.addView(navigation, LinearLayout.LayoutParams(-1, widgets.dp(44)))
        panel.addView(selectors)
        panel.addView(TextView(activity).apply {
            text = activity.getString(R.string.route_navigation_summary); textSize = 12.5f
            setLineSpacing(widgets.dp(2).toFloat(), 1f); setTextColor(DemoTheme.TEXT_SECONDARY)
            setPadding(widgets.dp(4), widgets.dp(10), widgets.dp(4), widgets.dp(8))
        })
        panel.addView(widgets.actionStrip("暂停导航" to route::pause, "继续导航" to route::resume, "停止导航" to route::stop))
        owner.show(AlertDialog.Builder(activity).setTitle("路线与导航").setView(panel).setNegativeButton("取消", null)
            .setNeutralButton("发起导航") { _, _ ->
                onNavigate(modes.getOrElse(travel.selectedItemPosition) { modes.first() },
                    navigationModes.getOrElse(navigation.selectedItemPosition) { EmbeddedNavigationMode.REAL })
            }.setPositiveButton("规划路线") { _, _ ->
                val mode = modes.getOrElse(travel.selectedItemPosition) { modes.first() }
                host.viewModel.selectRouteOptions(mode)
                route.plan(mode)
            })
    }
}
