import java.util.Locale
import java.util.Properties

// ==============================================================================
// app：Map Fusion 演示与验收宿主应用
// 职责：
// 1. 演示高德与百度地图引擎在运行时的无缝动态切换及全部统一 SDK 能力。
// 2. 提供 maps（双基础地图）、amapNavi（高德官方导航 + 双地图）、baiduNavi（百度官方导航 + 双地图）三个变体。
// 3. 从未纳入版本控制的 local.properties 或环境变量安全注入 API Key 与可选 Release 签名。
// ==============================================================================
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 读取本地未跟踪的 local.properties，避免将真实 API Key 或签名凭据硬编码进仓库
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

/** 按 Gradle 属性 -> 系统环境变量 -> local.properties -> 占位符的优先级解析配置项。 */
fun resolveKey(name: String, placeholder: String): String =
    providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: localProperties.getProperty(name)
        ?: placeholder

val baiduApiKey = resolveKey("BAIDU_MAP_API_KEY", "YOUR_BAIDU_API_KEY")
val amapApiKey = resolveKey("AMAP_API_KEY", "YOUR_AMAP_API_KEY")
// 默认使用统一 applicationId 匹配控制台登记的包名+SHA1；若需同设备并排安装多变体，可通过此属性附加后缀
val demoSideBySide = providers.gradleProperty("mapFusionDemoSideBySide")
    .map(String::toBoolean).getOrElse(false)
// 是否改用已发布到 Maven 仓库的 SDK 坐标进行构建验证
val usePublishedMapFusion = providers.gradleProperty("mapFusionUsePublishedSdk")
    .map(String::toBoolean).getOrElse(false)
val mapFusionGroup = providers.gradleProperty("mapFusionGroup").get()
val mapFusionVersion = providers.gradleProperty("mapFusionVersion").get()

// 可选的自定义 Release 签名配置（未提供时回退至 debug 签名以便本地直接验证 R8 构建）
val releaseStoreFile = providers.gradleProperty("MAP_FUSION_DEMO_STORE_FILE").orNull
    ?: providers.environmentVariable("MAP_FUSION_DEMO_STORE_FILE").orNull
    ?: localProperties.getProperty("MAP_FUSION_DEMO_STORE_FILE")
val releaseStorePassword = providers.gradleProperty("MAP_FUSION_DEMO_STORE_PASSWORD").orNull
    ?: providers.environmentVariable("MAP_FUSION_DEMO_STORE_PASSWORD").orNull
    ?: localProperties.getProperty("MAP_FUSION_DEMO_STORE_PASSWORD")
val releaseKeyAlias = providers.gradleProperty("MAP_FUSION_DEMO_KEY_ALIAS").orNull
    ?: providers.environmentVariable("MAP_FUSION_DEMO_KEY_ALIAS").orNull
    ?: localProperties.getProperty("MAP_FUSION_DEMO_KEY_ALIAS")
val releaseKeyPassword = providers.gradleProperty("MAP_FUSION_DEMO_KEY_PASSWORD").orNull
    ?: providers.environmentVariable("MAP_FUSION_DEMO_KEY_PASSWORD").orNull
    ?: localProperties.getProperty("MAP_FUSION_DEMO_KEY_PASSWORD")
val hasCustomReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

/** 将任意字符串安全转义为 Java/Kotlin BuildConfig 字符串字面量。 */
fun String.asBuildConfigString(): String = buildString {
    val backslash = 92.toChar()
    val quote = 34.toChar()
    append(quote)
    for (ch in this@asBuildConfigString) {
        when (ch) {
            backslash -> {
                append(backslash)
                append(backslash)
            }
            quote -> {
                append(backslash)
                append(quote)
            }
            else -> append(ch)
        }
    }
    append(quote)
}

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
        manifestPlaceholders["BAIDU_MAP_API_KEY"] = baiduApiKey
        manifestPlaceholders["AMAP_API_KEY"] = amapApiKey
        buildConfigField("String", "BAIDU_MAP_API_KEY", baiduApiKey.asBuildConfigString())
        buildConfigField("String", "AMAP_API_KEY", amapApiKey.asBuildConfigString())
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "demoSdk"
    productFlavors {
        // 标准双地图变体：默认变体，装载百度+高德全量基础地图能力
        create("maps") {
            dimension = "demoSdk"
            isDefault = true
            if (demoSideBySide) applicationIdSuffix = ".maps"
            versionNameSuffix = "-maps"
        }
        // 高德官方导航变体：装载高德官方 Navi 引擎 + 高德/百度双基础地图切换能力
        create("amapNavi") {
            dimension = "demoSdk"
            if (demoSideBySide) applicationIdSuffix = ".amapnavi"
            versionNameSuffix = "-amap-navi"
        }
        // 百度官方导航变体：装载百度官方 Navi 引擎 + 百度/高德双基础地图切换能力
        create("baiduNavi") {
            dimension = "demoSdk"
            if (demoSideBySide) applicationIdSuffix = ".baidunavi"
            versionNameSuffix = "-baidu-navi"
            // 百度 carnavi 8.0.1 AAR 的真实最低版本要求为 API 23
            minSdk = 23
            // 百度官方导航原生库仅提供 32/64 位 ARM 架构
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    // 支持在 local.properties 中按变体名称后缀（如 BAIDU_MAP_API_KEY_MAPS）覆盖各自 Key
    productFlavors.configureEach {
        val suffix = name.uppercase(Locale.ROOT)
        val baiduKey = localProperties.getProperty("BAIDU_MAP_API_KEY_$suffix", baiduApiKey)
        val amapKey = localProperties.getProperty("AMAP_API_KEY_$suffix", amapApiKey)
        buildConfigField("String", "BAIDU_MAP_API_KEY", baiduKey.asBuildConfigString())
        buildConfigField("String", "AMAP_API_KEY", amapKey.asBuildConfigString())
        manifestPlaceholders["BAIDU_MAP_API_KEY"] = baiduKey
        manifestPlaceholders["AMAP_API_KEY"] = amapKey
    }

    signingConfigs {
        if (hasCustomReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // 未配置正式证书时自动回退至 debug 签名，方便本地直接构建并运行 Release/R8 包
            signingConfig = if (hasCustomReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        jniLibs {
            // 百度 carnavi 8.0.1 含有未按 16 KB 页对齐的旧版 so，需启用传统压缩打包方式
            useLegacyPackaging = true
            // 百度与高德均内置同名 libc++_shared.so，通过 pickFirsts 解决双厂商同包共存冲突
            pickFirsts += "**/libc++_shared.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 导出当前解析的高德/百度官方 SDK 制品路径，供 tools/official_api_inventory.py 核对字节码签名（不输出任何 API Key）
tasks.register("exportOfficialSdkArtifacts") {
    group = "verification"
    description = "导出已解析的高德与百度官方 SDK 制品路径供 API 覆盖率盘点脚本检查"
    val output = layout.buildDirectory.file("reports/official-api/artifacts.tsv")
    outputs.file(output)
    outputs.upToDateWhen { false }
    doLast {
        val tab = 9.toChar()
        val nl = 10.toChar().toString()
        val rows = mutableListOf("variant${tab}coordinate${tab}file")
        listOf("maps", "amapNavi", "baiduNavi").forEach { flavor ->
            configurations.getByName("${flavor}DebugRuntimeClasspath").incoming.resolutionResult
                .allComponents.mapNotNull { it.moduleVersion }
                .filter { it.group in setOf("com.amap.api", "com.baidu.lbsyun") }
                .sortedBy { it.toString() }
                .forEach { module ->
                    val resolved = configurations.detachedConfiguration(dependencies.create(module.toString()))
                        .apply { isTransitive = false }.resolvedConfiguration.resolvedArtifacts
                    resolved.forEach { artifact ->
                        rows += "$flavor$tab$module$tab${artifact.file.absolutePath}"
                    }
                }
        }
        output.get().asFile.apply { parentFile.mkdirs(); writeText(rows.joinToString(nl) + nl) }
    }
}

dependencies {
    // 三个变体均同时装载高德与百度基础地图适配器，确保任意变体下均可运行时无缝切换双厂商地图
    if (usePublishedMapFusion) {
        add("mapsImplementation", "$mapFusionGroup:map-fusion-full:$mapFusionVersion")
        add("amapNaviImplementation", "$mapFusionGroup:map-amap-navi:$mapFusionVersion")
        add("amapNaviImplementation", "$mapFusionGroup:map-baidu:$mapFusionVersion")
        add("baiduNaviImplementation", "$mapFusionGroup:map-baidu-navi:$mapFusionVersion")
        add("baiduNaviImplementation", "$mapFusionGroup:map-amap:$mapFusionVersion")
    } else {
        add("mapsImplementation", project(":map-fusion-full"))
        add("amapNaviImplementation", project(":map-amap-navi"))
        add("amapNaviImplementation", project(":map-baidu"))
        add("baiduNaviImplementation", project(":map-baidu-navi"))
        add("baiduNaviImplementation", project(":map-amap"))
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
