package com.vgamepad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * 设置面板内「上下边缘」的苹果风格毛玻璃条。
 *
 * 原理：面板弹出前截取游戏视图，降采样模糊后作为底图；条带按自身在屏幕上的
 * 位置从底图中取样（保证模糊纹理与背景连续），先叠一层面板底色着色（毛玻璃
 * 质感与面板表面一致），再用垂直渐变做 alpha 蒙版——贴面板边缘一侧 100%
 * 不透明，向面板内部渐隐到 0，形成 iOS 弹层上下边缘的「越靠边越模糊」效果。
 *
 * Android 12+ 额外叠加 RenderEffect 高斯模糊，低版本靠降采样近似。
 */
class BackdropBlurView(
    context: Context,
    /** 已模糊的整屏底图（半分辨率） */
    private val blurred: Bitmap?,
    /** 本条带在底图中取样的上边界（占底图高度的比例） */
    private val srcTopFrac: Float,
    /** 取样下边界比例 */
    private val srcBottomFrac: Float,
    /** true = 顶部条（边缘在上侧）；false = 底部条（边缘在下侧） */
    private val edgeTop: Boolean,
    /** 面板底色着色（含 alpha，≈0xE6），让模糊带与面板表面同色系 */
    private val tint: Int = 0xE6FFFFFF.toInt()
) : View(context) {

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = RectF()

    init {
        // DST_IN 离屏合成走软件层最稳
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        if (android.os.Build.VERSION.SDK_INT >= 31 && blurred != null) {
            setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    22f, 22f, Shader.TileMode.CLAMP
                )
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = blurred ?: return
        if (width == 0 || height == 0 || bmp.width == 0 || bmp.height == 0) return
        val h = bmp.height
        src.set(
            0,
            (srcTopFrac * h).toInt().coerceIn(0, h),
            bmp.width,
            (srcBottomFrac * h).toInt().coerceIn(0, h)
        )
        if (src.height() == 0) return
        dst.set(0f, 0f, width.toFloat(), height.toFloat())

        val sc = canvas.saveLayer(null, null)
        canvas.drawBitmap(bmp, src, dst, bmpPaint)
        // 面板底色着色：压住模糊图的游戏色彩，只留一点透出的模糊纹理
        tintPaint.color = tint
        canvas.drawRect(dst, tintPaint)
        // 蒙版：贴边一侧 100%，向面板内部渐隐到 0（过渡区占条带高度的 70%）
        val opaque = 0xFFFFFFFF.toInt()
        val clear = 0x00FFFFFF
        maskPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            if (edgeTop) intArrayOf(opaque, clear) else intArrayOf(clear, opaque),
            if (edgeTop) floatArrayOf(0f, 1f) else floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawRect(dst, maskPaint)
        maskPaint.xfermode = null
        maskPaint.shader = null
        canvas.restoreToCount(sc)
    }
}
