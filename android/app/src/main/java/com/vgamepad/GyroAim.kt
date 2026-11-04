package com.vgamepad

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/**
 * 陀螺仪瞄准：把"转手机"的物理转动换算成右摇杆等效值（-1..1）。
 *
 * 原理：TYPE_GAME_ROTATION_VECTOR（陀螺仪+加速度融合）给出设备姿态，
 * 取"看向手机背面"的方向向量，相邻两帧的方向角差 / 时间差 = 转动角速度；
 * 再除以游戏的最大转速（与 LookCamera 的 YAW_SPEED/PITCH_SPEED 一致）
 * 即得摇杆等效值。效果是 1:1 物理映射——手机转多快，视角就转多快。
 *
 * 无需校准：方向来自融合传感器，无累计漂移；静止时角速度≈0，
 * 配合 3°/s 死区不会产生蠕动词。EMA 仅滤除传感器噪声（约 20ms，不影响跟手）。
 */
class GyroAim(context: Context) : SensorEventListener {

    companion object {
        // 与游戏端 LookCamera 的最大转速保持一致（度/秒）
        const val MAX_YAW_RATE = 150.0f
        const val MAX_PITCH_RATE = 95.0f
        private const val DEAD_RATE = 3.0f   // 度/秒以下视为静止
        private const val EMA = 0.45f        // 传感器噪声滤波强度

        /** 灵敏度滑杆（0%–100%）对应的增益范围：指数映射，两端差异明显。 */
        const val SENS_MIN_GAIN = 0.12f
        const val SENS_MAX_GAIN = 3.2f

        /** 0..1 灵敏度 → 增益。低端大幅更慢、高端更快（约 26.7 倍跨度）。 */
        fun gainForSens(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            val k = (SENS_MAX_GAIN / SENS_MIN_GAIN).toDouble()
            return (SENS_MIN_GAIN * Math.pow(k, x.toDouble())).toFloat()
        }
        private const val GAIN_DEFAULT = 1.0f
    }

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensorRot: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    /** 设备是否支持陀螺仪/姿态传感器 */
    val available: Boolean get() = sensorRot != null

    /** 输出的右摇杆等效值（-1..1），上推为正、右转为正 */
    @Volatile var rx = 0f
        private set
    @Volatile var ry = 0f
        private set

    /** 灵敏度倍率（可由设置滑条驱动） */
    @Volatile var gain = GAIN_DEFAULT

    /** 俯仰反向（用户实测校准用） */
    @Volatile var invertY = false

    /** 左右反向（用户实测校准用） */
    @Volatile var invertX = false

    @Volatile var running = false
        private set

    /** 每次传感器更新回调（主线程），参数为换算后的摇杆等效值 */
    var onAim: ((Float, Float) -> Unit)? = null

    private var lastYaw = Float.NaN
    private var lastPitch = Float.NaN
    private var lastTs = 0L
    private var emaYaw = 0f
    private var emaPitch = 0f

    fun start() {
        if (!available || running) return
        val s = sensorRot ?: return
        lastYaw = Float.NaN
        lastPitch = Float.NaN
        lastTs = 0L
        emaYaw = 0f
        emaPitch = 0f
        // registerListener 可能返回 false（传感器被独占/系统限制），
        // 此时保持 running=false，避免开关显示"已开启"却永远收不到数据
        running = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!running) return
        sm.unregisterListener(this)
        running = false
        rx = 0f
        ry = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return

        // 瞄准方向 = 屏幕法线（设备 +Z，指向持机者面部）。
        // 手柄式握持（屏幕对自己）时，"把屏幕转向天空"= 抬头，符合体感直觉；
        // 若用背摄方向（-Z）则俯仰恰好相反（此前版本的问题根源）。
        // 世界系：X=东，Y=北，Z=天顶。航向=atan2(东,北)，俯仰=asin(天顶分量)。
        val q = FloatArray(4)
        SensorManager.getQuaternionFromVector(q, event.values)
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        val lx = 2f * (qx * qz + qy * qw)
        val ly = 2f * (qy * qz - qx * qw)
        val lz = 1f - 2f * (qx * qx + qy * qy)

        // 安排好世界系约定：X=东，Y=北，Z=天顶（上）。
        // 视线方向的三个分量：东 / 北 / 上 ——
        //   航向 = atan2(东, 北)（右转为正），俯仰 = asin(上)（抬头为正）
        val yawDeg = Math.toDegrees(atan2(lx, ly).toDouble()).toFloat()
        val pitchDeg = Math.toDegrees(asin(clamp(lz, -1f, 1f)).toDouble()).toFloat()

        val dt = if (lastTs == 0L) 0f else (event.timestamp - lastTs) / 1_000_000_000f
        lastTs = event.timestamp
        if (dt <= 0f || dt > 0.5f) {
            lastYaw = yawDeg
            lastPitch = pitchDeg
            return
        }

        if (!lastYaw.isNaN()) {
            var dyaw = yawDeg - lastYaw
            if (dyaw > 180f) dyaw -= 360f
            if (dyaw < -180f) dyaw += 360f
            val dpitch = pitchDeg - lastPitch

            var rateYaw = dyaw / dt
            var ratePitch = dpitch / dt

            // 死区：极低速视为静止（抑制手抖蠕动词）
            if (abs(rateYaw) < DEAD_RATE) rateYaw = 0f
            if (abs(ratePitch) < DEAD_RATE) ratePitch = 0f

            // 轻度 EMA 滤噪：只滤传感器抖动，不引入可感知延迟
            emaYaw += (rateYaw - emaYaw) * EMA
            emaPitch += (ratePitch - emaPitch) * EMA

            // 方向以用户实测为准：俯仰默认取反（实测校准结果），左右保持正
            val yawOut = if (invertX) -emaYaw else emaYaw
            val pitchOut = if (invertY) emaPitch else -emaPitch
            rx = clamp(yawOut * gain / MAX_YAW_RATE, -1f, 1f)
            ry = clamp(pitchOut * gain / MAX_PITCH_RATE, -1f, 1f)
            onAim?.invoke(rx, ry)
        }

        lastYaw = yawDeg
        lastPitch = pitchDeg
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

private fun clamp(v: Float, lo: Float, hi: Float): Float = maxOf(lo, minOf(hi, v))
