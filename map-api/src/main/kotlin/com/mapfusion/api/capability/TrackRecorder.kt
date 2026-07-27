package com.mapfusion.api.capability

import com.mapfusion.api.model.TrackOptions
import com.mapfusion.api.model.TrackSnapshot

/**
 * 轨迹记录能力。实现由 map-factory 提供,通过组合 LocationClient 与 MapController 完成。
 *
 * 轨迹组件只管理自己创建的覆盖物,不会清空业务方已有的 Marker/Polyline。
 */
interface TrackRecorder {

    val state: TrackState

    /** 开始一条新轨迹；监听事件通过 [TrackOptions.callbackExecutor] 异步串行派发。 */
    fun start(options: TrackOptions = TrackOptions(), listener: TrackListener = object : TrackListener {})

    /** 暂停定位与计时,已记录点保留。 */
    fun pause()

    /** 继续定位与计时。 */
    fun resume()

    /** 停止定位并冻结当前快照。 */
    fun stop(): TrackSnapshot

    /** 返回当前轨迹快照。 */
    fun snapshot(): TrackSnapshot

    /** 停止并清除当前轨迹及组件创建的覆盖物。 */
    fun clear()

    /** 释放定位客户端与覆盖物。调用后不可继续使用。 */
    fun destroy()
}

/** 轨迹生命周期状态。 */
enum class TrackState {
    IDLE,
    RECORDING,
    PAUSED,
    STOPPED,
}

/**
 * 轨迹监听器。回调默认派发到 Android 主线程，可通过 [TrackOptions.callbackExecutor]
 * 指定执行器；同一轨迹会话内的回调严格串行有序，销毁后不再投递排队事件。
 */
interface TrackListener {
    fun onStateChanged(snapshot: TrackSnapshot) = Unit
    fun onPointAdded(snapshot: TrackSnapshot) = Unit
    fun onError(error: com.mapfusion.api.model.MapError) = Unit
}
