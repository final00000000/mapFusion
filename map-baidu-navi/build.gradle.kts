// ==============================================================================
// map-baidu-navi：百度官方 App 内导航扩展模块
// 职责：封装百度驾车 (carnavi) 与骑步行 (bwnavi) 官方导航引擎，并复用 :map-baidu 的基础地图能力。
// 兼容性与依赖排雷说明：
// 1. 官方 carnavi 8.0.1 AAR 的 AndroidManifest 要求 minSdk = 23，故在此显式声明。
// 2. 排除 :map-baidu 中的基础 BaiduMapSDK_Map 与 base 8.2，改用 BaiduMapSDK_Map-AllNavi 8.1.0
//    （官方 8.2.0 错误传递了不含导航 so 的 carnavi 8.0.3，8.1.0 才包含完整匹配的原生库）。
// 3. 百度 carnavi 的内部布局资源与字节码直接使用了 ConstraintLayout、AppCompat、RecyclerView、
//    Glide、BNGlide4、NaviTts、Lottie、Fresco 与 Gson，但官方 POM 均漏掉了传递声明，必须显式补齐。
// ==============================================================================
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mapfusion.baidu.navi"

    defaultConfig {
        // 官方 carnavi 8.0.1 Manifest 的真实最低版本要求为 API 23。
        minSdk = 23
    }

    lint {
        // 百度 Navi / Map / Search 必须按兼容矩阵成组锁定，禁止按通用“最新版本”提示单独升级。
        disable += "NewerVersionAvailable"
    }
}

dependencies {
    api(project(":map-api"))
    api(project(":map-factory"))
    api(project(":map-baidu")) {
        exclude(group = "com.baidu.lbsyun", module = "BaiduMapSDK_Map")
        // Search 8.2 去除了与 bwnavi 8.1 的重复类，但其传递的 base 8.2 会覆盖导航引擎依赖的 8.1 so。
        exclude(group = "com.baidu.lbsyun", module = "base")
    }
    // 锁定携带完整导航原生 so 的 8.1.0 合包。
    api(libs.baidu.map.allnavi)
    // 补齐 carnavi 布局与运行期必需但官方 POM 缺失的 UI、图片加载、TTS 及序列化依赖。
    api(libs.baidu.bnglide)
    api(libs.glide)
    api(libs.baidu.navitts)
    api(libs.androidx.appcompat)
    api(libs.androidx.constraintlayout)
    api(libs.androidx.recyclerview)
    api(libs.androidx.cardview)
    api(libs.androidx.viewpager)
    api(libs.androidx.legacy.support.core.ui)
    api(libs.lottie)
    api(libs.fresco)
    api(libs.gson)
    // 百度 Location 9.6.8 的运行路径按基础适配器锁定 OkHttp 4.x。
    runtimeOnly(libs.okhttp)

    testImplementation(libs.junit)
}
