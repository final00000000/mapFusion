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
    /** 消息强调文字/圆点的 32 位 ARGB 色值，不表示实际地图或导航引擎状态。 */
    val accentColor: Int,
    /** 消息徽标底色，32 位 ARGB；与 badgeLabel 共同表达视觉语义。 */
    val badgeBgColor: Int,
    /** 结果卡片背景色，32 位 ARGB；每次渲染创建独立背景 Drawable。 */
    val cardBgColor: Int,
    /** 结果卡片边框色，32 位 ARGB；只影响外观，不参与业务判断。 */
    val cardBorderColor: Int,
    /** 徽标显示文字；中性“就绪”等文案只是消息标签，不能作为 SDK 就绪凭据。 */
    val badgeLabel: String,
) {
    /** 普通说明/等待状态的中性色板，徽标默认“就绪”，不代表地图服务已启用。 */
    NEUTRAL(
        accentColor = DemoTheme.TEXT_SECONDARY,
        badgeBgColor = DemoTheme.SURFACE_SUBTLE,
        cardBgColor = Color.rgb(248, 250, 252),
        cardBorderColor = DemoTheme.BORDER,
        badgeLabel = "就绪",
    ),
    /** 请求、授权等待和运行状态提示，使用品牌色强调，不表示请求成功。 */
    INFO(
        accentColor = DemoTheme.BRAND,
        badgeBgColor = DemoTheme.BRAND_SOFT,
        cardBgColor = Color.rgb(240, 253, 250),
        cardBorderColor = Color.rgb(153, 246, 228),
        badgeLabel = "进行中",
    ),
    /** 当前结果成功的绿色色板；与导航是否到达或是否活跃分别归约。 */
    SUCCESS(
        accentColor = DemoTheme.SUCCESS,
        badgeBgColor = DemoTheme.SUCCESS_SOFT,
        cardBgColor = Color.rgb(240, 253, 244),
        cardBorderColor = Color.rgb(187, 247, 208),
        badgeLabel = "已完成",
    ),
    /** 错误、权限不足或服务不可用的红色色板；不直接改变引擎状态。 */
    ERROR(
        accentColor = DemoTheme.ERROR,
        badgeBgColor = DemoTheme.ERROR_SOFT,
        cardBgColor = Color.rgb(254, 242, 242),
        cardBorderColor = Color.rgb(254, 202, 202),
        badgeLabel = "异常",
    ),
}

/** 两页共享的视觉组件与样式工厂，只处理外观，不承接地图业务或会话生命周期。 */
internal object DemoTheme {
    /** 页面空白区域底色，32 位 ARGB；不用于原生地图瓦片。 */
    val PAGE_BG = Color.rgb(241, 245, 249)          // Slate 100
    /** 主卡片/头部的基础白色表面，32 位 ARGB。 */
    val SURFACE = Color.WHITE
    /** 悬浮岛/浮层卡片的高透白表面，32 位 ARGB。 */
    val SURFACE_GLASS = Color.argb(248, 255, 255, 255)
    /** 次级卡片的浅色表面，32 位 ARGB；名称不表示实际 elevation 高度。 */
    val SURFACE_ELEVATED = Color.rgb(248, 250, 252) // Slate 50
    /** 轻量分组/徽标的柔和背景色，32 位 ARGB。 */
    val SURFACE_SUBTLE = Color.rgb(241, 245, 249)   // Slate 100
    /** 按压态背景/水波纹色，32 位 ARGB；与正常表面颜色分别构造。 */
    val SURFACE_PRESSED = Color.rgb(226, 232, 240)  // Slate 200
    /** 本地未启用地图容器的占位底色，32 位 ARGB，不依赖厂商初始化。 */
    val MAP_PLACEHOLDER = Color.rgb(226, 232, 240)

    /** 普通卡片与分隔边界的浅色描边，32 位 ARGB。 */
    val BORDER = Color.rgb(226, 232, 240)           // Slate 200
    /** 选择器等需要更明显边界的描边色，32 位 ARGB。 */
    val BORDER_STRONG = Color.rgb(203, 213, 225)    // Slate 300
    /** 细分隔线颜色，32 位 ARGB；线宽由对应控件布局决定。 */
    val DIVIDER = Color.rgb(236, 241, 246)

    /** 主标题/正文的高对比文字色，32 位 ARGB。 */
    val TEXT_PRIMARY = Color.rgb(15, 23, 42)        // Slate 900
    /** 状态摘要和次要信息文字色，32 位 ARGB。 */
    val TEXT_SECONDARY = Color.rgb(71, 85, 105)     // Slate 600
    /** 分节标题等弱强调文字色，32 位 ARGB。 */
    val TEXT_MUTED = Color.rgb(100, 116, 139)       // Slate 500

    /** 品牌强调色，32 位 ARGB；用于选中态、徽标与普通信息强调。 */
    val BRAND = Color.rgb(13, 148, 136)             // Teal 600
    /** 品牌深色，32 位 ARGB；用于按压态、描边和强调文字。 */
    val BRAND_DARK = Color.rgb(15, 118, 110)        // Teal 700
    /** 品牌柔和底色，32 位 ARGB；用于选中/信息卡片背景。 */
    val BRAND_SOFT = Color.rgb(204, 251, 241)       // Teal 100
    /** 品牌系描边色，32 位 ARGB，不代表实时路况等级。 */
    val BRAND_BORDER = Color.rgb(94, 234, 212)      // Teal 300

    /** 成功消息强调色，32 位 ARGB；只表达结果语义。 */
    val SUCCESS = Color.rgb(22, 163, 74)            // Green 600
    /** 成功消息的柔和背景色，32 位 ARGB。 */
    val SUCCESS_SOFT = Color.rgb(220, 252, 231)     // Green 100

    /** 警示信息强调色，32 位 ARGB；不是速度限制或道路风险数据。 */
    val WARNING = Color.rgb(217, 119, 6)
    /** 警示信息的柔和背景色，32 位 ARGB。 */
    val WARNING_SOFT = Color.rgb(254, 243, 199)

    /** 失败/权限不足等错误文字和控件强调色，32 位 ARGB。 */
    val ERROR = Color.rgb(220, 38, 38)              // Red 600
    /** 危险操作按压/强强调使用的深红色，32 位 ARGB。 */
    val ERROR_DARK = Color.rgb(185, 28, 28)         // Red 700
    /** 错误卡片/危险操作的柔和红色背景，32 位 ARGB。 */
    val ERROR_SOFT = Color.rgb(254, 226, 226)       // Red 100
    /** 错误卡片/危险操作的边框色，32 位 ARGB。 */
    val ERROR_BORDER = Color.rgb(252, 165, 165)     // Red 300

    /** 路线演示蓝色，32 位 ARGB；不表示真实交通状态。 */
    val ROUTE_BLUE = Color.rgb(37, 99, 235)

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
        cornerDp: Float = 12f,
        strokeDp: Float = 1f,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpF(context, cornerDp)
        setColor(fillColor)
        if (strokeColor != Color.TRANSPARENT && strokeDp > 0f) {
            setStroke(dp(context, strokeDp.toInt().coerceAtLeast(1)), strokeColor)
        }
    }

    /** 构造对角渐变圆角背景，用于品牌图标与视觉焦点卡片。 */
    fun gradientRect(
        context: Context,
        startColor: Int,
        endColor: Int,
        strokeColor: Int = Color.TRANSPARENT,
        cornerDp: Float = 12f,
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(startColor, endColor),
    ).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpF(context, cornerDp)
        if (strokeColor != Color.TRANSPARENT) setStroke(dp(context, 1), strokeColor)
    }

    /** 构造仅顶部圆角的底部工具面板背景，不修改系统窗口形状。 */
    fun topSheetBackground(
        context: Context,
        fillColor: Int = SURFACE_GLASS,
        strokeColor: Int = BORDER,
        cornerDp: Float = 24f,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        val r = dpF(context, cornerDp)
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        setColor(fillColor)
        setStroke(dp(context, 1), strokeColor)
    }

    /** 构造四边圆角的悬浮面板卡片背景，用于地图上层浮岛。 */
    fun floatingCardBackground(
        context: Context,
        fillColor: Int = SURFACE_GLASS,
        strokeColor: Int = BORDER,
        cornerDp: Float = 22f,
    ): GradientDrawable = roundedRect(context, fillColor, strokeColor, cornerDp, 1f)

    /** 组合正常、按压和水波纹状态，调用者只提供颜色与尺寸。 */
    fun interactiveSurface(
        context: Context,
        fillColor: Int,
        pressedColor: Int,
        strokeColor: Int = Color.TRANSPARENT,
        cornerDp: Float = 12f,
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
        val tag = " ${tone.badgeLabel}  "
        builder.append(tag)
        builder.setSpan(
            ForegroundColorSpan(tone.accentColor),
            0,
            tag.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            tag.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            RelativeSizeSpan(0.84f),
            0,
            tag.length,
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
                    StatusTone.ERROR -> ERROR_DARK
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
                        StatusTone.ERROR -> ERROR
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

    /** 创建渐变品牌徽标，装饰文字不作为无障碍操作入口。 */
    fun buildBrandBadge(context: Context): View = FrameLayout(context).apply {
        background = gradientRect(
            context = context,
            startColor = Color.rgb(20, 184, 166),
            endColor = BRAND_DARK,
            strokeColor = BRAND_DARK,
            cornerDp = 12f,
        )
        elevation = dpF(context, 2f)
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
                    cornerDp = 99f,
                    strokeDp = 0f,
                )
            },
            FrameLayout.LayoutParams(dp(context, 38), dp(context, 4), Gravity.CENTER),
        )
    }

    /** 创建弹窗分节卡片，统一内边距、背景和边框。 */
    fun dialogSectionCard(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
        background = roundedRect(
            context = context,
            fillColor = SURFACE_ELEVATED,
            strokeColor = BORDER,
            cornerDp = 14f,
            strokeDp = 1f,
        )
    }
}
