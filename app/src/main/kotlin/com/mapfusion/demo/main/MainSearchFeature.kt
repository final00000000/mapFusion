package com.mapfusion.demo.main

import android.graphics.Color
import com.mapfusion.api.model.CameraUpdate
import com.mapfusion.api.model.DistrictSearchRequest
import com.mapfusion.api.model.DistrictSearchResult
import com.mapfusion.api.model.GeocodeRequest
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.MarkerOptions
import com.mapfusion.api.model.PoiSearchRequest
import com.mapfusion.api.model.PoiSearchResult
import com.mapfusion.api.model.PoiSearchType
import com.mapfusion.api.model.PoiSort
import com.mapfusion.api.model.PolygonOptions
import com.mapfusion.api.model.RequestHandle
import com.mapfusion.api.model.WeatherForecast
import com.mapfusion.api.model.WeatherNow
import com.mapfusion.api.model.WeatherRequest

/** 单次查询功能：统一请求托管与成功结果展示，不负责权限弹窗或 Session 生命周期。 */
internal class MainSearchFeature(
    /** Activity 绑定的地图宿主；会话组件、坐标与统一请求守卫都经它访问。 */
    private val host: MainMapHost
) {
    /** 解析公开示例地址，并只替换 Demo 结果 Marker，不清除导航或轨迹句柄。 */
    fun geocode() = execute("geocode", "地址解析", { callback ->
        requireNotNull(host.session?.geocoder) { "当前厂商不支持地理编码" }
            .geocode(GeocodeRequest(address = "天安门", city = "北京"), callback)
    }) { result ->
        host.controller?.apply {
            host.overlays.clear(); addMarker(MarkerOptions(result.location, "天安门", result.formattedAddress))
            moveCamera(CameraUpdate(target = result.location, zoom = 17f))
        }
        host.messages.success("地址解析成功", result.formattedAddress ?: result.location.toString())
    }

    /** 以当前操作中心执行逆地理；成功数据仅作为页面摘要，不持久化用户位置。 */
    fun reverseGeocode() = execute("reverseGeocode", "逆地理", { callback ->
        requireNotNull(host.session?.geocoder) { "当前厂商不支持逆地理编码" }
            .reverseGeocode(host.coordinates.center(), callback)
    }) { result -> host.messages.success("逆地理成功", "${result.formattedAddress} · 周边 ${result.pois.size} 个 POI") }

    /** 搜索附近咖啡店，厂商请求对象只在启动调用期间使用，不被状态层持有。 */
    fun poi() {
        val center = host.coordinates.center()
        execute("poi", "POI 搜索", { callback ->
            requireNotNull(host.session?.poiSearcher) { "当前厂商不支持 POI 搜索" }.search(
                PoiSearchRequest(type = PoiSearchType.NEARBY, keyword = "咖啡", center = center,
                    radiusMeters = 2_000, pageSize = 15, sort = PoiSort.DISTANCE), callback)
        }) { showPoi(center, it) }
    }

    /** 展示最多十二个 POI 并适配视野，只清本功能所属的 Demo 句柄。 */
    private fun showPoi(center: com.mapfusion.api.model.LatLng, result: PoiSearchResult) {
        val items = result.items.take(12)
        host.controller?.apply {
            host.overlays.clear(); addMarker(MarkerOptions(center, "搜索中心", zIndex = 30f))
            items.forEach { addMarker(MarkerOptions(it.location, it.name, it.address, zIndex = 10f)) }
            fitPoints(listOf(center) + items.map { it.location }, paddingPixels = host.dp(56))
        }
        host.messages.success("POI 搜索成功 · ${result.items.size}/${result.totalCount}",
            items.take(3).joinToString("、") { it.name }.ifBlank { "无可展示名称" })
    }

    /** 请求公开示例行政区的边界，保持原 Demo 的检索参数。 */
    fun district() = execute("district", "行政区搜索", { callback ->
        requireNotNull(host.session?.districtSearcher) { "当前厂商不支持行政区搜索" }
            .search(DistrictSearchRequest(keyword = "海淀区", city = "北京", showBoundary = true), callback)
    }, ::showDistrict)

    /** 空行政区结果按失败处理；有效边界按独立环绘制，不伪造不存在的数据。 */
    private fun showDistrict(result: DistrictSearchResult) {
        val district = result.districts.firstOrNull()
        if (district == null) { host.messages.error("行政区搜索失败", "厂商返回成功但没有行政区数据"); return }
        val rings = district.boundaries.filter { it.size >= 3 }
        val points = rings.flatten()
        host.controller?.apply {
            host.overlays.clear()
            rings.forEach { addPolygon(PolygonOptions(it, strokeWidth = 6f, strokeColor = DemoMapAssets.accent,
                fillColor = Color.argb(42, 25, 118, 210), zIndex = 5f)) }
            when {
                points.isNotEmpty() -> fitPoints(points, paddingPixels = host.dp(56))
                district.center != null -> moveCamera(CameraUpdate(target = district.center, zoom = 12f))
            }
        }
        host.messages.success("行政区搜索成功 · ${district.name}",
            "${district.adCode ?: district.cityCode ?: "无行政区编码"} · ${rings.size} 条边界 · ${district.children.size} 个下级行政区")
    }

    /** 查询公开示例城市的实时天气，缺失字段由格式化层明确展示为未知。 */
    fun weatherNow() = execute("weatherNow", "实时天气", { callback ->
        requireNotNull(host.session?.weatherService) { "当前厂商不支持天气服务" }
            .current(weatherRequest(), callback)
    }, ::showWeather)

    /** 将实时天气值转换成摘要，不将空值当成零度或无风。 */
    private fun showWeather(weather: WeatherNow) {
        val temperature = weather.temperatureC?.let { "${it.toInt()} °C" } ?: "温度未知"
        val condition = weather.condition?.takeIf(String::isNotBlank) ?: "天气未知"
        val humidity = weather.humidityPercent?.let { "湿度 ${it.toInt()}%" } ?: "湿度未知"
        val wind = listOfNotNull(weather.windDirection, weather.windPower).filter(String::isNotBlank)
            .joinToString(" ").ifBlank { "风况未知" }
        host.messages.success("${weather.city} · $condition · $temperature", "$humidity · $wind · ${weather.reportTime.orEmpty()}")
    }

    /** 查询逐日预报，仍使用请求仓库的同键替换与迟到回调隔离。 */
    fun weatherForecast() = execute("weatherForecast", "天气预报", { callback ->
        requireNotNull(host.session?.weatherService) { "当前厂商不支持天气服务" }
            .forecast(weatherRequest(), callback)
    }, ::showForecast)

    /** 展示前三天摘要，明确保留厂商没有逐日预报的情况。 */
    private fun showForecast(forecast: WeatherForecast) {
        val summary = forecast.days.take(3).joinToString("；") {
            "${it.date} ${it.dayCondition ?: it.nightCondition ?: "天气未知"} ${it.nightTemperatureC?.toInt() ?: "?"}~${it.dayTemperatureC?.toInt() ?: "?"} °C"
        }
        host.messages.success("${forecast.city}天气预报 · ${forecast.days.size} 天", summary.ifBlank { "厂商未返回逐日预报" })
    }

    /** 统一公开示例天气参数，避免各按钮维护重复城市编码。 */
    private fun weatherRequest(): WeatherRequest = WeatherRequest(city = "北京", adCode = "110100")

    /** 同键查询交由仓库取消替换；只有当前会话回调才能显示成功或失败。 */
    private fun <T> execute(key: String, title: String, launch: (MapCallback<T>) -> RequestHandle,
        onSuccess: (T) -> Unit) = host.request(title) { epoch ->
        host.requests.execute(key, launch, MapCallback { result -> host.deliver(epoch) {
            when (result) {
                is MapResult.Success -> onSuccess(result.data)
                is MapResult.Failure -> host.messages.mapError("$title 失败", result.error)
            }
        } })
    }
}
