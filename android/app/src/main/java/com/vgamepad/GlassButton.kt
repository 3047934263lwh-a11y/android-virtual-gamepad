package com.vgamepad

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat

/**
 * Uiverse「liquid glass button」风格的玻璃按钮。
 *
 * 与 CSS 版的对应关系：
 *  - `background: linear-gradient(-75deg, 5%/20%/5% 白)` → 斜向高光渐变填充；
 *  - `::after` 的 conic-gradient 遮罩描边 → [SweepGradient] 描边的锥形亮暗环；
 *  - `inset 0 .125em 阴影` → 顶部内侧压暗 + 底部内侧提亮；
 *  - `box-shadow 0 .25em` → 底部柔和外投影（多层圆角矩形近似，避免软件层）；
 *  - `:hover { transform: scale(.975) }` → 按下时整体缩到 0.975 并加深内阴影。
 *
 * 文字仍由 TextView 负责（字距、行高、省略号等都直接复用系统实现）。
 */
class GlassButton @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(ctx, attrs) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val inner = RectF()

    private var press = 0f
    private var pressAnim: ValueAnimator? = null

    private val rFillA = ContextCompat.getColor(ctx, R.color.glass_btn_fill_a)
    private val rFillB = ContextCompat.getColor(ctx, R.color.glass_btn_fill_b)
    private val rEdge = ContextCompat.getColor(ctx, R.color.glass_btn_edge)
    private val rText = ContextCompat.getColor(ctx, R.color.text_primary)

    init {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        includeFontPadding = false
        letterSpacing = -0.02f
        setTextColor(rText)
        setShadowLayer(0.8f, 0f, 0.8f, 0x16000000)
        background = null
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val inset = dp(1.4f)
        rect.set(inset, inset, w - inset, h - inset)
        val r = rect.height() / 2f

        // ---- 外投影：越靠外越淡，纯圆角矩形叠加，避免离屏模糊 ----
        shade.shader = null
        val depth = (1f - 0.45f * press) * dp(2.2f)
        for (i in 3 downTo 1) {
            val e = dp(0.9f) * i
            shade.color = Color.argb((10 - i * 2).coerceAtLeast(3), 0, 0, 0)
            inner.set(rect.left - e * 0.5f, rect.top - e * 0.3f + depth,
                      rect.right + e * 0.5f, rect.bottom + depth)
            canvas.drawRoundRect(inner, r, r, shade)
        }

        // ---- 玻璃填充：-75° 斜向高光（左下暗、右上亮、再回落）----
        fill.shader = LinearGradient(
            rect.left, rect.bottom, rect.right, rect.top,
            intArrayOf(rFillA, rFillA, rFillB, rFillA, rFillA),
            floatArrayOf(0f, 0.34f, 0.5f, 0.66f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, r, r, fill)
        fill.shader = null

        // ---- 顶部内阴影 + 底部内高光 ----
        canvas.save()
        val clipPath = android.graphics.Path()
        clipPath.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
        canvas.clipPath(clipPath)

        shade.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(
                Color.argb((26 + 34 * press).toInt(), 0, 0, 0),
                Color.TRANSPARENT,
                Color.argb(40, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, shade)
        shade.shader = null
        canvas.restore()

        // ---- 锥形描边：亮暗环（CSS 的 conic-gradient 边框）----
        val cx = rect.centerX()
        val cy = rect.centerY()
        ring.strokeWidth = dp(1.2f)
        ring.shader = SweepGradient(
            cx, cy,
            intArrayOf(rEdge, Color.TRANSPARENT, rEdge, Color.TRANSPARENT, rEdge),
            floatArrayOf(0f, 0.16f, 0.5f, 0.84f, 1f)
        )
        canvas.drawRoundRect(rect, r, r, ring)
        ring.shader = null
        // 统一的细亮边，保证锥形暗角之间仍有连续的玻璃边缘
        ring.strokeWidth = dp(0.8f)
        ring.color = ContextCompat.getColor(context, R.color.glass_btn_rim)
        canvas.drawRoundRect(rect, r, r, ring)

        super.onDraw(canvas)
    }

    /** 悬停/按下的缩小动画（对应 CSS 的 `transform: scale(.975)`） */
    private fun animatePress(target: Float) {
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(press, target).apply {
            duration = 180L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                press = it.animatedValue as Float
                scaleX = 1f - 0.025f * press
                scaleY = 1f - 0.025f * press
                invalidate()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animatePress(1f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animatePress(0f)
        }
        return super.onTouchEvent(event)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
    }
}
