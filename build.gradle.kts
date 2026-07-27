import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.GradleException
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension

// 顶层构建文件:声明共享插件版本与 Android Library 发布约定。
plugins {
    id("com.android.application") version "8.11.1" apply false
    id("com.android.library") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
}

apiValidation {
    // Demo 不是发布库；其余五个模块的公开 ABI 必须经过 apiCheck。
    ignoredProjects.add("app")
}

val mapFusionGroup = providers.gradleProperty("mapFusionGroup").get()
val mapFusionVersion = providers.gradleProperty("mapFusionVersion").get()
fun optionalProperty(name: String): String? =
    providers.gradleProperty(name).orNull?.trim()?.takeIf { it.isNotEmpty() }

fun optionalSecret(propertyName: String, environmentName: String): String? =
    optionalProperty(propertyName)
        ?: providers.environmentVariable(environmentName).orNull?.takeIf { it.isNotBlank() }

val pomUrl = optionalProperty("mapFusionPomUrl")
val pomLicenseName = optionalProperty("mapFusionPomLicenseName")
val pomLicenseUrl = optionalProperty("mapFusionPomLicenseUrl")
val pomDeveloperId = optionalProperty("mapFusionPomDeveloperId")
val pomDeveloperName = optionalProperty("mapFusionPomDeveloperName")
val pomDeveloperEmail = optionalProperty("mapFusionPomDeveloperEmail")
val pomScmConnection = optionalProperty("mapFusionPomScmConnection")
val pomScmDeveloperConnection = optionalProperty("mapFusionPomScmDeveloperConnection")
val pomScmUrl = optionalProperty("mapFusionPomScmUrl")
val requirePublicationMetadata =
    optionalProperty("mapFusionRequirePublicationMetadata")?.toBooleanStrictOrNull() ?: false
val signingKey = optionalSecret("mapFusionSigningKey", "MAP_FUSION_SIGNING_KEY")
val signingPassword = optionalSecret("mapFusionSigningPassword", "MAP_FUSION_SIGNING_PASSWORD")
val signPublications = optionalProperty("mapFusionSignPublications")?.toBooleanStrictOrNull()
    ?: (signingKey != null && signingPassword != null)

val publicationMetadata = linkedMapOf(
    "mapFusionPomUrl" to pomUrl,
    "mapFusionPomLicenseName" to pomLicenseName,
    "mapFusionPomLicenseUrl" to pomLicenseUrl,
    "mapFusionPomDeveloperId" to pomDeveloperId,
    "mapFusionPomDeveloperName" to pomDeveloperName,
    "mapFusionPomDeveloperEmail" to pomDeveloperEmail,
    "mapFusionPomScmConnection" to pomScmConnection,
    "mapFusionPomScmDeveloperConnection" to pomScmDeveloperConnection,
    "mapFusionPomScmUrl" to pomScmUrl,
)

val validatePublicationMetadata = tasks.register("validatePublicationMetadata") {
    group = "publishing"
    description = "校验正式 Maven 发布所需的 POM 元数据和签名配置"
    doLast {
        val missing = publicationMetadata.filterValues { it == null }.keys
        if (missing.isNotEmpty()) {
            throw GradleException("缺少正式发布元数据：${missing.joinToString()}")
        }
        if (!signPublications || signingKey == null || signingPassword == null) {
            throw GradleException(
                "正式发布必须启用 PGP 签名，并通过 Gradle 属性或环境变量提供内存密钥",
            )
        }
    }
}

allprojects {
    group = mapFusionGroup
    version = mapFusionVersion
}

val publicationDescriptions = mapOf(
    "map-api" to "Map Fusion 厂商无关的接口、能力契约与数据模型",
    "map-factory" to "Map Fusion Provider 注册、会话与通用能力编排",
    "map-baidu" to "Map Fusion 百度地图适配器",
    "map-amap" to "Map Fusion 高德地图适配器",
    "map-fusion-full" to "Map Fusion 百度与高德双厂商快速接入聚合包",
)

subprojects {
    plugins.withId("com.android.library") {
        pluginManager.apply("maven-publish")

        extensions.configure<LibraryExtension> {
            defaultConfig {
                consumerProguardFiles("consumer-rules.pro")
            }
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                }
            }
        }

        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications {
                    register<MavenPublication>("release") {
                        from(components["release"])
                        artifactId = project.name
                        pom {
                            name.set("Map Fusion ${project.name}")
                            description.set(publicationDescriptions[project.name])
                            pomUrl?.let(url::set)
                            if (pomLicenseName != null && pomLicenseUrl != null) {
                                licenses {
                                    license {
                                        name.set(pomLicenseName)
                                        url.set(pomLicenseUrl)
                                        distribution.set("repo")
                                    }
                                }
                            }
                            if (
                                pomDeveloperId != null ||
                                pomDeveloperName != null ||
                                pomDeveloperEmail != null
                            ) {
                                developers {
                                    developer {
                                        pomDeveloperId?.let(id::set)
                                        pomDeveloperName?.let(name::set)
                                        pomDeveloperEmail?.let(email::set)
                                    }
                                }
                            }
                            if (
                                pomScmConnection != null ||
                                pomScmDeveloperConnection != null ||
                                pomScmUrl != null
                            ) {
                                scm {
                                    pomScmConnection?.let(connection::set)
                                    pomScmDeveloperConnection?.let(developerConnection::set)
                                    pomScmUrl?.let(url::set)
                                }
                            }
                        }
                    }
                }
                repositories {
                    maven {
                        name = "mapFusion"
                        url = uri(
                            providers.gradleProperty("mapFusionRepositoryUrl").orElse(
                                rootProject.layout.buildDirectory.dir("repository")
                                    .map { it.asFile.toURI().toString() },
                            ).get(),
                        )

                        val repositoryUsername = optionalSecret(
                            "mapFusionRepositoryUsername",
                            "MAP_FUSION_REPOSITORY_USERNAME",
                        )
                        val repositoryPassword = optionalSecret(
                            "mapFusionRepositoryPassword",
                            "MAP_FUSION_REPOSITORY_PASSWORD",
                        )
                        if (repositoryUsername != null && repositoryPassword != null) {
                            credentials(PasswordCredentials::class) {
                                username = repositoryUsername
                                password = repositoryPassword
                            }
                        }
                    }
                }
            }

            if (signPublications) {
                if (signingKey == null || signingPassword == null) {
                    throw GradleException(
                        "mapFusionSignPublications=true，但没有同时提供签名密钥和密码",
                    )
                }
                pluginManager.apply("signing")
                val publishing = extensions.getByType(PublishingExtension::class.java)
                extensions.configure<SigningExtension> {
                    useInMemoryPgpKeys(signingKey, signingPassword)
                    sign(publishing.publications)
                }
            }

            if (requirePublicationMetadata) {
                tasks.matching { it.name.startsWith("publish") }.configureEach {
                    dependsOn(validatePublicationMetadata)
                }
            }
        }
    }
}
