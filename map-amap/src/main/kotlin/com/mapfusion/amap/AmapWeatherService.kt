package com.mapfusion.amap

import android.content.Context
import com.amap.api.services.core.AMapException
import com.amap.api.services.weather.LocalWeatherForecastResult
import com.amap.api.services.weather.LocalWeatherLiveResult
import com.amap.api.services.weather.WeatherSearch
import com.amap.api.services.weather.WeatherSearchQuery
import com.mapfusion.api.async.AsyncRuntime
import com.mapfusion.api.capability.WeatherService
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.RequestHandle
import com.mapfusion.api.model.WeatherForecast
import com.mapfusion.api.model.WeatherForecastDay
import com.mapfusion.api.model.WeatherNow
import com.mapfusion.api.model.WeatherRequest

internal class AmapWeatherService(private val context: Context) : WeatherService {

    private val runtime = AsyncRuntime.DEFAULT
    private val requests = NativeRequestRegistry<WeatherSearch> { it.setOnWeatherSearchListener(null) }

    override fun current(
        request: WeatherRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<WeatherNow>,
    ): RequestHandle = search(
        request = request,
        type = WeatherSearchQuery.WEATHER_TYPE_LIVE,
        asyncOptions = asyncOptions,
        callback = callback,
        liveMapper = { result, code ->
            val live = result?.liveResult
            if (code == AMapException.CODE_AMAP_SUCCESS && live != null) {
                MapResult.Success(
                    WeatherNow(
                        city = live.city.orEmpty(),
                        province = live.province,
                        adCode = live.adCode,
                        condition = live.weather,
                        temperatureC = live.temperature?.toDoubleOrNull(),
                        humidityPercent = live.humidity?.toDoubleOrNull(),
                        windDirection = live.windDirection,
                        windPower = live.windPower,
                        reportTime = live.reportTime,
                    ),
                )
            } else {
                MapResult.Failure(code.toWeatherError("高德实时天气失败"))
            }
        },
    )

    override fun forecast(
        request: WeatherRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<WeatherForecast>,
    ): RequestHandle = search(
        request = request,
        type = WeatherSearchQuery.WEATHER_TYPE_FORECAST,
        asyncOptions = asyncOptions,
        callback = callback,
        forecastMapper = { result, code ->
            val forecast = result?.forecastResult
            if (code == AMapException.CODE_AMAP_SUCCESS && forecast != null) {
                MapResult.Success(
                    WeatherForecast(
                        city = forecast.city.orEmpty(),
                        province = forecast.province,
                        adCode = forecast.adCode,
                        reportTime = forecast.reportTime,
                        days = forecast.weatherForecast.orEmpty().map {
                            WeatherForecastDay(
                                date = it.date.orEmpty(),
                                dayCondition = it.dayWeather,
                                nightCondition = it.nightWeather,
                                dayTemperatureC = it.dayTemp?.toDoubleOrNull(),
                                nightTemperatureC = it.nightTemp?.toDoubleOrNull(),
                                dayWindDirection = it.dayWindDirection,
                                nightWindDirection = it.nightWindDirection,
                                dayWindPower = it.dayWindPower,
                                nightWindPower = it.nightWindPower,
                            )
                        },
                    ),
                )
            } else {
                MapResult.Failure(code.toWeatherError("高德天气预报失败"))
            }
        },
    )

    private fun <T> search(
        request: WeatherRequest,
        type: Int,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<T>,
        liveMapper: ((LocalWeatherLiveResult?, Int) -> MapResult<T>)? = null,
        forecastMapper: ((LocalWeatherForecastResult?, Int) -> MapResult<T>)? = null,
    ): RequestHandle {
        if (requests.isDestroyed) return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "高德天气服务已销毁"))
        request.location?.let { location ->
            val coordinateError = runCatching { location.toAmapCoordinate() }.exceptionOrNull()
            if (coordinateError != null) {
                return failed(
                    asyncOptions,
                    callback,
                    MapError(ErrorType.INVALID_PARAM, coordinateError.message.orEmpty(), cause = coordinateError),
                )
            }
            return failed(
                asyncOptions,
                callback,
                MapError(ErrorType.UNSUPPORTED, "高德天气 SDK 仅支持按 city/adCode 查询，不能忽略 location"),
            )
        }
        val city = request.city?.takeIf(String::isNotBlank) ?: request.adCode?.takeIf(String::isNotBlank)
            ?: return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "高德天气请求必须提供 city 或 adCode"))
        val search = runCatching { WeatherSearch(context) }.getOrElse {
            return failed(asyncOptions, callback, it.toWeatherError("高德天气初始化失败"))
        }
        val async = runtime.createRequest(
            callback = callback,
            options = asyncOptions,
            terminalAction = Runnable { requests.release(search) },
        )
        if (!requests.register(search, async)) {
            runCatching { search.setOnWeatherSearchListener(null) }
            async.dispose()
            return async
        }
        runCatching {
            requests.withRegistered(search) {
                search.query = WeatherSearchQuery(city, type)
                search.setOnWeatherSearchListener(
                    object : WeatherSearch.OnWeatherSearchListener {
                        override fun onWeatherLiveSearched(result: LocalWeatherLiveResult?, code: Int) {
                            if (type != WeatherSearchQuery.WEATHER_TYPE_LIVE) return
                            val response = runCatching { requireNotNull(liveMapper).invoke(result, code) }
                                .getOrElse { MapResult.Failure(it.toWeatherError("高德实时天气结果解析失败")) }
                            requests.complete(search, response)
                        }

                        override fun onWeatherForecastSearched(result: LocalWeatherForecastResult?, code: Int) {
                            if (type != WeatherSearchQuery.WEATHER_TYPE_FORECAST) return
                            val response = runCatching { requireNotNull(forecastMapper).invoke(result, code) }
                                .getOrElse { MapResult.Failure(it.toWeatherError("高德天气预报结果解析失败")) }
                            requests.complete(search, response)
                        }
                    },
                )
                search.searchWeatherAsyn()
            }
        }.onFailure { async.failure(it.toWeatherError("高德天气初始化失败")) }
        return async
    }

    override fun destroy() = requests.destroy()

    private fun <T> failed(
        options: AsyncCallOptions,
        callback: MapCallback<T>,
        error: MapError,
    ): RequestHandle = runtime.createRequest(callback, options).also { it.failure(error) }
}

private fun Int.toWeatherError(prefix: String): MapError {
    val type = when (this) {
        AMapException.CODE_AMAP_INVALID_USER_KEY,
        AMapException.CODE_AMAP_INVALID_USER_SCODE,
        AMapException.CODE_AMAP_USERKEY_PLAT_NOMATCH -> ErrorType.AUTH
        AMapException.CODE_AMAP_ENGINE_CONNECT_TIMEOUT,
        AMapException.CODE_AMAP_ENGINE_RETURN_TIMEOUT,
        AMapException.CODE_AMAP_CLIENT_NETWORK_EXCEPTION -> ErrorType.NETWORK
        AMapException.CODE_AMAP_SERVICE_INVALID_PARAMS,
        AMapException.CODE_AMAP_CLIENT_INVALID_PARAMETER -> ErrorType.INVALID_PARAM
        AMapException.CODE_AMAP_SUCCESS -> ErrorType.NO_RESULT
        else -> ErrorType.UNKNOWN
    }
    return MapError(type, "$prefix（$this）", rawCode = this)
}

private fun Throwable.toWeatherError(prefix: String): MapError = when (this) {
    is AMapException -> errorCode.toWeatherError("$prefix：${errorMessage.orEmpty()}")
    is IllegalArgumentException -> MapError(ErrorType.INVALID_PARAM, "$prefix：${message.orEmpty()}", cause = this)
    else -> MapError(ErrorType.UNKNOWN, "$prefix：${message.orEmpty()}", cause = this)
}
