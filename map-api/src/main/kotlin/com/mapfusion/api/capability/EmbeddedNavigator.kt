package com.mapfusion.api.capability

import com.mapfusion.api.model.EmbeddedNavigationListener
import com.mapfusion.api.model.EmbeddedNavigationRequest
import com.mapfusion.api.model.EmbeddedNavigationState
import com.mapfusion.api.model.MapResult

/**
 * 在宿主当前 MapView 内运行的导航会话，不启动第三方 App 或浏览器。
 *
 * 会话拥有自己创建的路线和当前位置覆盖物，但不拥有传入的地图、定位及路线规划能力。
 * [destroy] 必须幂等；调用后不得再投递事件。
 */
interface EmbeddedNavigator : AutoCloseable {

    val state: EmbeddedNavigationState

    /**
     * 异步规划路线，并按请求模式开始连续定位或路线模拟。同步返回值仅表示请求是否被接受；监听事件始终通过
     * [EmbeddedNavigationRequest.options] 配置的执行器异步派发，默认使用 Android 主线程。
     */
    fun start(
        request: EmbeddedNavigationRequest,
        listener: EmbeddedNavigationListener,
    ): MapResult<Unit>

    fun pause(): MapResult<Unit>

    fun resume(): MapResult<Unit>

    /** 停止导航并移除本会话创建的覆盖物。 */
    fun stop(): MapResult<Unit>

    /** Activity/Fragment 恢复前台时调用；仅恢复由 [onPause] 暂停的会话。 */
    fun onResume()

    /** Activity/Fragment 进入后台时调用，停止连续定位以避免无意的后台定位。 */
    fun onPause()

    /** 幂等释放；不销毁外部传入的 LocationClient、RoutePlanner 或 MapController。 */
    fun destroy()

    override fun close() = destroy()
}
