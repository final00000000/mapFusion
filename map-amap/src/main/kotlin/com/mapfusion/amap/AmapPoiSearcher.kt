package com.mapfusion.amap

import android.content.Context
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.PoiItem as NativePoiItem
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
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

/** 高德 POI 检索、详情与输入提示的真实适配。 */
internal class AmapPoiSearcher(
    private val context: Context,
) : PoiSearcher {

    private val runtime = AsyncRuntime.DEFAULT
    private val poiRequests = NativeRequestRegistry<PoiSearch> { it.setOnPoiSearchListener(null) }
    private val suggestionRequests = NativeRequestRegistry<Inputtips> { it.setInputtipsListener(null) }

    override fun search(
        request: PoiSearchRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<PoiSearchResult>,
    ): RequestHandle {
        if (poiRequests.isDestroyed) return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "高德 POI 搜索器已销毁"))
        if (request.keyword.isBlank()) return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "POI 关键词不能为空"))
        if (request.type == PoiSearchType.NEARBY && request.center == null) {
            return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "周边搜索必须提供 center"))
        }
        val query = runCatching {
            PoiSearch.Query(request.keyword, request.category.orEmpty(), request.city.orEmpty()).apply {
                pageNum = request.pageIndex.coerceAtLeast(0) + 1
                pageSize = request.pageSize.coerceIn(1, 50)
                setDistanceSort(request.sort == PoiSort.DISTANCE)
                extensions = PoiSearch.EXTENSIONS_ALL
            }
        }.getOrElse {
            return failed(asyncOptions, callback, it.toPoiError("高德 POI 搜索参数无效"))
        }
        val search = runCatching { PoiSearch(context, query) }.getOrElse {
            return failed(asyncOptions, callback, it.toPoiError("高德 POI 搜索初始化失败"))
        }
        val async = runtime.createRequest(
            callback = callback,
            options = asyncOptions,
            terminalAction = Runnable { poiRequests.release(search) },
        )
        if (!poiRequests.register(search, async)) {
            runCatching { search.setOnPoiSearchListener(null) }
            async.dispose()
            return async
        }
        runCatching {
            poiRequests.withRegistered(search) {
                if (request.type == PoiSearchType.NEARBY) {
                    search.bound = PoiSearch.SearchBound(
                        request.center!!.toAmapPoiPoint(),
                        request.radiusMeters.coerceAtLeast(1),
                        request.sort == PoiSort.DISTANCE,
                    )
                }
                search.setOnPoiSearchListener(
                    object : PoiSearch.OnPoiSearchListener {
                        override fun onPoiSearched(result: PoiResult?, code: Int) {
                            val response = if (code == AMapException.CODE_AMAP_SUCCESS && result != null) {
                                MapResult.Success(
                                    PoiSearchResult(
                                        items = result.pois.orEmpty().mapNotNull(NativePoiItem::toFusionPoi),
                                        totalCount = result.pageCount * query.pageSize,
                                        pageIndex = query.pageNum - 1,
                                        pageSize = query.pageSize,
                                    ),
                                )
                            } else {
                                MapResult.Failure(code.toPoiError("高德 POI 搜索失败"))
                            }
                            poiRequests.complete(search, response)
                        }

                        override fun onPoiItemSearched(item: NativePoiItem?, code: Int) = Unit
                    },
                )
                search.searchPOIAsyn()
            }
        }.onFailure { async.failure(it.toPoiError("高德 POI 搜索初始化失败")) }
        return async
    }

    override fun searchDetail(
        id: String,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<PoiItem>,
    ): RequestHandle {
        if (poiRequests.isDestroyed) return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "高德 POI 搜索器已销毁"))
        if (id.isBlank()) return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "POI id 不能为空"))
        val search = runCatching { PoiSearch(context, PoiSearch.Query("", "")) }.getOrElse {
            return failed(asyncOptions, callback, it.toPoiError("高德 POI 详情初始化失败"))
        }
        val async = runtime.createRequest(
            callback = callback,
            options = asyncOptions,
            terminalAction = Runnable { poiRequests.release(search) },
        )
        if (!poiRequests.register(search, async)) {
            runCatching { search.setOnPoiSearchListener(null) }
            async.dispose()
            return async
        }
        runCatching {
            poiRequests.withRegistered(search) {
                search.setOnPoiSearchListener(
                    object : PoiSearch.OnPoiSearchListener {
                        override fun onPoiSearched(result: PoiResult?, code: Int) = Unit

                        override fun onPoiItemSearched(item: NativePoiItem?, code: Int) {
                            val fusionItem = item?.toFusionPoi()
                            val response = if (code == AMapException.CODE_AMAP_SUCCESS && fusionItem != null) {
                                MapResult.Success(fusionItem)
                            } else {
                                MapResult.Failure(
                                    if (code == AMapException.CODE_AMAP_SUCCESS) {
                                        MapError(ErrorType.NO_RESULT, "高德 POI 详情缺少有效坐标")
                                    } else {
                                        code.toPoiError("高德 POI 详情失败")
                                    },
                                )
                            }
                            poiRequests.complete(search, response)
                        }
                    },
                )
                search.searchPOIIdAsyn(id)
            }
        }.onFailure { async.failure(it.toPoiError("高德 POI 详情初始化失败")) }
        return async
    }

    override fun suggest(
        request: PoiSuggestionRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<List<PoiSuggestion>>,
    ): RequestHandle {
        if (suggestionRequests.isDestroyed) return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "高德 POI 搜索器已销毁"))
        if (request.keyword.isBlank()) return failed(asyncOptions, callback, MapError(ErrorType.INVALID_PARAM, "输入提示关键词不能为空"))
        val query = runCatching {
            InputtipsQuery(request.keyword, request.city.orEmpty()).apply {
                setCityLimit(request.cityLimit)
                type = request.category.orEmpty()
                request.center?.let { location = it.toAmapPoiPoint() }
            }
        }.getOrElse {
            return failed(asyncOptions, callback, it.toPoiError("高德输入提示参数无效"))
        }
        val inputtips = runCatching { Inputtips(context, query) }.getOrElse {
            return failed(asyncOptions, callback, it.toPoiError("高德输入提示初始化失败"))
        }
        val async = runtime.createRequest(
            callback = callback,
            options = asyncOptions,
            terminalAction = Runnable { suggestionRequests.release(inputtips) },
        )
        if (!suggestionRequests.register(inputtips, async)) {
            runCatching { inputtips.setInputtipsListener(null) }
            async.dispose()
            return async
        }
        runCatching {
            suggestionRequests.withRegistered(inputtips) {
                inputtips.setInputtipsListener { tips, code ->
                    val response = if (code == AMapException.CODE_AMAP_SUCCESS) {
                        MapResult.Success(
                            tips.orEmpty().map { tip ->
                                PoiSuggestion(
                                    id = tip.poiID,
                                    name = tip.name.orEmpty(),
                                    address = tip.address,
                                    district = tip.district,
                                    location = tip.point?.let { LatLng(it.latitude, it.longitude, CoordType.GCJ02) },
                                    category = tip.typeCode,
                                )
                            },
                        )
                    } else {
                        MapResult.Failure(code.toPoiError("高德输入提示失败"))
                    }
                    suggestionRequests.complete(inputtips, response)
                }
                inputtips.requestInputtipsAsyn()
            }
        }.onFailure { async.failure(it.toPoiError("高德输入提示初始化失败")) }
        return async
    }

    override fun destroy() {
        poiRequests.destroy()
        suggestionRequests.destroy()
    }

    private fun <T> failed(
        options: AsyncCallOptions,
        callback: MapCallback<T>,
        error: MapError,
    ): RequestHandle = runtime.createRequest(callback, options).also { it.failure(error) }
}

private fun LatLng.toAmapPoiPoint() = toAmapServicePoint()

private fun NativePoiItem.toFusionPoi(): PoiItem? {
    val point = latLonPoint?.let { LatLng(it.latitude, it.longitude, CoordType.GCJ02) }
        ?.takeIf(LatLng::isValidPoiPoint)
        ?: return null
    return PoiItem(
        id = poiId.orEmpty(), name = title.orEmpty(), location = point,
        address = snippet, province = provinceName, city = cityName, district = adName,
        category = typeDes, phone = tel, distanceMeters = distance.takeIf { it >= 0 },
    )
}

private fun LatLng.isValidPoiPoint(): Boolean =
    latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

private fun Int.toPoiError(prefix: String): MapError {
    val type = when (this) {
        AMapException.CODE_AMAP_SUCCESS -> ErrorType.NO_RESULT
        AMapException.CODE_AMAP_INVALID_USER_KEY,
        AMapException.CODE_AMAP_INVALID_USER_SCODE,
        AMapException.CODE_AMAP_USERKEY_PLAT_NOMATCH -> ErrorType.AUTH
        AMapException.CODE_AMAP_ENGINE_CONNECT_TIMEOUT,
        AMapException.CODE_AMAP_ENGINE_RETURN_TIMEOUT,
        AMapException.CODE_AMAP_CLIENT_NETWORK_EXCEPTION -> ErrorType.NETWORK
        AMapException.CODE_AMAP_SERVICE_INVALID_PARAMS,
        AMapException.CODE_AMAP_CLIENT_INVALID_PARAMETER -> ErrorType.INVALID_PARAM
        else -> ErrorType.UNKNOWN
    }
    return MapError(type, "$prefix（$this）", rawCode = this)
}

private fun Throwable.toPoiError(prefix: String): MapError = when (this) {
    is AMapException -> errorCode.toPoiError("$prefix：$errorMessage")
    is IllegalArgumentException -> MapError(ErrorType.INVALID_PARAM, "$prefix：${message.orEmpty()}", cause = this)
    else -> MapError(ErrorType.UNKNOWN, "$prefix：${message.orEmpty()}", cause = this)
}
