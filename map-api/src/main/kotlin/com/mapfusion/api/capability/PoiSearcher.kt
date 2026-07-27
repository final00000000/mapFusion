package com.mapfusion.api.capability

import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.PoiSearchRequest
import com.mapfusion.api.model.PoiSearchResult
import com.mapfusion.api.model.PoiItem
import com.mapfusion.api.model.PoiSuggestion
import com.mapfusion.api.model.PoiSuggestionRequest
import com.mapfusion.api.model.RequestHandle

/**
 * POI 检索能力:关键字检索与周边检索。
 * 归一百度 PoiSearch / 高德 PoiSearch。
 */
interface PoiSearcher {

    fun search(
        request: PoiSearchRequest,
        callback: MapCallback<PoiSearchResult>,
    ): RequestHandle = search(request, AsyncCallOptions(), callback)

    fun search(
        request: PoiSearchRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<PoiSearchResult>,
    ): RequestHandle

    /** 按厂商 POI id 查询详情。 */
    fun searchDetail(id: String, callback: MapCallback<PoiItem>): RequestHandle =
        searchDetail(id, AsyncCallOptions(), callback)

    fun searchDetail(
        id: String,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<PoiItem>,
    ): RequestHandle

    /** 输入提示/自动补全。 */
    fun suggest(
        request: PoiSuggestionRequest,
        callback: MapCallback<List<PoiSuggestion>>,
    ): RequestHandle = suggest(request, AsyncCallOptions(), callback)

    fun suggest(
        request: PoiSuggestionRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<List<PoiSuggestion>>,
    ): RequestHandle

    fun destroy()
}
