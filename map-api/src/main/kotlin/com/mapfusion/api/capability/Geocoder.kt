package com.mapfusion.api.capability

import com.mapfusion.api.model.GeocodeResult
import com.mapfusion.api.model.GeocodeRequest
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.ReverseGeocodeResult
import com.mapfusion.api.model.ReverseGeocodeRequest
import com.mapfusion.api.model.RequestHandle

/**
 * 地理编码能力:地址 <-> 坐标 互转。
 * 归一百度 GeoCoder / 高德 GeocodeSearch。
 */
interface Geocoder {

    fun geocode(request: GeocodeRequest, callback: MapCallback<GeocodeResult>): RequestHandle =
        geocode(request, AsyncCallOptions(), callback)

    fun geocode(
        request: GeocodeRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<GeocodeResult>,
    ): RequestHandle = geocode(request.address, request.city, asyncOptions, callback)

    /** 正地理编码:地址描述 -> 坐标。city 可选,用于消歧。 */
    fun geocode(
        address: String,
        city: String? = null,
        callback: MapCallback<GeocodeResult>,
    ): RequestHandle = geocode(address, city, AsyncCallOptions(), callback)

    fun geocode(
        address: String,
        city: String? = null,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<GeocodeResult>,
    ): RequestHandle

    /** 逆地理编码:坐标 -> 地址描述。 */
    fun reverseGeocode(
        location: LatLng,
        callback: MapCallback<ReverseGeocodeResult>,
    ): RequestHandle = reverseGeocode(location, AsyncCallOptions(), callback)

    fun reverseGeocode(
        location: LatLng,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<ReverseGeocodeResult>,
    ): RequestHandle

    fun reverseGeocode(
        request: ReverseGeocodeRequest,
        callback: MapCallback<ReverseGeocodeResult>,
    ): RequestHandle = reverseGeocode(request, AsyncCallOptions(), callback)

    fun reverseGeocode(
        request: ReverseGeocodeRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<ReverseGeocodeResult>,
    ): RequestHandle = reverseGeocode(request.location, asyncOptions, callback)

    fun destroy()
}
