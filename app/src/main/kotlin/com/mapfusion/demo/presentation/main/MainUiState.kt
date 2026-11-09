package com.mapfusion.demo.presentation.main

import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.EmbeddedNavigationMode
import com.mapfusion.api.model.MapLanguage
import com.mapfusion.api.model.MapLocation
import com.mapfusion.api.model.MapType
import com.mapfusion.api.model.TravelMode
import com.mapfusion.demo.data.PrivacyDecision

/** 系统位置授权成功后需要继续的用户操作；不保存 Activity 回调。 */
internal enum class LocationPermissionAction {
    /** 授权后启动首页定位展示并移动相机到定位结果。 */
    LOCATE,
    /** 授权后开始新的轨迹录制，具体互斥与清理由轨迹功能处理。 */
    START_TRACK,
    /** 授权后按已提交的出行方式启动真实轻导航，不代表权限返回前已经开始导航。 */
    START_NAVIGATION
}

/** 首页五类工具的稳定标识与中文标题，用于保存用户的分类选择。 */
internal enum class DemoToolCategory(
    /** 工具分类按钮显示的中文标题；恢复存储使用枚举 name，不使用此文案。 */
    val title: String
) {
    /** 定位、地图设置和截图等通用地图操作。 */
    COMMON("常用"),
    /** 路线规划、轻导航控制以及当前变体可用的官方导航入口。 */
    NAVIGATION("导航"),
    /** 地理编码、POI、行政区和天气等单次服务查询。 */
    SEARCH("查询服务"),
    /** 常规覆盖物、海量点、热力图和显式全局清空。 */
    OVERLAY("覆盖物"),
    /** 轨迹开始、暂停/继续、结束和仅清除轨迹组件。 */
    TRACK("轨迹")
}

/** 状态消息的业务语义，具体颜色由视图层决定。 */
internal enum class DemoStatusKind {
    /** 尚无操作结果或普通说明，不表示地图引擎已经就绪。 */
    NEUTRAL,
    /** 请求进行中、定位授权等待或运行进度等提示。 */
    INFO,
    /** 当前操作或数据结果成功，不等同于所有地图功能可用。 */
    SUCCESS,
    /** 当前操作失败、权限不足或服务不可用的提示。 */
    ERROR
}

/** 当前地图宿主的运行阶段；只描述当前会话，不写入恢复存储。 */
internal enum class MainMapPhase {
    /** 未启用地图会话，通常因为尚未同意、已撤回或会话刚被释放。 */
    DISABLED,
    /** 正在创建会话并恢复设置，尚不能视为资源装配完成。 */
    INITIALIZING,
    /** 当前地图会话和控制器已装配；不保证网络瓦片已全部加载或服务请求必然成功。 */
    READY,
    /** 当前会话创建或设置恢复失败，需重新初始化后才能使用地图资源。 */
    FAILED
}

/** 结果条的不可变数据，避免业务控制器直接修改 TextView。 */
internal data class DemoStatus(
    /** 结果条主标题；默认等待操作，仅属于当前页面，不写入恢复存储。 */
    val title: String = "等待操作",
    /** 主标题下的补充说明或错误摘要；默认提示功能入口，不保存原始 SDK 对象。 */
    val detail: String = "点击下方功能卡片体验统一地图 API",
    /** 标题与卡片的视觉语义；默认中性，与地图/导航实际运行阶段分别管理。 */
    val kind: DemoStatusKind = DemoStatusKind.NEUTRAL,
)

/** 完整地图设置快照；弹窗只修改副本，点击应用后才替换已生效设置。 */
internal data class DemoMapSettings(
    /** 底图类型选择，默认普通地图；应用/恢复时按当前厂商支持集合规范化。 */
    val mapType: MapType = MapType.NORMAL,
    /** 底图语言选择，默认中文；不是 Demo 界面语言，厂商不支持的语言不能强行应用。 */
    val mapLanguage: MapLanguage = MapLanguage.CHINESE,
    /** 是否请求显示实时交通图层；默认关闭，数据是否覆盖当前区域由厂商决定。 */
    val trafficEnabled: Boolean = false,
    /** 是否显示 3D 建筑图层；默认开启，具体建筑数据和可见缩放级别由厂商决定。 */
    val buildingsEnabled: Boolean = true,
    /** 是否启用室内地图图层；默认关闭，开启不代表已有可切换的室内建筑/楼层数据。 */
    val indoorEnabled: Boolean = false,
    /** 是否显示底图自带 POI；默认开启，不控制 Demo 自己创建的 POI Marker。 */
    val mapPoiEnabled: Boolean = true,
    /** 是否显示厂商地图指南针控件；默认开启，应用结果仍由 SDK 确认。 */
    val compassEnabled: Boolean = true,
    /** 是否显示厂商地图缩放控件；默认开启，不等同于是否允许缩放手势。 */
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
    /** 用户选择的地图厂商，可恢复；模型默认百度，启动时按变体规范化，不保证会话已创建。 */
    val preferredProvider: Provider = Provider.BAIDU,
    /** 已提交的路线出行方式，可恢复，默认驾车；弹窗未确认的临时选择不写入此字段。 */
    val routeMode: TravelMode = TravelMode.DRIVING,
    /** 轻导航的真实/模拟方式选择，可恢复，默认真实；不表示导航已启动。 */
    val navigationMode: EmbeddedNavigationMode = EmbeddedNavigationMode.REAL,
    /** 当前工具面板分类，可恢复，默认常用；只决定入口展示，不触发 SDK 调用。 */
    val toolCategory: DemoToolCategory = DemoToolCategory.COMMON,
    /** 可恢复的完整设置选择，默认标准开关组合；新会话仍须应用，弹窗草稿不直接修改它。 */
    val mapSettings: DemoMapSettings = DemoMapSettings(),
    /** 等待位置授权后的待办，null 表示无待办；可恢复，成功只消费一次，取消/撤回时清空。 */
    val pendingLocationAction: LocationPermissionAction? = null,
    /** 是否已有系统授权请求在途，可恢复，默认 false；用于防重入，不等于已获得权限。 */
    val permissionRequestInFlight: Boolean = false,
    /** 最近一次位置授权是否未获允许，可恢复，默认 false；实际权限仍须在操作前重新检查。 */
    val permissionDenied: Boolean = false,
    /** 最近拒绝是否需要引导应用设置，可恢复，默认 false；由系统授权协调器判断，非隐私拒绝。 */
    val permissionPermanentlyDenied: Boolean = false,
    /** 共享存储决定的页面缓存，默认未选择；不写 SavedStateHandle，不能代替实际持久决定复核。 */
    val privacyDecision: PrivacyDecision = PrivacyDecision.UNDECIDED,
    /** 当前轻导航是否规划/导航/暂停/重算中；默认 false，仅运行态，释放或终止时清除。 */
    val embeddedNavigationActive: Boolean = false,
    /** 当前会话最近有效定位，null 表示尚无结果或已释放；坐标系见 position，不持久化位置。 */
    val lastLocation: MapLocation? = null,
    /** 当前 SDK 宿主资源阶段，默认未启用；不持久化，新会话必须重新产生就绪状态。 */
    val mapPhase: MainMapPhase = MainMapPhase.DISABLED,
    /** 当前就绪厂商的能力数量，单位为项；默认/释放时为 0，不持久化，不代表全部能力已验收。 */
    val capabilityCount: Int = 0,
    /** 当前结果条快照，默认等待操作；只在页面存活期间展示，不恢复旧会话结果。 */
    val status: DemoStatus = DemoStatus(),
)
