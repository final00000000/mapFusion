package com.mapfusion.demo.navigation

/** 捕获会话身份与代次的页面级令牌；即使复用同一个对象，旧代次也不能提交回调。 */
internal data class NavigationSessionToken<T : Any>(
    /** 捕获的会话引用，用 === 验证身份；仅供页面宿主/回调使用，不放入 VM 或恢复存储。 */
    val identity: T,
    /** 捕获时的代次计数，不是时间戳；同对象重新挂载后旧代次仍然无效。 */
    val epoch: Long
)

/** 单线程会话身份栅栏；仅管理引用和代次，不调用 SDK，也不保存业务状态。 */
internal class NavigationSessionFence<T : Any> {
    /** 当前栅栏代次，初始 0；挂载/失效递增，用于隔离旧回调，不持久化。 */
    private var epoch = 0L
    /** 当前受保护的会话引用；null 表示无会话，只有栅栏可更改，释放责任仍归宿主。 */
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

    /** 初始化补偿轮询间隔，单位 ms；仅 STARTED 中的有效初始化会话使用此 150ms 间隔。 */
    const val INTERVAL_MS = 150L
}
