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
import android.util.SparseLongArray
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
 * 全屏自绘虚拟手柄 —— Material Design 3 + Neumorphism
 *
 * 设计分层（严格分离，互不影响输入）：
 *  - 令牌层 [VgTheme]：低饱和配色、浅/深主题、自定义主题色、MD3 语义角色
 *  - 渲染层 [Neumo]：双向柔和阴影，凸起/凹陷两种物理状态，着色器与阴影位图全部缓存
 *  - 几何层 [computeLayout]：只算坐标与半径，可被缩放/拖拽/隐藏
 *  - 输入层 [onTouchEvent] / [bindPointer]：直接读写 [GamepadState]，与视觉零耦合
 *
 * 视觉要点：
 *  - 背景与控件同色系，立体感由左上高光 + 右下投影塑造，避免高对比"塑料感"
 *  - ABXY 保留语义色字母，其余控件为中性表面色 + 强调色点缀
 *  - 按下 = 下沉 1.6dp + 内凹阴影 + 边缘收暗，松开回弹；动画只改绘制，不阻塞输入
 *  - 摇杆 = 挖入式摇杆井 + 悬浮摇杆帽，帽顶指示点随激活态点亮
 *  - LT/RT = 凹陷行程槽，强调色自下而上填充，带 25/50/75 刻度
 *  - 逐键自定义：位置、大小、透明度、显隐，全部只影响绘制与命中范围
 */
class GamepadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    val state = GamepadState()

    /** 当前主题（由 Activity 注入）。默认按系统/偏好解析，避免首帧闪白。 */
    private var themeColors: VgColors = VgTheme.resolve(context)

    /** 新拟态渲染器：只负责画，不碰任何输入状态 */
    private val neumo = Neumo(resources.displayMetrics.density)

    private val col: VgColors get() = themeColors

    init {
        neumo.setColors(themeColors)
    }

    private var themeAnim: ValueAnimator? = null

    /**
     * 应用新主题：只重建着色器并重绘，不影响触控与通信。
     * [animated] = true 时背景色从旧配色渐变到新配色（约 480ms，MD3 标准缓动），
     * 控件色板立即生效——避免整屏"啪"地跳色。
     */
    fun applyTheme(c: VgColors, animated: Boolean = false) {
        val old = themeColors
        if (old == c) return
        themeColors = c
        neumo.setColors(c)
        themeAnim?.cancel()
        themeAnim = null
        if (animated) {
            neumo.beginBackgroundTransition(old)
            themeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 480L
                interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                addUpdateListener {
                    neumo.setBackgroundProgress(it.animatedValue as Float)
                    invalidate()
                }
                start()
            }
        } else {
            neumo.endBackgroundTransition()
            invalidate()
        }
    }

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
        const val Z_GUIDE = 33           // Home / Xbox 键（Btn.GUIDE，游戏里的「加入」）
        const val Z_MENU = 34            // ☰ 菜单键（映射 Btn.START，游戏里的 Menu）
        const val Z_LT = 40
        const val Z_RT = 41

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
        /** 扳机：手指纵向移动超过该距离才进入「按位置调节」模式，否则单击即满行程 */
        const val TRIGGER_DRAG_SLOP_DP = 8f
        /** 方向键中心死区：距圆心小于该比例时不触发任何方向 */
        const val DPAD_CENTER_DEAD = 0.18f

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
            "LS", "RS", "DP", "A", "B", "X", "Y", "LB", "RB", "BACK", "MENU", "GUIDE", "LT", "RT"
        )
        val CTRL_NAMES = mapOf(
            "LS" to "左摇杆", "RS" to "右摇杆", "DP" to "方向键",
            "A" to "A", "B" to "B", "X" to "X", "Y" to "Y",
            "LB" to "LB", "RB" to "RB", "BACK" to "BACK",
            "MENU" to "菜单键（☰）", "GUIDE" to "Home 键（Xbox）",
            "LT" to "LT", "RT" to "RT"
        )
    }

    /** 单控件布局覆盖：dx/dy 位置、s 大小、a 透明度 */
    class Ctrl(var dx: Float = 0f, var dy: Float = 0f, var s: Float = 1f, var a: Float = 1f)

    private val layout = HashMap<String, Ctrl>()
    private val defCtrl = Ctrl()
    private fun ov(key: String): Ctrl = layout[key] ?: defCtrl

    // ---------- 按键显隐（设置面板「隐藏按键」） ----------

    private val hidden = HashSet<String>()

    fun isHidden(key: String): Boolean = hidden.contains(key)

    /** 隐藏后：不绘制、不命中、不参与布局编辑；只清被隐藏控件自己的状态，
     *  其它正在按着的按键/摇杆/扳机不受影响 */
    fun setHidden(key: String, hide: Boolean) {
        if (hide) hidden.add(key) else hidden.remove(key)
        if (hide) releaseKeyState(key)
        computeLayout()
        invalidate()
    }

    /** 释放某个控件自身的输入状态（隐藏该控件时调用），不影响其它控件 */
    private fun releaseKeyState(key: String) {
        val zones = when (key) {
            "DP" -> intArrayOf(Z_UP, Z_DOWN, Z_LEFT, Z_RIGHT)
            "LS" -> intArrayOf(Z_LSTICK)
            "RS" -> intArrayOf(Z_RSTICK)
            "LT" -> intArrayOf(Z_LT)
            "RT" -> intArrayOf(Z_RT)
            "A" -> intArrayOf(Z_A)
            "B" -> intArrayOf(Z_B)
            "X" -> intArrayOf(Z_X)
            "Y" -> intArrayOf(Z_Y)
            "LB" -> intArrayOf(Z_LB)
            "RB" -> intArrayOf(Z_RB)
            "BACK" -> intArrayOf(Z_BACK)
            "GUIDE" -> intArrayOf(Z_GUIDE)
            "MENU" -> intArrayOf(Z_MENU)
            else -> return
        }
        for (z in zones) {
            // 解绑按住该 zone 的手指，并复位该控件的输出
            for (i in pointers.size() - 1 downTo 0) {
                if (pointers.valueAt(i) == z) {
                    pointers.delete(pointers.keyAt(i))
                    zoneDownAt.delete(z)
                }
            }
            when (z) {
                Z_LSTICK -> {
                    lsKnob.set(0f, 0f); state.lx = 0; state.ly = 0
                    lsInDeadzone = true; lsTapOk = false
                }
                Z_RSTICK -> {
                    rsKnob.set(0f, 0f); state.rx = 0; state.ry = 0
                    rsInDeadzone = true; rsTapOk = false
                }
                Z_LT -> { state.lt = 0; ltDragging = false }
                Z_RT -> { state.rt = 0; rtDragging = false }
            }
        }
        val bits = when (key) {
            "DP" -> Btn.UP or Btn.DOWN or Btn.LEFT or Btn.RIGHT
            "A" -> Btn.A
            "B" -> Btn.B
            "X" -> Btn.X
            "Y" -> Btn.Y
            "LB" -> Btn.LB
            "RB" -> Btn.RB
            "BACK" -> Btn.BACK
            "GUIDE" -> Btn.GUIDE
            "MENU" -> Btn.START
            else -> 0
        }
        if (bits != 0) state.buttons = state.buttons and bits.inv()
    }

    fun loadHidden(json: String?) {
        hidden.clear()
        if (!json.isNullOrBlank()) {
            try {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val k = arr.optString(i, "")
                    if (k in CTRL_KEYS) hidden.add(k)
                }
            } catch (e: Exception) {
                // 坏数据按「全部显示」处理
            }
        }
        computeLayout()
        invalidate()
    }

    fun hiddenJson(): String {
        val arr = org.json.JSONArray()
        for (k in CTRL_KEYS) if (hidden.contains(k)) arr.put(k)
        return arr.toString()
    }

    /** 命中判定统一入口：隐藏的控件永不响应触摸 */
    private fun hit(key: String, inside: Boolean): Boolean = !hidden.contains(key) && inside

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
    private val cMenu = PointF()
    private var rMenu = 0f
    private val cGuide = PointF()
    private var rGuide = 0f
    private val tmp = RectF()
    private val tmp2 = RectF()
    private val fullRect = RectF()

    // ---------- 画笔 ----------
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
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

    private val pointers = SparseIntArray()

    /** 按压状态跟踪：zone -> 按下/松开时刻 */
    // 时间戳用 Long：uptimeMillis().toInt() 在开机约 24.8 天后回绕为负，
    // 会让 (now-down) 变成巨大负数、按压/回弹动画永久失效
    private val zoneDownAt = SparseLongArray()
    private val zoneReleaseAt = SparseLongArray()

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
        val now = SystemClock.uptimeMillis()
        val down = zoneDownAt.get(zone, 0L)
        if (down > 0L) return clamp((now - down) / PRESS_SINK_MS, 0f, 1f)
        val rel = zoneReleaseAt.get(zone, 0L)
        if (rel > 0L) return clamp(1f - (now - rel) / RELEASE_SETTLE_MS, 0f, 1f)
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

    // ---- 扳机：单击即满行程，纵向拖动切换为连续调节 ----
    private var ltDownY = 0f
    private var rtDownY = 0f
    private var ltDragging = false
    private var rtDragging = false

    // ---- 摇杆按下（L3/R3）：轻点摇杆触发一次按键脉冲 ----
    /**
     * 触发方式（0=轻点中心 / 1=双击区域内任意位置 / 2=操作摇杆即按下）。
     * 切换时清理 L3/R3 与脉冲/双击状态，避免跨模式卡键或被旧脉冲误清。
     */
    var stickClickMode = STICK_CLICK_CENTER
        set(value) {
            if (field == value) return
            field = value
            clearThumbState()
        }

    private fun clearThumbState() {
        state.buttons = state.buttons and (Btn.L3 or Btn.R3).inv()
        lsPulseToken++
        rsPulseToken++
        lsLastTapAt = 0L
        rsLastTapAt = 0L
        lsTapOk = false
        rsTapOk = false
    }

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
        // VibrationEffect 是 API 26 引入的；minSdk 24 下必须分支，否则 Android 7.x
        // 一触摸就抛 NoClassDefFoundError（catch Exception 拦不住 Error）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(hapticDurationMs.toLong(), amp))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(hapticDurationMs.toLong())
        }
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
                        // 布局数据可能来自旧版本/备份恢复/被篡改，全部钳制到合法区间：
                        // s=0 会摇杆除零得 NaN，s 极大会让阴影位图申请 OOM
                        o.optDouble("dx", 0.0).toFloat().coerceIn(-1f, 1f),
                        o.optDouble("dy", 0.0).toFloat().coerceIn(-1f, 1f),
                        o.optDouble("s", 1.0).toFloat().coerceIn(0.5f, 2.0f),
                        o.optDouble("a", 1.0).toFloat().coerceIn(0.05f, 1f)
                    )
                }
            } catch (e: Exception) {
                // 坏数据按默认处理
            }
        }
        computeLayout()
        // 尺寸已知时再按屏幕边界收敛一次（View 未测量时 clampIntoBounds 自会跳过）
        if (width > 0 && height > 0) {
            for ((key, c) in layout) clampIntoBounds(key, c)
            computeLayout()
        }
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

    /** 供设置面板枚举全部可隐藏 / 可编辑的控件 */
    fun controlKeys(): List<String> = CTRL_KEYS

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
        Z_GUIDE -> "GUIDE"
        Z_MENU -> "MENU"
        Z_LT -> "LT"
        Z_RT -> "RT"
        else -> null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        lsKnob.set(0f, 0f)
        rsKnob.set(0f, 0f)
        computeLayout()
        startIntro()
    }

    override fun onDetachedFromWindow() {
        introAnim?.cancel()
        introAnim = null
        themeAnim?.cancel()
        themeAnim = null
        pulseRunnable?.let { removeCallbacks(it) }
        pulseRunnable = null
        neumo.dispose()
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

        // Home / Xbox 键：顶部正中，取代原 START 的位置（圆形玻璃钮）
        val guide = ov("GUIDE")
        rGuide = base * 0.052f * kBtn * guide.s
        cGuide.set(W * 0.500f + guide.dx * W, pillCy + guide.dy * H)

        // ☰ 菜单键：独立圆形玻璃键（默认位于顶部功能区左下方）
        val menu = ov("MENU")
        rMenu = base * 0.050f * kBtn * menu.s
        cMenu.set(W * 0.340f + menu.dx * W, H * 0.320f + menu.dy * H)

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
        "GUIDE" -> 240f
        "MENU" -> 260f
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

    // ---------- 绘制（Material 3 语义 + Neumorphism 质感） ----------

    private fun faceColor(key: String): Int = when (key) {
        "A" -> col.faceA
        "B" -> col.faceB
        "X" -> col.faceX
        else -> col.faceY
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return

        // 底色与控件同色系：立体感全部交给双向柔和阴影，避免"塑料玩具"感
        neumo.background(canvas, w, h)

        fun shown(k: String) = !isHidden(k)

        if (shown("LT")) drawTrigger(canvas, rectLT, "LT", state.lt, "LT")
        if (shown("RT")) drawTrigger(canvas, rectRT, "RT", state.rt, "RT")

        if (shown("LS")) drawStick(canvas, lsCenter, lsKnob, lsR, lsKnobR, "LS")
        if (shown("RS")) drawStick(canvas, rsCenter, rsKnob, rsR, rsKnobR, "RS")

        if (shown("DP")) drawDpad(canvas)
        if (shown("A") || shown("B") || shown("X") || shown("Y")) drawAbxy(canvas)

        if (shown("LB")) drawPill(canvas, rectLB, "LB", (state.buttons and Btn.LB) != 0, "LB")
        if (shown("RB")) drawPill(canvas, rectRB, "RB", (state.buttons and Btn.RB) != 0, "RB")
        if (shown("BACK")) drawPill(canvas, rectBack, "BACK", (state.buttons and Btn.BACK) != 0, "BACK")
        if (shown("GUIDE")) drawGuideButton(canvas)
        if (shown("MENU")) drawMenuButton(canvas)

        if (editMode) drawEditOverlay(canvas)
    }

    // ---- 摇杆：凹槽井 + 立体摇杆帽 ----

    private fun drawStick(
        c: Canvas, center: PointF, knob: PointF, radius: Float,
        knobRadius: Float, key: String,
    ) {
        if (radius <= 0f) return
        val (a01, ty) = introOf(key)
        if (a01 <= 0f) return
        val cy = center.y + ty
        val ca01 = a01 * ov(key).a
        val active = hypot(knob.x, knob.y) > 2f

        val flashAt = if (key == "LS") lsPulseAt else rsPulseAt
        val heldBit = if (key == "LS") Btn.L3 else Btn.R3
        val tapped = SystemClock.uptimeMillis() - flashAt < STICK_PULSE_MS
        val lit = active || tapped || (state.buttons and heldBit) != 0

        // 1) 挖进面板的摇杆井
        neumo.insetCircle(c, center.x, cy, radius, ca01, 1f)

        // 2) 井底同心细环（MD3 层次提示活动范围）
        pStroke.color = fade(col.outline, ca01 * 0.34f)
        pStroke.strokeWidth = dp(1f)
        c.drawCircle(center.x, cy, radius * 0.78f, pStroke)

        // 3) 悬浮摇杆帽
        val kx = center.x + knob.x
        val ky = cy + knob.y
        neumo.raisedCircle(c, kx, ky, knobRadius, 0f, ca01,
            ring = if (lit) col.accent else null)

        // 4) 帽顶指向点：激活时用强调色，安静时用次级色
        pFill.color = fade(col.accent, ca01 * if (lit) 0.90f else 0.30f)
        c.drawCircle(kx, ky, knobRadius * 0.28f, pFill)
    }

    // ---- 方向键：凸起底盘 + 四条独立凸起臂 ----

    private fun drawDpad(c: Canvas) {
        if (dpR <= 0f) return
        val (a01, ty) = introOf("DP")
        if (a01 <= 0f) return
        val ca01 = a01 * ov("DP").a
        val cy = dpCenter.y + ty
        val cx = dpCenter.x

        neumo.raisedCircle(c, cx, cy, dpR, 0f, ca01)

        val t = dpR * 0.32f
        val reach = dpR * 0.78f
        val rad = dp(9f)
        val inner = t * 0.22f

        drawDpadArm(c, Z_UP, (state.buttons and Btn.UP) != 0,
            cx - t / 2, cy - reach, cx + t / 2, cy - inner, rad, ca01)
        drawDpadArm(c, Z_DOWN, (state.buttons and Btn.DOWN) != 0,
            cx - t / 2, cy + inner, cx + t / 2, cy + reach, rad, ca01)
        drawDpadArm(c, Z_LEFT, (state.buttons and Btn.LEFT) != 0,
            cx - reach, cy - t / 2, cx - inner, cy + t / 2, rad, ca01)
        drawDpadArm(c, Z_RIGHT, (state.buttons and Btn.RIGHT) != 0,
            cx + inner, cy - t / 2, cx + reach, cy + t / 2, rad, ca01)
    }

    private fun drawDpadArm(
        c: Canvas, zone: Int, active: Boolean,
        l: Float, t: Float, r: Float, b: Float, radius: Float, alpha: Float,
    ) {
        tmp.set(l, t, r, b)
        val pp = pressP(zone)
        neumo.raisedRect(c, tmp, radius, pp, alpha, tint = 0.055f)
        if (active) {
            pFill.color = fade(col.accent, alpha * 0.22f)
            c.drawRoundRect(tmp, radius, radius, pFill)
        }
    }

    // ---- ABXY：凸起圆钮 + 语义字母色 ----

    private fun drawAbxy(c: Canvas) {
        drawFace(c, cY, "Y", rY)
        drawFace(c, cA, "A", rA)
        drawFace(c, cX, "X", rX)
        drawFace(c, cB, "B", rB)
    }

    private fun drawFace(c: Canvas, p: PointF, label: String, radius: Float) {
        if (radius <= 0f || isHidden(label)) return
        val (a01, ty) = introOf(label)
        if (a01 <= 0f) return
        val ca01 = a01 * ov(label).a
        val active = (state.buttons and bitByName(label)) != 0
        val pp = pressP(zoneOfKey(label))
        val cy = p.y + ty
        val tint = faceColor(label)

        neumo.raisedCircle(c, p.x, cy, radius, pp, ca01,
            ring = if (active) tint else null)

        pText.color = fade(
            if (active) lerp(tint, 0xFFFFFFFF.toInt(), 0.30f) else tint,
            ca01
        )
        pText.textSize = radius * 0.92f
        pText.isFakeBoldText = true
        val fm = pText.fontMetrics
        c.drawText(label, p.x, cy - (fm.ascent + fm.descent) / 2f, pText)
        pText.isFakeBoldText = false
        pText.alpha = 255
    }

    // ---- 肩键 / BACK：凸起胶囊 ----

    private fun drawPill(c: Canvas, r: RectF, label: String, active: Boolean, key: String) {
        val (a01, ty) = introOf(key)
        if (a01 <= 0f) return
        val ca01 = a01 * ov(key).a
        val rad = r.height() * 0.5f
        val pp = pressP(zoneOfKey(key))

        tmp.set(r)
        tmp.offset(0f, ty)
        neumo.raisedRect(c, tmp, rad, pp, ca01)
        if (active) {
            pFill.color = fade(col.accent, ca01 * 0.20f)
            c.drawRoundRect(tmp, rad, rad, pFill)
        }

        pText.color = fade(
            if (active || pp > 0.5f) col.accentInk else col.onSurface,
            ca01
        )
        pText.textSize = min(r.height() * 0.40f, r.width() * 0.42f)
        pText.isFakeBoldText = true
        pText.letterSpacing = 0.04f
        val fm = pText.fontMetrics
        c.drawText(label, r.centerX(), r.centerY() + ty - (fm.ascent + fm.descent) / 2f, pText)
        pText.letterSpacing = 0f
        pText.isFakeBoldText = false
        pText.alpha = 255
    }

    // ---- Home / Xbox 键 ----

    private fun drawGuideButton(c: Canvas) {
        if (rGuide <= 0f) return
        val (a01, ty) = introOf("GUIDE")
        if (a01 <= 0f) return
        val ca01 = a01 * ov("GUIDE").a
        val active = (state.buttons and Btn.GUIDE) != 0
        val pp = pressP(Z_GUIDE)
        val cy = cGuide.y + ty

        neumo.raisedCircle(c, cGuide.x, cy, rGuide, pp, ca01,
            ring = if (active) col.accent else null)

        // 内圈：强调键位身份
        pStroke.color = fade(if (active) col.accentInk else col.outline, ca01 * 0.95f)
        pStroke.strokeWidth = dp(1.2f)
        c.drawCircle(cGuide.x, cy, rGuide * 0.58f, pStroke)

        pText.color = fade(if (active) col.accentInk else col.onSurface, ca01)
        pText.textSize = rGuide * 0.66f
        pText.isFakeBoldText = true
        val fm = pText.fontMetrics
        c.drawText("X", cGuide.x, cy - (fm.ascent + fm.descent) / 2f, pText)
        pText.isFakeBoldText = false
        pText.alpha = 255
    }

    // ---- ☰ 菜单键 ----

    private fun drawMenuButton(c: Canvas) {
        if (rMenu <= 0f) return
        val (a01, ty) = introOf("MENU")
        if (a01 <= 0f) return
        val ca01 = a01 * ov("MENU").a
        val active = (state.buttons and Btn.START) != 0
        val pp = pressP(Z_MENU)
        val cy = cMenu.y + ty

        neumo.raisedCircle(c, cMenu.x, cy, rMenu, pp, ca01,
            ring = if (active) col.accent else null)

        val lw = rMenu * 0.54f
        val lh = maxOf(dp(2f), rMenu * 0.085f)
        val gap = rMenu * 0.23f
        pFill.color = fade(if (active) col.accentInk else col.onSurface, ca01)
        for (k in -1..1) {
            val ly = cy + k * gap
            tmp.set(cMenu.x - lw / 2f, ly - lh / 2f, cMenu.x + lw / 2f, ly + lh / 2f)
            c.drawRoundRect(tmp, lh / 2f, lh / 2f, pFill)
        }
    }

    // ---- LT / RT：凹陷行程槽 + 强调色填充 ----

    private fun drawTrigger(c: Canvas, r: RectF, label: String, value: Int, key: String) {
        val (a01, ty) = introOf(key)
        if (a01 <= 0f) return
        val ca01 = a01 * ov(key).a
        val rad = r.width() / 2f
        val rr = tmp2
        rr.set(r)
        rr.offset(0f, ty)
        val frac = value.coerceIn(0, 255) / 255f

        // 凹槽：与按下状态同一套语言
        neumo.insetRect(c, rr, rad, ca01)

        // 行程填充
        if (frac > 0.02f) {
            val inner = dp(2.5f)
            val hh = (rr.height() - inner * 2f) * frac
            tmp.set(rr.left + inner, rr.bottom - inner - hh, rr.right - inner, rr.bottom - inner)
            pFill.shader = neumo.triggerFill(tmp.height(), frac, col.accent, ca01)
            // Shader 用局部坐标，先平移到填充区左上角再画
            c.save()
            c.translate(tmp.left, tmp.top)
            c.drawRoundRect(0f, 0f, tmp.width(), tmp.height(), rad * 0.72f, rad * 0.72f, pFill)
            c.restore()
            pFill.shader = null
        }

        // 25 / 50 / 75 刻度（两侧短刻线）
        pStroke.color = fade(col.outline, ca01 * 0.60f)
        pStroke.strokeWidth = dp(1f)
        for (i in 1..3) {
            val y = rr.bottom - rr.height() * (i / 4f)
            c.drawLine(rr.left + dp(3f), y, rr.left + dp(6.5f), y, pStroke)
            c.drawLine(rr.right - dp(6.5f), y, rr.right - dp(3f), y, pStroke)
        }

        c.save()
        c.rotate(-90f, rr.centerX(), rr.centerY())
        pText.color = fade(if (frac > 0.4f) col.accentInk else col.onSurfaceVar, ca01)
        pText.textSize = rr.width() * 0.44f
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
        "LB" -> Z_LB; "RB" -> Z_RB; "BACK" -> Z_BACK
        "MENU" -> Z_MENU; "GUIDE" -> Z_GUIDE
        "LT" -> Z_LT; "RT" -> Z_RT
        else -> Z_NONE
    }

    private fun bitByName(key: String): Int = when (key) {
        "A" -> Btn.A; "B" -> Btn.B; "X" -> Btn.X; "Y" -> Btn.Y
        "LB" -> Btn.LB; "RB" -> Btn.RB; "BACK" -> Btn.BACK
        "MENU" -> Btn.START     // 菜单键复用 START 位（界面已无独立 START 键）
        "GUIDE" -> Btn.GUIDE
        else -> 0
    }

    // ---------- 编辑模式覆盖层 ----------

    /** 编辑覆盖层专用的临时矩形：outlineOf 全部复用它，避免编辑模式每帧 new 14+ 个 RectF。
     *  所有调用点都是"取用即弃"的顺序使用，无嵌套持有。 */
    private val editRect = RectF()

    private fun outlineOf(key: String): RectF {
        val r = editRect
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
            "GUIDE" -> r.set(
                cGuide.x - rGuide, cGuide.y - rGuide,
                cGuide.x + rGuide, cGuide.y + rGuide
            )
            "MENU" -> r.set(cMenu.x - rMenu, cMenu.y - rMenu, cMenu.x + rMenu, cMenu.y + rMenu)
            "LT" -> r.set(rectLT)
            "RT" -> r.set(rectRT)
        }
        return r
    }

    private fun drawEditOverlay(c: Canvas) {
        for (key in CTRL_KEYS) {
            if (isHidden(key)) continue
            val r = outlineOf(key)
            val sel = key == selectedKey
            if (sel) {
                pEditSel.color = col.accentInk
                c.drawRect(r, pEditSel)
            } else {
                pEdit.color = fade(col.accentInk, 0.45f)
                c.drawRect(r, pEdit)
            }
        }
        selectedKey?.let { key ->
            val r = outlineOf(key)
            pFill.color = col.accent
            pFill.alpha = 255
            val name = CTRL_NAMES[key] ?: key
            pText.color = col.onAccent
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
                        Z_LT -> if (!ltDragging &&
                            abs(event.getY(i) - ltDownY) > dp(TRIGGER_DRAG_SLOP_DP)
                        ) ltDragging = true
                        Z_RT -> if (!rtDragging &&
                            abs(event.getY(i) - rtDownY) > dp(TRIGGER_DRAG_SLOP_DP)
                        ) rtDragging = true
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

    /** 编辑模式锁定的手指 id：多指触控下 MOVE 必须与 DOWN 用同一根手指做差。
     *  原实现 MOVE 固定用 event.x（恒 index 0），第二根手指按下后第一帧
     *  会算出等于两指间距的巨大位移，选中控件被瞬移。 */
    private var editPointerId = -1

    private fun handleEditTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // 已有拖动手指时忽略后续手指，避免多指互相覆盖选中与落点
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && editPointerId >= 0) return
                val i = event.actionIndex
                editPointerId = event.getPointerId(i)
                val x = event.getX(i)
                val y = event.getY(i)
                selectedKey = zoneKey(zoneAt(x, y, editMode = true))
                onSelectionChange?.invoke(selectedKey)
                editLastX = x
                editLastY = y
            }

            MotionEvent.ACTION_MOVE -> {
                val key = selectedKey ?: return
                if (editPointerId < 0) return
                val pi = event.findPointerIndex(editPointerId)
                if (pi < 0) return
                val x = event.getX(pi)
                val y = event.getY(pi)
                val c = layout.getOrPut(key) { Ctrl() }
                c.dx += (x - editLastX) / width
                c.dy += (y - editLastY) / height
                editLastX = x
                editLastY = y
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
                // 只有锁定的那根手指抬起才结束拖动；其它手指抬起不影响编辑
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL ||
                    event.getPointerId(event.actionIndex) == editPointerId
                ) {
                    editPointerId = -1
                }
            }
        }
    }

    private fun bindPointer(id: Int, x: Float, y: Float) {
        val zone = zoneAt(x, y)
        if (zone == Z_NONE) return
        // 同一 zone 只接受第一根手指：多指同区会互相覆盖落点/拖动态，
        // 摇杆值在两指间跳变、L3/R3 轻点也会误判
        if (heldByOther(zone)) return
        pointers.put(id, zone)
        zoneDownAt.put(zone, SystemClock.uptimeMillis())

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
            Z_BACK, Z_MENU, Z_GUIDE -> 0.70f
            Z_LT, Z_RT -> 0.50f
            else -> 0f
        }
        if (strength > 0f) haptic(strength)

        // 扳机：落点即满行程（点一下就踩到底）；明显上下移动后再按纵向位置连续调节
        when (zone) {
            Z_LT -> { ltDownY = y; ltDragging = false }
            Z_RT -> { rtDownY = y; rtDragging = false }
        }

        applyZone(zone, x, y)
    }

    private fun applyZone(zone: Int, x: Float, y: Float) {
        when (zone) {
            Z_LSTICK -> setStick(lsKnob, x, y, lsCenter, lsR, true)
            Z_RSTICK -> setStick(rsKnob, x, y, rsCenter, rsR, false)
            Z_LT -> state.lt = if (ltDragging) triggerValue(rectLT, y) else 255
            Z_RT -> state.rt = if (rtDragging) triggerValue(rectRT, y) else 255
            else -> pressBit(zone)
        }
    }

    private fun releasePointer(id: Int) {
        val zone = pointers.get(id, Z_NONE)
        if (zone == Z_NONE) return
        pointers.delete(id)
        zoneReleaseAt.put(zone, SystemClock.uptimeMillis())
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
            Z_LT -> { state.lt = 0; ltDragging = false }
            Z_RT -> { state.rt = 0; rtDragging = false }
            else -> {
                val bit = bitOf(zone)
                // 同一位可能被多个手指/控件按住，最后一根手指抬起时才清位
                if (bit != 0 && !bitHeldByOther(bit)) {
                    state.buttons = state.buttons and bit.inv()
                }
            }
        }
    }

    /** 是否还有其它手指按着同一个按键位（防御性：允许将来两个控件共用一位）。 */
    private fun bitHeldByOther(bit: Int): Boolean {
        for (i in 0 until pointers.size()) {
            if (bitOf(pointers.valueAt(i)) == bit) return true
        }
        return false
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
        ltDragging = false
        rtDragging = false
        lsPulseToken++
        rsPulseToken++
        state.reset()
        zoneDownAt.clear()
        invalidate()
    }

    /**
     * 摇杆抬起：按当前「摇杆按下方式」决定是否产生 LS/RS。
     *  - 轻点中心：一次轻点即按下（180ms 脉冲）
     *  - 双击：400ms 内两次，第二次「按下」即触发（单次不触发）
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

    /** 最近的脉冲 Runnable 引用：重新脉冲/detach 时 removeCallbacks 用 */
    private var pulseRunnable: Runnable? = null

    /** 置位 L3/R3 并保持 [STICK_PULSE_MS]；token 保证旧脉冲不会提前清掉新脉冲。 */
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
        pulseRunnable?.let { removeCallbacks(it) }
        val r = Runnable {
            val cur = if (left) lsPulseToken else rsPulseToken
            if (cur == token) {
                setThumbBit(left, on = false)
                invalidate()
            }
        }
        pulseRunnable = r
        postDelayed(r, STICK_PULSE_MS)
    }

    private fun zoneAt(x: Float, y: Float, editMode: Boolean = false): Int {
        val faceMul = if (editMode) 1.7f else 1.45f
        if (hit("Y", hypot(x - cY.x, y - cY.y) <= rY * faceMul)) return Z_Y
        if (hit("A", hypot(x - cA.x, y - cA.y) <= rA * faceMul)) return Z_A
        if (hit("X", hypot(x - cX.x, y - cX.y) <= rX * faceMul)) return Z_X
        if (hit("B", hypot(x - cB.x, y - cB.y) <= rB * faceMul)) return Z_B

        if (hit("LB", rectLB.contains(x, y))) return Z_LB
        if (hit("RB", rectRB.contains(x, y))) return Z_RB
        if (hit("BACK", rectBack.contains(x, y))) return Z_BACK
        if (hit("GUIDE", rGuide > 0f &&
                hypot(x - cGuide.x, y - cGuide.y) <= rGuide * faceMul)) return Z_GUIDE
        if (hit("MENU", rMenu > 0f &&
                hypot(x - cMenu.x, y - cMenu.y) <= rMenu * faceMul)) return Z_MENU

        if (hit("LT", rectLT.contains(x, y))) return Z_LT
        if (hit("RT", rectRT.contains(x, y))) return Z_RT

        val dx = x - dpCenter.x
        val dy = y - dpCenter.y
        val dpHit = if (editMode) dpR * 1.25f else dpR
        val dpDist = hypot(dx, dy)
        // 圆心附近不判方向，避免「点正中间 = 按下」这种误触
        if (hit("DP", dpDist <= dpHit && dpDist > dpR * DPAD_CENTER_DEAD)) {
            return if (abs(dx) > abs(dy)) {
                if (dx < 0) Z_LEFT else Z_RIGHT
            } else {
                if (dy < 0) Z_UP else Z_DOWN
            }
        }

        val stickMul = if (editMode) 1.25f else 1.35f
        if (hit("LS", hypot(x - lsCenter.x, y - lsCenter.y) <= lsR * stickMul)) return Z_LSTICK
        if (hit("RS", hypot(x - rsCenter.x, y - rsCenter.y) <= rsR * stickMul)) return Z_RSTICK

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

        // radius<=0（布局数据异常）时避免除零得 NaN
        if (radius <= 0f) return
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
        val hgt = r.height()
        if (hgt <= 0f) return 255   // 轨道高度异常时按"满行程"处理，与单击语义一致
        val frac = ((y - r.top) / hgt).coerceIn(0f, 1f)
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
        Z_MENU -> Btn.START
        Z_GUIDE -> Btn.GUIDE
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
