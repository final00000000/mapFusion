package com.mapfusion.api.capability

import com.mapfusion.api.model.DistrictSearchRequest
import com.mapfusion.api.model.DistrictSearchResult
import com.mapfusion.api.model.AsyncCallOptions
import com.mapfusion.api.model.MapCallback
import com.mapfusion.api.model.RequestHandle

/** 行政区检索与边界查询。 */
interface DistrictSearcher {
    fun search(
        request: DistrictSearchRequest,
        callback: MapCallback<DistrictSearchResult>,
    ): RequestHandle = search(request, AsyncCallOptions(), callback)

    fun search(
        request: DistrictSearchRequest,
        asyncOptions: AsyncCallOptions,
        callback: MapCallback<DistrictSearchResult>,
    ): RequestHandle
    fun destroy()
}
