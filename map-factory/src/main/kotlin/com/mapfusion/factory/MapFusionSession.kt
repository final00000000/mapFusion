package com.mapfusion.factory

import android.os.Bundle
import android.view.View
import com.mapfusion.api.MapProvider
import com.mapfusion.api.capability.DistrictSearcher
import com.mapfusion.api.capability.EmbeddedNavigator
import com.mapfusion.api.capability.Geocoder
import com.mapfusion.api.capability.LocationClient
import com.mapfusion.api.capability.LocationDisplay
import com.mapfusion.api.capability.MapController
import com.mapfusion.api.capability.Navigator
import com.mapfusion.api.capability.PoiSearcher
import com.mapfusion.api.capability.RoutePlanner
import com.mapfusion.api.capability.TrackRecorder
import com.mapfusion.api.capability.WeatherService
import com.mapfusion.api.capability.Capability
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.capability.TrackListener
import com.mapfusion.api.capability.TrackState
import com.mapfusion.api.model.TrackOptions
import com.mapfusion.api.model.TrackSnapshot
import kotlin.LazyThreadSafetyMode
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次地图接入会话。会话拥有 MapController,按需创建业务能力并统一释放资源。
 * 不同厂商的业务代码只依赖本类和 map-api。
 */
class MapFusionSession internal constructor(
    provider: MapProvider,
    mapController: MapController,
) : AutoCloseable {

    private val ownedProvider = provider
    private val ownedMapController = mapController

    /** 会话销毁后不可再绕过会话直接调用 Provider。 */
    val provider: MapProvider
        get() {
            checkActive()
            return ownedProvider
        }

    /** 会话销毁后不可再绕过会话直接调用地图控制器。 */
    val mapController: MapController
        get() {
            checkActive()
            return ownedMapController
        }

    val providerType: Provider get() = provider.provider

    /**
     * 会话销毁后不再暴露底层 View；这样可以尽早发现 Activity 已结束后仍误用地图的调用。
     */
    val view: View
        get() {
            checkActive()
            return ownedMapController.view
        }

    val capabilities: Set<Capability>
        get() {
            checkActive()
            return ownedProvider.capabilities()
        }

    private val locationClientLazy = lazy(LazyThreadSafetyMode.NONE) { ownedProvider.locationClient() }
    private val geocoderLazy = lazy(LazyThreadSafetyMode.NONE) { ownedProvider.geocoder() }
    private val poiSearcherLazy = lazy(LazyThreadSafetyMode.NONE) { ownedProvider.poiSearcher() }
    private val routePlannerLazy = lazy(LazyThreadSafetyMode.NONE) { ownedProvider.routePlanner() }
    private val districtSearcherLazy = lazy(LazyThreadSafetyMode.NONE) { ownedProvider.districtSearcher() }
    private val weatherServiceLazy = lazy(LazyThreadSafetyMode.NONE) { ownedProvider.weatherService() }
    private val navigatorLazy = lazy(LazyThreadSafetyMode.NONE) { ownedProvider.navigator() }
    private val locationDisplayLazy = lazy(LazyThreadSafetyMode.NONE) {
        MapFusion.createLocationDisplay(ownedProvider, ownedMapController)
    }
    // 组合能力必须拥有独立实例，避免业务定位或路线请求抢占导航正在使用的原生客户端。
    private val embeddedLocationClientLazy = lazy(LazyThreadSafetyMode.NONE) {
        ownedProvider.locationClient()
    }
    private val embeddedRoutePlannerLazy = lazy(LazyThreadSafetyMode.NONE) {
        ownedProvider.routePlanner()
    }
    private val embeddedNavigatorLazy = lazy(LazyThreadSafetyMode.NONE) {
        val location = embeddedLocationClientLazy.value ?: return@lazy null
        val route = embeddedRoutePlannerLazy.value ?: return@lazy null
        DefaultEmbeddedNavigator(ownedMapController, location, route)
    }

    val locationClient: LocationClient?
        get() {
            checkActive()
            return locationClientLazy.value
        }

    val geocoder: Geocoder?
        get() {
            checkActive()
            return geocoderLazy.value
        }

    val poiSearcher: PoiSearcher?
        get() {
            checkActive()
            return poiSearcherLazy.value
        }

    val routePlanner: RoutePlanner?
        get() {
            checkActive()
            return routePlannerLazy.value
        }

    val districtSearcher: DistrictSearcher?
        get() {
            checkActive()
            return districtSearcherLazy.value
        }

    val weatherService: WeatherService?
        get() {
            checkActive()
            return weatherServiceLazy.value
        }

    val navigator: Navigator?
        get() {
            checkActive()
            return navigatorLazy.value
        }

    /**
     * 可直接使用的当前位置图标、精度圈与相机跟随组件。它使用独立定位实例，并由 Session
     * 转发前后台生命周期和统一销毁。
     */
    val locationDisplay: LocationDisplay?
        get() {
            checkActive()
            return locationDisplayLazy.value
        }

    /**
     * 当前 MapView 内运行的厂商无关导航会话。Provider 同时支持定位和路线规划时可用。
     * 它不会启动百度/高德 App；会话销毁时由本类统一停止并释放覆盖物。
     */
    val embeddedNavigator: EmbeddedNavigator?
        get() {
            checkActive()
            return embeddedNavigatorLazy.value
        }

    private val trackRecorders = mutableSetOf<SessionTrackRecorder>()
    private var destroyed = false

    fun supports(capability: Capability): Boolean {
        checkActive()
        return ownedProvider.supports(capability)
    }

    /** 创建并登记一个轨迹记录器,会话销毁时自动释放。 */
    fun createTrackRecorder(): TrackRecorder? {
        checkActive()
        val delegate = MapFusion.createTrackRecorder(ownedProvider, ownedMapController) ?: return null
        return SessionTrackRecorder(delegate).also { recorder ->
            synchronized(trackRecorders) { trackRecorders += recorder }
        }
    }

    fun onResume() {
        checkActive()
        ownedMapController.onResume()
        if (locationDisplayLazy.isInitialized()) locationDisplayLazy.value?.onResume()
        if (embeddedNavigatorLazy.isInitialized()) embeddedNavigatorLazy.value?.onResume()
    }

    fun onPause() {
        if (!destroyed) {
            if (locationDisplayLazy.isInitialized()) locationDisplayLazy.value?.onPause()
            if (embeddedNavigatorLazy.isInitialized()) embeddedNavigatorLazy.value?.onPause()
            ownedMapController.onPause()
        }
    }

    fun onLowMemory() {
        if (!destroyed) ownedMapController.onLowMemory()
    }

    fun onSaveInstanceState(outState: Bundle) {
        if (!destroyed) ownedMapController.onSaveInstanceState(outState)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        var firstFailure: Throwable? = null
        fun attempt(block: () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                if (firstFailure == null) {
                    firstFailure = error
                } else if (error !== firstFailure) {
                    firstFailure?.addSuppressed(error)
                }
            }
        }

        // 先摘出记录器，确保即使某个记录器释放失败，其余资源仍会继续清理。
        val recorders = synchronized(trackRecorders) {
            val owned = trackRecorders.toList()
            trackRecorders.clear()
            owned
        }
        recorders.forEach { recorder -> attempt(recorder::destroy) }

        if (locationDisplayLazy.isInitialized()) {
            attempt { locationDisplayLazy.value?.destroy() }
        }

        if (embeddedNavigatorLazy.isInitialized()) {
            attempt { embeddedNavigatorLazy.value?.destroy() }
        }
        if (embeddedLocationClientLazy.isInitialized()) {
            attempt { embeddedLocationClientLazy.value?.destroy() }
        }
        if (embeddedRoutePlannerLazy.isInitialized()) {
            attempt { embeddedRoutePlannerLazy.value?.destroy() }
        }

        // 这里必须直接访问 lazy.value，而不能调用公开 getter；destroyed 已置为 true。
        if (locationClientLazy.isInitialized()) {
            attempt { locationClientLazy.value?.destroy() }
        }
        if (geocoderLazy.isInitialized()) {
            attempt { geocoderLazy.value?.destroy() }
        }
        if (poiSearcherLazy.isInitialized()) {
            attempt { poiSearcherLazy.value?.destroy() }
        }
        if (routePlannerLazy.isInitialized()) {
            attempt { routePlannerLazy.value?.destroy() }
        }
        if (districtSearcherLazy.isInitialized()) {
            attempt { districtSearcherLazy.value?.destroy() }
        }
        if (weatherServiceLazy.isInitialized()) {
            attempt { weatherServiceLazy.value?.destroy() }
        }
        attempt(ownedMapController::onDestroy)
        attempt(ownedProvider::destroy)

        firstFailure?.let { throw it }
    }

    override fun close() = destroy()

    private fun checkActive() {
        check(!destroyed) { "MapFusionSession 已销毁" }
    }

    /** 显式销毁后立即从会话所有权集合注销，避免长会话累计保留已释放记录器。 */
    private inner class SessionTrackRecorder(
        private val delegate: TrackRecorder,
    ) : TrackRecorder {
        private val released = AtomicBoolean(false)

        override val state: TrackState
            get() = delegate.state

        override fun start(options: TrackOptions, listener: TrackListener) =
            delegate.start(options, listener)

        override fun pause() = delegate.pause()

        override fun resume() = delegate.resume()

        override fun stop(): TrackSnapshot = delegate.stop()

        override fun snapshot(): TrackSnapshot = delegate.snapshot()

        override fun clear() = delegate.clear()

        override fun destroy() {
            if (!released.compareAndSet(false, true)) return
            try {
                delegate.destroy()
            } finally {
                synchronized(trackRecorders) { trackRecorders.remove(this) }
            }
        }
    }

}
