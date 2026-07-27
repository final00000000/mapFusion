package com.mapfusion.baidu

import android.app.Application
import com.mapfusion.api.MapConfig
import com.mapfusion.api.PrivacyConsentRequiredException
import com.mapfusion.api.capability.Provider
import org.junit.Assert.assertThrows
import org.junit.Test

class BaiduPrivacyConsentTest {

    @Test
    fun factory_rejects_without_consent_before_touching_native_sdk() {
        assertThrows(PrivacyConsentRequiredException::class.java) {
            BaiduProviderFactory().create(
                Application(),
                MapConfig(provider = Provider.BAIDU, apiKey = "key"),
            )
        }
    }
}

