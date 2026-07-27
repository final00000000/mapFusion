package com.mapfusion.baidu

import com.baidu.mapapi.search.core.SearchResult
import com.baidu.mapapi.search.weather.WeatherDataType
import com.baidu.mapapi.search.weather.WeatherResult
import com.baidu.mapapi.search.weather.WeatherSearch
import com.baidu.mapapi.search.weather.WeatherSearchOption
import com.baidu.mapapi.search.weather.OnGetWeatherResultListener
import com.mapfusion.api.async.AsyncRuntime
import com.mapfusion.api.capability.WeatherService
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.RequestHandle
import com.mapfusion.api.model.WeatherForecast
import com.mapfusion.api.model.WeatherForecastDay
import com.mapfusion.api.model.WeatherNow
import com.mapfusion.api.model.WeatherRequest

internal class BaiduWeatherService(
    private val asyncRuntime: AsyncRuntime = AsyncRuntime.DEFAULT,
) : WeatherService {

    private val requests = NativeRequestRegistry<WeatherSearch>(WeatherSearch::destroy)

    override fun current(
        request: WeatherRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<WeatherNow>,
    ): RequestHandle = request(
        WeatherDataType.WEATHER_DATA_TYPE_REAL_TIME,
        request,
        asyncOptions,
        callback,
    ) { result ->
            val realTime = result.realTimeWeather
                ?: return@request MapResult.Failure(MapError(ErrorType.NO_RESULT, "百度未返回实时天气"))
            val location = result.location
            MapResult.Success(
                WeatherNow(
                    city = location?.city ?: request.city ?: request.adCode.orEmpty(),
                    province = location?.province,
                    adCode = location?.districtID ?: request.adCode,
                    condition = realTime.phenomenon,
                    temperatureC = realTime.temperature.toDouble(),
                    humidityPercent = realTime.relativeHumidity.toDouble(),
                    windDirection = realTime.windDirection,
                    windPower = realTime.windPower,
                    reportTime = realTime.updateTime,
                    airQualityIndex = realTime.airQualityIndex,
                ),
            )
        }

    override fun forecast(
        request: WeatherRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<WeatherForecast>,
    ): RequestHandle = request(
        WeatherDataType.WEATHER_DATA_TYPE_FORECASTS_FOR_DAY,
        request,
        asyncOptions,
        callback,
    ) { result ->
            val location = result.location
            MapResult.Success(
                WeatherForecast(
                    city = location?.city ?: request.city ?: request.adCode.orEmpty(),
                    province = location?.province,
                    adCode = location?.districtID ?: request.adCode,
                    days = result.forecasts.orEmpty().map {
                        WeatherForecastDay(
                            date = it.date.orEmpty(),
                            dayCondition = it.phenomenonDay,
                            nightCondition = it.phenomenonNight,
                            dayTemperatureC = it.highestTemp.toDouble(),
                            nightTemperatureC = it.lowestTemp.toDouble(),
                            dayWindDirection = it.windDirectionDay,
                            nightWindDirection = it.windDirectionNight,
                            dayWindPower = it.windPowerDay,
                            nightWindPower = it.windPowerNight,
                        )
                    },
                    reportTime = null,
                ),
            )
        }

    private fun <T> request(
        type: WeatherDataType,
        request: WeatherRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<T>,
        transform: (WeatherResult) -> MapResult<T>,
    ): RequestHandle {
        if (requests.isDestroyed) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度天气服务已销毁"),
            )
        }
        if (request.city.isNullOrBlank() && request.adCode.isNullOrBlank() && request.location == null) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "天气请求必须提供 city、adCode 或 location"),
            )
        }
        val search = runCatching { WeatherSearch.newInstance() }.getOrElse { error ->
            val errorType = if (error is IllegalArgumentException) ErrorType.INVALID_PARAM else ErrorType.UNKNOWN
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(errorType, "百度天气初始化失败：${error.message.orEmpty()}", cause = error),
            )
        }
        val async = requests.trackedRequest(search, asyncRuntime, asyncOptions, callback)
        if (async == null) {
            search.destroy()
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度天气服务已销毁"),
            )
        }
        if (async.isDone) return async
        try {
            search.setWeatherSearchResultListener(OnGetWeatherResultListener { result ->
                val response = runCatching {
                    if (result.error == SearchResult.ERRORNO.NO_ERROR) {
                        transform(result)
                    } else {
                        MapResult.Failure(result.error.toWeatherError("百度天气请求失败", result.status))
                    }
                }.getOrElse { error ->
                    MapResult.Failure(
                        MapError(ErrorType.UNKNOWN, "百度天气结果解析失败：${error.message.orEmpty()}", cause = error),
                    )
                }
                async.complete(response)
            })
            val option = WeatherSearchOption()
                .weatherDataType(type)
                .districtID(request.adCode ?: request.city)
            request.location?.let { option.location(it.toBaiduSdkLatLng()) }
            if (!search.request(option)) {
                async.failure(MapError(ErrorType.INVALID_PARAM, "百度未接受天气请求"))
            }
        } catch (error: Throwable) {
            val errorType = if (error is IllegalArgumentException) ErrorType.INVALID_PARAM else ErrorType.UNKNOWN
            async.failure(MapError(errorType, "百度天气请求失败：${error.message.orEmpty()}", cause = error))
        }
        return async
    }

    override fun destroy() = requests.destroy()
}

private fun SearchResult.ERRORNO.toWeatherError(prefix: String, status: Int): MapError {
    return toBaiduSearchError(prefix, status)
}
