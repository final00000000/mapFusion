# Demo 鉴权排查

核对日期：2026-11-06。

登记身份必须从当前安装 APK 读取；仓库不保存用户实际签名指纹或 MCODE：

```text
Package: com.mapfusion.demo
SHA1: <从当前 APK 读取，不提交真实值>
Baidu MCODE: <当前 APK 的 SHA1>;com.mapfusion.demo
```

旧导航 APK 实际 package 分别为 `com.mapfusion.demo.amapnavi` /
`com.mapfusion.demo.baidunavi`，普通双地图为 `.maps`。仅登记无后缀包名不会匹配这些旧 APK。
历史记录出现过百度 `230 / APP Mcode码校验失败` 和高德
`INVALID_USER_SCODE#SHA1AndPackage`；这提示需要检查实际包名、安全码与授权，
不能保证修改包名后其余授权都自动满足。

现默认三个 flavor 均使用 `com.mapfusion.demo`，对应三份单独 APK；安装另一 flavor 会更新同包应用，
不能同时并排安装，但仍可保留不同厂商原生库的依赖隔离。旧后缀 APK 不会被它替换，识别时看实际包名。

如确需并排安装，添加 `-PmapFusionDemoSideBySide=true`，并分别登记每个最终包名。
`local.properties` 可以按 flavor 覆盖 Key：

```properties
BAIDU_MAP_API_KEY=基础百度AK
AMAP_API_KEY=基础高德Key
# 仅在对应 flavor 需要不同 Key 时填写；未填写时使用基础值。
BAIDU_MAP_API_KEY_MAPS=双地图包对应百度AK
AMAP_API_KEY_MAPS=双地图包对应高德Key
AMAP_API_KEY_AMAPNAVI=高德导航包对应Key
BAIDU_MAP_API_KEY_BAIDUNAVI=百度导航包对应AK
```

检查顺序：

1. 手机 Demo → 地图设置 → SDK 接入诊断；读取安装包实际 package 与 SHA1。
2. 百度应用类型选择 Android SDK，包名/SHA1 与此一致，勾选实际需要的服务。导航授权还须单独核验。
3. 高德 Key 类型选择 Android 平台 SDK，调试版/发布版安全码分别对应实际签名，Package 与此一致。
4. 确认注入的是控制台对应 Key；代码中的 BuildConfig 与 Manifest 共用同一配置来源。不在日志中打印 Key。
5. 编译安装后检查真实定位、路线规划、模拟导航和真实导航；任一步失败保留厂商错误码，不以地图能显示代替验收。

正式包必须从正式 APK 读取签名，不能把本机 debug SHA1 当成 release SHA1。
`namespace` 或 Kotlin `package` 不等于每个构建变体最终的 `applicationId`。

官方依据：

- [百度 AK 与包名/SHA1](https://lbsyun.baidu.com/docs/guide?title=android-guide/create-project/ak)：Android Studio 以 applicationId 为准；服务须勾选。
- [高德 Key 与安全码](https://lbs.amap.com/api/android-sdk/guide/create-project/get-key)：Android 平台 SDK Key，绑定 Package 与调试/发布 SHA1；不一致会触发 INVALID_USER_SCODE。
