package com.mapfusion.demo.data

import android.content.Context

/** 与旧 Demo 偏好兼容；仅使用 Application Context，两个页面共享同一约定。 */
enum class PrivacyDecision { UNDECIDED, ACCEPTED, DECLINED }

interface ConsentPersistence {
    fun read(): String?
    fun write(value: String): Boolean
}

class DemoConsentStore(private val persistence: ConsentPersistence) {
    constructor(context: Context) : this(object : ConsentPersistence {
        private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        override fun read(): String? = preferences.getString(DECISION_KEY, null)
        override fun write(value: String): Boolean = preferences.edit().putString(DECISION_KEY, value).commit()
    })

    fun read(): PrivacyDecision = persistence.read()?.let {
        runCatching { PrivacyDecision.valueOf(it) }.getOrDefault(PrivacyDecision.UNDECIDED)
    } ?: PrivacyDecision.UNDECIDED

    /** 必须落盘成功后才允许初始化或确认撤回，失败不伪造已保存状态。 */
    fun save(decision: PrivacyDecision): Boolean = persistence.write(decision.name)
    fun hasConsent(): Boolean = read() == PrivacyDecision.ACCEPTED

    private companion object {
        const val PREFERENCES = "mapfusion.demo.privacy"
        const val DECISION_KEY = "privacy_decision"
    }
}
