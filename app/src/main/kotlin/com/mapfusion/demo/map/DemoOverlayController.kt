package com.mapfusion.demo.map

import com.mapfusion.api.capability.MapController
import com.mapfusion.api.model.CircleOptions
import com.mapfusion.api.model.GroundOverlayOptions
import com.mapfusion.api.model.HeatMapOptions
import com.mapfusion.api.model.MapOverlay
import com.mapfusion.api.model.MapMultiPointOverlay
import com.mapfusion.api.model.MapTileOverlay
import com.mapfusion.api.model.MapHeatMap
import com.mapfusion.api.model.MapRouteOverlay
import com.mapfusion.api.model.MarkerOptions
import com.mapfusion.api.model.MultiPointOverlayOptions
import com.mapfusion.api.model.PolygonOptions
import com.mapfusion.api.model.PolylineOptions
import com.mapfusion.api.model.RouteOverlayOptions
import com.mapfusion.api.model.RoutePath
import com.mapfusion.api.model.RouteRequest
import com.mapfusion.api.model.TextOverlayOptions
import com.mapfusion.api.model.TileOverlayOptions

/** Demo 自有句柄集合；定位、导航和轨迹组件使用原始控制器，不登记在此集合。 */
internal class DemoOverlayStore {
    /** Demo 自有句柄的移除闭包列表；clear 先清空再逐项调用，不包含 Session 组件的资源。 */
    private val removals = mutableListOf<() -> Unit>()
    /** 登记常规覆盖物句柄并原样返回，清理时只调用它的 remove。 */
    fun <T : MapOverlay> track(overlay: T): T = overlay.also { removals += { it.remove() } }
    /** 海量点不继承 MapOverlay，独立登记真实海量点句柄。 */
    fun track(overlay: MapMultiPointOverlay): MapMultiPointOverlay = overlay.also { removals += { it.remove() } }
    /** 登记瓦片句柄，不伪造常规覆盖物的层级或可见属性。 */
    fun track(overlay: MapTileOverlay): MapTileOverlay = overlay.also { removals += { it.remove() } }
    /** 登记热力图句柄，保留与普通覆盖物不同的 SDK 契约。 */
    fun track(overlay: MapHeatMap): MapHeatMap = overlay.also { removals += { it.remove() } }
    /** 路线组合句柄单独登记，由它释放自己拥有的起终点和折线。 */
    fun trackRoute(route: MapRouteOverlay): MapRouteOverlay = route.also { removals += { it.remove() } }

    /** 先清集合再逐项移除；单项失败继续清理其他句柄，最终合并抛出异常。 */
    fun clear() {
        val pending = removals.toList()
        removals.clear()
        var failure: Throwable? = null
        pending.forEach { remove ->
            try { remove() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}

/** Demo 专用控制器装饰器，仅拦截新增句柄登记，其他能力全部委托原控制器。 */
internal class DemoOverlayController(
    /** 当前 Session 的原始控制器；装饰器只委托/登记，不拥有其生命周期或重新创建地图。 */
    private val delegate: MapController,
    /** 本页 Demo 自有句柄集合；只登记从本装饰器返回的覆盖物，不清定位/导航/轨迹组件。 */
    private val store: DemoOverlayStore,
) : MapController by delegate {
    /** 创建 Marker 后登记 Demo 所有权，不替换或清除 Session 组件句柄。 */
    override fun addMarker(options: MarkerOptions) = store.track(delegate.addMarker(options))
    /** 创建并登记原生海量点图层，保持 SDK 的独立返回类型。 */
    override fun addMultiPointOverlay(options: MultiPointOverlayOptions) = store.track(delegate.addMultiPointOverlay(options))
    /** 创建并登记 Demo 折线，样式参数不做隐式转换。 */
    override fun addPolyline(options: PolylineOptions) = store.track(delegate.addPolyline(options))
    /** 创建并登记多边形，边界与填充仍由厂商适配器处理。 */
    override fun addPolygon(options: PolygonOptions) = store.track(delegate.addPolygon(options))
    /** 创建并登记圆形覆盖物，移除只影响该句柄。 */
    override fun addCircle(options: CircleOptions) = store.track(delegate.addCircle(options))
    /** 创建并登记地面图，不在装饰器内保存原生图片或 View。 */
    override fun addGroundOverlay(options: GroundOverlayOptions) = store.track(delegate.addGroundOverlay(options))
    /** 创建并登记文字覆盖物，保留统一文本参数。 */
    override fun addText(options: TextOverlayOptions) = store.track(delegate.addText(options))
    /** 创建并登记瓦片图层，缓存生命周期仍由瓦片句柄负责。 */
    override fun addTileOverlay(options: TileOverlayOptions) = store.track(delegate.addTileOverlay(options))
    /** 创建并登记热力图，不将其强制转成普通 MapOverlay。 */
    override fun addHeatMap(options: HeatMapOptions) = store.track(delegate.addHeatMap(options))
    /** 按原始请求和选定方案创建路线组合，并登记整体释放句柄。 */
    override fun addRoute(request: RouteRequest, path: RoutePath, options: RouteOverlayOptions) =
        store.trackRoute(delegate.addRoute(request, path, options))
    /** 兼容只传方案的路线入口，同样登记整体所有权。 */
    override fun addRoute(path: RoutePath, options: RouteOverlayOptions) = store.trackRoute(delegate.addRoute(path, options))
}
