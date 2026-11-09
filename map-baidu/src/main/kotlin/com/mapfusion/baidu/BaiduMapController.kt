package com.mapfusion.baidu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BitmapDescriptor
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.Circle
import com.baidu.mapapi.map.FileTileProvider
import com.baidu.mapapi.map.GroundOverlay
import com.baidu.mapapi.map.HeatMap
import com.baidu.mapapi.map.MapPoi
import com.baidu.mapapi.map.MapLanguage as BaiduMapLanguage
import com.baidu.mapapi.map.MapCustomStyleOptions as BaiduCustomMapStyleOptions
import com.baidu.mapapi.map.MapStatus
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.Marker
import com.baidu.mapapi.map.MultiPoint as BaiduMultiPoint
import com.baidu.mapapi.map.MultiPointItem as BaiduMultiPointItem
import com.baidu.mapapi.map.MultiPointOption
import com.baidu.mapapi.map.Overlay
import com.baidu.mapapi.map.Polygon
import com.baidu.mapapi.map.Polyline
import com.baidu.mapapi.map.Stroke
import com.baidu.mapapi.map.Text
import com.baidu.mapapi.map.TileOverlay
import com.baidu.mapapi.map.WeightedLatLng
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
import com.mapfusion.api.model.MapCustomStyleFeature
import com.mapfusion.api.model.MapCustomStyleOptions
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapGroundOverlay
import com.mapfusion.api.model.MapHeatMap
import com.mapfusion.api.model.MapImage
import com.mapfusion.api.model.MapLanguage
import com.mapfusion.api.model.MapLegalInfo
import com.mapfusion.api.model.MapIndoorBuilding
import com.mapfusion.api.model.MapIndoorFloor
import com.baidu.mapapi.map.MapBaseIndoorMapInfo
import com.mapfusion.api.model.MapMarker
import com.mapfusion.api.model.MapMultiPointOverlay
import com.mapfusion.api.model.MapOverlay
import com.mapfusion.api.model.MapPoiClick
import com.mapfusion.api.model.MapPolygon
import com.mapfusion.api.model.MapPolyline
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.MapSnapshot
import com.mapfusion.api.model.MapTextOverlay
import com.mapfusion.api.model.MapTileOverlay
import com.mapfusion.api.model.MapTrafficStyle
import com.mapfusion.api.model.MapTouchAction
import com.mapfusion.api.model.MapTouchEvent
import com.mapfusion.api.model.MapType
import com.mapfusion.api.model.MapUiFeature
import com.mapfusion.api.model.MapUiOptions
import com.mapfusion.api.model.MapVisibleRegion
import com.mapfusion.api.model.MarkerIcon
import com.mapfusion.api.model.MarkerDragEvent
import com.mapfusion.api.model.MarkerDragPhase
import com.mapfusion.api.model.MarkerOptions
import com.mapfusion.api.model.MultiPointItem
import com.mapfusion.api.model.MultiPointOverlayOptions
import com.mapfusion.api.model.PolygonOptions
import com.mapfusion.api.model.PolylineOptions
import com.mapfusion.api.model.RequestHandle
import com.mapfusion.api.model.ScreenPoint
import com.mapfusion.api.model.TextHorizontalAlignment
import com.mapfusion.api.model.TextOverlayOptions
import com.mapfusion.api.model.TextVerticalAlignment
import com.mapfusion.api.model.TileOverlayOptions
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** 百度 MapView / BaiduMap 的真实统一适配。 */
internal class BaiduMapController(
    private val mapView: MapView,
    private val asyncRuntime: AsyncRuntime = AsyncRuntime.DEFAULT,
) : MapController {

    private val map: BaiduMap = mapView.map
    private val overlays = IdentityHashMap<Overlay, MapOverlay>()
    private val multiPointOverlays = IdentityHashMap<BaiduMultiPoint, BaiduMultiPointOverlay>()
    private val tileOverlays = mutableSetOf<MapTileOverlay>()
    private val heatMaps = mutableSetOf<MapHeatMap>()
    private val snapshotRequests = NativeRequestRegistry<Any> { }
    private var mapClickListener: ((LatLng) -> Unit)? = null
    private var mapPoiClickListener: ((MapPoiClick) -> Unit)? = null
    private var destroyed = false
    private var indoorListener: ((MapIndoorBuilding?) -> Unit)? = null

    override val view: View = mapView

    internal val nativeMap: BaiduMap get() = map
    internal val nativeMapView: MapView get() = mapView

    override fun onCreate(savedState: Bundle?) {
        // 百度 MapView 在构造时已创建；有状态时恢复 SDK 保存的数据。
        if (savedState != null) mapView.onCreate(mapView.context, savedState)
        map.setOnBaseIndoorMapListener { indoor, info ->
            if (!destroyed) indoorListener?.invoke(if (indoor) info?.toIndoorBuilding() else null)
        }
    }

    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onDestroy() {
        if (destroyed) return
        destroyed = true
        indoorListener = null
        try {
            runCatching { map.setOnBaseIndoorMapListener(null) }
            snapshotRequests.destroy()
            runCatching { setOnMapClickListener(null) }
            runCatching { setOnMapPoiClickListener(null) }
            runCatching { setOnMapLongClickListener(null) }
            runCatching { setOnMarkerClickListener(null) }
            runCatching { setOnMarkerDragListener(null) }
            runCatching { setOnMapTouchListener(null) }
            runCatching { setOnMultiPointClickListener(null) }
            runCatching { setOnOverlayClickListener(null) }
            runCatching { setOnCameraIdleListener(null) }
            runCatching { setOnMapLoadedListener(null) }
            // 先释放已登记的句柄，确保外部仍持有的 wrapper 不再反向持有控制器。
            overlays.values.toList().forEach { runCatching { it.remove() } }
            multiPointOverlays.values.toList().forEach { runCatching { it.remove() } }
            tileOverlays.toList().forEach { runCatching { it.remove() } }
            heatMaps.toList().forEach { runCatching { it.remove() } }
            runCatching { map.clear() }
            mapView.onDestroy()
        } finally {
            overlays.clear()
            multiPointOverlays.clear()
            tileOverlays.clear()
            heatMaps.clear()
            mapClickListener = null
            mapPoiClickListener = null
        }
    }
    override fun onSaveInstanceState(outState: Bundle) = mapView.onSaveInstanceState(outState)
    override fun onLowMemory() = Unit

    override fun moveCamera(update: CameraUpdate) {
        val nativeUpdate = update.bounds?.let { bounds ->
            MapStatusUpdateFactory.newLatLngBounds(
                bounds.toBaidu(),
                update.paddingPixels,
                update.paddingPixels,
                update.paddingPixels,
                update.paddingPixels,
            )
        } ?: MapStatusUpdateFactory.newMapStatus(
            MapStatus.Builder(map.mapStatus).apply {
                update.target?.let { target(it.toBaidu()) }
                update.zoom?.let(::zoom)
                update.bearing?.let(::rotate)
                update.tilt?.let { overlook(-it) }
            }.build(),
        )

        if (update.animated) {
            map.animateMapStatus(nativeUpdate, update.durationMs.coerceAtLeast(0))
        } else {
            map.setMapStatus(nativeUpdate)
        }
    }

    override fun getCameraPosition(): CameraPosition = map.mapStatus.let { status ->
        CameraPosition(
            target = status.target.toFusion(CoordType.BD09),
            zoom = status.zoom,
            bearing = status.rotate,
            tilt = -status.overlook,
        )
    }

    override fun setCameraBounds(bounds: LatLngBounds?) {
        map.setMapStatusLimits(bounds?.toBaidu())
    }

    override fun setZoomRange(minZoom: Float, maxZoom: Float) {
        require(minZoom <= maxZoom) { "minZoom 不能大于 maxZoom" }
        map.setMaxAndMinZoomLevel(maxZoom, minZoom)
    }

    override fun screenPointOf(location: LatLng): ScreenPoint? = runCatching {
        map.projection.toScreenLocation(location.toBaidu())?.let { ScreenPoint(it.x, it.y) }
    }.getOrNull()

    override fun coordinateAt(point: ScreenPoint): LatLng? = runCatching {
        map.projection.fromScreenLocation(Point(point.x, point.y))?.toFusion(CoordType.BD09)
    }.getOrNull()

    override fun visibleRegion(): MapVisibleRegion? {
        if (mapView.width <= 0 || mapView.height <= 0) return null
        return runCatching {
            val projection = map.projection
            fun coordinate(x: Int, y: Int): LatLng =
                requireNotNull(projection.fromScreenLocation(Point(x, y))) {
                    "百度地图尚未返回可视区域坐标"
                }.toFusion(CoordType.BD09)
            MapVisibleRegion(
                nearLeft = coordinate(0, mapView.height),
                nearRight = coordinate(mapView.width, mapView.height),
                farLeft = coordinate(0, 0),
                farRight = coordinate(mapView.width, 0),
            )
        }.getOrNull()
    }

    override fun addMarker(options: MarkerOptions): MapMarker {
        val nativeOptions = com.baidu.mapapi.map.MarkerOptions()
            .position(options.position.toBaidu())
            .title(options.title)
            .icon(options.icon.toBaiduDescriptor())
            .anchor(options.anchorU, options.anchorV)
            .draggable(options.draggable)
            .rotate(options.rotation)
            .alpha(options.alpha)
            .flat(options.flat)
            .visible(options.visible)
            .zIndex(options.zIndex.roundToInt())
        val native = map.addOverlay(nativeOptions) as Marker
        return BaiduMarker(native, options.snippet, options.tag) { overlays.remove(it) }
            .also { overlays[native] = it }
    }

    override fun addMultiPointOverlay(options: MultiPointOverlayOptions): MapMultiPointOverlay {
        val items = options.items.validatedMultiPointItems()
        val nativeItems = items.map(MultiPointItem::toBaiduMultiPointItem)
        val native = map.addOverlay(
            MultiPointOption()
                .setMultiPointItems(nativeItems)
                .setIcon(options.icon.toBaiduDescriptor())
                .setAnchor(options.anchorU, options.anchorV)
                .setClickable(options.clickable)
                .visible(options.visible),
        ) as BaiduMultiPoint
        return BaiduMultiPointOverlay(
            native = native,
            initialItems = items,
            initialNativeItems = nativeItems,
            initialTag = options.tag,
            onRemoved = { multiPointOverlays.remove(it) },
        ).also { multiPointOverlays[native] = it }
    }

    override fun addPolyline(options: PolylineOptions): MapPolyline {
        require(options.points.size >= 2) { "折线至少需要 2 个点" }
        val nativeOptions = com.baidu.mapapi.map.PolylineOptions()
            .points(options.points.map(LatLng::toBaidu))
            .width(options.width)
            .color(options.color)
            .dottedLine(options.dotted)
            .isGeodesic(options.geodesic)
            .clickable(options.clickable)
            .visible(options.visible)
            .zIndex(options.zIndex.roundToInt())
        val native = map.addOverlay(nativeOptions) as Polyline
        return BaiduPolyline(native, options.tag) { overlays.remove(it) }
            .also { overlays[native] = it }
    }

    override fun addPolygon(options: PolygonOptions): MapPolygon {
        require(options.points.size >= 3) { "多边形至少需要 3 个点" }
        val nativeOptions = com.baidu.mapapi.map.PolygonOptions()
            .points(options.points.map(LatLng::toBaidu))
            .stroke(Stroke(options.strokeWidth, options.strokeColor))
            .fillColor(options.fillColor)
            .visible(options.visible)
            .zIndex(options.zIndex.roundToInt())
        val native = map.addOverlay(nativeOptions) as Polygon
        native.isClickable = options.clickable
        return BaiduPolygon(native, options.tag) { overlays.remove(it) }
            .also { overlays[native] = it }
    }

    override fun addCircle(options: CircleOptions): MapCircle {
        require(options.radiusMeters > 0) { "圆半径必须大于 0" }
        val nativeOptions = com.baidu.mapapi.map.CircleOptions()
            .center(options.center.toBaidu())
            .radius(options.radiusMeters.roundToInt())
            .stroke(Stroke(options.strokeWidth, options.strokeColor))
            .fillColor(options.fillColor)
            .visible(options.visible)
            .zIndex(options.zIndex.roundToInt())
        val native = map.addOverlay(nativeOptions) as Circle
        native.isClickable = options.clickable
        return BaiduCircle(native, options.tag) { overlays.remove(it) }
            .also { overlays[native] = it }
    }

    override fun addGroundOverlay(options: GroundOverlayOptions): MapGroundOverlay {
        val nativeOptions = com.baidu.mapapi.map.GroundOverlayOptions()
            .image(options.image.toBaiduDescriptor())
            .anchor(options.anchorU, options.anchorV)
            .transparency(options.transparency.coerceIn(0f, 1f))
            .visible(options.visible)
            .zIndex(options.zIndex.roundToInt())
        val bounds = options.bounds
        if (bounds != null) {
            nativeOptions.positionFromBounds(bounds.toBaidu())
        } else {
            val position = requireNotNull(options.position) { "GroundOverlay 必须提供 position 或 bounds" }
            val width = requireNotNull(options.widthMeters) { "使用 position 时必须提供 widthMeters" }
            nativeOptions.position(position.toBaidu())
            options.heightMeters?.let { nativeOptions.dimensions(width.roundToInt(), it.roundToInt()) }
                ?: nativeOptions.dimensions(width.roundToInt())
        }
        val native = map.addOverlay(nativeOptions) as GroundOverlay
        return BaiduGroundOverlay(native, options.tag) { overlays.remove(it) }
            .also { overlays[native] = it }
    }

    override fun addText(options: TextOverlayOptions): MapTextOverlay {
        val horizontal = when (options.horizontalAlignment) {
            TextHorizontalAlignment.LEFT -> com.baidu.mapapi.map.TextOptions.ALIGN_LEFT
            TextHorizontalAlignment.CENTER -> com.baidu.mapapi.map.TextOptions.ALIGN_CENTER_HORIZONTAL
            TextHorizontalAlignment.RIGHT -> com.baidu.mapapi.map.TextOptions.ALIGN_RIGHT
        }
        val vertical = when (options.verticalAlignment) {
            TextVerticalAlignment.TOP -> com.baidu.mapapi.map.TextOptions.ALIGN_TOP
            TextVerticalAlignment.CENTER -> com.baidu.mapapi.map.TextOptions.ALIGN_CENTER_VERTICAL
            TextVerticalAlignment.BOTTOM -> com.baidu.mapapi.map.TextOptions.ALIGN_BOTTOM
        }
        val native = map.addOverlay(
            com.baidu.mapapi.map.TextOptions()
                .text(options.text)
                .position(options.position.toBaidu())
                .fontSize(options.fontSizePixels)
                .fontColor(options.fontColor)
                .bgColor(options.backgroundColor)
                .rotate(options.rotation)
                .align(horizontal, vertical)
                .setClickable(options.clickable)
                .visible(options.visible)
                .zIndex(options.zIndex.roundToInt()),
        ) as Text
        return BaiduTextOverlay(native, options.tag) { overlays.remove(it) }
            .also { overlays[native] = it }
    }

    override fun addTileOverlay(options: TileOverlayOptions): MapTileOverlay {
        val provider = object : FileTileProvider() {
            override fun getTile(x: Int, y: Int, zoom: Int): com.baidu.mapapi.map.Tile? {
                if (zoom !in options.minZoom..options.maxZoom) return null
                return options.provider.getTile(x, y, zoom)
                    ?.let(options::validateTile)
                    ?.let { tile ->
                        com.baidu.mapapi.map.Tile(tile.width, tile.height, tile.data)
                    }
            }

            override fun getMaxDisLevel(): Int = options.maxZoom
            override fun getMinDisLevel(): Int = options.minZoom
        }
        val nativeOptions = com.baidu.mapapi.map.TileOverlayOptions().tileProvider(provider)
        options.bounds?.let { nativeOptions.setPositionFromBounds(it.toBaidu()) }
        val native = requireNotNull(map.addTileLayer(nativeOptions)) { "百度 TileOverlay 创建失败" }
        return BaiduTileOverlay(native) { tileOverlays.remove(it) }
            .also(tileOverlays::add)
    }

    override fun addHeatMap(options: HeatMapOptions): MapHeatMap {
        require(options.points.isNotEmpty()) { "热力图至少需要一个点" }
        val native = HeatMap.Builder()
            .weightedData(options.points.toBaiduWeightedPoints())
            .radius(options.radiusPixels.coerceIn(10, 50))
            .opacity(options.opacity.coerceIn(0f, 1f).toDouble())
            .minShowLevel(options.minZoom.roundToInt())
            .maxShowLevel(options.maxZoom.roundToInt())
            .build()
        val wrapper = BaiduHeatMap(
            map = map,
            native = native,
            initialPoints = options.points,
            initialVisible = options.visible,
            initialZIndex = options.zIndex,
        ) { heatMaps.remove(it) }
        if (options.visible) map.addHeatMap(native)
        heatMaps += wrapper
        return wrapper
    }

    override fun clearMarkers() {
        overlays.values.filterIsInstance<MapMarker>().toList().forEach { runCatching { it.remove() } }
    }

    override fun clearOverlays() {
        try {
            overlays.values.toList().forEach { runCatching { it.remove() } }
            multiPointOverlays.values.toList().forEach { runCatching { it.remove() } }
            tileOverlays.toList().forEach { runCatching { it.remove() } }
            heatMaps.toList().forEach { runCatching { it.remove() } }
            map.clear()
        } finally {
            overlays.clear()
            multiPointOverlays.clear()
            tileOverlays.clear()
            heatMaps.clear()
        }
    }

    override fun setMyLocationEnabled(enabled: Boolean) = map.setMyLocationEnabled(enabled)
    override fun setTrafficEnabled(enabled: Boolean) = map.setTrafficEnabled(enabled)
    override fun setZoomControlsEnabled(enabled: Boolean) = mapView.showZoomControls(enabled)
    override fun setBuildingsEnabled(enabled: Boolean) = map.setBuildingsEnabled(enabled)
    override fun setIndoorEnabled(enabled: Boolean) = map.setIndoorEnable(enabled)
    override fun supportsIndoorFloorControl(): Boolean = true
    override fun activeIndoorBuilding(): MapIndoorBuilding? =
        if (destroyed || !map.isBaseIndoorMapMode) null else map.focusedBaseIndoorMapInfo?.toIndoorBuilding()

    override fun setOnIndoorBuildingListener(listener: ((MapIndoorBuilding?) -> Unit)?) {
        check(!destroyed || listener == null) { "地图已销毁" }
        indoorListener = listener
    }

    override fun switchIndoorFloor(buildingId: String, floorId: String): MapResult<Unit> {
        val current = activeIndoorBuilding()
        if (current == null || current.id != buildingId || current.floors.none { it.id == floorId }) {
            return MapResult.Failure(MapError(ErrorType.INVALID_PARAM, "当前室内建筑或楼层已不可用，请重新读取室内信息"))
        }
        return try {
            val status = map.switchBaseIndoorMapFloor(floorId, buildingId)
            if (status == MapBaseIndoorMapInfo.SwitchFloorError.SWITCH_OK) MapResult.Success(Unit)
            else MapResult.Failure(MapError(ErrorType.UNKNOWN, "百度室内切层失败：$status", rawMessage = status?.name))
        } catch (error: Exception) {
            MapResult.Failure(MapError(ErrorType.UNKNOWN, "百度室内切层失败：${error.message}", cause = error))
        }
    }

    private fun MapBaseIndoorMapInfo.toIndoorBuilding(): MapIndoorBuilding? =
        id?.takeIf { it.isNotBlank() }?.let { buildingId ->
            MapIndoorBuilding(buildingId, floors.orEmpty().map { MapIndoorFloor(it, it) }, curFloor)
        }
    override fun setMapPoiEnabled(enabled: Boolean) = map.showMapPoi(enabled)

    override fun supportedMapLanguages(): Set<MapLanguage> = BAIDU_SUPPORTED_MAP_LANGUAGES

    override fun applyMapLanguage(language: MapLanguage): MapResult<MapLanguage> {
        if (language !in BAIDU_SUPPORTED_MAP_LANGUAGES) {
            return MapResult.Failure(
                MapError(ErrorType.UNSUPPORTED, "百度地图不支持底图语言 $language"),
            )
        }
        return runCatching {
            map.setMapLanguage(
                when (language) {
                    MapLanguage.CHINESE -> BaiduMapLanguage.CHINESE
                    MapLanguage.ENGLISH -> BaiduMapLanguage.ENGLISH
                    MapLanguage.LOCAL -> error("已在支持范围检查中排除")
                },
            )
            MapResult.Success(language)
        }.getOrElse { error ->
            MapResult.Failure(
                MapError(ErrorType.UNKNOWN, "百度底图语言应用失败：${error.message.orEmpty()}", cause = error),
            )
        }
    }

    override fun applyTrafficStyle(style: MapTrafficStyle): MapResult<Unit> = runCatching {
        map.setCustomTrafficColor(
            style.smoothColor,
            style.slowColor,
            style.congestedColor,
            style.seriouslyCongestedColor,
        )
        map.setCustomTrafficColorEnable(true)
        MapResult.Success(Unit)
    }.getOrElse { error ->
        MapResult.Failure(
            MapError(ErrorType.UNKNOWN, "百度路况配色应用失败：${error.message.orEmpty()}", cause = error),
        )
    }

    override fun legalInfo(): MapLegalInfo? = runCatching {
        MapLegalInfo(
            mapContentApprovalNumber = map.mapApprovalNumber.nonBlankOrNull(),
            englishMapApprovalNumber = map.englishMapApprovalNumber.nonBlankOrNull(),
            copyright = map.mapCopyrightInfo.nonBlankOrNull(),
            mappingQualification = map.mapMappingQualificationInfo.nonBlankOrNull(),
        )
    }.getOrNull()

    override fun supportedCustomStyleFeatures(): Set<MapCustomStyleFeature> =
        BAIDU_SUPPORTED_CUSTOM_STYLE_FEATURES

    override fun applyCustomStyle(options: MapCustomStyleOptions): MapResult<Unit> {
        val unsupported = options.requestedCustomStyleFeatures() - supportedCustomStyleFeatures()
        if (unsupported.isNotEmpty()) {
            return MapResult.Failure(
                MapError(ErrorType.UNSUPPORTED, "百度地图不支持自定义样式来源：${unsupported.joinToString()}"),
            )
        }
        return runCatching {
            if (options.enabled) {
                val nativeOptions = BaiduCustomMapStyleOptions().apply {
                    options.localStylePath?.let(::localCustomStylePath)
                    options.styleId?.let(::customStyleId)
                }
                mapView.setMapCustomStyle(nativeOptions, null)
            }
            mapView.setMapCustomStyleEnable(options.enabled)
            MapResult.Success(Unit)
        }.getOrElse { error ->
            MapResult.Failure(
                MapError(ErrorType.UNKNOWN, "百度自定义地图样式提交失败：${error.message.orEmpty()}", cause = error),
            )
        }
    }

    override fun supportedMapTypes(): Set<MapType> = BAIDU_SUPPORTED_MAP_TYPES

    @Deprecated("使用 applyMapType(type) 获取明确结果", ReplaceWith("applyMapType(type)"))
    override fun setMapType(type: MapType) {
        map.mapType = when (type) {
            MapType.SATELLITE -> BaiduMap.MAP_TYPE_SATELLITE
            MapType.NONE -> BaiduMap.MAP_TYPE_NONE
            MapType.NORMAL -> BaiduMap.MAP_TYPE_NORMAL
            MapType.NIGHT, MapType.NAVIGATION ->
                throw UnsupportedOperationException("百度地图不支持地图类型 $type")
        }
    }

    override fun getMapType(): MapType = when (map.mapType) {
        BaiduMap.MAP_TYPE_SATELLITE -> MapType.SATELLITE
        BaiduMap.MAP_TYPE_NONE -> MapType.NONE
        else -> MapType.NORMAL
    }

    override fun supportedUiFeatures(): Set<MapUiFeature> = BAIDU_SUPPORTED_UI_FEATURES

    override fun setUiOptions(options: MapUiOptions) {
        mapView.showZoomControls(options.zoomControlsEnabled)
        mapView.showScaleControl(options.scaleControlsEnabled)
        map.uiSettings.apply {
            isCompassEnabled = options.compassEnabled
            isScrollGesturesEnabled = options.scrollGesturesEnabled
            isZoomGesturesEnabled = options.zoomGesturesEnabled
            isRotateGesturesEnabled = options.rotateGesturesEnabled
            isOverlookingGesturesEnabled = options.tiltGesturesEnabled
            setDoubleClickZoomEnabled(options.doubleClickZoomEnabled)
            setTwoTouchClickZoomEnabled(options.twoTouchClickZoomEnabled)
            setDoubleClickMoveZoomEnable(options.doubleClickMoveZoomEnabled)
            setFlingEnable(options.flingEnabled)
            setInertialAnimation(options.inertialAnimationEnabled)
        }
    }

    override fun snapshot(
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<MapSnapshot>,
    ): RequestHandle {
        if (destroyed || snapshotRequests.isDestroyed) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度地图控制器已销毁"),
            )
        }
        val token = Any()
        val async = snapshotRequests.trackedRequest(token, asyncRuntime, asyncOptions, callback)
        if (async == null) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度地图控制器已销毁"),
            )
        }
        if (async.isDone) return async
        try {
            map.snapshot { bitmap ->
                val result = runCatching {
                    requireNotNull(bitmap) { "百度地图未返回截图位图" }
                    val output = ByteArrayOutputStream()
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "百度地图截图编码失败" }
                    MapResult.Success(MapSnapshot(output.toByteArray(), bitmap.width, bitmap.height))
                }.getOrElse { error ->
                    MapResult.Failure(
                        MapError(ErrorType.UNKNOWN, "百度地图截图失败：${error.message.orEmpty()}", cause = error),
                    )
                }
                async.complete(result)
            }
        } catch (error: Throwable) {
            async.failure(MapError(ErrorType.UNKNOWN, "百度地图截图失败：${error.message.orEmpty()}", cause = error))
        }
        return async
    }

    override fun setOnMapClickListener(listener: ((LatLng) -> Unit)?) {
        mapClickListener = listener
        updateMapClickDispatcher()
    }

    override fun setOnMapPoiClickListener(listener: ((MapPoiClick) -> Unit)?) {
        mapPoiClickListener = listener
        updateMapClickDispatcher()
    }

    override fun setOnMapLongClickListener(listener: ((LatLng) -> Unit)?) {
        map.setOnMapLongClickListener(listener?.let { callback ->
            BaiduMap.OnMapLongClickListener { point -> callback(point.toFusion(CoordType.BD09)) }
        })
    }

    override fun setOnMarkerClickListener(listener: ((MapMarker) -> Boolean)?) {
        map.setOnMarkerClickListener(listener?.let { callback ->
            BaiduMap.OnMarkerClickListener { marker ->
                (overlays[marker] as? MapMarker)?.let(callback) ?: false
            }
        })
    }

    override fun setOnMarkerDragListener(listener: ((MarkerDragEvent) -> Unit)?) {
        map.setOnMarkerDragListener(listener?.let { callback ->
            object : BaiduMap.OnMarkerDragListener {
                override fun onMarkerDragStart(marker: Marker) = deliver(marker, MarkerDragPhase.STARTED)
                override fun onMarkerDrag(marker: Marker) = deliver(marker, MarkerDragPhase.DRAGGING)
                override fun onMarkerDragEnd(marker: Marker) = deliver(marker, MarkerDragPhase.ENDED)

                private fun deliver(marker: Marker, phase: MarkerDragPhase) {
                    val fusionMarker = overlays[marker] as? MapMarker ?: return
                    callback(MarkerDragEvent(fusionMarker, phase, marker.position.toFusion(CoordType.BD09)))
                }
            }
        })
    }

    override fun setOnMapTouchListener(listener: ((MapTouchEvent) -> Unit)?) {
        map.setOnMapTouchListener(listener?.let { callback ->
            BaiduMap.OnMapTouchListener { event -> callback(event.toFusionTouchEvent()) }
        })
    }

    override fun setOnMultiPointClickListener(
        listener: ((MapMultiPointOverlay, MultiPointItem) -> Boolean)?,
    ) {
        map.setOnMultiPointClickListener(listener?.let { callback ->
            BaiduMap.OnMultiPointClickListener { nativeOverlay, nativeItem ->
                val overlay = multiPointOverlays[nativeOverlay]
                if (overlay == null || !overlay.clickable) {
                    false
                } else {
                    overlay.itemFor(nativeItem)?.let { callback(overlay, it) } ?: false
                }
            }
        })
    }

    override fun setOnOverlayClickListener(listener: ((MapOverlay) -> Boolean)?) {
        map.setOnPolylineClickListener(listener?.let { callback ->
            BaiduMap.OnPolylineClickListener { line -> overlays[line]?.let(callback) ?: false }
        })
        map.setOnPolygonClickListener(listener?.let { callback ->
            BaiduMap.OnPolygonClickListener { polygon -> overlays[polygon]?.let(callback) ?: false }
        })
        map.setOnCircleClickListener(listener?.let { callback ->
            BaiduMap.OnCircleClickListener { circle -> overlays[circle]?.let(callback) ?: false }
        })
        map.setOnGroundOverlayClickListener(listener?.let { callback ->
            BaiduMap.OnGroundOverlayClickListener { ground -> overlays[ground]?.let(callback) ?: false }
        })
        map.setOnTextClickListener(listener?.let { callback ->
            BaiduMap.OnTextClickListener { text -> overlays[text]?.let(callback) ?: false }
        })
    }

    override fun setOnCameraIdleListener(listener: ((CameraPosition) -> Unit)?) {
        map.setOnMapStatusChangeListener(listener?.let { callback ->
            object : BaiduMap.OnMapStatusChangeListener {
                override fun onMapStatusChangeStart(status: MapStatus) = Unit
                override fun onMapStatusChangeStart(status: MapStatus, reason: Int) = Unit
                override fun onMapStatusChange(status: MapStatus) = Unit
                override fun onMapStatusChangeFinish(status: MapStatus) {
                    callback(
                        CameraPosition(
                            status.target.toFusion(CoordType.BD09),
                            status.zoom,
                            status.rotate,
                            -status.overlook,
                        ),
                    )
                }
            }
        })
    }

    override fun setOnMapLoadedListener(listener: (() -> Unit)?) {
        map.setOnMapLoadedCallback(listener?.let { callback ->
            BaiduMap.OnMapLoadedCallback { callback() }
        })
    }

    private fun updateMapClickDispatcher() {
        val mapCallback = mapClickListener
        val poiCallback = mapPoiClickListener
        if (mapCallback == null && poiCallback == null) {
            map.setOnMapClickListener(null)
            return
        }
        map.setOnMapClickListener(
            object : BaiduMap.OnMapClickListener {
                override fun onMapClick(point: com.baidu.mapapi.model.LatLng) {
                    mapCallback?.invoke(point.toFusion(CoordType.BD09))
                }

                override fun onMapPoiClick(poi: MapPoi) {
                    val position = poi.position ?: return
                    poiCallback?.invoke(
                        MapPoiClick(
                            id = poi.uid.nonBlankOrNull(),
                            name = poi.name.nonBlankOrNull(),
                            position = position.toFusion(CoordType.BD09),
                        ),
                    )
                }
            },
        )
    }

    private fun MarkerIcon.toBaiduDescriptor(): BitmapDescriptor = when (this) {
        MarkerIcon.Default -> createDefaultMarkerDescriptor()
        is MarkerIcon.Asset -> BitmapDescriptorFactory.fromAsset(assetName)
        is MarkerIcon.Resource -> BitmapDescriptorFactory.fromResource(resId)
        is MarkerIcon.Bytes -> BitmapDescriptorFactory.fromBitmap(
            BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: error("无法解码 MarkerIcon.Bytes"),
        )
    }

    private fun MapImage.toBaiduDescriptor(): BitmapDescriptor = when (this) {
        is MapImage.Asset -> BitmapDescriptorFactory.fromAsset(assetName)
        is MapImage.Resource -> BitmapDescriptorFactory.fromResource(resId)
        is MapImage.Bytes -> BitmapDescriptorFactory.fromBitmap(
            BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: error("无法解码 MapImage.Bytes"),
        )
    }

    private fun createDefaultMarkerDescriptor(): BitmapDescriptor {
        val bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(213, 48, 48) }
        canvas.drawCircle(24f, 23f, 18f, paint)
        canvas.drawPath(
            Path().apply {
                moveTo(12f, 35f)
                lineTo(24f, 62f)
                lineTo(36f, 35f)
                close()
            },
            paint,
        )
        paint.color = Color.WHITE
        canvas.drawCircle(24f, 23f, 7f, paint)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}

private abstract class BaiduOverlay<T : Overlay>(
    protected val native: T,
    initialTag: Any?,
    onRemoved: (T) -> Unit,
) : MapOverlay {
    final override var isRemoved: Boolean = false
        private set
    private var unregister: ((T) -> Unit)? = onRemoved
    override val id: String = (native as? Marker)?.id ?: native.name ?: "baidu-${NEXT_ID.incrementAndGet()}"
    override var visible: Boolean
        get() = native.isVisible
        set(value) = native.setVisible(value)
    override var zIndex: Float
        get() = native.zIndex.toFloat()
        set(value) = native.setZIndex(value.roundToInt())
    override var tag: Any? = initialTag
    final override fun remove() {
        if (isRemoved) return
        isRemoved = true
        try {
            native.remove()
        } finally {
            unregister?.invoke(native)
            unregister = null
        }
    }
    override fun rawOverlay(): Any = native

    private companion object {
        val NEXT_ID = AtomicLong()
    }
}

private class BaiduMarker(
    native: Marker,
    initialSnippet: String?,
    tag: Any?,
    onRemoved: (Marker) -> Unit,
) : BaiduOverlay<Marker>(native, tag, onRemoved), MapMarker {
    override val id: String get() = native.id
    override var position: LatLng
        get() = native.position.toFusion(CoordType.BD09)
        set(value) = native.setPosition(value.toBaidu())
    @Suppress("DEPRECATION")
    override var title: String?
        get() = native.title
        set(value) = native.setTitle(value)
    override var snippet: String? = initialSnippet
    override var rotation: Float
        get() = native.rotate
        set(value) = native.setRotate(value)
    override var alpha: Float
        get() = native.alpha
        set(value) = native.setAlpha(value.coerceIn(0f, 1f))
    override var flat: Boolean
        get() = native.isFlat
        set(value) = native.setFlat(value)
    override fun showInfoWindow() = native.showInfoWindow()
    override fun hideInfoWindow() = native.hideInfoWindow()
}

private class BaiduMultiPointOverlay(
    private val native: BaiduMultiPoint,
    initialItems: List<MultiPointItem>,
    initialNativeItems: List<BaiduMultiPointItem>,
    initialTag: Any?,
    onRemoved: (BaiduMultiPoint) -> Unit,
) : MapMultiPointOverlay {
    private val itemByNative = IdentityHashMap<BaiduMultiPointItem, MultiPointItem>()
    private var unregister: ((BaiduMultiPoint) -> Unit)? = onRemoved
    override val id: String = native.name ?: "baidu-multipoint-${System.identityHashCode(native)}"
    override var isRemoved: Boolean = false
        private set
    override var items: List<MultiPointItem> = initialItems.toList()
        set(value) {
            check(!isRemoved) { "海量点图层已删除" }
            val validated = value.validatedMultiPointItems()
            val nativeItems = validated.map(MultiPointItem::toBaiduMultiPointItem)
            native.setMultiPointItems(nativeItems)
            field = validated
            replaceItemMapping(nativeItems, validated)
        }
    override var visible: Boolean
        get() = native.isVisible
        set(value) {
            check(!isRemoved) { "海量点图层已删除" }
            native.setVisible(value)
        }
    override var clickable: Boolean
        get() = native.isClickable
        set(value) {
            check(!isRemoved) { "海量点图层已删除" }
            native.setClickable(value)
        }
    override var tag: Any? = initialTag

    init {
        replaceItemMapping(initialNativeItems, items)
    }

    fun itemFor(nativeItem: BaiduMultiPointItem): MultiPointItem? = itemByNative[nativeItem]

    override fun remove() {
        if (isRemoved) return
        isRemoved = true
        try {
            native.remove()
        } finally {
            itemByNative.clear()
            unregister?.invoke(native)
            unregister = null
        }
    }

    override fun rawOverlay(): Any = native

    private fun replaceItemMapping(
        nativeItems: List<BaiduMultiPointItem>,
        unifiedItems: List<MultiPointItem>,
    ) {
        itemByNative.clear()
        nativeItems.zip(unifiedItems).forEach { (nativeItem, item) -> itemByNative[nativeItem] = item }
    }
}

private class BaiduPolyline(native: Polyline, tag: Any?, onRemoved: (Polyline) -> Unit) :
    BaiduOverlay<Polyline>(native, tag, onRemoved), MapPolyline {
    override var points: List<LatLng>
        get() = native.points.map { it.toFusion(CoordType.BD09) }
        set(value) = native.setPoints(value.map(LatLng::toBaidu))
    override var width: Float
        get() = native.widthFloat
        set(value) = native.setWidth(value)
    override var color: Int
        get() = native.color
        set(value) = native.setColor(value)
}

private class BaiduPolygon(native: Polygon, tag: Any?, onRemoved: (Polygon) -> Unit) :
    BaiduOverlay<Polygon>(native, tag, onRemoved), MapPolygon {
    override var points: List<LatLng>
        get() = native.points.map { it.toFusion(CoordType.BD09) }
        set(value) = native.setPoints(value.map(LatLng::toBaidu))
    override var strokeWidth: Float
        get() = native.stroke?.strokeWidth ?: 0f
        set(value) = native.setStroke(Stroke(value, strokeColor))
    override var strokeColor: Int
        get() = native.stroke?.color ?: Color.TRANSPARENT
        set(value) = native.setStroke(Stroke(strokeWidth, value))
    override var fillColor: Int
        get() = native.fillColor
        set(value) = native.setFillColor(value)
}

private class BaiduCircle(native: Circle, tag: Any?, onRemoved: (Circle) -> Unit) :
    BaiduOverlay<Circle>(native, tag, onRemoved), MapCircle {
    override var center: LatLng
        get() = native.center.toFusion(CoordType.BD09)
        set(value) = native.setCenter(value.toBaidu())
    override var radiusMeters: Double
        get() = native.radius.toDouble()
        set(value) = native.setRadius(value.roundToInt())
    override var strokeWidth: Float
        get() = native.stroke?.strokeWidth ?: 0f
        set(value) = native.setStroke(Stroke(value, strokeColor))
    override var strokeColor: Int
        get() = native.stroke?.color ?: Color.TRANSPARENT
        set(value) = native.setStroke(Stroke(strokeWidth, value))
    override var fillColor: Int
        get() = native.fillColor
        set(value) = native.setFillColor(value)
}

private class BaiduGroundOverlay(native: GroundOverlay, tag: Any?, onRemoved: (GroundOverlay) -> Unit) :
    BaiduOverlay<GroundOverlay>(native, tag, onRemoved), MapGroundOverlay {
    override var position: LatLng?
        get() = native.position?.toFusion(CoordType.BD09)
        set(value) {
            if (value != null) native.setPosition(value.toBaidu())
        }
    override var bounds: LatLngBounds?
        get() = native.bounds?.let {
            LatLngBounds(
                it.southwest.toFusion(CoordType.BD09),
                it.northeast.toFusion(CoordType.BD09),
            )
        }
        set(value) {
            if (value != null) native.setPositionFromBounds(value.toBaidu())
        }
    override var transparency: Float
        get() = native.transparency
        set(value) = native.setTransparency(value.coerceIn(0f, 1f))
}

private class BaiduTextOverlay(native: Text, tag: Any?, onRemoved: (Text) -> Unit) :
    BaiduOverlay<Text>(native, tag, onRemoved), MapTextOverlay {
    override var text: String
        get() = native.text
        set(value) = native.setText(value)
    override var position: LatLng
        get() = native.position.toFusion(CoordType.BD09)
        set(value) = native.setPosition(value.toBaidu())
    override var fontSizePixels: Int
        get() = native.fontSize
        set(value) = native.setFontSize(value)
    override var fontColor: Int
        get() = native.fontColor
        set(value) = native.setFontColor(value)
    override var backgroundColor: Int
        get() = native.bgColor
        set(value) = native.setBgColor(value)
    override var rotation: Float
        get() = native.rotate
        set(value) = native.setRotate(value)
}

private class BaiduTileOverlay(
    private val native: TileOverlay,
    onRemoved: (MapTileOverlay) -> Unit,
) : MapTileOverlay {
    private var removed = false
    private var unregister: ((MapTileOverlay) -> Unit)? = onRemoved
    override val id: String = "baidu-tile-${System.identityHashCode(native)}"
    override fun clearCache() { native.clearTileCache() }
    override fun remove() {
        if (removed) return
        removed = true
        try {
            native.removeTileOverlay()
        } finally {
            unregister?.invoke(this)
            unregister = null
        }
    }
    override fun rawOverlay(): Any = native
}

private class BaiduHeatMap(
    private val map: BaiduMap,
    private val native: HeatMap,
    initialPoints: List<HeatPoint>,
    initialVisible: Boolean,
    initialZIndex: Float,
    onRemoved: (MapHeatMap) -> Unit,
) : MapHeatMap {
    private var attached = initialVisible
    private var removed = false
    private var unregister: ((MapHeatMap) -> Unit)? = onRemoved
    override var zIndex: Float = initialZIndex
    override var points: List<HeatPoint> = initialPoints
        set(value) {
            check(!removed) { "热力图已删除" }
            require(value.isNotEmpty()) { "热力图至少需要一个点" }
            field = value
            native.updateWeightedData(value.toBaiduWeightedPoints())
        }
    private var visibleState = initialVisible
    override var visible: Boolean
        get() = visibleState
        set(value) {
            check(!removed) { "热力图已删除" }
            if (visibleState == value) return
            visibleState = value
            if (value) {
                map.addHeatMap(native)
                attached = true
            } else if (attached) {
                native.removeHeatMap()
                attached = false
            }
        }
    override fun remove() {
        if (removed) return
        removed = true
        try {
            if (attached) native.removeHeatMap()
            attached = false
            visibleState = false
        } finally {
            unregister?.invoke(this)
            unregister = null
        }
    }
    override fun rawOverlay(): Any = native
}

/**
 * 在适配器边界把统一坐标转换成百度 BD09。
 * UNKNOWN 或非法坐标必须显式失败，不能把数值直接交给百度 SDK 造成静默偏移。
 */
private fun LatLng.toBaidu(): com.baidu.mapapi.model.LatLng = toBaiduSdkLatLng()

private fun com.baidu.mapapi.model.LatLng.toFusion(coordType: CoordType) =
    LatLng(latitude, longitude, coordType)

private fun LatLngBounds.toBaidu(): com.baidu.mapapi.model.LatLngBounds =
    com.baidu.mapapi.model.LatLngBounds.Builder()
        .include(southwest.toBaidu())
        .include(northeast.toBaidu())
        .build()

private fun List<HeatPoint>.toBaiduWeightedPoints() = map {
    WeightedLatLng(it.location.toBaidu(), it.intensity.coerceAtLeast(0.0))
}

@Suppress("DEPRECATION")
private fun MultiPointItem.toBaiduMultiPointItem() = BaiduMultiPointItem(position.toBaidu()).also {
    it.title = title
}

private fun List<MultiPointItem>.validatedMultiPointItems(): List<MultiPointItem> = toList().also { items ->
    require(items.isNotEmpty()) { "海量点图层至少需要一个点" }
    require(items.map(MultiPointItem::id).toSet().size == items.size) {
        "同一海量点图层内的 id 不能重复"
    }
}

internal val BAIDU_SUPPORTED_MAP_TYPES: Set<MapType> = setOf(
    MapType.NORMAL,
    MapType.SATELLITE,
    MapType.NONE,
)

internal val BAIDU_SUPPORTED_MAP_LANGUAGES: Set<MapLanguage> = setOf(
    MapLanguage.CHINESE,
    MapLanguage.ENGLISH,
)

internal val BAIDU_SUPPORTED_UI_FEATURES: Set<MapUiFeature> = setOf(
    MapUiFeature.ZOOM_CONTROLS,
    MapUiFeature.SCALE_CONTROLS,
    MapUiFeature.COMPASS,
    MapUiFeature.SCROLL_GESTURES,
    MapUiFeature.ZOOM_GESTURES,
    MapUiFeature.ROTATE_GESTURES,
    MapUiFeature.TILT_GESTURES,
    MapUiFeature.DOUBLE_CLICK_ZOOM,
    MapUiFeature.TWO_TOUCH_CLICK_ZOOM,
    MapUiFeature.DOUBLE_CLICK_MOVE_ZOOM,
    MapUiFeature.FLING,
    MapUiFeature.INERTIAL_ANIMATION,
)

internal val BAIDU_SUPPORTED_CUSTOM_STYLE_FEATURES: Set<MapCustomStyleFeature> = setOf(
    MapCustomStyleFeature.LOCAL_STYLE_FILE,
    MapCustomStyleFeature.STYLE_ID,
)

private fun MapCustomStyleOptions.requestedCustomStyleFeatures(): Set<MapCustomStyleFeature> = buildSet {
    if (!enabled) return@buildSet
    if (localStylePath != null) add(MapCustomStyleFeature.LOCAL_STYLE_FILE)
    if (styleId != null) add(MapCustomStyleFeature.STYLE_ID)
    if (localTexturePath != null) add(MapCustomStyleFeature.LOCAL_TEXTURE_FILE)
}

private fun MotionEvent.toFusionTouchEvent() = MapTouchEvent(
    action = when (actionMasked) {
        MotionEvent.ACTION_DOWN -> MapTouchAction.DOWN
        MotionEvent.ACTION_UP -> MapTouchAction.UP
        MotionEvent.ACTION_MOVE -> MapTouchAction.MOVE
        MotionEvent.ACTION_CANCEL -> MapTouchAction.CANCEL
        MotionEvent.ACTION_POINTER_DOWN -> MapTouchAction.POINTER_DOWN
        MotionEvent.ACTION_POINTER_UP -> MapTouchAction.POINTER_UP
        else -> MapTouchAction.OTHER
    },
    x = x,
    y = y,
    pointerCount = pointerCount,
)

private fun String?.nonBlankOrNull(): String? = this?.takeIf(String::isNotBlank)
