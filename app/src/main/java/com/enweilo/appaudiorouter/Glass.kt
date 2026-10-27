package com.enweilo.appaudiorouter

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 主題色 */
object Palette {
    val text = Color.WHITE
    val textDim = Color.argb(165, 255, 255, 255)
    val textFaint = Color.argb(110, 255, 255, 255)
    val accentA = Color.rgb(90, 200, 250)   // 天藍
    val accentB = Color.rgb(125, 92, 255)   // 紫
    val good = Color.rgb(52, 211, 153)
    val warn = Color.rgb(251, 191, 36)
    val bad = Color.rgb(248, 113, 113)
    val base = Color.rgb(9, 11, 18)
}

/**
 * 液態玻璃材質：半透明填色 + 上半部高光 + 漸層描邊（左上亮、右下暗）。
 */
class GlassDrawable(
    private val radius: Float,
    private val fillColor: Int = Color.argb(30, 255, 255, 255),
    private val strokeWidth: Float = 2f,
    private val sheenAlpha: Int = 46,
) : Drawable() {
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@GlassDrawable.strokeWidth
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rect.set(bounds)
        rect.inset(strokeWidth / 2, strokeWidth / 2)
        fill.color = fillColor
        val top = bounds.top.toFloat()
        sheen.shader = LinearGradient(
            0f, top, 0f, top + bounds.height() * 0.6f,
            Color.argb(sheenAlpha, 255, 255, 255), Color.argb(0, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
        stroke.shader = LinearGradient(
            bounds.left.toFloat(), top, bounds.right.toFloat(), bounds.bottom.toFloat(),
            intArrayOf(
                Color.argb(150, 255, 255, 255),
                Color.argb(28, 255, 255, 255),
                Color.argb(70, 255, 255, 255),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, sheen)
        canvas.drawRoundRect(rect, radius, radius, stroke)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** 主要按鈕：藍紫漸層膠囊 + 高光 */
fun accentCapsule(radius: Float, strokePx: Int): Drawable {
    val body = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Palette.accentA, Palette.accentB)
    ).apply {
        cornerRadius = radius
        setStroke(strokePx, Color.argb(110, 255, 255, 255))
    }
    val shine = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255))
    ).apply { cornerRadius = radius }
    return LayerDrawable(arrayOf(body, shine))
}

/** 按下時的水波紋，形狀跟著圓角 */
fun rippleFor(radius: Float): RippleDrawable {
    val mask = GradientDrawable().apply {
        cornerRadius = radius
        setColor(Color.WHITE)
    }
    return RippleDrawable(ColorStateList.valueOf(Color.argb(50, 255, 255, 255)), null, mask)
}

/** 慢慢飄動的彩色光暈背景，玻璃卡片疊在上面就會有通透感 */
class GlowBackground(context: Context) : View(context) {

    private class Blob(
        val color: Int,
        val x: Float, val y: Float, val r: Float,
        val speed: Float, val phase: Float,
    )

    private val blobs = listOf(
        Blob(Color.rgb(58, 123, 255), 0.15f, 0.12f, 0.75f, 1.0f, 0.0f),
        Blob(Color.rgb(150, 80, 255), 0.90f, 0.30f, 0.70f, 0.8f, 1.7f),
        Blob(Color.rgb(20, 200, 190), 0.25f, 0.70f, 0.65f, 1.2f, 3.1f),
        Blob(Color.rgb(255, 90, 160), 0.85f, 0.88f, 0.60f, 0.9f, 4.4f),
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f

    private val animator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 24000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Palette.base)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val size = min(w, h)
        for (b in blobs) {
            val a = t * b.speed + b.phase
            val cx = w * (b.x + 0.10f * sin(a))
            val cy = h * (b.y + 0.06f * cos(a * 0.8f))
            val r = size * b.r
            paint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(
                    Color.argb(150, Color.red(b.color), Color.green(b.color), Color.blue(b.color)),
                    Color.argb(0, Color.red(b.color), Color.green(b.color), Color.blue(b.color)),
                ),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r, paint)
        }
        // 上方暗化一點，讓標題文字更清楚
        canvas.drawColor(Color.argb(60, 0, 0, 0))
    }
}
