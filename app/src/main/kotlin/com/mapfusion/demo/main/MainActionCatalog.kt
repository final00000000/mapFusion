package com.mapfusion.demo.main

import com.mapfusion.demo.presentation.main.DemoToolCategory

/** 首页的用户意图；视图仅发出标识，不持有 SDK 操作或 Activity 方法引用。 */
internal enum class MainAction {
    /** 定位：经系统授权后启动连续定位展示，位置结果由定位功能写入 UiState。 */
    LOCATE,
    /** 地图设置：打开地图类型、底图语言与图层开关草稿弹窗，点击“应用”才提交给 SDK。 */
    MAP_SETTINGS,
    /** 截图：对当前地图发起一次快照请求，只展示尺寸与 PNG 字节数，不保存图片产物。 */
    SNAPSHOT,
    /** 路线/轻导航：打开路线弹窗，按所选出行方式规划路线或发起地图内嵌导航。 */
    ROUTE_OPTIONS,
    /** 官方 Navi：拉起厂商官方导航界面，仅在当前变体装载官方导航 SDK 且能力可用时出现。 */
    OFFICIAL_NAVIGATION,
    /** 暂停/继续：读取内嵌导航的真实状态，在暂停推进与恢复推进之间切换。 */
    NAVIGATION_PAUSE_RESUME,
    /** 停止轻导航：停止内嵌导航并清除页面运行标记，不清理普通 Demo 覆盖物。 */
    NAVIGATION_STOP,
    /** 地址解析：用公开示例地址请求地理编码，成功后绘制结果 Marker 并移动相机。 */
    GEOCODE,
    /** 逆地理：以当前操作中心请求逆地理编码，只展示地址摘要，不持久化真实位置。 */
    REVERSE_GEOCODE,
    /** 周边 POI：以当前中心搜索示例咖啡店，最多绘制 12 个结果并适配视野。 */
    POI,
    /** 行政区：检索公开示例行政区边界，按独立环绘制多边形并适配视野。 */
    DISTRICT,
    /** 实时天气：查询示例城市当前天气，缺失字段明确显示为“未知”。 */
    WEATHER_NOW,
    /** 天气预报：查询逐日预报并展示前三天摘要，空列表不伪造天气。 */
    WEATHER_FORECAST,
    /** Marker：清空 Demo 自有覆盖物后添加可拖拽的统一示例 Marker。 */
    MARKER,
    /** 海量点：向厂商原生海量点图层一次提交 625 个示例点并适配视野。 */
    MULTI_POINT,
    /** 折线：清空 Demo 覆盖物后连接示例西侧、中心和东侧点，不额外添加起终点 Marker。 */
    POLYLINE,
    /** 多边形：清空 Demo 覆盖物后绘制三点示例多边形。 */
    POLYGON,
    /** 圆：清空 Demo 覆盖物后在操作中心西侧的示例点绘制半径 500 米的圆。 */
    CIRCLE,
    /** 文字：清空 Demo 覆盖物后在示例中心绘制文字覆盖物。 */
    TEXT,
    /** 地面图：清空 Demo 覆盖物后绘制 650×320 米的示例地面图片。 */
    GROUND,
    /** 热力图：清空 Demo 覆盖物后提交四个示例热力点生成热力图层。 */
    HEAT_MAP,
    /** 清空：先停止导航、轨迹与定位展示，再移除全部地图覆盖物。 */
    CLEAR_MAP,
    /** 开始轨迹：经系统授权后停止导航与定位展示，并新建轨迹录制器。 */
    TRACK_START,
    /** 暂停/继续轨迹：按录制器真实状态切换，空闲或已结束时提示先开始。 */
    TRACK_PAUSE_RESUME,
    /** 结束轨迹：停止录制但保留录制器结果，用于展示最终统计。 */
    TRACK_STOP,
    /** 清除轨迹：销毁轨迹录制器并移除轨迹折线与当前位置 Marker，不触发全局清空。 */
    TRACK_CLEAR,
}

/** 功能按钮的视觉语义，与具体业务动作解耦。 */
internal enum class MainActionStyle {
    /** 默认样式：中性普通操作，使用常规文字色与描边背景。 */
    DEFAULT,
    /** 主要样式：当前分类的推荐入口，使用品牌强调色与加粗字重。 */
    PRIMARY,
    /** 危险样式：清空、停止等破坏性操作，使用错误色提示后果。 */
    DANGER
}

/** 纯值类型的功能入口，不在可恢复状态中保存点击闭包。 */
internal data class MainActionSpec(
    /** 点击后分发的用户意图，由分发器映射到具体功能，本身不持有 SDK 句柄。 */
    val action: MainAction,
    /** 按钮上显示的中文短标签，仅供视图渲染，不参与业务判断。 */
    val title: String,
    /** 按钮视觉语义，默认按普通操作渲染，危险操作由面板改用错误色。 */
    val style: MainActionStyle = MainActionStyle.DEFAULT
)

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
