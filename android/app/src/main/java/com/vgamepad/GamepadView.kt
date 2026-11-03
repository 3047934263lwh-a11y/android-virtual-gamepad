package com.vgamepad

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * 全屏自绘虚拟手柄 —— Ultra-Premium Dark Glassmorphism
 *
 * 视觉体系：Smoked Obsidian Glass（烟熏黑曜石玻璃）+
 *           Layered Frosted Glass（多层磨砂玻璃）+ Precision Industrial Design。
 *
 *  - 背景：黑曜石深空渐变 + 极淡冷色环境光斑（供玻璃折射采样）
 *  - 每个控件 = 独立悬浮玻璃体：
 *      外层烟熏玻璃（裁剪采样模糊背景 + 烟熏染色）→ 间隙（透出模糊背景）
 *      → 内芯层（第二层磨砂玻璃 + 钛金属细节线）→ 边缘折射高光/暗折
 *      → 低对比银灰描边 → 柔和悬浮投影 → 激活冷色辉光
 *  - 按下：内芯下沉 2dp、染色加深、辉光微亮；长按 450ms：冰川蓝环淡入
 *  - 松开：130ms 弹回。全部动画仅作用于绘制层。
 *  - 逐键自定义：位置、大小、透明度（编辑滑杆切换"大小/透明度"）。
 */
class GamepadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    val state = GamepadState()

    private companion object {
        const val Z_NONE = -1
        const val Z_LSTICK = 1
        const val Z_RSTICK = 2
        const val Z_UP = 10
        const val Z_DOWN = 11
        const val Z_LEFT = 12
        const val Z_RIGHT = 13
        const val Z_A = 20
        const val Z_B = 21
        const val Z_X = 22
        const val Z_Y = 23
        const val Z_LB = 30
        const val Z_RB = 31
        const val Z_BACK = 32
        const val Z_START = 33
        const val Z_LT = 40
        const val Z_RT = 41

        // ---- 黑曜石 / 烟熏玻璃色板 ----
        val C_BG0 = 0xFF0C0E12.toInt()         // 黑曜石上端
        val C_BG1 = 0xFF07080A.toInt()         // 黑曜石下端
        val C_SMOKE = 0x40101418.toInt()       // 烟熏石墨染色 25%
        val C_CORE = 0x30202833.toInt()        // 内芯层染色（磨砂 frost，略亮）
        val C_CORE_ON = 0x47182028.toInt()     // 按下内芯
        val C_SILVER = 0x66A8B4C4.toInt()      // 银灰描边 40%
        val C_SILVER_ON = 0x8CA8B4C4.toInt()   // 按下银边 55%
        val C_SILVER_DIM = 0x26A8B4C4.toInt()  // 内芯银线 15%
        val C_TITANIUM = 0x2E808896.toInt()    // 钛金属细节线 18%
        val C_SHEEN = 0x14FFFFFF.toInt()       // 折射高光带 8% 白
        val C_REFRACT = 0x3DFFFFFF.toInt()     // 边缘折射 24% 白
        val C_DARKFOLD = 0x30000000.toInt()    // 边缘暗折 19% 黑
        val C_INSHADOW = 0x4D000000.toInt()    // 内阴影 30% 黑
        val C_AO = 0x5E000000.toInt()          // 悬浮投影 37% 黑
        val C_WELL = 0x5510141A.toInt()        // 摇杆井烟熏底 33%
        val C_GLACIER = 0xFF6FA8DC.toInt()     // 冰川蓝（强调）
        val C_GLACIER_GLOW = 0x1F6FA8DC.toInt()// 激活冷辉光 12%
        val C_GLACIER_FILL = 0x406FA8DC.toInt()// 扳机行程 25%
        val C_A = 0xFF5CBF60.toInt()           // ABXY 字母（高对比版）
        val C_B = 0xFFE86A62.toInt()
        val C_X = 0xFF5C9BE8.toInt()
        val C_Y = 0xFFE5C05A.toInt()
        val C_DARK = 0xFF0B0D0F.toInt()
        val C_ACCENT = 0xFF6FA8DC.toInt()      // 编辑选中色（同冰川蓝）
        val C_TXT = 0xFFD8E2EC.toInt()         // 常规文字（高对比银白）
        val C_TXT_ON = 0xFFF2F7FB.toInt()      // 按下文字
        val C_TXT_DIM = 0xFF8794A3.toInt()     // 次要文字

        const val DEADZONE = 0.06f
        const val STICK_MAX = 32767f

        /**
         * 摇杆按下（L3/R3）判定：手指落点与抬起点的位移超过该值即视为"拖动摇杆"，
         * 不触发按键——避免正常推杆时误按 LS/RS。
         */
        const val STICK_TAP_SLOP_DP = 8f
        /** 双击模式的位移容差更宽松：双击是明确手势，手指轻微移动不算拖动 */
        const val STICK_TAP_SLOP_DP_DOUBLE = 14f
        /** 轻点产生的按键保持时长（毫秒），足够所有游戏在下一帧轮询到按下沿 */
        const val STICK_PULSE_MS = 180L

        /** 摇杆按下（LS/RS）触发方式：与设置面板的三个选项一一对应 */
        const val STICK_CLICK_CENTER = 0   // 轻点摇杆中心
        const val STICK_CLICK_DOUBLE = 1   // 双击摇杆区域内任意位置
        const val STICK_CLICK_ALWAYS = 2   // 只要操作摇杆就持续按下
        /** 双击判定窗口（毫秒） */
        const val DOUBLE_TAP_MS = 400L

        /** 按压手感参数 */
        const val GLASS_SINK_DP = 2.0f         // 玻璃下沉（dp）
        const val PRESS_SINK_MS = 90f          // 下沉时长
        const val RELEASE_SETTLE_MS = 130f     // 松开弹回时长

        /** 入场动效（Material motion） */
        const val INTRO_DURATION = 900L
        const val INTRO_ITEM = 400L
        const val INTRO_SHIFT = 40f

        /** 可编辑控件 key */
        val CTRL_KEYS = listOf(
            "LS", "RS", "DP", "A", "B", "X", "Y", "LB", "RB", "BACK", "START", "LT", "RT"
        )
        val CTRL_NAMES = mapOf(
            "LS" to "左摇杆", "RS" to "右摇杆", "DP" to "方向键",
            "A" to "A", "B" to "B", "X" to "X", "Y" to "Y",
            "LB" to "LB", "RB" to "RB", "BACK" to "BACK", "START" to "START",
            "LT" to "LT", "RT" to "RT"
        )
    }

    /** 单控件布局覆盖：dx/dy 位置、s 大小、a 透明度 */
    class Ctrl(var dx: Float = 0f, var dy: Float = 0f, var s: Float = 1f, var a: Float = 1f)

    private val layout = HashMap<String, Ctrl>()
    private val defCtrl = Ctrl()
    private fun ov(key: String): Ctrl = layout[key] ?: defCtrl

    // ---------- 几何 ----------
    private val lsCenter = PointF()
    private val rsCenter = PointF()
    private val dpCenter = PointF()
    private val abxyCenter = PointF()
    private val cA = PointF()
    private val cB = PointF()
    private val cX = PointF()
    private val cY = PointF()
    private val lsKnob = PointF()
    private val rsKnob = PointF()

    private var lsR = 0f
    private var rsR = 0f
    private var lsKnobR = 0f
    private var rsKnobR = 0f
    private var dpR = 0f
    private var rA = 0f
    private var rB = 0f
    private var rX = 0f
    private var rY = 0f
    private var abxySpread = 0f

    private val rectLT = RectF()
    private val rectRT = RectF()
    private val rectLB = RectF()
    private val rectRB = RectF()
    private val rectBack = RectF()
    private val rectStart = RectF()
    private val tmp = RectF()
    private val tmp2 = RectF()
    private val fullRect = RectF()

    // ---------- 画笔 ----------
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }
    private val pShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pBitmap = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = C_GLACIER_GLOW
        strokeWidth = dp(6f)
    }
    private val pEdit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
    }
    private val pEditSel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.6f)
    }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private val glassPath = Path()
    private val pointers = SparseIntArray()

    /** 按压状态跟踪：zone -> 按下/松开时刻 */
    private val zoneDownAt = SparseIntArray()
    private val zoneReleaseAt = SparseIntArray()

    // ---- 模糊背景（黑曜石环境光，供玻璃折射采样）----
    private var backdropBlur: Bitmap? = null

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun clamp(v: Float, lo: Float, hi: Float): Float = maxOf(lo, minOf(hi, v))

    /** 按系数缩放 ARGB 颜色的 alpha（保留色相）。 */
    private fun fade(argb: Int, a01: Float): Int {
        val a = ((argb ushr 24) * a01).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    private fun lerp(c1: Int, c2: Int, t: Float): Int {
        val t = clamp(t, 0f, 1f)
        val r = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t).toInt()
        val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t).toInt()
        val b = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t).toInt()
        return Color.rgb(r, g, b)
    }

    // ---------- 按压状态 ----------

    private fun pressP(zone: Int): Float {
        val now = SystemClock.uptimeMillis().toInt()
        val down = zoneDownAt.get(zone, 0)
        if (down > 0) return clamp((now - down) / PRESS_SINK_MS, 0f, 1f)
        val rel = zoneReleaseAt.get(zone, 0)
        if (rel > 0) return clamp(1f - (now - rel) / RELEASE_SETTLE_MS, 0f, 1f)
        return 0f
    }

    // ---------- 触感反馈 ----------

    var hapticsEnabled = true
    /** 触感基础强度 0..1（设置滑杆「触感强度」换算而来） */
    var hapticStrength = 0.55f
    /** 单次触感时长（毫秒） */
    var hapticDurationMs = 28
    /** 同强度下允许重复触发的最小间隔（毫秒） */
    var hapticIntervalMs = 60

    private var lastHapticAt = 0L
    private var lastHapticAmp = 0
    private var lsInDeadzone = true
    private var rsInDeadzone = true

    // ---- 摇杆按下（L3/R3）：轻点摇杆触发一次按键脉冲 ----
    /** 触发方式（0=轻点中心 / 1=双击区域内任意位置 / 2=操作摇杆即按下） */
    var stickClickMode = STICK_CLICK_CENTER
    private var lsDownX = 0f
    private var lsDownY = 0f
    private var rsDownX = 0f
    private var rsDownY = 0f
    private var lsTapOk = false
    private var rsTapOk = false
    private var lsPulseToken = 0
    private var rsPulseToken = 0
    private var lsPulseAt = 0L
    private var rsPulseAt = 0L
    /** 双击模式下上一次轻点的时刻（0 表示无待配对的首击） */
    private var lsLastTapAt = 0L
    private var rsLastTapAt = 0L

    private val vibrator: Vibrator? by lazy { obtainVibrator() }

    private fun obtainVibrator(): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: Exception) {
        null
    }

    private fun haptic(intensity: Float) {
        if (!hapticsEnabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val baseAmp = (hapticStrength.coerceIn(0f, 1f) * 255f).toInt()
        if (baseAmp <= 0) return
        val amp = (baseAmp * (0.55f + 0.45f * intensity.coerceIn(0f, 1f)))
            .toInt().coerceIn(1, 255)
        val now = SystemClock.uptimeMillis()
        if (amp == lastHapticAmp && now - lastHapticAt < hapticIntervalMs) return
        lastHapticAt = now
        lastHapticAmp = amp
        v.vibrate(VibrationEffect.createOneShot(hapticDurationMs.toLong(), amp))
    }

    // ---------- 尺寸调节 ----------

    var uiScale = 1f
        set(value) {
            field = value
            computeLayout()
            invalidate()
        }

    var stickScale = 1f
        set(value) {
            field = value
            computeLayout()
            invalidate()
        }

    var buttonScale = 1f
        set(value) {
            field = value
            computeLayout()
            invalidate()
        }

    // ---------- 布局持久化 ----------

    fun loadLayout(json: String?) {
        layout.clear()
        if (!json.isNullOrBlank()) {
            try {
                val obj = JSONObject(json)
                for (key in CTRL_KEYS) {
                    val o = obj.optJSONObject(key) ?: continue
                    layout[key] = Ctrl(
                        o.optDouble("dx", 0.0).toFloat(),
                        o.optDouble("dy", 0.0).toFloat(),
                        o.optDouble("s", 1.0).toFloat(),
                        o.optDouble("a", 1.0).toFloat().coerceIn(0.05f, 1f)
                    )
                }
            } catch (e: Exception) {
                // 坏数据按默认处理
            }
        }
        computeLayout()
        invalidate()
    }

    fun layoutJson(): String {
        val obj = JSONObject()
        for (key in CTRL_KEYS) {
            val c = ov(key)
            if (c.dx != 0f || c.dy != 0f || c.s != 1f || c.a != 1f) {
                obj.put(
                    key,
                    JSONObject().put("dx", c.dx).put("dy", c.dy)
                        .put("s", c.s).put("a", c.a)
                )
            }
        }
        return obj.toString()
    }

    fun resetLayout() {
        layout.clear()
        selectedKey = null
        computeLayout()
        invalidate()
    }

    fun setControlScale(key: String, scale: Float) {
        val c = layout.getOrPut(key) { Ctrl() }
        c.s = scale.coerceIn(0.5f, 2.0f)
        computeLayout()
        clampIntoBounds(key, c)
        computeLayout()
        invalidate()
    }

    /** 把控件矩形限制在屏幕内：允许全屏任意位置，但不允许整块拖出屏幕。 */
    private fun clampIntoBounds(key: String, c: Ctrl) {
        val W = width.toFloat()
        val H = height.toFloat()
        if (W <= 0f || H <= 0f) return
        val r = outlineOf(key)
        var fixX = 0f
        var fixY = 0f
        if (r.width() >= W) fixX = (W / 2f - r.centerX()) / W
        else if (r.left < 0f) fixX = -r.left / W
        else if (r.right > W) fixX = (W - r.right) / W
        if (r.height() >= H) fixY = (H / 2f - r.centerY()) / H
        else if (r.top < 0f) fixY = -r.top / H
        else if (r.bottom > H) fixY = (H - r.bottom) / H
        if (fixX != 0f) c.dx += fixX
        if (fixY != 0f) c.dy += fixY
    }

    fun controlScale(key: String): Float = ov(key).s

    fun setControlAlpha(key: String, a: Float) {
        val c = layout.getOrPut(key) { Ctrl() }
        c.a = a.coerceIn(0.05f, 1f)
        computeLayout()
        invalidate()
    }

    fun controlAlpha(key: String): Float = ov(key).a

    fun controlName(key: String): String = CTRL_NAMES[key] ?: key

    fun resetControl(key: String) {
        layout.remove(key)
        computeLayout()
        invalidate()
    }

    // ---------- 编辑模式 ----------

    var editMode = false
        set(value) {
            field = value
            selectedKey = null
            if (value) releaseAll()
            invalidate()
        }

    var editTarget = 0

    var selectedKey: String? = null
        private set

    var onSelectionChange: ((String?) -> Unit)? = null

    private var editLastX = 0f
    private var editLastY = 0f

    private fun zoneKey(zone: Int): String? = when (zone) {
        Z_LSTICK -> "LS"
        Z_RSTICK -> "RS"
        Z_UP, Z_DOWN, Z_LEFT, Z_RIGHT -> "DP"
        Z_A -> "A"
        Z_B -> "B"
        Z_X -> "X"
        Z_Y -> "Y"
        Z_LB -> "LB"
        Z_RB -> "RB"
        Z_BACK -> "BACK"
        Z_START -> "START"
        Z_LT -> "LT"
        Z_RT -> "RT"
        else -> null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        lsKnob.set(0f, 0f)
        rsKnob.set(0f, 0f)
        computeLayout()
        buildBackdrop(w, h)
        startIntro()
    }

    override fun onDetachedFromWindow() {
        introAnim?.cancel()
        introAnim = null
        super.onDetachedFromWindow()
    }

    private fun computeLayout() {
        val W = width.toFloat()
        val H = height.toFloat()
        if (W <= 0f || H <= 0f) return
        val base = min(W, H)

        val kBtn = (uiScale * buttonScale).coerceIn(0.45f, 2.20f)
        val kStick = (uiScale * stickScale).coerceIn(0.45f, 2.20f)

        val ls = ov("LS")
        val rs = ov("RS")
        lsR = base * 0.155f * kStick * ls.s
        rsR = base * 0.155f * kStick * rs.s
        lsKnobR = lsR * 0.42f
        rsKnobR = rsR * 0.42f
        lsCenter.set(W * 0.215f + ls.dx * W, H * 0.700f + ls.dy * H)
        rsCenter.set(W * 0.610f + rs.dx * W, H * 0.700f + rs.dy * H)

        val dp = ov("DP")
        dpR = base * 0.115f * kBtn * dp.s
        val dpBaseY = min(H * 0.365f, H * 0.700f - lsR * 1.30f - dpR - H * 0.020f)
            .coerceAtLeast(H * 0.135f + dpR + H * 0.010f)
        dpCenter.set(W * 0.205f + dp.dx * W, dpBaseY + dp.dy * H)

        abxyCenter.set(W * 0.845f, H * 0.585f)
        abxySpread = base * 0.078f * kBtn
        val a = ov("A")
        val b = ov("B")
        val x = ov("X")
        val y = ov("Y")
        rA = base * 0.052f * kBtn * a.s
        rB = base * 0.052f * kBtn * b.s
        rX = base * 0.052f * kBtn * x.s
        rY = base * 0.052f * kBtn * y.s
        cY.set(abxyCenter.x + y.dx * W, abxyCenter.y - abxySpread + y.dy * H)
        cA.set(abxyCenter.x + a.dx * W, abxyCenter.y + abxySpread + a.dy * H)
        cX.set(abxyCenter.x - abxySpread + x.dx * W, abxyCenter.y + x.dy * H)
        cB.set(abxyCenter.x + abxySpread + b.dx * W, abxyCenter.y + b.dy * H)

        val pillH = H * 0.075f * kBtn
        val pillCy = H * 0.1825f
        fun pill(key: String, cx: Float, w: Float, out: RectF) {
            val c = ov(key)
            val ww = w * c.s
            val hh = pillH * c.s
            val ccx = cx + c.dx * W
            val ccy = pillCy + c.dy * H
            out.set(ccx - ww / 2, ccy - hh / 2, ccx + ww / 2, ccy + hh / 2)
        }
        pill("LB", W * 0.225f, W * 0.180f * kBtn, rectLB)
        pill("RB", W * 0.775f, W * 0.180f * kBtn, rectRB)
        pill("BACK", W * 0.412f, W * 0.080f * kBtn, rectBack)
        pill("START", W * 0.588f, W * 0.080f * kBtn, rectStart)

        val trigW = min(W * 0.050f * kBtn, W * 0.055f)
        val trigH = H * 0.370f * kBtn
        val trigCy = H * 0.435f
        fun trig(key: String, cx: Float, out: RectF) {
            val c = ov(key)
            val ww = trigW * c.s
            val hh = trigH * c.s
            val ccx = cx + c.dx * W
            val ccy = trigCy + c.dy * H
            out.set(ccx - ww / 2, ccy - hh / 2, ccx + ww / 2, ccy + hh / 2)
        }
        trig("LT", W * 0.033f, rectLT)
        trig("RT", W * 0.967f, rectRT)
    }

    // ---------- 入场动效 ----------

    private var introT = 1f
    private var introAnim: ValueAnimator? = null
    private val introEase = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val introPop = PathInterpolator(0.3f, 1.25f, 0.5f, 1f)

    private fun startIntro() {
        introT = 0f
        introAnim?.cancel()
        introAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = INTRO_DURATION
            interpolator = LinearInterpolator()
            addUpdateListener {
                introT = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun introDelay(key: String): Float = when (key) {
        "LT" -> 0f
        "LB" -> 50f
        "LS" -> 100f
        "DP" -> 150f
        "BACK" -> 200f
        "START" -> 240f
        "RS" -> 280f
        "RB" -> 320f
        "RT" -> 360f
        "Y" -> 400f
        "X" -> 440f
        "B" -> 480f
        "A" -> 520f
        else -> 0f
    }

    private fun introOf(key: String): Pair<Float, Float> {
        val p = clamp((introT * INTRO_DURATION - introDelay(key)) / INTRO_ITEM, 0f, 1f)
        if (p <= 0f) return 0f to INTRO_SHIFT
        if (p >= 1f) return 1f to 0f
        val a01 = introEase.getInterpolation(p)
        val ty = (1f - introPop.getInterpolation(p)) * INTRO_SHIFT
        return a01 to ty
    }

    // ---------- 黑曜石环境背景（供玻璃折射采样） ----------

    private fun buildBackdrop(w: Int, h: Int) {
        backdropBlur?.recycle()
        backdropBlur = null
        if (w <= 0 || h <= 0) return

        val sw = (w / 4f).toInt().coerceAtLeast(16)
        val sh = (h / 4f).toInt().coerceAtLeast(16)
        val base = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val bc = Canvas(base)
        bc.drawRect(
            0f, 0f, sw.toFloat(), sh.toFloat(),
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, sw * 0.25f, sh.toFloat(),
                    0xFF0E1116.toInt(), 0xFF07080A.toInt(), Shader.TileMode.CLAMP
                )
            }
        )
        // 极淡冷色环境光斑（玻璃折射的色彩来源，克制、低饱和）
        fun glow(cx: Float, cy: Float, rad: Float, color: Int) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx, cy, rad, color, Color.TRANSPARENT, Shader.TileMode.CLAMP
                )
            }
            bc.drawCircle(cx, cy, rad, p)
        }
        glow(sw * 0.18f, sh * 0.12f, sw * 0.42f, 0x331B3A5C)   // 冰川蓝（极淡）
        glow(sw * 0.85f, sh * 0.90f, sw * 0.55f, 0x2A12304A)   // 冷青蓝
        glow(sw * 0.60f, sh * 0.04f, sw * 0.24f, 0x14202A38)   // 钛灰

        val s1 = Bitmap.createScaledBitmap(
            base, (sw / 3f).toInt().coerceAtLeast(4), (sh / 3f).toInt().coerceAtLeast(4), true
        )
        val s2 = Bitmap.createScaledBitmap(
            s1, (sw / 8f).toInt().coerceAtLeast(2), (sh / 8f).toInt().coerceAtLeast(2), true
        )
        if (s1 !== base) s1.recycle()
        base.recycle()
        backdropBlur = s2
    }

    // ---------- 多层烟熏玻璃渲染 ----------

    /**
     * 多层烟熏玻璃圆形：
     *   悬浮投影 → 外层烟熏玻璃（裁剪采样模糊背景 + 烟熏染色 + 上缘内阴影）
     *   → 间隙（透出模糊背景）→ 内芯层（第二层磨砂玻璃 + 钛金属细节线）
     *   → 边缘折射高光/暗折 + 银灰描边 → 激活冷辉光
     */
    private fun layeredGlassCircle(
        c: Canvas, cx: Float, cy: Float, radius: Float,
        pressP: Float, alpha01: Float, active: Boolean
    ) {
        val pa = (255f * alpha01).toInt()
        val sink = pressP * dp(GLASS_SINK_DP)
        val cyS = cy + sink * 0.6f

        // 悬浮投影（柔和环境遮蔽，按下贴近地面）
        pShadow.color = fade(C_AO, alpha01 * (1f - pressP * 0.4f))
        c.drawCircle(cx, cyS + dp(3f), radius + dp(0.5f), pShadow)

        // ---- 外层烟熏玻璃 ----
        glassPath.reset()
        glassPath.addCircle(cx, cyS, radius, Path.Direction.CW)
        c.save()
        c.clipPath(glassPath)
        pBitmap.alpha = (205f * alpha01).toInt()
        backdropBlur?.let {
            c.drawBitmap(it, null, tmp2.apply { set(0f, 0f, width.toFloat(), height.toFloat()) }, pBitmap)
        }
        c.restore()
        pFill.color = fade(C_SMOKE, alpha01)
        c.drawPath(glassPath, pFill)
        // 外层上缘内阴影
        pStroke.color = fade(C_INSHADOW, alpha01 * 0.85f)
        pStroke.strokeWidth = dp(2f)
        tmp.set(cx - radius + dp(1f), cyS - radius + dp(1f),
                cx + radius - dp(1f), cyS)
        c.drawArc(tmp, 180f, 180f, false, pStroke)
        // 外缘：低对比银灰
        pStroke.color = fade(C_SILVER, alpha01)
        pStroke.strokeWidth = dp(1f)
        c.drawPath(glassPath, pStroke)

        // ---- 间隙（透出模糊背景）+ 内芯层 ----
        val coreR = radius - dp(3.5f)
        glassPath.reset()
        glassPath.addCircle(cx, cyS, coreR, Path.Direction.CW)
        c.save()
        c.clipPath(glassPath)
        pBitmap.alpha = (110f * alpha01).toInt()
        backdropBlur?.let {
            c.drawBitmap(it, null, tmp2.apply { set(0f, 0f, width.toFloat(), height.toFloat()) }, pBitmap)
        }
        c.restore()
        pFill.color = fade(if (pressP > 0.5f) C_CORE_ON else C_CORE, alpha01)
        c.drawPath(glassPath, pFill)
        // 内芯钛金属细节环
        pStroke.color = fade(C_TITANIUM, alpha01)
        pStroke.strokeWidth = dp(0.7f)
        c.drawCircle(cx, cyS, coreR - dp(0.6f), pStroke)

        // ---- 边缘系统：折射高光（左上弧）+ 暗折（右下弧）----
        pStroke.color = fade(C_REFRACT, alpha01)
        pStroke.strokeWidth = dp(1.4f)
        tmp.set(cx - radius + dp(1.5f), cyS - radius + dp(1.5f),
                cx + radius - dp(1.5f), cyS + radius - dp(1.5f))
        c.drawArc(tmp, 150f, 120f, false, pStroke)      // 左上折射
        pStroke.color = fade(C_DARKFOLD, alpha01)
        c.drawArc(tmp, -20f, 100f, false, pStroke)      // 右下暗折

        // ---- 顶部折射高光带 ----
        pFill.color = fade(C_SHEEN, alpha01)
        c.drawCircle(cx, cyS - radius * 0.26f, radius * 0.74f, pFill)

        // ---- 状态：激活冷辉光（长按环已按需求移除） ----
        if (active) {
            pGlow.color = fade(C_GLACIER_GLOW, alpha01)
            c.drawCircle(cx, cyS, radius + dp(2.5f), pGlow)
        }
        pStroke.strokeWidth = dp(1.4f)
    }

    /** 多层烟熏玻璃圆角矩形变体。 */
    private fun layeredGlassRect(
        c: Canvas, r: RectF, rad: Float,
        pressP: Float, alpha01: Float, active: Boolean
    ) {
        val pa = (255f * alpha01).toInt()
        val sink = pressP * dp(GLASS_SINK_DP)
        val rr = RectF(r)
        rr.offset(0f, sink * 0.6f)

        pShadow.color = fade(C_AO, alpha01 * (1f - pressP * 0.4f))
        tmp.set(rr.left + dp(1f), rr.top + dp(2f), rr.right - dp(1f), rr.bottom + dp(3.5f))
        c.drawRoundRect(tmp, rad, rad, pShadow)

        // 外层烟熏玻璃
        glassPath.reset()
        glassPath.addRoundRect(rr, rad, rad, Path.Direction.CW)
        c.save()
        c.clipPath(glassPath)
        pBitmap.alpha = (205f * alpha01).toInt()
        backdropBlur?.let {
            c.drawBitmap(it, null, tmp2.apply { set(0f, 0f, width.toFloat(), height.toFloat()) }, pBitmap)
        }
        c.restore()
        pFill.color = fade(C_SMOKE, alpha01)
        c.drawPath(glassPath, pFill)
        pStroke.color = fade(C_INSHADOW, alpha01 * 0.85f)
        pStroke.strokeWidth = dp(2f)
        tmp.set(rr.left + dp(1f), rr.top + dp(1f), rr.right - dp(1f), rr.centerY())
        c.drawRoundRect(tmp, rad, rad, pStroke)
        pStroke.color = fade(C_SILVER, alpha01)
        pStroke.strokeWidth = dp(1f)
        c.drawPath(glassPath, pStroke)

        // 内芯层（内缩 3.5dp）
        val coreR = rad - dp(3f)
        tmp.set(rr.left + dp(3.5f), rr.top + dp(3.5f), rr.right - dp(3.5f), rr.bottom - dp(3.5f))
        glassPath.reset()
        glassPath.addRoundRect(tmp, coreR, coreR, Path.Direction.CW)
        c.save()
        c.clipPath(glassPath)
        pBitmap.alpha = (110f * alpha01).toInt()
        backdropBlur?.let {
            c.drawBitmap(it, null, tmp2.apply { set(0f, 0f, width.toFloat(), height.toFloat()) }, pBitmap)
        }
        c.restore()
        pFill.color = fade(if (pressP > 0.5f) C_CORE_ON else C_CORE, alpha01)
        c.drawPath(glassPath, pFill)
        pStroke.color = fade(C_TITANIUM, alpha01)
        pStroke.strokeWidth = dp(0.7f)
        c.drawPath(glassPath, pStroke)

        // 边缘折射/暗折
        pStroke.color = fade(C_REFRACT, alpha01)
        pStroke.strokeWidth = dp(1.4f)
        tmp.set(rr.left + dp(1.5f), rr.top + dp(1.5f), rr.right - dp(1.5f), rr.bottom - dp(1.5f))
        c.drawArc(tmp, 150f, 120f, false, pStroke)
        pStroke.color = fade(C_DARKFOLD, alpha01)
        c.drawArc(tmp, -20f, 100f, false, pStroke)

        // 顶部折射高光带
        pFill.color = fade(C_SHEEN, alpha01)
        tmp.set(rr.left + rr.width() * 0.05f, rr.top + rr.height() * 0.08f,
                rr.right - rr.width() * 0.05f, rr.top + rr.height() * 0.40f)
        c.drawRoundRect(tmp, coreR * 0.9f, coreR * 0.9f, pFill)

        if (active) {
            pGlow.color = fade(C_GLACIER_GLOW, alpha01)
            c.drawRoundRect(rr, rad, rad, pGlow)
        }
        pStroke.strokeWidth = dp(1.4f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        // 背景：黑曜石模糊环境（玻璃折射的色彩来源）
        val bb = backdropBlur
        if (bb != null && !bb.isRecycled) {
            canvas.drawBitmap(bb, null, fullRect.apply { set(0f, 0f, w, h) }, pBitmap)
        } else {
            canvas.drawColor(0xFF07080A.toInt())
        }

        drawTrigger(canvas, rectLT, "LT", state.lt, "LT")
        drawTrigger(canvas, rectRT, "RT", state.rt, "RT")

        drawStick(canvas, lsCenter, lsKnob, lsR, lsKnobR, "LS")
        drawStick(canvas, rsCenter, rsKnob, rsR, rsKnobR, "RS")

        drawDpad(canvas)
        drawAbxy(canvas)

        drawPill(canvas, rectLB, "LB", (state.buttons and Btn.LB) != 0, "LB")
        drawPill(canvas, rectRB, "RB", (state.buttons and Btn.RB) != 0, "RB")
        drawPill(canvas, rectBack, "BACK", (state.buttons and Btn.BACK) != 0, "BACK")
        drawPill(canvas, rectStart, "START", (state.buttons and Btn.START) != 0, "START")

        if (editMode) drawEditOverlay(canvas)
    }

    // ---------- 绘制 ----------

    private fun drawStick(c: Canvas, center: PointF, knob: PointF, radius: Float,
                          knobRadius: Float, key: String) {
        if (radius <= 0f) return
        val (a01, ty) = introOf(key)
        if (a01 <= 0f) return
        val cy = center.y + ty
        val ca01 = a01 * ov(key).a
        val active = hypot(knob.x, knob.y) > 2f
        // 轻点摇杆触发 L3/R3 时的短促高亮反馈；模式 3 下按住期间也保持高亮
        val flashAt = if (key == "LS") lsPulseAt else rsPulseAt
        val heldBit = if (key == "LS") Btn.L3 else Btn.R3
        val tapped = SystemClock.uptimeMillis() - flashAt < STICK_PULSE_MS
        val lit = active || tapped || (state.buttons and heldBit) != 0

        // 玻璃底座（多层烟熏玻璃）
        layeredGlassCircle(c, center.x, cy, radius, 0f, ca01, active)

        // 井内玻璃芯（下凹的第二层）
        pFill.color = fade(C_WELL, ca01)
        c.drawCircle(center.x, cy, radius * 0.60f, pFill)
        pStroke.color = fade(C_INSHADOW, ca01)
        pStroke.strokeWidth = dp(1.8f)
        tmp.set(center.x - radius * 0.60f + dp(1f), cy - radius * 0.60f + dp(1f),
                center.x + radius * 0.60f - dp(1f), cy)
        c.drawArc(tmp, 180f, 180f, false, pStroke)

        // 立体玻璃摇杆帽（独立悬浮）
        val knobPress = if (lit) 0.4f else 0f
        layeredGlassCircle(c, center.x + knob.x, cy + knob.y, knobRadius,
                           knobPress, ca01, lit)
        if (lit) {
            pStroke.color = fade(C_GLACIER, ca01 * 0.8f)
            pStroke.strokeWidth = dp(1.2f)
            c.drawCircle(center.x + knob.x, cy + knob.y, knobRadius + dp(1.4f), pStroke)
            pStroke.strokeWidth = dp(1.4f)
        }
    }

    private fun drawDpad(c: Canvas) {
        if (dpR <= 0f) return
        val (a01, ty) = introOf("DP")
        if (a01 <= 0f) return
        val ca01 = a01 * ov("DP").a
        val cy = dpCenter.y + ty

        // 玻璃底盘
        layeredGlassCircle(c, dpCenter.x, cy, dpR, 0f, ca01, false)

        val t = dpR * 0.34f
        val reach = dpR * 0.80f
        val cx = dpCenter.x
        val rad = t * 0.35f

        fun arm(zone: Int, active: Boolean, set: (RectF) -> Unit) {
            val pp = pressP(zone)
            set(tmp)
            // 悬浮玻璃臂（内芯层样式：按压下沉）
            pShadow.color = fade(C_AO, ca01 * (1f - pp * 0.5f))
            tmp2.set(tmp)
            tmp2.offset(0f, dp(1.5f))
            c.drawRoundRect(tmp2, rad, rad, pShadow)
            pFill.color = fade(if (pp > 0.5f) C_CORE_ON else C_CORE, ca01)
            c.drawRoundRect(tmp, rad, rad, pFill)
            pStroke.color = fade(C_SILVER_DIM, ca01)
            pStroke.strokeWidth = dp(0.8f)
            c.drawRoundRect(tmp, rad, rad, pStroke)
            pStroke.color = fade(C_REFRACT, ca01)
            pStroke.strokeWidth = dp(0.9f)
            tmp2.set(tmp.left + dp(0.8f), tmp.top + dp(0.8f),
                     tmp.right - dp(0.8f), tmp.centerY())
            c.drawRoundRect(tmp2, rad * 0.8f, rad * 0.8f, pStroke)
            if (active) {
                pGlow.color = fade(C_GLACIER_GLOW, ca01)
                c.drawRoundRect(tmp, rad, rad, pGlow)
            }
            pStroke.strokeWidth = dp(1.4f)
        }

        arm(Z_UP, (state.buttons and Btn.UP) != 0) {
            tmp.set(cx - t / 2, cy - reach, cx + t / 2, cy - t * 0.15f)
        }
        arm(Z_DOWN, (state.buttons and Btn.DOWN) != 0) {
            tmp.set(cx - t / 2, cy + t * 0.15f, cx + t / 2, cy + reach)
        }
        arm(Z_LEFT, (state.buttons and Btn.LEFT) != 0) {
            tmp.set(cx - reach, cy - t / 2, cx - t * 0.15f, cy + t / 2)
        }
        arm(Z_RIGHT, (state.buttons and Btn.RIGHT) != 0) {
            tmp.set(cx + t * 0.15f, cy - t / 2, cx + reach, cy + t / 2)
        }
    }

    private fun drawAbxy(c: Canvas) {
        val faces: List<Triple<PointF, String, Int>> = listOf(
            Triple(cY, "Y", C_Y), Triple(cA, "A", C_A),
            Triple(cX, "X", C_X), Triple(cB, "B", C_B)
        )
        faces.forEach { (p, key, color) ->
            val active = (state.buttons and bitByName(key)) != 0
            val radius = when (key) {
                "A" -> rA; "B" -> rB; "X" -> rX; else -> rY
            }
            val (a01, ty) = introOf(key)
            drawFace(c, p, label = key, color = color, active = active,
                     radius = radius, key = key, a01 = a01, ty = ty)
        }
    }

    /** ABXY：多层烟熏玻璃 + 高对比彩色字母（身份识别）。 */
    private fun drawFace(c: Canvas, p: PointF, label: String, color: Int, active: Boolean,
                         radius: Float, key: String, a01: Float, ty: Float) {
        if (radius <= 0f || a01 <= 0f) return
        val ca01 = a01 * ov(key).a
        val pp = pressP(zoneOfKey(key))
        val cy = p.y + ty

        layeredGlassCircle(c, p.x, cy, radius, pp, ca01, active)

        // 高对比字母（保持彩色身份，按下向白提亮）
        pText.color = fade(lerp(color, 0xFFFFFFFF.toInt(), if (active) 0.6f else 0.1f), ca01)
        pText.textSize = radius * 0.95f
        pText.isFakeBoldText = true
        val fm = pText.fontMetrics
        c.drawText(label, p.x, cy - (fm.ascent + fm.descent) / 2f, pText)
        pText.isFakeBoldText = false
        pText.alpha = 255
    }

    private fun drawPill(c: Canvas, r: RectF, label: String, active: Boolean, key: String) {
        val (a01, ty) = introOf(key)
        if (a01 <= 0f) return
        val ca01 = a01 * ov(key).a
        val rad = r.height() * 0.42f
        val zone = zoneOfKey(key)
        val pp = pressP(zone)

        tmp.set(r)
        tmp.offset(0f, ty)
        layeredGlassRect(c, tmp, rad, pp, ca01, active)

        pText.color = fade(if (active || pp > 0.5f) C_TXT_ON else C_TXT, ca01)
        pText.textSize = min(r.height() * 0.50f, r.width() * 0.28f)
        pText.isFakeBoldText = true
        val fm = pText.fontMetrics
        c.drawText(label, r.centerX(), r.centerY() + ty - (fm.ascent + fm.descent) / 2f, pText)
        pText.isFakeBoldText = false
        pText.alpha = 255
    }

    private fun drawTrigger(c: Canvas, r: RectF, label: String, value: Int, key: String) {
        val (a01, ty) = introOf(key)
        if (a01 <= 0f) return
        val ca01 = a01 * ov(key).a
        val rad = r.width() / 2f
        val rr = RectF(r)
        rr.offset(0f, ty)
        val zone = zoneOfKey(key)
        val pp = pressP(zone)
        val frac = value.coerceIn(0, 255) / 255f

        layeredGlassRect(c, rr, rad, pp, ca01, frac > 0.3f)

        // 行程填充（冰川蓝，连续触发的直观反馈）
        if (frac > 0.02f) {
            val h = rr.height() * frac
            tmp.set(rr.left + dp(1.5f), rr.bottom - h - dp(1.5f),
                    rr.right - dp(1.5f), rr.bottom - dp(1.5f))
            pFill.color = fade(C_GLACIER_FILL, ca01)
            c.drawRoundRect(tmp, rad * 0.8f, rad * 0.8f, pFill)
        }

        c.save()
        c.rotate(-90f, rr.centerX(), rr.centerY())
        pText.color = fade(if (frac > 0.4f) C_TXT_ON else C_TXT_DIM, ca01)
        pText.textSize = rr.width() * 0.50f
        pText.isFakeBoldText = true
        val fm = pText.fontMetrics
        c.drawText(label, rr.centerX(), rr.centerY() - (fm.ascent + fm.descent) / 2f, pText)
        pText.isFakeBoldText = false
        pText.alpha = 255
        c.restore()
    }

    private fun zoneOfKey(key: String): Int = when (key) {
        "LS" -> Z_LSTICK; "RS" -> Z_RSTICK
        "A" -> Z_A; "B" -> Z_B; "X" -> Z_X; "Y" -> Z_Y
        "LB" -> Z_LB; "RB" -> Z_RB; "BACK" -> Z_BACK; "START" -> Z_START
        "LT" -> Z_LT; "RT" -> Z_RT
        else -> Z_NONE
    }

    private fun bitByName(key: String): Int = when (key) {
        "A" -> Btn.A; "B" -> Btn.B; "X" -> Btn.X; "Y" -> Btn.Y
        "LB" -> Btn.LB; "RB" -> Btn.RB; "BACK" -> Btn.BACK; "START" -> Btn.START
        else -> 0
    }

    // ---------- 编辑模式覆盖层 ----------

    private fun outlineOf(key: String): RectF {
        val r = RectF()
        when (key) {
            "LS" -> r.set(lsCenter.x - lsR, lsCenter.y - lsR, lsCenter.x + lsR, lsCenter.y + lsR)
            "RS" -> r.set(rsCenter.x - rsR, rsCenter.y - rsR, rsCenter.x + rsR, rsCenter.y + rsR)
            "DP" -> r.set(dpCenter.x - dpR, dpCenter.y - dpR, dpCenter.x + dpR, dpCenter.y + dpR)
            "A" -> r.set(cA.x - rA, cA.y - rA, cA.x + rA, cA.y + rA)
            "B" -> r.set(cB.x - rB, cB.y - rB, cB.x + rB, cB.y + rB)
            "X" -> r.set(cX.x - rX, cX.y - rX, cX.x + rX, cX.y + rX)
            "Y" -> r.set(cY.x - rY, cY.y - rY, cY.x + rY, cY.y + rY)
            "LB" -> r.set(rectLB)
            "RB" -> r.set(rectRB)
            "BACK" -> r.set(rectBack)
            "START" -> r.set(rectStart)
            "LT" -> r.set(rectLT)
            "RT" -> r.set(rectRT)
        }
        return r
    }

    private fun drawEditOverlay(c: Canvas) {
        for (key in CTRL_KEYS) {
            val r = outlineOf(key)
            val sel = key == selectedKey
            if (sel) {
                pEditSel.color = C_ACCENT
                c.drawRect(r, pEditSel)
            } else {
                pEdit.color = 0x4D6FA8DC
                c.drawRect(r, pEdit)
            }
        }
        selectedKey?.let { key ->
            val r = outlineOf(key)
            pFill.color = C_GLACIER
            pFill.alpha = 0xFF
            val name = CTRL_NAMES[key] ?: key
            pText.color = C_DARK
            pText.textSize = dp(12f)
            pText.isFakeBoldText = true
            val label = if (editTarget == 0) {
                "$name  大小 ${((controlScale(key)) * 100).toInt()}%"
            } else {
                "$name  透明度 ${((controlAlpha(key)) * 100).toInt()}%"
            }
            val tw = pText.measureText(label)
            tmp.set(
                r.centerX() - tw / 2 - dp(8f), r.top - dp(26f),
                r.centerX() + tw / 2 + dp(8f), r.top - dp(6f)
            )
            c.drawRoundRect(tmp, dp(8f), dp(8f), pFill)
            val fm = pText.fontMetrics
            c.drawText(label, tmp.centerX(), tmp.centerY() - (fm.ascent + fm.descent) / 2f, pText)
            pText.isFakeBoldText = false
            pFill.alpha = 255
        }
    }

    // ---------- 触摸 ----------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) {
            handleEditTouch(event)
            invalidate()
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                bindPointer(event.getPointerId(i), event.getX(i), event.getY(i))
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val zone = pointers.get(id, Z_NONE)
                    if (zone == Z_NONE) continue
                    // 手指相对落点移动超过阈值 → 本次是拖动摇杆，取消 L3/R3 判定
                    // 双击模式容差更宽松（明确手势，允许轻微位移）
                    val slop = dp(
                        if (stickClickMode == STICK_CLICK_DOUBLE) STICK_TAP_SLOP_DP_DOUBLE
                        else STICK_TAP_SLOP_DP
                    )
                    when (zone) {
                        Z_LSTICK -> if (hypot(event.getX(i) - lsDownX,
                                              event.getY(i) - lsDownY) > slop
                        ) lsTapOk = false
                        Z_RSTICK -> if (hypot(event.getX(i) - rsDownX,
                                              event.getY(i) - rsDownY) > slop
                        ) rsTapOk = false
                    }
                    applyZone(zone, event.getX(i), event.getY(i))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                releasePointer(event.getPointerId(event.actionIndex))
            }

            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        invalidate()
        return true
    }

    private fun handleEditTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val x = event.getX(i)
                val y = event.getY(i)
                selectedKey = zoneKey(zoneAt(x, y, editMode = true))
                onSelectionChange?.invoke(selectedKey)
                editLastX = x
                editLastY = y
            }

            MotionEvent.ACTION_MOVE -> {
                val key = selectedKey ?: return
                val c = layout.getOrPut(key) { Ctrl() }
                c.dx += (event.x - editLastX) / width
                c.dy += (event.y - editLastY) / height
                editLastX = event.x
                editLastY = event.y
                computeLayout()
                clampIntoBounds(key, c)   // 全屏可拖，只保证控件不越出屏幕
                computeLayout()

                val o = outlineOf(key)
                val halfW = (o.width() / 2f).coerceAtLeast(1f)
                val halfH = (o.height() / 2f).coerceAtLeast(1f)
                val ncx = clamp(o.centerX(), halfW * 0.55f, width - halfW * 0.55f)
                val ncy = clamp(o.centerY(), halfH * 0.55f, height - halfH * 0.55f)
                if (ncx != o.centerX() || ncy != o.centerY()) {
                    c.dx += (ncx - o.centerX()) / width
                    c.dy += (ncy - o.centerY()) / height
                    computeLayout()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                editLastX = event.x
                editLastY = event.y
            }
        }
    }

    private fun bindPointer(id: Int, x: Float, y: Float) {
        val zone = zoneAt(x, y)
        if (zone == Z_NONE) return
        pointers.put(id, zone)
        zoneDownAt.put(zone, SystemClock.uptimeMillis().toInt())

        // 摇杆轻点（L3/R3）：记住落点，抬起时位移足够小才判定为"按下摇杆"
        when (zone) {
            Z_LSTICK -> {
                lsDownX = x
                lsDownY = y
                lsTapOk = true
            }
            Z_RSTICK -> {
                rsDownX = x
                rsDownY = y
                rsTapOk = true
            }
        }

        // 模式 3：只要操作摇杆（按下即算）就持续按住 LS/RS
        if (stickClickMode == STICK_CLICK_ALWAYS) {
            when (zone) {
                Z_LSTICK -> setThumbBit(left = true, on = true)
                Z_RSTICK -> setThumbBit(left = false, on = true)
            }
        }

        // 模式 2：双击 —— 第二次"按下"即触发（不等抬起，避免第二次抬起时的
        // 轻微位移/时间差把整段双击作废；也更快给到游戏与视觉反馈）
        if (stickClickMode == STICK_CLICK_DOUBLE) {
            val now = SystemClock.uptimeMillis()
            when (zone) {
                Z_LSTICK -> if (lsLastTapAt > 0 && now - lsLastTapAt <= DOUBLE_TAP_MS) {
                    lsLastTapAt = 0
                    lsTapOk = false      // 本次抬起不再重复触发
                    pulseThumb(left = true)
                }
                Z_RSTICK -> if (rsLastTapAt > 0 && now - rsLastTapAt <= DOUBLE_TAP_MS) {
                    rsLastTapAt = 0
                    rsTapOk = false
                    pulseThumb(left = false)
                }
            }
        }

        val strength = when (zone) {
            Z_A, Z_B, Z_X, Z_Y -> 1.0f
            Z_LB, Z_RB -> 0.85f
            Z_UP, Z_DOWN, Z_LEFT, Z_RIGHT -> 0.75f
            Z_START, Z_BACK -> 0.70f
            Z_LT, Z_RT -> 0.50f
            else -> 0f
        }
        if (strength > 0f) haptic(strength)

        applyZone(zone, x, y)
    }

    private fun applyZone(zone: Int, x: Float, y: Float) {
        when (zone) {
            Z_LSTICK -> setStick(lsKnob, x, y, lsCenter, lsR, true)
            Z_RSTICK -> setStick(rsKnob, x, y, rsCenter, rsR, false)
            Z_LT -> state.lt = triggerValue(rectLT, y)
            Z_RT -> state.rt = triggerValue(rectRT, y)
            else -> pressBit(zone)
        }
    }

    private fun releasePointer(id: Int) {
        val zone = pointers.get(id, Z_NONE)
        if (zone == Z_NONE) return
        pointers.delete(id)
        zoneReleaseAt.put(zone, SystemClock.uptimeMillis().toInt())
        if (heldByOther(zone)) return
        zoneDownAt.delete(zone)   // 清除按下时间戳，避免长按状态残留

        when (zone) {
            Z_LSTICK -> {
                val tap = lsTapOk
                lsTapOk = false
                lsKnob.set(0f, 0f)
                state.lx = 0
                state.ly = 0
                lsInDeadzone = true
                onStickReleased(left = true, tap = tap)
            }
            Z_RSTICK -> {
                val tap = rsTapOk
                rsTapOk = false
                rsKnob.set(0f, 0f)
                state.rx = 0
                state.ry = 0
                rsInDeadzone = true
                onStickReleased(left = false, tap = tap)
            }
            Z_LT -> state.lt = 0
            Z_RT -> state.rt = 0
            else -> {
                val bit = bitOf(zone)
                if (bit != 0) state.buttons = state.buttons and bit.inv()
            }
        }
    }

    private fun heldByOther(zone: Int): Boolean {
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i) == zone) return true
        }
        return false
    }

    fun releaseAll() {
        pointers.clear()
        lsKnob.set(0f, 0f)
        rsKnob.set(0f, 0f)
        lsInDeadzone = true
        rsInDeadzone = true
        lsTapOk = false
        rsTapOk = false
        lsLastTapAt = 0L
        rsLastTapAt = 0L
        lsPulseToken++
        rsPulseToken++
        state.reset()
        zoneDownAt.clear()
        invalidate()
    }

    /**
     * 摇杆按下脉冲：置位 L3/R3，保持 [STICK_PULSE_MS] 后自动复位。
     * 用递增 token 保证连续两次轻点时，前一次的复位不会提前清掉后一次。
     */
    /**
     * 摇杆抬起：按当前"摇杆按下方式"决定是否产生 LS/RS。
     *  - 轻点中心：一次轻点即按下（140ms 脉冲）
     *  - 双击：320ms 内连点两次才按下（单次不触发）
     *  - 操作即按下：抬起时释放（按下的位已在 [bindPointer] 里置位）
     */
    private fun onStickReleased(left: Boolean, tap: Boolean) {
        when (stickClickMode) {
            STICK_CLICK_ALWAYS -> setThumbBit(left, on = false)
            STICK_CLICK_DOUBLE -> {
                val now = SystemClock.uptimeMillis()
                if (!tap) {
                    // 这一次抬起被判为拖动：只让它"不作数"，但不要抹掉 400ms 内刚记录的首击
                    // （否则一次轻微抖动就会让整段双击失效）
                    if (left) {
                        if (now - lsLastTapAt > DOUBLE_TAP_MS) lsLastTapAt = 0
                    } else {
                        if (now - rsLastTapAt > DOUBLE_TAP_MS) rsLastTapAt = 0
                    }
                    return
                }
                val last = if (left) lsLastTapAt else rsLastTapAt
                if (last > 0 && now - last <= DOUBLE_TAP_MS) {
                    if (left) lsLastTapAt = 0 else rsLastTapAt = 0
                    pulseThumb(left)
                } else {
                    if (left) lsLastTapAt = now else rsLastTapAt = now
                }
            }
            else -> if (tap) pulseThumb(left)
        }
    }

    private fun setThumbBit(left: Boolean, on: Boolean) {
        val bit = if (left) Btn.L3 else Btn.R3
        state.buttons = if (on) state.buttons or bit else state.buttons and bit.inv()
    }

    private fun pulseThumb(left: Boolean) {
        setThumbBit(left, on = true)
        if (left) {
            lsPulseAt = SystemClock.uptimeMillis()
            lsPulseToken++
        } else {
            rsPulseAt = SystemClock.uptimeMillis()
            rsPulseToken++
        }
        haptic(0.55f)
        invalidate()
        val token = if (left) lsPulseToken else rsPulseToken
        postDelayed({
            val cur = if (left) lsPulseToken else rsPulseToken
            if (cur == token) {
                setThumbBit(left, on = false)
                invalidate()
            }
        }, STICK_PULSE_MS)
    }

    private fun zoneAt(x: Float, y: Float, editMode: Boolean = false): Int {
        val faceMul = if (editMode) 1.7f else 1.45f
        if (hypot(x - cY.x, y - cY.y) <= rY * faceMul) return Z_Y
        if (hypot(x - cA.x, y - cA.y) <= rA * faceMul) return Z_A
        if (hypot(x - cX.x, y - cX.y) <= rX * faceMul) return Z_X
        if (hypot(x - cB.x, y - cB.y) <= rB * faceMul) return Z_B

        if (rectLB.contains(x, y)) return Z_LB
        if (rectRB.contains(x, y)) return Z_RB
        if (rectBack.contains(x, y)) return Z_BACK
        if (rectStart.contains(x, y)) return Z_START

        if (rectLT.contains(x, y)) return Z_LT
        if (rectRT.contains(x, y)) return Z_RT

        val dx = x - dpCenter.x
        val dy = y - dpCenter.y
        val dpHit = if (editMode) dpR * 1.25f else dpR
        if (hypot(dx, dy) <= dpHit) {
            return if (abs(dx) > abs(dy)) {
                if (dx < 0) Z_LEFT else Z_RIGHT
            } else {
                if (dy < 0) Z_UP else Z_DOWN
            }
        }

        val stickMul = if (editMode) 1.25f else 1.35f
        if (hypot(x - lsCenter.x, y - lsCenter.y) <= lsR * stickMul) return Z_LSTICK
        if (hypot(x - rsCenter.x, y - rsCenter.y) <= rsR * stickMul) return Z_RSTICK

        return Z_NONE
    }

    private fun setStick(
        knob: PointF,
        x: Float,
        y: Float,
        center: PointF,
        radius: Float,
        isLeft: Boolean
    ) {
        val dx = x - center.x
        val dy = y - center.y
        val dist = hypot(dx, dy)

        // 摇杆已被明显推动（超过半径 30%）→ 本次是操作摇杆而不是"按下摇杆"，取消 L3/R3 判定。
        // 防止"手指按在摇杆偏心位置不动、抬起时误触发 LS/RS"。
        // 仅"轻点中心"模式需要该限制；双击模式允许在摇杆区域内任意位置双击。
        if (stickClickMode == STICK_CLICK_CENTER && dist > radius * 0.30f) {
            if (isLeft) lsTapOk = false else rsTapOk = false
        }

        if (dist < 0.5f) {
            knob.set(0f, 0f)
            if (isLeft) {
                state.lx = 0
                state.ly = 0
            } else {
                state.rx = 0
                state.ry = 0
            }
            return
        }

        val clamped = min(dist, radius)
        val nx = dx / dist
        val ny = dy / dist
        knob.set(nx * clamped, ny * clamped)

        val raw = clamped / radius
        val mag = if (raw <= DEADZONE) 0f else (raw - DEADZONE) / (1f - DEADZONE)

        val nowInDead = mag <= 0f
        if (isLeft) {
            if (nowInDead != lsInDeadzone) {
                lsInDeadzone = nowInDead
                if (!nowInDead) haptic(0.35f)
            }
        } else {
            if (nowInDead != rsInDeadzone) {
                rsInDeadzone = nowInDead
                if (!nowInDead) haptic(0.35f)
            }
        }

        val vx = (nx * mag * STICK_MAX).toInt().coerceIn(-32768, 32767)
        // 屏幕 Y 向下为正，XInput 的 Y 轴向上为正，必须翻转
        val vy = (-ny * mag * STICK_MAX).toInt().coerceIn(-32768, 32767)

        if (isLeft) {
            state.lx = vx
            state.ly = vy
        } else {
            state.rx = vx
            state.ry = vy
        }
    }

    private fun triggerValue(r: RectF, y: Float): Int {
        val frac = ((y - r.top) / r.height()).coerceIn(0f, 1f)
        return (frac * 255f).toInt()
    }

    private fun bitOf(zone: Int): Int = when (zone) {
        Z_A -> Btn.A
        Z_B -> Btn.B
        Z_X -> Btn.X
        Z_Y -> Btn.Y
        Z_LB -> Btn.LB
        Z_RB -> Btn.RB
        Z_BACK -> Btn.BACK
        Z_START -> Btn.START
        Z_UP -> Btn.UP
        Z_DOWN -> Btn.DOWN
        Z_LEFT -> Btn.LEFT
        Z_RIGHT -> Btn.RIGHT
        else -> 0
    }

    private fun pressBit(zone: Int) {
        val bit = bitOf(zone)
        if (bit != 0) state.buttons = state.buttons or bit
    }
}
