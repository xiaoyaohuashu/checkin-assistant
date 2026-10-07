package com.checkinassistant

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import androidx.core.view.WindowCompat
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * v8: 视觉基建 —— 与 Web 原型 (/root/资料/ui) 1:1 对齐的安卓等价实现。
 *
 * 色值取自 app/globals.css 的 :root OKLCH 变量（已换算为 sRGB）与各组件内的硬编码色：
 *   --background      oklch(0.97 0.015 250) → #EEF6FF
 *   --foreground      oklch(0.22 0.03 260)  → #121B29
 *   --primary         oklch(0.58 0.20 262)  → #3271EE
 *   --muted-foreground oklch(0.55 0.03 258) → #677283
 * 图标为 lucide 矢量路径（res/drawable/ic_*.xml），尺寸/线宽与原型一致。
 */
object Ui {

    // ── 设计令牌 ──────────────────────────────────────────────
    val PRIMARY = Color.rgb(0x32, 0x71, 0xEE)
    val PAGE_BG = Color.rgb(0xEE, 0xF6, 0xFF)
    val TEXT_MAIN = Color.rgb(0x12, 0x1B, 0x29)
    val TEXT_SUB = Color.rgb(0x67, 0x72, 0x83)
    val CARD_BG = Color.WHITE
    val HAIRLINE = Color.argb(13, 0, 0, 0) // black/5
    val DIVIDER = Color.argb(13, 0, 0, 0) // bg-black/[0.05]
    val CHEVRON = Color.rgb(0xC3, 0xCA, 0xD6)
    val NAV_INACTIVE = Color.rgb(0x9A, 0xA4, 0xB4)
    val TOGGLE_ON = Color.rgb(0x1F, 0xBF, 0x6A)
    val TOGGLE_OFF = Color.rgb(0xD5, 0xDA, 0xE2)
    val GREEN_OK = Color.rgb(0x1A, 0xA8, 0x63)
    val GREEN_OK_BG = Color.rgb(0xE2, 0xF6, 0xEC)
    val SHIELD_OFF_BG = Color.rgb(0xEE, 0xF2, 0xFB)
    val SHIELD_OFF_FG = Color.rgb(0x8F, 0x9B, 0xB3)
    /** 统计/日历行的浅灰底与灰字（原型 #f7f9fc / #f0f2f6 / #8b95a7）。 */
    val ROW_BG = Color.rgb(0xF7, 0xF9, 0xFC)
    val CHIP_BG = Color.rgb(0xF0, 0xF2, 0xF6)
    val CHIP_FG = Color.rgb(0x8B, 0x95, 0xA7)
    val CAL_DAY_BG = Color.rgb(0xF4, 0xF6, 0xFA)
    val CAL_DAY_FG = Color.rgb(0x59, 0x63, 0x7A)
    val CAL_SELECTED_BG = Color.rgb(0xE8, 0xF0, 0xFF)
    val CAL_NAV = Color.rgb(0x98, 0xA2, 0xB4)
    val DOT_RED = Color.rgb(0xF0, 0x52, 0x6A)
    val DANGER = Color.rgb(0xE5, 0x48, 0x4D)
    val DANGER_BG = Color.rgb(0xFD, 0xEC, 0xEC)
    /** 顶部插画遮罩用的品牌蓝（原型渐变硬编码 #2f6bf3 → #3f77f4）。 */
    val HERO_BLUE = 0x2F6BF3
    val HERO_BLUE_2 = 0x3F77F4

    /** 原型 font-bold (700)。 */
    val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    /** 原型 font-semibold (600)：安卓无 600，Roboto Medium (500) 最接近。 */
    val SEMIBOLD: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** 图标配色调性（原型 types.ts 的 tintStyles）。bg=圆形底色，fg=图标色。 */
    enum class Tint(val bg: Int, val fg: Int) {
        BLUE(Color.rgb(0xE8, 0xF0, 0xFF), Color.rgb(0x2F, 0x6B, 0xF3)),
        GREEN(Color.rgb(0xE2, 0xF6, 0xEC), Color.rgb(0x1A, 0xA8, 0x63)),
        PURPLE(Color.rgb(0xEF, 0xEA, 0xFE), Color.rgb(0x7A, 0x5C, 0xF0)),
        AMBER(Color.rgb(0xFD, 0xF1, 0xDC), Color.rgb(0xDD, 0x8C, 0x22)),
        SLATE(Color.rgb(0xEE, 0xF1, 0xF6), Color.rgb(0x97, 0xA2, 0xB4)),
    }

    /**
     * 顶部插画图标框专用（原型 header 内图标 strokeWidth 2.4，比列表里的 2.1 更粗）。
     * 与底部导航选中态复用同一组矢量文件。
     */
    val HEADER_ICON_MAP_PIN = R.drawable.ic_nav_map_pin_active
    val HEADER_ICON_HISTORY = R.drawable.ic_nav_history_active
    val HEADER_ICON_SETTINGS = R.drawable.ic_nav_settings_active

    // ── 插画资源与原始宽高比（高度 ÷ 宽度）────────────────────
    // 用比例而不是固定高度，保证图片按原比例完整显示、不被拉伸变形。
    /** 顶部插画 hero_top.png（4096×1056）。三页共用。 */
    val HERO_IMAGE = R.drawable.hero_top
    const val HERO_ASPECT = 1056f / 4096f

    /** 首页底部宣传图 banner_bottom.png（4608×864），文案已印在图上。 */
    val BANNER_IMAGE = R.drawable.banner_bottom
    const val BANNER_ASPECT = 864f / 4608f

    // ── 尺寸 ─────────────────────────────────────────────────

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun dpf(context: Context, v: Float): Float = v * context.resources.displayMetrics.density

    /** 状态栏高度；非沉浸式窗口下由系统负责避让，返回 0 即可。 */
    fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    /**
     * 状态栏图标明暗：插画位于状态栏下方时用浅色图标（原型效果），
     * 页面滚过插画后翻成深色图标，否则浅色页面底上会看不见。
     */
    fun darkStatusBarIcons(activity: Activity, dark: Boolean) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .isAppearanceLightStatusBars = dark
    }

    /** 按滚动位置自动切换状态栏图标明暗；[heroHeader] 为顶部插画所在视图。 */
    fun trackStatusBarIcons(activity: Activity, scroll: ScrollView, heroHeader: View) {
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val threshold = (heroHeader.height - statusBarHeight(scroll.context)).coerceAtLeast(0)
            darkStatusBarIcons(activity, scrollY > threshold)
        }
    }

    // ── Drawable ────────────────────────────────────────────

    fun rounded(color: Int, radiusDp: Int, context: Context): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpf(context, radiusDp.toFloat())
            setColor(color)
        }

    /** 圆角 + 1px 描边（原型 border-black/5 之类的细边）。 */
    fun roundedStroked(color: Int, radiusDp: Int, strokeDp: Int, strokeColor: Int, context: Context): GradientDrawable =
        rounded(color, radiusDp, context).apply { setStroke(dp(context, strokeDp), strokeColor) }

    fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** 左向渐变（原型 bg-gradient-to-r）。 */
    fun horizontalGradient(colors: IntArray): GradientDrawable =
        GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            this.colors = colors
        }

    /** 顶部插画遮罩：from-[#2f6bf3]/80 via-[#3f77f4]/35 to-transparent。 */
    fun heroScrim(): GradientDrawable = horizontalGradient(
        intArrayOf(
            (0xCC shl 24) or HERO_BLUE,
            (0x59 shl 24) or HERO_BLUE_2,
            0x00 shl 24
        )
    )

    /** 完全透明的遮罩层：图片自带文案与底色时不需要任何渐变。 */
    fun noScrim(): Drawable = ColorDrawable(Color.TRANSPARENT)

    /**
     * 插画背景图层：[插画(cover)] + [渐变遮罩]，直接设为容器 background。
     *
     * 原型里 Hero / banner 的 <img> 是 `absolute inset-0`（脱离文档流），
     * 容器高度只由前景内容决定。安卓若把 MATCH_PARENT 的 ImageView 放进
     * wrap_content 的容器，它会按图片自身比例撑高父容器——这两张图都是
     * 1024×1024，于是 Hero 会被撑成一个整屏宽的方块。因此改成背景图层，
     * 图片完全不参与测量。
     */
    fun imageLayers(context: Context, imageRes: Int, scrim: Drawable): Drawable {
        val layers = mutableListOf<Drawable>()
        bitmapOf(context, imageRes)?.let { layers.add(CenterCropBitmapDrawable(it)) }
        layers.add(scrim)
        return LayerDrawable(layers.toTypedArray())
    }

    private val bitmapCache = HashMap<Int, Bitmap>()

    /** 同一位图只解码一次（Hero 在三页复用）。 */
    private fun bitmapOf(context: Context, resId: Int): Bitmap? = synchronized(bitmapCache) {
        bitmapCache[resId] ?: BitmapFactory.decodeResource(context.resources, resId)?.also { bitmapCache[resId] = it }
    }

    /** 给任意 View 一个圆角轮廓，使 elevation 阴影跟随圆角。 */
    fun roundOutline(view: View, radiusPx: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
    }

    /** 把颜色按 ratio 混向白色，得到原型那种浅色图标底（如 #2f6bf3 → #e8f0ff）。 */
    fun paleOf(color: Int, ratio: Float = 0.88f): Int = Color.rgb(
        (Color.red(color) * (1 - ratio) + 255 * ratio).toInt().coerceIn(0, 255),
        (Color.green(color) * (1 - ratio) + 255 * ratio).toInt().coerceIn(0, 255),
        (Color.blue(color) * (1 - ratio) + 255 * ratio).toInt().coerceIn(0, 255)
    )

    // ── 文本 ─────────────────────────────────────────────────

    /**
     * 统一文本构件：原型里 font-bold / font-semibold 与固定 px 字号。
     * includeFontPadding=false 让行盒贴近 Web 的排版，避免安卓额外的上下留白。
     */
    fun text(
        context: Context,
        sizeSp: Float,
        color: Int,
        weight: Typeface = Typeface.DEFAULT,
        text: String? = null
    ): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = weight
        includeFontPadding = false
        text?.let { setText(it) }
    }

    /** 分组标题（原型 text-[13px] font-semibold text-muted-foreground）。 */
    fun groupLabel(context: Context, label: String): TextView =
        text(context, 13f, TEXT_SUB, SEMIBOLD, label)

    /** 右侧 chevron：仅跳转新页面/展开详情的行显示（原型 ui.tsx Chevron，20px / #c3cad6）。 */
    fun chevron(context: Context, sizeDp: Int = 20): ImageView =
        icon(context, R.drawable.ic_chevron_right, sizeDp, CHEVRON)

    // ── 图标 ─────────────────────────────────────────────────

    /** lucide 矢量图标；颜色通过 ColorFilter 上色（等同原型 currentColor）。 */
    fun icon(context: Context, resId: Int, sizeDp: Int, color: Int): ImageView =
        ImageView(context).apply {
            setImageResource(resId)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(color)
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }

    /**
     * 圆形浅底 + 居中矢量图标（原型 ui.tsx TintedIcon）。
     * size: "md"(44dp 圆 / 22px 图标，列表默认) 或 "sm"(36dp / 18px，历史详情行)。
     */
    fun tintedIcon(context: Context, resId: Int, tint: Tint, small: Boolean = false): FrameLayout {
        val glyph = if (small) 18 else 22 // 圆底尺寸由调用方的 LayoutParams 决定
        return FrameLayout(context).apply {
            background = circle(tint.bg)
            addView(
                icon(context, resId, glyph, tint.fg),
                FrameLayout.LayoutParams(dp(context, glyph), dp(context, glyph), Gravity.CENTER)
            )
        }
    }

    /**
     * 同 TintedIcon，但底色/图标色由提醒点自身的存储颜色推导。
     *
     * 尺寸由本函数直接写进 layoutParams（[boxDp] 圆底直径 / [glyphDp] 图标边长 /
     * [rightMarginDp] 与右侧文字的间距），调用方只 addView 即可——
     * 历史页统计行用它把行高从 44dp 压到 32dp。
     */
    fun tintedIconOf(
        context: Context,
        resId: Int,
        color: Int,
        boxDp: Int = 44,
        glyphDp: Int = 22,
        rightMarginDp: Int = 0
    ): FrameLayout = FrameLayout(context).apply {
        background = circle(paleOf(color))
        layoutParams = LinearLayout.LayoutParams(dp(context, boxDp), dp(context, boxDp))
            .apply { rightMargin = dp(context, rightMarginDp) }
        addView(
            icon(context, resId, glyphDp, color),
            FrameLayout.LayoutParams(dp(context, glyphDp), dp(context, glyphDp), Gravity.CENTER)
        )
    }

    // ── 容器 ─────────────────────────────────────────────────

    /** 卡片（原型 rounded-3xl bg-white shadow-sm shadow-black/5 + p-5）。 */
    fun card(context: Context, radiusDp: Int = 22, paddingDp: Int = 20, elevationDp: Float = 1f): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD_BG, radiusDp, context)
            setPadding(dp(context, paddingDp), dp(context, paddingDp), dp(context, paddingDp), dp(context, paddingDp))
            elevation = dpf(context, elevationDp)
            roundOutline(this, dpf(context, radiusDp.toFloat()))
        }

    /** 胶囊标签（原型 rounded-full px-2.5 py-1 text-[12px]）。 */
    fun pill(context: Context, label: String, bg: Int, fg: Int, bold: Boolean = false): TextView =
        text(context, 12f, fg, if (bold) SEMIBOLD else Typeface.DEFAULT, label).apply {
            gravity = Gravity.CENTER
            background = rounded(bg, 20, context)
            setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
        }

    /**
     * 顶部插画 Header（原型 page-header.tsx）：city-train-hero.png 铺底 + 蓝色渐变遮罩
     * + 40dp 圆角图标框 + 24sp 标题 + 13sp 副标题，右上角可选胶囊按钮。
     * 三个主页面共用同一结构。
     */
    class PageHeader(val root: FrameLayout, val row: LinearLayout)

    fun pageHeader(
        context: Context,
        iconRes: Int,
        title: String,
        subtitle: String,
        action: View? = null
    ): PageHeader {
        // 插画 + 遮罩是背景图层（不参与测量）；高度 = max(pt + 前景内容 + pb, 宽度 × 图片比)。
        // 比例下限保证插画按原比例完整显示（cover 不裁切、不变形），内容更高时以内容为准。
        val root = AspectFrameLayout(context, HERO_ASPECT).apply {
            background = imageLayers(context, HERO_IMAGE, heroScrim())
        }

        // v10: 上下留白 20/24 → 12/12，顶部插画整体变矮，下方内容随之上移。
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 12))
        }
        // 图标框：size-10 rounded-2xl bg-white/25，图标 22px / strokeWidth 2.4，mt-0.5
        val iconBox = FrameLayout(context).apply {
            background = rounded(Color.argb(64, 255, 255, 255), 18, context) // rounded-2xl
        }
        iconBox.addView(
            icon(context, iconRes, 22, Color.WHITE),
            FrameLayout.LayoutParams(dp(context, 22), dp(context, 22), Gravity.CENTER)
        )
        row.addView(
            iconBox,
            LinearLayout.LayoutParams(dp(context, 40), dp(context, 40)).apply { topMargin = dp(context, 2); rightMargin = dp(context, 12) }
        )

        // leading-tight / 继承的 1.5 —— Header 的总高度由这两行的行盒决定
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            text(context, 24f, Color.WHITE, BOLD, title).tight().leading(1.25f),
            LinearLayout.LayoutParams(-2, -2)
        )
        texts.addView(
            text(context, 13f, Color.argb(230, 255, 255, 255), SEMIBOLD, subtitle).leading(1.5f),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(context, 4) }
        )
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // 右上角胶囊（原型 HeaderPillButton：bg-white/90 rounded-full px-3 py-1.5 text-[13px] text-primary）
        action?.let {
            // 原型 row 的 gap-3 + 胶囊自身的 mt-1
            row.addView(it, LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = dp(context, 4)
                leftMargin = dp(context, 12)
            })
        }

        root.addView(row, FrameLayout.LayoutParams(-1, -2))
        return PageHeader(root, row)
    }

    /** 右上角月份胶囊内容（文字 + 向下 chevron），供首页/历史页复用。 */
    fun headerPill(context: Context, label: String, chevronRes: Int = R.drawable.ic_chevron_down): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(230, 255, 255, 255), 20, context)
            setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6))
            addView(
                text(context, 13f, PRIMARY, SEMIBOLD, label),
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(context, 4) }
            )
            addView(
                icon(context, chevronRes, 15, PRIMARY),
                LinearLayout.LayoutParams(dp(context, 15), dp(context, 15))
            )
        }

    /** 主按钮（原型：primary 底、白字、rounded-2xl、居中）。 */
    fun primaryButton(
        context: Context,
        label: String,
        heightDp: Int = 56,
        textSizeSp: Float = 16f,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = rounded(PRIMARY, 18, context) // rounded-2xl
        isClickable = true
        setOnClickListener { onClick() }
        addView(
            text(context, textSizeSp, Color.WHITE, SEMIBOLD, label).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2)
        )
        minimumHeight = dp(context, heightDp)
    }
}

/**
 * 高度 = max(内容高度, 宽度 × [aspect]) 的容器，用来承载「背景插画 + 叠加前景」的顶部区域。
 *
 * 为什么需要它：插画必须是背景层（原型里 `<img>` 是 absolute inset-0），容器高度不能
 * 由图片决定，否则 1024×1024 的图会被撑成整屏方块。但容器比例若与图片差太多，
 * cover 又会把画面裁掉一大块。这里以图片原始比例作为高度下限，两个问题一起解决：
 *   · 内容更高 → 用内容高度（cover 裁切，不拉伸）
 *   · 图片比例更高 → 用图片高度（正好完整显示，零裁切）
 */
class AspectFrameLayout(context: Context, private val aspect: Float) : FrameLayout(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (measuredWidth <= 0) return
        val ratioHeight = (measuredWidth * aspect).toInt()
        if (measuredHeight < ratioHeight) setMeasuredDimension(measuredWidth, ratioHeight)
    }
}

/** 原型 tracking-tight（-0.025em）用于各级标题。 */
fun TextView.tight(): TextView = apply { letterSpacing = -0.025f } // tracking-tight

/**
 * 原型 CSS 的行高倍数（leading-tight=1.25、leading-snug=1.375、leading-relaxed=1.625、
 * 未显式指定时继承 html 的 1.5）。
 *
 * 安卓 setLineSpacing 的 multiplier 乘的是字体 (descent-ascent)≈1.171em，不是字号，
 * 所以这里按 1.171 换算，让行盒高度与 Web 一致——它直接决定 Header 等容器的高度。
 */
fun TextView.leading(cssLineHeight: Float): TextView =
    apply { setLineSpacing(0f, cssLineHeight / 1.171f) }

/**
 * 背景插画图层：把整张图按 CENTER_CROP 铺满 bounds —— 等价 CSS `object-fit: cover`。
 *
 * 用 Drawable 而不是 ImageView，是为了让插画彻底脱离布局测量：
 * 容器高度只由叠加在上面的前景内容决定（原型里 <img> 是 absolute inset-0）。
 */
class CenterCropBitmapDrawable(private val bitmap: Bitmap) : Drawable() {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty || bitmap.isRecycled) return
        val scale = maxOf(b.width().toFloat() / bitmap.width, b.height().toFloat() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = b.left + (b.width() - w) / 2f
        val top = b.top + (b.height() - h) / 2f
        dst.set(left, top, left + w, top + h)
        canvas.drawBitmap(bitmap, null, dst, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * 提醒点卡片背景：白底圆角 + 启用时左侧 4dp 主色竖条。
 *
 * Web 原型用 `border-l-4 border-l-primary`，竖条本身会跟随 24px 圆角裁切，
 * 用 LayerDrawable 叠加无法复现，因此自绘并按圆角路径裁剪。
 */
class AccentCardDrawable(
    private val fillColor: Int,
    private val radiusPx: Float,
    private val accentColor: Int?,
    private val accentWidthPx: Float
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        rect.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
        path.reset()
        path.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        paint.color = fillColor
        canvas.drawRect(rect, paint)
        accentColor?.let {
            paint.color = it
            canvas.drawRect(rect.left, rect.top, rect.left + accentWidthPx, rect.bottom, paint)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 开关（原型 ui.tsx Toggle）：46x28 圆角轨道 + 24dp 白色圆钮，
 * 开=#1fbf6a，关=#d5dae2，钮位移 3dp ↔ 21dp。
 * 安卓自带的 SwitchCompat 外观与原型差异较大，故自绘。
 */
class ToggleView(context: Context) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val thumbShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1A000000 }

    private var fraction = 0f
    private var animator: ValueAnimator? = null

    var isChecked: Boolean = false
        private set

    /** 与原型 onChange 等价；由点击触发（不响应程序化 setChecked）。 */
    var onCheckedChange: ((Boolean) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            setChecked(!isChecked, animate = true)
            onCheckedChange?.invoke(isChecked)
        }
    }

    fun setChecked(checked: Boolean, animate: Boolean = false) {
        if (isChecked == checked) {
            if (!animate) {
                fraction = if (checked) 1f else 0f
                invalidate()
            }
            return
        }
        isChecked = checked
        animator?.cancel()
        val target = if (checked) 1f else 0f
        if (!animate) {
            fraction = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { fraction = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val w = resolveSize((46 * density).toInt(), widthMeasureSpec)
        val h = resolveSize((28 * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val trackRadius = h / 2f
        trackPaint.color = if (isChecked) Ui.TOGGLE_ON else Ui.TOGGLE_OFF
        canvas.drawRoundRect(0f, 0f, w, h, trackRadius, trackRadius, trackPaint)

        val thumbD = h - 4 * density
        val minX = 3 * density
        val maxX = w - thumbD - 1 * density
        val left = minX + (maxX - minX) * fraction
        val cy = h / 2f
        val cx = left + thumbD / 2f
        canvas.drawCircle(cx, cy + density, thumbD / 2f, thumbShadowPaint)
        canvas.drawCircle(cx, cy, thumbD / 2f, thumbPaint)
    }
}
