package com.mapfusion.demo.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 无 Android/厂商依赖的会话边界测试，验证实际宿主使用的身份和代次栅栏。 */
class NavigationSessionFenceTest {
    /** 用值相等但引用不同的对象模拟会话，防止误用 equals 接纳旧事件。 */
    private data class Identity(val name: String)

    /** 当前令牌可提交，值相等的新对象不能替代当前会话身份。 */
    @Test
    fun `accepts only current reference identity`() {
        val fence = NavigationSessionFence<Identity>()
        val session = Identity("session")
        val token = fence.attach(session)
        assertTrue(fence.accepts(token, finishing = false, destroyed = false))
        assertFalse(fence.accepts(NavigationSessionToken(Identity("session"), token.epoch), false, false))
    }

    /** 撤回时先清空引用并递增代次，随后旧会话 stop/destroy 的同步尾事件必须拒绝。 */
    @Test
    fun `invalidation clears identity before teardown callbacks`() {
        val fence = NavigationSessionFence<Any>()
        val session = Any()
        val oldToken = fence.attach(session)
        val detached = fence.invalidate()
        assertSame(session, detached)
        assertNull(fence.current)
        assertNull(fence.token())
        assertFalse(fence.accepts(oldToken, false, false))
        val newToken = fence.attach(Any())
        assertFalse(fence.accepts(oldToken, false, false))
        assertTrue(fence.accepts(newToken, false, false))
    }

    /** 即使厂商复用同一个会话对象，换代后的旧 UI/事件令牌仍然失效。 */
    @Test
    fun `reattaching same identity rejects earlier epoch`() {
        val fence = NavigationSessionFence<Any>()
        val session = Any()
        val oldToken = fence.attach(session)
        fence.invalidate()
        val newToken = fence.attach(session)
        assertFalse(fence.accepts(oldToken, false, false))
        assertTrue(fence.accepts(newToken, false, false))
    }

    /** 退出或销毁中的 Activity 不接受任何当前事件，缺失令牌也不能等待后续会话复活。 */
    @Test
    fun `finishing destroyed and absent tokens are rejected`() {
        val fence = NavigationSessionFence<Any>()
        val absent = fence.token()
        val current = fence.attach(Any())
        assertFalse(fence.accepts(current, true, false))
        assertFalse(fence.accepts(current, false, true))
        assertFalse(fence.accepts(absent, false, false))
        fence.invalidate()
        assertNull(fence.invalidate())
        assertFalse(fence.accepts(current, false, false))
    }

    /** 覆盖未释放的会话会泄漏，栅栏必须显式拒绝而非悄悄替换。 */
    @Test(expected = IllegalStateException::class)
    fun `cannot overwrite live session`() {
        val fence = NavigationSessionFence<Any>()
        fence.attach(Any())
        fence.attach(Any())
    }

    /** 实际轮询策略要求所有门禁成立；onStop、退出、撤回或初始化完成均停止排队。 */
    @Test
    fun `polling requires started alive consent and initializing`() {
        assertTrue(NavigationPollingPolicy.shouldPoll(true, true, true, true))
        assertFalse(NavigationPollingPolicy.shouldPoll(false, true, true, true))
        assertFalse(NavigationPollingPolicy.shouldPoll(true, false, true, true))
        assertFalse(NavigationPollingPolicy.shouldPoll(true, true, false, true))
        assertFalse(NavigationPollingPolicy.shouldPoll(true, true, true, false))
        org.junit.Assert.assertEquals(150L, NavigationPollingPolicy.INTERVAL_MS)
    }
}
