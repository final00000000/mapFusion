package com.mapfusion.api.model

import java.util.concurrent.atomic.AtomicBoolean

/** 路线起点或终点 Marker 的样式，位置由 [RouteRequest] 和路线几何决定。 */
data class RouteMarkerOptions(
    val icon: MarkerIcon = MarkerIcon.Default,
    val title: String? = null,
    val snippet: String? = null,
    val anchorU: Float = 0.5f,
    val anchorV: Float = 1.0f,
    val rotation: Float = 0f,
    val alpha: Float = 1f,
    val flat: Boolean = false,
    val visible: Boolean = true,
    val zIndex: Float = 101f,
    val tag: Any? = null,
)

/** 路线、起点和终点的统一绘制样式。 */
data class RouteOverlayOptions(
    val startMarker: RouteMarkerOptions? = RouteMarkerOptions(title = "起点"),
    val endMarker: RouteMarkerOptions? = RouteMarkerOptions(title = "终点"),
    val lineWidth: Float = 14f,
    val lineColor: Int = 0xFF1976D2.toInt(),
    val lineDotted: Boolean = false,
    val lineGeodesic: Boolean = false,
    val lineClickable: Boolean = true,
    val visible: Boolean = true,
    val lineZIndex: Float = 100f,
    val lineTag: Any? = null,
)

/** 一次路线绘制产生的全部覆盖物句柄。 */
interface MapRouteOverlay : AutoCloseable {
    val path: RoutePath
    val startMarker: MapMarker?
    val endMarker: MapMarker?
    val polyline: MapPolyline

    /** 幂等移除路线及其起终点。 */
    fun remove()

    override fun close() = remove()
}

internal class DefaultMapRouteOverlay(
    override val path: RoutePath,
    override val startMarker: MapMarker?,
    override val endMarker: MapMarker?,
    override val polyline: MapPolyline,
) : MapRouteOverlay {

    private val removed = AtomicBoolean(false)

    override fun remove() {
        if (!removed.compareAndSet(false, true)) return
        var firstFailure: Throwable? = null
        listOfNotNull(startMarker, endMarker, polyline).forEach { overlay ->
            try {
                overlay.remove()
            } catch (error: Throwable) {
                if (firstFailure == null) {
                    firstFailure = error
                } else if (error !== firstFailure) {
                    firstFailure?.addSuppressed(error)
                }
            }
        }
        firstFailure?.let { throw it }
    }
}

internal fun RouteMarkerOptions.at(position: LatLng): MarkerOptions = MarkerOptions(
    position = position,
    title = title,
    snippet = snippet,
    icon = icon,
    anchorU = anchorU,
    anchorV = anchorV,
    rotation = rotation,
    alpha = alpha,
    flat = flat,
    visible = visible,
    zIndex = zIndex,
    tag = tag,
)
