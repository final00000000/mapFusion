# 当前百度 Map/Search/Location AAR 没有可用的 Consumer R8 规则。原生引擎依赖稳定类名
# 完成 JNI/反射绑定，因此先保留厂商命名空间；升级 SDK 后应重新核对官方最小规则。
-keep class com.baidu.** { *; }
-keep class vi.com.** { *; }
