package com.mapfusion.baidu

import com.baidu.mapapi.search.core.PoiInfo
import com.baidu.mapapi.search.core.SearchResult
import com.baidu.mapapi.search.geocode.GeoCodeOption
import com.baidu.mapapi.search.geocode.GeoCodeResult as NativeGeocodeResult
import com.baidu.mapapi.search.geocode.GeoCoder
import com.baidu.mapapi.search.geocode.OnGetGeoCoderResultListener
import com.baidu.mapapi.search.geocode.ReverseGeoCodeOption
import com.baidu.mapapi.search.geocode.ReverseGeoCodeResult as NativeReverseResult
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
import com.mapfusion.api.model.ReverseGeocodeRequest
import com.mapfusion.api.model.ReverseGeocodeResult
import com.mapfusion.api.model.RequestHandle

/** 百度地理编码 SDK 的真实适配。 */
internal class BaiduGeocoder(
    private val asyncRuntime: AsyncRuntime = AsyncRuntime.DEFAULT,
) : Geocoder {

    private val requests = NativeRequestRegistry<GeoCoder>(GeoCoder::destroy)

    override fun geocode(
        address: String,
        city: String?,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<GeocodeResult>,
    ): RequestHandle {
        if (requests.isDestroyed) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度地理编码器已销毁"),
            )
        }
        if (address.isBlank()) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "地理编码地址不能为空"),
            )
        }
        val coder = runCatching { GeoCoder.newInstance() }.getOrElse {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                it.toGeocodeError("百度地理编码初始化失败"),
            )
        }
        val async = requests.trackedRequest(coder, asyncRuntime, asyncOptions, callback)
        if (async == null) {
            coder.destroy()
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度地理编码器已销毁"),
            )
        }
        if (async.isDone) return async
        val accepted = try {
            coder.setOnGetGeoCodeResultListener(
            object : OnGetGeoCoderResultListener {
                override fun onGetGeoCodeResult(result: NativeGeocodeResult) {
                    val response = runCatching {
                        if (result.error == SearchResult.ERRORNO.NO_ERROR && result.location != null) {
                            MapResult.Success(
                                GeocodeResult(
                                    location = result.location.toFusion(),
                                    formattedAddress = result.address,
                                    city = city,
                                    level = result.level,
                                ),
                            )
                        } else {
                            MapResult.Failure(result.toMapError("百度地理编码失败"))
                        }
                    }.getOrElse { MapResult.Failure(it.toGeocodeError("百度地理编码结果解析失败")) }
                    async.complete(response)
                }

                override fun onGetReverseGeoCodeResult(result: NativeReverseResult) = Unit
            },
            )
            coder.geocode(GeoCodeOption().address(address).city(city.orEmpty()))
        } catch (error: Throwable) {
            async.failure(error.toGeocodeError("百度地理编码失败"))
            return async
        }
        if (!accepted) {
            async.failure(MapError(ErrorType.INVALID_PARAM, "百度未接受地理编码请求"))
        }
        return async
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
        if (requests.isDestroyed) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度地理编码器已销毁"),
            )
        }
        val coder = runCatching { GeoCoder.newInstance() }.getOrElse {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                it.toGeocodeError("百度逆地理编码初始化失败"),
            )
        }
        val async = requests.trackedRequest(coder, asyncRuntime, asyncOptions, callback)
        if (async == null) {
            coder.destroy()
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度地理编码器已销毁"),
            )
        }
        if (async.isDone) return async
        val accepted = try {
            coder.setOnGetGeoCodeResultListener(
            object : OnGetGeoCoderResultListener {
                override fun onGetGeoCodeResult(result: NativeGeocodeResult) = Unit

                override fun onGetReverseGeoCodeResult(result: NativeReverseResult) {
                    val response = runCatching {
                        if (result.error == SearchResult.ERRORNO.NO_ERROR) {
                            val detail = result.addressDetail
                            MapResult.Success(
                                ReverseGeocodeResult(
                                    formattedAddress = result.address.orEmpty(),
                                    country = detail?.countryName,
                                    province = detail?.province,
                                    city = detail?.city,
                                    district = detail?.district,
                                    township = detail?.town,
                                    street = detail?.street,
                                    streetNumber = detail?.streetNumber,
                                    adCode = result.adcode.takeIf { it > 0 }?.toString(),
                                    pois = result.poiList.orEmpty().mapNotNull(PoiInfo::toFusion),
                                ),
                            )
                        } else {
                            MapResult.Failure(result.toMapError("百度逆地理编码失败"))
                        }
                    }.getOrElse { MapResult.Failure(it.toGeocodeError("百度逆地理编码结果解析失败")) }
                    async.complete(response)
                }
            },
            )
            coder.reverseGeoCode(
                ReverseGeoCodeOption()
                    .location(request.location.toBaidu())
                    .radius(request.radiusMeters.coerceIn(0, 1_000))
                    .newVersion(1),
            )
        } catch (error: Throwable) {
            async.failure(error.toGeocodeError("百度逆地理编码失败"))
            return async
        }
        if (!accepted) {
            async.failure(MapError(ErrorType.INVALID_PARAM, "百度未接受逆地理编码请求"))
        }
        return async
    }

    override fun destroy() = requests.destroy()
}

private fun Throwable.toGeocodeError(prefix: String): MapError = when (this) {
    is IllegalArgumentException -> MapError(ErrorType.INVALID_PARAM, "$prefix：${message.orEmpty()}", cause = this)
    else -> MapError(ErrorType.UNKNOWN, "$prefix：${message.orEmpty()}", cause = this)
}

private fun com.baidu.mapapi.model.LatLng.toFusion() = LatLng(latitude, longitude, CoordType.BD09)
private fun LatLng.toBaidu() = toBaiduSdkLatLng()

private fun PoiInfo.toFusion(): PoiItem? {
    val point = location?.toFusion()
        ?.takeIf { it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
        ?: return null
    return PoiItem(
        id = uid.orEmpty(), name = name.orEmpty(), location = point,
        address = address, province = province, city = city, district = area,
        category = tag, phone = phoneNum, distanceMeters = distance.takeIf { it >= 0 },
    )
}

private fun com.baidu.mapapi.search.core.SearchResult.toMapError(prefix: String): MapError {
    val typeOverride = if (error == SearchResult.ERRORNO.NO_DATA_FOR_LATLNG) {
        ErrorType.NO_RESULT
    } else {
        null
    }
    return error.toBaiduSearchError(prefix, status, typeOverride)
}
