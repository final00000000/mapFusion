package com.mapfusion.api.capability

import android.content.Context
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.TravelMode

/**
 * 导航能力。第一版只统一「发起导航」,界面沿用厂商原生导航页,
 * 不强行封装导航 UI(百度 BaiduNaviSDK / 高德 AMapNaviSDK 差异过大)。
 *
 * 实现方式因厂商而异:可能拉起独立导航 Activity,也可能唤起对方 App。
 */
interface Navigator {

    /** 外部导航 URI 真实支持的标准化出行方式，不包含已废弃的 RIDING 别名。 */
    fun supportedModes(): Set<TravelMode> = setOf(
        TravelMode.DRIVING,
        TravelMode.WALKING,
        TravelMode.BICYCLE,
        TravelMode.TRANSIT,
    )

    fun supportsMode(mode: TravelMode): Boolean = mode.canonical() in supportedModes()

    /**
     * 发起导航。
     *
     * @param origin 起点;传 null 表示由 SDK 使用当前定位作为起点。
     * @param destination 终点。
     * @param mode 出行方式。
     * @return [MapResult.Success] 仅表示导航 Intent 已交给 Android 系统处理，
     * 不代表外部导航页已完成加载；模式不受支持或没有可用客户端、浏览器时返回
     * [MapResult.Failure]。当前外部 URI 无法准确表达电动车导航，可先用 [supportsMode] 查询。
     */
    fun startNavigation(
        context: Context,
        origin: LatLng?,
        destination: LatLng,
        mode: TravelMode = TravelMode.DRIVING,
    ): MapResult<Unit>
}
