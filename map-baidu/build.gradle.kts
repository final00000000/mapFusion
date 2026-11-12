// ==============================================================================
// map-baidu：百度地图基础适配器模块
// 职责：实现 :map-api 定义的地图渲染、覆盖物、定位、POI、地理编码、路线、行政区与天气接口。
// 依赖说明：
// 1. 组合引入百度官方 Map、Search 与 Location_All 稳定版本。
// 2. 百度 Location 9.6.8 的国际/HTTPDNS 路径直接调用 OkHttp，但官方 POM 未传递该依赖；
//    使用 runtimeOnly(libs.okhttp) 注入，既不泄漏到公开 API，又保证 R8 混淆与运行期路径完整。
// ==============================================================================
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mapfusion.baidu"
}

dependencies {
    api(project(":map-api"))
    api(libs.baidu.map)
    api(libs.baidu.search)
    api(libs.baidu.location)
    // 百度定位 SDK 运行时网络与 HTTPDNS 依赖 OkHttp 4.x。
    runtimeOnly(libs.okhttp)

    testImplementation(libs.junit)
}
