package com.mapfusion.demo.main

import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.EmbeddedNavigationMode
import com.mapfusion.api.model.MapLanguage
import com.mapfusion.api.model.MapLegalInfo
import com.mapfusion.api.model.MapType
import com.mapfusion.api.model.TravelMode
import java.util.Locale

/** 将厂商标识转换为界面中文名称，不依赖 Android 资源或原生 SDK。 */
internal fun Provider.displayName(): String = if (this == Provider.BAIDU) "百度" else "高德"

/** 地图类型的展示名称；业务状态仍保存枚举值而非中文文案。 */
internal fun MapType.displayName(): String = when (this) {
    MapType.NORMAL -> "普通"; MapType.SATELLITE -> "卫星"; MapType.NIGHT -> "夜间"
    MapType.NAVIGATION -> "导航"; MapType.NONE -> "无底图"
}

/** 地图底图语言的展示名称，不改变厂商接受的语言参数。 */
internal fun MapLanguage.displayName(): String = when (this) {
    MapLanguage.CHINESE -> "中文"; MapLanguage.ENGLISH -> "English"; MapLanguage.LOCAL -> "跟随系统"
}

/** 先规范化兼容枚举，再生成出行方式的中文标签。 */
internal fun TravelMode.displayName(): String = when (canonical()) {
    TravelMode.DRIVING -> "驾车"; TravelMode.WALKING -> "步行"; TravelMode.BICYCLE -> "自行车"
    TravelMode.ELECTRIC_BICYCLE -> "电动车"; TravelMode.TRANSIT -> "公交"; TravelMode.RIDING -> "自行车"
}

/** 明确区分真实定位与路线模拟，避免将轻量模拟误称为官方导航。 */
internal fun EmbeddedNavigationMode.displayName(): String = when (this) {
    EmbeddedNavigationMode.REAL -> "真实导航（GPS）"; EmbeddedNavigationMode.SIMULATED -> "模拟导航"
}

/** 各出行方式的演示模拟速度，单位为米每秒，不用于真实定位推进。 */
internal fun TravelMode.simulationSpeedMetersPerSecond(): Float = when (canonical()) {
    TravelMode.DRIVING -> 16.7f; TravelMode.WALKING -> 1.4f; TravelMode.BICYCLE -> 5f
    TravelMode.ELECTRIC_BICYCLE -> 6.9f; TravelMode.TRANSIT -> 12f; TravelMode.RIDING -> 5f
}

/** 仅拼接 SDK 提供的审图与版权值，缺失信息不伪造占位授权。 */
internal fun MapLegalInfo.displaySummary(): String = listOfNotNull(
    mapContentApprovalNumber?.let { "审图号 $it" }, englishMapApprovalNumber?.let { "英文地图 $it" },
    satelliteImageApprovalNumber?.let { "卫星影像 $it" }, terrainApprovalNumber?.let { "地形图 $it" },
    copyright, mappingQualification).joinToString("\n")

/** 页面距离与时间格式化，供查询、路线及轨迹共享，不持有任何资源。 */
internal object DemoLabels {
    /** 小于一公里显示米，其余保留一位小数公里。 */
    fun distance(meters: Int): String = if (meters < 1_000) "$meters m" else "%.1f km".format(Locale.CHINA, meters / 1_000.0)

    /** 按秒、分钟、小时显示耗时，不改变原始导航时长值。 */
    fun duration(seconds: Int): String = when {
        seconds < 60 -> "$seconds 秒"; seconds < 3_600 -> "${seconds / 60} 分钟"
        else -> "${seconds / 3_600} 小时 ${seconds % 3_600 / 60} 分钟"
    }

    /** 轨迹累计距离保留比路线摘要更高的精度。 */
    fun trackDistance(meters: Double): String = if (meters < 1_000) "%.0f m".format(Locale.CHINA, meters)
        else "%.2f km".format(Locale.CHINA, meters / 1_000.0)
}
