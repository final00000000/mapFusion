package com.mapfusion.amap

import android.content.Context
import com.amap.api.services.core.AMapException
import com.amap.api.services.district.DistrictItem
import com.amap.api.services.district.DistrictResult
import com.amap.api.services.district.DistrictSearch
import com.amap.api.services.district.DistrictSearchQuery
import com.mapfusion.api.async.AsyncRuntime
import com.mapfusion.api.capability.DistrictSearcher
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.DistrictInfo
import com.mapfusion.api.model.DistrictSearchRequest
import com.mapfusion.api.model.DistrictSearchResult
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.RequestHandle

internal class AmapDistrictSearcher(private val context: Context) : DistrictSearcher {

    private val runtime = AsyncRuntime.DEFAULT
    private val requests = NativeRequestRegistry<DistrictSearch> { it.setOnDistrictSearchListener(null) }

    override fun search(
        request: DistrictSearchRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<DistrictSearchResult>,
    ): RequestHandle {
        if (requests.isDestroyed) {
            return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "高德行政区搜索器已销毁"))
        }
        if (request.keyword.isBlank()) {
            return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "行政区关键词不能为空"))
        }
        val search = runCatching { DistrictSearch(context) }.getOrElse {
            return failed(asyncOptions, callback, it.toDistrictError("高德行政区初始化失败"))
        }
        val async = runtime.createRequest(
            callback = callback,
            options = asyncOptions,
            terminalAction = Runnable { requests.release(search) },
        )
        if (!requests.register(search, async)) {
            runCatching { search.setOnDistrictSearchListener(null) }
            async.dispose()
            return async
        }
        runCatching {
            val query = DistrictSearchQuery().apply {
                keywords = request.keyword
                keywordsLevel = DistrictSearchQuery.KEYWORDS_DISTRICT
                subDistrict = request.subDistrictLevel.coerceIn(0, 3)
                isShowBoundary = request.showBoundary
                isShowChild = request.subDistrictLevel > 0
                pageNum = 0
                pageSize = 20
            }
            requests.withRegistered(search) {
                search.setQuery(query)
                search.setOnDistrictSearchListener { result ->
                    val response = runCatching { result.toFusionResult(request) }
                        .getOrElse { MapResult.Failure(it.toDistrictError("高德行政区结果解析失败")) }
                    requests.complete(search, response)
                }
                search.searchDistrictAsyn()
            }
        }.onFailure { async.failure(it.toDistrictError("高德行政区初始化失败")) }
        return async
    }

    private fun DistrictResult.toFusionResult(request: DistrictSearchRequest): MapResult<DistrictSearchResult> {
        val code = aMapException?.errorCode ?: AMapException.CODE_AMAP_SUCCESS
        val districts = district.orEmpty()
        return if (code == AMapException.CODE_AMAP_SUCCESS && districts.isNotEmpty()) {
            MapResult.Success(DistrictSearchResult(districts.map { it.toFusion(request.showBoundary) }))
        } else {
            MapResult.Failure(code.toDistrictError("高德行政区搜索失败"))
        }
    }

    override fun destroy() = requests.destroy()

    private fun failed(
        options: AsyncCallOptions,
        callback: MapCallback<DistrictSearchResult>,
        error: MapError,
    ): RequestHandle = runtime.createRequest(callback, options).also { it.failure(error) }
}

private fun DistrictItem.toFusion(showBoundary: Boolean): DistrictInfo = DistrictInfo(
    name = name.orEmpty(),
    level = level,
    cityCode = citycode,
    adCode = adcode,
    center = center?.let { LatLng(it.latitude, it.longitude, CoordType.GCJ02) },
    boundaries = if (showBoundary) {
        districtBoundary().orEmpty().flatMap(String::toBoundaryRings)
    } else emptyList(),
    children = subDistrict.orEmpty().map { it.toFusion(showBoundary) },
)

private fun String.toBoundaryRings(): List<List<LatLng>> = split('|').mapNotNull { ring ->
    ring.split(';').mapNotNull { pair ->
        val values = pair.split(',')
        val longitude = values.getOrNull(0)?.toDoubleOrNull()
        val latitude = values.getOrNull(1)?.toDoubleOrNull()
        if (latitude != null && longitude != null) LatLng(latitude, longitude, CoordType.GCJ02) else null
    }.takeIf { it.size >= 2 }
}

private fun Int.toDistrictError(prefix: String): MapError {
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

private fun Throwable.toDistrictError(prefix: String): MapError = when (this) {
    is AMapException -> errorCode.toDistrictError("$prefix：${errorMessage.orEmpty()}")
    else -> MapError(ErrorType.UNKNOWN, "$prefix：${message.orEmpty()}", cause = this)
}
