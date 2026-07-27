# Map Fusion 能力矩阵

本文件是能力范围和完成状态的唯一事实来源。README 负责接入说明，
`MapSdk-Development-Plan.md` 只描述后续交付顺序，不复制另一套模块名或完成度。

更新时间：2026-07-27。

状态说明：`完成` 表示统一入口和当前两家真实 SDK 实现均已落地；`受限` 表示入口可用，
但厂商支持范围或参数语义不同；`已配置` 表示工程配置存在但发布验证尚未闭环；
`计划` 表示尚不能作为对外能力承诺；`预留` 表示 API 字段存在但当前没有稳定实现语义。

## 地图与覆盖物

| 能力 | 百度 | 高德 | 统一契约状态 | 备注 |
| --- | --- | --- | --- | --- |
| MapView 与生命周期 | 完成 | 完成 | 完成 | `MapFusionSession` 托管创建和幂等释放 |
| 相机移动、动画、边界、缩放范围 | 完成 | 完成 | 完成 | 新增 `fitPoints()` 和 `LatLngBounds.fromPoints()`；View/生命周期调用要求主线程 |
| 坐标边界转换 | 完成 | 完成 | 完成 | 输入按 `coordType` 转到 BD09/GCJ02，UNKNOWN 和非法数值明确拒绝 |
| Marker | 完成 | 完成 | 完成 | 图标、标题、snippet、旋转、透明度、平贴、锚点、显隐、层级、tag、点击及移除状态 |
| 原生海量点 | 完成 | 完成 | 完成 | 百度 `MultiPoint` / 高德 `MultiPointOverlay`；统一图标、锚点、批量替换、显隐、点击、业务 id/tag 和幂等移除，不伪造高德缺失的 zIndex |
| 路线与自定义起终点图标 | 完成 | 完成 | 完成 | `addRoute()` + `RouteOverlayOptions`，支持 Default/Asset/Resource/Bytes |
| Polyline | 完成 | 完成 | 完成 | 普通线、虚线、颜色、宽度、显隐、层级、tag |
| Polygon / Circle | 完成 | 完成 | 完成 | 删除幂等，并同步注销点击命中与内部句柄 |
| GroundOverlay / Text | 完成 | 完成 | 完成 | 统一图片源、位置/边界和基础样式 |
| TileOverlay | 受限 | 受限 | 受限 | XYZ、瓦片尺寸、bounds、min/max zoom 已统一过滤；缓存、显隐和层级仍受原生 API 差异约束 |
| HeatMap | 完成 | 受限 | 受限 | 高德使用官方瓦片热力图实现以规避部分设备 native 崩溃 |
| 地图截图与交互事件 | 完成 | 完成 | 完成 | 地图、Marker、海量点、覆盖物和相机事件；截图返回 PNG |
| 地图类型 | 完成 | 完成 | 完成 | 百度 NORMAL/SATELLITE/NONE；高德 NORMAL/SATELLITE/NIGHT/NAVIGATION；不支持项明确返回 `UNSUPPORTED` |
| UI 与手势设置 | 受限 | 完成 | 受限 | 百度无法等价表达高德的定位按钮等全部选项，尚无逐项能力查询 |
| 交通、建筑、室内、底图 POI | 受限 | 完成 | 受限 | 统一开关已实现，最终效果以厂商、城市和底图数据为准 |

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
| 厂商完整原生导航 SDK | 计划 | 计划 | 计划 | 官方车道级引导、语音、路口放大图、电子眼等必须作为可选 Navi 模块接入 |
| 外部厂商页面导航 | 完成 | 完成 | 受限 | 兼容旧业务的 URI 入口；成功只表示 Intent 被系统接受 |
| 行政区与边界 | 受限 | 完成 | 受限 | 百度行政层级信息尚未完全归一 |
| 实时天气与预报 | 完成 | 完成 | 受限 | 高德查询需要 city/adCode，不接受纯坐标请求 |
| 轨迹记录 | 完成 | 完成 | 完成 | 通用组合能力；持久化、恢复、纠偏和回放仍为计划 |

`EmbeddedNavigator` 是 `map-factory` 基于 `MapController + LocationClient + RoutePlanner`
组合出的轻量能力。`REAL` 使用真实设备位置，`SIMULATED` 按规划路线、速度和刷新间隔生成位置；
这两个模式都不是百度/高德官方 Navi 的真实/模拟导航。它不跳转外部 App，但“留在 App 内”不等于
已经接入厂商完整导航 SDK；不得把当前实现宣传为车道级 turn-by-turn 商用导航，也不得暗示
已具备官方语音、车道线、路口放大图、电子眼或限速数据。

## SDK 与示例工程

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 单厂商组合 | 完成 | `map-factory + map-baidu` 或 `map-factory + map-amap`，宿主显式注册 Factory |
| 双厂商聚合包 | 完成 | `map-fusion-full` 自动注册两家，提供 `create/openSession/updatePrivacyConsent` |
| Demo 公共能力边界 | 完成 | 路线绘制、视野适配、定制定位展示、原生海量点、搜索、导航、轨迹等调用公开 SDK API；权限和隐私 UI 保持宿主职责 |
| 隐私同意/撤回 | 完成 | 默认不同意；撤回时先取消请求和销毁 Session，再通过 Factory SPI 同步 false |
| 请求托管 | 完成 | `RequestScope` 支持同 key 替换、单 key/全部取消和关闭 |
| `MapConfig.extras` | 预留 | 当前两家适配器未消费稳定 key；1.0 前需决定类型化扩展契约或继续仅作保留字段 |

## 工程化门槛

| 项目 | 状态 | 交付要求或当前结果 |
| --- | --- | --- |
| JVM 单测 | 已通过 | 2026-07-27 最新报告：五模块 125/125，0 failure、0 error；覆盖定位展示、原生海量点，以及真实/模拟轻量导航推进与竞态 |
| 真机 androidTest | 已通过当前套件 | OnePlus 9 Pro 上 10/10：双厂商地图生命周期、WGS84 Marker、原生海量点与批量替换、真实定位、步行路线与几何数据；release SHA1、minify、前后台和长稳仍待验收 |
| 异步取消/超时/线程 | 完成 | `RequestHandle`、单请求取消、超时、默认主线程、自定义 Executor；导航/轨迹事件串行，见 `ASYNC_CONTRACT.md` |
| Release/R8 构建 | 已通过 | `assembleRelease` 生成五个 Release AAR 和 unsigned Demo APK；已定向抑制厂商可选缺类，仍有厂商 jar stack-map 警告 |
| Release Maven 配置 | 已通过独立消费验证 | 五模块均已发布 AAR、sources、POM、Gradle metadata；独立工程已验证聚合包、传递依赖和 sources JAR |
| POM 元数据门禁 | 已配置 | `mapFusionRequirePublicationMetadata=true` 会强制校验；真实 group/license/developer/SCM/签名身份待项目方提供 |
| Consumer R8 | 已通过构建、运行受限 | 独立消费者不补应用规则仍完成 Release/R8；厂商命名空间仍保守 keep，需用 minify 真机结果和官方规则收紧 |
| ABI 兼容检查 | 已通过初始基线 | BCV 覆盖五个发布模块；本轮 `apiDump` 与独立 `apiCheck` 成功，后续 API 变更必须继续审查 |
| Lint | 已通过但有警告 | 六模块 Lint 均 BUILD SUCCESSFUL；最新 XML 合计 0 error / 11 warning，均为依赖/工具版本提示或 KTX 建议 |
| 版本库与 CI | 未配置 | 当前目录不是 Git 仓库；正式交付前必须建立版本历史、受保护分支和自动校验 |
| Maven Central 发布 | 未完成 | 脚本支持元数据与签名，但真实身份信息、签名密钥、Central 账户和发布验证均未闭环 |

## 当前结论

当前版本已经是功能完整的双厂商原型，并具备可供宿主集成验证的公开 API，但仍是
`0.9.0-SNAPSHOT`，不是可直接宣称生产稳定或 Maven Central 可发布的成品。以下门槛完成前
不得发布 `1.0.0`：共享 Provider 契约继续补齐、双厂商真机长稳与 minify Release 验证、
独立消费者回归已闭环；仍需 Git/CI/CHANGELOG、正式 POM 身份与签名，以及用户
要求的厂商完整原生导航可选模块。
