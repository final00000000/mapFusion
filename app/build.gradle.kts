import java.util.Properties
import java.util.Locale

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
// 默认匹配控制台登记的身份；并排安装需要为每个最终包名单独登记 Key。
val demoSideBySide = providers.gradleProperty("mapFusionDemoSideBySide")
    .map(String::toBoolean).getOrElse(false)
val usePublishedMapFusion = providers.gradleProperty("mapFusionUsePublishedSdk")
    .map(String::toBoolean)
    .getOrElse(false)
val mapFusionGroup = providers.gradleProperty("mapFusionGroup").get()
val mapFusionVersion = providers.gradleProperty("mapFusionVersion").get()
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

    flavorDimensions += "demoSdk"
    productFlavors {
        create("maps") {
            dimension = "demoSdk"
            if (demoSideBySide) applicationIdSuffix = ".maps"
            versionNameSuffix = "-maps"
        }
        create("amapNavi") {
            dimension = "demoSdk"
            if (demoSideBySide) applicationIdSuffix = ".amapnavi"
            versionNameSuffix = "-amap-navi"
        }
        create("baiduNavi") {
            dimension = "demoSdk"
            if (demoSideBySide) applicationIdSuffix = ".baidunavi"
            versionNameSuffix = "-baidu-navi"
            // 百度官方 car Navi AAR 的真实最低版本。
            minSdk = 23
        }
    }

    productFlavors.configureEach {
        val suffix = name.uppercase(Locale.ROOT)
        val baiduKey = localProperties.getProperty("BAIDU_MAP_API_KEY_$suffix", baiduMapApiKey)
        val amapKey = localProperties.getProperty("AMAP_API_KEY_$suffix", amapApiKey)
        buildConfigField("String", "BAIDU_MAP_API_KEY", baiduKey.asBuildConfigString())
        buildConfigField("String", "AMAP_API_KEY", amapKey.asBuildConfigString())
        manifestPlaceholders["BAIDU_MAP_API_KEY"] = baiduKey
        manifestPlaceholders["AMAP_API_KEY"] = amapKey
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

// 仅导出所选版本的制品路径，不读取或输出 API Key。
tasks.register("exportOfficialSdkArtifacts") {
    group = "verification"
    description = "Export the resolved official SDK artifacts for API coverage inspection"
    val output = layout.buildDirectory.file("reports/official-api/artifacts.tsv")
    outputs.file(output)
    outputs.upToDateWhen { false }
    doLast {
        val rows = mutableListOf("variant\tcoordinate\tfile")
        listOf("maps", "amapNavi", "baiduNavi").forEach { flavor ->
            configurations.getByName("${flavor}DebugRuntimeClasspath").incoming.resolutionResult
                .allComponents.mapNotNull { it.moduleVersion }
                .filter { it.group in setOf("com.amap.api", "com.baidu.lbsyun") }
                .sortedBy { it.toString() }
                .forEach { module ->
                    // 按已选定的精确版本取原制品；避免要求 Android project 输出无类型 artifact。
                    val resolved = configurations.detachedConfiguration(dependencies.create(module.toString()))
                        .apply { isTransitive = false }.resolvedConfiguration.resolvedArtifacts
                    resolved.forEach { artifact ->
                        rows += "$flavor\t$module\t${artifact.file.absolutePath}"
                    }
                }
        }
        output.get().asFile.apply { parentFile.mkdirs(); writeText(rows.joinToString("\n") + "\n") }
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        val demoSdk = variant.productFlavors.firstOrNull { it.first == "demoSdk" }?.second
        if (demoSdk != null && demoSdk != "maps") {
            // 既有真机契约同时引用两家基础适配器，只属于双地图 maps 变体。
            variant.enableAndroidTest = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Navi 变体必须保持单厂商，避免两家 libc++_shared.so 在同一 APK 中相互覆盖。
    if (usePublishedMapFusion) {
        add("mapsImplementation", "$mapFusionGroup:map-fusion-full:$mapFusionVersion")
        add("amapNaviImplementation", "$mapFusionGroup:map-amap-navi:$mapFusionVersion")
        add("baiduNaviImplementation", "$mapFusionGroup:map-baidu-navi:$mapFusionVersion")
    } else {
        add("mapsImplementation", project(":map-fusion-full"))
        add("amapNaviImplementation", project(":map-amap-navi"))
        add("baiduNaviImplementation", project(":map-baidu-navi"))
    }

    // 1.19.0 要求 compileSdk 37 + AGP 9.1；当前项目工具链上限为 36 / 8.11.1。
    //noinspection GradleDependency
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    // maps 的测试 APK 继承 minSdk 21；2.4.0 的 shell 依赖要求 23。
    // 当前仅使用旧版 UiDevice/By/Until，锁定兼容版本，不提高业务 minSdk。
    //noinspection GradleDependency
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
