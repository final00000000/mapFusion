package com.mapfusion.api.model

/**
 * 统一异步结果封装。各厂商的成功/失败回调统一翻译成此密封类,
 * 业务层用 when 分支处理,不感知厂商差异。
 *
 * 一次性异步能力会返回 [RequestHandle]，成功、失败、取消和超时最多发生一次。
 * 回调默认在 Android 主线程执行；调用方可通过 [AsyncCallOptions] 为单次请求指定
 * 自定义 Executor。连续定位返回的句柄代表整个订阅生命周期。
 */
sealed class MapResult<out T> {
    data class Success<T>(val data: T) : MapResult<T>()
    data class Failure(val error: MapError) : MapResult<Nothing>()

    inline fun onSuccess(block: (T) -> Unit): MapResult<T> {
        if (this is Success) block(data)
        return this
    }

    inline fun onFailure(block: (MapError) -> Unit): MapResult<T> {
        if (this is Failure) block(error)
        return this
    }
}

/**
 * 统一错误模型。原始厂商错误码保留在 [rawCode]/[rawMessage],
 * 归一化后的类别放在 [type],便于业务层跨厂商判断。
 */
data class MapError(
    val type: ErrorType,
    val message: String,
    val rawCode: Int? = null,
    val rawMessage: String? = null,
    val cause: Throwable? = null
)

enum class ErrorType {
    /** 网络异常 */
    NETWORK,

    /** Key 无效 / 鉴权失败 */
    AUTH,

    /** 无结果 */
    NO_RESULT,

    /** 权限不足(如未授予定位权限) */
    PERMISSION,

    /** 参数错误 */
    INVALID_PARAM,

    /** 该厂商不支持此能力 */
    UNSUPPORTED,

    /** 请求被调用方主动取消 */
    CANCELLED,

    /** 请求在约定时间内未完成 */
    TIMEOUT,

    /** 其他/未知 */
    UNKNOWN
}

/** 简单函数式回调别名,供 Java 友好或不想用协程的场景 */
fun interface MapCallback<T> {
    fun onResult(result: MapResult<T>)
}
