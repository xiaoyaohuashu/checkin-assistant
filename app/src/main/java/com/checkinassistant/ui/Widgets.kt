package com.checkinassistant.ui

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

// 自绘控件和两个文字排版小工具。原先和 Ui 放在同一个文件里，这里只是拆出来，内容没动。

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
