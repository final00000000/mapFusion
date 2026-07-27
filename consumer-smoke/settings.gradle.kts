pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            name = "mapFusionUnderTest"
            url = uri(
                providers.gradleProperty("mapFusionRepository")
                    .orElse(file("../build/repository").toURI().toString())
                    .get(),
            )
            content { includeGroup("com.mapfusion") }
            metadataSources {
                gradleMetadata()
                mavenPom()
                artifact()
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "map-fusion-consumer-smoke"
include(":app")
