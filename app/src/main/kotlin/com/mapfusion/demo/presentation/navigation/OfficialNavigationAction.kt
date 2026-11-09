package com.mapfusion.demo.presentation.navigation

import com.mapfusion.api.model.OfficialNavigationMode
import com.mapfusion.api.model.TravelMode

/** 页面只发出用户意图；权限、引擎副作用和纯选择归约分别交给对应职责处理。 */
internal sealed interface OfficialNavigationAction {
    /** 开始按钮意图，由 Activity 先连接精确定位权限，再提交导航请求。 */
    data object Start : OfficialNavigationAction
    /** 根据当前业务状态切换暂停或继续，由会话操作层调用统一引擎。 */
    data object PauseOrResume : OfficialNavigationAction
    /** 停止当前导航，成功后由 Activity 结束页面。 */
    data object Stop : OfficialNavigationAction
    /** 在已规划的路线中循环选择下一条，不允许空列表参与取模。 */
    data object NextRoute : OfficialNavigationAction
    /** 请求厂商主动重算当前路线，不自行修改路线列表。 */
    data object Reroute : OfficialNavigationAction
    /** 请求厂商显示当前路线全览，不改变业务状态。 */
    data object Overview : OfficialNavigationAction
    /** 请求厂商恢复导航车标跟随。 */
    data object Follow : OfficialNavigationAction
    /** 请求切换语音，只有厂商返回成功才持久化新的选择。 */
    data object ToggleVoice : OfficialNavigationAction
    /** 请求按自动、白天、夜间循环配色，成功后保存选择。 */
    data object CycleDayNight : OfficialNavigationAction
    /** 滑条松手后尝试把已选速度应用到运行中的模拟导航。 */
    data object ApplySpeed : OfficialNavigationAction
    /** 输入真实或模拟模式，由 ViewModel 校验能力和页面忙碌状态。 */
    data class SelectMode(
        /** 用户点击的真实/模拟模式值，仅表达选择，由 VM 校验后提交，不持有导航器。 */
        val mode: OfficialNavigationMode
    ) : OfficialNavigationAction
    /** 输入出行方式，由 ViewModel 归一别名并校验厂商支持。 */
    data class SelectTravel(
        /** 用户选择的出行方式值；骑行别名与厂商支持范围由 VM 统一规范化。 */
        val mode: TravelMode
    ) : OfficialNavigationAction
    /** 输入滑条速度，先保存和渲染，不在拖动过程中频繁调用引擎。 */
    data class SelectSpeed(
        /** 滑条选择速度，单位 km/h；VM 限幅到 20–120，拖动本身不调用 SDK 调速。 */
        val kilometersPerHour: Int
    ) : OfficialNavigationAction
}
