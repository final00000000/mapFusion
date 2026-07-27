// 高德地图适配器:实现 map-api 的各能力接口,把高德原生 SDK 翻译成统一模型。
// 第二阶段接入高德真实 SDK，把原生能力翻译成统一模型。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mapfusion.amap"
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
    // 高德各独立包包含重复基础类，必须使用官方组合包保证版本兼容。
    // AmapNativeAccess 的公开签名直接返回原生类型，消费者编译期必须可见组合包。
    api("com.amap.api:3dmap-location-search:11.2.000_loc11.2.000_sea9.8.0")
    // 组合包的定位算法直接引用 FastMath，但官方 POM 未传递该小型运行时依赖。
    runtimeOnly("net.jafama:jafama:2.3.2")
    // 若需要高德原生导航，改用 navi-3dmap-location-search 对应组合包，勿与本包重复引入。
}
