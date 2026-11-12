// ==============================================================================
// map-amap：高德地图基础适配器模块
// 职责：实现 :map-api 定义的地图渲染、覆盖物、定位、POI、地理编码、路线、行政区与天气接口。
// 依赖说明：
// 1. 使用高德官方 3dmap-location-search 合包，避免 3D 地图、定位与搜索版本不一致引起的符号冲突。
// 2. 组合包内部定位算法直接调用 FastMath，但官方 POM 未声明传递依赖，故显式补充 runtimeOnly(libs.jafama)。
// 3. 若宿主同时需要高德官方导航，应使用 :map-amap-navi 模块替代本模块中的基础合包。
// ==============================================================================
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mapfusion.amap"
}

dependencies {
    api(project(":map-api"))
    api(libs.amap.map.location.search)
    // 高德组合包的定位算法直接引用 FastMath，作为 runtimeOnly 注入避免污染公开 API。
    runtimeOnly(libs.jafama)

    testImplementation(libs.junit)
}
