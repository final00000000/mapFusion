package com.mapfusion.demo

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** 消息语义到视觉色板的映射，状态层不直接依赖 Android 颜色。 */
internal enum class StatusTone(
    val accentColor: Int,
    val badgeBgColor: Int,
    val cardBgColor: Int,
    val cardBorderColor: Int,
    val badgeLabel: String,
) {
    NEUTRAL(
        accentColor = DemoTheme.TEXT_SECONDARY,
        badgeBgColor = DemoTheme.SURFACE_SUBTLE,
        cardBgColor = DemoTheme.SURFACE,
        cardBorderColor = DemoTheme.BORDER,
        badgeLabel = "就绪",
    ),
    INFO(
        accentColor = DemoTheme.BRAND,
        badgeBgColor = DemoTheme.BRAND_SOFT,
        cardBgColor = Color.rgb(245, 251, 250),
        cardBorderColor = Color.rgb(183, 224, 217),
        badgeLabel = "状态",
    ),
    SUCCESS(
        accentColor = DemoTheme.SUCCESS,
        badgeBgColor = DemoTheme.SUCCESS_SOFT,
        cardBgColor = Color.rgb(244, 251, 246),
        cardBorderColor = Color.rgb(187, 228, 197),
        badgeLabel = "成功",
    ),
    ERROR(
        accentColor = DemoTheme.ERROR,
        badgeBgColor = DemoTheme.ERROR_SOFT,
        cardBgColor = Color.rgb(254, 246, 246),
        cardBorderColor = Color.rgb(245, 198, 198),
        badgeLabel = "异常",
    ),
}

/** 两页共享的视觉组件与样式工厂，只处理外观，不承接地图业务或会话生命周期。 */
internal object DemoTheme {
    val PAGE_BG = Color.rgb(241, 245, 249)          // Slate 100
    val SURFACE = Color.WHITE
    val SURFACE_ELEVATED = Color.rgb(248, 250, 252) // Slate 50
    val SURFACE_SUBTLE = Color.rgb(241, 245, 249)   // Slate 100
    val SURFACE_PRESSED = Color.rgb(226, 232, 240)  // Slate 200
    val MAP_PLACEHOLDER = Color.rgb(226, 232, 240)

    val BORDER = Color.rgb(226, 232, 240)           // Slate 200
    val BORDER_STRONG = Color.rgb(203, 213, 225)    // Slate 300
    val DIVIDER = Color.rgb(236, 241, 246)

    val TEXT_PRIMARY = Color.rgb(15, 23, 42)        // Slate 900
    val TEXT_SECONDARY = Color.rgb(71, 85, 105)     // Slate 600
    val TEXT_MUTED = Color.rgb(100, 116, 139)       // Slate 500

    val BRAND = Color.rgb(13, 122, 109)             // Deep Teal
    val BRAND_DARK = Color.rgb(9, 94, 84)
    val BRAND_SOFT = Color.rgb(224, 245, 241)
    val BRAND_BORDER = Color.rgb(132, 209, 197)

    val SUCCESS = Color.rgb(22, 128, 61)
    val SUCCESS_SOFT = Color.rgb(220, 252, 231)

    val WARNING = Color.rgb(217, 119, 6)
    val WARNING_SOFT = Color.rgb(254, 243, 199)

    val ERROR = Color.rgb(211, 47, 47)
    val ERROR_DARK = Color.rgb(178, 34, 34)
    val ERROR_SOFT = Color.rgb(254, 226, 226)
    val ERROR_BORDER = Color.rgb(244, 180, 180)

    val ROUTE_BLUE = Color.rgb(25, 118, 210)

    /** 根据当前屏幕密度转换整数 dp，统一共享组件尺寸。 */
    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** 转换浮点 dp，保留圆角和描边计算所需的精度。 */
    fun dpF(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    /** 构造可选描边的圆角背景，每次返回独立 Drawable 避免跨 View 状态串扰。 */
    fun roundedRect(
        context: Context,
        fillColor: Int,
        strokeColor: Int = Color.TRANSPARENT,
        cornerDp: Float = 10f,
        strokeDp: Float = 1f,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpF(context, cornerDp)
        setColor(fillColor)
        if (strokeColor != Color.TRANSPARENT && strokeDp > 0f) {
            setStroke(dp(context, strokeDp.toInt().coerceAtLeast(1)), strokeColor)
        }
    }

    /** 构造仅顶部圆角的底部工具面板背景，不修改系统窗口形状。 */
    fun topSheetBackground(
        context: Context,
        fillColor: Int = SURFACE,
        strokeColor: Int = BORDER,
        cornerDp: Float = 18f,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        val r = dpF(context, cornerDp)
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        setColor(fillColor)
        setStroke(dp(context, 1), strokeColor)
    }

    /** 组合正常、按压和水波纹状态，调用者只提供颜色与尺寸。 */
    fun interactiveSurface(
        context: Context,
        fillColor: Int,
        pressedColor: Int,
        strokeColor: Int = Color.TRANSPARENT,
        cornerDp: Float = 10f,
        strokeDp: Float = 1f,
    ): Drawable {
        val normal = roundedRect(context, fillColor, strokeColor, cornerDp, strokeDp)
        val pressed = roundedRect(context, pressedColor, strokeColor, cornerDp, strokeDp)
        val mask = roundedRect(context, Color.WHITE, Color.TRANSPARENT, cornerDp, 0f)
        val stateList = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
        return RippleDrawable(ColorStateList.valueOf(pressedColor), stateList, mask)
    }

    /** 根据业务语义格式化结果标题和详情，不在视觉层更改业务状态。 */
    fun formatStatusText(
        title: String,
        detail: String,
        tone: StatusTone,
    ): CharSequence {
        val builder = SpannableStringBuilder()
        val bullet = "●  "
        builder.append(bullet)
        builder.setSpan(
            ForegroundColorSpan(tone.accentColor),
            0,
            bullet.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            RelativeSizeSpan(0.85f),
            0,
            bullet.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )

        val titleStart = builder.length
        builder.append(title)
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            titleStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            ForegroundColorSpan(
                when (tone) {
                    StatusTone.ERROR -> ERROR
                    else -> TEXT_PRIMARY
                },
            ),
            titleStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val cleanDetail = detail.trim()
        if (cleanDetail.isNotEmpty()) {
            builder.appendLine()
            val detailStart = builder.length
            builder.append(cleanDetail)
            builder.setSpan(
                ForegroundColorSpan(
                    when (tone) {
                        StatusTone.ERROR -> Color.rgb(153, 27, 27)
                        else -> TEXT_SECONDARY
                    },
                ),
                detailStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            builder.setSpan(
                RelativeSizeSpan(0.92f),
                detailStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return builder
    }

    /** 创建品牌徽标，装饰文字不作为无障碍操作入口。 */
    fun buildBrandBadge(context: Context): View = FrameLayout(context).apply {
        background = roundedRect(
            context = context,
            fillColor = BRAND,
            strokeColor = BRAND_DARK,
            cornerDp = 11f,
            strokeDp = 1f,
        )
        addView(
            TextView(context).apply {
                text = "MF"
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                includeFontPadding = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /** 创建工具面板的装饰拖柄，不绑定任何地图操作。 */
    fun buildSheetHandle(context: Context): View = FrameLayout(context).apply {
        addView(
            View(context).apply {
                background = roundedRect(
                    context = context,
                    fillColor = Color.rgb(203, 213, 225),
                    cornerDp = 2f,
                    strokeDp = 0f,
                )
            },
            FrameLayout.LayoutParams(dp(context, 36), dp(context, 4), Gravity.CENTER),
        )
    }

    /** 创建弹窗分节卡片，统一内边距、背景和边框。 */
    fun dialogSectionCard(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        background = roundedRect(
            context = context,
            fillColor = SURFACE_ELEVATED,
            strokeColor = BORDER,
            cornerDp = 12f,
            strokeDp = 1f,
        )
    }
}
