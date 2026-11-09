package com.mapfusion.demo.navigation

/** 捕获会话身份与代次的页面级令牌；即使复用同一个对象，旧代次也不能提交回调。 */
internal data class NavigationSessionToken<T : Any>(val identity: T, val epoch: Long)

/** 单线程会话身份栅栏；仅管理引用和代次，不调用 SDK，也不保存业务状态。 */
internal class NavigationSessionFence<T : Any> {
    private var epoch = 0L
    var current: T? = null
        private set

    /** 输入新会话，递增代次后持有它；宿主必须先释放已有会话，避免覆盖造成泄漏。 */
    fun attach(session: T): NavigationSessionToken<T> {
        check(current == null) { "旧导航会话必须先释放" }
        epoch += 1
        current = session
        return NavigationSessionToken(session, epoch)
    }

    /** 捕获当前会话令牌；无会话时返回 null，使页面回调无法作用于后续新会话。 */
    fun token(): NavigationSessionToken<T>? = current?.let { NavigationSessionToken(it, epoch) }

    /** 先递增代次并清空引用，再把旧会话交给宿主 stop/destroy；同步尾事件也会被拒绝。 */
    fun invalidate(): T? {
        epoch += 1
        val previous = current
        current = null
        return previous
    }

    /** 同时校验引用身份、代次以及 Activity 退出状态；不采用可能相等的值对象 equals。 */
    fun accepts(token: NavigationSessionToken<T>?, finishing: Boolean, destroyed: Boolean): Boolean =
        token != null && !finishing && !destroyed && token.epoch == epoch && current === token.identity
}

/** 初始化轮询的纯生命周期门禁；宿主每次调度和回调都重新计算，绝不在 STOPPED 继续。 */
internal object NavigationPollingPolicy {
    /** 输入页面 STARTED 状态、可用性和引擎快照，只允许未退出且仍初始化的有效会话轮询。 */
    fun shouldPoll(started: Boolean, alive: Boolean, consent: Boolean, initializing: Boolean): Boolean =
        started && alive && consent && initializing

    const val INTERVAL_MS = 150L
}
