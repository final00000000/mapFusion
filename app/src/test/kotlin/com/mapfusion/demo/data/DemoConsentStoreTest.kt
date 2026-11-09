package com.mapfusion.demo.data

import org.junit.Assert.*
import org.junit.Test

class DemoConsentStoreTest {
    private class Memory(var value: String? = null, var writable: Boolean = true) : ConsentPersistence {
        override fun read() = value
        override fun write(value: String): Boolean {
            if (!writable) return false
            this.value = value
            return true
        }
    }

    @Test fun missing_consent_never_enables_sdk() {
        val store = DemoConsentStore(Memory())
        assertEquals(PrivacyDecision.UNDECIDED, store.read())
        assertFalse(store.hasConsent())
    }
    @Test fun unknown_stored_value_is_not_accepted() {
        assertFalse(DemoConsentStore(Memory("future-value")).hasConsent())
    }
    @Test fun old_preference_value_remains_compatible() {
        assertTrue(DemoConsentStore(Memory("ACCEPTED")).hasConsent())
    }
    @Test fun failed_commit_preserves_previous_consent() {
        val memory = Memory("DECLINED", writable = false)
        val store = DemoConsentStore(memory)
        assertFalse(store.save(PrivacyDecision.ACCEPTED))
        assertFalse(store.hasConsent())
    }
    @Test fun both_pages_observe_successful_withdrawal() {
        val memory = Memory("ACCEPTED")
        val home = DemoConsentStore(memory)
        val navigation = DemoConsentStore(memory)
        assertTrue(navigation.hasConsent())
        assertTrue(home.save(PrivacyDecision.DECLINED))
        assertFalse(navigation.hasConsent())
    }
}
