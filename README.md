# Map Fusion

Map Fusion 是一个 Android / Kotlin 地图抽象层，把百度地图与高德地图统一到同一套业务接口下。业务代码只依赖 `map-api` 和 `map-factory`，切换厂商时只需修改 `MapConfig.provider` 与对应的 API Key。

当前版本 `0.9.0-SNAPSHOT`，百度 / 高德真实 SDK 与主要业务能力已接入。

## 模块结构

```text
                         ┌──> map-factory ──> map-api
app ──> map-fusion-full ─┼──> map-baidu ────> map-api
                         └──> map-amap ─────> map-api
```

- `map-api`：统一接口、数据模型与 SPI（`MapProvider`、`MapConfig`、`MapProviderFactory`）。
- `map-factory`：`ProviderRegistry`、`MapFusion` 门面与通用 `EmbeddedNavigator`、`TrackRecorder`。
- `map-baidu` / `map-amap`：各厂商适配器，仅依赖 `map-api`。
- `map-fusion-full`：一步接入的聚合包，自动注册双厂商。
- `app`：可在百度 / 高德之间切换的示例。

统一能力覆盖地图控制、定位、地理编码、POI 搜索、路线规划、内嵌导航、行政区检索与天气服务。详细能力与厂商差异见 [CAPABILITY_MATRIX.md](CAPABILITY_MATRIX.md)。

## 快速开始

1. 克隆仓库并用 Android Studio 打开。
2. 在项目根目录的 `local.properties` 中填入你自己的 API Key（该文件不提交到版本库）：

   ```properties
   BAIDU_MAP_API_KEY=你的百度AK
   AMAP_API_KEY=你的高德Key
   ```

3. 构建脚本会自动把 Key 注入 `BuildConfig` 与 Manifest。运行 `:app` 即可。

## 在其他项目中接入

```kotlin
dependencies {
    implementation(project(":map-fusion-full"))
}
```

```kotlin
MapFusionFull.install()
val session = MapFusionFull.openSession(
    context = this,
    config = MapConfig(provider = Provider.BAIDU /* 或 Provider.AMAP */),
)
```

`install()` 只注册工厂，`openSession()` 才会按 `MapConfig.provider` 初始化目标厂商并检查隐私同意状态。隐私告知、弹窗、同意记录与撤回入口仍属于宿主职责。

## 权限

宿主 Manifest 至少需声明：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

## 注意事项

- 请勿提交 `local.properties`、密钥文件（`*.jks` / `*.keystore`）或任何私密凭据。
- API Key 请使用你自己申请的百度 / 高德开发者密钥。
