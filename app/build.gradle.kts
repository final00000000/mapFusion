import java.util.Properties

// 演示 App:展示如何通过 map-factory 在百度/高德之间切换。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val baiduMapApiKey = localProperties.getProperty("BAIDU_MAP_API_KEY", "YOUR_BAIDU_API_KEY")
val amapApiKey = localProperties.getProperty("AMAP_API_KEY", "YOUR_AMAP_API_KEY")
val usePublishedMapFusion = providers.gradleProperty("mapFusionUsePublishedSdk")
    .map(String::toBoolean)
    .getOrElse(false)
val mapFusionCoordinates =
    "${providers.gradleProperty("mapFusionGroup").get()}:map-fusion-full:" +
        providers.gradleProperty("mapFusionVersion").get()
fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.mapfusion.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mapfusion.demo"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BAIDU_MAP_API_KEY", baiduMapApiKey.asBuildConfigString())
        buildConfigField("String", "AMAP_API_KEY", amapApiKey.asBuildConfigString())
        manifestPlaceholders["BAIDU_MAP_API_KEY"] = baiduMapApiKey
        manifestPlaceholders["AMAP_API_KEY"] = amapApiKey
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
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
    // Demo 需要运行时切换双厂商，使用一步接入聚合包。
    if (usePublishedMapFusion) {
        implementation(mapFusionCoordinates)
    } else {
        implementation(project(":map-fusion-full"))
    }

    // 1.19.0 要求 compileSdk 37 + AGP 9.1；当前项目工具链上限为 36 / 8.11.1。
    //noinspection GradleDependency
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
