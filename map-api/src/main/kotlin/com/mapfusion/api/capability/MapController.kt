package com.mapfusion.api.capability

import android.view.View
import com.mapfusion.api.model.CameraPosition
import com.mapfusion.api.model.CameraUpdate
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.CircleOptions
import com.mapfusion.api.model.GroundOverlayOptions
import com.mapfusion.api.model.HeatMapOptions
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.LatLngBounds
import com.mapfusion.api.model.MapMarker
import com.mapfusion.api.model.MapMultiPointOverlay
import com.mapfusion.api.model.MapCircle
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapOverlay
import com.mapfusion.api.model.MapGroundOverlay
import com.mapfusion.api.model.MapHeatMap
import com.mapfusion.api.model.MapPolygon
import com.mapfusion.api.model.MapPolyline
import com.mapfusion.api.model.MapRouteOverlay
import com.mapfusion.api.model.MapSnapshot
import com.mapfusion.api.model.MapTextOverlay
import com.mapfusion.api.model.MapTileOverlay
import com.mapfusion.api.model.MapType
import com.mapfusion.api.model.MapUiOptions
import com.mapfusion.api.model.MarkerOptions
import com.mapfusion.api.model.MultiPointItem
import com.mapfusion.api.model.MultiPointOverlayOptions
import com.mapfusion.api.model.PolygonOptions
import com.mapfusion.api.model.PolylineOptions
import com.mapfusion.api.model.RouteOverlayOptions
import com.mapfusion.api.model.RoutePath
import com.mapfusion.api.model.RouteRequest
import com.mapfusion.api.model.TextOverlayOptions
import com.mapfusion.api.model.TileOverlayOptions
import com.mapfusion.api.model.RequestHandle
import com.mapfusion.api.model.DefaultMapRouteOverlay
import com.mapfusion.api.model.at
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult

/**
 * 地图控件能力:承载地图 View、相机控制、标记管理与交互事件。
 *
 * 生命周期方法必须由宿主 Activity/Fragment 转发,适配器内部转调各家
 * MapView 的对应生命周期(百度 MapView / 高德 MapView 均有 onResume 等)。
 */
interface MapController {

    /** 返回可加入布局的地图 View。业务方拿到后 addView 即可。 */
    val view: View

    // ---- 生命周期转发 ----
    fun onCreate(savedState: android.os.Bundle?)
    fun onResume()
    fun onPause()
    fun onDestroy()
    fun onSaveInstanceState(outState: android.os.Bundle)
    fun onLowMemory()

    // ---- 相机 ----
    fun moveCamera(update: CameraUpdate)
    fun getCameraPosition(): CameraPosition
    fun setCameraBounds(bounds: LatLngBounds?)
    fun setZoomRange(minZoom: Float, maxZoom: Float)

    /**
     * 将一组点完整展示在当前地图视野内。单点使用 [singlePointZoom] 居中，多点使用
     * 厂商原生 bounds 相机更新；所有点必须声明且使用相同坐标系。
     */
    fun fitPoints(
        points: List<LatLng>,
        paddingPixels: Int = 0,
        singlePointZoom: Float = 16f,
        animated: Boolean = true,
        durationMs: Int = 300,
    ) {
        require(paddingPixels >= 0) { "paddingPixels 不能小于 0" }
        require(singlePointZoom.isFinite()) { "singlePointZoom 必须是有限值" }
        require(durationMs >= 0) { "durationMs 不能小于 0" }
        val bounds = LatLngBounds.fromPoints(points)
        moveCamera(
            if (points.size == 1) {
                CameraUpdate(
                    target = points.first(),
                    zoom = singlePointZoom,
                    animated = animated,
                    durationMs = durationMs,
                )
            } else {
                CameraUpdate(
                    bounds = bounds,
                    paddingPixels = paddingPixels,
                    animated = animated,
                    durationMs = durationMs,
                )
            },
        )
    }

    // ---- 覆盖物 ----
    fun addMarker(options: MarkerOptions): MapMarker
    /** 使用厂商原生高性能海量点图层，不会退化为循环创建普通 Marker。 */
    fun addMultiPointOverlay(options: MultiPointOverlayOptions): MapMultiPointOverlay =
        throw UnsupportedOperationException("当前地图提供商未实现原生海量点图层")
    fun addPolyline(options: PolylineOptions): MapPolyline
    fun addPolygon(options: PolygonOptions): MapPolygon
    fun addCircle(options: CircleOptions): MapCircle
    fun addGroundOverlay(options: GroundOverlayOptions): MapGroundOverlay
    fun addText(options: TextOverlayOptions): MapTextOverlay
    fun addTileOverlay(options: TileOverlayOptions): MapTileOverlay
    fun addHeatMap(options: HeatMapOptions): MapHeatMap

    /**
     * 一次性绘制规划路线、起点和终点。起终点位置使用 [request] 的精确坐标，图标与
     * 线样式由 [options] 统一配置。返回的组合句柄可整体移除，不需要业务保存三个句柄。
     */
    fun addRoute(
        request: RouteRequest,
        path: RoutePath,
        options: RouteOverlayOptions = RouteOverlayOptions(),
    ): MapRouteOverlay = addRouteInternal(request.origin, request.destination, path, options)

    /** 路线没有对应请求时，以路线几何的首尾点作为起终点。 */
    fun addRoute(
        path: RoutePath,
        options: RouteOverlayOptions = RouteOverlayOptions(),
    ): MapRouteOverlay {
        val points = path.displayPoints()
        return addRouteInternal(points.first(), points.last(), path, options)
    }

    fun clearMarkers()
    fun clearOverlays()

    // ---- 图层/控件开关 ----
    /**
     * 仅控制厂商原生定位图层开关，不会把 [LocationClient] 的结果自动写入地图。
     * 普通业务优先使用 [LocationDisplay] 获得统一图标、精度圈和相机跟随。
     */
    fun setMyLocationEnabled(enabled: Boolean)
    fun setTrafficEnabled(enabled: Boolean)
    fun setZoomControlsEnabled(enabled: Boolean)
    fun setBuildingsEnabled(enabled: Boolean)
    fun setIndoorEnabled(enabled: Boolean)
    fun setMapPoiEnabled(enabled: Boolean)
    /**
     * 返回当前适配器可真实应用的地图类型。不能表达的类型必须排除，不能以 NORMAL 代替。
     * 自定义适配器若支持额外类型，应覆盖此方法并在 [applyMapType] 中自行处理。
     */
    fun supportedMapTypes(): Set<MapType> = setOf(MapType.NORMAL)

    fun supportsMapType(type: MapType): Boolean = type in supportedMapTypes()

    /**
     * 应用地图类型并回读实际类型。失败时返回统一错误，旧的无返回值 API 仍保留给二进制兼容调用方。
     */
    fun applyMapType(type: MapType): MapResult<MapType> {
        if (!supportsMapType(type)) {
            return MapResult.Failure(
                MapError(ErrorType.UNSUPPORTED, "当前地图提供商不支持地图类型 $type"),
            )
        }
        return try {
            @Suppress("DEPRECATION")
            setMapType(type)
            val applied = getMapType()
            if (applied == type) {
                MapResult.Success(applied)
            } else {
                MapResult.Failure(
                    MapError(ErrorType.UNSUPPORTED, "地图类型 $type 未被当前地图实际应用（实际为 $applied）"),
                )
            }
        } catch (error: IllegalArgumentException) {
            MapResult.Failure(MapError(ErrorType.INVALID_PARAM, error.message.orEmpty(), cause = error))
        } catch (error: Throwable) {
            MapResult.Failure(MapError(ErrorType.UNKNOWN, error.message.orEmpty(), cause = error))
        }
    }

    /** @deprecated 使用 [applyMapType] 获取明确的成功/失败结果。 */
    @Deprecated("使用 applyMapType(type) 获取明确结果", ReplaceWith("applyMapType(type)"))
    fun setMapType(type: MapType)
    fun getMapType(): MapType
    fun setUiOptions(options: MapUiOptions)
    fun snapshot(callback: MapCallback<MapSnapshot>): RequestHandle =
        snapshot(AsyncCallOptions(), callback)

    fun snapshot(
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<MapSnapshot>,
    ): RequestHandle

    // ---- 交互事件 ----
    fun setOnMapClickListener(listener: ((LatLng) -> Unit)?)
    fun setOnMapLongClickListener(listener: ((LatLng) -> Unit)?)
    fun setOnMarkerClickListener(listener: ((MapMarker) -> Boolean)?)
    /** 点击返回所属图层及统一业务点；返回 true 表示事件已消费。 */
    fun setOnMultiPointClickListener(
        listener: ((MapMultiPointOverlay, MultiPointItem) -> Boolean)?,
    ) {
        if (listener != null) {
            throw UnsupportedOperationException("当前地图提供商未实现原生海量点点击")
        }
    }
    fun setOnOverlayClickListener(listener: ((MapOverlay) -> Boolean)?)
    fun setOnCameraIdleListener(listener: ((CameraPosition) -> Unit)?)
    fun setOnMapLoadedListener(listener: (() -> Unit)?)

    private fun addRouteInternal(
        origin: LatLng,
        destination: LatLng,
        path: RoutePath,
        options: RouteOverlayOptions,
    ): MapRouteOverlay {
        require(options.lineWidth.isFinite() && options.lineWidth > 0f) { "路线宽度必须大于 0" }
        val points = path.displayPoints()
        var line: MapPolyline? = null
        var start: MapMarker? = null
        var end: MapMarker? = null
        try {
            line = addPolyline(
                PolylineOptions(
                    points = points,
                    width = options.lineWidth,
                    color = options.lineColor,
                    dotted = options.lineDotted,
                    geodesic = options.lineGeodesic,
                    clickable = options.lineClickable,
                    visible = options.visible,
                    zIndex = options.lineZIndex,
                    tag = options.lineTag,
                ),
            )
            start = options.startMarker?.let { addMarker(it.at(origin)) }
            end = options.endMarker?.let { addMarker(it.at(destination)) }
            return DefaultMapRouteOverlay(path, start, end, line)
        } catch (error: Throwable) {
            listOfNotNull(start, end, line).forEach { overlay ->
                runCatching { overlay.remove() }.exceptionOrNull()?.let { cleanupError ->
                    if (cleanupError !== error) error.addSuppressed(cleanupError)
                }
            }
            throw error
        }
    }
}

private fun RoutePath.displayPoints(): List<LatLng> =
    polyline.ifEmpty { steps.flatMap { it.polyline } }
        .also { require(it.size >= 2) { "路线至少需要 2 个可绘制点" } }
