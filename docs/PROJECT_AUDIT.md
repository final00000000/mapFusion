# Map Fusion 项目梳理与本轮结果

源码/构建核对日期：2026-11-06；文档收尾与最终扫描：2026-11-09。范围：当前磁盘文档、源码、测试配置及工作区；不是历史提交的完整安全审计。

> 历史说明：第 1–11 节记录此前审计、状态/页面拆分与字段注释阶段，行数与测试仅描述对应任务。2026-11-10 脚本用途排查及无用文件清理见第 12 节；当前职责见 [Demo 架构](DEMO_ARCHITECTURE.md)。

## 1. 结论

- 项目已经具备双厂商 SDK 抽象、真实适配器、统一异步/会话契约、双地图 Demo 和独立官方导航变体，不是单纯地图展示壳。
- SDK 的契约/工厂/厂商适配分层基本成立；**主要架构债务集中在 Demo，而不是整个 SDK 都应该套 MVVM**。
- Demo 尚未使用 ViewModel、Repository、Flow/StateFlow 或生命周期状态收集；页面状态、视图构建、请求编排、权限与隐私集中在两个 Activity。
- 生产交付计划未执行完。工具面板分类不等于页面拆分，原生对象工厂不等于统一封装，编译/模拟成功不等于真实导航、后台或长稳验收。
- 本轮完成梳理、确认无用产物清理、文档脱敏、提交前门禁及相关验证，**没有实施 MVVM 重构**，也没有把剩余功能批次勾选为完成。
- 工作区开始时已有大量用户改动，本轮未暂存、提交、推送、发布或重写 Git 历史，保留其他源码和 ABI 基线。

## 2. 文档职责与校正

| 文档 | 用途 | 本轮处理 |
| --- | --- | --- |
| [README](../README.md) | 模块、接入、变体、权限和边界 | 首页分类改为实际五类；新增梳理/提交卫生入口 |
| [能力矩阵](../CAPABILITY_MATRIX.md) | 对外能力与验证状态 | 明确 Demo MVVM 未完成，补本次复验，不覆盖历史设备结论 |
| [生产交付计划](../MapSdk-Development-Plan.md) | Stage A-G、发布门槛 | 区分 Git 已建立与 CI 未完成；拆开围栏未实现/室内待验收；明确 MVVM目标；修正 flavor 任务名 |
| [异步契约](../ASYNC_CONTRACT.md) | 取消、超时、线程和所有权 | 共享运行时实际位于 `map-api/.../async/AsyncRequest.kt`；已有 ABI 基线，不再写“待提交首个基线” |
| [官方 API 覆盖计划](OFFICIAL_API_COVERAGE_PLAN.md) | 锁定制品、逐签名账本、P0-P8 | 保留；本轮没有执行全量签名审核或推进新能力批次 |
| [鉴权说明](SDK_AUTHENTICATION.md) | 当前 APK 身份、错误码与排查 | 实际 SHA1/MCODE 替换为占位符，保留错误码和排查流程 |
| [提交卫生](REPOSITORY_HYGIENE.md) | 保留/排除规则、扫描、hook、边界 | 本轮新增 |
| `docs/README.md` | 被忽略的本地旧长文笔记 | 保留并标明历史资料；移除实际签名指纹，不作为当前完成度来源 |

旧计划中的 142 项（2026-08-13）、原五库发布验证，与后续七模块/144 项记录属于不同阶段，不能混成当前一次验证。本轮结果见第 7 节；Release/真机记录不因本轮构建成功而自动更新。

## 3. 真实模块与架构边界

`settings.gradle.kts` 实际包含七个发布模块和一个 Demo；没有 `map-core` 模块。

| 模块 | 当前职责 | 后续边界 |
| --- | --- | --- |
| `map-api` | 小能力接口、模型、Provider SPI、异步运行时、诊断 | 保持统一契约，不引入 Demo 页面状态与 ViewModel |
| `map-factory` | 注册、门面、Session、LocationDisplay、轻量导航、轨迹组合 | 所有权、幂等释放与组合用例不下沉到 Activity |
| `map-amap` / `map-baidu` | 原生地图、定位、检索、路线等适配 | 厂商类型限于适配器及显式 NativeAccess，不侵入通用 Demo 流程 |
| `map-amap-navi` / `map-baidu-navi` | 官方导航可选组合与引擎适配 | 继续按厂商分包，不与双厂商聚合依赖混装 |
| `map-fusion-full` | 双地图一步接入聚合 | 不替代官方导航组合，不承载页面业务 |
| `app` | 权限/隐私宿主、地图展示、公开能力演示 | MVVM 改造主要落在此处，地图原生 View/Session 的宿主生命周期仍由页面管理 |

已有能力包含地图/相机/投影、常规覆盖物和海量点、定位展示、地理编码、POI、路线、天气/行政区、坐标转换、轨迹记录、轻量路线引导、官方导航运行控制及室内入口。支持差异、原生扩展和待设备验证项仍以能力矩阵为准，不在这里另造完成率。

## 4. Demo 架构与可定位问题

核对时：`MainActivity.kt` 2464 行，`OfficialNavigationActivity.kt` 1331 行，`DemoUiKit.kt` 262 行；app 没有 ViewModel/Repository/Flow 状态层，主要 UI 状态依靠字段与手写 Bundle 键。

以下是代码证据及改进方向，**本轮未修改这些 Kotlin 逻辑、未做设备复现**。

| 项目 | 证据位置 | 影响与下一步 |
| --- | --- | --- |
| 大型 Activity 混合职责 | `app/src/main/kotlin/com/mapfusion/demo/MainActivity.kt:152`、`:246`、`:569`、`:1963`；`OfficialNavigationActivity.kt:72` | UI构建、业务状态、请求、权限与隐私难以隔离测试。先建立纯 UiState/状态归约和服务用例，再拆视图，而非仅把长方法搬到 util |
| 清覆盖物隐含停止其他功能 | `MainActivity.kt:922`：内部调用导航 stop、轨迹 destroy，再 clearOverlays；POI/行政区/路线展示复用它 | 功能之间存在隐式耦合。先确认产品是否要求互斥；互斥则显式提示/建模，允许并行则按功能管理覆盖物所有权，不能只删 stop 而让导航引用失效 |
| 轻量导航回调 epoch 未正确捕获 | `MainActivity.kt:1539`、`:1540`；守卫定义在 `:1922` | 回调执行时才读取当前 providerEpoch，而不是启动请求时保存。应捕获启动 epoch，并补“切厂商后旧回调不更新新页面”测试 |
| 地图设置缺少完整页面状态恢复 | `MainActivity.kt:129`、`:1260`、`:2375` | 保存的 Bundle 没有 DemoMapSettings；对话框大部分开关仍读新 Activity 默认字段。存在旋转后 UI/地图状态分叉风险；用 SavedStateHandle/显式快照恢复并设备验证 |
| 官方导航初始化状态由 UI 轮询 | `OfficialNavigationActivity.kt:95`、`:1041`、`:1259` | 当前通过 150 ms 轮询同步初始化，弥补监听器在 start 时才注册的契约。先封装页面状态映射；若要新增 SDK 状态订阅，应另行设计所有权、销毁语义并审查 ABI，不能把已有轮询直接当成已证实崩溃 |
| 隐私偏好键与读取重复 | `MainActivity.kt:2424`；`OfficialNavigationActivity.kt:1253` | 两页各自维护同一偏好约定。抽唯一 ConsentStore，统一同意/撤回状态；宿主权限/隐私 UI 不进入 SDK |

本轮没有把“后台请求继续执行”直接判定为错误，也没有把 Activity 绑定的官方导航宿主强行改成纯 JVM 接口；这些需按产品及官方引擎约束设计。

## 5. 计划未闭环清单

| 批次/阶段 | 当前已有 | 仍未闭环 |
| --- | --- | --- |
| Stage A/C | 坐标、隐私、句柄、超时/取消与 JVM 竞态契约 | 双厂商共享契约进一步扩展；真机取消/超时、切厂商、前后台、旋转、进程重建竞态 |
| Stage D | Git、七库 ABI、发布脚本、历史原五库消费验证 | CI、标签/CHANGELOG、受保护分支、正式身份/签名；Navi 发布消费；minify 真机/stack-map、泄漏/ANR/体积基线 |
| Stage E | 独立 Navi 模块、统一会话、真实/模拟入口及控制 | 正式授权、语音资源/条款、宿主前台服务/通知/后台权限、数据合规、真实道路与锁屏/弱网/来电/长稳 |
| Stage F | 五类工具面板、公共 SDK 调用入口 | 页面拆分、MVVM、可单测状态层、包体/ABI预算、覆盖物压测与稳定性 |
| Stage G | 自定义样式、交互、原生海量点、高德原生离线入口 | 聚合、轨迹持久化/纠偏/回放、围栏、更多路线；室内已实现但待实际建筑切层验收 |
| 官方 P0/P1 | 默认包名与诊断入口、室内 API、六种原生检索工厂 | 当前真实身份和授权复验；室内/六种请求结果、取消/销毁设备验收；原生工厂不等于统一请求响应 |
| 官方 P2-P8 | 已有部分基础能力可复用 | 逐签名映射及定位/围栏、渲染进阶、检索路线、导航事件/后台、轨迹、额外产品分批实施 |

本轮没有建立远程 CI、申请厂商授权、配置正式发布身份，也没有以 `apiDump` 重建基线来掩盖兼容性差异。

## 6. 本轮清理、脱敏与改动范围

### 已完成

1. 删除最初快照中确认无业务/文档/测试引用的 **28 个调试产物：14 张 PNG + 14 份布局 JSON，共 6,415,800 字节（约 6.12 MiB）**。
2. 删除各变体 Lint 一致报告未使用、且没有静态/动态引用的 `R.string.result_message`；其他字符串、图标及业务资源保留。
3. 修改 `docs/SDK_AUTHENTICATION.md` 和本地旧 `docs/README.md` 的实际签名指纹为占位符。真实 API Key 保留在未跟踪的 local.properties，未破坏鉴权注入。
4. 增加 `artifacts/` 与 Python 缓存忽略规则；保持公开计划、映射账本和本轮报告可跟踪。
5. 新增 `tools/check_sensitive.py`、`.githooks/pre-commit`、LF 约束 `.gitattributes`、20 项工具测试，并在当前仓库本地启用 hook。
6. 验证中修复测试依赖兼容性：UIAutomator 2.4.0 的 shell 依赖要求 minSdk 23，与 maps 测试 APK 的 21 不兼容；当前测试使用旧版 UiDevice/By/Until，固定为 2.3.0 后 Manifest 合并和测试编译成功。业务/SDK 的 minSdk 与厂商版本未改变。

### 保留与保护

- 删除前将候选清单、哈希及压缩备份放入会话临时目录并验证；候选变化时中止删除，随后只删最初快照中未变化的文件。
- 检查期间又生成的新截图/JSON 不在初始快照内，本轮保留，由 artifacts 忽略规则隔离，未追着其他工作删除新文件。
- 本轮没有修改 MainActivity、OfficialNavigationActivity、厂商适配器、公共 API 或 ABI 基线；原有用户改动以哈希核对保留。
- 未对整个 Git 历史、任意二进制、所有未知密钥格式做完整安全审计；门禁通过不等于“绝对不含敏感信息”。

## 7. 本轮实际验证

| 检查 | 本轮结果 | 边界 |
| --- | --- | --- |
| 工具单测 | 20/20，通过 | 包括真实临时仓库 hook、暂存区/工作区错位、误报回归、输出不包含敏感值 |
| 工作区/暂存区敏感检查 | 2026-11-09 最终扫描通过：工作区 195 文件，index 154 文件 | 读取真实 Git index；暂存区差异为空，未自动暂存/提交；文档 18 个本地链接、hook LF、diff --check 均通过 |
| 七模块 Debug JVM 门禁 | 通过，报告合计 144 项，0 failure/error/skipped | Gradle 合理复用 UP-TO-DATE；不宣称本轮跑过 Release 单测 |
| 三 Demo Kotlin 编译 | maps/amapNavi/baiduNavi 均通过 | 删除未用字符串后又复验一次 |
| maps androidTest 编译 | 通过 | 初次 Manifest 合并失败已修复；没有 connected 执行 |
| 三 Demo Lint | 各 0 error / 9 warning | 删除未使用资源后：各依赖提示 1、SetTextI18n 8；警告未全部清零 |
| 七发布模块 apiCheck | 全部通过 | 未执行 apiDump，未改 ABI 基线 |
| 文件哈希核对 | 非本轮范围的既有文件未变化 | 不覆盖其他独立任务新生成的文件 |
| 设备/Release/发布 | 本轮未执行 | 没有设备安装/connected、Release/R8、Maven发布或消费者复验；既有文档记录仅保留为历史证据 |

Debug 报告分布：map-api 54，map-factory 47，map-baidu 18，map-amap 22，map-fusion-full 1，map-amap-navi 1，map-baidu-navi 1。导航模块当前单测以能力契约为主，不能替代官方引擎运行验收。

验证命令已按三个 flavor 修正到生产交付计划。日志和清理备份位于会话临时目录，不属于发布材料。

## 8. 审计时建议的最小实施顺序（历史）

1. **先锁定 Demo 行为并修正小缺口**：epoch 捕获、设置恢复、覆盖物与导航/轨迹互斥规则；为这些行为补可重复测试，不开始全量 API 扩张。
2. **先做 Main 页 MVVM**：不可变 DemoUiState、MainViewModel、SavedStateHandle、Repository/服务用例；ViewModel 不持有 Activity/地图 View/Activity 绑定 Session。页面只负责渲染、权限及地图宿主生命周期。
3. **再拆功能 UI 与导航页状态**：地图/覆盖物/查询/路线导航/轨迹按功能分包；公共 ConsentStore 与格式化/工具面板组件，避免只形成新的巨型 util。
4. **完成自动化质量与发布闭环**：把已有单测、Lint、apiCheck、敏感检查接到 CI；再复验 Release/R8、七模块发布消费和真实身份设备场景。
5. **最后按既有 P0-P8账本推进高级能力**：每批分别验收统一入口、厂商实现、Demo、错误/所有权、ABI、设备结果；需要后台导航或额外产品时单独确认授权和合规。

MVVM 验收应包含：两页可观察的单一 UiState；纯状态/用例测试；切厂商旧回调隔离；旋转与进程重建的可恢复选择状态；不同功能互斥/覆盖物所有权明确；三变体编译、Lint、ABI和相关设备用例通过。只减少 Activity 行数或新增 ViewModel 文件不能视为完成。

## 9. 2026-11-09 后续实施与验收

用户授权先将现有代码保存到本地 Git，再完善与修复。已建立本地基线 `a866f58 feat(sdk): 保存双厂商地图与导航集成基线`；后续改动按 scoped 半角 `type(scope): 描述` 分开提交，不推送、发布或重写历史。
app 改造已提交到本地 `328db0b feat(app): 完善MVVM状态管理与会话隔离`（18 个文件）；不含厂商 Key、构建产物或临时日志。

### 已实施

- 首页与官方导航页建立真实 ViewModel、不可变 UiState、只读 StateFlow 和 SavedStateHandle；只持久化选择/设置，不把旧 NAVIGATING、路线或定位恢复成活跃引擎。原生 View/Session 仍由 Activity 管理。
- 两页共用 DemoConsentStore，保留旧偏好约定；同步保存失败不伪造成功，返回页面或启动导航复查实际同意状态，撤回使事件桥失效并停止/销毁会话。
- 首页八类单次请求归入 DemoRequestRepository；同键替换先使旧监听失效再取消，切厂商/关闭后的迟到回调被隔离。轻量导航启动前捕获代次，UI 投递同时校验会话身份、销毁与隐私。
- 同厂商重建保留完整地图设置；切厂商重建默认值；对话框用不可变草稿，取消不污染已应用状态；初始化、恢复和用户应用复用同一路径。
- Demo 只登记自己创建的常规、海量点、瓦片、热力图和路线句柄，普通查询/替换覆盖物不再隐式停止导航或销毁轨迹；轨迹/导航互斥及全局清空改为显式动作。移除失败不阻断其他句柄或 Session 释放。
- 官方导航状态由 ViewModel 归约；模式/速度/路线索引按能力和边界规范化。保留现有 150 ms 初始化轮询，但限定 STARTED，onStop/onDestroy 取消；事件桥复核会话身份、代次和隐私。
- 新增 commit-msg 格式/敏感内容门禁及贡献约定；不绕过既有 pre-commit，不复制本地 Key、签名身份或真实位置到提交材料。

### 实际验证

| 检查 | 2026-11-09 结果 | 边界 |
| --- | --- | --- |
| Demo Debug JVM | maps/amapNavi/baiduNavi 各 38/38，0 failure/error/skipped | 同一套 38 项独立用例在三个变体验证，不当作 114 项独立场景 |
| Demo Debug 编译与 Lint | 三变体通过；各 0 error / 8 warning | 未清零现有 Lint 警告 |
| maps androidTest | 编译及 connected 通过，12/12，0 failure/error/skipped | 既有双厂商 10 项 + 新 Activity 重建/隐私 2 项；新用例禁用厂商初始化并恢复原隐私决定，不等同进程死亡恢复 |
| Demo Release/R8 | maps/amapNavi/baiduNavi 全部通过 | 三个 minify...WithR8 任务成功；未安装 minify APK、正式签名或做真实导航/发布消费 |
| SDK Debug JVM | 七模块门禁通过，报告合计 144 项 | 可复用 UP-TO-DATE；未复跑 SDK Release 单测 |
| SDK ABI | 七模块 apiCheck 全部通过 | 未改 SDK 公共接口或 ABI；未执行 apiDump |
| 仓库工具 | 27/27，通过 | 包含真实临时 Git 仓库 pre-commit/commit-msg 拦截格式及敏感正文 |
| 提交前文件门禁 | 工作区 216 文件敏感扫描、18 文档/工具文件、25 本地链接、双 hook LF 与 diff --check 通过 | app 提交前整个 index 210 文件扫描通过；每次提交继续运行真实 index/提交信息门禁，不替代完整历史安全审计 |

本轮实际执行命令：

```powershell
.\gradlew.bat :app:testMapsDebugUnitTest :app:testAmapNaviDebugUnitTest :app:testBaiduNaviDebugUnitTest :app:compileMapsDebugAndroidTestKotlin :app:lintMapsDebug :app:lintAmapNaviDebug :app:lintBaiduNaviDebug --no-daemon --console=plain --max-workers=2
.\gradlew.bat :app:connectedMapsDebugAndroidTest --no-daemon --console=plain --max-workers=2
.\gradlew.bat :app:assembleMapsRelease :app:assembleAmapNaviRelease :app:assembleBaiduNaviRelease --no-daemon --console=plain --max-workers=2
python -m unittest discover -s tools/tests -v
```

构建中实际修复了权限回调签名、独立覆盖物句柄类型、测试 Rule 可见性和导航速度恢复类型问题；重跑上述 Demo 回归后通过。日志保存在会话临时目录，不发布完整鉴权/设备日志。

### 仍未闭环

本轮完成的是状态基础与已定位行为修复，不是全部页面拆分。功能 UI/用例进一步拆分、真实进程死亡恢复、官方 Navi 真实路线/正式授权/锁屏/弱网/来电/长稳、性能/泄漏/体积基线、minify 真机、七模块发布消费与远程 CI 仍需后续验收。厂商 SDK 组合与业务 minSdk 21 未改变。

## 10. 2026-11-09 页面职责拆分与中文注释

本轮以干净的本地 `be3e0f6` 为起点，回应“Activity 仍有两千多行”的问题。上一轮主要完成状态基础，未彻底拆开 UI 与功能；本轮按真实职责重构 Demo，不修改 SDK 公共 API、ABI、厂商依赖组合或 minSdk，不切换 Compose，不推送远端。

### 实质拆分

| 页面/职责 | 原规模 | 当前规模与责任 |
| --- | --- | --- |
| MainActivity | 2428 行 | 170 行（含中文注释）；只装配页面对象、收集状态、连接权限/官方跳转、原生恢复与生命周期 |
| OfficialNavigationActivity | 1433 行 | 251 行（含中文注释）；只解析 Intent、连接页面/命令/宿主、处理权限续接、返回与原生生命周期 |
| 独立生产代码 | Activity 内混合实现 | 48 个 Demo 生产文件，最长 285 行；没有迁移到另一个巨型 helper |

- 首页布局由 MainScreen、MainToolPanel、MainWidgets 管理；五类功能目录用 26 个值类型 MainAction 描述，MainActionDispatcher 穷举路由，视图不保存 SDK 点击闭包。
- 设置、室内、诊断和路线窗口独立封装，MainDialogOwner 管窗口；MainMapSettingsDraft 是纯局部草稿，取消不写回已应用状态，选择按当前能力规范化。
- 查询、覆盖物、定位、轨迹和轻导航分别由独立 Feature 实现，MainFeatures 只是组合根；地图会话和请求统一归 MainMapHost，工厂注册和 Key 读取仍在同意门禁内。
- 官方导航页面拆出 Page/Controls/Renderer/Widgets/Views，纯 ControlPolicy 供控件与命令共同使用；Text 处理全部 20 类事件文案，业务选择、路线、等待和终态由 ViewModel 归约。
- Commands 转换用户意图为 SDK 操作，SessionHost 管原生生命周期与初始化轮询，NavigationSessionFence 管引用身份/代次；ViewModel 不持有 Activity、Context、View、provider、navigator 或 Session。
- 原有 Intent extra、地图/导航原生 Bundle、返回键、配置、权限 `Array<String>` 和 150 ms STARTED-only 初始化补偿协议保留。新会话运行态重置，可恢复选择仍沿用旧 SavedStateHandle 键。

### 中文注释与具体边界修复

- 注释范围是 app 的 main/maps/amapNavi/baiduNavi/debug 生产代码，不是全仓 SDK 重构。类、接口、枚举、对象、显式构造方法和普通/扩展/override/局部方法补中文 KDoc；主构造参数由类说明覆盖。
- 48 文件、460 处声明的轻量 KDoc 检查通过；每文件 400 行限额用于防回退，检查器不是 Kotlin AST，不能证明注释质量、MVVM 或运行行为。
- 轨迹回调同时验证代次与 recorder 身份；释放先清空句柄再 destroy。切会话/撤回/退出先使令牌失效，迟到或同步尾事件不能污染新运行态。
- 权限回调在隐私失效时只取消待办，不伪造系统拒绝；设置提示被返回键/外部点击取消时清掉续接。保存失败仍不伪造同意或撤回成功。
- 不支持的路线方式继续返回统一 UNSUPPORTED 错误；撤回后的占位说明改为“当前未启用会话”，不再错误声称从未初始化。
- 事件文案迁入纯格式器后，删除确认无源码引用的 7 个旧字符串资源；速度初始控件复用既有格式资源，不增加重构产生的 Lint 警告。
- 新增 43 个 JVM 场景：首页 18 个、导航 25 个；覆盖目录全集/能力过滤、草稿、坐标、明确归约、权限一次性消费、控件策略、事件和会话栅栏。新增 3 个设备场景验证真实首页连接，但禁用厂商初始化、结束恢复原隐私决定。

完整职责树与源码定位见 [Demo 架构](DEMO_ARCHITECTURE.md)。

### 本轮实际验证

| 检查 | 2026-11-09 最终结果 | 明确边界 |
| --- | --- | --- |
| Demo Debug JVM | 三变体各 81/81，0 failure/error/skipped | 43 个新场景加原 38 个；同一套场景不计作 243 项独立用例 |
| Demo 编译与 Lint | maps/amapNavi/baiduNavi Debug 及 maps androidTest 编译通过；Lint 各 0 error / 8 warning | 保留 GradleDependency 5、NewerVersionAvailable 1、UseKtx 1、SetTextI18n 1，不扩大范围升级依赖 |
| maps connected | 完整复跑 15/15，0 failure/error/skipped | 双厂商契约 10 项、原恢复 2 项、新首页交互 3 项；新增 Demo 用例禁用厂商初始化并恢复原隐私决定 |
| SDK Debug JVM | 七模块门禁通过，报告合计 144，0 failure/error/skipped | api 54、factory 47、baidu 18、amap 22、full 1、两个 navi 各 1；可复用 UP-TO-DATE，未复跑 SDK Release 单测 |
| SDK ABI | 七模块 apiCheck 通过 | SDK 源码相对 a866f58 未变；公共 API/ABI、厂商组合、业务 minSdk 21 与 Navi minSdk 23 保持不变；没有 apiDump |
| Demo Release/R8 | 三变体全部成功，三个 minify...WithR8 实际执行 | 仍有厂商字节码 stack map 警告；未安装 minify APK，不代表正式签名、真实导航或发布消费通过 |
| 中文 KDoc/结构辅助 | 48 文件、460 处声明通过，每文件不超过 400 行 | 最长为导航 ViewModel 的 285 行；词法检查不等于完整 AST 或行为证明 |
| 仓库工具 | 36/36 通过 | 新结构检查器 9 项，原工具 27 项；包含真实临时 Git 仓库双 hook 拦截 |
| 文件门禁 | 工作区 260 文件敏感扫描、20 文档/工具文件及 48 本地文件链接、双 hook LF、diff --check 通过 | 每次本地提交另检查整个 index；不发布完整鉴权、位置或设备日志 |

首轮 connected 为 14/15：既有百度单次定位返回 TIMEOUT，25,000ms 内未完成；位置开关已开启。未改生产代码、断言、超时、权限逻辑或 Key，完整复跑后 15/15。首轮日志与 XML 快照留在会话临时目录。这仅记录一次失败及复验，不推断网络原因，不证明长期稳定性。

首轮 Gradle 任务选择误写为 map-full，尚未进入编译就退出；核对工程后使用真实 map-fusion-full。后续完整 Debug、ABI、设备及 Release/R8 都重新执行，不引用上一轮 38/12 作为本轮结论。

本轮执行的核心命令（Gradle 串行，统一 --no-daemon --console=plain --max-workers=2）：

```powershell
.\gradlew.bat :app:testMapsDebugUnitTest :app:testAmapNaviDebugUnitTest :app:testBaiduNaviDebugUnitTest :app:compileMapsDebugAndroidTestKotlin :app:lintMapsDebug :app:lintAmapNaviDebug :app:lintBaiduNaviDebug
.\gradlew.bat :app:connectedMapsDebugAndroidTest
.\gradlew.bat :app:assembleMapsRelease :app:assembleAmapNaviRelease :app:assembleBaiduNaviRelease
python tools/check_demo_structure.py
python tools/check_sensitive.py
python -m unittest discover -s tools/tests -p 'test_*.py' -q
```

### 未纳入本轮验收

真实道路官方导航、正式授权、前台服务、弱网/锁屏/来电/长稳、真实进程死亡恢复、性能/泄漏/体积基线、minify 真机、发布消费与远程 CI 仍未闭环。这里完成的是页面职责拆分、中文注释及上述回归，不把全部 Stage A-G 或生产稳定性勾选完成。

### 本地提交与收尾

页面拆分、中文注释和相关测试已保存为本地 `179b94a refactor(app): 按功能拆分地图页面并完善中文注释`（59 个 app/src 文件）。提交前整个 index 的 258 文件敏感扫描通过，双 hook 为 100755、LF；核对提交父节点为 be3e0f6、UTF-8 信息正确、已提交源码与验证源码一致。文档与新结构检查器另按 scoped 格式提交，不推送、发布、重写历史或绕过 hook。

## 11. 2026-11-09 字段与枚举项注释补齐

本轮起点为干净的 f7366c1。用户指出上一轮仅类/方法说明仍不完整，data class 字段、enum 项与属性未说明用途。本轮只补 Demo 生产代码 KDoc 和排版，并强化本地检查，不改 Kotlin 运行 token、SDK/API/ABI、初值、恢复键、枚举顺序、权限或会话逻辑。

### 覆盖与说明内容

- 48 个生产文件中 44 个增加注释；原有 460 处类/方法声明保持注释，新增检查 318 个主构造/成员/顶层属性与 60 个枚举项，全部具有独立中文 KDoc。
- 数据类说明用途、适用单位与默认值、null 意义、是否恢复、资源持有与清理条件；主构造 val/var 也是属性，不能仅用类概述替代。普通参数用类说明/@param，函数局部变量不算字段。
- MainUiState 明确厂商/分类/设置/权限待办可恢复，地图阶段/能力数量/定位/轻导航活跃标记仅运行态；隐私字段是共享持久决定的缓存，不等于系统位置授权。
- 官方导航状态明确 simulationSpeed 为 km/h、默认 60、20–120 限幅；navigationStarted 不等于正在导航；语音/配色是选择，SDK 实际成功另由操作结果确认。speedVisible 与 speedEnabled 分开，不能凭显示推断授权或会话存在。
- MainAction 等枚举每项说明真实按钮动作与清理副作用；视觉枚举/色值说明 UI 语义，不冒充引擎状态或实时路况。恢复常量与 Intent 前缀说明稳定协议。
- 会话令牌 identity 是原生引用身份、epoch 是代次而非时间戳；它们只在宿主/回调使用，不可放进 VM 或恢复存储。宿主和控件字段明确生命周期、空值与关闭条件。
- 当前 Activity 行数为首页 183、官方导航 264（含字段注释）；最长生产文件为 DemoUiKit 307 行，仍不超过 400。第 10 节 170/251/285 行是上一阶段历史快照。

### 检查器改进与静态验证

- 结构检查器新增属性与枚举项诊断，不能用类级或前一个成员的说明冒充字段说明；识别构造属性、普通成员、对象常量、枚举注解/参数/项体，并排除初始化/访问器/方法局部变量。
- 提取标准库 Kotlin 词法辅助，完整屏蔽普通/原始/模板字面量并保留内容用于 token 比对；不分析模板内表达式，不声称完整 AST 或行为证明。
- 工具测试从 36 增为 53，新增 17 项包括漏字段/漏枚举、注解、局部变量、泛型/函数类型构造属性、匿名对象、字面量变更及枚举调序检测，实际 53/53 通过。
- 48 个生产文件与 f7366c1 的非注释 token 比对无差异（包含完整字面量和运算符）；这是注释改动辅助验证，不把空白归一化结果当作完整语义证明。

本轮不新增厂商设备/真实导航验收，不重跑 connected 或 Release/R8；第 10 节的 15/15 及压缩构建保留为对应历史结果，不能计作本轮新验证。

### 本轮编译与门禁结果

2026-11-09 实际执行三变体 Debug JVM、Lint、maps androidTest Kotlin 编译及七模块 apiCheck，Gradle 成功。三变体各 81/81，0 failure/error/skipped；Lint 各 0 error / 8 warning，仍为依赖更新提示 6、UseKtx 1、SetTextI18n 1。SDK 与构建配置相对本轮起点保持原样，没有 apiDump；没有用前次 connected/R8 结果冒充此次验证。

注释人工复核还修正了折线/圆的实际绘制位置、草稿起点是否已应用与释放回调发生阶段等描述；这些仅为同一行 KDoc 更正，非注释 token 无变化。收尾继续验证源码 token、注释覆盖、工具测试、工作区/整个 index 敏感信息及文档链接，再按 scoped 格式本地提交，不推送。

收尾最终复跑 Gradle 成功（2026-11-09）：三变体 JVM 仍各 81/81、Lint 各 0 error / 8 warning、maps androidTest 编译与七模块 apiCheck 通过，可复用 UP-TO-DATE。工具 53/53、48 文件非注释 token 比对、318 属性/60 枚举项覆盖、工作区 262 文件敏感扫描、22 文档/工具文件与 48 本地文件链接、hook LF 与 diff --check 均通过。

Demo 字段/枚举说明已提交到本地 `70bdd3b docs(app): 补齐数据字段和枚举项中文注释`（44 个 app/src 文件），父节点为 f7366c1；提交前整个 index 260 文件敏感扫描通过。检查器、17 项新工具用例和文档另按 scoped 格式提交；无远端推送，无 SDK/API/ABI 或运行逻辑变化。

## 12. 2026-11-10 脚本用途排查与无用文件清理

用户要求排查 Python 脚本及其他无用文件。以干净的本地 915cbd0 为起点，对 262 个可提交文件、10 个 Python 文件及散落的忽略态文件核对用途和引用；不按扩展名、文件数量或“未被 Demo 调用”删除 SDK 公开能力。

### 核对结论与处理

- 10 个 Python 源文件均保留：敏感检查与信息检查供双 hook/命令使用，Demo 结构检查导入 Kotlin 词法辅助，官方 API 签名盘点在覆盖计划中有明确命令与人工映射输入，5 个测试文件由 unittest discovery 执行。工具测试仍为 53 项，未删检查或放宽断言。
- 清理 artifacts 中 7 个旧产物：6 个 list 型 UI 节点快照 JSON（含 center/key/interactions 等结构）及 1 个 PNG 截图；262 文件中未发现具体产物文件名引用。只读结构、不输出身份/位置/节点文本。
- 清理 tools/__pycache__ 与 tools/tests/__pycache__ 的 9 个 .pyc 缓存。删除清单逐项验证忽略态、非跟踪、普通文件且非链接；先完成全部备份大小/SHA256 校验，再重验源文件未变后删除。
- 实际删除 16 文件共 186,280 字节：UI 产物 114,923 字节、字节码 71,357 字节；删除后确认清单全部不存在、业务源码与 index 均未变化。备份与删除清单只存会话临时目录，不提交。
- `docs/README.md` 为已标历史的本地长笔记，含较完整接入示例并有审计引用，保留；IDE、本机配置、Key/签名、Gradle 构建/缓存、消费者、ABI 和 Git 历史备份不动。
- 本轮仅更新现有清理/用途记录，不新增项目脚本，不改 app/SDK 代码、公共 API/ABI、版本组合或提交署名历史。忽略态文件删除不是源码 Git diff，不能为了产生删除统计而误删有用途的 .py。

脚本用途与清理边界见 [提交卫生](REPOSITORY_HYGIENE.md#5-python-脚本用途与清理边界2026-11-10)。

### 2026-11-10 实际复验与范围

- 删除后工具测试 53/53，通过；Demo 检查仍为 48 文件、460 类/方法、318 属性、60 枚举项，通过。
- 工作区 262 文件敏感扫描、22 文档/工具文件、54 个本地文件链接、双 hook LF、diff --check 通过；逐项复验 16 个源文件不存在且备份 SHA256/大小一致，验证后无 tools .pyc 残留。
- 与 915cbd0 比较，app、七 SDK 模块、消费者、工具源文件、构建/忽略/hook 配置均未变化；没有删除有效 Python 工具、测试或公开 SDK 能力，没有更改私有 Key/签名或历史备份。
- 官方 API 盘点命令 --help 入口检查 exit 0，未重跑全部官方制品扫描。没有 Android 代码变更，本轮不跑 Gradle/connected/Release/R8，不借用旧构建/设备结果作为新增验收。
- 仅对两个现有文档保存本次用途/清理记录，正常运行整个 index/提交信息门禁后本地 scoped 提交；不推送远端。
