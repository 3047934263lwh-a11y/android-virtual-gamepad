package com.vgamepad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * 设置面板的「上下框」苹果风格毛玻璃条。
 *
 * 原理：把面板弹出前的整屏内容截图降采样模糊后作为底图，
 * 再用垂直渐变做 alpha 蒙版——屏幕上 / 下边缘完全不透明（100%），
 * 向中间渐隐到 0，形成 iOS 弹层边缘的「越靠边越模糊」的框式效果。
 *
 * Android 12+ 额外叠加 RenderEffect 高斯模糊，低版本靠降采样近似。
 */
class BackdropBlurView(
    context: Context,
    /** 已模糊处理的整屏底图（可按视图尺寸拉伸） */
    private val blurred: Bitmap?
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    init {
        // ComposeShader 的 DST_IN 合成在部分硬件加速管线上有兼容问题，走软件层最稳
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        // API 31+：在降采样模糊之上再叠加真实高斯，边缘更接近 iOS 原生质感
        if (android.os.Build.VERSION.SDK_INT >= 31 && blurred != null) {
            setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    26f, 26f, Shader.TileMode.CLAMP
                )
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = blurred ?: return
        if (width == 0 || height == 0) return
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val bmpShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        // 边缘 100% -> 靠内 20% 处渐隐到 0：上下各留 1/5 高度做「0→100%」过渡
        val fade = 0.20f
        val mask = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(0xFFFFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF, 0xFFFFFFFF.toInt()),
            floatArrayOf(0f, fade, 1f - fade, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = ComposeShader(bmpShader, mask, PorterDuff.Mode.DST_IN)
        canvas.drawRect(rect, paint)
        paint.shader = null
    }
}
