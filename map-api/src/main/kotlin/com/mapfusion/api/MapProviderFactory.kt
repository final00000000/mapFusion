package com.mapfusion.api

import android.content.Context
import com.mapfusion.api.capability.Provider

/**
 * 适配器工厂(SPI 契约)。每个厂商模块(map-baidu / map-amap)各自实现一个,
 * 并在初始化时注册进 map-factory 的 ProviderRegistry。工厂负责用给定配置创建 [MapProvider] 实例。
 *
 * 契约放在 map-api,使适配器只需依赖 map-api 即可实现;map-factory 只负责注册与编排,
 * 不被适配器反向依赖。谁被引入、谁被注册,就支持谁——未引入的厂商不会造成缺类/崩溃。
 */
interface MapProviderFactory {

    /** 本工厂负责的厂商 */
    val provider: Provider

    /**
     * 把宿主最新的隐私同意状态同步给厂商 SDK。
     *
     * 实现只能更新厂商合规标记，不能创建地图、定位或搜索实例。宿主撤回同意时应先
     * 销毁活动会话，再传入 false。没有独立隐私 API 的 Provider 可以保留默认空实现。
     */
    fun updatePrivacyConsent(context: Context, consentGranted: Boolean) = Unit

    /**
     * 用配置创建厂商实例。实现方在此完成 SDK 初始化(如设置 apiKey、同意隐私合规等)。
     *
     * @param context 建议传 applicationContext,避免持有 Activity 泄漏。
     * @param config  含 apiKey 及其他厂商参数。
     */
    fun create(context: Context, config: MapConfig): MapProvider
}
