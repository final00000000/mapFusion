package com.mapfusion.api

import com.mapfusion.api.capability.Provider

/**
 * 地图初始化配置。业务方构造此对象交给 `MapFusion.create`。
 *
 * 换厂商时,理论上只需改 [provider] 与对应 [apiKey],其余业务代码不动。
 *
 * 放在 map-api(而非 map-factory)是因为它是适配器要实现的 SPI 契约的一部分:
 * 适配器只依赖 map-api 即可,不必依赖编排层。
 *
 * @param provider   选用的地图厂商。
 * @param apiKey     该厂商开放平台申请的 Key(需在其后台绑定应用包名 + SHA1 签名)。
 * @param enablePrivacyCompliance 宿主是否已完成隐私告知并取得用户明确同意。
 *        默认为 false，SDK 不会替宿主推定用户已同意。
 * @param debug      是否输出调试日志。
 * @param extras     厂商特有的附加配置,原样透传给适配器(如高德的 apiType、百度的 coordType 等)。
 */
data class MapConfig(
    val provider: Provider,
    val apiKey: String,
    val enablePrivacyCompliance: Boolean = false,
    val debug: Boolean = false,
    val extras: Map<String, Any?> = emptyMap(),
) {
    init {
        require(apiKey.isNotBlank()) { "apiKey 不能为空" }
    }

    /**
     * 在接触任何厂商 SDK 前校验隐私同意状态。
     * 隐私告知、同意记录和撤回入口由宿主应用负责，地图 SDK 不弹自有授权界面。
     */
    fun requirePrivacyConsent() {
        if (!enablePrivacyCompliance) throw PrivacyConsentRequiredException(provider)
    }

    /** 避免日志或崩溃报告通过 data class 默认 toString 泄露开放平台 Key。 */
    override fun toString(): String =
        "MapConfig(provider=$provider, apiKey=<redacted>, " +
            "enablePrivacyCompliance=$enablePrivacyCompliance, debug=$debug, " +
            "extrasKeys=${extras.keys})"
}

/** 宿主尚未明确确认隐私同意，禁止初始化厂商 SDK。 */
class PrivacyConsentRequiredException(val provider: Provider) : IllegalStateException(
    "初始化 $provider 前必须先完成隐私告知并取得用户明确同意，然后设置 " +
        "MapConfig.enablePrivacyCompliance=true",
)
