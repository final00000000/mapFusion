package com.mapfusion.demo.main

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.mapfusion.api.model.MarkerIcon
import com.mapfusion.api.model.RouteMarkerOptions
import com.mapfusion.api.model.RouteOverlayOptions
import java.io.ByteArrayOutputStream

/** 演示地图样式与 PNG 生成；不依赖 Activity，不缓存原生 Bitmap。 */
internal object DemoMapAssets {
    /** 品牌强调色，用于文字覆盖物背景、行政区描边等强调元素。 */
    val accent = Color.rgb(0, 105, 92)
    /** 路线与轨迹折线共用的蓝色，作为不透明 ARGB 传给 SDK。 */
    val route = Color.rgb(25, 118, 210)
    /** 当前位置点及“我”图标使用的青绿色，与定位精度圈搭配。 */
    val current = Color.rgb(0, 121, 107)
    /** 路线起点圆标使用的绿色。 */
    val start = Color.rgb(46, 125, 50)
    /** 路线终点圆标使用的红色。 */
    val end = Color.rgb(198, 40, 40)

    /** 生成起点、终点或当前位置圆形图标，编码结束后立即回收 Bitmap。 */
    fun endpoint(label: String, color: Int): ByteArray = png(84, 84) { canvas ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        canvas.drawCircle(42f, 42f, 38f, paint)
        paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f
        canvas.drawCircle(42f, 42f, 35f, paint)
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER; paint.textSize = 34f
        canvas.drawText(label, 42f, 54f, paint)
    }

    /** 创建带自定义起终点的路线配置，仅返回 SDK 值对象，不拥有路线句柄。 */
    fun routeOptions(): RouteOverlayOptions = RouteOverlayOptions(
        startMarker = RouteMarkerOptions(icon = MarkerIcon.Bytes(endpoint("起", start)), title = "起点",
            anchorU = 0.5f, anchorV = 0.5f, zIndex = 101f),
        endMarker = RouteMarkerOptions(icon = MarkerIcon.Bytes(endpoint("终", end)), title = "终点",
            anchorU = 0.5f, anchorV = 0.5f, zIndex = 101f),
        lineWidth = 14f, lineColor = route, lineClickable = true, lineZIndex = 100f)

    /** 生成地面覆盖物的演示图片，不落盘到项目或设备文件。 */
    fun ground(): ByteArray = png(360, 180) { canvas ->
        canvas.drawColor(Color.argb(235, 255, 255, 255))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; textSize = 42f; textAlign = Paint.Align.CENTER }
        canvas.drawText("Map Fusion", 180f, 82f, paint)
        paint.color = current; paint.textSize = 27f
        canvas.drawText("GroundOverlay", 180f, 132f, paint)
    }

    /** 生成厂商原生海量点图层的小型统一图标。 */
    fun multiPoint(): ByteArray = png(36, 36) { canvas ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.FILL }
        canvas.drawCircle(18f, 18f, 13f, paint)
        paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
        canvas.drawCircle(18f, 18f, 11f, paint)
    }

    /** 统一 Bitmap 编码及异常释放路径，绘制或压缩失败也不泄漏原生图像内存。 */
    private fun png(width: Int, height: Int, draw: (Canvas) -> Unit): ByteArray {
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(bitmap))
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "演示图片编码失败" }
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}
