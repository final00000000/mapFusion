package com.mapfusion.api.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** 可复用于地面图片等覆盖物的统一图片来源。 */
sealed class MapImage {
    data class Asset(val assetName: String) : MapImage()
    data class Resource(val resId: Int) : MapImage()
    data class Bytes(val data: ByteArray) : MapImage() {
        override fun equals(other: Any?): Boolean = other is Bytes && data.contentEquals(other.data)
        override fun hashCode(): Int = data.contentHashCode()
    }
}

data class GroundOverlayOptions(
    val image: MapImage,
    /** 使用中心点时必填；与 bounds 二选一。 */
    val position: LatLng? = null,
    /** 使用矩形范围时必填；优先于 position/width/height。 */
    val bounds: LatLngBounds? = null,
    val widthMeters: Float? = null,
    val heightMeters: Float? = null,
    val anchorU: Float = 0.5f,
    val anchorV: Float = 0.5f,
    /** 0 完全不透明，1 完全透明。 */
    val transparency: Float = 0f,
    val visible: Boolean = true,
    val zIndex: Float = 0f,
    val tag: Any? = null,
)

interface MapGroundOverlay : MapOverlay {
    var position: LatLng?
    var bounds: LatLngBounds?
    var transparency: Float
}

enum class TextHorizontalAlignment { LEFT, CENTER, RIGHT }
enum class TextVerticalAlignment { TOP, CENTER, BOTTOM }

data class TextOverlayOptions(
    val text: String,
    val position: LatLng,
    val fontSizePixels: Int = 32,
    val fontColor: Int = 0xFF212121.toInt(),
    val backgroundColor: Int = 0x00FFFFFF,
    val rotation: Float = 0f,
    val horizontalAlignment: TextHorizontalAlignment = TextHorizontalAlignment.CENTER,
    val verticalAlignment: TextVerticalAlignment = TextVerticalAlignment.CENTER,
    val clickable: Boolean = false,
    val visible: Boolean = true,
    val zIndex: Float = 0f,
    val tag: Any? = null,
)

interface MapTextOverlay : MapOverlay {
    var text: String
    var position: LatLng
    var fontSizePixels: Int
    var fontColor: Int
    var backgroundColor: Int
    var rotation: Float
}

class MapTile(
    val width: Int,
    val height: Int,
    val data: ByteArray,
) {
    init {
        require(width > 0) { "瓦片宽度必须大于 0" }
        require(height > 0) { "瓦片高度必须大于 0" }
    }

    override fun equals(other: Any?): Boolean =
        other is MapTile && width == other.width && height == other.height && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * (31 * width + height) + data.contentHashCode()
}

fun interface MapTileProvider {
    /** 返回 null 表示该瓦片不存在。 */
    fun getTile(x: Int, y: Int, zoom: Int): MapTile?
}

data class TileOverlayOptions(
    val provider: MapTileProvider,
    val tileWidth: Int = 256,
    val tileHeight: Int = 256,
    val minZoom: Int = 3,
    val maxZoom: Int = 21,
    val bounds: LatLngBounds? = null,
    val memoryCacheEnabled: Boolean = true,
    val diskCacheEnabled: Boolean = true,
    val visible: Boolean = true,
    val zIndex: Float = 0f,
) {
    init {
        require(tileWidth > 0) { "tileWidth 必须大于 0" }
        require(tileHeight > 0) { "tileHeight 必须大于 0" }
        require(minZoom >= 0) { "minZoom 不能小于 0" }
        require(maxZoom <= MAX_WEB_MERCATOR_ZOOM) {
            "maxZoom 不能大于 $MAX_WEB_MERCATOR_ZOOM"
        }
        require(minZoom <= maxZoom) { "minZoom 不能大于 maxZoom" }
        bounds?.let(::validateBounds)
    }

    /**
     * 判断指定 WebMercator 瓦片是否落在当前图层的缩放级别和地理边界内。
     * x/y 使用标准 XYZ 编号；范围外请求直接返回 false，适配器不应再调用业务 provider。
     */
    fun containsTile(x: Int, y: Int, zoom: Int): Boolean {
        if (zoom !in minZoom..maxZoom || zoom > MAX_WEB_MERCATOR_ZOOM) return false
        val worldSize = 1L shl zoom
        if (x < 0 || y < 0 || x.toLong() >= worldSize || y.toLong() >= worldSize) return false
        val tileBounds = bounds ?: return true
        val west = longitudeToTile(x = tileBounds.southwest.longitude, worldSize = worldSize)
        val east = longitudeToTile(x = tileBounds.northeast.longitude, worldSize = worldSize)
        val north = latitudeToTile(y = tileBounds.northeast.latitude, worldSize = worldSize)
        val south = latitudeToTile(y = tileBounds.southwest.latitude, worldSize = worldSize)
        return x.toLong() in west..east && y.toLong() in north..south
    }

    /**
     * 在调用业务 provider 前执行统一范围/尺寸策略。边界外或缩放级别外返回 null；
     * provider 返回的尺寸不匹配时立即失败，避免厂商 SDK 收到非法瓦片。
     */
    fun loadTile(x: Int, y: Int, zoom: Int): MapTile? {
        if (!containsTile(x, y, zoom)) return null
        return provider.getTile(x, y, zoom)?.let(::validateTile)
    }

    /** 校验业务 provider 返回的瓦片尺寸是否与图层配置一致。 */
    fun validateTile(tile: MapTile): MapTile = tile.also {
        require(it.width == tileWidth) {
            "瓦片宽度 ${it.width} 与配置的 tileWidth $tileWidth 不一致"
        }
        require(it.height == tileHeight) {
            "瓦片高度 ${it.height} 与配置的 tileHeight $tileHeight 不一致"
        }
    }

    private companion object {
        const val MAX_WEB_MERCATOR_ZOOM = 30
        const val MAX_MERCATOR_LATITUDE = 85.0511287798066

        fun validateBounds(bounds: LatLngBounds) {
            val southwest = bounds.southwest
            val northeast = bounds.northeast
            require(southwest.coordType != CoordType.UNKNOWN && northeast.coordType != CoordType.UNKNOWN) {
                "TileOverlay bounds 坐标系必须明确"
            }
            require(southwest.coordType == northeast.coordType) {
                "TileOverlay bounds 不能混用不同坐标系"
            }
            require(southwest.latitude.isFinite() && northeast.latitude.isFinite()) {
                "TileOverlay bounds 纬度必须是有限值"
            }
            require(southwest.longitude.isFinite() && northeast.longitude.isFinite()) {
                "TileOverlay bounds 经度必须是有限值"
            }
            require(southwest.latitude in -90.0..90.0 && northeast.latitude in -90.0..90.0) {
                "TileOverlay bounds 纬度必须在 -90..90"
            }
            require(southwest.longitude in -180.0..180.0 && northeast.longitude in -180.0..180.0) {
                "TileOverlay bounds 经度必须在 -180..180"
            }
            require(southwest.latitude <= northeast.latitude) {
                "TileOverlay bounds southwest.latitude 不能大于 northeast.latitude"
            }
            require(southwest.longitude <= northeast.longitude) {
                "TileOverlay bounds 暂不支持跨日期变更线范围"
            }
        }

        fun longitudeToTile(x: Double, worldSize: Long): Long =
            floor(((x + 180.0) / 360.0) * worldSize)
                .toLong()
                .coerceIn(0L, worldSize - 1)

        fun latitudeToTile(y: Double, worldSize: Long): Long {
            val latitude = y.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE)
            val radians = Math.toRadians(latitude)
            val normalized = (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0
            return floor(normalized * worldSize)
                .toLong()
                .coerceIn(0L, worldSize - 1)
        }
    }
}

interface MapTileOverlay {
    val id: String
    fun clearCache()
    fun remove()
    fun rawOverlay(): Any
}

data class HeatPoint(
    val location: LatLng,
    val intensity: Double = 1.0,
)

data class HeatMapOptions(
    val points: List<HeatPoint>,
    val radiusPixels: Int = 20,
    val opacity: Float = 0.7f,
    val minZoom: Float = 3f,
    val maxZoom: Float = 21f,
    val visible: Boolean = true,
    val zIndex: Float = 0f,
)

interface MapHeatMap {
    var points: List<HeatPoint>
    var visible: Boolean
    var zIndex: Float
    fun remove()
    fun rawOverlay(): Any
}
