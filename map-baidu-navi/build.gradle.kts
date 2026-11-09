plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mapfusion.baidu.navi"
    compileSdk = 36

    // 官方 carnavi 8.0.1 Manifest 的真实最低版本为 23。
    defaultConfig { minSdk = 23 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Navi/Map/Search 必须作为一组升级，不能按通用“最新版本”提示单独替换。
        disable += "NewerVersionAvailable"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":map-api"))
    api(project(":map-factory"))
    api(project(":map-baidu")) {
        exclude(group = "com.baidu.lbsyun", module = "BaiduMapSDK_Map")
        // Search 8.2 去除了与 bwnavi 8.1 的重复类，但其 base 8.2 会覆盖导航引擎依赖的 8.1 so。
        exclude(group = "com.baidu.lbsyun", module = "base")
    }
    // 官方 8.1.0 组合包携带 ABI 匹配的 base/bwnavi 8.1 与 carnavi 8.0.1 导航引擎。
    // 8.2.0 错误传递了不含导航 so 的 carnavi 8.0.3，不能用于 App 内导航。
    api("com.baidu.lbsyun:BaiduMapSDK_Map-AllNavi:8.1.0")
    // carnavi 的运行路径直接引用图片加载与 TTS，但两个版本的官方 POM 均未声明。
    api("com.baidu.lbsyun:BNGlide4:1.0.1")
    api("com.github.bumptech.glide:glide:4.12.0")
    api("com.baidu.lbsyun:NaviTts:3.2.13")
    // 百度 carnavi 的布局/字节码直接引用这些 UI 库，但官方 POM 未声明。
    api("androidx.appcompat:appcompat:1.7.1")
    api("androidx.constraintlayout:constraintlayout:2.2.2")
    api("androidx.recyclerview:recyclerview:1.4.0")
    api("androidx.cardview:cardview:1.0.0")
    api("androidx.viewpager:viewpager:1.1.0")
    api("androidx.legacy:legacy-support-core-ui:1.0.0")
    api("com.airbnb.android:lottie:6.7.1")
    api("com.facebook.fresco:fresco:3.7.0")
    // carnavi serializes route, asset and guidance payloads through Gson, but its POM omits it.
    api("com.google.code.gson:gson:2.11.0")
    // 百度 Location 9.6.8 的运行路径按当前基础适配器锁定 OkHttp 4.x。
    //noinspection GradleDependency
    runtimeOnly("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
