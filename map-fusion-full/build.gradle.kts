// ==============================================================================
// map-fusion-full：双厂商便捷聚合包
// 职责：一站式聚合 :map-factory、:map-baidu 与 :map-amap，并提供 MapFusionFull.install()
//      同时注册百度与高德适配器，适合需要运行时动态切换地图引擎的宿主直接依赖。
// ==============================================================================
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mapfusion.full"
}

dependencies {
    api(project(":map-factory"))
    api(project(":map-baidu"))
    api(project(":map-amap"))

    testImplementation(libs.junit)
}
