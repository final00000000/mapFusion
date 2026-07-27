// 双厂商便捷聚合包：自动注册百度和高德，适合需要运行时切换的宿主。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mapfusion.full"
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
    api(project(":map-factory"))
    api(project(":map-baidu"))
    api(project(":map-amap"))
    testImplementation("junit:junit:4.13.2")
}
