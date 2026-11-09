# Map Fusion

Map Fusion 是一个 Android / Kotlin 地图抽象层，把百度地图与高德地图统一到同一套业务接口下。业务代码只依赖 `map-api` 和 `map-factory`，切换厂商时只需修改 `MapConfig.provider` 与对应的 API Key。

当前版本 `0.9.0-SNAPSHOT`，百度 / 高德真实 SDK 与主要业务能力已接入。

## 模块结构

```text
                         ┌──> map-factory ──> map-api
app ──> map-fusion-full ─┼──> map-baidu ────> map-api
                         └──> map-amap ─────> map-api

官方 Navi 可选组合: map-baidu-navi 或 map-amap-navi ──> map-factory + 对应厂商适配器
```

- `map-api`：统一接口、数据模型与 SPI（`MapProvider`、`MapConfig`、`MapProviderFactory`）。
- `map-factory`：`ProviderRegistry`、`MapFusion` 门面与通用 `EmbeddedNavigator`、`TrackRecorder`。
- `map-baidu` / `map-amap`：各厂商适配器，仅依赖 `map-api`。
- `map-baidu-navi` / `map-amap-navi`：包含对应厂商地图与官方 App 内 Navi 的可选组合包。
- `map-fusion-full`：一步接入的聚合包，自动注册双厂商。
- `app`：可在百度 / 高德之间切换的示例；首页以地图为主视图，并按常用、导航、查询服务、覆盖物和轨迹五类展示公开 SDK 能力。

统一能力覆盖地图控制、定位、地理编码、POI 搜索、路线规划、内嵌导航、行政区检索与天气服务。地图控制器还直接提供屏幕坐标投影、可视区域、官方底图语言、审图/版权信息、路况配色、自定义地图样式、POI/Marker/触摸交互和可查询的 UI/手势能力；无法稳定统一的厂商能力可通过 `BaiduNativeAccess` / `AmapNativeAccess` 获取原生地图及常用官方对象。详细能力与厂商差异见 [CAPABILITY_MATRIX.md](CAPABILITY_MATRIX.md)。

## 快速开始

1. 克隆仓库并用 Android Studio 打开。
2. 在项目根目录的 `local.properties` 中填入你自己的 API Key（该文件不提交到版本库）：

   ```properties
   BAIDU_MAP_API_KEY=你的百度AK
   AMAP_API_KEY=你的高德Key
   ```

3. 构建脚本会自动把 Key 注入 `BuildConfig` 与 Manifest。运行 `:app` 即可。

三个 Demo 变体默认均使用 `com.mapfusion.demo`，与控制台登记的包名保持一致；安装不同变体会互相更新。
需要并排安装时使用 `-PmapFusionDemoSideBySide=true` 并为带后缀的实际包名单独配置 Key。
地图设置 → “SDK 接入诊断”会展示当前安装包的包名/SHA1；详见 [鉴权排查](docs/SDK_AUTHENTICATION.md)。
官方 API 的剩余缺口、精确签名清单生成方式和实施批次见 [全量覆盖计划](docs/OFFICIAL_API_COVERAGE_PLAN.md)，
当前并非全部 API 都已统一封装或真机验证。

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

val controller = session.mapController
controller.applyMapLanguage(MapLanguage.CHINESE)
val customStyle = MapCustomStyleOptions(
    enabled = true,
    localStylePath = filesDir.resolve("map-style.data").absolutePath,
)
when (val result = controller.applyCustomStyle(customStyle)) {
    is MapResult.Success -> Unit // 配置已被厂商 SDK 接受
    is MapResult.Failure -> error(result.error.message)
}
controller.setOnMapPoiClickListener { poi -> /* 使用统一 POI 信息 */ }
controller.setOnMarkerDragListener { event -> /* STARTED / DRAGGING / ENDED */ }
val visibleRegion = controller.visibleRegion()
val legalInfo = controller.legalInfo()

val baiduNative = session.provider.rawProvider(BaiduNativeAccess::class.java)
```

调用自定义样式前可用 `supportedCustomStyleFeatures()` 判断来源。两家均支持本地样式文件和样式 ID；
本地纹理文件只由当前高德 SDK 声明支持。`applyCustomStyle()` 成功表示配置已提交，不代表远程样式已下载或完成渲染。
高德离线地图下载属于单厂商能力，可从 `AmapNativeAccess.newOfflineMapManager()` 创建官方管理器并由调用方负责 `destroy()`；
百度地图 8.2 公共 AAR 没有对应公开下载管理 API，因此没有伪造统一离线接口。

`install()` 只注册工厂，`openSession()` 才会按 `MapConfig.provider` 初始化目标厂商并检查隐私同意状态。隐私告知、弹窗、同意记录与撤回入口仍属于宿主职责。

## 官方 App 内导航

官方 Navi 是独立可选能力，不会进入基础地图包。选择一个厂商时只需依赖对应模块：

```kotlin
dependencies {
    implementation(project(":map-amap-navi")) // 或 map-baidu-navi
}
```

```kotlin
AmapNaviSdk.updatePrivacyConsent(this, true)
val navigator = when (val result = AmapNaviSdk.createNavigator(this, savedInstanceState)) {
    is MapResult.Success -> result.data
    is MapResult.Failure -> error(result.error.message)
}
setContentView(navigator.view)

navigator.start(
    OfficialNavigationRequest(
        routeRequest = RouteRequest(
            mode = TravelMode.DRIVING,
            origin = LatLng(39.9087, 116.3975, CoordType.GCJ02),
            destination = LatLng(39.9928, 116.3109, CoordType.GCJ02),
        ),
        options = OfficialNavigationOptions(
            navigationMode = OfficialNavigationMode.SIMULATED, // 改为 REAL 即真实导航
        ),
    ),
) { event ->
    // StateChanged / RoutesReady / Progress / TrafficStatus / ServiceAreas /
    // WaypointProgress / HighwayGuidance / RouteNotice / RerouteCompleted / Arrived
}
```

宿主需要把 `onStart/onResume/onPause/onStop`、返回键、权限结果等转发给 `OfficialNavigator`，
在 `state == FAILED` 时读取 `lastError` 获取初始化阶段的厂商鉴权码、包名/SHA1 等诊断信息，
并在 Activity 销毁时调用 `destroy()`。百度当前官方车导航只声明 `DRIVING`；高德还声明步行、
自行车和电动自行车。调用前可通过 `AmapNaviSdk.capabilities()` 或
`BaiduNaviSdk.capabilities()` 查询真实支持范围。
两家都通过统一事件返回途经点到达及剩余距离/时间、服务区、分段路况、高速出口/方向/收费站、
厂商路线提醒、偏航重算完成和已行驶距离。高德当前依赖还会返回 GPS 开关/弱信号、区间测速、
路线剩余红绿灯及途经点剩余红绿灯。业务应按需查询 `WAYPOINT_PROGRESS`、`HIGHWAY_GUIDANCE`、
`ROUTE_NOTICES`、`GPS_STATUS` 和 `INTERVAL_CAMERA_ALERTS`，未声明的事件不会由适配器伪造。

导航会话还提供跨厂商的运行时控制：`recalculateRoute()`、`showRouteOverview()`、
`resumeFollowing()`、`setVoiceGuidanceEnabled()` 和 `setDayNightMode()`；高德额外声明
`RUNTIME_SIMULATION_SPEED`，可在模拟导航运行中调用 `setSimulationSpeed(kmh)`。调用前检查
`capabilities.supports(OfficialNavigationFeature.*)`，未声明能力会返回 `UNSUPPORTED`，不会静默
吞掉调用。需要厂商尚未统一的官方 API 时，可从 `AmapNaviSdk.nativeAccess()` 获取当前会话的
`AMapNavi/AMapNaviView`，或从 `BaiduNaviSdk.nativeAccess()` 获取当前会话的路线、设置、TTS 和
地图管理器；这些对象跟随会话生命周期，不能自行重新初始化或销毁。

不要同时依赖 `map-fusion-full` 和任一 Navi 模块：Navi 组合包已经替换了对应厂商的基础地图包，
叠加会重新引入重复类。也不要把两家 Navi 模块打入同一个 APK：两家都携带自己的
`libc++_shared.so`，直接 `pickFirst` 无法证明 ABI 兼容。需要同时演示时使用独立构建变体或独立 App。
正式导航还要求宿主完成厂商授权、语音与隐私条款、运行时权限、前台服务及真机验收。
百度车导航官方 AAR 的最低版本是 Android 6.0（API 23）；模块不会自动合并厂商声明的后台定位、
电话、外部存储和蓝牙权限，业务确实需要后台引导或车机连接时由宿主按场景显式增加并说明用途。
百度 Navi 依赖包含通知目标能力；使用通知或前台服务时宿主需声明 `POST_NOTIFICATIONS`，并在
Android 13 及以上按业务时机申请运行时授权。Demo 只在 `baiduNavi` 变体声明该权限。
Baidu Navi 组合包固定使用 `BaiduMapSDK_Map-AllNavi:8.1.0`（`base/bwnavi:8.1.0`、
`carnavi:8.0.1`），并与 Search 8.2.0 共存时排除 Search 传递的 `base:8.2.0`；组合包已通过
Release/R8 打包审计。百度控制台仍必须登记宿主最终包名和 release SHA1，否则官方引擎会在初始化或路线规划阶段返回鉴权错误。

Demo 已按依赖边界提供三个变体：

```powershell
.\gradlew.bat :app:assembleMapsDebug       # 双厂商基础地图与轻量导航
.\gradlew.bat :app:assembleAmapNaviDebug   # 高德官方真实/模拟导航
.\gradlew.bat :app:assembleBaiduNaviDebug  # 百度官方真实/模拟驾车导航，minSdk 23
```

Navi 变体首页的“导航”分类会显示“官方 Navi”入口。导航页使用统一 API 承载厂商原生 View，
根据运行时能力显示出行方式、路线控制、语音/昼夜控制和模拟速度，并转发权限结果、旋转、返回键
及完整 Activity 生命周期。已安装 `mapsDebug` 时不会出现官方 Navi 入口；该变体刻意不携带原生
导航库，需安装 `amapNaviDebug` 或 `baiduNaviDebug` 对应的独立包。

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

## 项目梳理与提交卫生

- 首次文档/代码核对见 [项目梳理报告](docs/PROJECT_AUDIT.md)。2026-11-09 开始完善两页 MVVM 状态基础、恢复和资源隔离，职责与未闭环项见 [Demo 架构](docs/DEMO_ARCHITECTURE.md)，不表示全部功能页拆分或生产验收完成。
- 调试截图和布局 JSON 不属于 SDK 资源，`artifacts/` 已忽略；公共验证输出放在 `build/reports/`。
- 凭据和签名指纹不得写进文档或代码。扫描和 hook 使用方式见 [提交卫生](docs/REPOSITORY_HYGIENE.md)。
- 提交统一采用 `type(scope): 描述`，例如 `feat(app): 完善状态管理`；约定和本地门禁见 [贡献说明](CONTRIBUTING.md)。

```powershell
python tools/check_sensitive.py             # 当前可提交文件
python tools/check_sensitive.py --staged    # 整个暂存区快照
git config --local core.hooksPath .githooks  # 新克隆需启用；已有 hook 时先手动合并
```

hook 检出敏感信息时只报告路径、行号与规则，并阻止提交；不会自动替换真实 Key 或改动暂存区。
