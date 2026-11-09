package com.mapfusion.demo.main

import com.mapfusion.api.capability.TrackListener
import com.mapfusion.api.capability.TrackRecorder
import com.mapfusion.api.capability.TrackState
import com.mapfusion.api.model.LocationOptions
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.TrackOptions
import com.mapfusion.api.model.TrackSnapshot

/** 轨迹功能及 recorder 所有者；替换、切厂商和销毁时由地图宿主显式释放。 */
internal class MainTrackFeature(private val host: MainMapHost) : AutoCloseable {
    private var recorder: TrackRecorder? = null

    /** 开始新轨迹前显式停止导航与定位展示，只清 Demo 自有覆盖物。 */
    fun start() = host.perform("开始轨迹") {
        val session = requireNotNull(host.session) { "地图尚未初始化" }
        session.embeddedNavigator?.stop(); host.viewModel.setNavigationActive(false)
        close(); session.locationDisplay?.stop(); host.overlays.clear()
        val created = requireNotNull(session.createTrackRecorder()) { "当前厂商不支持连续定位" }
        val epoch = host.requests.generation
        recorder = created
        host.messages.info("轨迹启动中", session.provider.provider.displayName())
        created.start(TrackOptions(locationOptions = LocationOptions(onceOnly = false, intervalMs = 2_000,
            needAddress = false, useCache = false, distanceFilterMeters = 1f), minPointDistanceMeters = 1.5,
            maxAccuracyMeters = 100f, drawOnMap = true, followLocation = true, followZoom = 17f,
            polylineWidth = 14f, polylineColor = DemoMapAssets.route),
            /** 当前轨迹监听器，额外验证 recorder 身份，拒绝被替换组件的迟到事件。 */
            object : TrackListener {
                /** 显示当前录制状态；切会话或换 recorder 后不再接收旧状态。 */
                override fun onStateChanged(snapshot: TrackSnapshot) = deliver(created, epoch) { showState(snapshot) }

                /** 有效点加入后展示统计，不把点列表写入页面恢复存储。 */
                override fun onPointAdded(snapshot: TrackSnapshot) = deliver(created, epoch) { showSnapshot("轨迹记录中", snapshot) }

                /** 将当前轨迹错误保留为失败状态，不能覆盖新 recorder 的结果。 */
                override fun onError(error: MapError) = deliver(created, epoch) { host.messages.mapError("轨迹记录失败", error) }
            })
    }

    /** 仅在录制或暂停状态之间切换，其他状态明确提示用户先开始。 */
    fun pauseOrResume() = host.perform("暂停/继续轨迹") {
        val current = requireNotNull(recorder) { "请先开始轨迹记录" }
        when (current.state) {
            TrackState.RECORDING -> current.pause(); TrackState.PAUSED -> current.resume()
            TrackState.IDLE, TrackState.STOPPED -> error("当前没有可暂停或继续的轨迹")
        }
    }

    /** 停止录制但保留组件结果，供页面展示最终统计。 */
    fun stop() = host.perform("结束轨迹") {
        showSnapshot("轨迹已结束", requireNotNull(recorder) { "请先开始轨迹记录" }.stop())
    }

    /** 用户主动清除仅释放轨迹组件自己的资源，不调用全局 clearOverlays。 */
    fun clear() = host.perform("清除轨迹") {
        close(); host.messages.success("轨迹已清除", "仅移除轨迹组件创建的折线与当前位置 Marker")
    }

    /** 在宿主代次校验后再核对 recorder 身份，防止同会话内旧组件回调串线。 */
    private fun deliver(source: TrackRecorder, epoch: Int, block: () -> Unit) = host.deliver(epoch) {
        if (recorder === source) block()
    }

    /** 把 SDK 轨迹状态映射为用户可理解的结果摘要。 */
    private fun showState(snapshot: TrackSnapshot) = when (snapshot.state) {
        TrackState.IDLE -> host.messages.info("轨迹已清除", "等待开始")
        TrackState.RECORDING -> showSnapshot("轨迹记录中", snapshot)
        TrackState.PAUSED -> showSnapshot("轨迹已暂停", snapshot)
        TrackState.STOPPED -> showSnapshot("轨迹已结束", snapshot)
    }

    /** 仅展示点数、距离、时长及过滤数，不额外持久化轨迹或真实位置。 */
    private fun showSnapshot(title: String, snapshot: TrackSnapshot) = host.messages.success(
        "$title · ${snapshot.points.size} 点", "${DemoLabels.trackDistance(snapshot.distanceMeters)} · " +
            "${DemoLabels.duration((snapshot.elapsedTimeMillis / 1_000).toInt())} · 过滤 ${snapshot.rejectedPointCount} 点")

    /** 先清空当前句柄再 destroy，使同步销毁回调也无法更新活跃组件状态。 */
    override fun close() { val previous = recorder; recorder = null; previous?.destroy() }
}
