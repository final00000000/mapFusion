package com.mapfusion.demo.main

import com.mapfusion.demo.presentation.main.DemoToolCategory

/** 首页的用户意图；视图仅发出标识，不持有 SDK 操作或 Activity 方法引用。 */
internal enum class MainAction {
    LOCATE, MAP_SETTINGS, SNAPSHOT, ROUTE_OPTIONS, OFFICIAL_NAVIGATION, NAVIGATION_PAUSE_RESUME,
    NAVIGATION_STOP, GEOCODE, REVERSE_GEOCODE, POI, DISTRICT, WEATHER_NOW, WEATHER_FORECAST,
    MARKER, MULTI_POINT, POLYLINE, POLYGON, CIRCLE, TEXT, GROUND, HEAT_MAP, CLEAR_MAP,
    TRACK_START, TRACK_PAUSE_RESUME, TRACK_STOP, TRACK_CLEAR,
}

/** 功能按钮的视觉语义，与具体业务动作解耦。 */
internal enum class MainActionStyle { DEFAULT, PRIMARY, DANGER }

/** 纯值类型的功能入口，不在可恢复状态中保存点击闭包。 */
internal data class MainActionSpec(val action: MainAction, val title: String,
    val style: MainActionStyle = MainActionStyle.DEFAULT)

/** 首页功能目录；只根据分类和官方导航能力计算入口，可在 JVM 中完整核对。 */
internal object MainActionCatalog {
    /** 返回当前分类全部按钮；官方导航入口必须由当前厂商变体能力决定。 */
    fun actions(category: DemoToolCategory, officialNavigationAvailable: Boolean): List<MainActionSpec> = when (category) {
        DemoToolCategory.COMMON -> listOf(
            MainActionSpec(MainAction.LOCATE, "定位", MainActionStyle.PRIMARY),
            MainActionSpec(MainAction.MAP_SETTINGS, "地图设置"), MainActionSpec(MainAction.SNAPSHOT, "截图"))
        DemoToolCategory.NAVIGATION -> buildList {
            add(MainActionSpec(MainAction.ROUTE_OPTIONS, "路线/轻导航", MainActionStyle.PRIMARY))
            if (officialNavigationAvailable) add(MainActionSpec(MainAction.OFFICIAL_NAVIGATION, "官方 Navi", MainActionStyle.PRIMARY))
            add(MainActionSpec(MainAction.NAVIGATION_PAUSE_RESUME, "暂停/继续"))
            add(MainActionSpec(MainAction.NAVIGATION_STOP, "停止轻导航", MainActionStyle.DANGER))
        }
        DemoToolCategory.SEARCH -> listOf(
            MainActionSpec(MainAction.GEOCODE, "地址解析"), MainActionSpec(MainAction.REVERSE_GEOCODE, "逆地理"),
            MainActionSpec(MainAction.POI, "周边 POI", MainActionStyle.PRIMARY), MainActionSpec(MainAction.DISTRICT, "行政区"),
            MainActionSpec(MainAction.WEATHER_NOW, "实时天气"), MainActionSpec(MainAction.WEATHER_FORECAST, "天气预报"))
        DemoToolCategory.OVERLAY -> listOf(
            MainActionSpec(MainAction.MARKER, "Marker"), MainActionSpec(MainAction.MULTI_POINT, "海量点"),
            MainActionSpec(MainAction.POLYLINE, "折线"), MainActionSpec(MainAction.POLYGON, "多边形"),
            MainActionSpec(MainAction.CIRCLE, "圆"), MainActionSpec(MainAction.TEXT, "文字"),
            MainActionSpec(MainAction.GROUND, "地面图"), MainActionSpec(MainAction.HEAT_MAP, "热力图"),
            MainActionSpec(MainAction.CLEAR_MAP, "清空", MainActionStyle.DANGER))
        DemoToolCategory.TRACK -> listOf(
            MainActionSpec(MainAction.TRACK_START, "开始", MainActionStyle.PRIMARY),
            MainActionSpec(MainAction.TRACK_PAUSE_RESUME, "暂停/继续"), MainActionSpec(MainAction.TRACK_STOP, "结束"),
            MainActionSpec(MainAction.TRACK_CLEAR, "清除", MainActionStyle.DANGER))
    }

    /** 根据固定四列计算工具行数，保证各分类高度随按钮数量动态变化。 */
    fun rowCount(actionCount: Int): Int = ((actionCount + 3) / 4).coerceAtLeast(1)
}
