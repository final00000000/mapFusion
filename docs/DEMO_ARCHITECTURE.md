# Demo 状态管理与资源所有权

本轮实施日期：2026-11-09。本文描述 Demo 的 MVVM 状态基础与已定位问题的修复边界，不把它写成所有交付阶段完成。

## 职责

```text
Activity（视图、权限、厂商 View/Session 生命周期）
  ├─ ViewModel → 不可变 UiState → StateFlow → 生命周期内渲染
  │    └─ SavedStateHandle（可恢复的选择/设置，不保存原生对象）
  ├─ DemoRequestRepository（单次请求、同键替换、会话代次、关闭）
  ├─ DemoConsentStore（唯一偏好约定、同步落盘、跨页面读取）
  └─ DemoOverlayController → DemoOverlayStore（只拥有 Demo 自己的句柄）

SDK 仍保留 map-api → factory / 厂商 adapter 的契约边界。
```

- `presentation/main`：首页选择、权限请求状态、完整地图设置、可观察结果状态。
- `presentation/navigation`：官方导航选择和运行态，选项规范化与状态恢复。
- `data`：共享隐私存储、页面请求仓库；不复制厂商 Key，不持有地图 View。
- `map`：地图设置应用路径与 Demo 覆盖物所有权；不修改 SDK 公共 API。
- Activity 仍负责地图 View、Activity 绑定的官方导航会话、权限回调和业务工具入口；本轮没有把这些资源塞进 ViewModel。

## 恢复规则

1. 首页保存厂商选择、出行/导航方式、工具分类、全部地图开关及权限请求选择。
2. 设置使用不可变副本；对话框修改草稿，不直接修改活跃 UiState；取消不污染已应用设置。
3. 同一厂商重建保留设置，不再在每次 `switchProvider` 中无条件重置；真实切厂商按厂商能力重新建立默认设置。
4. 初始化、重建和用户应用设置复用一个应用路径，保存 SDK 接受的类型、语言和 UI 选项。
5. 原生地图/导航 Bundle 仍由宿主转发；SavedStateHandle 只保存可恢复的业务选择。
6. 新 Activity 对应新 SDK 会话，不将旧 NAVIGATING、路线列表或定位对象当成已经恢复的活跃引擎。隐私状态始终以持久存储重新校验。

## 异步与生命周期

- 轻量导航启动时捕获当前会话代次；回调转入 UI 线程后再次验证代次、会话身份、宿主是否销毁以及隐私同意。
- 单次服务请求由 DemoRequestRepository 管理；替换同键请求先使旧监听失效再取消，避免旧 CANCELLED 覆盖新结果。
- 切厂商或撤回隐私会使旧代次失效并取消请求；页面销毁永久关闭仓库，拒绝继续发起新请求。
- 官方导航事件绑定当前会话身份；初始化状态轮询仍保留为当前 SDK 契约的补偿，但仅在 STARTED 状态内运行，停止/销毁时移除。
- 两页统一使用 DemoConsentStore；保存失败不假装同意/撤回成功，恢复或重新开始时复核实际持久状态。

## 覆盖物规则

- SDK Session 中的 LocationDisplay、EmbeddedNavigator、TrackRecorder 使用原始控制器拥有自己的资源。
- Demo 查询、路线展示和覆盖物示例经 DemoOverlayController 登记返回句柄，替换示例只清 DemoOverlayStore。
- POI、行政区或普通覆盖物展示不再静默停止导航、销毁轨迹；相机移动仍可能改变视野，导航是否持续跟随由组件契约决定。
- 开始轨迹时显式停止轻量导航并释放旧轨迹；开始轻量导航时显式停止定位展示、释放轨迹，避免争抢定位展示。
- “清空地图”是明确的全局动作：停止导航/轨迹/定位展示后清全部覆盖物，结果说明包含这些副作用。
- 单个 Demo 句柄移除失败时继续尝试其他移除；销毁/切厂商不能因此跳过 Session 释放。

## 测试与未闭环项

新增 JVM 用例覆盖设置编码、选择恢复、厂商归一化、隐私保存失败、同键请求替换、迟到回调、关闭、覆盖物所有权及地图设置应用。
新增设备用例在不初始化厂商的情况下检查 Activity 重建与隐私状态，测试结束恢复原同意决定。
导航状态用例检查选择恢复、能力规范化、速度/路线索引与新会话运行态重置。

2026-11-09 实际验证：三 Demo Debug 各 38/38 JVM 用例、Lint 各 0 error / 8 warning；maps connected 12/12（既有 10 项、新增 2 项）；三 Demo Release/R8 构建及七模块 apiCheck 通过。Release 仅验收构建，未安装 minify APK 或测试真实道路导航。

实际执行结果以 [能力矩阵](../CAPABILITY_MATRIX.md) 和本轮总结为准，源码里存在测试不代表已经通过。

仍需继续：功能 UI/用例进一步拆分、官方导航服务及真实道路/弱网/锁屏/来电/长稳、真机进程死亡恢复、性能与泄漏基线、Release 发布消费和 CI 强制门禁。
本轮不会把高级 API P0-P8、Stage A-G 的全部未完成项勾选，也不会机械更新 ABI 基线。
