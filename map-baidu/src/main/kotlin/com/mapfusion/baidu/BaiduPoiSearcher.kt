package com.mapfusion.baidu

import com.baidu.mapapi.search.core.PoiDetailInfo
import com.baidu.mapapi.search.core.PoiInfo
import com.baidu.mapapi.search.core.SearchResult
import com.baidu.mapapi.search.poi.OnGetPoiSearchResultListener
import com.baidu.mapapi.search.poi.PoiCitySearchOption
import com.baidu.mapapi.search.poi.PoiDetailResult
import com.baidu.mapapi.search.poi.PoiDetailSearchOption
import com.baidu.mapapi.search.poi.PoiDetailSearchResult
import com.baidu.mapapi.search.poi.PoiIndoorResult
import com.baidu.mapapi.search.poi.PoiNearbySearchOption
import com.baidu.mapapi.search.poi.PoiResult
import com.baidu.mapapi.search.poi.PoiSearch
import com.baidu.mapapi.search.poi.PoiSortType
import com.baidu.mapapi.search.sug.SuggestionResult
import com.baidu.mapapi.search.sug.SuggestionSearch
import com.baidu.mapapi.search.sug.SuggestionSearchOption
import com.mapfusion.api.async.AsyncRuntime
import com.mapfusion.api.capability.PoiSearcher
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.PoiItem
import com.mapfusion.api.model.PoiSearchRequest
import com.mapfusion.api.model.PoiSearchResult
import com.mapfusion.api.model.PoiSearchType
import com.mapfusion.api.model.PoiSort
import com.mapfusion.api.model.PoiSuggestion
import com.mapfusion.api.model.PoiSuggestionRequest
import com.mapfusion.api.model.RequestHandle

/** 百度 POI 检索、详情与输入提示的真实适配。 */
internal class BaiduPoiSearcher(
    private val asyncRuntime: AsyncRuntime = AsyncRuntime.DEFAULT,
) : PoiSearcher {

    private val poiRequests = NativeRequestRegistry<PoiSearch>(PoiSearch::destroy)
    private val suggestionRequests = NativeRequestRegistry<SuggestionSearch>(SuggestionSearch::destroy)

    override fun search(
        request: PoiSearchRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<PoiSearchResult>,
    ): RequestHandle {
        if (poiRequests.isDestroyed) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度 POI 搜索器已销毁"),
            )
        }
        if (request.keyword.isBlank()) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "POI 关键词不能为空"),
            )
        }
        val nativeCenter = request.center?.let {
            runCatching { it.toBaiduSdkLatLng() }.getOrElse { error ->
                return asyncRuntime.failedRequest(
                    asyncOptions,
                    callback,
                    MapError(ErrorType.INVALID_PARAM, "百度 POI 坐标转换失败：${error.message.orEmpty()}", cause = error),
                )
            }
        }
        val search = runCatching { PoiSearch.newInstance() }.getOrElse { error ->
            return asyncRuntime.failedRequest(asyncOptions, callback, error.toPoiError("百度 POI 搜索初始化失败"))
        }
        val async = poiRequests.trackedRequest(search, asyncRuntime, asyncOptions, callback)
        if (async == null) {
            search.destroy()
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度 POI 搜索器已销毁"),
            )
        }
        if (async.isDone) return async
        try {
            search.setOnGetPoiSearchResultListener(
                poiListener(
                    onResult = { result ->
                        val response = runCatching {
                            if (result.error == SearchResult.ERRORNO.NO_ERROR) {
                                MapResult.Success(
                                    PoiSearchResult(
                                        items = result.allPoi.orEmpty().mapNotNull(PoiInfo::toFusionPoi),
                                        totalCount = result.totalPoiNum,
                                        pageIndex = result.currentPageNum,
                                        pageSize = result.currentPageCapacity,
                                    ),
                                )
                            } else {
                                MapResult.Failure(result.toPoiError("百度 POI 搜索失败"))
                            }
                        }.getOrElse { error -> MapResult.Failure(error.toPoiError("百度 POI 结果解析失败")) }
                        async.complete(response)
                    },
                ),
            )

            val accepted = when (request.type) {
                PoiSearchType.KEYWORD -> search.searchInCity(
                    PoiCitySearchOption()
                        .city(request.city.orEmpty())
                        .keyword(request.keyword)
                        .tag(request.category.orEmpty())
                        .pageNum(request.pageIndex.coerceAtLeast(0))
                        .pageCapacity(request.pageSize.coerceIn(1, 50)),
                )
                PoiSearchType.NEARBY -> {
                    val center = request.center
                    if (center == null) {
                        async.failure(MapError(ErrorType.INVALID_PARAM, "周边搜索必须提供 center"))
                        false
                    } else {
                        search.searchNearby(
                            PoiNearbySearchOption()
                                .keyword(request.keyword)
                                .tag(request.category.orEmpty())
                                .location(requireNotNull(nativeCenter))
                                .radius(request.radiusMeters.coerceAtLeast(1))
                                .sortType(
                                    if (request.sort == PoiSort.DISTANCE) {
                                        PoiSortType.distance_from_near_to_far
                                    } else {
                                        PoiSortType.comprehensive
                                    },
                                )
                                .pageNum(request.pageIndex.coerceAtLeast(0))
                                .pageCapacity(request.pageSize.coerceIn(1, 50)),
                        )
                    }
                }
            }
            if (!accepted) {
                async.failure(MapError(ErrorType.INVALID_PARAM, "百度未接受 POI 请求"))
            }
        } catch (error: Throwable) {
            async.failure(error.toPoiError("百度 POI 搜索失败"))
        }
        return async
    }

    override fun searchDetail(
        id: String,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<PoiItem>,
    ): RequestHandle {
        if (poiRequests.isDestroyed) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度 POI 搜索器已销毁"),
            )
        }
        if (id.isBlank()) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "POI id 不能为空"),
            )
        }
        val search = runCatching { PoiSearch.newInstance() }.getOrElse { error ->
            return asyncRuntime.failedRequest(asyncOptions, callback, error.toPoiError("百度 POI 详情初始化失败"))
        }
        val async = poiRequests.trackedRequest(search, asyncRuntime, asyncOptions, callback)
        if (async == null) {
            search.destroy()
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度 POI 搜索器已销毁"),
            )
        }
        if (async.isDone) return async
        try {
            search.setOnGetPoiSearchResultListener(
                poiListener(
                    onDetail = { detail ->
                        val response = runCatching {
                            val item = detail.toFusionPoi()
                            if (detail.error == SearchResult.ERRORNO.NO_ERROR && item != null) {
                                MapResult.Success(item)
                            } else {
                                MapResult.Failure(
                                    if (detail.error == SearchResult.ERRORNO.NO_ERROR) {
                                        MapError(ErrorType.NO_RESULT, "百度 POI 详情缺少有效坐标")
                                    } else {
                                        detail.toPoiError("百度 POI 详情失败")
                                    },
                                )
                            }
                        }.getOrElse { error -> MapResult.Failure(error.toPoiError("百度 POI 详情解析失败")) }
                        async.complete(response)
                    },
                    onDetailList = { result ->
                        val response = runCatching {
                            val detail = result.poiDetailInfoList?.firstOrNull()
                            val item = detail?.toFusionPoi()
                            if (result.error == SearchResult.ERRORNO.NO_ERROR && item != null) {
                                MapResult.Success(item)
                            } else {
                                MapResult.Failure(
                                    if (result.error == SearchResult.ERRORNO.NO_ERROR) {
                                        MapError(ErrorType.NO_RESULT, "百度 POI 详情缺少有效坐标")
                                    } else {
                                        result.toPoiError("百度 POI 详情失败")
                                    },
                                )
                            }
                        }.getOrElse { error -> MapResult.Failure(error.toPoiError("百度 POI 详情解析失败")) }
                        async.complete(response)
                    },
                ),
            )
            if (!search.searchPoiDetail(PoiDetailSearchOption().poiUid(id))) {
                async.failure(MapError(ErrorType.INVALID_PARAM, "百度未接受 POI 详情请求"))
            }
        } catch (error: Throwable) {
            async.failure(error.toPoiError("百度 POI 详情失败"))
        }
        return async
    }

    override fun suggest(
        request: PoiSuggestionRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<List<PoiSuggestion>>,
    ): RequestHandle {
        if (suggestionRequests.isDestroyed) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度 POI 搜索器已销毁"),
            )
        }
        if (request.keyword.isBlank()) {
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "输入提示关键词不能为空"),
            )
        }
        val nativeCenter = request.center?.let {
            runCatching { it.toBaiduSdkLatLng() }.getOrElse { error ->
                return asyncRuntime.failedRequest(
                    asyncOptions,
                    callback,
                    MapError(ErrorType.INVALID_PARAM, "百度输入提示坐标转换失败：${error.message.orEmpty()}", cause = error),
                )
            }
        }
        val search = runCatching { SuggestionSearch.newInstance() }.getOrElse { error ->
            return asyncRuntime.failedRequest(asyncOptions, callback, error.toPoiError("百度输入提示初始化失败"))
        }
        val async = suggestionRequests.trackedRequest(search, asyncRuntime, asyncOptions, callback)
        if (async == null) {
            search.destroy()
            return asyncRuntime.failedRequest(
                asyncOptions,
                callback,
                MapError(ErrorType.INVALID_PARAM, "百度 POI 搜索器已销毁"),
            )
        }
        if (async.isDone) return async
        try {
            search.setOnGetSuggestionResultListener { result ->
                val response = runCatching {
                    if (result.error == SearchResult.ERRORNO.NO_ERROR) {
                        MapResult.Success(
                            result.allSuggestions.orEmpty().map { suggestion ->
                                PoiSuggestion(
                                    id = suggestion.uid,
                                    name = suggestion.key.orEmpty(),
                                    address = suggestion.address,
                                    city = suggestion.city,
                                    district = suggestion.district,
                                    location = suggestion.pt?.let {
                                        LatLng(it.latitude, it.longitude, CoordType.BD09)
                                    },
                                    category = suggestion.tag,
                                )
                            },
                        )
                    } else {
                        MapResult.Failure(result.toPoiError("百度输入提示失败"))
                    }
                }.getOrElse { error -> MapResult.Failure(error.toPoiError("百度输入提示解析失败")) }
                async.complete(response)
            }
            val option = SuggestionSearchOption()
                .keyword(request.keyword)
                .city(request.city.orEmpty())
                .citylimit(request.cityLimit)
            nativeCenter?.let(option::location)
            if (!search.requestSuggestion(option)) {
                async.failure(MapError(ErrorType.INVALID_PARAM, "百度未接受输入提示请求"))
            }
        } catch (error: Throwable) {
            async.failure(error.toPoiError("百度输入提示失败"))
        }
        return async
    }

    private fun poiListener(
        onResult: (PoiResult) -> Unit = {},
        onDetail: (PoiDetailResult) -> Unit = {},
        onDetailList: (PoiDetailSearchResult) -> Unit = {},
    ) = object : OnGetPoiSearchResultListener {
        override fun onGetPoiResult(result: PoiResult) = onResult(result)
        override fun onGetPoiDetailResult(result: PoiDetailResult) = onDetail(result)
        override fun onGetPoiDetailResult(result: PoiDetailSearchResult) = onDetailList(result)
        override fun onGetPoiIndoorResult(result: PoiIndoorResult) = Unit
    }

    override fun destroy() {
        poiRequests.destroy()
        suggestionRequests.destroy()
    }
}

private fun LatLng.toBaiduPoiPoint() = toBaiduSdkLatLng()

private fun PoiInfo.toFusionPoi(): PoiItem? {
    val point = location?.let { LatLng(it.latitude, it.longitude, CoordType.BD09) }
        ?.takeIf(LatLng::isValidPoiPoint)
        ?: return null
    return PoiItem(
        id = uid.orEmpty(), name = name.orEmpty(), location = point,
        address = address, province = province, city = city, district = area,
        category = tag, phone = phoneNum, distanceMeters = distance.takeIf { it >= 0 },
    )
}

private fun PoiDetailResult.toFusionPoi(): PoiItem? {
    val nativePoint = location ?: return null
    val point = LatLng(nativePoint.latitude, nativePoint.longitude, CoordType.BD09)
        .takeIf(LatLng::isValidPoiPoint)
        ?: return null
    return PoiItem(
        id = uid.orEmpty(), name = name.orEmpty(), location = point,
        address = address, category = tag, phone = telephone,
    )
}

private fun PoiDetailInfo.toFusionPoi(): PoiItem? {
    val nativePoint = location ?: return null
    val point = LatLng(nativePoint.latitude, nativePoint.longitude, CoordType.BD09)
        .takeIf(LatLng::isValidPoiPoint)
        ?: return null
    return PoiItem(
        id = uid.orEmpty(), name = name.orEmpty(), location = point,
        address = address, province = province, city = city, district = area,
        category = tag, phone = telephone, distanceMeters = distance.takeIf { it >= 0 },
    )
}

private fun LatLng.isValidPoiPoint(): Boolean =
    latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

private fun SearchResult.toPoiError(prefix: String): MapError {
    return error.toBaiduSearchError(prefix, status)
}

private fun Throwable.toPoiError(prefix: String): MapError = when (this) {
    is IllegalArgumentException -> MapError(ErrorType.INVALID_PARAM, "$prefix：${message.orEmpty()}", cause = this)
    else -> MapError(ErrorType.UNKNOWN, "$prefix：${message.orEmpty()}", cause = this)
}
