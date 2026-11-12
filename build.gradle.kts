import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

// ==============================================================================
// 根工程构建配置 (Root build.gradle.kts)
// 职责：
// 1. 声明全局插件版本与二进制兼容性校验 (Binary Compatibility Validator) 规则。
// 2. 统一配置全工程的 Group、Version 以及所有 Library 子模块的编译基准（SDK 36 / minSdk 21 / JVM 17）。
// 3. 引入独立的 gradle/publishing.gradle.kts 脚本管理 Maven 发布与签名逻辑。
// ==============================================================================

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.binary.compatibility.validator)
}

apiValidation {
    // Demo 应用模块不是对外发布的 SDK 库；其余所有发布模块的公开 ABI 均须通过 apiCheck 校验。
    ignoredProjects.add("app")
}

val mapFusionGroup = providers.gradleProperty("mapFusionGroup").orElse("com.mapfusion")
val mapFusionVersion = providers.gradleProperty("mapFusionVersion").orElse("0.9.0-SNAPSHOT")

allprojects {
    group = mapFusionGroup.get()
    version = mapFusionVersion.get()
}

// 统一抽取所有 Library 子模块的 Android 编译版本、混淆传递规则、Release 发布变体与 Kotlin JVM 17 字节码目标。
subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk = 36
            defaultConfig {
                // 允许特定模块（如 map-baidu-navi 要求 23）在自身模块内按需覆盖 minSdk。
                if (minSdk == null) {
                    minSdk = 21
                }
                // 若模块目录下存在 consumer-rules.pro，则自动打入 AAR 随包传递给下游消费者。
                if (project.file("consumer-rules.pro").isFile) {
                    consumerProguardFiles("consumer-rules.pro")
                }
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            publishing {
                // 为所有 Android Library 模块启用带 Sources JAR 的单 Release 发布组件，供 publishing.gradle.kts 注册。
                singleVariant("release") {
                    withSourcesJar()
                }
            }
        }
    }

    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<KotlinAndroidProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
}

// 引入独立的 SDK Maven 发布与 PGP 签名脚本
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
