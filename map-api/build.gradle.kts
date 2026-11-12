// ==============================================================================
// map-api：核心抽象层
// 职责：定义厂商无关的地图控制器、定位、搜索、路线规划、导航接口与统一领域数据模型。
// 约束：仅依赖 Android 基础视图与生命周期类型，严禁依赖高德或百度任何厂商 SDK。
// 编译基准（compileSdk 36 / minSdk 21 / JVM 17）与 Maven 发布由根工程统一注入。
// ==============================================================================
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mapfusion.api"
}

dependencies {
    testImplementation(libs.junit)
}
