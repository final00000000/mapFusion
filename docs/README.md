# Map Fusion

> 本文件为保留的本地历史笔记，不作为当前完成度或发布依据。当前接入说明见
> [根 README](../README.md)，能力状态见 [能力矩阵](../CAPABILITY_MATRIX.md)，
> 本次文档/架构核对见 [项目梳理报告](PROJECT_AUDIT.md)。

Map Fusion 是一个 Android/Kotlin 地图抽象层，把百度地图与高德地图统一到同一套业务接口下。业务代码依赖 `map-api` 与 `map-factory`，切换厂商时只修改 `MapConfig.provider` 和对应 `apiKey`。

> 百度/高德真实 SDK 与主要业务能力已经接入。当前版本仍为 `0.9.0-SNAPSHOT`，
> 生产范围、厂商差异和发布门槛以 [CAPABILITY_MATRIX.md](CAPABILITY_MATRIX.md) 为准。

## 模块与依赖方向

```text
                         ┌──> map-factory ──> map-api
app ──> map-fusion-full ─┼──> map-baidu ────> map-api
                         └──> map-amap ─────> map-api

单厂商最小组合: app ──> map-factory ──> map-api <── map-baidu 或 map-amap
```

- `map-api`：8 个 Provider 小能力接口、2 个通用组合能力接口、统一数据模型、`MapProvider`、`MapConfig` 和 `MapProviderFactory` SPI。
- `map-factory`：`ProviderRegistry` 注册表、`MapFusion` 门面与通用 `EmbeddedNavigator`、`TrackRecorder` 实现。
- `map-baidu`：百度适配器；只依赖 `map-api`。
- `map-amap`：高德适配器；只依赖 `map-api`。
- `map-fusion-full`：同时依赖 factory、百度和高德的一步接入聚合包，自动注册双厂商。
- `app`：可在百度/高德之间切换的示例。

统一能力包括 `MapController`、`LocationClient`、`Geocoder`、`PoiSearcher`、`RoutePlanner`、`Navigator`、`DistrictSearcher` 和 `WeatherService`。其中：

- `MapController`：原生 MapView 创建与生命周期、相机/区域适配、Marker、原生海量点、折线、多边形、圆、地面图片、文字、瓦片和热力图、地图类型、建筑/室内/交通、UI/手势、截图和交互事件。
- `LocationClient`：单次/连续定位，精度、间隔、超时、缓存、模拟位置和 GPS 优先选项。
- `LocationDisplay`：开箱即用的实时位置图标、精度圈和相机跟随；可配置图标、锚点、透明度、
  航向旋转、首次/持续/航向朝上跟随、精度过滤与前后台暂停恢复。
- `Geocoder`：正/逆地理编码，逆编码半径和周边 POI。
- `PoiSearcher`：关键字/周边搜索、分类、排序、分页、详情和输入提示。
- `RoutePlanner`：驾车、步行、自行车、电动自行车、公交，途经点、路线偏好、避轮渡和车牌参数；通过 `supportedModes()/supportsMode()` 查询当前厂商真实支持范围。
- `EmbeddedNavigator`：在当前 MapView 内完成路线绘制，并支持设备定位驱动的真实导航或沿路线推进的模拟导航，以及相机跟随、步骤/剩余距离、到达和偏航重算；不跳转第三方 App。
- `Navigator`：兼容旧业务的外部地图 URI 入口；新业务应优先使用 `EmbeddedNavigator`。
- `DistrictSearcher`：按行政区关键词检索中心点、编码、下级行政区和边界环。
- `WeatherService`：查询实时天气与逐日天气预报。

`EmbeddedNavigator` 和 `TrackRecorder` 是基于小能力组合出的厂商无关能力，不计入
`MapProvider.capabilities()` 的 8 项厂商原生能力集合。它们只管理自己创建的请求与覆盖物，
并由 `MapFusionSession` 统一释放。

## 在其他项目中接入

运行时需要切换百度和高德时，直接依赖聚合包：

```kotlin
dependencies {
    implementation(project(":map-fusion-full"))
    // Maven: implementation("com.mapfusion:map-fusion-full:0.9.0-SNAPSHOT")
}
```

`MapFusionFull.install()` 只注册工厂，不会初始化厂商 SDK。`create/openSession` 才会按
`MapConfig.provider` 初始化目标厂商，并强制检查隐私同意状态：

```kotlin
val session = MapFusionFull.openSession(
    context = this,
    config = MapConfig(
        provider = Provider.AMAP,
        apiKey = "你的高德 Key",
        enablePrivacyCompliance = true,
    ),
    savedState = savedInstanceState,
)
mapContainer.addView(session.view)
```

只使用一家时，应选择最小组合，避免把另一家 SDK 和原生库带入宿主：

```kotlin
dependencies {
    implementation(project(":map-factory"))
    implementation(project(":map-amap")) // 或 map-baidu，只选一家
}
```

在 `Application.onCreate` 注册实际引入的适配器，然后使用 `MapFusion`：

```kotlin
override fun onCreate() {
    super.onCreate()
    MapFusion.register(AmapProviderFactory())
}

val provider = MapFusion.create(
    context,
    MapConfig(
        provider = Provider.AMAP,
        apiKey = "你的高德 Key",
        enablePrivacyCompliance = true,
    ),
)
```

### 快速接入会话

不需要分别保存 `MapProvider`、`MapController` 并手工释放能力时，可以直接使用 `MapFusionSession`：

```kotlin
private lateinit var session: MapFusionSession

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    session = MapFusionFull.openSession(
        context = this,
        config = MapConfig(
            provider = Provider.AMAP,
            apiKey = "你的高德 Key",
            enablePrivacyCompliance = true,
        ),
        savedState = savedInstanceState,
    )
    mapContainer.addView(session.view)

    session.locationClient?.requestSingleLocation { result -> /* MapLocation */ }
    session.locationDisplay?.start(
        LocationDisplayOptions(
            followMode = LocationFollowMode.FIRST_FIX,
            style = LocationDisplayStyle(
                marker = LocationMarkerStyle(
                    icon = MarkerIcon.Resource(R.drawable.ic_my_location),
                    flat = true,
                    rotateWithBearing = true,
                ),
                accuracy = LocationAccuracyStyle(
                    strokeColor = Color.BLUE,
                    fillColor = 0x223F51B5,
                ),
            ),
        ),
    ) { event -> /* LocationUpdated / AccuracyRejected / Failure */ }
    session.routePlanner?.plan(routeRequest) { result -> /* RouteResult */ }
    session.embeddedNavigator?.start(
        EmbeddedNavigationRequest(
            routeRequest = routeRequest,
            options = EmbeddedNavigationOptions(
                // REAL 使用设备连续定位；SIMULATED 不请求定位，按路线和配置速度推进。
                navigationMode = EmbeddedNavigationMode.SIMULATED,
                simulationSpeedMetersPerSecond = 13.9f,
                simulationIntervalMillis = 500L,
            ),
        ),
    ) { event -> /* RouteReady / Progress / Arrived / Error */ }
}

override fun onResume() { super.onResume(); session.onResume() }
override fun onPause() { session.onPause(); super.onPause() }
override fun onDestroy() { session.destroy(); super.onDestroy() }
```

`MapFusionSession` 按需创建定位、定位展示、地理编码、POI、路线、行政区、天气和导航能力，
并在 `destroy()` 时统一释放已创建资源。`LocationDisplay` 使用独立 `LocationClient`，不会停止
页面的普通定位、轨迹或导航请求；Session 会自动转发前后台暂停/恢复。
`createTrackRecorder()` 也由会话登记管理，避免业务遗漏销毁。

Session 为普通业务定位/路线与内嵌导航分别创建原生能力实例，导航不会抢占页面正在进行的
普通定位或路线请求。已显式销毁的轨迹记录器也会立即从 Session 的所有权集合注销。

`MarkerOptions` 与 `MapMarker` 现已统一图标旋转、透明度和平贴地图属性。`MapOverlay.isRemoved`
可判断句柄是否已被单独删除或被 `clearOverlays()` 清理；`LocationDisplay` 会在下一次定位更新时
自动重建被业务清图移除的定位图标和精度圈。

`addMultiPointOverlay()` 直接使用百度 `MultiPoint` 和高德 `MultiPointOverlay`，不会循环创建普通
Marker。它支持统一图标、锚点、批量替换、显隐、点击开关、业务 id/tag 和幂等移除；高德原生
海量点没有 zIndex，因此统一句柄没有伪造层级属性：

```kotlin
val points = (0 until 1_000).map { index ->
    MultiPointItem(
        id = "poi-$index",
        position = businessPoints[index], // WGS84 / GCJ02 / BD09，必须明确声明
        title = "点位 ${index + 1}",
        tag = domainObjects[index],
    )
}
val layer = session.mapController.addMultiPointOverlay(
    MultiPointOverlayOptions(
        items = points,
        icon = MarkerIcon.Resource(R.drawable.ic_dense_point),
        anchorU = 0.5f,
        anchorV = 0.5f,
    ),
)
session.mapController.setOnMultiPointClickListener { clickedLayer, item ->
    showPoint(item.id, item.tag)
    clickedLayer === layer
}

layer.items = refreshedPoints
layer.remove()
```

`MapConfig.extras` 当前只是 SPI 预留，两家适配器尚未解释任何稳定 key。生产业务不要依赖
未文档化的 extras；厂商独有能力应暂时通过类型安全的 `rawProvider()` 局部访问，1.0 前再决定
需要固化的扩展配置。

`EmbeddedNavigator` 的 `start()` 只在当前地图内运行，不启动百度/高德 App；返回成功表示路线规划请求已接受，终态通过事件监听器通知。`EmbeddedNavigationMode.REAL` 使用独立 `LocationClient` 的设备连续定位推进，宿主必须先完成位置权限和隐私流程；`SIMULATED` 不启动定位，而是按规划路线、`simulationSpeedMetersPerSecond` 和 `simulationIntervalMillis` 生成模拟位置。两种模式都可调用 `pause()`、`resume()`、`stop()`，会话销毁时会取消所属定位或模拟任务，并移除路线和当前位置 Marker。`EmbeddedNavigationProgress.navigationMode` 会标明当前数据来源。

这是由统一路线规划、连续定位和本地路线模拟组合出的轻量 App 内路线引导，不是百度/高德官方导航引擎；这里的 `REAL/SIMULATED` 也不是厂商 Navi 引擎的真实/模拟模式。
不得当作车道级 turn-by-turn 导航宣传。当前不含官方语音、车道线、路口放大图、电子眼、
限速和导航数据闭环；需要这些能力时必须接入后续独立的厂商 Navi 模块。

`Navigator.startNavigation()` 仍保留给需要外部厂商页面的旧业务。它的成功结果只表示外部 `Intent` 已被 Android 接受，不代表页面已加载完成；百度和高德 URI 均无法准确表达电动车导航，该模式会明确返回 `UNSUPPORTED`。

### 路线绘制与自定义起终点

路线规划结果不需要业务分别管理两个 Marker 和一条 Polyline。`addRoute` 返回组合句柄，
支持资源、assets 或 PNG/JPEG 字节图标，`remove()` 幂等移除整条路线：

```kotlin
val overlay = session.mapController.addRoute(
    request = routeRequest,
    path = routeResult.paths.first(),
    options = RouteOverlayOptions(
        startMarker = RouteMarkerOptions(
            icon = MarkerIcon.Resource(R.drawable.route_start),
            title = "起点",
        ),
        endMarker = RouteMarkerOptions(
            icon = MarkerIcon.Asset("route_end.png"),
            title = "终点",
        ),
        lineWidth = 14f,
        lineColor = Color.BLUE,
    ),
)

overlay.remove()
```

路线规划和内嵌导航共用同一套 `RouteOverlayOptions`，因此起终点图标、路线颜色、宽度、
层级和点击语义不需要在 Demo 或业务层重复实现。需要展示一组点时使用
`MapController.fitPoints()`；它会校验坐标系并在单点时居中、多点时使用厂商 bounds。

### 异步取消、超时与线程

定位、地理编码、POI、路线、行政区、天气和截图都返回 `RequestHandle`。默认回调在
Android 主线程执行；单次请求可以覆盖超时和回调 Executor：

```kotlin
val handle = session.routePlanner?.plan(
    routeRequest,
    AsyncCallOptions(timeoutMillis = 20_000),
) { result ->
    result.onSuccess { /* 主线程更新 UI */ }
}

handle?.cancel() // 仅取消这次路线请求，回调 ErrorType.CANCELLED
```

超时统一返回 `TIMEOUT`，取消/完成/超时只允许一个终态。连续定位句柄表示整个订阅，
超时只约束首次定位结果；`stopContinuousLocation()` 和能力 `destroy()` 为兼容清理入口，
不会让迟到的厂商回调穿透销毁边界。完整契约见 [ASYNC_CONTRACT.md](ASYNC_CONTRACT.md)。

页面同时发起多类请求时，用 `RequestScope` 按业务键托管句柄。同 key 的新请求会取消旧请求，
切换 Provider 时调用 `cancelAll()`，页面销毁时调用 `close()`：

```kotlin
private val requests = RequestScope()

fun search(request: PoiSearchRequest) {
    val handle = requireNotNull(session.poiSearcher).search(request) { result ->
        // 默认主线程回调
    }
    requests.replace("poi", handle)
}

fun switchProvider() {
    requests.cancelAll()
    // 销毁旧 Session，再创建新 Session
}

override fun onDestroy() {
    requests.close()
    session.destroy()
    super.onDestroy()
}
```

`EmbeddedNavigationOptions.callbackExecutor` 和 `TrackOptions.callbackExecutor` 可覆盖组合能力的
默认主线程；即使使用线程池，同一导航/轨迹会话内的事件也会串行派发，销毁后排队事件会丢弃。
`RequestScope` 取消请求时业务仍会收到一次 `CANCELLED` 终态；切图页面还应像 Demo 一样用
Provider epoch 或自身生命周期状态忽略旧页面的终态 UI 更新。

切到百度时只替换配置：

```kotlin
val config = MapConfig(
    provider = Provider.BAIDU,
    apiKey = "你的百度 AK",
    enablePrivacyCompliance = true,
)
```

之后的业务调用保持不变，并可先查询能力：

```kotlin
if (provider.supports(Capability.LOCATION)) {
    provider.locationClient()?.requestSingleLocation { result ->
        result
            .onSuccess { location -> /* 使用统一 MapLocation */ }
            .onFailure { error -> /* 使用统一 MapError */ }
    }
}
```

`MapController` 的 `onCreate/onResume/onPause/onDestroy/onSaveInstanceState/onLowMemory` 必须由宿主 Activity 或 Fragment 转发。完整切换与生命周期示例见 `app/src/main/kotlin/com/mapfusion/demo/MainActivity.kt`。

## 隐私合规

`MapConfig.enablePrivacyCompliance` 默认为 `false`。宿主必须先完成隐私告知、取得用户明确同意并保存同意状态，
之后才能传入 `true` 创建 Provider。未明确同意时，两家工厂会在接触任何厂商 API 前抛出
`PrivacyConsentRequiredException`，SDK 不会替宿主推定同意。

用户撤回同意后，宿主必须先取消在途请求、销毁现有 `MapFusionSession`，再把撤回状态同步给
已注册厂商，并且在再次取得同意前不创建新的 Provider：

```kotlin
requests.cancelAll()
session.destroy()

val results = MapFusionFull.updatePrivacyConsent(
    context = applicationContext,
    consentGranted = false,
)
// 单厂商接入也可调用 MapFusion.updatePrivacyConsent(context, provider, false)
```

`MapProviderFactory.updatePrivacyConsent()` 只同步厂商地图、定位和搜索组件的合规标记，不创建
地图或服务实例。隐私告知内容、弹窗、同意记录、撤回入口和审计留痕仍属于宿主职责。

## API Key 放置位置

Demo 已从 `local.properties` 读取 Key，并自动注入 `BuildConfig` 与 Manifest。添加以下两行后重新构建：

```properties
BAIDU_MAP_API_KEY=你的百度AK
AMAP_API_KEY=你的高德Key
```

`local.properties` 不应提交到版本库。正式项目也可以改用 CI Secret 注入。

宿主 App 还需在 `AndroidManifest.xml` 写入厂商要求的 meta-data：

```xml
<application>
    <!-- 百度 -->
    <meta-data
        android:name="com.baidu.lbsapi.API_KEY"
        android:value="${BAIDU_API_KEY}" />

    <!-- 高德 -->
    <meta-data
        android:name="com.amap.api.v2.apikey"
        android:value="${AMAP_API_KEY}" />
</application>
```

宿主 Manifest 至少还应声明网络和所需定位权限：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

`${...}` 是 Gradle manifest placeholder；也可以直接填 Key，但不建议提交生产密钥。定位权限由宿主 App 申请，适配器不会弹运行时权限框。

Demo 会在首次进入时申请精确/大致位置权限，并通过 `session.locationDisplay` 自动展示定制位置
图标、航向和精度圈；每次切换百度或高德后都会重建对应的独立定位展示。路线规划与导航统一
在“路线导航”面板选择出行方式后执行；地图类型、交通、建筑、室内、底图 POI、指南针和缩放
控件统一在“地图设置”面板应用。库适配器本身不弹权限框，权限缺失时统一返回
`MapResult.Failure(PERMISSION)`。行政区、天气及各类演示覆盖物均由对应按钮单独触发，异步结果
会在地图上方保留错误类型和厂商原始错误码。

权限被拒绝后，自动切图不会反复弹系统授权框；用户可以点击“定位”重新申请。若权限被永久拒绝，Demo 会引导用户前往应用设置，开启权限并返回后自动继续定位。这些权限交互属于宿主 App 职责，地图抽象库不会持有 Activity 或强制展示 UI。

Demo 首次创建地图及每次切换厂商后都会自动发起一次定位；之后仍可通过“定位”按钮重复请求。百度适配器支持自行车和电动自行车两种路线参数，高德当前 SDK 支持步行和自行车，电动自行车会明确返回 `UNSUPPORTED`，不会静默降级为普通自行车。

地图类型也不会静默降级。业务应先读取 `supportedMapTypes()`，再调用
`applyMapType()` 并处理 `MapResult`。百度当前公开 NORMAL/SATELLITE/NONE，高德公开
NORMAL/SATELLITE/NIGHT/NAVIGATION；旧 `setMapType()` 仅为兼容保留，已标记废弃。

## 行政区与天气

行政区边界按环返回，保留厂商坐标系。每一项 `DistrictInfo.boundaries` 都是 `List<List<LatLng>>`，业务可以逐环创建多边形：

```kotlin
provider.districtSearcher()?.search(
    DistrictSearchRequest(keyword = "海淀区", city = "北京"),
) { result ->
    result.onSuccess { data ->
        data.districts.firstOrNull()?.boundaries?.forEach { ring ->
            mapController?.addPolygon(PolygonOptions(points = ring))
        }
    }
}
```

天气拆成实时与预报两个明确入口：

```kotlin
val request = WeatherRequest(city = "北京", adCode = "110100")
provider.weatherService()?.current(request) { result -> /* WeatherNow */ }
provider.weatherService()?.forecast(request) { result -> /* WeatherForecast */ }
```

百度天气支持 `city`、`adCode` 或原生坐标位置，高德天气原生服务要求 `city` 或 `adCode`。为了让同一请求稳定切换两家，业务应优先同时提供城市名与行政区编码；只提供 `location` 时，高德适配器会返回 `INVALID_PARAM`，不会偷偷做逆地理编码。

## 轨迹记录

从当前 `MapProvider` 创建记录器即可切换百度/高德，记录器拥有内部 `LocationClient` 的生命周期，使用结束后调用 `destroy()`：

```kotlin
val recorder = MapFusion.createTrackRecorder(provider, mapController)
recorder?.start(
    TrackOptions(
        minPointDistanceMeters = 2.0,
        maxAccuracyMeters = 100f,
        drawOnMap = true,
        followLocation = false,
    ),
    object : TrackListener {
        override fun onPointAdded(snapshot: TrackSnapshot) {
            println("${snapshot.points.size} points, ${snapshot.distanceMeters} m")
        }

        override fun onError(error: MapError) {
            // 权限、网络或厂商定位错误
        }
    },
)

recorder?.pause()
recorder?.resume()
val finished = recorder?.stop()
recorder?.destroy()
```

`TrackOptions.locationOptions` 会被强制设为连续定位；`maxAccuracyMeters` 为 0 表示不按精度过滤，`minPointDistanceMeters` 为 0 表示不按位移去重。组件不会申请运行时权限，也不会自动保存轨迹文件；后台长期记录应由宿主自行配合前台服务和持久化策略。

## 坐标系约定

- 百度默认 `BD09`。
- 高德默认 `GCJ02`。
- GPS 原始坐标使用 `WGS84`。
- 所有跨模块坐标都必须显式填写 `LatLng.coordType`；地图、覆盖物、搜索和路线适配器会在边界
  自动转换到当前厂商坐标系，`UNKNOWN` 或非法经纬度直接返回 `INVALID_PARAM`，不会静默使用数值。
- `DefaultCoordinateConverter` 支持 WGS84、GCJ02、BD09 两两转换；境外 WGS84/GCJ02 转换保持数值不变，`UNKNOWN` 会返回统一错误。

建议业务层统一保存 WGS84；需要在业务层缓存、导出或预览转换结果时，可以显式调用转换服务：

```kotlin
val businessPoint = LatLng(39.9087, 116.3975, CoordType.WGS84)
val targetType = when (config.provider) {
    Provider.BAIDU -> CoordType.BD09
    Provider.AMAP -> CoordType.GCJ02
}

when (val result = DefaultCoordinateConverter.convert(businessPoint, targetType)) {
    is MapResult.Success -> mapController?.addMarker(MarkerOptions(position = result.data))
    is MapResult.Failure -> handleMapError(result.error)
}
```

适配器边界的自动转换不会改变业务模型中的 `coordType`，返回的 `MapLocation`、POI 和路线点仍会标注实际坐标系。

## 高级覆盖物

两家适配器均实现以下统一接口：

- `addGroundOverlay`：支持 Asset、资源 ID 或图片字节，并可用中心点尺寸或边界定位。
- `addText`：统一文字、颜色、背景、旋转、对齐、显示状态和层级。
- `addTileOverlay`：业务实现 `MapTileProvider`，返回 PNG/JPEG 等瓦片字节；返回 `null` 表示无瓦片。
- `addHeatMap`：统一带权重点、半径、透明度、显示状态和层级。

```kotlin
mapController?.addText(
    TextOverlayOptions(
        text = "门店热区",
        position = displayPoint,
        fontColor = Color.WHITE,
        backgroundColor = Color.BLUE,
    ),
)

val heatMap = mapController?.addHeatMap(
    HeatMapOptions(
        points = listOf(
            HeatPoint(displayPoint, 1.0),
            HeatPoint(anotherPoint, 0.7),
        ),
        radiusPixels = 30,
        opacity = 0.6f,
    ),
)
```

高德热力图内部采用官方 `HeatmapTileProvider + TileOverlay`，规避部分设备上新版 `HeatMapLayer` 的 native 渲染崩溃；对业务暴露的 `MapHeatMap` 接口不变。TileOverlay 的完整接入入口是 `MapTileProvider`，Demo 因没有实际瓦片服务未内置在线瓦片源。

`TileOverlayOptions` 统一校验 WebMercator XYZ、瓦片尺寸、`minZoom/maxZoom` 和 bounds；
范围外请求不会调用业务 `MapTileProvider`。bounds 会在适配器边界转换到 BD09/GCJ02。
百度和高德对缓存、显隐和层级的原生支持仍不完全一致，调用方应以能力矩阵中的受限项为准。

## 当前真实 SDK 版本

下面版本已于 2026-07-17 对照 Maven Central metadata 核实。升级版本前仍应检查百度/高德官方发布说明和兼容矩阵。

### 百度

`map-baidu/build.gradle.kts` 已启用：

```kotlin
api("com.baidu.lbsyun:BaiduMapSDK_Map:8.1.0")
api("com.baidu.lbsyun:BaiduMapSDK_Location_All:9.6.8")
api("com.baidu.lbsyun:BaiduMapSDK_Search:8.1.0")
```

`BaiduProviderFactory` 已完成地图/定位隐私声明、动态 AK 注入、BD09 坐标设置和 SDK 初始化。
统一 `EmbeddedNavigator` 组合地图、路线和独立定位实例，不需要额外导航 AAR；外部兼容入口
才使用百度地图 URI。厂商官方导航需在独立模块中评估用兼容版本的
`BaiduMapSDK_Map-AllNavi` 替换基础 Map 包，并单独处理语音/TTS；不能把两种地图包重复引入
基础适配器。

### 高德

高德独立的地图、定位、搜索包包含重复基础类，不能随意混用。本项目使用官方兼容组合包：

```kotlin
api("com.amap.api:3dmap-location-search:11.2.000_loc11.2.000_sea9.8.0")
```

`AmapProviderFactory` 已为地图、定位、搜索分别完成隐私声明、动态 Key 注入和初始化。
统一 `EmbeddedNavigator` 不需要额外导航 AAR，外部兼容入口才使用高德地图 URI。需要高德
官方车道级/语音导航时，应在独立可选模块中整体切换到对应版本的
`navi-3dmap-location-search` 组合包，不能与现有组合包同时引入。

### 开放平台与 SHA1

两家平台都需要创建 Android 应用并绑定宿主 App 的包名与签名 SHA1：

```powershell
.\gradlew.bat signingReport
```

把 debug/release 对应变体输出的 SHA1 与 `applicationId` 分别登记到百度、高德开放平台，并使用平台生成的对应 Key。正式发布时不要拿 debug SHA1 的 Key 代替 release Key。

当前 Demo 调试配置：

```text
applicationId: com.mapfusion.demo
debug SHA1: <从当前安装 APK 读取，不提交真实值>
```

## 使用厂商独有功能

通用接口只收敛两家都稳定具备、语义相近的能力。3D 模型、粒子、轨迹纠偏、室内楼层、离线地图等差异较大的功能，通过类型安全的逃生舱获取原生 SDK：

```kotlin
val baiduAccess = provider.rawProvider() as? BaiduNativeAccess
val baiduMap = baiduAccess?.mapOf(mapController)
// baiduMap?.addPrism(...)

val amapAccess = provider.rawProvider() as? AmapNativeAccess
val amap = amapAccess?.mapOf(mapController)
// amap?.addNavigateArrow(...)
```

这样高级页面可以使用厂商全部能力，普通业务页面仍保持可切换。

## 后续扩充方向

- 点聚合；海量点已通过两家原生高性能图层接入。
- 自定义地图样式、室内楼层与离线地图独立可选能力。
- 轨迹纠偏、轨迹持久化与地理围栏服务能力。
- 货车/新能源路线、路线避让区域和更多公交详情。
- 可选的百度/高德完整导航 SDK 模块，避免基础地图包被导航体积拖大。
- 更高精度或可替换的坐标转换策略；核心接口继续保留显式 `coordType`。

完整阶段顺序见 [MapSdk-Development-Plan.md](MapSdk-Development-Plan.md)，异步取消、超时和回调线程迁移见
[ASYNC_CONTRACT.md](ASYNC_CONTRACT.md)。

## Maven 发布

五个库模块均已配置 Release AAR、sources JAR、POM 和 Gradle module metadata。默认坐标为：

```text
com.mapfusion:map-api:0.9.0-SNAPSHOT
com.mapfusion:map-factory:0.9.0-SNAPSHOT
com.mapfusion:map-baidu:0.9.0-SNAPSHOT
com.mapfusion:map-amap:0.9.0-SNAPSHOT
com.mapfusion:map-fusion-full:0.9.0-SNAPSHOT
```

默认发布到工程内 `build/repository`，用于本地消费验证：

```powershell
.\gradlew.bat publishAllPublicationsToMapFusionRepository
```

发布参数清单见 `gradle/publishing.properties.example`。正式仓库发布应设置
`mapFusionRequirePublicationMetadata=true`，此时 publish 任务会强制校验以下真实值：

- 坐标与仓库：`mapFusionGroup`、`mapFusionVersion`、`mapFusionRepositoryUrl`。
- POM：`mapFusionPomUrl`、`mapFusionPomLicenseName/Url`、
  `mapFusionPomDeveloperId/Name/Email`、`mapFusionPomScmConnection/DeveloperConnection/Url`。
- 签名：`mapFusionSignPublications=true`，并提供内存 PGP 密钥和密码。

仓库凭据通过 `MAP_FUSION_REPOSITORY_USERNAME`、`MAP_FUSION_REPOSITORY_PASSWORD` 注入，
签名通过 `MAP_FUSION_SIGNING_KEY`、`MAP_FUSION_SIGNING_PASSWORD` 注入；构建脚本也接受
对应的 `mapFusion*` Gradle 属性。
不要把密钥、密码或仓库凭据写入仓库。

当前工程已成功把五个模块发布到本地 `build/repository`，并核对 POM 的传递依赖（包括百度
运行时 OkHttp、高德运行时 jafama 及聚合包的三项模块依赖）。真实 group、许可证、项目主页、
开发者、SCM 与 PGP 签名身份仍需项目方确定，因此当前 `0.9.0-SNAPSHOT` 可以用于本地/内部
仓库验证，但不能宣称已经满足 Maven Central 正式发布条件。

Demo 还提供 `mapFusionUsePublishedSdk` 开关，用于绕开工程模块依赖并消费刚发布到
`build/repository` 的 `map-fusion-full`。工程内 published-SDK 编译冒烟测试命令为：

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin `
    -PmapFusionUsePublishedSdk=true --refresh-dependencies
```

此外，`consumer-smoke` 是不加入主工程 settings、不允许 `project(...)` 依赖的独立 Android
消费者。它会显式验证聚合 POM/Gradle metadata、五个 Map Fusion 模块、sources JAR、百度
OkHttp、高德 Jafama，并在应用侧不补厂商 keep 规则的情况下完成 Release 资源压缩和 R8：

```powershell
$env:ANDROID_HOME = "C:\path\to\Android\Sdk"
.\gradlew.bat -p consumer-smoke clean :app:verifyPublishedSdk --refresh-dependencies
```

截至 2026-07-27，该独立门禁已通过并生成 65,824,953 字节的 unsigned APK。它证明发布产物
可被外部工程解析、编译和压缩，但不等同于签名 Release 真机、Maven Central 或长稳验收。

宿主从 Maven 仓库接入时，双厂商快速接入只依赖聚合包：

```kotlin
dependencies {
    implementation("com.mapfusion:map-fusion-full:0.9.0-SNAPSHOT")
}
```

单厂商宿主使用 `map-factory + map-baidu` 或 `map-factory + map-amap`，并显式注册对应 Factory。
适配器的公开 `BaiduNativeAccess/AmapNativeAccess` 返回厂商原生类型，因此厂商依赖以 `api`
方式传递，消费者可以编译显式使用逃生舱的代码。

## 构建与验证

本工程使用 Java 17、compileSdk 36、minSdk 21、Gradle 8.13、AGP 8.11.1、Kotlin 2.2.10。

截至 2026-07-27，五个库模块的最新 JVM 报告为 125/125 通过，0 failure、0 error；其中内嵌
导航覆盖真实/模拟模式、推进、到达、暂停恢复和迟到任务屏蔽。`app` 的双厂商参数化 androidTest
已在 OnePlus 9 Pro 上完成 10/10，通过项覆盖百度/高德地图生命周期、WGS84 Marker、原生海量点
与批量替换、真实单次定位，以及步行路线和可绘制几何数据。该结果是当前 Debug
设备契约回归，不替代 release SHA1、minify Release、前后台切换和长时间稳定性验收。

同日 `assembleRelease` 已通过，生成五个 Release AAR 和启用 R8/资源压缩的 unsigned Demo
APK。首次 R8 缺类由百度可选 OkHttp、高德可选 GNSS/jafama 探测触发，现已用定向
`dontwarn` 规则处理；构建仍会报告厂商 jar 的 stack-map 警告，必须继续用 minify Release
真机测试确认 JNI/反射路径。六个模块的最新 Lint 任务均构建成功，报告合计 0 error / 11
warning；现有 warning 为依赖/工具版本提示和 KTX 建议，不应把“构建成功”写成“无 warning”。

```powershell
.\gradlew.bat :map-api:testDebugUnitTest :map-factory:testDebugUnitTest `
    :map-baidu:testDebugUnitTest :map-amap:testDebugUnitTest `
    :map-fusion-full:testDebugUnitTest
.\gradlew.bat :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat assembleRelease
.\gradlew.bat :app:lintDebug
.\gradlew.bat publishAllPublicationsToMapFusionRepository
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin `
    -PmapFusionUsePublishedSdk=true --refresh-dependencies
$env:ANDROID_HOME = "C:\path\to\Android\Sdk"
.\gradlew.bat -p consumer-smoke :app:verifyPublishedSdk --refresh-dependencies
.\gradlew.bat apiDump
.\gradlew.bat apiCheck
```

BCV 已覆盖五个发布模块，本轮 `apiDump` 和独立 `apiCheck` 均成功，首次公共 ABI 基线已建立；
后续任何公开 API 变更都必须重新审查并通过 `apiCheck`。独立消费者 Release/R8 已验证发布
产物携带的规则能够完成压缩；百度/高德 Consumer R8 仍对已知可选缺类使用定向 `dontwarn`，
并采用保守的厂商命名空间 keep 保护 JNI/反射。正式发布前还需对照所用 SDK 版本的官方规则和
minify Release 真机结果收紧，不能把构建通过等同于运行时 R8 兼容验证。

本机执行 Gradle 时不要添加 `--offline`，否则可能因 AndroidX 缓存版本路径不匹配而解析失败。
