package com.mapfusion.api

import com.mapfusion.api.capability.Provider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MapConfigTest {

    @Test
    fun privacy_consent_is_never_assumed_by_default() {
        val config = MapConfig(provider = Provider.AMAP, apiKey = "key")

        assertFalse(config.enablePrivacyCompliance)
        val error = assertThrows(PrivacyConsentRequiredException::class.java) {
            config.requirePrivacyConsent()
        }
        assertTrue(error.message.orEmpty().contains("enablePrivacyCompliance=true"))
    }

    @Test
    fun explicit_privacy_consent_passes_initialization_validation() {
        val config = MapConfig(
            provider = Provider.BAIDU,
            apiKey = "key",
            enablePrivacyCompliance = true,
        )

        config.requirePrivacyConsent()
    }

    @Test
    fun to_string_redacts_api_key() {
        val config = MapConfig(
            provider = Provider.AMAP,
            apiKey = "sensitive-map-key",
            debug = true,
            extras = mapOf("vendorSecret" to "another-sensitive-value"),
        )

        val text = config.toString()

        assertFalse(text.contains("sensitive-map-key"))
        assertFalse(text.contains("another-sensitive-value"))
        assertTrue(text.contains("apiKey=<redacted>"))
        assertTrue(text.contains("provider=AMAP"))
        assertTrue(text.contains("debug=true"))
        assertTrue(text.contains("extrasKeys=[vendorSecret]"))
    }

    @Test
    fun blank_api_key_is_rejected_before_provider_initialization() {
        assertThrows(IllegalArgumentException::class.java) {
            MapConfig(provider = Provider.AMAP, apiKey = "  ")
        }
    }
}
