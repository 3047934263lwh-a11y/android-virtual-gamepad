package com.vgamepad

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import kotlin.math.max

/**
 * visionOS 风格毛玻璃的底层实现：把目标视图画成缩小快照，再做多次盒式模糊，
 * 最后由覆盖在同层的 ImageView 拉伸回全屏。GPU 无关，全 API 版本可用。
 *
 * 之所以不直接用 [android.graphics.RenderEffect]：它要求 API 31+，而本项目 minSdk 24；
 * 且 RenderEffect 只能作用于当前 View 自身，做不到"模糊后面的内容"。
 */
object GlassBlur {

    /** 抓取视图快照；[scale] 越大图越小、模糊越省。 */
    fun snapshot(v: View, scale: Int = 4): Bitmap? {
        val w = v.width
        val h = v.height
        if (w <= 0 || h <= 0) return null
        val bw = max(1, w / scale)
        val bh = max(1, h / scale)
        return try {
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(bw / w.toFloat(), bh / h.toFloat())
            v.draw(c)
            bmp
        } catch (e: Throwable) {
            null
        }
    }

    /** 三次盒式模糊近似高斯；[radius] 以缩小后的像素计。 */
    fun blur(src: Bitmap, radius: Int = 5, passes: Int = 3): Bitmap {
        var cur = src
        repeat(passes) {
            cur = boxPass(cur, radius)
        }
        return cur
    }

    private fun boxPass(src: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return src
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val tmp = IntArray(w * h)
        val div = radius * 2 + 1

        // 横向
        for (y in 0 until h) {
            val row = y * w
            var r = 0; var g = 0; var b = 0; var a = 0
            for (i in -radius..radius) {
                val p = px[row + i.coerceIn(0, w - 1)]
                a += p ushr 24 and 0xFF
                r += p shr 16 and 0xFF
                g += p shr 8 and 0xFF
                b += p and 0xFF
            }
            for (x in 0 until w) {
                tmp[row + x] = (a / div shl 24) or (r / div shl 16) or (g / div shl 8) or (b / div)
                val out = px[row + (x - radius).coerceIn(0, w - 1)]
                val inP = px[row + (x + radius + 1).coerceIn(0, w - 1)]
                a += (inP ushr 24 and 0xFF) - (out ushr 24 and 0xFF)
                r += (inP shr 16 and 0xFF) - (out shr 16 and 0xFF)
                g += (inP shr 8 and 0xFF) - (out shr 8 and 0xFF)
                b += (inP and 0xFF) - (out and 0xFF)
            }
        }

        // 纵向
        for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0; var a = 0
            for (i in -radius..radius) {
                val p = tmp[i.coerceIn(0, h - 1) * w + x]
                a += p ushr 24 and 0xFF
                r += p shr 16 and 0xFF
                g += p shr 8 and 0xFF
                b += p and 0xFF
            }
            for (y in 0 until h) {
                px[y * w + x] = (a / div shl 24) or (r / div shl 16) or (g / div shl 8) or (b / div)
                val out = tmp[(y - radius).coerceIn(0, h - 1) * w + x]
                val inP = tmp[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                a += (inP ushr 24 and 0xFF) - (out ushr 24 and 0xFF)
                r += (inP shr 16 and 0xFF) - (out shr 16 and 0xFF)
                g += (inP shr 8 and 0xFF) - (out shr 8 and 0xFF)
                b += (inP and 0xFF) - (out and 0xFF)
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        if (out !== src) src.recycle()
        return out
    }
}
