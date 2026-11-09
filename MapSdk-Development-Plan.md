# Map Fusion 生产交付计划

## 1. 项目定位

Map Fusion 是 Android/Kotlin 地图抽象 SDK，以统一业务接口适配百度地图和高德地图。
当前真实模块为：

```text
                         ┌──> map-factory ──> map-api
app ──> map-fusion-full ─┼──> map-baidu ────> map-api
                         └──> map-amap ─────> map-api

单厂商最小组合: map-factory + map-baidu 或 map-amap
官方导航组合: map-baidu-navi 或 map-amap-navi（已包含 factory 与对应厂商适配器）
```

- `map-api`：厂商无关的能力接口、数据模型、异步契约和 Provider SPI。
- `map-factory`：注册表、`MapFusion`、`MapFusionSession` 与通用组合能力。
- `map-baidu` / `map-amap`：只依赖 `map-api` 的真实 SDK 适配器。
- `map-baidu-navi` / `map-amap-navi`：替换对应基础地图依赖的官方 Navi 可选组合。
- `map-fusion-full`：依赖 factory 和两家适配器的一步接入聚合包。
- `app`：宿主权限、隐私、切换和能力演示，不属于发布 SDK。

能力范围与限制只以 `CAPABILITY_MATRIX.md` 为准。

2026-11-06 审计与 2026-11-09 清理/后续实施结果见 [项目梳理报告](docs/PROJECT_AUDIT.md)，
状态层与资源所有权见 [Demo 架构](docs/DEMO_ARCHITECTURE.md)。下文既有数字按实际日期区分，不表示所有阶段已闭环。

2026-11-06 起按 [官方 API 覆盖计划](docs/OFFICIAL_API_COVERAGE_PLAN.md) 扩大封装范围，
以实际锁定版本的逐签名账本追踪缺口。P0 修正 Demo 身份；P1 补齐室内楼层与六种原生检索工厂；
后续 P2-P8 分批覆盖定位/围栏、地图进阶、检索路线、导航和轨迹。当前不能宣称全量覆盖。

## 2. 设计红线

1. 业务 API 不出现百度/高德类型；厂商独有能力只能通过显式 `rawProvider()` 逃生舱使用。
2. 切换厂商只修改 `provider + apiKey`，同一坐标、错误、线程和生命周期契约保持一致。
3. 能力继续按小接口拆分；不为表面一致而静默忽略参数、伪造坐标或伪造支持。
4. 原生对象必须有清晰所有权，`remove/destroy/cancel/close` 均幂等并释放内部引用。
5. 未取得用户明确隐私同意时，不调用任何厂商初始化 API；撤回时先停业务处理再同步厂商状态。
6. Demo 使用的通用功能必须先成为公开 SDK API；权限、隐私弹窗和业务 UI 明确保留在宿主。
7. 轻量 App 内路线引导与厂商官方导航是两种能力，不能用同一个名称模糊产品边界。
8. 发布版本必须通过单测、Lint、ABI 检查、Release/R8、全部发布模块、独立消费和真机门禁。

## 3. 当前版本

开发坐标：`com.mapfusion:*:0.9.0-SNAPSHOT`。

已具备百度/高德真实地图、定位、覆盖物、搜索、路线、行政区、天气、轨迹、坐标转换、
Provider 切换、双厂商聚合包以及支持真实定位/路线模拟两种模式的 App 内轻量路线引导。SDK 已提供路线组合覆盖物、
自定义起终点图标、可定制实时定位展示、视野适配、异步取消/超时、`RequestScope`、
隐私同意/撤回 SPI、原生海量点和 Session 托管。

历史记录：2026-08-13 JVM 报告为 142/142；当时参数化 androidTest 已在 OnePlus 9 Pro
完成 10/10，覆盖双厂商地图生命周期、Marker、原生海量点、真实定位和步行路线。
历史记录：2026-11-06 七模块 Debug 报告 144/144，三 Demo 编译/Lint、maps androidTest 编译和七模块 apiCheck 通过；当轮未复验 connected、Release/R8 或发布消费。
2026-11-09 后续实施：两页 MVVM 状态基础与问题修复落地，三 Demo Debug 单测各 38/38、Lint 各 0 error / 8 warning；maps connected 12/12、三 Demo Release/R8 构建、七模块 Debug 门禁及 apiCheck 通过。只验收上述检查，未复跑 SDK Release 单测、minify 真机或发布消费。
Release Maven、POM 元数据校验、可选 PGP 签名、Consumer R8 入口和 BCV 已配置，七模块
ABI 基线已由 `apiDump/apiCheck` 建立。原五库与 Demo Release 已通过，两个 Navi Release AAR
也已单独通过；Amap Navi 与 Baidu Navi Demo 的 minify Release/R8 均已通过并生成 unsigned APK，本地原五模块
publish 及工程内 published-SDK 消费编译已通过。2026-07-27 又由独立
`consumer-smoke` 工程验证了聚合 POM、sources、传递依赖和 Release/R8。厂商可选缺类已有逐类
定向 R8 规则，但正式发布身份、CI、厂商 stack-map 警告评估和真机长稳尚未闭环。

当前 `EmbeddedNavigator` 是统一路线规划、设备连续定位和本地路线模拟组合出的轻量引导：
`REAL` 由真实设备位置推进，`SIMULATED` 按路线、速度和间隔生成位置。它不跳转外部 App，
但两种模式都不是百度/高德官方 Navi 引擎，不包含官方语音、车道级引导、路口放大图、电子眼和限速数据。
官方 Navi 可选模块已建立独立契约、映射两家真实引擎并接入独立 Demo 变体，但授权、前台服务和真机长稳尚未闭环，
因此当前快照仍不能宣称为生产稳定 SDK，也不能用“完整内置导航”描述现状。

## 4. 交付阶段

### Stage A：P0 契约和资源安全

- [x] 在适配器边界统一 WGS84/GCJ02/BD09 输入，拒绝 UNKNOWN 和非法坐标。
- [x] 修正境外坐标转换语义并覆盖边界测试。
- [x] 覆盖物删除同步注销厂商对象、句柄表和点击命中表。
- [x] 隐私同意默认 false，未明确同意时拒绝初始化。
- [x] 增加 `MapProviderFactory.updatePrivacyConsent()`，Demo 撤回时先销毁 Session 再同步 false。
- [ ] 继续扩大百度/高德共享 Provider 契约：能力差异、销毁竞态、错误码、UI 降级和长期资源回收。
- [x] 接入官方地图投影、可视区域、底图语言、审图/版权信息和路况配色，并扩展两家 `NativeAccess` 常用原生检索工厂。

完成条件：错误坐标不静默偏移，重复删除/销毁不泄漏，未同意隐私无法触达原生 SDK，
同一套共享契约能约束两家实现。

### Stage B：统一路线展示与 App 内轻量导航

- [x] `MapController.addRoute()` 一次绘制路线与起终点，组合句柄幂等移除。
- [x] `RouteMarkerOptions` 支持 Default/Asset/Resource/Bytes 自定义起终点图标。
- [x] 路线规划与导航复用 `RouteOverlayOptions`，Demo 不再私有实现路线覆盖物协议。
- [x] `MapController.fitPoints()` 和 `LatLngBounds.fromPoints()` 统一视野适配。
- [x] App 内导航支持真实连续定位与沿路线模拟推进，并提供跟随、步骤/剩余距离、到达、偏航重算、暂停/继续/停止。
- [x] 模拟模式支持速度和刷新间隔配置，不申请位置权限；进度明确返回 `REAL/SIMULATED` 数据来源。
- [x] 导航使用独立 LocationClient/RoutePlanner，并在坐标归一后计算进度和偏航。
- [x] 导航与轨迹事件默认主线程、可配置 Executor，并在会话内串行派发。

完成条件：轻量路线引导不跳转第三方 App，宿主只用公开 API 完成 Demo 同等流程，且文档
明确它不是厂商官方车道级导航。

### Stage B2：开箱即用的地图定位展示

- [x] 增加 `LocationDisplay`，组合独立 `LocationClient + MapController`。
- [x] 统一定制位置图标、锚点、透明度、平贴和航向旋转，以及动态精度圈。
- [x] 支持 NONE/FIRST_FIX/CONTINUOUS/COURSE_UP 相机跟随和最大精度过滤。
- [x] 支持暂停、继续、停止、运行中换样式、前后台自动暂停恢复及 Session 托管。
- [x] `MapOverlay.isRemoved` 让组合组件在业务 `clearOverlays()` 后自动重建自有覆盖物。
- [x] Demo 首次进入和切 Provider 改用公开 `session.locationDisplay`，不再手写定位 Marker。

完成条件：宿主只提供定位样式和事件监听即可获得双厂商一致的当前位置展示，不管理原生
定位图层、Marker、精度圈或相机跟随。

### Stage B3：厂商原生海量点

- [x] 增加 `MultiPointItem`、`MultiPointOverlayOptions` 和 `MapMultiPointOverlay` 统一契约。
- [x] 百度使用原生 `MultiPointOption/MultiPoint`，高德使用原生 `MultiPointOverlayOptions/MultiPointOverlay`。
- [x] 在适配器边界转换 WGS84/GCJ02/BD09，拒绝 UNKNOWN、非法坐标、空数据和重复 id。
- [x] 支持统一图标、锚点、批量替换、显隐、点击开关、业务 tag 及幂等移除。
- [x] 点击监听返回所属统一图层和统一业务点；`clearOverlays()` 同步注销句柄与点击映射。
- [x] Demo 一次提交 625 个点且只调用公开 API；双厂商真机契约覆盖原生类型和数据替换。

完成条件：大量同图标点位由两家原生高性能图层渲染，不退化成普通 Marker 循环；高德缺失的
zIndex 不进入统一契约，不以无效字段伪造支持。

### Stage C：异步、错误与请求所有权

- [x] 按 `ASYNC_CONTRACT.md` 增加 `RequestHandle`、单请求取消、超时和可配置回调 Executor。
- [x] 增加 `CANCELLED`、`TIMEOUT`，统一同步异常和厂商错误映射。
- [x] 一次性请求至多一个终态，能力销毁后不让迟到回调穿透。
- [x] `RequestScope` 支持同 key 替换、按 key/全部取消和页面关闭。
- [x] 为完成、取消、超时、销毁及导航/轨迹事件竞态编写确定性 JVM 测试。
- [ ] 真机补齐主动取消、超时、切 Provider、前后台和进程重建竞态。

完成条件：调用方能精确取消请求，所有回调线程可预测，切图和销毁后不更新旧页面。

### Stage D：发布与质量门禁

- [x] 五个库模块配置 Release AAR、sources JAR、POM 和 Gradle metadata。
- [x] 新增 `map-fusion-full`，同时保留单厂商最小组合。
- [x] 五模块包含 Consumer R8 入口；Demo Release 启用 R8 和资源压缩。
- [x] `assembleRelease` 通过，生成五个 Release AAR 和压缩后的 unsigned Demo APK。
- [x] 为百度可选 OkHttp、Gson 与 Navi 宿主/AR/动态 UI 缺类，高德可选 GNSS/jafama 探测增加逐类定向规则，消除 Release 缺类失败。
- [x] 配置 BCV `apiCheck/apiDump`，排除 Demo。
- [x] 增加双厂商参数化 androidTest：权限、定位、路线、地图生命周期和覆盖物。
- [x] 配置 POM 元数据校验和内存 PGP 签名，提供 `gradle/publishing.properties.example`。
- [x] 公共 API 评审后生成、审查并通过五模块首次 ABI 基线（`apiDump` + `apiCheck`）。
- [ ] 在 ABI 固化前决定 `MapConfig.extras` 的类型化扩展策略，禁止依赖未文档化字符串 key。
- [x] 运行五模块本地 publish，核对 AAR、sources、POM、module metadata 和主要传递依赖。
- [x] 通过 `mapFusionUsePublishedSdk=true` 让 Demo 消费本地 Maven 产物，Debug 与 androidTest 编译成功。
- [x] 建立独立 `consumer-smoke`，验证 POM/Gradle metadata、sources、传递依赖和 Release/R8。
- [ ] 评估剩余厂商 jar stack-map 警告，并对照官方规则和 minify Release 真机结果收紧保守 keep。
- [x] 在连接设备执行当前双厂商 connected 契约套件，10/10 通过（含原生海量点批量替换）。
- [ ] 在真实 Key 和 release SHA1 环境执行 connected 套件、反复切图、旋转、前后台和长稳测试。
- [x] Navi 两模块 Lint 构建通过，最新报告合计 0 error / 2 warning，均为百度兼容版本锁定导致的依赖更新提示。
- [ ] 建立泄漏、ANR、崩溃和包体积基线。
- [x] 建立 Git 仓库与初始版本历史。
- [ ] 建立 CI、版本标签、CHANGELOG、受保护分支和可审计发布任务。
- [ ] 由项目方确定正式 group、许可证、主页、开发者、SCM、Central 账户及 PGP 身份。

完成条件：干净环境可复现并消费签名 Release 产物，CI 阻止测试、Lint、ABI、R8 或真机
回归进入发布分支。

### Stage E：厂商官方 App 内导航可选模块

这是用户要求“导航留在 App 内且可商用”的下一项核心工作，优先级高于继续增加普通地图功能。

- [x] 锁定兼容组合包：百度 Map-AllNavi 8.1.0（base/bwnavi 8.1.0 + carnavi 8.0.1）与 Search 8.2.0（排除 base 8.2）；高德 Navi/3DMap/Location/Search 11.2.000 组合包。正式商用授权仍由项目方验收。
- [x] 设计独立 `map-baidu-navi` / `map-amap-navi`，避免基础适配器和单厂商包被导航体积拖大。
- [x] 通过依赖排除与组合包替换解决重复类/原生库冲突，并核对 Debug 运行时依赖树。
- [x] 在 `map-api` 增加官方导航能力查询、事件和会话契约，不改变现有轻量 `EmbeddedNavigator` 语义。
- [x] 将路线选择、模拟/真实模式、控制、状态、剩余信息和错误模型映射到两家官方 Navi 引擎，并明确能力差异。
- [x] 对语音、车道线、路口图、电子眼、限速和后台定位逐项标注支持范围；未完成前台服务时不声明后台导航。
- [x] 统一分段路况、途经点到达/剩余进度、服务区、高速出口/方向/收费站、厂商路线提醒、已行驶距离和偏航重算完成事件；高德额外映射 GPS/弱信号、区间测速及剩余红绿灯，未支持项通过能力集合显式区分。
- [x] 继续封装两家官方运行时控制：主动重算、路线全览、恢复跟随、语音开关、昼夜模式；高德额外支持模拟导航运行中调速，并提供会话绑定的原生 Navi 访问口。
- [x] 通过 `OfficialNavigator.lastError` 保存并读取监听器注册前的初始化失败；Demo 同步显示完整厂商鉴权诊断。高德待开始时隐藏空路线面板，导航/暂停状态恢复官方布局。
- [ ] 完成 TTS/语音资源、隐私条款、运行时权限、前台服务、许可证和数据合规审查。
- [x] Demo 通过统一 API 在独立页面承载官方导航 UI，不启动外部地图 App；按 `maps` / `amapNavi` / `baiduNavi` 变体隔离冲突的原生运行库。
- [ ] 使用真实路线完成双厂商真机导航、偏航、来电/锁屏、弱网、进程恢复和长时间稳定性验收。

完成条件：只有官方引擎能力、授权、隐私、R8、体积和真机验收全部闭环后，才能对外声明
“完整 App 内导航”。外部 `Navigator` URI 入口仅作为兼容能力保留。

### Stage F：性能与示例工程

- [x] Demo 首页改为分类工具面板，并按当前分类动态收缩操作行和结果条，保留更多地图主视图空间。
- [x] 地图 UI/手势扩展支持运行时能力查询与明确失败：新增 `supportedUiFeatures()` / `applyUiOptions()`，覆盖两家共有控制项及厂商特有手势。
- [x] 两页建立 ViewModel/不可变 UiState、SavedStateHandle 和生命周期内 StateFlow 渲染；共享隐私存储、单次请求仓库、完整设置恢复与 Demo 覆盖物所有权已测试。地图 View、权限和 Activity 绑定 Session 仍由宿主管理。
- [ ] 地图、覆盖物、搜索、路线导航和轨迹的功能 UI/用例继续拆分；真实进程死亡恢复、长期状态/资源稳定性仍需验证，不把 MVVM 状态基础计作全页面重构完成。
- [x] README 提供 `map-fusion-full` 与单厂商最小组合接入说明。
- [ ] Demo/正式应用采用 App Bundle 或 ABI Split，记录两家 SDK 与 Navi 模块体积预算。
- [ ] 建立 Marker/MultiPoint/Polyline/Tile/HeatMap 压测、内存泄漏和长时间前后台稳定性基线。

完成条件：单厂商宿主不携带另一厂商依赖，Demo 不承担 SDK 内部业务逻辑，性能指标可回归。

### Stage G：可选高级能力

- [x] 两家厂商原生海量点渲染。
- [ ] 点聚合；若原生能力不对称，统一层使用明确标注的公共聚合实现。
- [ ] 轨迹持久化、恢复、纠偏和动画回放。
- [ ] 地理围栏：尚无统一能力接口。
- [ ] 室内楼层：统一 API 与双厂商适配已有实现，实际建筑、切层和退出事件待真机验收。
- [x] 官方自定义地图样式完成统一来源能力查询与配置提交；支持本地样式/样式 ID，高德额外支持纹理文件。
- [x] 地图交互补齐底图 POI、Marker 拖拽阶段和不可变触摸事件快照。
- [x] 高德离线地图通过 `AmapNativeAccess` 提供官方管理器工厂；百度 8.2 无公开对应 API，明确保持不支持。
- [ ] 货车/新能源路线、路线避让区域和更多公交详情。

高级能力只在 Stage A-D 的发布门禁稳定、Stage E 的导航产品边界确定后进入主版本计划。

## 5. 版本规则

- `0.x`：允许经过评审的破坏性 API 调整；每次调整必须更新 ABI 基线和迁移说明。
- `1.0.0`：首次生产稳定版，至少 Stage A-D 全部完成；若承诺完整内置导航，Stage E 也必须完成。
- `1.x`：只做二进制兼容新增和修复；废弃 API 至少保留一个次版本周期。
- `2.0.0`：仅用于无法兼容的统一契约调整。

正式版本不得使用 `SNAPSHOT`，不得包含 Demo Key，不得依赖 debug 签名或 debug SHA1，
不得把轻量路线引导宣传成厂商官方导航。

## 6. 验证命令

```powershell
.\gradlew.bat :map-api:testDebugUnitTest :map-factory:testDebugUnitTest `
    :map-baidu:testDebugUnitTest :map-amap:testDebugUnitTest `
    :map-fusion-full:testDebugUnitTest :map-amap-navi:testDebugUnitTest `
    :map-baidu-navi:testDebugUnitTest
.\gradlew.bat :app:compileMapsDebugKotlin :app:compileAmapNaviDebugKotlin `
    :app:compileBaiduNaviDebugKotlin :app:compileMapsDebugAndroidTestKotlin
.\gradlew.bat :app:connectedMapsDebugAndroidTest
.\gradlew.bat assembleRelease
.\gradlew.bat :app:lintMapsDebug :app:lintAmapNaviDebug :app:lintBaiduNaviDebug
.\gradlew.bat apiCheck
# 以下仅在正式发布身份已配置、明确执行发布验证时运行：
.\gradlew.bat validatePublicationMetadata
.\gradlew.bat publishAllPublicationsToMapFusionRepository
.\gradlew.bat :app:compileMapsDebugKotlin :app:compileMapsDebugAndroidTestKotlin `
    -PmapFusionUsePublishedSdk=true --refresh-dependencies
$env:ANDROID_HOME = "C:\path\to\Android\Sdk"
.\gradlew.bat -p consumer-smoke :app:verifyPublishedSdk --refresh-dependencies
python tools/check_sensitive.py
python tools/check_sensitive.py --staged
```

`apiDump` 只在公共 API 评审后生成基线；生成结果必须人工审查，不可机械提交。
正式 publish 使用 `mapFusionRequirePublicationMetadata=true`，真实 POM 元数据和 PGP 密钥由项目方/CI 注入。
Gradle 命令不得添加 `--offline`。当前 10/10 是 Debug 设备契约回归；正式发布前仍必须在登记
release SHA1、注入真实 Key 的设备环境执行 minify、前后台、进程恢复和长稳真机套件。
