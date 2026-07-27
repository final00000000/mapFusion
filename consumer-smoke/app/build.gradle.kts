plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val mapFusionVersion = providers.gradleProperty("mapFusionVersion").get()
val mapFusionSources by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

android {
    namespace = "com.mapfusion.consumer.smoke"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mapfusion.consumer.smoke"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
    // 只能使用 Maven 坐标，禁止退回 project(...) 掩盖发布元数据问题。
    implementation("com.mapfusion:map-fusion-full:$mapFusionVersion")
    mapFusionSources("com.mapfusion:map-fusion-full:$mapFusionVersion:sources@jar")
}

tasks.register("verifyPublishedSdk") {
    group = "verification"
    description = "验证 Map Fusion 发布元数据、sources 与独立消费者 Release/R8"
    dependsOn("assembleRelease")

    doLast {
        val runtimeCoordinates = configurations.getByName("releaseRuntimeClasspath")
            .incoming
            .resolutionResult
            .allComponents
            .mapNotNull { component ->
                component.moduleVersion?.let { "${it.group}:${it.name}" }
            }
            .toSet()
        val expectedCoordinates = setOf(
            "com.mapfusion:map-api",
            "com.mapfusion:map-factory",
            "com.mapfusion:map-baidu",
            "com.mapfusion:map-amap",
            "com.mapfusion:map-fusion-full",
            "com.squareup.okhttp3:okhttp",
            "net.jafama:jafama",
        )
        val missingCoordinates = expectedCoordinates - runtimeCoordinates
        check(missingCoordinates.isEmpty()) {
            "发布运行时依赖缺失：${missingCoordinates.sorted().joinToString()}"
        }

        val sourceArtifacts = mapFusionSources.files
        check(sourceArtifacts.size == 1 && sourceArtifacts.single().name.endsWith("-sources.jar")) {
            "map-fusion-full sources JAR 未能从发布仓库唯一解析：$sourceArtifacts"
        }

        logger.lifecycle(
            "Map Fusion $mapFusionVersion 独立消费验证通过：Release/R8、传递依赖与 sources JAR 均可用。",
        )
    }
}
