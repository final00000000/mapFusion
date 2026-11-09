package com.mapfusion.demo

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.mapfusion.demo.data.DemoConsentStore
import com.mapfusion.demo.data.PrivacyDecision
import com.mapfusion.demo.main.MainActionCatalog
import com.mapfusion.demo.presentation.main.DemoStatus
import com.mapfusion.demo.presentation.main.DemoStatusKind
import com.mapfusion.demo.presentation.main.DemoToolCategory
import com.mapfusion.demo.presentation.main.MainMapPhase
import com.mapfusion.demo.presentation.main.MainViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 只验证拆分后真实首页连接；禁止厂商初始化，结束恢复原隐私决定。 */
class DemoMainInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var consent: DemoConsentStore
    private lateinit var previous: PrivacyDecision

    /** 使用已有共享隐私约定，不依赖设备 Key 或实际位置。 */
    @Before fun disableVendorInitialization() {
        consent = DemoConsentStore(instrumentation.targetContext)
        previous = consent.read()
        assertTrue(consent.save(PrivacyDecision.DECLINED))
    }

    /** ActivityScenario 已先关闭窗口，再恢复测试开始前的隐私决定。 */
    @After fun restoreHostConsent() { assertTrue(consent.save(previous)) }

    /** 五类按钮点击归约到 VM，收集后网格入口与纯目录一致。 */
    @Test fun all_categories_route_clicks_and_render_the_complete_catalog() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            DemoToolCategory.entries.forEach { category ->
                scenario.onActivity { activity ->
                    button(activity, category.title).performClick()
                    assertEquals(category, ViewModelProvider(activity)[MainViewModel::class.java].state.value.toolCategory)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val state = ViewModelProvider(activity)[MainViewModel::class.java].state.value
                    val official = DemoOfficialNaviSdk.capabilities(state.preferredProvider) != null
                    val grid = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<GridLayout>().single()
                    val actual = descendants(grid).filterIsInstance<Button>().map { it.text.toString() }
                    assertEquals(MainActionCatalog.actions(category, official).map { it.title }, actual)
                    assertEquals(MainMapPhase.DISABLED, state.mapPhase)
                }
            }
        }
    }

    /** 状态消息不由业务控制器直接改 View；重建后分类仍显示，运行态不恢复。 */
    @Test fun observable_status_renders_and_category_survives_recreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                button(activity, DemoToolCategory.SEARCH.title).performClick()
                ViewModelProvider(activity)[MainViewModel::class.java]
                    .showStatus(DemoStatus("合成摘要", "状态渲染验证", DemoStatusKind.SUCCESS))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                    .any { it.text.toString().contains("合成摘要") && it.text.toString().contains("状态渲染验证") })
            }
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[MainViewModel::class.java].state.value
                assertEquals(DemoToolCategory.SEARCH, state.toolCategory)
                assertEquals(MainMapPhase.DISABLED, state.mapPhase)
                assertFalse(state.embeddedNavigationActive)
                assertNull(state.lastLocation)
                assertNotNull(button(activity, "周边 POI"))
            }
        }
    }

    /** 被拒绝隐私的定位点击只进入本地告知，不申请位置权限或创建地图会话。 */
    @Test fun locate_click_cannot_bypass_declined_consent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                button(activity, "定位").performClick()
                val state = ViewModelProvider(activity)[MainViewModel::class.java].state.value
                assertEquals(PrivacyDecision.DECLINED, state.privacyDecision)
                assertEquals(MainMapPhase.DISABLED, state.mapPhase)
                assertNull(state.pendingLocationAction)
                assertFalse(state.permissionRequestInFlight)
                assertNull(state.lastLocation)
                assertFalse(consent.hasConsent())
            }
        }
    }

    /** 从真实页面查找唯一按钮，不反射 Activity 的私有依赖图。 */
    private fun button(activity: MainActivity, text: String): Button =
        descendants(activity.findViewById(android.R.id.content)).filterIsInstance<Button>().single { it.text.toString() == text }

    /** 递归读取页面控件树，仅用于本地断言，不输出地图或身份内容。 */
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
}
