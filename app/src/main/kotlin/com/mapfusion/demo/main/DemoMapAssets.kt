package com.mapfusion.demo.main

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import com.mapfusion.api.model.MarkerIcon
import com.mapfusion.api.model.RouteMarkerOptions
import com.mapfusion.api.model.RouteOverlayOptions
import java.io.ByteArrayOutputStream

/** 演示地图样式与高清矢量级 PNG 生成；不依赖 Activity，不缓存原生 Bitmap。 */
internal object DemoMapAssets {
    /** 品牌强调色，用于文字覆盖物背景、行政区描边等强调元素。 */
    val accent = Color.rgb(13, 148, 136)
    /** 路线与轨迹折线共用的蓝色，作为不透明 ARGB 传给 SDK。 */
    val route = Color.rgb(37, 99, 235)
    /** 当前位置点及“我”图标使用的青绿色，与定位精度圈搭配。 */
    val current = Color.rgb(13, 148, 136)
    /** 路线起点标记使用的绿色。 */
    val start = Color.rgb(22, 163, 74)
    /** 路线终点标记使用的红色。 */
    val end = Color.rgb(220, 38, 38)
    /** POI 搜索标记使用的琥珀色。 */
    val poi = Color.rgb(217, 119, 6)

    /** 惰性缓存的当前定位方向圆盘图标，锚点 (0.5, 0.5)。 */
    val locationPuckIcon: MarkerIcon.Bytes by lazy { MarkerIcon.Bytes(encodeLocationPuck()) }
    /** 惰性缓存的标准品牌大头针图标，锚点 (0.5, 1.0)。 */
    val defaultPinIcon: MarkerIcon.Bytes by lazy { MarkerIcon.Bytes(pin("M", accent, Color.rgb(15, 118, 110))) }
    /** 惰性缓存的起点大头针图标，锚点 (0.5, 1.0)。 */
    val startPinIcon: MarkerIcon.Bytes by lazy { MarkerIcon.Bytes(pin("起", start, Color.rgb(21, 128, 61))) }
    /** 惰性缓存的终点大头针图标，锚点 (0.5, 1.0)。 */
    val endPinIcon: MarkerIcon.Bytes by lazy { MarkerIcon.Bytes(pin("终", end, Color.rgb(185, 28, 28))) }
    /** 惰性缓存的 POI 大头针图标，锚点 (0.5, 1.0)。 */
    val poiPinIcon: MarkerIcon.Bytes by lazy { MarkerIcon.Bytes(pin("P", poi, Color.rgb(180, 83, 9))) }

    /** 生成带立体光影与白边的精致圆形标记图标，编码结束后立即回收 Bitmap。 */
    fun endpoint(label: String, color: Int): ByteArray = png(92, 92) { canvas ->
        val cx = 46f
        val cy = 46f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(42, 15, 23, 42)
        canvas.drawCircle(cx, cy + 3f, 36f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 35f, paint)
        paint.color = color
        canvas.drawCircle(cx, cy, 30f, paint)
        paint.color = Color.argb(45, 255, 255, 255)
        canvas.drawCircle(cx, cy - 3f, 22f, paint)
        paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 30f
        val metrics = paint.fontMetrics
        val baseline = cy - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, cx, baseline, paint)
    }

    /** 绘制精致水滴形 Marker 大头针（96x116 px），底部尖端对齐 (0.5, 1.0)。 */
    fun pin(label: String, primary: Int, dark: Int): ByteArray = png(96, 116) { canvas ->
        val cx = 48f
        val cy = 44f
        val r = 32f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(45, 15, 23, 42)
        canvas.drawOval(RectF(cx - 16f, 102f, cx + 16f, 112f), paint)
        val outer = Path().apply {
            addCircle(cx, cy, r, Path.Direction.CW)
            moveTo(cx - 18f, cy + 22f)
            quadTo(cx, 110f, cx, 108f)
            quadTo(cx, 110f, cx + 18f, cy + 22f)
            close()
        }
        paint.color = Color.WHITE
        canvas.drawPath(outer, paint)
        val inner = Path().apply {
            addCircle(cx, cy, r - 4f, Path.Direction.CW)
            moveTo(cx - 14f, cy + 20f)
            lineTo(cx, 101f)
            lineTo(cx + 14f, cy + 20f)
            close()
        }
        paint.shader = LinearGradient(cx, cy - r, cx, 102f, primary, dark, Shader.TileMode.CLAMP)
        canvas.drawPath(inner, paint)
        paint.shader = null
        paint.color = Color.argb(38, 255, 255, 255)
        canvas.drawCircle(cx, cy, r - 10f, paint)
        paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = if (label.length == 1 && label[0].code > 127) 26f else 28f
        val metrics = paint.fontMetrics
        canvas.drawText(label, cx, cy - (metrics.ascent + metrics.descent) / 2f, paint)
    }

    /** 绘制带航向指示锥与白边外环的现代定位蓝点（96x96 px）。 */
    private fun encodeLocationPuck(): ByteArray = png(96, 96) { canvas ->
        val cx = 48f
        val cy = 52f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(46, 13, 148, 136)
        canvas.drawCircle(cx, cy, 34f, paint)
        val cone = Path().apply {
            moveTo(cx, 8f)
            lineTo(cx - 14f, cy - 10f)
            lineTo(cx + 14f, cy - 10f)
            close()
        }
        paint.color = accent
        canvas.drawPath(cone, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 20f, paint)
        paint.shader = LinearGradient(cx, cy - 15f, cx, cy + 15f,
            Color.rgb(20, 184, 166), Color.rgb(15, 118, 110), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, 15f, paint)
        paint.shader = null
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 4.5f, paint)
    }

    /** 创建带自定义起终点大头针的路线配置，仅返回 SDK 值对象，不拥有路线句柄。 */
    fun routeOptions(): RouteOverlayOptions = RouteOverlayOptions(
        startMarker = RouteMarkerOptions(icon = startPinIcon, title = "起点",
            anchorU = 0.5f, anchorV = 1.0f, zIndex = 101f),
        endMarker = RouteMarkerOptions(icon = endPinIcon, title = "终点",
            anchorU = 0.5f, anchorV = 1.0f, zIndex = 101f),
        lineWidth = 15f, lineColor = route, lineClickable = true, lineZIndex = 100f)

    /** 生成地面覆盖物的演示图片，不落盘到项目或设备文件。 */
    fun ground(): ByteArray = png(360, 180) { canvas ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 360f, 180f,
            Color.argb(232, 13, 148, 136), Color.argb(232, 15, 118, 110), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(8f, 8f, 352f, 172f), 32f, 32f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
        paint.color = Color.argb(210, 255, 255, 255)
        canvas.drawRoundRect(RectF(16f, 16f, 344f, 164f), 24f, 24f, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 40f; paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Map Fusion", 180f, 84f, paint)
        paint.color = Color.argb(225, 204, 251, 241); paint.textSize = 24f
        canvas.drawText("GROUND OVERLAY", 180f, 128f, paint)
    }

    /** 生成厂商原生海量点图层的小型高对比度圆点图标。 */
    fun multiPoint(): ByteArray = png(40, 40) { canvas ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(45, 15, 23, 42)
        canvas.drawCircle(20f, 21f, 15f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(20f, 20f, 15f, paint)
        paint.color = accent
        canvas.drawCircle(20f, 20f, 11f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(20f, 20f, 4f, paint)
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
