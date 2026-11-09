# 官方 API 覆盖计划与验收账本

核对日期：2026-11-06。总状态：**未全量覆盖，未全量真机验收**。
本文件描述范围、缺口和交付顺序；对外能力状态仍以 `CAPABILITY_MATRIX.md` 为准。

## 1. 范围与“完成”的定义

本轮基线是项目实际锁定的 Android SDK，不把厂商 Web API、JS/iOS/HarmonyOS SDK、
云端轨迹产品、全景产品和未购买的商业服务混入“已集成”。后者分别列入可选模块调研，
不能因为 `nativeAccess()` 可访问某个对象就声称已经提供完整统一封装。

| 组合 | 官方版本 |
| --- | --- |
| 高德普通地图 | `3dmap-location-search:11.2.000_loc11.2.000_sea9.8.0` |
| 高德官方导航 | `navi-3dmap-location-search:11.2.000_3dmap11.2.000_loc11.2.000_sea9.8.0` |
| 百度普通地图 | Map/Search `8.2.0`、Location_All `9.6.8` |
| 百度官方导航 | Map-AllNavi `8.1.0`、base/bwnavi `8.1.0`、carnavi `8.0.1`、Search `8.2.0`、Location_All `9.6.8` |

每个能力需要分别记录：官方签名和版本、统一接口/厂商扩展入口、实际适配、Demo 路径、
生命周期与错误语义、构建/ABI 结果、设备和真实结果。构建成功不等于服务鉴权成功，
模拟导航成功不等于真实道路导航、后台或长稳通过。

状态：`UNREVIEWED` 待逐项审核；`UNIFIED` 已有明确统一映射；`NATIVE_FACTORY` 仅代建原生对象；
后续审核可标 `NATIVE_ONLY`、`MISSING`、`VENDOR_UNSUPPORTED`、`DEPRECATED`。
`UNREVIEWED` 包含已有实现但尚未逐签名标注的内容，不能用它计算缺失率；
`NATIVE_FACTORY` 不代表该类所有方法已统一封装，运行验证单列。

## 2. 可重复的逐符号审计

```powershell
.\gradlew.bat :app:exportOfficialSdkArtifacts --no-daemon --console=plain --max-workers=2
python tools/official_api_inventory.py
```

输出：

- `app/build/reports/official-api/artifacts.tsv`：三个 Demo 实际解析的官方制品和版本。
- `build/reports/official-api/symbols.csv`：类、公开/受保护字段、构造器、方法的 JVM 签名，按变体记录。
- `build/reports/official-api/summary.json`：制品 SHA256、扫描命名空间、候选数量、未匹配的人工映射。
- `docs/official-api-mappings.csv`：人工确认的精确签名、SDK 入口、Demo、验证状态。

扫描器直接解析 class 文件，不执行厂商代码；同时扫描 AAR 的 `classes.jar` 和 `libs/*.jar`。
只扫描地图/定位/围栏/检索/导航/纠偏等业务命名空间，排除编译器 synthetic、非公开类型及
第三方图片/TTS 实现库。反射可见但官方文档未承诺的公开类仍是待审候选，不能自动视为
稳定 API。继承的 Android/Java 通用方法不重复登记；版本升级时须用官方参考手册补审
新增命名空间和额外产品。不要把候选清单说成已经完成了官方文档的逐项审核。

本轮先精确标注新增入口，其余由后续批次审核补齐，不能给出“100%”或用引用字符串猜覆盖率。

本轮生成结果：maps 16,303、amapNavi 13,796、baiduNavi 12,680 条候选符号（包括类型、字段、
构造器和方法，并非独立业务 API 数量；跨变体和同名制品可能重复）。新增的 11 条精确映射全部
匹配制品，剩余映射待审核。此计数不能当成缺失功能数量，也不能当成完成百分比。

## 3. 缺口与实施顺序

| 批次 | 官方能力范围 | 当前入口与明确缺口 | 交付与验收 |
| --- | --- | --- | --- |
| P0 接入身份 | 两家 Key / SHA1 / applicationId / 隐私 | 原三个 flavor 带后缀，与登记包名不符 | 默认无后缀；可选并排模式；运行时身份 API；两家设备初始化及算路复验 |
| P1 室内与检索扩展 | 室内建筑、楼层、楼层事件；POI V2、公交站点、距离矩阵、百度建筑/推荐停车点/分享 | 本轮新增统一室内 API；六种搜索仅原生工厂 | 先通过双普通地图及 Navi 编译；室内实际楼层数据、变更/退出回调、无数据及销毁行为待真机 |
| P2 定位完整参数与围栏 | 定位模式/缓存/传感器/地址/坐标系/模拟位置/最后位置、围栏创建/状态/事件 | 统一 `LocationClient` 只覆盖常用参数，尚无围栏能力接口 | 对照官方 LocationClient/Option/GeoFence 签名；按能力拆分扩展；后台请求由宿主权限/服务协作；不伪造百度不具备的高德围栏语义 |
| P3 地图渲染与覆盖物 | 气泡窗、覆盖物编辑/动画、线纹理/渐变、洞、多段覆盖物、3D 模型/粒子、海量点/聚合、地图状态/离屏渲染/离线 | 普通覆盖物已有统一入口，进阶样式和事件未逐项覆盖；原生地图只属扩展通道 | 精确登记 `AMap/BaiduMap/UiSettings/Projection/*Options/overlay`；共享项统一，独有项类型化扩展；生命周期与大数据压测 |
| P4 检索与路线 | POI bounds/多边形/室内/子节点、公交站点/线路详情、行政层级、货车/新能源/未来行程/避让区/室内路线 | `PoiSearcher/RoutePlanner` 仅部分参数；六个新工厂没有统一请求/响应/取消 | 按官方 query/result 字段逐项映射；支持集合明确；请求取消/超时/原始错误与坐标系一致 |
| P5 官方导航事件 | 引擎位置、平行道路、巡航/拥堵、路口/车道细节、限行/路线变化/路线策略 | 部分官方回调仍为空；百度统一层仅驾车 | 补齐事件模型和监听映射；步骑行须根据 bwnavi 独立引擎设计，不能给驾车换标签；模拟/真实各验一次 |
| P6 官方导航运行 | 离线导航/下载、车辆与新能源/货车参数、TTS、前台服务/通知/后台位置、状态恢复 | `BACKGROUND_GUIDANCE` 未声明；长稳未验收 | 服务通知与宿主权限闭环后才声明；偏航、弱网、锁屏、来电、进程重建、长路线验收 |
| P7 轨迹与工具 | 本地轨迹持久化/恢复/动画、官方纠偏、点聚合、几何工具 | `TrackRecorder` 仅记录；离线管理仅高德原生入口 | 本地组合与厂商在线纠偏分开；取消、生命周期、坐标系与数据量边界验证 |
| P8 额外产品 | 百度鹰眼/全景、高德猎鹰、独立 Web 服务/商业高级能力 | 当前依赖中未集成；不能归入现有 SDK 已覆盖 | 单独确认对应 Android 包、鉴权、付费/配额、可分发依赖及隐私；采用可选模块，不能混装造成 native 冲突 |

执行顺序 P0 → P1 → P2/P3 → P4 → P5/P6 → P7/P8。每批先实现公共 SDK 和适配，再接 Demo，
功能齐备后补系统性单元/设备回归；构建与 ABI 检查持续执行。任何批次未完成不得勾选全量完成。
两家 Navi 继续分包；默认相同 applicationId 只是安装身份相同，依赖仍独立。

## 4. 本轮已落地与仍待验证

- `MapSdkIdentity.read(context)`：读取当前 APK 包名、SHA1 和百度 MCODE；不输出 API Key。
- `MapController`：`supportsIndoorFloorControl`、`activeIndoorBuilding`、`switchIndoorFloor`、`setOnIndoorBuildingListener`。
- 两家 adapter：室内数据快照、切层校验、厂商状态/异常、监听和销毁清理。
- `AmapNativeAccess`：`newPoiSearchV2`、`newBusStationSearch`、`newDistanceSearch`。
- `BaiduNativeAccess`：`newBuildingSearch`、`newRecommendStopSearch`、`newShareUrlSearch`。
- Demo：地图设置中的“SDK 接入诊断”和“室内楼层”；三个变体默认身份一致。
- 尚未完成：室内真实建筑的切层验收、六种新原生检索请求的结果验收、剩余批次和全量逐签名映射。

## 5. 官方依据

- 百度获取 AK / 包名 / SHA1：<https://lbsyun.baidu.com/docs/guide?title=android-guide/create-project/ak>
- 高德获取 Key / SHA1 / 包名：<https://lbs.amap.com/api/android-sdk/guide/create-project/get-key>
- 高德工程初始化和隐私：<https://lbs.amap.com/api/android-sdk/guide/create-project/dev-attention>
- 高德地图参考手册：<https://a.amap.com/lbs/static/unzip/Android_Map_Doc/3D/index.html>
- 高德室内属性：<https://a.amap.com/lbs/static/unzip/Android_Map_Doc/3D/com/amap/api/maps/model/IndoorBuildingInfo.html>

在线文档可能随版本更新；本轮方法是否存在以固定制品的公开签名和编译为准。
