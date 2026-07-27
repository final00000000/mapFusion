package com.mapfusion.demo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.mapfusion.amap.AmapProviderFactory
import com.mapfusion.api.MapConfig
import com.mapfusion.api.MapProvider
import com.mapfusion.api.capability.MapController
import com.mapfusion.api.capability.Provider
import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.LatLng
import com.mapfusion.api.model.LocationAccuracy
import com.mapfusion.api.model.LocationOptions
import com.mapfusion.api.model.MapResult
import com.mapfusion.api.model.MarkerOptions
import com.mapfusion.api.model.MultiPointItem
import com.mapfusion.api.model.MultiPointOverlayOptions
import com.mapfusion.api.model.RouteRequest
import com.mapfusion.api.model.TravelMode
import com.mapfusion.baidu.BaiduProviderFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * 同一套真机契约同时验证百度和高德。无 Key 的公共 CI 会跳过，发布流水线必须注入真实 Key。
 */
@RunWith(Parameterized::class)
class ProviderDeviceContractTest(
    private val providerType: Provider,
) {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var hostActivity: Activity? = null
    private var provider: MapProvider? = null
    private var controller: MapController? = null

    @Before
    fun createProvider() {
        val key = apiKey()
        assumeTrue("真机契约测试需要注入 $providerType Key", key.isUsableApiKey())
        hostActivity = instrumentation.startActivitySync(
            Intent(context, DeviceTestHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()
        provider = when (providerType) {
            Provider.BAIDU -> BaiduProviderFactory()
            Provider.AMAP -> AmapProviderFactory()
        }.create(
            context,
            MapConfig(
                provider = providerType,
                apiKey = key,
                enablePrivacyCompliance = true,
            ),
        )
    }

    @After
    fun releaseProvider() {
        onMain {
            controller?.let { map ->
                runCatching { map.onPause() }
                runCatching { map.onDestroy() }
                runCatching { map.onDestroy() }
            }
            controller = null
            runCatching { provider?.destroy() }
            runCatching { provider?.destroy() }
            provider = null
            runCatching { hostActivity?.finish() }
            hostActivity = null
        }
    }

    @Test
    fun providerCapabilitiesAndMapLifecycleFollowTheSameContract() {
        val current = requireNotNull(provider)
        assertEquals(providerType, current.provider)
        assertFalse(current.capabilities().isEmpty())
        current.capabilities().forEach { capability -> assertTrue(current.supports(capability)) }

        val map = onMain {
            requireNotNull(current.createMapController(context)).also {
                controller = it
                it.onCreate(null)
                it.onResume()
            }
        }
        assertNotNull(map.view)
    }

    @Test
    fun wgs84OverlayInputIsAcceptedAndRemovalIsIdempotent() {
        val map = createMapController()
        val marker = onMain {
            map.addMarker(
                MarkerOptions(
                    position = BEIJING_WGS84,
                    title = "契约测试",
                    rotation = 37f,
                    alpha = 0.7f,
                    flat = true,
                    tag = "provider-contract",
                ),
            )
        }

        assertTrue(marker.position.latitude in -90.0..90.0)
        assertTrue(marker.position.longitude in -180.0..180.0)
        assertEquals(providerType.nativeCoordType, marker.position.coordType)
        assertEquals(37f, marker.rotation, 0.01f)
        assertEquals(0.7f, marker.alpha, 0.01f)
        assertTrue(marker.flat)
        assertFalse(marker.isRemoved)
        onMain {
            marker.remove()
            assertTrue(marker.isRemoved)
            marker.remove()
            map.clearOverlays()
            map.clearOverlays()
        }
    }

    @Test
    fun nativeMultiPointSupportsBatchReplacementVisibilityAndIdempotentRemoval() {
        val map = createMapController()
        val initial = listOf(
            MultiPointItem("first", BEIJING_WGS84, "第一个", tag = 1),
            MultiPointItem("second", BEIJING_DESTINATION_WGS84, "第二个", tag = 2),
        )
        val overlay = onMain {
            map.addMultiPointOverlay(
                MultiPointOverlayOptions(
                    items = initial,
                    clickable = true,
                    tag = "provider-multipoint-contract",
                ),
            )
        }

        assertEquals(initial, overlay.items)
        assertEquals("provider-multipoint-contract", overlay.tag)
        assertEquals(providerType.nativeMultiPointClassName, overlay.rawOverlay()::class.java.name)
        assertEquals(initial.size, nativeMultiPointItemCount(overlay.rawOverlay()))
        assertFalse(overlay.isRemoved)

        val replacement = listOf(
            MultiPointItem("replacement", BEIJING_DESTINATION_WGS84, "替换点", tag = 3),
        )
        onMain {
            overlay.visible = false
            assertFalse(overlay.visible)
            overlay.clickable = false
            assertFalse(overlay.clickable)
            overlay.items = replacement
            assertEquals(replacement, overlay.items)
            assertEquals(replacement.size, nativeMultiPointItemCount(overlay.rawOverlay()))
            overlay.visible = true
            overlay.clickable = true
            map.clearOverlays()
            assertTrue(overlay.isRemoved)
            overlay.remove()
            map.clearOverlays()
        }
    }

    @Test
    fun singleLocationReturnsValidVendorCoordinate() {
        ensureLocationPermission()
        val client = requireNotNull(provider?.locationClient())
        val result = awaitResult<com.mapfusion.api.model.MapLocation>(LOCATION_TIMEOUT_SECONDS) { callback ->
            client.requestSingleLocation(
                LocationOptions(
                    accuracy = LocationAccuracy.HIGH,
                    timeoutMs = 25_000,
                    needAddress = true,
                    onceOnly = true,
                    useCache = false,
                    gpsFirst = true,
                ),
                callback,
            )
        }
        client.destroy()

        val location = (result as? MapResult.Success)?.data
            ?: error("$providerType 定位失败：${(result as MapResult.Failure).error}")
        assertTrue(location.position.latitude in -90.0..90.0)
        assertTrue(location.position.longitude in -180.0..180.0)
        assertEquals(providerType.nativeCoordType, location.position.coordType)
    }

    @Test
    fun walkingRouteAcceptsWgs84AndReturnsDrawableGeometry() {
        val planner = requireNotNull(provider?.routePlanner())
        val request = RouteRequest(
            mode = TravelMode.WALKING,
            origin = BEIJING_WGS84,
            destination = BEIJING_DESTINATION_WGS84,
        )
        var result: MapResult<com.mapfusion.api.model.RouteResult>? = null
        for (attempt in 0 until MAX_ROUTE_ATTEMPTS) {
            result = awaitResult(ROUTE_TIMEOUT_SECONDS) { callback -> planner.plan(request, callback) }
            val failure = result as? MapResult.Failure
            if (failure == null || !failure.error.type.isTransientRouteFailure || attempt == MAX_ROUTE_ATTEMPTS - 1) {
                break
            }
        }
        planner.destroy()

        val finalResult = requireNotNull(result)
        val route = (finalResult as? MapResult.Success)?.data
            ?: error("$providerType 步行路线失败：${(finalResult as MapResult.Failure).error}")
        assertTrue(route.paths.isNotEmpty())
        val points = route.paths.first().polyline.ifEmpty {
            route.paths.first().steps.flatMap { it.polyline }
        }
        assertTrue("路线必须包含可绘制几何", points.size >= 2)
        assertTrue(points.all { it.coordType == providerType.nativeCoordType })
    }

    private fun createMapController(): MapController = onMain {
        controller ?: requireNotNull(provider?.createMapController(context)).also {
            controller = it
            it.onCreate(null)
            it.onResume()
        }
    }

    private fun apiKey(): String = when (providerType) {
        Provider.BAIDU -> BuildConfig.BAIDU_MAP_API_KEY
        Provider.AMAP -> BuildConfig.AMAP_API_KEY
    }

    private fun nativeMultiPointItemCount(nativeOverlay: Any): Int {
        val methodName = when (providerType) {
            Provider.BAIDU -> "getMultiPointItems"
            Provider.AMAP -> "getItems"
        }
        @Suppress("UNCHECKED_CAST")
        return (nativeOverlay::class.java.getMethod(methodName).invoke(nativeOverlay) as List<*>).size
    }

    /**
     * 部分量产设备禁止 adb/UiAutomation 直接 grant；契约测试走真实系统授权界面，
     * 同时验证宿主按 Android 标准流程申请“仅使用期间”的精确位置权限。
     */
    private fun ensureLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val activity = requireNotNull(hostActivity)
        onMain {
            activity.requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
                LOCATION_PERMISSION_REQUEST_CODE,
            )
        }

        val device = UiDevice.getInstance(instrumentation)
        assertTrue(
            "系统未显示位置权限授权界面",
            device.wait(Until.hasObject(By.pkg(PERMISSION_CONTROLLER_PACKAGE)), PERMISSION_UI_TIMEOUT_MS),
        )
        if (hasFineLocationPermission()) return
        device.findObject(By.res(FINE_LOCATION_RADIO_ID))?.let { fineRadio ->
            if (!fineRadio.isChecked) fineRadio.click()
        }
        if (awaitFineLocationPermission(device, 2_000L)) return
        val allowButton = ALLOW_FOREGROUND_BUTTON_IDS
            .asSequence()
            .mapNotNull { id ->
                device.wait(Until.findObject(By.res(id)), PERMISSION_UI_TIMEOUT_MS)
            }
            .firstOrNull()
            ?: listOf("使用时允许", "While using the app", "允许")
                .asSequence()
                .mapNotNull { text -> device.wait(Until.findObject(By.text(text)), 1_000L) }
                .firstOrNull()
        if (allowButton == null && hasFineLocationPermission()) return
        assertNotNull("系统位置权限界面缺少“使用时允许”按钮", allowButton)
        allowButton!!.click()
        device.wait(
            Until.gone(By.pkg(PERMISSION_CONTROLLER_PACKAGE)),
            PERMISSION_UI_TIMEOUT_MS,
        )
        instrumentation.waitForIdleSync()

        assertEquals(
            "系统没有授予精确位置权限",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION),
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun awaitFineLocationPermission(device: UiDevice, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (hasFineLocationPermission()) return true
            device.waitForIdle()
            SystemClock.sleep(100L)
        }
        return hasFineLocationPermission()
    }

    private fun <T> awaitResult(
        timeoutSeconds: Long,
        start: (com.mapfusion.api.model.MapCallback<T>) -> Unit,
    ): MapResult<T> {
        val result = AtomicReference<MapResult<T>>()
        val latch = CountDownLatch(1)
        start { value ->
            if (result.compareAndSet(null, value)) latch.countDown()
        }
        assertTrue("$providerType 异步请求在 ${timeoutSeconds}s 内没有回调", latch.await(timeoutSeconds, TimeUnit.SECONDS))
        return requireNotNull(result.get())
    }

    private fun <T> onMain(block: () -> T): T {
        val value = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            runCatching(block)
                .onSuccess(value::set)
                .onFailure(failure::set)
        }
        failure.get()?.let { throw it }
        return value.get()
    }

    private val Provider.nativeCoordType: CoordType
        get() = when (this) {
            Provider.BAIDU -> CoordType.BD09
            Provider.AMAP -> CoordType.GCJ02
        }

    private val Provider.nativeMultiPointClassName: String
        get() = when (this) {
            Provider.BAIDU -> "com.baidu.mapapi.map.MultiPoint"
            Provider.AMAP -> "com.amap.api.maps.model.MultiPointOverlay"
        }

    private fun String.isUsableApiKey(): Boolean =
        isNotBlank() && !contains("YOUR_", ignoreCase = true) && !contains("你的")

    private val ErrorType.isTransientRouteFailure: Boolean
        get() = this == ErrorType.NETWORK

    companion object {
        private const val LOCATION_TIMEOUT_SECONDS = 35L
        private const val ROUTE_TIMEOUT_SECONDS = 40L
        private const val MAX_ROUTE_ATTEMPTS = 2
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val PERMISSION_UI_TIMEOUT_MS = 10_000L
        private const val PERMISSION_CONTROLLER_PACKAGE = "com.android.permissioncontroller"
        private const val FINE_LOCATION_RADIO_ID =
            "$PERMISSION_CONTROLLER_PACKAGE:id/permission_location_accuracy_radio_fine"
        private val ALLOW_FOREGROUND_BUTTON_IDS = listOf(
            "$PERMISSION_CONTROLLER_PACKAGE:id/permission_allow_foreground_only_button",
            "$PERMISSION_CONTROLLER_PACKAGE:id/permission_allow_button",
        )
        private val BEIJING_WGS84 = LatLng(39.9087, 116.3975, CoordType.WGS84)
        private val BEIJING_DESTINATION_WGS84 = LatLng(39.9163, 116.4172, CoordType.WGS84)

        @JvmStatic
        @Parameterized.Parameters(name = "provider={0}")
        fun providers(): List<Array<Provider>> = Provider.entries.map { arrayOf(it) }
    }
}
