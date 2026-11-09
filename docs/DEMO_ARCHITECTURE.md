# Demo MVVM、页面职责与资源所有权

实施日期：2026-11-09。本轮在已提交的状态基础上进一步拆分首页和官方导航页；不是只新增 ViewModel，也不是把 Activity 搬进巨型工具类。范围为 `app` Demo 生产代码，不重构 SDK 公共接口。

## 分层与调用方向

```text
MainActivity（Android 装配、权限连接、跳转、原生恢复与生命周期）
  ├─ MainViewModel → MainUiState → STARTED 内 StateFlow 收集
  ├─ MainScreen → MainToolPanel / MainWidgets（渲染、发出值类型意图）
  ├─ MainActionCatalog → MainActionDispatcher（入口目录、穷举路由）
  ├─ MainMapDialogs / MainRouteDialog → 局部草稿 → 确认后提交
  ├─ MainFeatures（独立功能的组合根，不实现全部功能）
  │    ├─ MainSearchFeature / MainOverlayFeature
  │    └─ MainLocationFeature / MainTrackFeature / MainRouteFeature
  ├─ MainPrivacyController / MainLocationPermissionCoordinator
  └─ MainMapHost → DemoRequestRepository / MapFusionSession

OfficialNavigationActivity（Intent、权限续接、Android 生命周期与返回）
  ├─ OfficialNavigationViewModel → OfficialNavigationUiState
  ├─ OfficialNavigationPage / Controls / Renderer / Widgets / Views
  ├─ OfficialNavigationAction → OfficialNavigationCommands
  ├─ OfficialNavigationControlPolicy / OfficialNavigationText（纯策略、格式器）
  └─ OfficialNavigationSessionHost → NavigationSessionFence → OfficialNavigator

共享：DemoConsentStore、DemoMapSettingsApplier、DemoOverlayController / Store。
SDK 仍是 map-api → factory / 厂商 adapter，不套用页面 MVVM。
```

## 文件定位与边界

| 职责 | 实现 | 不应承担的职责 |
| --- | --- | --- |
| 首页 Android 连接点 | [MainActivity](../app/src/main/kotlin/com/mapfusion/demo/MainActivity.kt) | 不构造功能弹窗、不实现查询/绘制/导航、不格式化结果 |
| 首页业务状态 | [MainViewModel](../app/src/main/kotlin/com/mapfusion/demo/presentation/main/MainViewModel.kt)、[MainUiState](../app/src/main/kotlin/com/mapfusion/demo/presentation/main/MainUiState.kt) | 不保存 Activity、Context、View、MapProvider、Session 或 navigator |
| 首页视图 | [MainScreen](../app/src/main/kotlin/com/mapfusion/demo/main/MainScreen.kt)、[MainToolPanel](../app/src/main/kotlin/com/mapfusion/demo/main/MainToolPanel.kt) | 只渲染快照和发送意图，不直接调用 SDK，不持有可变业务状态副本 |
| 首页入口 | [MainActionCatalog](../app/src/main/kotlin/com/mapfusion/demo/main/MainActionCatalog.kt)、[MainActionDispatcher](../app/src/main/kotlin/com/mapfusion/demo/main/MainActionDispatcher.kt) | 目录只有值类型描述，26 个动作按五类组织；不把点击闭包保存进状态 |
| 首页功能 | [MainFeatures](../app/src/main/kotlin/com/mapfusion/demo/main/MainFeatures.kt) 及同目录独立 Feature | 功能控制器不负责 Activity 生命周期、页面布局或权限申请 |
| 设置草稿与窗口 | [MainMapSettingsDraft](../app/src/main/kotlin/com/mapfusion/demo/main/MainMapSettingsDraft.kt)、[MainDialogOwner](../app/src/main/kotlin/com/mapfusion/demo/main/MainDialogOwner.kt) | 草稿不持有 ViewModel；窗口由页面拥有，退出时统一关闭 |
| 首页 SDK 宿主 | [MainMapHost](../app/src/main/kotlin/com/mapfusion/demo/main/MainMapHost.kt) | 只拥有页面级原生资源与请求边界，不构造工具 UI，不成为全局缓存 |
| 官方导航 Android 连接点 | [OfficialNavigationActivity](../app/src/main/kotlin/com/mapfusion/demo/OfficialNavigationActivity.kt) | 不构造整页控件、不实现事件文案或导航状态归约 |
| 官方导航业务状态 | [OfficialNavigationViewModel](../app/src/main/kotlin/com/mapfusion/demo/presentation/navigation/OfficialNavigationViewModel.kt) | 接收统一事件和值快照，不持有引擎引用；运行态不持久化 |
| 官方导航视图与纯策略 | [OfficialNavigationPage](../app/src/main/kotlin/com/mapfusion/demo/presentation/navigation/OfficialNavigationPage.kt)、[ControlPolicy](../app/src/main/kotlin/com/mapfusion/demo/presentation/navigation/OfficialNavigationControlPolicy.kt)、[Text](../app/src/main/kotlin/com/mapfusion/demo/presentation/navigation/OfficialNavigationText.kt) | 视图不保存业务状态；控件与命令共享可用性规则；20 类事件文案与业务归约分开 |
| 官方导航原生资源 | [SessionHost](../app/src/main/kotlin/com/mapfusion/demo/navigation/OfficialNavigationSessionHost.kt)、[Commands](../app/src/main/kotlin/com/mapfusion/demo/navigation/OfficialNavigationCommands.kt)、[Fence](../app/src/main/kotlin/com/mapfusion/demo/navigation/NavigationSessionFence.kt) | 生命周期、身份栅栏和操作结果分别处理，不向状态层传 Activity/原生会话 |

职责拆分阶段的 MainActivity 为 170 行、OfficialNavigationActivity 为 251 行，见 [审计第 10 节](PROJECT_AUDIT.md#10-2026-11-09-页面职责拆分与中文注释)。字段/枚举注释补齐后当前为 183/264 行（均含中文注释），48 个生产文件最长为 307 行；未增加业务逻辑。每文件 400 行限额只是防回退辅助，不是 MVVM 正确性的证明。

## 恢复与状态规则

1. 首页保存厂商、出行/导航方式、工具分类、全部地图开关及位置权限待办；使用明确的分类、路线、设置、权限与运行阶段归约操作。
2. 地图设置和路线弹窗只修改局部草稿，取消不改变已应用快照。能力外的地图类型/语言不能通过草稿绕过厂商支持集合。
3. 同一厂商重建保留设置；真实切厂商重置厂商相关设置并丢弃旧定位、导航和原生恢复 Bundle。
4. 初始化、恢复和用户应用设置共用 DemoMapSettingsApplier。其应用不是事务：中途失败不能宣称全部生效，不伪造回滚。
5. 原生地图/导航 Bundle、Intent extra、返回键、配置和 `Array<String>` 权限回调协议保持不变。
6. 新 Activity 对应新 SDK 会话：地图阶段、能力数量、定位、NAVIGATING、路线列表和官方导航等待标记必须重新产生；不把旧运行状态当成已恢复引擎。
7. 官方导航由 ViewModel 归约事件、路线下标、开始等待、终态与配色；渲染与命令层共用纯 ControlPolicy，避免两套可用性判断。

## 异步、隐私与生命周期

- 单次服务请求由 DemoRequestRepository 托管。同键替换先使旧监听失效再取消；切厂商/关闭后的迟到回调不能覆盖新结果。
- UI 投递重新校验代次、页面是否关闭/退出和持久隐私决定。轻导航与轨迹额外校验具体 session/recorder 身份，旧组件终止事件不能修改新组件状态。
- 释放资源先使请求/会话令牌失效并清空引用，再停止、销毁和分离 View；单个覆盖物或组件清理失败不跳过其他资源释放。
- 页面级宿主可以持有 Activity 和原生 View，但不可全局缓存，Activity 必须显式关闭。ViewModel 始终只持有值与可恢复选择。
- 两页共用 DemoConsentStore。同步保存失败不伪造同意/撤回成功；回前台、开始操作与导航事件前复核真实存储。Key 只在同意门禁内读取，仍由被忽略的 local.properties 注入。
- 官方导航保留 150 ms 初始化补偿轮询：仅 STARTED 且当前会话仍 INITIALIZING 时运行，onStop/onDestroy 移除；新令牌和身份不匹配时拒绝旧事件。
- 权限状态由 ViewModel 管理，系统注册与结果续接由 Activity/协调器拥有。首页允许精确或大致定位，官方真实导航仍要求精确权限；取消/撤回后不能续接旧动作。

## 覆盖物与功能互斥

- SDK Session 的 LocationDisplay、EmbeddedNavigator、TrackRecorder 用原始控制器拥有自己的资源。
- Demo 查询、路线展示和覆盖物示例经 DemoOverlayController 登记句柄；普通替换只清 DemoOverlayStore，不隐式停止轨迹或导航。
- 海量点、瓦片、热力图、路线分别登记，不强转成 MapOverlay。
- 开始轨迹时显式停止轻导航、定位展示并释放旧 recorder；开始轻导航时显式停止定位展示、释放轨迹。
- “清空地图”是全局动作：停止导航/轨迹/定位后清全部覆盖物，结果说明明确这些副作用。
- Demo 图片只在创建请求时编码，用完回收 Bitmap，不落盘或全局缓存原生图像。

## 中文注释与回归

Demo 生产类/接口/枚举/对象、显式构造与普通/扩展/override/局部方法均有中文 KDoc；主构造 val/var、类成员/顶层属性、对象常量及每个枚举项现在也逐条补齐独立说明。普通主构造参数由类说明或 @param 覆盖，函数局部变量不当作字段。说明用途、适用单位/默认值、空值、所有权、恢复和清理条件，而不是只翻译字段名。

```powershell
python tools/check_demo_structure.py
python -m unittest discover -s tools/tests -p 'test_*.py' -v
```

[结构检查器](../tools/check_demo_structure.py) 和 [词法辅助](../tools/kotlin_source.py) 只扫描 main/maps/amapNavi/baiduNavi/debug，分别检查类/方法、属性、枚举项的独立中文 KDoc及每文件 400 行限额。模板字符串作为完整字面量屏蔽，不分析其中表达式；它们不是完整 Kotlin AST，不能替代人工质量核对、编译或行为测试。支持保留完整字面量的 token 比对，用于确认纯注释改动没有改名、改类型、改初值或枚举顺序；SDK 和测试源码不在生产注释门禁范围。

本轮新增 JVM 场景覆盖入口全集/能力过滤、草稿取消/非法选择、坐标类型、明确状态归约、权限一次性消费、官方导航控件策略、事件格式化/归约与会话栅栏。新增设备场景验证真实首页分类点击、状态渲染、重建和隐私门禁，但禁用厂商初始化并在结束恢复原隐私决定。

状态基础的 38/38 JVM、12/12 connected 保留在第 9 节；页面拆分的 81/81、15/15 和 Release/R8 保留在第 10 节，均不作为本次字段注释补齐的新增设备验收。字段注释阶段的最新检查以第 11 节和 [能力矩阵](../CAPABILITY_MATRIX.md) 为准。

仍未闭环：官方导航真实道路、正式授权、前台服务、弱网/锁屏/来电/长稳、真实进程死亡恢复、性能/泄漏/体积基线、minify APK 真机、发布消费和远程 CI。本轮不勾完 Stage A-G 或高级 API P0-P8，也不机械更新 ABI 基线。

### 字段说明示例与覆盖

- MainUiState 的 lastLocation 说明当前会话、null 与不持久化；preferredProvider 是用户选择，不等于 SDK 已创建；mapPhase/capabilityCount 仅当前运行态。
- OfficialNavigationUiState 的 simulationSpeed 说明 km/h、默认 60 和 20–120 限幅；navigationStarted 不等于 navigationActive；语音/配色选择与 SDK 实际设置分开。
- ControlPolicy 的 speedVisible 只决定是否展示，speedEnabled 才是操作门禁，不能凭显示推断授权或会话有效。
- NavigationSessionToken 的 identity 是宿主内引用身份，epoch 是计数而非时间戳，两者都不能放到 VM/恢复存储。
- 48 文件中 318 个属性、60 个枚举项以及原有 460 处类/方法声明的中文 KDoc 检查通过；新工具边界用例覆盖字段漏注释、枚举参数/注解/项体、局部变量、完整模板字面量与 token 变更检测。
