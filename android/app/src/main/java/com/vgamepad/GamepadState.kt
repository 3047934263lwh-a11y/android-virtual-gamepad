package com.vgamepad

/**
 * 按键位定义，必须与 PC 端 GamepadProtocol.cs 中的常量严格一致。
 * 位序与 Windows XINPUT_GAMEPAD.wButtons 保持一致，便于后续扩展。
 */
object Btn {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val X = 1 shl 2
    const val Y = 1 shl 3
    const val LB = 1 shl 4
    const val RB = 1 shl 5
    const val BACK = 1 shl 6
    const val START = 1 shl 7
    const val L3 = 1 shl 8
    const val R3 = 1 shl 9
    const val UP = 1 shl 10
    const val DOWN = 1 shl 11
    const val LEFT = 1 shl 12
    const val RIGHT = 1 shl 13
    const val GUIDE = 1 shl 14
}

/**
 * 手柄当前状态。写入方为 UI 线程（GamepadView），读取方为发送线程（UdpSender），
 * 因此每个字段都使用 @Volatile 保证可见性。16 位以内整型的读写本身是原子的。
 */
class GamepadState {

    @Volatile
    var buttons: Int = 0

    /** 左摇杆 X，-32768..32767，右为正 */
    @Volatile
    var lx: Int = 0

    /** 左摇杆 Y，-32768..32767，上为正（已做屏幕坐标翻转） */
    @Volatile
    var ly: Int = 0

    @Volatile
    var rx: Int = 0

    @Volatile
    var ry: Int = 0

    /** 左扳机 0..255 */
    @Volatile
    var lt: Int = 0

    /** 右扳机 0..255 */
    @Volatile
    var rt: Int = 0

    /** 包序号，仅用于丢包诊断，0..255 循环 */
    @Volatile
    var sequence: Int = 0

    // ---- 陀螺仪瞄准（UdpSender 在 useGyro 时以此覆盖右摇杆） ----

    @Volatile
    var useGyro: Boolean = false

    @Volatile
    var gyroRx: Int = 0

    @Volatile
    var gyroRy: Int = 0

    fun reset() {
        buttons = 0
        lx = 0
        ly = 0
        rx = 0
        ry = 0
        lt = 0
        rt = 0
    }
}
