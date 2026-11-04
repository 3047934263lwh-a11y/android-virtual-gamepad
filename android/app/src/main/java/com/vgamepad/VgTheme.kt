package com.vgamepad

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate

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

    /** prefs 中表示「使用默认主题色」 */
    const val ACCENT_DEFAULT = 0

    enum class Mode(val pref: Int) {
        SYSTEM(0), LIGHT(1), DARK(2);

        companion object {
            fun of(v: Int): Mode = values().firstOrNull { it.pref == v } ?: SYSTEM
        }
    }

    /**
     * 主题色板：只保留黄、橙、绿、青、紫五色。
     * 已移除「跟随壁纸（动态取色）」与蓝/灰/棕等其它色相——动态取色会把系统高饱和色带进来，
     * 破坏新拟态要求的低饱和基调，且与 ABXY 四色（绿红蓝黄）撞色。
     */
    val accents: List<VgAccent> = listOf(
        VgAccent("暖黄", 0xFFBEA23C.toInt(), 0xFFE8CE73.toInt(), 0xFF7E6512.toInt(), 0xFFF3E7B4.toInt()),
        VgAccent("暖橙", 0xFFC67F45.toInt(), 0xFFE8AB7E.toInt(), 0xFF88491A.toInt(), 0xFFF4D5BD.toInt()),
        VgAccent("苔绿", 0xFF6E9A82.toInt(), 0xFFA2C9B2.toInt(), 0xFF3F6A54.toInt(), 0xFFC8E6D3.toInt()),
        VgAccent("青瓷", 0xFF5E8F94.toInt(), 0xFF92C3C8.toInt(), 0xFF34646A.toInt(), 0xFFB9E1E5.toInt()),
        VgAccent("梅紫", 0xFF8C7AA6.toInt(), 0xFFB9A6D2.toInt(), 0xFF5B4A73.toInt(), 0xFFD9CAEB.toInt()),
    )

    // ---------------- 持久化 ----------------

    fun mode(ctx: Context): Mode =
        Mode.of(prefs(ctx).getInt("theme_mode", Mode.SYSTEM.pref))

    fun setMode(ctx: Context, mode: Mode) {
        prefs(ctx).edit().putInt("theme_mode", mode.pref).apply()
        applyNightMode(mode)
    }

    /** 0 = 默认；其余为 [accents] 的下标 + 1。越界（旧版本的动态取色/已删色板）归一到合法区间 */
    fun accentIndex(ctx: Context): Int =
        prefs(ctx).getInt("accent_index", ACCENT_DEFAULT).coerceIn(ACCENT_DEFAULT, accents.size)

    fun setAccentIndex(ctx: Context, index: Int) {
        prefs(ctx).edit().putInt("accent_index", index.coerceIn(ACCENT_DEFAULT, accents.size)).apply()
    }

    fun accentName(index: Int): String {
        val i = index.coerceIn(ACCENT_DEFAULT, accents.size)
        return accents[(i - 1).coerceAtLeast(0)].name
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

    /** 解析主题色：只走预设色板（动态取色已移除） */
    private fun accentPair(index: Int, ctx: Context, dark: Boolean): Pair<Int, Int> {
        val i = index.coerceIn(ACCENT_DEFAULT, accents.size)
        val a = accents[(i - 1).coerceAtLeast(0)]
        return (if (dark) a.dark else a.light) to (if (dark) a.inkDark else a.inkLight)
    }

}
