package com.mapfusion.baidu

import com.baidu.mapapi.search.core.SearchResult
import com.baidu.mapapi.search.district.DistrictResult
import com.baidu.mapapi.search.district.DistrictSearch
import com.baidu.mapapi.search.district.DistrictSearchOption
import com.baidu.mapapi.search.district.OnGetDistricSearchResultListener
import com.mapfusion.api.async.AsyncRuntime
import com.mapfusion.api.capability.DistrictSearcher
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.DistrictInfo
import com.mapfusion.api.model.DistrictSearchRequest
import com.mapfusion.api.model.DistrictSearchResult
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.RequestHandle

internal class BaiduDistrictSearcher(
    private val asyncRuntime: AsyncRuntime = AsyncRuntime.DEFAULT,
) : DistrictSearcher {

    private val requests = NativeRequestRegistry<DistrictSearch>(DistrictSearch::destroy)

    override fun search(
        request: DistrictSearchRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<DistrictSearchResult>,
    ): RequestHandle {
        if (requests.isDestroyed) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度行政区搜索器已销毁"),
            )
        }
        if (request.keyword.isBlank()) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "行政区关键词不能为空"),
            )
        }
        val search = runCatching { DistrictSearch.newInstance() }.getOrElse { error ->
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.UNKNOWN, "百度行政区初始化失败：${error.message.orEmpty()}", cause = error),
            )
        }
        val async = requests.trackedRequest(search, asyncRuntime, asyncOptions, callback)
        if (async == null) {
            search.destroy()
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度行政区搜索器已销毁"),
            )
        }
        if (async.isDone) return async
        try {
            search.setOnDistrictSearchListener(OnGetDistricSearchResultListener { result ->
                val response = runCatching {
                    if (result.error == SearchResult.ERRORNO.NO_ERROR) {
                        MapResult.Success(result.toFusion(request))
                    } else {
                        MapResult.Failure(result.error.toDistrictError("百度行政区搜索失败", result.status))
                    }
                }.getOrElse { error ->
                    MapResult.Failure(
                        MapError(ErrorType.UNKNOWN, "百度行政区结果解析失败：${error.message.orEmpty()}", cause = error),
                    )
                }
                async.complete(response)
            })
            val option = DistrictSearchOption()
                .cityName(request.city ?: request.keyword)
                .districtName(request.city?.let { request.keyword }.orEmpty())
            if (!search.searchDistrict(option)) {
                async.failure(MapError(ErrorType.INVALID_PARAM, "百度未接受行政区请求"))
            }
        } catch (error: Throwable) {
            val type = if (error is IllegalArgumentException) ErrorType.INVALID_PARAM else ErrorType.UNKNOWN
            async.failure(MapError(type, "百度行政区请求失败：${error.message.orEmpty()}", cause = error))
        }
        return async
    }

    private fun DistrictResult.toFusion(request: DistrictSearchRequest) = DistrictSearchResult(
        districts = listOf(
            DistrictInfo(
                name = cityName ?: request.keyword,
                cityCode = cityCode.takeIf { it != 0 }?.toString(),
                center = centerPt?.let { com.mapfusion.api.model.LatLng(it.latitude, it.longitude, CoordType.BD09) },
                boundaries = if (request.showBoundary) {
                    polylines.orEmpty().map { line ->
                        line.map { point -> com.mapfusion.api.model.LatLng(point.latitude, point.longitude, CoordType.BD09) }
                    }
                } else emptyList(),
            ),
        ),
    )

    override fun destroy() = requests.destroy()
}

private fun SearchResult.ERRORNO.toDistrictError(prefix: String, status: Int): MapError {
    return toBaiduSearchError(prefix, status)
}
