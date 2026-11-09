plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mapfusion.amap.navi"
    compileSdk = 36

    defaultConfig { minSdk = 21 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":map-api"))
    api(project(":map-factory"))
    api(project(":map-amap")) {
        exclude(group = "com.amap.api", module = "3dmap-location-search")
    }
    api("com.amap.api:navi-3dmap-location-search:11.2.000_3dmap11.2.000_loc11.2.000_sea9.8.0")
    runtimeOnly("net.jafama:jafama:2.3.2")
    testImplementation("junit:junit:4.13.2")
}
