// 百度地图适配器:实现 map-api 的各能力接口,把百度原生 SDK 翻译成统一模型。
// 第二阶段接入百度真实 SDK，把原生能力翻译成统一模型。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mapfusion.baidu"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":map-api"))
    implementation("androidx.core:core-ktx:1.17.0")
    testImplementation("junit:junit:4.13.2")

    // 版本核对日期：2026-07-17。
    // BaiduNativeAccess 的公开签名直接返回原生类型，消费者编译期必须可见这些依赖。
    api("com.baidu.lbsyun:BaiduMapSDK_Map:8.1.0")
    api("com.baidu.lbsyun:BaiduMapSDK_Location_All:9.6.8")
    api("com.baidu.lbsyun:BaiduMapSDK_Search:8.1.0")
    // 百度 Location 9.6.8 的国际/HTTPDNS 路径直接调用 OkHttp，但官方 POM 未传递它。
    // 作为 runtime 依赖发布，既不污染本模块公开 API，也避免 R8 将该路径裁成不可达。
    runtimeOnly("com.squareup.okhttp3:okhttp:4.12.0")
    // 若需要百度原生导航，用 BaiduMapSDK_Map-AllNavi:8.1.0 替换 Map，勿重复引入两种地图包。
}
