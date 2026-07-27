package com.mapfusion.amap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.View
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.GroundOverlay
import com.amap.api.maps.model.HeatmapTileProvider
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MultiPointItem as AmapMultiPointItem
import com.amap.api.maps.model.MultiPointOverlay as NativeAmapMultiPointOverlay
import com.amap.api.maps.model.MultiPointOverlayOptions as NativeAmapMultiPointOverlayOptions
import com.amap.api.maps.model.Polygon
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.Text
import com.amap.api.maps.model.Tile
import com.amap.api.maps.model.TileOverlay
import com.amap.api.maps.model.TileProvider
import com.amap.api.maps.model.WeightedLatLng
import com.mapfusion.api.async.AsyncRuntime
import com.mapfusion.api.capability.MapController
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.CameraPosition
import com.mapfusion.api.model.CameraUpdate
import com.mapfusion.api.model.CircleOptions
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.GroundOverlayOptions
import com.mapfusion.api.model.HeatMapOptions
import com.mapfusion.api.model.HeatPoint
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.LatLngBounds
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapCircle
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapGroundOverlay
import com.mapfusion.api.model.MapHeatMap
import com.mapfusion.api.model.MapImage
import com.mapfusion.api.model.MapMarker
import com.mapfusion.api.model.MapMultiPointOverlay
import com.mapfusion.api.model.MapOverlay
import com.mapfusion.api.model.MapPolygon
import com.mapfusion.api.model.MapPolyline
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.MapSnapshot
import com.mapfusion.api.model.MapTextOverlay
import com.mapfusion.api.model.MapTileOverlay
import com.mapfusion.api.model.MapType
import com.mapfusion.api.model.MapUiOptions
import com.mapfusion.api.model.MarkerIcon
import com.mapfusion.api.model.MarkerOptions
import com.mapfusion.api.model.MultiPointItem
import com.mapfusion.api.model.MultiPointOverlayOptions
import com.mapfusion.api.model.PolygonOptions
import com.mapfusion.api.model.PolylineOptions
import com.mapfusion.api.model.RequestHandle
import com.mapfusion.api.model.TextHorizontalAlignment
import com.mapfusion.api.model.TextOverlayOptions
import com.mapfusion.api.model.TextVerticalAlignment
import com.mapfusion.api.model.TileOverlayOptions
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap

/** 高德 MapView / AMap 的真实统一适配。 */
internal class AmapMapController(
    private val mapView: MapView,
) : MapController {

    private val map: AMap = mapView.map
    private val asyncRuntime = AsyncRuntime.DEFAULT
    private val snapshotRequests = NativeRequestRegistry<Any> { }
    private val overlays = IdentityHashMap<Any, MapOverlay>()
    private val multiPointOverlays = mutableSetOf<AmapMultiPointOverlay>()
    private val clickableShapes = mutableSetOf<Any>()
    private val tileOverlays = mutableSetOf<MapTileOverlay>()
    private val heatMaps = mutableSetOf<MapHeatMap>()
    private var mapClickListener: ((LatLng) -> Unit)? = null
    private var overlayClickListener: ((MapOverlay) -> Boolean)? = null
    private var destroyed = false

    override val view: View = mapView

    internal val nativeMap: AMap get() = map
    internal val nativeMapView: MapView get() = mapView

    override fun onCreate(savedState: Bundle?) = mapView.onCreate(savedState)
    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onDestroy() {
        if (destroyed) return
        destroyed = true
        try {
            runCatching { setOnMapClickListener(null) }
            runCatching { setOnMapLongClickListener(null) }
            runCatching { setOnMarkerClickListener(null) }
            runCatching { setOnMultiPointClickListener(null) }
            runCatching { setOnOverlayClickListener(null) }
            runCatching { setOnCameraIdleListener(null) }
            runCatching { setOnMapLoadedListener(null) }
            snapshotRequests.destroy()
            // 先释放已登记的句柄，避免删除后的图形残留在点击命中集合中。
            overlays.values.toList().forEach { runCatching { it.remove() } }
            multiPointOverlays.toList().forEach { runCatching { it.remove() } }
            tileOverlays.toList().forEach { runCatching { it.remove() } }
            heatMaps.toList().forEach { runCatching { it.remove() } }
            runCatching { map.clear() }
            mapView.onDestroy()
        } finally {
            overlays.clear()
            multiPointOverlays.clear()
            clickableShapes.clear()
            tileOverlays.clear()
            heatMaps.clear()
            mapClickListener = null
            overlayClickListener = null
        }
    }
    override fun onSaveInstanceState(outState: Bundle) = mapView.onSaveInstanceState(outState)
    override fun onLowMemory() = mapView.onLowMemory()

    override fun moveCamera(update: CameraUpdate) {
        val nativeUpdate = update.bounds?.let { bounds ->
            CameraUpdateFactory.newLatLngBounds(bounds.toAmap(), update.paddingPixels)
        } ?: CameraUpdateFactory.newCameraPosition(
            com.amap.api.maps.model.CameraPosition.builder(map.cameraPosition).apply {
                update.target?.let { target(it.toAmap()) }
                update.zoom?.let(::zoom)
                update.bearing?.let(::bearing)
                update.tilt?.let(::tilt)
            }.build(),
        )
        if (update.animated) {
            map.animateCamera(nativeUpdate, update.durationMs.coerceAtLeast(0).toLong(), null)
        } else {
            map.moveCamera(nativeUpdate)
        }
    }

    override fun getCameraPosition(): CameraPosition = map.cameraPosition.let { position ->
        CameraPosition(
            target = position.target.toFusion(CoordType.GCJ02),
            zoom = position.zoom,
            bearing = position.bearing,
            tilt = position.tilt,
        )
    }

    override fun setCameraBounds(bounds: LatLngBounds?) {
        map.setMapStatusLimits(bounds?.toAmap())
    }

    override fun setZoomRange(minZoom: Float, maxZoom: Float) {
        require(minZoom <= maxZoom) { "minZoom 不能大于 maxZoom" }
        map.minZoomLevel = minZoom
        map.maxZoomLevel = maxZoom
    }

    override fun addMarker(options: MarkerOptions): MapMarker {
        val native = map.addMarker(
            com.amap.api.maps.model.MarkerOptions()
                .position(options.position.toAmap())
                .title(options.title)
                .snippet(options.snippet)
                .icon(options.icon.toAmapDescriptor())
                .anchor(options.anchorU, options.anchorV)
                .draggable(options.draggable)
                .rotateAngle(options.rotation)
                .setFlat(options.flat)
                .alpha(options.alpha)
                .visible(options.visible)
                .zIndex(options.zIndex),
        )
        return AmapMarker(native, options.tag) { unregisterOverlay(it) }
            .also { overlays[native] = it }
    }

    override fun addMultiPointOverlay(options: MultiPointOverlayOptions): MapMultiPointOverlay {
        val items = options.items.validatedMultiPointItems()
        val nativeItems = items.map(MultiPointItem::toAmapMultiPointItem)
        val nativeOptions = NativeAmapMultiPointOverlayOptions()
            .anchor(options.anchorU, options.anchorV)
            .icon(options.icon.toAmapDescriptor())
            .also {
                it.setMultiPointItems(nativeItems)
                it.setEnable(options.visible)
            }
        val native = map.addMultiPointOverlay(nativeOptions)
        return AmapMultiPointOverlay(
            native = native,
            initialItems = items,
            initialNativeItems = nativeItems,
            initialVisible = options.visible,
            initialClickable = options.clickable,
            initialTag = options.tag,
            onRemoved = { multiPointOverlays.remove(it) },
        ).also(multiPointOverlays::add)
    }

    override fun addPolyline(options: PolylineOptions): MapPolyline {
        require(options.points.size >= 2) { "折线至少需要 2 个点" }
        val native = map.addPolyline(
            com.amap.api.maps.model.PolylineOptions()
                .addAll(options.points.map(LatLng::toAmap))
                .width(options.width)
                .color(options.color)
                .setDottedLine(options.dotted)
                .geodesic(options.geodesic)
                .visible(options.visible)
                .zIndex(options.zIndex),
        )
        if (options.clickable) clickableShapes += native
        return AmapPolyline(native, options.tag) { unregisterOverlay(it) }
            .also { overlays[native] = it }
    }

    override fun addPolygon(options: PolygonOptions): MapPolygon {
        require(options.points.size >= 3) { "多边形至少需要 3 个点" }
        val native = map.addPolygon(
            com.amap.api.maps.model.PolygonOptions()
                .addAll(options.points.map(LatLng::toAmap))
                .strokeWidth(options.strokeWidth)
                .strokeColor(options.strokeColor)
                .fillColor(options.fillColor)
                .visible(options.visible)
                .zIndex(options.zIndex),
        )
        if (options.clickable) clickableShapes += native
        return AmapPolygon(native, options.tag) { unregisterOverlay(it) }
            .also { overlays[native] = it }
    }

    override fun addCircle(options: CircleOptions): MapCircle {
        require(options.radiusMeters > 0) { "圆半径必须大于 0" }
        val native = map.addCircle(
            com.amap.api.maps.model.CircleOptions()
                .center(options.center.toAmap())
                .radius(options.radiusMeters)
                .strokeWidth(options.strokeWidth)
                .strokeColor(options.strokeColor)
                .fillColor(options.fillColor)
                .visible(options.visible)
                .zIndex(options.zIndex),
        )
        if (options.clickable) clickableShapes += native
        return AmapCircle(native, options.tag) { unregisterOverlay(it) }
            .also { overlays[native] = it }
    }

    override fun addGroundOverlay(options: GroundOverlayOptions): MapGroundOverlay {
        val nativeOptions = com.amap.api.maps.model.GroundOverlayOptions()
            .image(options.image.toAmapDescriptor())
            .anchor(options.anchorU, options.anchorV)
            .transparency(options.transparency.coerceIn(0f, 1f))
            .visible(options.visible)
            .zIndex(options.zIndex)
        val bounds = options.bounds
        if (bounds != null) {
            nativeOptions.positionFromBounds(bounds.toAmap())
        } else {
            val position = requireNotNull(options.position) { "GroundOverlay 必须提供 position 或 bounds" }
            val width = requireNotNull(options.widthMeters) { "使用 position 时必须提供 widthMeters" }
            options.heightMeters?.let { nativeOptions.position(position.toAmap(), width, it) }
                ?: nativeOptions.position(position.toAmap(), width)
        }
        val native = map.addGroundOverlay(nativeOptions)
        return AmapGroundOverlay(native, options.tag) { unregisterOverlay(it) }
            .also { overlays[native] = it }
    }

    override fun addText(options: TextOverlayOptions): MapTextOverlay {
        val horizontal = when (options.horizontalAlignment) {
            TextHorizontalAlignment.LEFT -> Text.ALIGN_LEFT
            TextHorizontalAlignment.CENTER -> Text.ALIGN_CENTER_HORIZONTAL
            TextHorizontalAlignment.RIGHT -> Text.ALIGN_RIGHT
        }
        val vertical = when (options.verticalAlignment) {
            TextVerticalAlignment.TOP -> Text.ALIGN_TOP
            TextVerticalAlignment.CENTER -> Text.ALIGN_CENTER_VERTICAL
            TextVerticalAlignment.BOTTOM -> Text.ALIGN_BOTTOM
        }
        val native = map.addText(
            com.amap.api.maps.model.TextOptions()
                .text(options.text)
                .position(options.position.toAmap())
                .fontSize(options.fontSizePixels)
                .fontColor(options.fontColor)
                .backgroundColor(options.backgroundColor)
                .rotate(options.rotation)
                .align(horizontal, vertical)
                .visible(options.visible)
                .zIndex(options.zIndex)
                .setObject(options.tag),
        )
        return AmapTextOverlay(native, options.tag) { unregisterOverlay(it) }
            .also { overlays[native] = it }
    }

    override fun addTileOverlay(options: TileOverlayOptions): MapTileOverlay {
        val normalizedOptions = options.copy(bounds = options.bounds?.toAmapCoordinateBounds())
        val provider = object : TileProvider {
            override fun getTile(x: Int, y: Int, zoom: Int): Tile =
                normalizedOptions.loadTile(x, y, zoom)?.let { tile ->
                    Tile.obtain(tile.width, tile.height, tile.data)
                } ?: TileProvider.NO_TILE

            override fun getTileWidth(): Int = normalizedOptions.tileWidth
            override fun getTileHeight(): Int = normalizedOptions.tileHeight
        }
        val native = map.addTileOverlay(
            com.amap.api.maps.model.TileOverlayOptions()
                .tileProvider(provider)
                .memoryCacheEnabled(options.memoryCacheEnabled)
                .diskCacheEnabled(options.diskCacheEnabled)
                .visible(options.visible)
                .zIndex(options.zIndex),
        )
        return AmapTileOverlay(native) { tileOverlays.remove(it) }
            .also(tileOverlays::add)
    }

    override fun addHeatMap(options: HeatMapOptions): MapHeatMap {
        require(options.points.isNotEmpty()) { "热力图至少需要一个点" }
        require(options.minZoom <= options.maxZoom) { "热力图 minZoom 不能大于 maxZoom" }
        return AmapHeatMap(map, options) { heatMaps.remove(it) }
            .also(heatMaps::add)
    }

    override fun clearMarkers() {
        overlays.values.filterIsInstance<MapMarker>().toList().forEach { runCatching { it.remove() } }
    }

    override fun clearOverlays() {
        try {
            overlays.values.toList().forEach { runCatching { it.remove() } }
            multiPointOverlays.toList().forEach { runCatching { it.remove() } }
            tileOverlays.toList().forEach { runCatching { it.remove() } }
            heatMaps.toList().forEach { runCatching { it.remove() } }
            map.clear()
        } finally {
            overlays.clear()
            multiPointOverlays.clear()
            clickableShapes.clear()
            tileOverlays.clear()
            heatMaps.clear()
        }
    }

    private fun unregisterOverlay(native: Any) {
        overlays.remove(native)
        clickableShapes.remove(native)
    }

    override fun setMyLocationEnabled(enabled: Boolean) = map.setMyLocationEnabled(enabled)
    override fun setTrafficEnabled(enabled: Boolean) = map.setTrafficEnabled(enabled)
    override fun setZoomControlsEnabled(enabled: Boolean) = map.uiSettings.setZoomControlsEnabled(enabled)
    override fun setBuildingsEnabled(enabled: Boolean) = map.showBuildings(enabled)
    override fun setIndoorEnabled(enabled: Boolean) = map.showIndoorMap(enabled)
    override fun setMapPoiEnabled(enabled: Boolean) = map.showMapText(enabled)

    override fun supportedMapTypes(): Set<MapType> = AMAP_SUPPORTED_MAP_TYPES

    @Deprecated("使用 applyMapType(type) 获取明确结果", ReplaceWith("applyMapType(type)"))
    override fun setMapType(type: MapType) {
        map.mapType = when (type) {
            MapType.NORMAL -> AMap.MAP_TYPE_NORMAL
            MapType.SATELLITE -> AMap.MAP_TYPE_SATELLITE
            MapType.NIGHT -> AMap.MAP_TYPE_NIGHT
            MapType.NAVIGATION -> AMap.MAP_TYPE_NAVI
            MapType.NONE -> throw UnsupportedOperationException("高德地图不支持地图类型 $type")
        }
    }

    override fun getMapType(): MapType = when (map.mapType) {
        AMap.MAP_TYPE_SATELLITE -> MapType.SATELLITE
        AMap.MAP_TYPE_NIGHT, AMap.MAP_TYPE_NAVI_NIGHT -> MapType.NIGHT
        AMap.MAP_TYPE_NAVI -> MapType.NAVIGATION
        else -> MapType.NORMAL
    }

    override fun setUiOptions(options: MapUiOptions) {
        map.uiSettings.apply {
            setZoomControlsEnabled(options.zoomControlsEnabled)
            setScaleControlsEnabled(options.scaleControlsEnabled)
            setCompassEnabled(options.compassEnabled)
            setMyLocationButtonEnabled(options.myLocationButtonEnabled)
            setScrollGesturesEnabled(options.scrollGesturesEnabled)
            setZoomGesturesEnabled(options.zoomGesturesEnabled)
            setRotateGesturesEnabled(options.rotateGesturesEnabled)
            setTiltGesturesEnabled(options.tiltGesturesEnabled)
        }
    }

    override fun snapshot(
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<MapSnapshot>,
    ): RequestHandle {
        if (destroyed) {
            return asyncRuntime.createRequest(callback, asyncOptions).also {
                it.failure(MapError(ErrorType.INVALID_PARAM, "高德地图控制器已销毁"))
            }
        }
        val token = Any()
        val request = asyncRuntime.createRequest(
            callback = callback,
            options = asyncOptions,
            terminalAction = Runnable { snapshotRequests.release(token) },
        )
        if (!snapshotRequests.register(token, request)) {
            request.dispose()
            return request
        }
        runCatching {
            map.getMapScreenShot(
                object : AMap.OnMapScreenShotListener {
                    override fun onMapScreenShot(bitmap: Bitmap?) = deliver(bitmap)
                    override fun onMapScreenShot(bitmap: Bitmap?, status: Int) = deliver(bitmap)

                    private fun deliver(bitmap: Bitmap?) {
                        val result = runCatching {
                            requireNotNull(bitmap) { "高德地图截图失败" }
                            val output = ByteArrayOutputStream()
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "高德地图截图编码失败" }
                            MapResult.Success(MapSnapshot(output.toByteArray(), bitmap.width, bitmap.height))
                        }.getOrElse {
                            MapResult.Failure(MapError(ErrorType.UNKNOWN, it.message.orEmpty(), cause = it))
                        }
                        snapshotRequests.complete(token, result)
                    }
                },
            )
        }.onFailure {
            request.failure(MapError(ErrorType.UNKNOWN, "高德地图截图启动失败：${it.message.orEmpty()}", cause = it))
        }
        return request
    }

    override fun setOnMapClickListener(listener: ((LatLng) -> Unit)?) {
        mapClickListener = listener
        updateMapClickDispatcher()
    }

    override fun setOnMapLongClickListener(listener: ((LatLng) -> Unit)?) {
        map.setOnMapLongClickListener(listener?.let { callback ->
            AMap.OnMapLongClickListener { point -> callback(point.toFusion(CoordType.GCJ02)) }
        })
    }

    override fun setOnMarkerClickListener(listener: ((MapMarker) -> Boolean)?) {
        map.setOnMarkerClickListener(listener?.let { callback ->
            AMap.OnMarkerClickListener { marker ->
                (overlays[marker] as? MapMarker)?.let(callback) ?: false
            }
        })
    }

    override fun setOnMultiPointClickListener(
        listener: ((MapMultiPointOverlay, MultiPointItem) -> Boolean)?,
    ) {
        map.setOnMultiPointClickListener(listener?.let { callback ->
            AMap.OnMultiPointClickListener { nativeItem ->
                val match = multiPointOverlays.firstNotNullOfOrNull { overlay ->
                    if (overlay.clickable) overlay.itemFor(nativeItem)?.let { overlay to it } else null
                }
                match?.let { (overlay, item) -> callback(overlay, item) } ?: false
            }
        })
    }

    override fun setOnOverlayClickListener(listener: ((MapOverlay) -> Boolean)?) {
        overlayClickListener = listener
        map.setOnPolylineClickListener(listener?.let { callback ->
            AMap.OnPolylineClickListener { line -> overlays[line]?.let(callback) }
        })
        updateMapClickDispatcher()
    }

    override fun setOnCameraIdleListener(listener: ((CameraPosition) -> Unit)?) {
        map.setOnCameraChangeListener(listener?.let { callback ->
            object : AMap.OnCameraChangeListener {
                override fun onCameraChange(position: com.amap.api.maps.model.CameraPosition) = Unit
                override fun onCameraChangeFinish(position: com.amap.api.maps.model.CameraPosition) {
                    callback(
                        CameraPosition(
                            position.target.toFusion(CoordType.GCJ02),
                            position.zoom,
                            position.bearing,
                            position.tilt,
                        ),
                    )
                }
            }
        })
    }

    override fun setOnMapLoadedListener(listener: (() -> Unit)?) {
        map.setOnMapLoadedListener(listener?.let { callback ->
            AMap.OnMapLoadedListener { callback() }
        })
    }

    private fun updateMapClickDispatcher() {
        val mapCallback = mapClickListener
        val overlayCallback = overlayClickListener
        if (mapCallback == null && overlayCallback == null) {
            map.setOnMapClickListener(null)
            return
        }
        map.setOnMapClickListener { point ->
            if (overlayCallback != null) {
                val hit = clickableShapes
                    .sortedByDescending { native -> overlays[native]?.zIndex ?: 0f }
                    .firstOrNull { native ->
                        when (native) {
                            is Polygon -> native.contains(point)
                            is Circle -> native.contains(point)
                            else -> false
                        }
                    }
                if (hit != null && overlays[hit]?.let(overlayCallback) == true) {
                    return@setOnMapClickListener
                }
            }
            mapCallback?.invoke(point.toFusion(CoordType.GCJ02))
        }
    }

    private fun MarkerIcon.toAmapDescriptor(): BitmapDescriptor = when (this) {
        MarkerIcon.Default -> BitmapDescriptorFactory.defaultMarker()
        is MarkerIcon.Asset -> BitmapDescriptorFactory.fromAsset(assetName)
        is MarkerIcon.Resource -> BitmapDescriptorFactory.fromResource(resId)
        is MarkerIcon.Bytes -> BitmapDescriptorFactory.fromBitmap(
            BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: error("无法解码 MarkerIcon.Bytes"),
        )
    }

    private fun MapImage.toAmapDescriptor(): BitmapDescriptor = when (this) {
        is MapImage.Asset -> BitmapDescriptorFactory.fromAsset(assetName)
        is MapImage.Resource -> BitmapDescriptorFactory.fromResource(resId)
        is MapImage.Bytes -> BitmapDescriptorFactory.fromBitmap(
            BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: error("无法解码 MapImage.Bytes"),
        )
    }
}

private abstract class AmapOverlay<T : Any>(
    protected val native: T,
    initialTag: Any?,
    removeNative: (T) -> Unit,
    onRemoved: (T) -> Unit,
) : MapOverlay {
    final override var isRemoved: Boolean = false
        private set
    private var nativeRemover: ((T) -> Unit)? = removeNative
    private var unregister: ((T) -> Unit)? = onRemoved
    override var tag: Any? = initialTag
    final override fun remove() {
        if (isRemoved) return
        isRemoved = true
        try {
            nativeRemover?.invoke(native)
        } finally {
            nativeRemover = null
            unregister?.invoke(native)
            unregister = null
        }
    }
    override fun rawOverlay(): Any = native
}

private class AmapMarker(native: Marker, tag: Any?, onRemoved: (Marker) -> Unit) :
    AmapOverlay<Marker>(native, tag, { it.remove() }, onRemoved), MapMarker {
    override val id: String get() = native.id
    override var position: LatLng
        get() = native.position.toFusion(CoordType.GCJ02)
        set(value) = native.setPosition(value.toAmap())
    override var title: String?
        get() = native.title
        set(value) = native.setTitle(value)
    override var snippet: String?
        get() = native.snippet
        set(value) = native.setSnippet(value)
    override var rotation: Float
        get() = native.rotateAngle
        set(value) = native.setRotateAngle(value)
    override var alpha: Float
        get() = native.alpha
        set(value) = native.setAlpha(value.coerceIn(0f, 1f))
    override var flat: Boolean
        get() = native.isFlat
        set(value) = native.setFlat(value)
    override var visible: Boolean
        get() = native.isVisible
        set(value) = native.setVisible(value)
    override var zIndex: Float
        get() = native.zIndex
        set(value) = native.setZIndex(value)
    override fun showInfoWindow() = native.showInfoWindow()
    override fun hideInfoWindow() = native.hideInfoWindow()
}

private class AmapMultiPointOverlay(
    private val native: NativeAmapMultiPointOverlay,
    initialItems: List<MultiPointItem>,
    initialNativeItems: List<AmapMultiPointItem>,
    initialVisible: Boolean,
    initialClickable: Boolean,
    initialTag: Any?,
    onRemoved: (AmapMultiPointOverlay) -> Unit,
) : MapMultiPointOverlay {
    private val itemByNative = IdentityHashMap<AmapMultiPointItem, MultiPointItem>()
    private var unregister: ((AmapMultiPointOverlay) -> Unit)? = onRemoved
    override val id: String = "amap-multipoint-${System.identityHashCode(native)}"
    override var isRemoved: Boolean = false
        private set
    override var items: List<MultiPointItem> = initialItems.toList()
        set(value) {
            check(!isRemoved) { "海量点图层已删除" }
            val validated = value.validatedMultiPointItems()
            val nativeItems = validated.map(MultiPointItem::toAmapMultiPointItem)
            native.setItems(nativeItems)
            field = validated
            replaceItemMapping(nativeItems, validated)
        }
    override var visible: Boolean = initialVisible
        set(value) {
            check(!isRemoved) { "海量点图层已删除" }
            native.setEnable(value)
            field = value
        }
    override var clickable: Boolean = initialClickable
        set(value) {
            check(!isRemoved) { "海量点图层已删除" }
            field = value
        }
    override var tag: Any? = initialTag

    init {
        replaceItemMapping(initialNativeItems, items)
    }

    fun itemFor(nativeItem: AmapMultiPointItem): MultiPointItem? =
        itemByNative[nativeItem] ?: items.firstOrNull { it.id == nativeItem.customerId }

    override fun remove() {
        if (isRemoved) return
        isRemoved = true
        try {
            native.remove()
        } finally {
            itemByNative.clear()
            unregister?.invoke(this)
            unregister = null
        }
    }

    override fun rawOverlay(): Any = native

    private fun replaceItemMapping(
        nativeItems: List<AmapMultiPointItem>,
        unifiedItems: List<MultiPointItem>,
    ) {
        itemByNative.clear()
        nativeItems.zip(unifiedItems).forEach { (nativeItem, item) -> itemByNative[nativeItem] = item }
    }
}

private class AmapPolyline(native: Polyline, tag: Any?, onRemoved: (Polyline) -> Unit) :
    AmapOverlay<Polyline>(native, tag, { it.remove() }, onRemoved), MapPolyline {
    override val id: String get() = native.id
    override var points: List<LatLng>
        get() = native.points.map { it.toFusion(CoordType.GCJ02) }
        set(value) = native.setPoints(value.map(LatLng::toAmap))
    override var width: Float
        get() = native.width
        set(value) = native.setWidth(value)
    override var color: Int
        get() = native.color
        set(value) = native.setColor(value)
    override var visible: Boolean
        get() = native.isVisible
        set(value) = native.setVisible(value)
    override var zIndex: Float
        get() = native.zIndex
        set(value) = native.setZIndex(value)
}

private class AmapPolygon(native: Polygon, tag: Any?, onRemoved: (Polygon) -> Unit) :
    AmapOverlay<Polygon>(native, tag, { it.remove() }, onRemoved), MapPolygon {
    override val id: String get() = native.id
    override var points: List<LatLng>
        get() = native.points.map { it.toFusion(CoordType.GCJ02) }
        set(value) = native.setPoints(value.map(LatLng::toAmap))
    override var strokeWidth: Float
        get() = native.strokeWidth
        set(value) = native.setStrokeWidth(value)
    override var strokeColor: Int
        get() = native.strokeColor
        set(value) = native.setStrokeColor(value)
    override var fillColor: Int
        get() = native.fillColor
        set(value) = native.setFillColor(value)
    override var visible: Boolean
        get() = native.isVisible
        set(value) = native.setVisible(value)
    override var zIndex: Float
        get() = native.zIndex
        set(value) = native.setZIndex(value)
}

private class AmapCircle(native: Circle, tag: Any?, onRemoved: (Circle) -> Unit) :
    AmapOverlay<Circle>(native, tag, { it.remove() }, onRemoved), MapCircle {
    override val id: String get() = native.id
    override var center: LatLng
        get() = native.center.toFusion(CoordType.GCJ02)
        set(value) = native.setCenter(value.toAmap())
    override var radiusMeters: Double
        get() = native.radius
        set(value) = native.setRadius(value)
    override var strokeWidth: Float
        get() = native.strokeWidth
        set(value) = native.setStrokeWidth(value)
    override var strokeColor: Int
        get() = native.strokeColor
        set(value) = native.setStrokeColor(value)
    override var fillColor: Int
        get() = native.fillColor
        set(value) = native.setFillColor(value)
    override var visible: Boolean
        get() = native.isVisible
        set(value) = native.setVisible(value)
    override var zIndex: Float
        get() = native.zIndex
        set(value) = native.setZIndex(value)
}

private class AmapGroundOverlay(native: GroundOverlay, tag: Any?, onRemoved: (GroundOverlay) -> Unit) :
    AmapOverlay<GroundOverlay>(native, tag, { it.remove() }, onRemoved), MapGroundOverlay {
    override val id: String get() = native.id
    override var position: LatLng?
        get() = native.position?.toFusion(CoordType.GCJ02)
        set(value) {
            if (value != null) native.setPosition(value.toAmap())
        }
    override var bounds: LatLngBounds?
        get() = native.bounds?.let {
            LatLngBounds(
                it.southwest.toFusion(CoordType.GCJ02),
                it.northeast.toFusion(CoordType.GCJ02),
            )
        }
        set(value) {
            if (value != null) native.setPositionFromBounds(value.toAmap())
        }
    override var transparency: Float
        get() = native.transparency
        set(value) = native.setTransparency(value.coerceIn(0f, 1f))
    override var visible: Boolean
        get() = native.isVisible
        set(value) = native.setVisible(value)
    override var zIndex: Float
        get() = native.zIndex
        set(value) = native.setZIndex(value)
}

private class AmapTextOverlay(native: Text, tag: Any?, onRemoved: (Text) -> Unit) :
    AmapOverlay<Text>(native, tag, { it.remove() }, onRemoved), MapTextOverlay {
    override val id: String get() = native.id
    override var text: String
        get() = native.text
        set(value) = native.setText(value)
    override var position: LatLng
        get() = native.position.toFusion(CoordType.GCJ02)
        set(value) = native.setPosition(value.toAmap())
    override var fontSizePixels: Int
        get() = native.fontSize
        set(value) = native.setFontSize(value)
    override var fontColor: Int
        get() = native.fontColor
        set(value) = native.setFontColor(value)
    override var backgroundColor: Int
        get() = native.backgroundColor
        set(value) = native.setBackgroundColor(value)
    override var rotation: Float
        get() = native.rotate
        set(value) = native.setRotate(value)
    override var visible: Boolean
        get() = native.isVisible
        set(value) = native.setVisible(value)
    override var zIndex: Float
        get() = native.zIndex
        set(value) = native.setZIndex(value)
}

private class AmapTileOverlay(
    private val native: TileOverlay,
    onRemoved: (MapTileOverlay) -> Unit,
) : MapTileOverlay {
    private var removed = false
    private var unregister: ((MapTileOverlay) -> Unit)? = onRemoved
    override val id: String get() = native.id
    override fun clearCache() = native.clearTileCache()
    override fun remove() {
        if (removed) return
        removed = true
        try {
            native.remove()
        } finally {
            unregister?.invoke(this)
            unregister = null
        }
    }
    override fun rawOverlay(): Any = native
}

/**
 * 高德新版 HeatMapLayer 在部分设备/驱动组合上会触发 native vector 越界。
 * 这里使用官方 HeatmapTileProvider 生成热力瓦片，统一接口保持不变且兼容性更好。
 */
private class AmapHeatMap(
    private val map: AMap,
    options: HeatMapOptions,
    onRemoved: (MapHeatMap) -> Unit,
) : MapHeatMap {
    private val radiusPixels = options.radiusPixels.coerceIn(10, 50)
    private val opacity = options.opacity.coerceIn(0f, 1f)
    private val minZoom = options.minZoom
    private val maxZoom = options.maxZoom
    private var native = createOverlay(options.points, options.visible, options.zIndex)
    private var removed = false
    private var unregister: ((MapHeatMap) -> Unit)? = onRemoved

    override var points: List<HeatPoint> = options.points
        set(value) {
            check(!removed) { "热力图已删除" }
            require(value.isNotEmpty()) { "热力图至少需要一个点" }
            field = value
            val wasVisible = visible
            val oldZIndex = zIndex
            native.remove()
            native = createOverlay(value, wasVisible, oldZIndex)
        }
    override var visible: Boolean
        get() = native.isVisible
        set(value) {
            check(!removed) { "热力图已删除" }
            native.setVisible(value)
        }
    override var zIndex: Float
        get() = native.zIndex
        set(value) {
            check(!removed) { "热力图已删除" }
            native.setZIndex(value)
        }
    override fun remove() {
        if (removed) return
        removed = true
        try {
            native.remove()
        } finally {
            unregister?.invoke(this)
            unregister = null
        }
    }
    override fun rawOverlay(): Any = native

    private fun createOverlay(points: List<HeatPoint>, visible: Boolean, zIndex: Float): TileOverlay {
        val heatProvider = HeatmapTileProvider.Builder()
            .weightedData(points.toAmapWeightedPoints())
            .radius(radiusPixels)
            .transparency(opacity.toDouble())
            .build()
        val provider = object : TileProvider {
            override fun getTile(x: Int, y: Int, zoom: Int): Tile =
                if (zoom.toFloat() in minZoom..maxZoom) {
                    heatProvider.getTile(x, y, zoom)
                } else {
                    TileProvider.NO_TILE
                }

            override fun getTileWidth(): Int = heatProvider.tileWidth
            override fun getTileHeight(): Int = heatProvider.tileHeight
        }
        return map.addTileOverlay(
            com.amap.api.maps.model.TileOverlayOptions()
                .tileProvider(provider)
                .memoryCacheEnabled(true)
                .diskCacheEnabled(false)
                .visible(visible)
                .zIndex(zIndex),
        )
    }
}

/**
 * 在适配器边界把统一坐标转换成高德 GCJ02。
 * UNKNOWN 或非法坐标必须显式失败，不能把数值直接交给高德 SDK 造成静默偏移。
 */
private fun LatLng.toAmap(): com.amap.api.maps.model.LatLng = toAmapMapLatLng()

private fun com.amap.api.maps.model.LatLng.toFusion(coordType: CoordType) =
    LatLng(latitude, longitude, coordType)

private fun LatLngBounds.toAmap() = com.amap.api.maps.model.LatLngBounds(
    southwest.toAmap(),
    northeast.toAmap(),
)

private fun LatLngBounds.toAmapCoordinateBounds() = LatLngBounds(
    southwest = southwest.toAmapCoordinate(),
    northeast = northeast.toAmapCoordinate(),
)

private fun List<HeatPoint>.toAmapWeightedPoints() = map {
    WeightedLatLng(it.location.toAmap(), it.intensity.coerceAtLeast(0.0))
}

private fun MultiPointItem.toAmapMultiPointItem() = AmapMultiPointItem(position.toAmap()).also {
    it.customerId = id
    it.title = title
    it.setObject(tag)
}

private fun List<MultiPointItem>.validatedMultiPointItems(): List<MultiPointItem> = toList().also { items ->
    require(items.isNotEmpty()) { "海量点图层至少需要一个点" }
    require(items.map(MultiPointItem::id).toSet().size == items.size) {
        "同一海量点图层内的 id 不能重复"
    }
}

internal val AMAP_SUPPORTED_MAP_TYPES: Set<MapType> = setOf(
    MapType.NORMAL,
    MapType.SATELLITE,
    MapType.NIGHT,
    MapType.NAVIGATION,
)
