package com.mapfusion.baidu

import com.baidu.mapapi.search.core.SearchResult
import com.mapfusion.api.model.ErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaiduSearchErrorsTest {

    @Test
    fun serverInternalErrorMapsToNetworkAndKeepsRawDiagnostics() {
        val error = SearchResult.ERRORNO.SEARCH_SERVER_INTERNAL_ERROR.toBaiduSearchError(
            prefix = "百度搜索失败",
            status = 500,
        )

        assertEquals(ErrorType.NETWORK, error.type)
        assertEquals(500, error.rawCode)
        assertEquals("SEARCH_SERVER_INTERNAL_ERROR", error.rawMessage)
        assertTrue(error.message.contains("SEARCH_SERVER_INTERNAL_ERROR"))
    }

    @Test
    fun commonSearchErrorsUseUnifiedMapping() {
        val expected = mapOf(
            SearchResult.ERRORNO.RESULT_NOT_FOUND to ErrorType.NO_RESULT,
            SearchResult.ERRORNO.KEY_ERROR to ErrorType.AUTH,
            SearchResult.ERRORNO.PERMISSION_UNFINISHED to ErrorType.AUTH,
            SearchResult.ERRORNO.NETWORK_ERROR to ErrorType.NETWORK,
            SearchResult.ERRORNO.NETWORK_TIME_OUT to ErrorType.NETWORK,
            SearchResult.ERRORNO.PARAMER_ERROR to ErrorType.INVALID_PARAM,
            SearchResult.ERRORNO.SEARCH_OPTION_ERROR to ErrorType.INVALID_PARAM,
        )

        expected.forEach { (raw, unified) ->
            assertEquals(unified, raw.toBaiduSearchError("百度搜索失败", raw.ordinal).type)
        }
    }

    @Test
    fun capabilitySpecificMappingCanOverrideCommonFallback() {
        val error = SearchResult.ERRORNO.NOT_SUPPORT_BUS.toBaiduSearchError(
            prefix = "百度路线规划失败",
            status = 7,
            typeOverride = ErrorType.UNSUPPORTED,
        )

        assertEquals(ErrorType.UNSUPPORTED, error.type)
        assertEquals(7, error.rawCode)
        assertEquals("NOT_SUPPORT_BUS", error.rawMessage)
    }
}
