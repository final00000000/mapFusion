package com.mapfusion.baidu

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.mapfusion.api.capability.Navigator
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.TravelMode

/** 拉起百度地图原生路线页；未安装百度地图时降级到浏览器。 */
internal class BaiduNavigator : Navigator {
    override fun startNavigation(
        context: Context,
        origin: LatLng?,
        destination: LatLng,
        mode: TravelMode,
    ): MapResult<Unit> {
        if (!supportsMode(mode)) {
            return MapResult.Failure(
                MapError(ErrorType.UNSUPPORTED, "百度地图 URI 不支持${mode.displayName()}导航"),
            )
        }
        val baiduMode = mode.toBaiduMode()
            ?: return MapResult.Failure(
                MapError(ErrorType.UNKNOWN, "百度导航模式映射缺失：${mode.name}"),
            )
        val convertedOrigin: LatLng?
        val convertedDestination: LatLng
        try {
            convertedOrigin = origin?.toBaiduCoordinate()
            convertedDestination = destination.toBaiduCoordinate()
        } catch (error: IllegalArgumentException) {
            return MapResult.Failure(
                MapError(ErrorType.INVALID_PARAM, error.message.orEmpty(), cause = error),
            )
        }
        val nativeUri = buildString {
            append("baidumap://map/direction?")
            append("origin=")
            append(convertedOrigin?.let { "latlng:${it.latitude},${it.longitude}|name:起点" } ?: "我的位置")
            append("&destination=latlng:${convertedDestination.latitude},${convertedDestination.longitude}|name:终点")
            append("&mode=$baiduMode&coord_type=bd09ll&src=com.mapfusion")
        }
        val webUri = buildString {
            append("https://api.map.baidu.com/direction?")
            append("origin=")
            append(convertedOrigin?.let { "latlng:${it.latitude},${it.longitude}" } ?: "我的位置")
            append("&destination=latlng:${convertedDestination.latitude},${convertedDestination.longitude}")
            append("&mode=$baiduMode&region=全国&output=html&src=com.mapfusion")
        }
        return launchBaiduWithFallback(context, nativeUri, webUri)
    }
}

private fun TravelMode.displayName() = when (canonical()) {
    TravelMode.DRIVING -> "驾车"
    TravelMode.WALKING -> "步行"
    TravelMode.BICYCLE -> "自行车"
    TravelMode.ELECTRIC_BICYCLE -> "电动车"
    TravelMode.TRANSIT -> "公交"
    TravelMode.RIDING -> "自行车"
}

private fun TravelMode.toBaiduMode() = when (this) {
    TravelMode.DRIVING -> "driving"
    TravelMode.WALKING -> "walking"
    TravelMode.BICYCLE, TravelMode.RIDING -> "riding"
    TravelMode.ELECTRIC_BICYCLE -> null
    TravelMode.TRANSIT -> "transit"
}

private fun launchBaiduWithFallback(
    context: Context,
    nativeUri: String,
    webUri: String,
): MapResult<Unit> {
    val nativeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(nativeUri)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // Android 11 包可见性限制下 resolveActivity 可能误报 null，直接启动并捕获失败更可靠。
    val nativeFailure = context.tryStartActivity(nativeIntent)
        ?: return MapResult.Success(Unit)
    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUri)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val webFailure = context.tryStartActivity(webIntent)
        ?: return MapResult.Success(Unit)
    return MapResult.Failure(navigationLaunchError("百度", nativeFailure, webFailure))
}

private fun Context.tryStartActivity(intent: Intent): RuntimeException? = try {
    startActivity(intent)
    null
} catch (error: ActivityNotFoundException) {
    error
} catch (error: SecurityException) {
    error
} catch (error: RuntimeException) {
    error
}

private fun navigationLaunchError(
    providerName: String,
    nativeFailure: RuntimeException,
    webFailure: RuntimeException,
): MapError {
    if (nativeFailure !== webFailure) webFailure.addSuppressed(nativeFailure)
    val failures = listOf(nativeFailure, webFailure)
    val type = when {
        failures.any { it is SecurityException } -> ErrorType.PERMISSION
        failures.all { it is ActivityNotFoundException } -> ErrorType.UNSUPPORTED
        else -> ErrorType.UNKNOWN
    }
    return MapError(
        type = type,
        message = "无法打开${providerName}地图客户端或网页导航",
        rawMessage = failures.joinToString(" -> ") { it::class.java.simpleName },
        cause = webFailure,
    )
}
