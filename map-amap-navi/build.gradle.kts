// ==============================================================================
// map-amap-navi：高德官方 App 内导航扩展模块
// 职责：封装高德 AMapNaviView 与 AMapNavi 实时/模拟导航能力，并复用 :map-amap 的基础地图适配器。
// 依赖冲突处理：
// - 排除 :map-amap 中的基础 3dmap-location-search 包，统一替换为包含导航引擎的
//   navi-3dmap-location-search 合包，防止重复类打包冲突。
// ==============================================================================
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mapfusion.amap.navi"
}

dependencies {
    api(project(":map-api"))
    api(project(":map-factory"))
    api(project(":map-amap")) {
        exclude(group = "com.amap.api", module = "3dmap-location-search")
    }
    api(libs.amap.navi.map.location.search)
    runtimeOnly(libs.jafama)

    testImplementation(libs.junit)
}
