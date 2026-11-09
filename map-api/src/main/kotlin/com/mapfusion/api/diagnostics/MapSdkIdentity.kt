package com.mapfusion.api.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.mapfusion.api.model.ErrorType
import com.mapfusion.api.model.MapError
import com.mapfusion.api.model.MapResult
import java.security.MessageDigest
import java.util.Locale

/** 当前安装包的身份，不包含 Key。只读取本应用签名，不触发厂商 SDK 初始化。 */
data class MapSdkIdentity(
    val packageName: String,
    /** 当前 APK 签名；不混入签名轮换前的历史证书。 */
    val signingSha1: List<String>,
) {
    val baiduMcodes: List<String> get() = signingSha1.map { "$it;$packageName" }

    companion object {
        @JvmStatic
        @Suppress("DEPRECATION")
        fun read(context: Context): MapResult<MapSdkIdentity> = try {
            val name = context.packageName
            val manager = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners.orEmpty()
            } else {
                manager.getPackageInfo(name, PackageManager.GET_SIGNATURES).signatures.orEmpty()
            }
            check(signatures.isNotEmpty()) { "当前安装包未返回签名证书" }
            val sha1 = signatures.map { signature ->
                MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
                    .joinToString(":") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
            }.distinct()
            MapResult.Success(MapSdkIdentity(name, sha1))
        } catch (error: Exception) {
            MapResult.Failure(MapError(ErrorType.UNKNOWN, "读取安装包身份失败：${error.message}", cause = error))
        }
    }
}
