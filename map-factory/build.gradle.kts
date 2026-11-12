// ==============================================================================
// map-factory：工厂、注册表与会话编排层
// 职责：提供 ProviderRegistry 动态注册表、MapFusionSession 生命周期托管，以及基于统一接口的
//      定位图层展示 (DefaultLocationDisplay)、轻量内嵌导航 (DefaultEmbeddedNavigator) 与轨迹录制。
// 约束：仅依赖 :map-api 抽象层，不硬编码编译依赖任何具体厂商适配器模块。
// ==============================================================================
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mapfusion.factory"
}

dependencies {
    api(project(":map-api"))
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
