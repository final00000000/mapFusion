package com.mapfusion.demo.presentation.main

import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.EmbeddedNavigationMode
import com.mapfusion.api.model.MapLanguage
import com.mapfusion.api.model.MapLocation
import com.mapfusion.api.model.MapType
import com.mapfusion.api.model.TravelMode
import com.mapfusion.demo.data.PrivacyDecision

/** 系统位置授权成功后需要继续的用户操作；不保存 Activity 回调。 */
internal enum class LocationPermissionAction { LOCATE, START_TRACK, START_NAVIGATION }

/** 首页五类工具的稳定标识与中文标题，用于保存用户的分类选择。 */
internal enum class DemoToolCategory(val title: String) {
    COMMON("常用"), NAVIGATION("导航"), SEARCH("查询服务"), OVERLAY("覆盖物"), TRACK("轨迹")
}

/** 状态消息的业务语义，具体颜色由视图层决定。 */
internal enum class DemoStatusKind { NEUTRAL, INFO, SUCCESS, ERROR }

/** 当前地图宿主的运行阶段；只描述当前会话，不写入恢复存储。 */
internal enum class MainMapPhase { DISABLED, INITIALIZING, READY, FAILED }

/** 结果条的不可变数据，避免业务控制器直接修改 TextView。 */
internal data class DemoStatus(
    val title: String = "等待操作",
    val detail: String = "点击下方功能卡片体验统一地图 API",
    val kind: DemoStatusKind = DemoStatusKind.NEUTRAL,
)

/** 完整地图设置快照；弹窗只修改副本，点击应用后才替换已生效设置。 */
internal data class DemoMapSettings(
    val mapType: MapType = MapType.NORMAL,
    val mapLanguage: MapLanguage = MapLanguage.CHINESE,
    val trafficEnabled: Boolean = false,
    val buildingsEnabled: Boolean = true,
    val indoorEnabled: Boolean = false,
    val mapPoiEnabled: Boolean = true,
    val compassEnabled: Boolean = true,
    val zoomControlsEnabled: Boolean = true,
) {
    /** 编码为 SavedStateHandle 可保存的字符串，固定顺序兼容已有恢复数据。 */
    fun encode(): String = listOf(mapType.name, mapLanguage.name, trafficEnabled, buildingsEnabled,
        indoorEnabled, mapPoiEnabled, compassEnabled, zoomControlsEnabled).joinToString("|")

    /** 负责恢复设置快照，不引用任何 Android 或厂商对象。 */
    companion object {
        /** 对缺失、损坏或不认识的快照回退到默认值，避免恢复阶段崩溃。 */
        fun decode(value: String?): DemoMapSettings {
            val parts = value?.split('|') ?: return DemoMapSettings()
            if (parts.size != 8) return DemoMapSettings()
            return runCatching {
                DemoMapSettings(MapType.valueOf(parts[0]), MapLanguage.valueOf(parts[1]),
                    parts[2].toBooleanStrict(), parts[3].toBooleanStrict(), parts[4].toBooleanStrict(),
                    parts[5].toBooleanStrict(), parts[6].toBooleanStrict(), parts[7].toBooleanStrict())
            }.getOrDefault(DemoMapSettings())
        }
    }
}

/** 首页唯一可观察状态；选择可恢复，地图阶段、定位与导航运行态必须由新会话重新产生。 */
internal data class MainUiState(
    val preferredProvider: Provider = Provider.BAIDU,
    val routeMode: TravelMode = TravelMode.DRIVING,
    val navigationMode: EmbeddedNavigationMode = EmbeddedNavigationMode.REAL,
    val toolCategory: DemoToolCategory = DemoToolCategory.COMMON,
    val mapSettings: DemoMapSettings = DemoMapSettings(),
    val pendingLocationAction: LocationPermissionAction? = null,
    val permissionRequestInFlight: Boolean = false,
    val permissionDenied: Boolean = false,
    val permissionPermanentlyDenied: Boolean = false,
    val privacyDecision: PrivacyDecision = PrivacyDecision.UNDECIDED,
    val embeddedNavigationActive: Boolean = false,
    val lastLocation: MapLocation? = null,
    val mapPhase: MainMapPhase = MainMapPhase.DISABLED,
    val capabilityCount: Int = 0,
    val status: DemoStatus = DemoStatus(),
)
