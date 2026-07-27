# Map Fusion 异步契约设计

状态：已实现并迁移 `map-api`、百度/高德适配器和 `map-factory` 组合能力。
后续新增 Provider 必须遵守本契约。

## 目标契约

所有单次异步请求应满足以下规则：

1. 方法返回独立的 `RequestHandle`，只取消本次请求。
2. 参数校验成功后，每个请求只产生一个终态：成功、失败、取消或超时。
3. 回调默认派发到 Android 主线程；宿主可以按请求传入 `Executor`。
4. `cancel()` 幂等。只有首次从活动态切换到取消态时返回 `true`，并回调
   `MapResult.Failure(ErrorType.CANCELLED)`。
5. 超时后释放原生监听器或搜索对象，并回调 `ErrorType.TIMEOUT`。迟到的原生回调必须丢弃。
6. `destroy()` 取消该能力仍持有的全部请求。销毁后的新请求立即返回统一错误，不能调用厂商 API。
7. 参数错误、原生同步异常、取消和超时也必须走同一个回调执行器，不能混用调用线程和主线程。

公共类型位于 `map-api`：

```kotlin
interface RequestHandle {
    val isDone: Boolean
    val isCancelled: Boolean
    fun cancel(): Boolean
}

data class AsyncCallOptions(
    val timeoutMillis: Long = 15_000L,
    val callbackExecutor: Executor? = null, // null 表示 Android 主线程
)
```

单次接口统一返回句柄，例如：

```kotlin
fun search(
    request: PoiSearchRequest,
    asyncOptions: AsyncCallOptions,
    callback: MapCallback<PoiSearchResult>,
): RequestHandle
```

连续定位返回的句柄代表一个订阅。`timeoutMillis` 只约束首次定位结果；首次结果后
订阅继续投递，`RequestHandle.cancel()` 只停止本订阅并投递一次 `CANCELLED`。
`stopContinuousLocation()` 保留为兼容入口并静默停止当前订阅；`destroy()` 静默释放，
不再触达宿主回调。

## 实现约束

- 在 `map-factory` 提供共享的原子请求状态机、主线程派发器和超时调度器。
- 百度/高德适配器只负责绑定原生请求的开始与释放，不各自实现竞态规则。
- 原生 SDK 没有取消 API 时，取消操作至少要解绑监听器、注销请求并屏蔽迟到回调。
- 回调执行器抛异常不能让请求重新进入活动态，也不能触发第二次回调。
- `MapController` 的 View 与生命周期方法仍限定主线程，不纳入异步执行器配置。

## 已完成迁移

1. `map-api` 提供 `RequestHandle`、`AsyncCallOptions`、`CANCELLED` 和 `TIMEOUT`，
   `AsyncRuntime` 保证 CAS 单终态、超时调度和执行器派发。
2. 百度/高德定位、地理编码、POI、路线、行政区、天气和截图均返回句柄。
3. `TrackRecorder` 与 `EmbeddedNavigator` 保存并取消自己创建的句柄，不再误伤宿主的
   其他定位/路线请求。
4. JVM 竞态测试已覆盖完成、取消、超时、销毁和回调执行器拒绝。

## 仍需验证

1. 在真实 Key 设备上运行取消、超时、前后台和旋转的 androidTest。
2. 公共 API 评审后运行 `apiDump` 并提交首个 ABI 基线。

## 必测竞态

- 原生成功与 `cancel()` 同时发生，只允许一个终态。
- 超时与原生失败同时发生，只允许一个终态。
- `destroy()` 与回调重入同时发生，不泄漏原生对象且不重复回调。
- 同一能力并行多个请求，取消其中一个不影响其他请求。
- 自定义单线程 Executor 收到全部回调；默认配置只在主线程回调。
