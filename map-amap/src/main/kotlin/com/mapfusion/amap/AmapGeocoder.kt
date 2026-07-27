package com.mapfusion.amap

import android.content.Context
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItem as NativePoiItem
import com.amap.api.services.geocoder.GeocodeQuery
import com.amap.api.services.geocoder.GeocodeResult as NativeGeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.geocoder.RegeocodeResult as NativeReverseResult
import com.mapfusion.api.async.AsyncRequest
import com.mapfusion.api.async.AsyncRuntime
import com.mapfusion.api.capability.Geocoder
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.GeocodeResult
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.PoiItem
import com.mapfusion.api.model.RequestHandle
import com.mapfusion.api.model.ReverseGeocodeRequest
import com.mapfusion.api.model.ReverseGeocodeResult

/** 高德地理编码 SDK 的真实适配。 */
internal class AmapGeocoder(
    private val context: Context,
) : Geocoder {

    private val runtime = AsyncRuntime.DEFAULT
    private val requests = NativeRequestRegistry<GeocodeSearch> { it.setOnGeocodeSearchListener(null) }

    override fun geocode(
        address: String,
        city: String?,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<GeocodeResult>,
    ): RequestHandle {
        if (requests.isDestroyed) return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "高德地理编码器已销毁"))
        if (address.isBlank()) return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "地址不能为空"))

        val search = runCatching { GeocodeSearch(context) }.getOrElse {
            return failed(asyncOptions, callback, it.toAmapError("高德地理编码初始化失败"))
        }
        val request = runtime.createRequest(
            callback = callback,
            options = asyncOptions,
            terminalAction = Runnable { requests.release(search) },
        )
        if (!requests.register(search, request)) {
            runCatching { search.setOnGeocodeSearchListener(null) }
            request.dispose()
            return request
        }
        runCatching {
            requests.withRegistered(search) {
                search.setOnGeocodeSearchListener(
                    object : GeocodeSearch.OnGeocodeSearchListener {
                        override fun onGeocodeSearched(result: NativeGeocodeResult?, code: Int) {
                            val first = result?.geocodeAddressList?.firstOrNull()
                            val response = if (code == AMapException.CODE_AMAP_SUCCESS && first?.latLonPoint != null) {
                                MapResult.Success(
                                    GeocodeResult(
                                        location = first.latLonPoint.toFusion(),
                                        formattedAddress = first.formatAddress,
                                        province = first.province,
                                        city = first.city,
                                        district = first.district,
                                        level = first.level,
                                    ),
                                )
                            } else {
                                MapResult.Failure(code.toAmapError("高德地理编码失败"))
                            }
                            requests.complete(search, response)
                        }

                        override fun onRegeocodeSearched(result: NativeReverseResult?, code: Int) = Unit
                    },
                )
                search.getFromLocationNameAsyn(GeocodeQuery(address, city.orEmpty()))
            }
        }.onFailure { request.failure(it.toAmapError("高德地理编码初始化失败")) }
        return request
    }

    override fun reverseGeocode(
        location: LatLng,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<ReverseGeocodeResult>,
    ): RequestHandle = reverseGeocode(ReverseGeocodeRequest(location), asyncOptions, callback)

    override fun reverseGeocode(
        request: ReverseGeocodeRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<ReverseGeocodeResult>,
    ): RequestHandle {
        if (requests.isDestroyed) return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "高德地理编码器已销毁"))
        val point = runCatching { request.location.toAmapPoint() }.getOrElse {
            return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, it.message.orEmpty(), cause = it))
        }
        val search = runCatching { GeocodeSearch(context) }.getOrElse {
            return failed(asyncOptions, callback, it.toAmapError("高德逆地理编码初始化失败"))
        }
        val async = runtime.createRequest(
            callback = callback,
            options = asyncOptions,
            terminalAction = Runnable { requests.release(search) },
        )
        if (!requests.register(search, async)) {
            runCatching { search.setOnGeocodeSearchListener(null) }
            async.dispose()
            return async
        }
        runCatching {
            requests.withRegistered(search) {
                search.setOnGeocodeSearchListener(
                    object : GeocodeSearch.OnGeocodeSearchListener {
                        override fun onGeocodeSearched(result: NativeGeocodeResult?, code: Int) = Unit

                        override fun onRegeocodeSearched(result: NativeReverseResult?, code: Int) {
                            val address = result?.regeocodeAddress
                            val response = if (code == AMapException.CODE_AMAP_SUCCESS && address != null) {
                                MapResult.Success(
                                    ReverseGeocodeResult(
                                        formattedAddress = address.formatAddress.orEmpty(),
                                        country = address.country,
                                        province = address.province,
                                        city = address.city,
                                        district = address.district,
                                        township = address.township,
                                        street = address.streetNumber?.street,
                                        streetNumber = address.streetNumber?.number,
                                        adCode = address.adCode,
                                        pois = address.pois.orEmpty().mapNotNull(NativePoiItem::toFusion),
                                    ),
                                )
                            } else {
                                MapResult.Failure(code.toAmapError("高德逆地理编码失败"))
                            }
                            requests.complete(search, response)
                        }
                    },
                )
                search.getFromLocationAsyn(
                    RegeocodeQuery(
                        point,
                        request.radiusMeters.coerceAtLeast(0).toFloat(),
                        GeocodeSearch.AMAP,
                    ).apply { extensions = GeocodeSearch.EXTENSIONS_ALL },
                )
            }
        }.onFailure { async.failure(it.toAmapError("高德逆地理编码初始化失败")) }
        return async
    }

    override fun destroy() = requests.destroy()

    private fun <T> failed(
        options: AsyncCallOptions,
        callback: MapCallback<T>,
        error: MapError,
    ): RequestHandle = runtime.createRequest(callback, options).also { it.failure(error) }
}

private fun LatLonPoint.toFusion() = LatLng(latitude, longitude, CoordType.GCJ02)
private fun LatLng.toAmapPoint() = toAmapServicePoint()

private fun NativePoiItem.toFusion(): PoiItem? {
    val point = latLonPoint?.toFusion()
        ?.takeIf { it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
        ?: return null
    return PoiItem(
        id = poiId.orEmpty(), name = title.orEmpty(), location = point,
        address = snippet, province = provinceName, city = cityName, district = adName,
        category = typeDes, phone = tel, distanceMeters = distance.takeIf { it >= 0 },
    )
}

private fun Int.toAmapError(prefix: String): MapError {
    val type = when (this) {
        AMapException.CODE_AMAP_SUCCESS -> ErrorType.NO_RESULT
        AMapException.CODE_AMAP_INVALID_USER_KEY,
        AMapException.CODE_AMAP_INVALID_USER_SCODE,
        AMapException.CODE_AMAP_USERKEY_PLAT_NOMATCH,
        AMapException.CODE_AMAP_INSUFFICIENT_PRIVILEGES -> ErrorType.AUTH
        AMapException.CODE_AMAP_ENGINE_CONNECT_TIMEOUT,
        AMapException.CODE_AMAP_ENGINE_RETURN_TIMEOUT,
        AMapException.CODE_AMAP_CLIENT_NETWORK_EXCEPTION,
        AMapException.CODE_AMAP_CLIENT_UNKNOWHOST_EXCEPTION -> ErrorType.NETWORK
        AMapException.CODE_AMAP_SERVICE_INVALID_PARAMS,
        AMapException.CODE_AMAP_SERVICE_MISSING_REQUIRED_PARAMS,
        AMapException.CODE_AMAP_CLIENT_INVALID_PARAMETER -> ErrorType.INVALID_PARAM
        else -> ErrorType.UNKNOWN
    }
    return MapError(type, "$prefix（$this）", rawCode = this)
}

private fun Throwable.toAmapError(prefix: String): MapError = when (this) {
    is AMapException -> errorCode.toAmapError("$prefix：$errorMessage")
    is IllegalArgumentException -> MapError(ErrorType.INVALID_PARAM, "$prefix：${message.orEmpty()}", cause = this)
    else -> MapError(ErrorType.UNKNOWN, "$prefix：${message.orEmpty()}", cause = this)
}
