package com.mapfusion.baidu

import com.baidu.mapapi.search.core.SearchResult
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapError

/**
 * 将百度搜索类 SDK 的错误码转换为统一错误模型。
 *
 * 百度多个搜索服务共用 [SearchResult.ERRORNO]，但每个适配器此前各自维护
 * 一份映射，容易遗漏服务端错误。这里集中处理所有跨服务通用的错误码；
 * 具体能力的特殊错误（例如公交路线不支持）仍由调用方通过 [typeOverride]
 * 覆盖。rawCode/rawMessage 始终保留，便于宿主定位厂商侧问题。
 */
internal fun SearchResult.ERRORNO?.toBaiduSearchError(
    prefix: String,
    status: Int,
    typeOverride: ErrorType? = null,
): MapError {
    val type = typeOverride ?: when (this) {
        SearchResult.ERRORNO.RESULT_NOT_FOUND -> ErrorType.NO_RESULT
        SearchResult.ERRORNO.KEY_ERROR,
        SearchResult.ERRORNO.PERMISSION_UNFINISHED -> ErrorType.AUTH
        SearchResult.ERRORNO.NETWORK_ERROR,
        SearchResult.ERRORNO.NETWORK_TIME_OUT,
        // 百度服务端内部错误通常是临时网络/服务不可用，允许业务重试。
        SearchResult.ERRORNO.SEARCH_SERVER_INTERNAL_ERROR -> ErrorType.NETWORK
        SearchResult.ERRORNO.PARAMER_ERROR,
        SearchResult.ERRORNO.SEARCH_OPTION_ERROR -> ErrorType.INVALID_PARAM
        else -> ErrorType.UNKNOWN
    }
    return MapError(
        type = type,
        message = "$prefix：$this",
        rawCode = status,
        rawMessage = this?.name,
    )
}
