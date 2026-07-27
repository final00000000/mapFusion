# Provider 由宿主显式注册，MapFusion 不使用反射发现实现，因此当前不需要 keep 规则。
# 不要在此保留整个 com.mapfusion 包，否则会阻止宿主 R8 删除未使用能力。
