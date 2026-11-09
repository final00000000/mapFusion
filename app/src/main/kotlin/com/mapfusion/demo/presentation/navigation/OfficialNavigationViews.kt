package com.mapfusion.demo.presentation.navigation

import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.mapfusion.api.model.OfficialNavigationMode
import com.mapfusion.api.model.TravelMode

/** 页面内的控件引用表，仅供构造层与渲染层共享；不存业务状态，也不拥有导航引擎。 */
internal class OfficialNavigationViews {
    /** 原生导航 View 的页面容器，由 Page 初始化；会话和 View 生命周期仍归 SessionHost。 */
    lateinit var navigatorHost: FrameLayout
    /** 覆盖原生容器的初始化/终态/错误说明，由 Page 初始化，Renderer 只按状态更新显示。 */
    lateinit var placeholder: TextView
    /** 顶部引擎状态徽标，由 Page 初始化；不是业务状态的存储副本。 */
    lateinit var stateBadge: TextView
    /** 导航事件/操作结果的详情控件，由 Page 初始化；文字内容来自 UiState。 */
    lateinit var detail: TextView
    /** 模拟速度整行布局，由 Controls 初始化；可见性与可操作性分别由纯策略决定。 */
    lateinit var speedRow: LinearLayout
    /** 速度显示文本，由 Controls 初始化；以 km/h 格式化已选速度，不读取车辆真实速度。 */
    lateinit var speedValue: TextView
    /** 模拟速度滑条，由 Controls 初始化；progress 为相对最小速度的偏移，不直接存 km/h。 */
    lateinit var speed: SeekBar
    /** 导航模式值到选择按钮的有序引用表；仅登记厂商支持项，业务选择仍归 VM。 */
    val modes = linkedMapOf<OfficialNavigationMode, Button>()
    /** 规范化出行方式值到按钮的有序引用表；不缓存路线或导航器。 */
    val travelModes = linkedMapOf<TravelMode, Button>()
    /** 开始/控制等意图到按钮的引用表；关闭页面时断开监听，不能写入恢复存储。 */
    val actions = linkedMapOf<OfficialNavigationAction, Button>()
}
