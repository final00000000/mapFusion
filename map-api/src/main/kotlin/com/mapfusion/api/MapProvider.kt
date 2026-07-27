package com.mapfusion.api

import android.content.Context
import com.mapfusion.api.capability.Capability
import com.mapfusion.api.capability.Geocoder
import com.mapfusion.api.capability.DistrictSearcher
import com.mapfusion.api.capability.LocationClient
import com.mapfusion.api.capability.MapController
import com.mapfusion.api.capability.Navigator
import com.mapfusion.api.capability.PoiSearcher
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.capability.RoutePlanner
import com.mapfusion.api.capability.WeatherService

/**
 * 地图厂商聚合入口。业务方通过工厂拿到本接口的实现(百度或高德),
 * 再取用各项能力。所有能力都是「按需获取」——某能力返回 null 表示该厂商
 * 未提供或未实现,业务方应先 [supports] / [capabilities] 判断。
 *
 * 设计要点:
 * - 能力按小接口拆分,避免一个大接口塞满「这家有那家没有」的空方法;
 * - [locationClient]、[geocoder]、[poiSearcher]、[routePlanner]、[districtSearcher]
 *   和 [weatherService] 每次调用都返回独立、可单独销毁的实例,调用方负责调用其
 *   `destroy()`; [com.mapfusion.factory.MapFusionSession] 会缓存并统一释放由会话获取的实例;
 * - [createMapController] 创建的地图控件按生命周期由调用方释放,[navigator] 为无状态发起器,
 *   不要求调用方销毁;
 * - [rawProvider] 是逃生舱:业务方在需要厂商独有 API 时可强转拿到原生对象,
 *   代价是与该厂商耦合,应尽量局部使用。
 */
interface MapProvider {

    /** 当前厂商标识 */
    val provider: Provider

    /** 本厂商支持的能力集合 */
    fun capabilities(): Set<Capability>

    /** 便捷判断是否支持某能力 */
    fun supports(capability: Capability): Boolean = capabilities().contains(capability)

    // ---- 各项能力:不支持时返回 null ----

    /**
     * 地图控件能力。需要一个已 inflate 的地图容器 View 来创建控制器。
     * 传入的 [mapView] 是各适配器约定的原生 MapView(百度 MapView / 高德 MapView),
     * 通常由厂商专用的布局或工厂方法产生。
     */
    fun mapController(mapView: Any): MapController?

    /**
     * 由适配器创建厂商原生 MapView 并包装成统一控制器。
     * 业务代码优先使用本方法，完全不需要引用百度/高德的 View 类型。
     * [mapController] 保留给需要复用已存在原生 MapView 的高级场景。
     */
    fun createMapController(context: Context): MapController? = null

    /** 定位能力 */
    fun locationClient(): LocationClient?

    /** 地理编码能力 */
    fun geocoder(): Geocoder?

    /** POI 检索能力 */
    fun poiSearcher(): PoiSearcher?

    /** 路径规划能力 */
    fun routePlanner(): RoutePlanner?

    /** 行政区与边界检索。 */
    fun districtSearcher(): DistrictSearcher? = null

    /** 实时天气与天气预报。 */
    fun weatherService(): WeatherService? = null

    /** 导航能力 */
    fun navigator(): Navigator?

    /**
     * 逃生舱:返回底层厂商的原生对象(如百度的 SDKInitializer 上下文、
     * 高德的 MapsInitializer 句柄等),供访问未被抽象覆盖的独有能力。
     * 返回类型由各适配器文档说明。谨慎使用,会引入厂商耦合。
     */
    fun rawProvider(): Any?

    /** 释放本厂商持有的全局资源(在 App 退出或不再使用时调用) */
    fun destroy()
}
