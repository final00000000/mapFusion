pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven {
            name = "mapFusionLocal"
            url = uri(rootDir.resolve("build/repository"))
            content { includeGroup("com.mapfusion") }
        }
        google()
        mavenCentral()
        // 百度/高德 SDK 后续接真实现时在此追加各自 Maven 源
    }
}

rootProject.name = "map-fusion"

include(":map-api")
include(":map-factory")
include(":map-baidu")
include(":map-amap")
include(":map-fusion-full")
include(":app")
