package com.mapfusion.api.capability

import com.mapfusion.api.model.LocationDisplayEvent
import com.mapfusion.api.model.LocationDisplayOptions
import com.mapfusion.api.model.LocationDisplayListener
import com.mapfusion.api.model.LocationDisplayState
import com.mapfusion.api.model.LocationDisplayStyle
import com.mapfusion.api.model.MapLocation
import com.mapfusion.api.model.MapResult

/**
 * 把连续定位、当前位置图标、精度圈和相机跟随组合成可直接使用的地图定位组件。
 *
 * 组件拥有内部 [LocationClient]，但不拥有地图控制器。所有控制方法和事件均在 Android
 * 主线程使用；[destroy] 幂等，销毁后不再投递事件。
 */
interface LocationDisplay : AutoCloseable {

    val state: LocationDisplayState

    /** 最近一次通过精度过滤并成功绘制的位置。 */
    val lastLocation: MapLocation?

    fun start(
        options: LocationDisplayOptions = LocationDisplayOptions(),
        listener: LocationDisplayListener = LocationDisplayListener {},
    ): MapResult<Unit>

    /** 运行中替换图标和精度圈样式，不重启底层定位。 */
    fun updateStyle(style: LocationDisplayStyle): MapResult<Unit>

    fun pause(): MapResult<Unit>

    fun resume(): MapResult<Unit>

    /** 停止定位并移除组件创建的图标和精度圈。 */
    fun stop(): MapResult<Unit>

    /** 宿主恢复前台时调用，只恢复由 [onPause] 自动暂停的会话。 */
    fun onResume()

    /** 宿主进入后台时调用；是否自动暂停由 [LocationDisplayOptions] 决定。 */
    fun onPause()

    fun destroy()

    override fun close() = destroy()
}
