package com.mapfusion.demo.data

import android.content.Context

/** 用户隐私决定：未选择、明确同意、明确拒绝；不等同于系统位置授权。 */
enum class PrivacyDecision { UNDECIDED, ACCEPTED, DECLINED }

/** 可替换的隐私持久化边界，JVM 测试使用内存实现，不初始化 Android 或厂商 SDK。 */
interface ConsentPersistence {
    /** 读取已有枚举名称；未保存决定时返回 null。 */
    fun read(): String?

    /** 同步写入决定名称；只有确实保存成功才返回 true。 */
    fun write(value: String): Boolean
}

/** 两页共享的隐私存储，兼容旧偏好约定，保存失败不伪造已同意或已撤回。 */
class DemoConsentStore(private val persistence: ConsentPersistence) {
    /** Android 宿主入口；持久化后端只通过 Application Context 获取偏好，不保存 Activity。 */
    constructor(context: Context) : this(PreferencesConsentPersistence(context))

    /** 读取当前磁盘决定，损坏或未知枚举值回退为未选择，不能默认视作同意。 */
    fun read(): PrivacyDecision = persistence.read()?.let {
        runCatching { PrivacyDecision.valueOf(it) }.getOrDefault(PrivacyDecision.UNDECIDED)
    } ?: PrivacyDecision.UNDECIDED

    /** 必须落盘成功后才允许初始化或确认撤回，失败不伪造已保存状态。 */
    fun save(decision: PrivacyDecision): Boolean = persistence.write(decision.name)

    /** 每次重新读取持久决定，支持另一个页面撤回后的实时复核。 */
    fun hasConsent(): Boolean = read() == PrivacyDecision.ACCEPTED
}

/** Android 偏好后端，仅保留应用级 SharedPreferences，不引用传入的 Activity。 */
private class PreferencesConsentPersistence(context: Context) : ConsentPersistence {
    private val preferences = context.applicationContext.getSharedPreferences("mapfusion.demo.privacy", Context.MODE_PRIVATE)

    /** 读取旧 Demo 使用的稳定键，保持历史用户决定兼容。 */
    override fun read(): String? = preferences.getString("privacy_decision", null)

    /** 使用 commit 同步落盘，把实际保存结果返回给隐私流程。 */
    override fun write(value: String): Boolean = preferences.edit().putString("privacy_decision", value).commit()
}
