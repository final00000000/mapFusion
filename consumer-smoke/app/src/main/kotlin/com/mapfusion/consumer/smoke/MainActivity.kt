package com.mapfusion.consumer.smoke

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.mapfusion.api.MapConfig
import com.mapfusion.api.capability.Provider
import com.mapfusion.full.MapFusionFull

/**
 * 最小外部消费者只验证公开入口、Maven 传递依赖和 R8，不使用真实 Key 初始化厂商 SDK。
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapFusionFull.install()
        val config = MapConfig(
            provider = Provider.AMAP,
            apiKey = "consumer-smoke-placeholder",
        )

        setContentView(
            TextView(this).apply {
                text = "Map Fusion ${config.provider} consumer ready"
            },
        )
    }
}
