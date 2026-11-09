# Map Fusion 项目梳理与本轮结果

源码/构建核对日期：2026-11-06；文档收尾与最终扫描：2026-11-09。范围：当前磁盘文档、源码、测试配置及工作区；不是历史提交的完整安全审计。

> 历史说明：第 1–8 节是改造前的审计与清理快照，其中“尚无 ViewModel”“未修改 Kotlin”“未提交”等仅描述当时任务。2026-11-09 后续实施已保存本地基线，并完善 Demo 状态层；最新改动与实际验证见第 9 节及 [Demo 架构](DEMO_ARCHITECTURE.md)。

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
