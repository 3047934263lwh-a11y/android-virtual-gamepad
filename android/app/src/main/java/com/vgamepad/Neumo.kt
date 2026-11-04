package com.vgamepad

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * Neumorphism 渲染引擎（配合 Material Design 3 令牌）。
 *
 * 性能约定（直接决定输入延迟）：
 *  - 所有 Shader 按「尺寸 + 按压档位 + 主题版本」缓存，稳定状态下每帧零分配；
 *  - 圆角矩形的柔和投影用一次性烘焙的 Bitmap（BlurMaskFilter 只在离屏生成时用），
 *    运行时只是 drawBitmap；
 *  - 不使用 saveLayer，不使用实时模糊；
 *  - 绘制只读状态，不改任何输入状态。
 */
class Neumo(private val density: Float) {

    private var colors: VgColors? = null
    private var rev = 1

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shaderCache = HashMap<Long, Shader>(64)
    private val shadowCache = HashMap<Long, Bitmap>(16)
    private val clipRect = RectF()
    private val clipPath = Path()
    private val shadowDst = RectF()

    companion object {
        private const val TYPE_SPHERE = 1
        private const val TYPE_RECT = 2
        private const val TYPE_INSET = 3
        private const val TYPE_BG = 4
        private const val TYPE_INNER_TOP = 5
        private const val TYPE_INNER_BOTTOM = 6
        private const val PRESS_STEPS = 12f
        /**
         * 柔影位图烘焙上限（像素）。单键可放大到 500%，摇杆直径会到 ~2000px，
         * 若按 1:1 烘焙，单个位图就要 16MB 且每帧首次绘制会明显卡顿。
         * 超过该尺寸时按比例缩小烘焙、绘制时再放大——柔影本身是模糊的，视觉上无差别。
         */
        private const val SHADOW_BAKE_MAX = 1024f
    }

    fun dip(v: Float): Float = v * density

    fun setColors(c: VgColors) {
        if (colors == c) return
        colors = c
        rev++
        shaderCache.clear()
        recycleShadows()
    }

    fun current(): VgColors = colors ?: VgColors(
        dark = true, bg = 0xFF1B1E23.toInt(), bgTop = 0xFF20242A.toInt(),
        surface = 0xFF22262C.toInt(), surfaceHigh = 0xFF292E35.toInt(),
        onSurface = 0xFFE4E8EF.toInt(), onSurfaceVar = 0xFF9AA4B2.toInt(),
        outline = 0xFF363C45.toInt(), accent = 0xFF9DB0D6.toInt(),
        accentInk = 0xFFC6D5F0.toInt(), onAccent = 0xFF16181C.toInt(),
        accentSoft = 0x339DB0D6.toInt(), ok = 0xFF6FD79A.toInt(), err = 0xFFFF8A82.toInt(),
        shadowDark = 0x99000000.toInt(), shadowLight = 0x1AFFFFFF.toInt(),
        faceA = 0xFF7FD08A.toInt(), faceB = 0xFFF08E86.toInt(),
        faceX = 0xFF86B4EE.toInt(), faceY = 0xFFE7C877.toInt(),
    )

    private fun col(): VgColors = current()

    private fun recycleShadows() {
        shadowCache.values.forEach { if (!it.isRecycled) it.recycle() }
        shadowCache.clear()
        trigShaderCache.clear()
    }

    // ---------------- 背景 ----------------

    private var bgShader: Shader? = null
    private var bgRadial: Shader? = null
    private var bgW = 0
    private var bgH = 0
    private var bgQ = -1

    // 主题色切换时的背景过渡：从旧配色渐变到新配色
    private var bgFromTop = 0
    private var bgFromBg = 0
    private var bgFromAccent = 0
    private var bgT = 1f

    /** 画布底色：低饱和纵向渐变 + 极淡的同色系环境光。 */
    fun background(canvas: Canvas, w: Float, h: Float) {
        val c = col()
        val q = (bgT * 60).toInt()
        if (bgShader == null || bgW != w.toInt() || bgH != h.toInt() || bgQ != q) {
            // 过渡期间把旧背景色与新背景色按进度混合，避免整屏"啪"地跳变
            val top = if (bgT >= 1f) c.bgTop else mix(bgFromTop, c.bgTop, bgT)
            val bot = if (bgT >= 1f) c.bg else mix(bgFromBg, c.bg, bgT)
            val acc = if (bgT >= 1f) c.accent else mix(bgFromAccent, c.accent, bgT)
            bgShader = LinearGradient(0f, 0f, 0f, h, top, bot, Shader.TileMode.CLAMP)
            bgRadial = RadialGradient(
                w * 0.18f, h * 0.08f, w * 0.60f,
                alphaOf(acc, 0.055f), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            bgW = w.toInt()
            bgH = h.toInt()
            bgQ = q
        }
        fill.shader = bgShader
        fill.alpha = 255
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = bgRadial
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
    }

    // ---------------- 凸起：圆形 ----------------

    /**
     * 凸起圆钮。
     * @param press 0=完全抬起，1=完全按下（下沉 + 内凹）
     * @param alpha 入场/自定义透明度
     * @param ring  激活时的强调色描边（可选）
     */
    fun raisedCircle(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        press: Float, alpha: Float, ring: Int? = null,
    ) {
        if (r <= 0f || alpha <= 0.004f) return
        val c = col()
        val p = press.coerceIn(0f, 1f)
        val a = alpha.coerceIn(0f, 1f)
        val sink = dip(1.6f) * p
        val cy2 = cy + sink
        val r2 = r * (1f - 0.014f * p)
        val ps = (p * PRESS_STEPS).toInt()

        // 1) 右下投影：径向渐变环，扩散到 1.45r
        val shadowA = a * (0.85f - 0.45f * p)
        shadowRing(canvas, cx + r2 * 0.06f, cy2 + r2 * 0.11f, r2, c.shadowDark, 1.28f, 0.62f, shadowA)
        // 2) 球面本体
        //    注意：Shader 的坐标是「画布坐标」而不是「相对控件的坐标」。
        //    控件散布在屏幕各处，若不先平移，渐变会全部被 CLAMP 成同一个端色，
        //    表现就是所有按钮发平、摇杆变成一个灰饼。
        fill.shader = sphereShader(r2, ps)
        fill.alpha = (255 * a).toInt()
        canvas.save()
        canvas.translate(cx, cy2)
        canvas.drawCircle(0f, 0f, r2, fill)
        canvas.restore()
        fill.shader = null
        fill.alpha = 255

        // 3) 顶缘细高光（体内，不外扩）
        stroke.color = c.withAlpha(c.shadowLight, a * (0.62f - 0.38f * p))
        stroke.strokeWidth = dip(1.2f)
        clipRect.set(cx - r2 + stroke.strokeWidth * 0.6f, cy2 - r2 + stroke.strokeWidth * 0.6f,
            cx + r2 - stroke.strokeWidth * 0.6f, cy2 + r2 - stroke.strokeWidth * 0.6f)
        canvas.drawArc(clipRect, 200f, 140f, false, stroke)

        // 4) 按压内凹：边缘向内收的暗影
        if (p > 0.04f) {
            canvas.save()
            clipPath.reset()
            clipPath.addCircle(cx, cy2, r2, Path.Direction.CW)
            canvas.clipPath(clipPath)
            fill.shader = innerShadowShader(r2, ps)
            fill.alpha = (255 * a * p).toInt()
            canvas.translate(cx - r2 * 0.06f, cy2 - r2 * 0.10f)
            canvas.drawCircle(0f, 0f, r2 * 1.02f, fill)
            canvas.restore()
            fill.shader = null
            fill.alpha = 255
        }

        // 5) 激活描边
        if (ring != null) {
            stroke.color = c.withAlpha(ring, a * 0.85f)
            stroke.strokeWidth = dip(1.6f)
            canvas.drawCircle(cx, cy2, r2 - dip(1.2f), stroke)
        }
    }

    // ---------------- 凹槽：圆形（摇杆井） ----------------

    fun insetCircle(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float, depth: Float = 1f) {
        if (r <= 0f || alpha <= 0.004f) return
        val c = col()
        val a = alpha.coerceIn(0f, 1f)
        val d = depth.coerceIn(0f, 1.5f)

        // 槽底（比背景略深，形成"挖进去"的第一层）
        fill.shader = null
        fill.color = darken(c.bg, 0.024f * d)
        fill.alpha = (255 * a).toInt()
        canvas.drawCircle(cx, cy, r, fill)
        fill.alpha = 255

        // 内阴影：偏移到左上，边缘最重
        canvas.save()
        clipPath.reset()
        clipPath.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.clipPath(clipPath)
        fill.shader = innerShadowShader(r, (PRESS_STEPS * 0.6f).toInt())
        fill.alpha = (255 * a * 0.70f * d).toInt()
        canvas.translate(cx - r * 0.05f, cy - r * 0.09f)
        canvas.drawCircle(0f, 0f, r * 1.02f, fill)
        canvas.restore()
        fill.shader = null
        fill.alpha = 255

        // 底部反光：极细的亮边，交代槽口厚度
        stroke.color = c.withAlpha(c.shadowLight, a * 0.55f)
        stroke.strokeWidth = dip(1.1f)
        clipRect.set(cx - r + stroke.strokeWidth, cy - r + stroke.strokeWidth,
            cx + r - stroke.strokeWidth, cy + r - stroke.strokeWidth)
        canvas.drawArc(clipRect, 20f, 140f, false, stroke)
    }

    // ---------------- 凸起：圆角矩形 ----------------

    fun raisedRect(
        canvas: Canvas, rect: RectF, radius: Float, press: Float, alpha: Float,
        tint: Float = 0f,
    ) {
        if (rect.width() <= 0f || rect.height() <= 0f || alpha <= 0.004f) return
        val c = col()
        val p = press.coerceIn(0f, 1f)
        val a = alpha.coerceIn(0f, 1f)
        val sink = dip(1.4f) * p
        val r2 = radius * (1f - 0.10f * p)

        // 柔和投影（预烘焙 Bitmap）
        val blur = dip(6f)
        // 关键：阴影位图只按「静止尺寸」缓存。若把按压后的半径算进 key，
        // 按压动画的每一帧都会重新烘焙一次模糊位图，直接掉帧。
        val key = shadowKey(rect.width(), rect.height(), radius, blur, 0x01)
        val bmp = shadowBitmap(key, rect.width(), rect.height(), radius, blur)
        imgPaint.alpha = (255 * a * (0.95f - 0.45f * p)).toInt().coerceIn(0, 255)
        // 留白必须与烘焙时一致，且足够大：模糊尾部若被位图边界裁断，
        // 就会在按钮四周露出一圈直角的"方框"。
        val pad = shadowPad(blur)
        val sc = shadowScale(rect.width(), rect.height(), blur)
        val shadowTop = rect.top - pad + sink + dip(1.6f)
        if (sc >= 1f) {
            canvas.drawBitmap(bmp, rect.left - pad, shadowTop, imgPaint)
        } else {
            // 缩小烘焙的位图按原始留白等比放大回去，避免边缘被裁成直角的"方框"
            shadowDst.set(
                rect.left - pad, shadowTop,
                rect.left - pad + (rect.width() + pad * 2f),
                shadowTop + (rect.height() + pad * 2f)
            )
            canvas.drawBitmap(bmp, null, shadowDst, imgPaint)
        }
        imgPaint.alpha = 255

        // 本体：纵向渐变（上亮下暗）
        clipRect.set(rect)
        clipRect.offset(0f, sink)
        fill.shader = rectBodyShader(rect.width(), rect.height(), (p * PRESS_STEPS).toInt(), tint)
        fill.alpha = (255 * a).toInt()
        canvas.save()
        canvas.translate(clipRect.left, clipRect.top)
        canvas.drawRoundRect(0f, 0f, clipRect.width(), clipRect.height(), r2, r2, fill)
        canvas.restore()
        fill.shader = null
        fill.alpha = 255

        // 顶缘高光
        stroke.color = c.withAlpha(c.shadowLight, a * (0.75f - 0.4f * p))
        stroke.strokeWidth = dip(1.1f)
        clipRect.set(rect)
        clipRect.offset(0f, sink)
        clipRect.inset(stroke.strokeWidth * 0.5f, stroke.strokeWidth * 0.5f)
        canvas.drawArc(clipRect, 195f, 150f, false, stroke)

        // 按压内凹
        if (p > 0.04f) {
            drawInnerShadowRect(canvas, rect, r2, sink, a * p)
        }
    }

    // ---------------- 凹槽：圆角矩形（扳机行程槽等） ----------------

    fun insetRect(canvas: Canvas, rect: RectF, radius: Float, alpha: Float) {
        if (rect.width() <= 0f || rect.height() <= 0f || alpha <= 0.004f) return
        val c = col()
        val a = alpha.coerceIn(0f, 1f)
        fill.shader = null
        fill.color = darken(c.bg, 0.028f)
        fill.alpha = (255 * a).toInt()
        canvas.drawRoundRect(rect, radius, radius, fill)
        fill.alpha = 255
        drawInnerShadowRect(canvas, rect, radius, 0f, a * 0.9f)
    }

    /** 按下时的内阴影：顶缘暗、底缘亮，模拟"陷进面板"。 */
    private fun drawInnerShadowRect(
        canvas: Canvas, rect: RectF, radius: Float, sink: Float, alpha: Float,
    ) {
        if (alpha <= 0.004f) return
        val a = alpha.coerceIn(0f, 1f)
        canvas.save()
        clipRect.set(rect)
        clipRect.offset(0f, sink)
        // 必须按「圆角路径」裁剪：用 clipRect 会把矩形四角也涂上内阴影，
        // 胶囊按钮/竖向轨道按下时就会露出一圈直角的"方块"。
        clipPath.reset()
        clipPath.addRoundRect(clipRect, radius.coerceAtLeast(0f), radius.coerceAtLeast(0f),
            Path.Direction.CW)
        canvas.clipPath(clipPath)

        val h = clipRect.height()
        val w = clipRect.width()
        // 平移到控件原点，渐变改用局部坐标 → 可以按 (高度档, 主题版本) 缓存，
        // 稳定状态下每帧不再新建 LinearGradient（原实现每帧 new 2 个）
        canvas.translate(clipRect.left, clipRect.top)
        // 顶缘暗影（端色满 alpha，绘制强度由 paint.alpha 控制，与缓存解耦）
        fill.shader = innerRectShader(h, TYPE_INNER_TOP)
        fill.alpha = (255 * a).toInt()
        canvas.drawRect(0f, 0f, w, h, fill)
        // 底缘反光
        fill.shader = innerRectShader(h, TYPE_INNER_BOTTOM)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        fill.alpha = 255
        canvas.restore()
    }

    /** 内阴影渐变（局部坐标）：端色不含 alpha，强度统一由 fill.alpha 乘出。
     *  key 只依赖高度档与主题版本 → 每个控件形状一条缓存项。 */
    private fun innerRectShader(h: Float, type: Int): Shader {
        val k = key(type, (h * 4).toInt().coerceAtLeast(1), 0, 0)
        shaderCache[k]?.let { return it }
        val c = col()
        val sh: Shader = if (type == TYPE_INNER_TOP) {
            LinearGradient(
                0f, 0f, 0f, h * 0.30f,
                c.withAlpha(0xFF000000.toInt(), 0.20f),
                Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f, h, 0f, h - h * 0.26f,
                c.withAlpha(c.shadowLight, 0.42f),
                Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        if (shaderCache.size > 220) shaderCache.clear()
        shaderCache[k] = sh
        return sh
    }

    // ---------------- Shader / 缓存 ----------------

    private fun key(type: Int, a: Int, b: Int, cs: Int): Long {
        var k = type.toLong()
        k = k * 100003L + (a.coerceAtLeast(0) % 100003)
        k = k * 100003L + (b.coerceAtLeast(0) % 100003)
        k = k * 100003L + cs
        k = k * 100003L + rev
        return k
    }

    private fun sphereShader(r: Float, ps: Int): Shader {
        val k = key(TYPE_SPHERE, (r * 4).toInt(), ps, 0)
        shaderCache[k]?.let { return it }
        val c = col()
        val t = ps / PRESS_STEPS
        // 抬起：左上亮；按下：整体压暗、亮部收缩
        val hi = mix(lighten(c.bg, if (c.dark) 0.27f else 0.115f), c.bg, 0.18f * t)
        val mid = mix(c.bg, darken(c.bg, 0.10f), 0.40f * t)
        val lo = darken(c.bg, if (c.dark) 0.075f + 0.075f * t else 0.045f + 0.070f * t)
        val sh = RadialGradient(
            -r * 0.42f, -r * 0.50f, r * 1.62f,
            intArrayOf(hi, mid, lo), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        if (shaderCache.size > 220) shaderCache.clear()
        shaderCache[k] = sh
        return sh
    }

    private fun innerShadowShader(r: Float, ps: Int): Shader {
        val k = key(TYPE_INSET, (r * 4).toInt(), ps, 0)
        shaderCache[k]?.let { return it }
        val c = col()
        // 内阴影的中心要朝「光源侧」（右下）偏移：这样离中心最远的是左上沿，
        // 暗部才会落在左上，符合"光从左上打来、凹槽被上沿遮挡"的物理直觉。
        // 半径需覆盖中心到最远点的距离（r + 偏移），否则 CLAMP 会把整片涂黑。
        val dark = c.withAlpha(0xFF000000.toInt(), if (c.dark) 0.40f else 0.18f)
        val sh = RadialGradient(
            r * 0.10f, r * 0.14f, r * 1.24f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, dark),
            floatArrayOf(0f, 0.80f, 1f), Shader.TileMode.CLAMP
        )
        if (shaderCache.size > 220) shaderCache.clear()
        shaderCache[k] = sh
        return sh
    }

    private fun rectBodyShader(w: Float, h: Float, ps: Int, tint: Float): Shader {
        val k = key(TYPE_RECT, w.toInt() + (tint * 1000).toInt(), h.toInt(), ps)
        shaderCache[k]?.let { return it }
        val c = col()
        val t = ps / PRESS_STEPS
        val base = if (tint <= 0f) c.bg else lighten(c.bg, tint)
        val top = mix(lighten(base, 0.14f), base, t * 0.55f)
        val bottom = darken(base, 0.045f + 0.065f * t)
        val sh = LinearGradient(
            0f, 0f, 0f, h, intArrayOf(top, base, bottom),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP
        )
        if (shaderCache.size > 220) shaderCache.clear()
        shaderCache[k] = sh
        return sh
    }

    /** 圆形投影环：中心实体（被本体遮住）+ 外缘渐隐 */
    private fun shadowRing(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        color: Int, spread: Float, solidStop: Float, alpha: Float,
    ) {
        if (alpha <= 0.004f) return
        val k = key(9, (r * 4).toInt(), (spread * 100).toInt(), color)
        val sh = shaderCache[k] ?: RadialGradient(
            0f, 0f, r * spread,
            intArrayOf(color, color, Color.TRANSPARENT),
            floatArrayOf(0f, solidStop, 1f), Shader.TileMode.CLAMP
        ).also {
            if (shaderCache.size > 220) shaderCache.clear()
            shaderCache[k] = it
        }
        fill.shader = sh
        fill.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawCircle(0f, 0f, r * spread, fill)
        canvas.restore()
        fill.shader = null
        fill.alpha = 255
    }

    /**
     * 阴影位图留白。BlurMaskFilter 的高斯尾部约为 2~3 倍半径，
     * 留白不足会把尾部裁成一条直边，视觉上就是按钮周围多出一个矩形框。
     */
    private fun shadowPad(blur: Float): Float = blur * 4.0f

    /** 超大控件的柔影烘焙缩放系数（0,1]：超过 [SHADOW_BAKE_MAX] 时按比例缩小烘焙。 */
    private fun shadowScale(w: Float, h: Float, blur: Float): Float {
        val full = (if (w > h) w else h) + shadowPad(blur) * 2f
        return if (full <= SHADOW_BAKE_MAX) 1f else SHADOW_BAKE_MAX / full
    }

    private fun shadowKey(w: Float, h: Float, r: Float, blur: Float, seed: Int): Long =
        key(5, w.toInt() * 8 + h.toInt(), r.toInt() * 4 + seed, (blur * 10).toInt())
    /** 圆角矩形柔影：离屏用 BlurMaskFilter 烘焙一次，运行时直接贴图。 */
    private fun shadowBitmap(
        key: Long, w: Float, h: Float, radius: Float, blur: Float,
    ): Bitmap {
        shadowCache[key]?.let { if (!it.isRecycled) return it }
        val pad = shadowPad(blur)
        val sc = shadowScale(w, h, blur)
        // 尺寸上限：布局数据异常（s 极大）时避免在 onDraw 里申请巨型位图直接 OOM
        val bw = ((w + pad * 2) * sc).toInt().coerceIn(4, 2048)
        val bh = ((h + pad * 2) * sc).toInt().coerceIn(4, 2048)
        val bmp = try {
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            // 极端情况下（低端机内存紧张）退回 1x1，放弃阴影也不崩
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        val bc = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = col().shadowDark
            maskFilter = BlurMaskFilter(blur * sc, BlurMaskFilter.Blur.NORMAL)
        }
        bc.drawRoundRect(
            pad * sc, pad * sc, pad * sc + w * sc, pad * sc + h * sc,
            radius * sc, radius * sc, p,
        )
        val prev = shadowCache.put(key, bmp)
        if (prev != null && prev !== bmp && !prev.isRecycled) prev.recycle()
        if (shadowCache.size > 28) {
            val it = shadowCache.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key != key) {
                    if (!e.value.isRecycled) e.value.recycle()
                    it.remove()
                }
            }
        }
        return bmp
    }

    // ---------------- 颜色工具 ----------------

    private fun lighten(c: Int, t: Float): Int = mix(c, 0xFFFFFFFF.toInt(), t.coerceIn(0f, 1f))

    private fun darken(c: Int, t: Float): Int = mix(c, 0xFF000000.toInt(), t.coerceIn(0f, 1f))

    private fun mix(c1: Int, c2: Int, t0: Float): Int {
        val t = t0.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t).toInt().coerceIn(0, 255),
            (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t).toInt().coerceIn(0, 255),
            (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t).toInt().coerceIn(0, 255),
        )
    }

    private val trigShaderCache = HashMap<Long, Shader>(24)

    /** 扳机行程填充渐变（按高度与行程档位缓存，避免每帧分配） */
    fun triggerFill(h: Float, frac: Float, color: Int, alpha: Float): Shader {
        val step = (frac.coerceIn(0f, 1f) * 24).toInt()
        val k = key(7, (h * 2).toInt(), step, color)
        trigShaderCache[k]?.let { return it }
        val a0 = (alpha * 0.72f).coerceIn(0f, 1f)
        val c0 = (color and 0x00FFFFFF) or ((((color ushr 24) * a0).toInt().coerceIn(0,255)) shl 24)
        val c1 = (color and 0x00FFFFFF) or ((((color ushr 24) * alpha).toInt().coerceIn(0,255)) shl 24)
        val sh = LinearGradient(0f, 0f, 0f, h, c0, c1, Shader.TileMode.CLAMP)
        if (trigShaderCache.size > 48) trigShaderCache.clear()
        trigShaderCache[k] = sh
        return sh
    }

    /** 开始一次背景过渡：记录旧配色，进度从 0 起。 */
    fun beginBackgroundTransition(from: VgColors) {
        bgFromTop = from.bgTop
        bgFromBg = from.bg
        bgFromAccent = from.accent
        bgT = 0f
        bgShader = null
        bgQ = -1
    }

    /** 推进过渡进度（0..1）；按 1/60 量化，避免每帧重建 Shader。 */
    fun setBackgroundProgress(t: Float) {
        val v = t.coerceIn(0f, 1f)
        if ((v * 60).toInt() != (bgT * 60).toInt()) {
            bgT = v
            bgShader = null
        }
    }

    fun endBackgroundTransition() {
        bgT = 1f
        bgShader = null
        bgQ = -1
    }

    private fun alphaOf(color: Int, a: Float): Int {
        val al = (a.coerceIn(0f, 1f) * 255).toInt()
        return (al shl 24) or (color and 0x00FFFFFF)
    }

    /** 供外部计算可读文字色 */
    fun readableOn(bg: Int): Int {
        val l = (0.299f * Color.red(bg) + 0.587f * Color.green(bg) + 0.114f * Color.blue(bg)) / 255f
        return if (l > 0.55f) 0xFF1E2228.toInt() else 0xFFF2F5F9.toInt()
    }

    fun dispose() {
        recycleShadows()
        bgShader = null
        bgRadial = null
        bgW = 0
        bgH = 0
        shaderCache.clear()
    }
}
