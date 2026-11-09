# Map Fusion 能力矩阵

本文件是能力范围和完成状态的唯一事实来源。README 负责接入说明，
`MapSdk-Development-Plan.md` 只描述后续交付顺序，不复制另一套模块名或完成度。

文档更新时间：2026-11-09；各构建、测试及设备结果以对应条目的实际日期为准。

全量 API 尚未覆盖。逐签名审计工具与缺口批次见 [官方 API 覆盖计划](docs/OFFICIAL_API_COVERAGE_PLAN.md)。
原生工厂、统一封装和真机验证分开记录，不能将“可拿到原生对象”计作全量封装完成。

状态说明：`完成` 表示统一入口和当前两家真实 SDK 实现均已落地；`受限` 表示入口可用，
但厂商支持范围或参数语义不同；`已配置` 表示工程配置存在但发布验证尚未闭环；
`计划` 表示尚不能作为对外能力承诺；`预留` 表示 API 字段存在但当前没有稳定实现语义。

## 地图与覆盖物

| 能力 | 百度 | 高德 | 统一契约状态 | 备注 |
| --- | --- | --- | --- | --- |
| MapView 与生命周期 | 完成 | 完成 | 完成 | `MapFusionSession` 托管创建和幂等释放 |
| 相机移动、动画、边界、缩放范围 | 完成 | 完成 | 完成 | 新增 `fitPoints()` 和 `LatLngBounds.fromPoints()`；View/生命周期调用要求主线程 |
| 屏幕坐标投影与当前可视区域 | 完成 | 完成 | 完成 | `screenPointOf()`、`coordinateAt()`、`visibleRegion()`；输出明确标注 BD09 / GCJ02 |
| 坐标边界转换 | 完成 | 完成 | 完成 | 输入按 `coordType` 转到 BD09/GCJ02，UNKNOWN 和非法数值明确拒绝 |
| Marker | 完成 | 完成 | 完成 | 图标、标题、snippet、旋转、透明度、平贴、锚点、显隐、层级、tag、点击及移除状态 |
| 原生海量点 | 完成 | 完成 | 完成 | 百度 `MultiPoint` / 高德 `MultiPointOverlay`；统一图标、锚点、批量替换、显隐、点击、业务 id/tag 和幂等移除，不伪造高德缺失的 zIndex |
| 路线与自定义起终点图标 | 完成 | 完成 | 完成 | `addRoute()` + `RouteOverlayOptions`，支持 Default/Asset/Resource/Bytes |
| Polyline | 完成 | 完成 | 完成 | 普通线、虚线、颜色、宽度、显隐、层级、tag |
| Polygon / Circle | 完成 | 完成 | 完成 | 删除幂等，并同步注销点击命中与内部句柄 |
| GroundOverlay / Text | 完成 | 完成 | 完成 | 统一图片源、位置/边界和基础样式 |
| TileOverlay | 受限 | 受限 | 受限 | XYZ、瓦片尺寸、bounds、min/max zoom 已统一过滤；缓存、显隐和层级仍受原生 API 差异约束 |
| HeatMap | 完成 | 受限 | 受限 | 高德使用官方瓦片热力图实现以规避部分设备 native 崩溃 |
| 地图截图与交互事件 | 完成 | 完成 | 完成 | 地图、底图 POI、Marker 点击/拖拽、地图触摸、海量点、覆盖物和相机事件；触摸回调使用不可变快照，截图返回 PNG |
| 官方自定义地图样式 | 完成 | 完成 | 受限 | `supportedCustomStyleFeatures()` / `applyCustomStyle()`；两家支持本地样式和样式 ID，高德额外支持本地纹理；成功仅表示配置被 SDK 接受 |
| 离线地图管理 | 不支持 | 原生扩展完成 | 受限 | 高德通过 `AmapNativeAccess.newOfflineMapManager()` 暴露官方完整下载管理器并由调用方负责销毁；百度 8.2 公共包无对应公开 API，因此不伪造统一实现 |
| 地图类型 | 完成 | 完成 | 完成 | 百度 NORMAL/SATELLITE/NONE；高德 NORMAL/SATELLITE/NIGHT/NAVIGATION；不支持项明确返回 `UNSUPPORTED` |
| UI 与手势设置 | 受限 | 完成 | 受限 | 已提供 `supportedUiFeatures()` / `applyUiOptions()`；两家统一基础控件和手势，高德额外支持室内开关及缩放中心，百度额外支持双击/双指缩放、甩动和惯性；百度定位按钮仍明确标为不支持 |
| 交通、建筑、室内、底图 POI | 受限 | 完成 | 受限 | 统一开关已实现，最终效果以厂商、城市和底图数据为准 |
| 室内建筑与楼层控制 | 已实现待真机 | 已实现待真机 | 受限 | 新增 `activeIndoorBuilding()`、`switchIndoorFloor()`、室内事件和支持查询；使用厂商楼层 ID，成功只表示切层请求接受；实际建筑数据和切层未验收 |
| 官方底图语言 | 完成 | 完成 | 完成 | 两家统一支持中文/英文；高德额外声明跟随系统，调用前可查询支持集合 |
| 官方路况配色 | 完成 | 完成 | 受限 | 统一四级颜色；高德额外的极度畅通级映射为畅通色 |
| 官方审图号与版权信息 | 完成 | 完成 | 完成 | 统一 `MapLegalInfo`，无对应数据的厂商字段返回 null |

## 服务能力

| 能力 | 百度 | 高德 | 统一契约状态 | 备注 |
| --- | --- | --- | --- | --- |
| 单次/连续定位 | 完成 | 完成 | 完成 | 权限由宿主申请，首次创建及切换 Provider 后由 Demo 重新定位 |
| 可定制定位展示 | 完成 | 完成 | 完成 | `LocationDisplay` 统一位置图标、航向、精度圈、精度过滤、首次/持续/COURSE_UP 跟随及生命周期 |
| 正/逆地理编码 | 完成 | 完成 | 完成 | 返回坐标标注实际厂商坐标系，无坐标结果不伪造 `(0,0)` |
| POI、详情、输入提示 | 完成 | 完成 | 完成 | 关键字、周边、分页和排序按统一模型返回 |
| 驾车路线 | 完成 | 完成 | 受限 | 部分车牌、避轮渡和偏好参数存在厂商差异 |
| 步行路线 | 完成 | 完成 | 完成 | 统一 `TravelMode.WALKING` |
| 自行车路线 | 完成 | 完成 | 完成 | `RIDING` 仅保留兼容，业务使用 `BICYCLE` |
| 电动自行车路线 | 完成 | 不支持 | 受限 | 高德明确返回 `UNSUPPORTED`，不得静默降级 |
| 公交路线 | 完成 | 完成 | 受限 | 必须提供城市，跨城和详情能力依厂商而异 |
| App 内轻量路线引导 | 完成 | 完成 | 受限 | `REAL` 由设备连续定位推进，`SIMULATED` 沿统一规划路线推进；支持跟随、步骤/进度、到达和偏航重算，不含官方导航资源 |
| 厂商完整原生导航 SDK | 受限 | 受限 | 受限 | `map-baidu-navi` / `map-amap-navi` 已映射真实/模拟、官方 UI、路线选择、详细进度、分段路况、服务区、途经点进度、高速出口/方向/收费站、路线提醒、语音、车道线、路口图、电子眼、限速及偏航重算完成；高德另提供 GPS/弱信号、区间测速和剩余红绿灯事件。百度真机页面可启动但路线规划仍受控制台 SHA1/包名鉴权限制，前台服务和双厂商长稳未验收 |
| 外部厂商页面导航 | 完成 | 完成 | 受限 | 兼容旧业务的 URI 入口；成功只表示 Intent 被系统接受 |
| 行政区与边界 | 受限 | 完成 | 受限 | 百度行政层级信息尚未完全归一 |
| 实时天气与预报 | 完成 | 完成 | 受限 | 高德查询需要 city/adCode，不接受纯坐标请求 |
| 轨迹记录 | 完成 | 完成 | 完成 | 通用组合能力；持久化、恢复、纠偏和回放仍为计划 |

`EmbeddedNavigator` 是 `map-factory` 基于 `MapController + LocationClient + RoutePlanner`
组合出的轻量能力。`REAL` 使用真实设备位置，`SIMULATED` 按规划路线、速度和刷新间隔生成位置；
这两个模式都不是百度/高德官方 Navi 的真实/模拟导航。它不跳转外部 App，但“留在 App 内”不等于
已经接入厂商完整导航 SDK；不得把当前实现宣传为车道级 turn-by-turn 商用导航，也不得暗示
已具备官方语音、车道线、路口放大图、电子眼或限速数据。

`OfficialNavigator` 则由可选 Navi 模块直接驱动厂商官方引擎。百度模块当前只声明驾车；高德模块
声明驾车、步行、自行车和电动自行车。两家均支持官方真实/模拟模式；只有高德声明可设置模拟速度。
两家均声明途经点到达/剩余进度、服务区、高速出口/方向/收费站、厂商路线提醒和重算完成事件；
GPS 状态、区间测速与剩余红绿灯详细事件只由当前高德组合包声明。
两家均声明主动重算、路线全览/恢复跟随、语音控制和昼夜模式；只有高德声明导航运行中的模拟速度
调整。未统一的厂商管理器通过对应 Navi 模块的 `nativeAccess()` 访问，仍绑定当前导航会话。
两家会话均通过 `lastError` 保留最近一次进入 `FAILED` 的错误，初始化失败即使早于 `start()`
监听器注册也可读取；包含厂商原始错误码和消息，不需要先发起路线请求才能诊断鉴权问题。
`BACKGROUND_GUIDANCE` 在宿主完成前台服务与合规闭环前不会出现在能力集合中。

## SDK 与示例工程

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 单厂商组合 | 完成 | `map-factory + map-baidu` 或 `map-factory + map-amap`，宿主显式注册 Factory |
| 双厂商聚合包 | 完成 | `map-fusion-full` 自动注册两家，提供 `create/openSession/updatePrivacyConsent` |
| Demo 公共能力边界 | 完成 | 路线绘制、视野适配、定制定位展示、原生海量点、搜索、导航、轨迹等调用公开 SDK API；权限和隐私 UI 保持宿主职责 |
| Demo 首页工具面板 | 完成 | 地图保持主视图；公开能力按常用、导航、查询服务、覆盖物和轨迹五类切换，分类与布局状态不进入 SDK |
| Demo 动态工具高度 | 完成 | 常用/轨迹一行、查询两行、覆盖物三行；结果条按内容测量，释放的空间自动归还地图 |
| 厂商原生能力入口 | 完成 | `NativeAccess` 可获取官方地图/View，并统一代建常用行政区、天气、输入提示、公交线路和 V2 路线等原生对象；高德额外提供官方离线地图管理器工厂 |
| 新增原生检索工厂 | 已实现待真机 | 高德 POI V2/公交站点/距离查询，百度建筑/推荐上车点/分享链接；只代建原生对象，尚未统一其请求响应，也未逐请求真机验收 |
| 安装包身份诊断 | 已实现待真机 | `MapSdkIdentity.read()` 返回实际 package、SHA1、百度 MCODE；默认 Demo 不再加 applicationId 后缀，并排模式为显式选项 |
| 官方 Navi 可选模块 | 受限 | 单模块一步注册、隐私同步、地图会话和官方导航会话；百度 Map-AllNavi 8.1.0 与 base/bwnavi 8.1.0、carnavi 8.0.1 对齐，Search 8.2.0 排除 base 8.2 以保持 native ABI，Navi minSdk 23，并移除非核心高敏权限的自动合并 |
| 官方 Navi Demo | 受限 | `amapNavi` / `baiduNavi` 独立变体通过统一 API 承载厂商 View；支持真实/模拟、能力驱动的出行方式、模拟速度、路线切换、暂停/继续/停止、主动重算、路线全览、恢复跟随、语音和昼夜控制，并展示途经点、高速引导、路线提醒、服务区及路况事件。高德待开始时隐藏空路线指引，导航/暂停时恢复官方布局；初始化轮询通过 `lastError` 展示错误。2026-11-06 真机复验：高德地图无遮挡、模拟速度行可动态显示，路线请求失败；百度启动即显示 status=230 / APP Mcode码校验失败。两家实际路线与导航中的布局切换仍未验收 |
| Navi 原生库隔离 | 完成 | 两家 `libc++_shared.so` 不在同一 APK 中做不安全覆盖；`maps`、`amapNavi`、`baiduNavi` 三变体分别打包 |
| 隐私同意/撤回 | 完成 | 默认不同意；两页共用 DemoConsentStore，保存失败不伪造成功；返回页面/启动导航时复查，撤回时先取消请求和销毁 Session，再同步厂商 false |
| 请求托管 | 完成 | SDK RequestScope 管理句柄；DemoRequestRepository 补同键监听失效、会话代次和关闭后的迟到回调隔离 |
| `MapConfig.extras` | 预留 | 当前两家适配器未消费稳定 key；1.0 前需决定类型化扩展契约或继续仅作保留字段 |
| Demo MVVM 状态基础 | 已实现并验证 | 两页使用不可变 UiState、ViewModel、SavedStateHandle 与 STARTED 内 StateFlow 收集；共享隐私、请求隔离、设置恢复和覆盖物所有权见 [Demo 架构](docs/DEMO_ARCHITECTURE.md) |
| Demo 页面/用例进一步拆分 | 未完成 | Activity 仍管理视图、权限和厂商资源宿主；功能 UI/用例继续拆分及性能基线未闭环，不把状态层基础计作所有页面拆分完成 |

## 工程化门槛

| 项目 | 状态 | 交付要求或当前结果 |
| --- | --- | --- |
| SDK JVM 单测 | 已通过 | 2026-11-09 七模块 Debug 门禁通过，报告合计 144 项，0 failure/error/skipped；Gradle 可复用 UP-TO-DATE，未复跑 Release 单测 |
| Demo JVM 单测 | 已通过 | 2026-11-09 maps/amapNavi/baiduNavi Debug 各 38/38，覆盖两页状态、隐私、请求隔离、设置应用和句柄所有权；同一套用例不计作 114 项独立场景 |
| 真机 androidTest | 本轮已通过 | 2026-11-09 maps connected 12/12：既有双厂商契约 10 项及 Activity 重建/隐私恢复 2 项；新增用例不初始化厂商并恢复原隐私决定。官方 Navi 真实路线、进程死亡及长稳待验收 |
| 异步取消/超时/线程 | 完成 | `RequestHandle`、单请求取消、超时、默认主线程、自定义 Executor；导航/轨迹事件串行，见 `ASYNC_CONTRACT.md` |
| Release/R8 构建 | 三 Demo 已通过 | 2026-11-09 maps/amapNavi/baiduNavi 均执行 minify Release/R8 并生成 APK；只验收构建，未安装 minify APK 或验收真实导航、正式签名与发布消费 |
| Release Maven 配置 | 部分完成 | 原五模块已通过独立消费验证；两个 Navi 模块已纳入统一发布配置但本阶段按要求未执行 Maven 发布/消费验证 |
| POM 元数据门禁 | 已配置 | `mapFusionRequirePublicationMetadata=true` 会强制校验；真实 group/license/developer/SCM/签名身份待项目方提供 |
| Consumer R8 | 已通过构建、运行受限 | 独立消费者不补应用规则仍完成 Release/R8；厂商命名空间仍保守 keep，需用 minify 真机结果和官方规则收紧 |
| ABI 兼容检查 | 已通过 | 2026-11-09 七个发布模块 apiCheck 全部成功；本轮未执行 apiDump，未改公共 API 或 ABI 基线 |
| Lint | 已通过但有警告 | 2026-11-09 maps/amapNavi/baiduNavi Debug 各 0 error / 8 warning；警告未全部清零 |
| 版本库与 CI | 部分完成 | 已有 Git 版本历史；仍需受保护分支和自动校验工作流 |
| Maven Central 发布 | 未完成 | 脚本支持元数据与签名，但真实身份信息、签名密钥、Central 账户和发布验证均未闭环 |
| 提交格式与敏感检查 | 本地已启用 | scoped 半角 type(scope): 描述；pre-commit 扫整个 index，commit-msg 检查格式和正文敏感内容；27 项工具测试通过，包含真实临时仓库双 hook 拦截，不替代 CI 和历史安全审计 |

2026-11-09 后续实施与复验见 [项目梳理报告第 9 节](docs/PROJECT_AUDIT.md#9-2026-11-09-后续实施与验收)。旧清理/设备/发布记录保留为对应历史结果；本轮未做 Maven 发布或独立消费者复验。

## 当前结论

当前版本已经是功能完整的双厂商原型，并具备可供宿主集成验证的公开 API，但仍是
`0.9.0-SNAPSHOT`，不是可直接宣称生产稳定或 Maven Central 可发布的成品。以下门槛完成前
不得发布 `1.0.0`：共享 Provider 契约继续补齐、双厂商真机长稳与 minify Release 路线验证、
独立消费者回归已闭环；仍需 Git/CI/CHANGELOG、正式 POM 身份与签名。官方 Navi 的代码、
依赖替换、独立 Demo 页面与 Debug/Release R8 构建已经落地，但正式授权、宿主前台服务及双厂商真机长稳仍需闭环。
