package com.mapfusion.amap

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

/** 拉起高德地图原生路线页；未安装高德地图时降级到浏览器。 */
internal class AmapNavigator : Navigator {
    override fun startNavigation(
        context: Context,
        origin: LatLng?,
        destination: LatLng,
        mode: TravelMode,
    ): MapResult<Unit> {
        if (!supportsMode(mode)) {
            return MapResult.Failure(
                MapError(ErrorType.UNSUPPORTED, "高德地图 URI 不支持${mode.displayName()}导航"),
            )
        }
        val routeType = mode.toAmapRouteType()
            ?: return MapResult.Failure(
                MapError(ErrorType.UNKNOWN, "高德导航模式映射缺失：${mode.name}"),
            )
        val webMode = mode.toAmapWebMode()
            ?: return MapResult.Failure(
                MapError(ErrorType.UNKNOWN, "高德网页导航模式映射缺失：${mode.name}"),
            )
        val convertedOrigin: LatLng?
        val convertedDestination: LatLng
        try {
            convertedOrigin = origin?.toAmapCoordinate()
            convertedDestination = destination.toAmapCoordinate()
        } catch (error: IllegalArgumentException) {
            return MapResult.Failure(
                MapError(ErrorType.INVALID_PARAM, error.message.orEmpty(), cause = error),
            )
        }
        val nativeUri = buildString {
            append("androidamap://route?sourceApplication=mapfusion")
            convertedOrigin?.let {
                append("&slat=${it.latitude}&slon=${it.longitude}&sname=起点")
            }
            append("&dlat=${convertedDestination.latitude}&dlon=${convertedDestination.longitude}&dname=终点")
            append("&dev=0&t=$routeType")
        }
        val webUri = buildString {
            append("https://uri.amap.com/navigation?")
            append("to=${convertedDestination.longitude},${convertedDestination.latitude},终点")
            convertedOrigin?.let { append("&from=${it.longitude},${it.latitude},起点") }
            append("&mode=$webMode&policy=1&src=mapfusion&coordinate=gaode&callnative=1")
        }
        return launchAmapWithFallback(context, nativeUri, webUri)
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

private fun TravelMode.toAmapRouteType() = when (this) {
    TravelMode.DRIVING -> 0
    TravelMode.TRANSIT -> 1
    TravelMode.WALKING -> 2
    TravelMode.BICYCLE, TravelMode.RIDING -> 3
    TravelMode.ELECTRIC_BICYCLE -> null
}

private fun TravelMode.toAmapWebMode() = when (this) {
    TravelMode.DRIVING -> "car"
    TravelMode.TRANSIT -> "bus"
    TravelMode.WALKING -> "walk"
    TravelMode.BICYCLE, TravelMode.RIDING -> "ride"
    TravelMode.ELECTRIC_BICYCLE -> null
}

private fun launchAmapWithFallback(
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
    return MapResult.Failure(navigationLaunchError("高德", nativeFailure, webFailure))
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
