package com.vgamepad

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat

/**
 * 设计令牌（Material Design 3 语义角色 + Neumorphism 阴影色）。
 *
 * 说明：新拟态要求「控件与背景同色系」，立体感完全由双向柔和阴影塑造，
 * 因此 [bg] 同时是画布底色与控件表面基色；[surface]/[surfaceHigh] 只用于
 * 需要抬升的浮层（设置面板、连接栏）。
 */
data class VgColors(
    val dark: Boolean,
    val bg: Int,
    val bgTop: Int,
    val surface: Int,
    val surfaceHigh: Int,
    val onSurface: Int,
    val onSurfaceVar: Int,
    val outline: Int,
    val accent: Int,
    val accentInk: Int,
    val onAccent: Int,
    val accentSoft: Int,
    val ok: Int,
    val err: Int,
    val shadowDark: Int,
    val shadowLight: Int,
    /** ABXY 字母色（保持手柄身份识别，但压低饱和度融进主题） */
    val faceA: Int,
    val faceB: Int,
    val faceX: Int,
    val faceY: Int,
) {
    /** 供 `Canvas` 合成未预乘颜色时使用 */
    fun withAlpha(color: Int, alpha: Float): Int {
        val a = ((color ushr 24) * alpha.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }
}

/** 自定义主题色。低饱和、成对提供浅/深变体，保证两种主题下都有足够对比。 */
data class VgAccent(
    val name: String,
    val light: Int,
    val dark: Int,
    val inkLight: Int,
    val inkDark: Int,
)

object VgTheme {

    /** prefs 中表示「跟随系统壁纸取色」的哨兵值 */
    const val ACCENT_DYNAMIC = -1
    /** prefs 中表示「使用默认石板蓝」 */
    const val ACCENT_DEFAULT = 0

    enum class Mode(val pref: Int) {
        SYSTEM(0), LIGHT(1), DARK(2);

        companion object {
            fun of(v: Int): Mode = values().firstOrNull { it.pref == v } ?: SYSTEM
        }
    }

    val accents: List<VgAccent> = listOf(
        VgAccent("石板蓝", 0xFF6C7FA8.toInt(), 0xFF9DB0D6.toInt(), 0xFF44557B.toInt(), 0xFFC6D5F0.toInt()),
        VgAccent("鼠尾草", 0xFF6E9A82.toInt(), 0xFFA2C9B2.toInt(), 0xFF3F6A54.toInt(), 0xFFC8E6D3.toInt()),
        VgAccent("青灰", 0xFF5E8F94.toInt(), 0xFF92C3C8.toInt(), 0xFF34646A.toInt(), 0xFFB9E1E5.toInt()),
        VgAccent("陶土", 0xFFB08268.toInt(), 0xFFD9AC91.toInt(), 0xFF7A5138.toInt(), 0xFFEDCDB6.toInt()),
        VgAccent("梅紫", 0xFF8C7AA6.toInt(), 0xFFB9A6D2.toInt(), 0xFF5B4A73.toInt(), 0xFFD9CAEB.toInt()),
        VgAccent("古铜", 0xFFA08A5E.toInt(), 0xFFCDB98C.toInt(), 0xFF6E5B33.toInt(), 0xFFE3D3AD.toInt()),
    )

    // ---------------- 持久化 ----------------

    fun mode(ctx: Context): Mode =
        Mode.of(prefs(ctx).getInt("theme_mode", Mode.SYSTEM.pref))

    fun setMode(ctx: Context, mode: Mode) {
        prefs(ctx).edit().putInt("theme_mode", mode.pref).apply()
        applyNightMode(mode)
    }

    /** -1 = 动态取色；0 = 默认；其余为 [accents] 的下标 + 1 */
    fun accentIndex(ctx: Context): Int = prefs(ctx).getInt("accent_index", ACCENT_DEFAULT)

    fun setAccentIndex(ctx: Context, index: Int) {
        prefs(ctx).edit().putInt("accent_index", index).apply()
    }

    fun accentName(index: Int): String = when {
        index == ACCENT_DYNAMIC -> "跟随壁纸"
        index <= ACCENT_DEFAULT -> accents[0].name
        index <= accents.size -> accents[index - 1].name
        else -> accents[0].name
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("vgp", Context.MODE_PRIVATE)

    // ---------------- 夜间模式 ----------------

    fun applyNightMode(mode: Mode) {
        val target = when (mode) {
            Mode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            Mode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            Mode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        if (AppCompatDelegate.getDefaultNightMode() != target) {
            AppCompatDelegate.setDefaultNightMode(target)
        }
    }

    fun isSystemDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // ---------------- 解析 ----------------

    fun resolve(ctx: Context, mode: Mode, accentIndex: Int): VgColors {
        val dark = when (mode) {
            Mode.LIGHT -> false
            Mode.DARK -> true
            Mode.SYSTEM -> isSystemDark(ctx)
        }
        return if (dark) darkColors(accentIndex, ctx) else lightColors(accentIndex, ctx)
    }

    fun resolve(ctx: Context): VgColors =
        resolve(ctx, mode(ctx), accentIndex(ctx))

    private fun lightColors(accentIndex: Int, ctx: Context): VgColors {
        val (accent, ink) = accentPair(accentIndex, ctx, dark = false)
        // 背景由主题色推导：只混入很小比例，既能让整屏随主题色一起变，
        // 又能保持低饱和与足够的文字对比度。
        return VgColors(
            dark = false,
            bg = tint(0xFFE9EDF3.toInt(), accent, 0.125f),
            bgTop = tint(0xFFEDF1F7.toInt(), accent, 0.095f),
            surface = 0xFFF1F4F9.toInt(),
            surfaceHigh = 0xFFF7F9FC.toInt(),
            onSurface = 0xFF2A2F37.toInt(),
            onSurfaceVar = 0xFF5C6572.toInt(),
            outline = 0xFFC7CFDA.toInt(),
            accent = accent,
            accentInk = ink,
            onAccent = 0xFFFFFFFF.toInt(),
            accentSoft = withAlpha(accent, 0.14f),
            ok = 0xFF3F7A55.toInt(),
            err = 0xFFB4514B.toInt(),
            // 浅色新拟态：左上白光、右下冷灰影
            shadowDark = 0x4FA8B6C9.toInt(),
            shadowLight = 0xF0FFFFFF.toInt(),
            faceA = 0xFF4F8757.toInt(),
            faceB = 0xFFB0564E.toInt(),
            faceX = 0xFF4470A8.toInt(),
            faceY = 0xFF9A7A2E.toInt(),
        )
    }

    private fun darkColors(accentIndex: Int, ctx: Context): VgColors {
        val (accent, ink) = accentPair(accentIndex, ctx, dark = true)
        return VgColors(
            dark = true,
            bg = tint(0xFF1B1E23.toInt(), accent, 0.165f),
            bgTop = tint(0xFF20242A.toInt(), accent, 0.130f),
            surface = 0xFF22262C.toInt(),
            surfaceHigh = 0xFF292E35.toInt(),
            onSurface = 0xFFE4E8EF.toInt(),
            onSurfaceVar = 0xFF9AA4B2.toInt(),
            outline = 0xFF363C45.toInt(),
            accent = accent,
            accentInk = ink,
            onAccent = 0xFF16181C.toInt(),
            accentSoft = withAlpha(accent, 0.20f),
            ok = 0xFF6FD79A.toInt(),
            err = 0xFFFF8A82.toInt(),
            // 深色新拟态：纯黑投影 + 极淡顶光
            shadowDark = 0x70000000.toInt(),
            shadowLight = 0x30FFFFFF.toInt(),
            faceA = 0xFF7FD08A.toInt(),
            faceB = 0xFFF08E86.toInt(),
            faceX = 0xFF86B4EE.toInt(),
            faceY = 0xFFE7C877.toInt(),
        )
    }

    /** 把主题色按比例混进基准色（保持明度，只带一点色相） */
    private fun tint(base: Int, over: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(base) + (Color.red(over) - Color.red(base)) * t).toInt().coerceIn(0, 255),
            (Color.green(base) + (Color.green(over) - Color.green(base)) * t).toInt().coerceIn(0, 255),
            (Color.blue(base) + (Color.blue(over) - Color.blue(base)) * t).toInt().coerceIn(0, 255),
        )
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    /**
     * 解析主题色：动态取色（Android 12+ 系统调色板）或预设色板。
     * 动态色会做一次去饱和处理，避免 Material You 默认高饱和色破坏「低饱和」基调。
     */
    private fun accentPair(index: Int, ctx: Context, dark: Boolean): Pair<Int, Int> {
        if (index == ACCENT_DYNAMIC) {
            val dyn = dynamicAccent(ctx, dark)
            if (dyn != 0) {
                val base = desaturate(dyn, 0.42f)
                return base to inkOf(base, dark)
            }
        }
        val a = accents[(index - 1).coerceIn(0, accents.size - 1)]
        return (if (dark) a.dark else a.light) to (if (dark) a.inkDark else a.inkLight)
    }

    private fun dynamicAccent(ctx: Context, dark: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0
        val id = if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
        return try {
            ContextCompat.getColor(ctx, id)
        } catch (_: Throwable) {
            0
        }
    }

    /** 向中性灰混合，降低饱和度（保留色相识别） */
    private fun desaturate(color: Int, amount: Float): Int {
        val gray = (Color.red(color) * 0.299f + Color.green(color) * 0.587f +
            Color.blue(color) * 0.114f).toInt()
        val t = amount.coerceIn(0f, 1f)
        fun mix(c: Int) = (c + (gray - c) * t).toInt().coerceIn(0, 255)
        return Color.rgb(mix(Color.red(color)), mix(Color.green(color)), mix(Color.blue(color)))
    }

    /** 为强调色挑一个能当文字用的深/浅变体（WCAG AA ≥ 4.5:1 目标） */
    private fun inkOf(accent: Int, dark: Boolean): Int {
        if (dark) {
            // 深色底：提亮到能读
            return mixWith(accent, Color.WHITE, 0.45f)
        }
        // 浅色底：压暗到能读
        return mixWith(accent, Color.BLACK, 0.42f)
    }

    private fun mixWith(c: Int, target: Int, t: Float): Int = Color.rgb(
        (Color.red(c) + (Color.red(target) - Color.red(c)) * t).toInt().coerceIn(0, 255),
        (Color.green(c) + (Color.green(target) - Color.green(c)) * t).toInt().coerceIn(0, 255),
        (Color.blue(c) + (Color.blue(target) - Color.blue(c)) * t).toInt().coerceIn(0, 255),
    )
}
