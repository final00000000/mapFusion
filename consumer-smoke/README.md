# 独立消费者冒烟测试

本 Android 工程刻意不加入 Map Fusion 主工程，只通过 Maven 坐标消费发布产物。应用侧不会为
Map Fusion、百度或高德补充 R8 keep 规则，避免主工程源码依赖掩盖发布问题。

在 Map Fusion 根目录执行：

```powershell
.\gradlew.bat publishAllPublicationsToMapFusionRepository
$env:ANDROID_HOME = "C:\path\to\Android\Sdk"
.\gradlew.bat -p consumer-smoke clean :app:verifyPublishedSdk --refresh-dependencies
```

验证正式仓库或其他版本时可覆盖仓库与版本：

```powershell
.\gradlew.bat -p consumer-smoke :app:verifyPublishedSdk `
    -PmapFusionRepository=https://repo.example.com/releases `
    -PmapFusionVersion=1.0.0 --refresh-dependencies
```

成功表示外部工程可解析聚合 POM/Gradle metadata 与 sources JAR，并可编译公开 API、合并 AAR
Manifest、解析百度 OkHttp/高德 Jafama 等运行时依赖，最后只使用发布产物自带的 Consumer
Proguard 规则完成资源压缩和 R8。该门禁不能替代真实 Key、release 签名设备上的运行时测试。
