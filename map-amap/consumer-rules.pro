# 当前高德组合包没有可用的 Consumer R8 规则。原生引擎依赖稳定类名完成 JNI/反射
# 绑定，因此先保留厂商命名空间；升级 SDK 后应重新核对官方最小规则。
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }

# 高德组合包包含对未公开软 GNSS 扩展的条件引用；标准定位不打包该扩展。
-dontwarn com.amap.ams.gnss.GnssSoftLocator
