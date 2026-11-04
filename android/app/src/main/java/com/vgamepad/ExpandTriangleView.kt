package com.vgamepad

import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * Material 风格的展开指示三角。
 *
 * 取代原先的文本字符 ▸ / ▾：改为按 dp 精确控制比例、圆角收边的等腰三角形，
 * 配色取自主题的次级文字色，并可在两态之间插值。
 * 收起时尖端朝右；展开时由外部动画把 [rotation] 从 0° 平滑转到 90° 变为朝下，
 * 与内容区的高度动画共用同一条时间轴，因此两者始终同步。
 */
class ExpandTriangleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val triangle = Path()

    init {
        paint.color = ContextCompat.getColor(context, R.color.text_secondary)
    }

    /** 供下拉动画按进度插值颜色：收起偏次级灰，展开渐进到主文字色。 */
    fun setTint(color: Int) {
        if (paint.color != color) {
            paint.color = color
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val s = min(w, h).toFloat()
        if (s <= 0f) return
        val cx = w / 2f
        val cy = h / 2f

        // 底边在左、尖端在右的等腰三角形；四周留白，避免顶到图标框
        val halfHeight = s * 0.265f
        val backX = cx - s * 0.165f
        val tipX = cx + s * 0.215f

        triangle.reset()
        triangle.moveTo(backX, cy - halfHeight)
        triangle.lineTo(tipX, cy)
        triangle.lineTo(backX, cy + halfHeight)
        triangle.close()

        // 圆角收边：比纯尖角更柔和，接近 Material 图标的几何处理
        paint.pathEffect = CornerPathEffect(s * 0.075f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(triangle, paint)
    }
}
