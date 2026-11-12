import org.gradle.api.GradleException
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension

// ==============================================================================
// Map Fusion SDK Maven 发布与 PGP 签名独立脚本 (gradle/publishing.gradle.kts)
// 职责：
// 1. 为所有可发布的 SDK Library 模块统一配置 MavenPublication（Release AAR + Sources JAR）与 POM 元数据。
// 2. 读取 gradle.properties 或 CI 环境变量中的 POM 元数据、仓库凭据与内存 PGP 密钥。
// 3. 注册 validatePublicationMetadata 任务，在开启正式发布校验时强制检查 POM 与签名完整性。
// ==============================================================================

/** 读取非空白 Gradle 属性；若未设置或为空白字符串则返回 null。 */
fun optionalProperty(name: String): String? =
    providers.gradleProperty(name).orNull?.trim()?.takeIf { it.isNotEmpty() }

/** 优先从 Gradle 属性读取敏感配置，未提供时回退到系统环境变量。 */
fun optionalSecret(propertyName: String, environmentName: String): String? =
    optionalProperty(propertyName)
        ?: providers.environmentVariable(environmentName).orNull?.takeIf { it.isNotBlank() }

// POM 元数据与签名开关读取（支持通过 -P 参数或环境变量从 CI 注入）
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

// 正式发布必须具备的完整 POM 字段清单
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

// 各发布模块在 Maven POM 中的职能描述映射表
val publicationDescriptions = mapOf(
    "map-api" to "Map Fusion 厂商无关的接口、能力契约与数据模型",
    "map-factory" to "Map Fusion Provider 注册、会话与通用能力编排",
    "map-baidu" to "Map Fusion 百度地图适配器",
    "map-amap" to "Map Fusion 高德地图适配器",
    "map-baidu-navi" to "Map Fusion 百度官方 App 内导航可选适配器",
    "map-amap-navi" to "Map Fusion 高德官方 App 内导航可选适配器",
    "map-fusion-full" to "Map Fusion 百度与高德双厂商快速接入聚合包",
)

// 根工程级别的正式发布元数据校验任务（参考 gradle/publishing.properties.example）
val validatePublicationMetadata = tasks.register("validatePublicationMetadata") {
    group = "publishing"
    description = "校验正式 Maven 发布所需的 POM 元数据和 PGP 签名配置"
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

// 为属于发布列表的 Android Library 子模块自动装配 maven-publish 与 signing
subprojects {
    val moduleDescription = publicationDescriptions[name] ?: return@subprojects

    plugins.withId("com.android.library") {
        pluginManager.apply("maven-publish")

        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications {
                    register<MavenPublication>("release") {
                        from(components["release"])
                        artifactId = project.name
                        pom {
                            name.set("Map Fusion ${project.name}")
                            description.set(moduleDescription)
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
                        // 未指定远端仓库 URL 时，默认发布到根工程 build/repository 目录供本地与 consumer-smoke 验证
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

            // 按需启用内存 PGP 签名
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

            // 开启强制元数据校验时，令所有 publish 任务依赖 validatePublicationMetadata
            if (requirePublicationMetadata) {
                tasks.matching { it.name.startsWith("publish") }.configureEach {
                    dependsOn(validatePublicationMetadata)
                }
            }
        }
    }
}
