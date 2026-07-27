package com.mapfusion.api.model

data class DistrictSearchRequest(
    val keyword: String,
    val city: String? = null,
    val showBoundary: Boolean = true,
    val subDistrictLevel: Int = 1,
)

data class DistrictInfo(
    val name: String,
    val level: String? = null,
    val cityCode: String? = null,
    val adCode: String? = null,
    val center: LatLng? = null,
    /** 每一项是一条闭合边界环，坐标系为当前厂商坐标系。 */
    val boundaries: List<List<LatLng>> = emptyList(),
    val children: List<DistrictInfo> = emptyList(),
)

data class DistrictSearchResult(
    val districts: List<DistrictInfo>,
)

data class WeatherRequest(
    val city: String? = null,
    val adCode: String? = null,
    val location: LatLng? = null,
)

data class WeatherNow(
    val city: String,
    val province: String? = null,
    val adCode: String? = null,
    val condition: String? = null,
    val temperatureC: Double? = null,
    val humidityPercent: Double? = null,
    val windDirection: String? = null,
    val windPower: String? = null,
    val reportTime: String? = null,
    val airQualityIndex: Int? = null,
)

data class WeatherForecastDay(
    val date: String,
    val dayCondition: String? = null,
    val nightCondition: String? = null,
    val dayTemperatureC: Double? = null,
    val nightTemperatureC: Double? = null,
    val dayWindDirection: String? = null,
    val nightWindDirection: String? = null,
    val dayWindPower: String? = null,
    val nightWindPower: String? = null,
)

data class WeatherForecast(
    val city: String,
    val province: String? = null,
    val adCode: String? = null,
    val reportTime: String? = null,
    val days: List<WeatherForecastDay>,
)
