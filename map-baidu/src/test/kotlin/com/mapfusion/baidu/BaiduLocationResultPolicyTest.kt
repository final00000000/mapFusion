package com.mapfusion.baidu

import com.baidu.location.BDLocation
import com.mapfusion.api.model.ErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaiduLocationResultPolicyTest {

    @Test
    fun criteriaAndNetworkFailuresRemainRetryableForSingleLocation() {
        assertTrue(BDLocation.TypeCriteriaException.isRetryableBaiduLocationFailure())
        assertTrue(BDLocation.TypeNetWorkException.isRetryableBaiduLocationFailure())
        assertTrue(BDLocation.TypeOffLineLocationNetworkFail.isRetryableBaiduLocationFailure())
    }

    @Test
    fun permissionAndAuthenticationFailuresAreTerminal() {
        assertFalse(BDLocation.TYPE_NO_PERMISSION_LOCATION_FAIL.isRetryableBaiduLocationFailure())
        assertFalse(BDLocation.TypeServerCheckKeyError.isRetryableBaiduLocationFailure())
        assertEquals(
            ErrorType.PERMISSION,
            BDLocation.TYPE_NO_PERMISSION_LOCATION_FAIL.toBaiduLocationErrorType(),
        )
        assertEquals(ErrorType.AUTH, BDLocation.TypeServerCheckKeyError.toBaiduLocationErrorType())
    }

    @Test
    fun rawFailureCodesMapToActionableUnifiedErrors() {
        assertEquals(ErrorType.NO_RESULT, BDLocation.TypeCriteriaException.toBaiduLocationErrorType())
        assertEquals(ErrorType.NETWORK, BDLocation.TypeNetWorkException.toBaiduLocationErrorType())
        assertEquals(
            ErrorType.NETWORK,
            BDLocation.TypeOffLineLocationNetworkFail.toBaiduLocationErrorType(),
        )
    }
}
