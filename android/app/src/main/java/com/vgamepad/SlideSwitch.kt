package com.vgamepad

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Uiverse「checkbox-wrapper-25」风格的双色滑块开关。
 *
 * 造型要点（与 CSS 版一致）：
 *  - 轨道由两段纯色拼成，接缝固定在正中：左段=开色、右段=关色；
 *  - 滑块宽度约为轨道的 2/3，滑到左端只露出右侧一段关色，滑到右端只露出左侧一段开色；
 *  - 滑块自带竖向渐变与下投影，轨道内壁压一圈内阴影，保持与新拟态其它控件的凹凸关系一致。
 */
class SlideSwitch @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
) : View(ctx, attrs) {

    companion object {
        private const val DURATION = 220L
        private const val KNOB_RATIO = 0.66f
    }

    /** 左半轨颜色（开启色，由外部按链路状态给） */
    var onColor: Int = 0xFF3DDC84.toInt()
        set(v) { if (field != v) { field = v; invalidate() } }

    /** 右半轨颜色（关闭色） */
    var offColor: Int = 0xFFFF6B6B.toInt()
        set(v) { if (field != v) { field = v; invalidate() } }

    private var onState = false
    private var t = 0f                       // 滑块位置 0..1
    private var anim: ValueAnimator? = null
    private var downX = 0f

    /** 程序化赋值走动画；不会回调 [onCheckedChange]，只有真实点击才会 */
    var checked: Boolean
        get() = onState
        set(v) {
            if (onState == v) return
            onState = v
            animateThumb(if (v) 1f else 0f)
        }

    var onCheckedChange: ((Boolean) -> Unit)? = null

    private val track = RectF()
    private val knob = RectF()
    private val shadow = RectF()
    private val clip = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = true
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dp(76f).toInt(), widthMeasureSpec)
        val h = resolveSize(dp(26f).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f
        val inset = dp(1f)

        track.set(inset, inset, w - inset, h - inset)

        // ---- 轨道：左开色 / 右关色，接缝固定在正中 ----
        clip.reset()
        clip.addRoundRect(track, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        fill.shader = null
        fill.style = Paint.Style.FILL
        fill.color = onColor
        canvas.drawRect(track.left, track.top, w / 2f, track.bottom, fill)
        fill.color = offColor
        canvas.drawRect(w / 2f, track.top, track.right, track.bottom, fill)
        // 顶部压暗 + 底部微亮，做出凹槽感
        fill.shader = LinearGradient(
            0f, track.top, 0f, track.bottom,
            intArrayOf(0x40000000, 0x00000000, 0x1AFFFFFF),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(track, fill)
        fill.shader = null
        canvas.restore()

        // 内阴影描边
        inner.strokeWidth = dp(1.2f)
        inner.color = 0x33000000
        canvas.drawRoundRect(track, r, r, inner)

        // ---- 滑块 ----
        val knobW = w * KNOB_RATIO
        val knobH = h - inset * 2
        val travel = track.width() - knobW
        val left = track.left + travel * t
        knob.set(left, track.top, left + knobW, track.top + knobH)
        val kr = knobH / 2f

        // 投影：左右各内收 1.5dp、只从底部露出，
        // 否则同尺寸的圆角矩形会在滑块四周包一圈灰边，看起来像"没擦干净的脏边"
        fill.shader = null
        fill.color = 0x2A000000
        shadow.set(
            knob.left + dp(1.6f), knob.top + dp(1.8f),
            knob.right - dp(1.6f), knob.bottom + dp(2.4f)
        )
        canvas.drawRoundRect(shadow, kr, kr, fill)

        // 本体：纯白 → 极浅灰，靠渐变表现圆柱受光，不加任何横向线条
        knobPaint.shader = LinearGradient(
            0f, knob.top, 0f, knob.bottom,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFF3F4F5.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(knob, kr, kr, knobPaint)
        knobPaint.shader = null
        // 细内描边收边：让白块边界干净利落，而不是靠阴影糊出轮廓
        inner.strokeWidth = dp(0.6f)
        inner.color = 0x12000000
        canvas.drawRoundRect(knob, kr, kr, inner)
    }

    /** 首帧直接定位，不做动画（用于恢复已连接状态） */
    fun setCheckedImmediate(v: Boolean) {
        anim?.cancel()
        onState = v
        t = if (v) 1f else 0f
        invalidate()
    }

    private fun animateThumb(target: Float) {
        anim?.cancel()
        val from = t
        if (from == target) {
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(from, target).apply {
            duration = DURATION
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                return true
            }
            MotionEvent.ACTION_UP -> {
                val moved = kotlin.math.abs(event.x - downX) > dp(8f)
                if (!moved && event.x >= 0f && event.x <= width) {
                    checked = !checked
                    onCheckedChange?.invoke(checked)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }
}
