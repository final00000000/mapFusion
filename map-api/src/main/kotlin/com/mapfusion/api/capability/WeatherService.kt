package com.mapfusion.api.capability

import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.RequestHandle
import com.mapfusion.api.model.WeatherForecast
import com.mapfusion.api.model.WeatherNow
import com.mapfusion.api.model.WeatherRequest

/** 实时天气与天气预报服务。 */
interface WeatherService {
    fun current(request: WeatherRequest, callback: MapCallback<WeatherNow>): RequestHandle =
        current(request, AsyncCallOptions(), callback)

    fun current(
        request: WeatherRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<WeatherNow>,
    ): RequestHandle

    fun forecast(request: WeatherRequest, callback: MapCallback<WeatherForecast>): RequestHandle =
        forecast(request, AsyncCallOptions(), callback)

    fun forecast(
        request: WeatherRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<WeatherForecast>,
    ): RequestHandle
    fun destroy()
}
