package com.vgamepad

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var gamepadView: GamepadView
    private lateinit var ipInput: EditText
    private lateinit var portInput: EditText
    private lateinit var connectBtn: MaterialButton
    private lateinit var settingsBtn: ImageButton
    private lateinit var statusText: TextView
    private lateinit var sender: UdpSender

    private lateinit var editBar: LinearLayout
    private lateinit var editLabel: TextView
    private lateinit var editSeek: SeekBar

    private val prefs by lazy { getSharedPreferences("vgp", Context.MODE_PRIVATE) }

    private var hapticsOn = true
    private var hapticsLevel = 2
    private var rumbleOn = true
    private var gyroAim: GyroAim? = null
    private var wantGyro = false
    private var resumed = false
    private var playerNo = 0

    private var lastRumbleAt = 0L
    private var lastRumbleAmp = -1

    private val vibrator: Vibrator? by lazy { obtainVibrator() }

    private val listener = object : UdpSender.Listener {
        override fun onConnected(host: String, port: Int) {
            statusText.text = "已连接 $host:$port"
            statusText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ok))
        }

        override fun onError(message: String) {
            statusText.text = message
            statusText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.err))
        }

        override fun onStats(pps: Int) {
            if (!sender.isRunning) return
            statusText.text = "已连接 · $pps pps" +
                (if (playerNo > 0) " · ${playerNo}P" else "")
            statusText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ok))
        }

        override fun onStopped() = Unit

        override fun onRumble(largeMotor: Int, smallMotor: Int) {
            handleRumble(largeMotor, smallMotor)
        }

        override fun onPlayerAssigned(playerIndex: Int) {
            // 接收端会周期性重发编号包（解决启动竞态），只在编号变化时提示一次，
            // 否则 Toast 会每秒弹出造成"不断闪烁"。
            val changed = playerNo != playerIndex
            playerNo = playerIndex
            if (changed) toast("本手机分配为 ${playerIndex}P 手柄")
            statusText.text = "已连接 · ${playerIndex}P"
            statusText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ok))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        applyImmersive()

        gamepadView = findViewById(R.id.gamepadView)
        ipInput = findViewById(R.id.ipInput)
        portInput = findViewById(R.id.portInput)
        connectBtn = findViewById(R.id.connectBtn)
        settingsBtn = findViewById(R.id.settingsBtn)
        statusText = findViewById(R.id.statusText)

        ipInput.setText(prefs.getString("ip", ""))
        portInput.setText(prefs.getInt("port", UdpSender.DEFAULT_PORT).toString())

        // 恢复用户自定义的控件大小与震动设置
        hapticsOn = prefs.getBoolean("haptics", true)
        hapticsLevel = prefs.getInt("haptics_level", 2)
        rumbleOn = prefs.getBoolean("rumble", true)
        gamepadView.uiScale = prefs.getFloat("ui_scale", 1f)
        gamepadView.stickScale = prefs.getFloat("stick_scale", 1f)
        gamepadView.buttonScale = prefs.getFloat("button_scale", 1f)
        gamepadView.loadLayout(prefs.getString("layout_v1", null))
        gamepadView.hapticsEnabled = hapticsOn
        gamepadView.hapticsLevel = hapticsLevel

        sender = UdpSender(gamepadView.state)

        // 陀螺仪瞄准：把"转手机"换算成右摇杆等效值，发送时叠加到右摇杆字节
        gyroAim = GyroAim(this)
        wantGyro = prefs.getBoolean("gyro", false)
        gyroAim?.gain = prefs.getFloat("gyro_gain", 1.0f)
        gyroAim?.invertY = prefs.getBoolean("gyro_invert_y", false)
        gyroAim?.invertX = prefs.getBoolean("gyro_invert_x", false)
        gyroAim?.onAim = { rx, ry ->
            gamepadView.state.gyroRx = (rx * 32767).toInt()
            gamepadView.state.gyroRy = (ry * 32767).toInt()
        }

        connectBtn.setOnClickListener {
            if (sender.isRunning) disconnect() else connect()
        }
        settingsBtn.setOnClickListener { openSettings() }

        editBar = findViewById(R.id.editBar)
        editLabel = findViewById(R.id.editLabel)
        editSeek = findViewById(R.id.editSeek)
        val resetOne = findViewById<MaterialButton>(R.id.editResetOne)
        val resetAll = findViewById<MaterialButton>(R.id.editResetAll)
        val done = findViewById<MaterialButton>(R.id.editDone)

        gamepadView.onSelectionChange = { updateEditBar() }
        // 点击编辑栏文字：切换滑杆目标（大小 ⇄ 透明度）
        editLabel.setOnClickListener {
            gamepadView.editTarget = if (gamepadView.editTarget == 0) 1 else 0
            updateEditBar()
        }
        editSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val key = gamepadView.selectedKey ?: return
                if (gamepadView.editTarget == 0) {
                    gamepadView.setControlScale(key, 0.5f + progress / 100f)
                } else {
                    gamepadView.setControlAlpha(key, 0.35f + progress / 100f * 0.65f)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putString("layout_v1", gamepadView.layoutJson()).apply()
            }
        })
        resetOne.setOnClickListener {
            gamepadView.selectedKey?.let {
                gamepadView.resetControl(it)
                prefs.edit().putString("layout_v1", gamepadView.layoutJson()).apply()
            }
            updateEditBar()
        }
        resetAll.setOnClickListener {
            gamepadView.resetLayout()
            prefs.edit().putString("layout_v1", gamepadView.layoutJson()).apply()
            updateEditBar()
        }
        done.setOnClickListener {
            prefs.edit().putString("layout_v1", gamepadView.layoutJson()).apply()
            gamepadView.editMode = false
            editBar.visibility = android.view.View.GONE
        }

        // 一键体验：保存过服务器地址就自动重连，免去每次手动点"连接"
        if (prefs.getString("ip", "")?.isNotBlank() == true) {
            connect()
        }
    }

    // ---------- 设置面板 ----------

    private fun openSettings() {
        val ctx = this
        val d = resources.displayMetrics.density
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (10 * d).toInt(), (22 * d).toInt(), (14 * d).toInt())
        }

        fun label(text: String): TextView = TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(0, (16 * d).toInt(), 0, (2 * d).toInt())
        }

        /**
         * 连续滑杆：不使用 stepSize，规避 Material Slider 的浮点步进校验
         * （stepSize=0.05 时，浮点噪声可能触发 IllegalStateException 直接崩溃）。
         * 显示时四舍五入到 5% 粒度，应用时取连续值即可。
         */
        fun slider(
            value: Float,
            fmt: (Float) -> String,
            onChange: (Float) -> Unit
        ): Slider = Slider(ctx).apply {
            valueFrom = 0.75f
            valueTo = 1.30f
            this.value = value.coerceIn(0.75f, 1.30f)
            setLabelFormatter { "${(it * 100).roundToInt()}%" }
            addOnChangeListener { _, v, _ -> onChange(v) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun toggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch =
            MaterialSwitch(ctx).apply {
                this.text = text
                isChecked = checked
                textSize = 15f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                setOnCheckedChangeListener { _, c -> onChange(c) }
            }

        fun saveScale(key: String, value: Float) {
            prefs.edit().putFloat(key, value).apply()
        }

        val sheet = BottomSheetDialog(ctx)

        // 布局编辑入口放在最上方，展开即可见，避免被滑杆挤出屏幕
        container.addView(
            MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "自定义按键布局（逐键拖动 / 调大小）"
                textSize = 13f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    sheet.dismiss()
                    enterEditMode()
                }
            }
        )

        // 右摇杆控制（陀螺仪相关）放在最前，展开即可见
        container.addView(label("右摇杆控制"))
        val gyroSw = toggle("陀螺仪瞄准（转手机 = 转视角）", wantGyro) {
            wantGyro = it
            applyGyroState()
            if (it) {
                toast("陀螺仪瞄准已开启：转动手机即可转视角")
            }
        }
        if (gyroAim?.available != true) {
            gyroSw.isEnabled = false
            gyroSw.text = "陀螺仪瞄准（此设备不支持）"
        }
        container.addView(gyroSw)
        container.addView(
            Slider(ctx).apply {
                valueFrom = 0.4f
                valueTo = 3.0f
                value = (gyroAim?.gain ?: 1.0f).coerceIn(0.4f, 3.0f)
                setLabelFormatter { "${(it * 100).toInt()}%" }
                addOnChangeListener { _, v, _ ->
                    gyroAim?.gain = v
                    prefs.edit().putFloat("gyro_gain", v).apply()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        )
        container.addView(
            toggle("陀螺仪俯仰反向（若抬头变低头则打开）",
                prefs.getBoolean("gyro_invert_y", false)) {
                prefs.edit().putBoolean("gyro_invert_y", it).apply()
                gyroAim?.invertY = it
            }
        )
        container.addView(
            toggle("陀螺仪左右反向（若左转变右转则打开）",
                prefs.getBoolean("gyro_invert_x", false)) {
                prefs.edit().putBoolean("gyro_invert_x", it).apply()
                gyroAim?.invertX = it
            }
        )

        container.addView(label("控件整体大小"))
        container.addView(
            slider(gamepadView.uiScale, { "${(it * 100).toInt()}%" }) {
                gamepadView.uiScale = it
                saveScale("ui_scale", it)
            }
        )

        container.addView(label("摇杆大小"))
        container.addView(
            slider(gamepadView.stickScale, { "${(it * 100).toInt()}%" }) {
                gamepadView.stickScale = it
                saveScale("stick_scale", it)
            }
        )

        container.addView(label("按键大小（ABXY / 肩键 / 方向键 / 扳机）"))
        container.addView(
            slider(gamepadView.buttonScale, { "${(it * 100).toInt()}%" }) {
                gamepadView.buttonScale = it
                saveScale("button_scale", it)
            }
        )

        container.addView(label("震动"))
        container.addView(
            toggle("按键触感反馈", hapticsOn) {
                hapticsOn = it
                gamepadView.hapticsEnabled = it
                prefs.edit().putBoolean("haptics", it).apply()
            }
        )
        container.addView(
            toggle("游戏震动回传（由电脑端游戏驱动）", rumbleOn) {
                rumbleOn = it
                prefs.edit().putBoolean("rumble", it).apply()
            }
        )

        container.addView(
            MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "恢复默认大小"
                textSize = 13f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (18 * d).toInt() }
                setOnClickListener {
                    prefs.edit()
                        .putFloat("ui_scale", 1f)
                        .putFloat("stick_scale", 1f)
                        .putFloat("button_scale", 1f)
                        .apply()
                    gamepadView.uiScale = 1f
                    gamepadView.stickScale = 1f
                    gamepadView.buttonScale = 1f
                    sheet.dismiss()
                    openSettings()
                }
            }
        )

        // 内容包进可滚动容器：横屏下设置项超过一屏，否则底部项被裁掉看不见
        // 显式 MATCH_PARENT：默认 WRAP_CONTENT 会让面板内容收缩变窄
        container.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val scroll = androidx.core.widget.NestedScrollView(ctx)
        scroll.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        scroll.addView(container)
        sheet.setContentView(scroll)
        // 直接全展开，保证"自定义按键布局"入口不被折叠隐藏
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    // ---------- 逐键布局编辑 ----------

    private fun enterEditMode() {
        gamepadView.editMode = true
        editBar.visibility = android.view.View.VISIBLE
        updateEditBar()
    }

    private fun updateEditBar() {
        val key = gamepadView.selectedKey
        if (key == null) {
            editLabel.text = if (gamepadView.editTarget == 0)
                "点选按键：拖动移位，滑杆调大小（点此切换透明度）"
            else
                "点选按键：拖动移位，滑杆调透明度（点此切回大小）"
            editSeek.isEnabled = false
        } else {
            if (gamepadView.editTarget == 0) {
                editLabel.text = "已选：${gamepadView.controlName(key)} · 大小"
                editSeek.max = 150
                editSeek.progress = ((gamepadView.controlScale(key) - 0.5f) / 1.5f * 150f)
                    .toInt().coerceIn(0, editSeek.max)
            } else {
                editLabel.text = "已选：${gamepadView.controlName(key)} · 透明度"
                editSeek.max = 100
                editSeek.progress = ((gamepadView.controlAlpha(key) - 0.35f) / 0.65f * 100f)
                    .toInt().coerceIn(0, editSeek.max)
            }
            editSeek.isEnabled = true
        }
    }

    // ---------- 震动回传 ----------

    private fun obtainVibrator(): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: Exception) {
        null
    }

    private fun handleRumble(largeMotor: Int, smallMotor: Int) {
        if (!rumbleOn) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        val amp = max(largeMotor, smallMotor)
        if (amp < 8) {
            lastRumbleAmp = 0
            return
        }

        val gain = when (hapticsLevel) {
            1 -> 0.55f
            2 -> 0.80f
            else -> 1.05f
        }
        val a = (amp * gain).toInt().coerceIn(1, 255)
        val now = SystemClock.uptimeMillis()
        // 震动事件由游戏高频下发，仅在强度变化或超过 100ms 时真正起振
        if (a == lastRumbleAmp && now - lastRumbleAt < 100) return
        lastRumbleAt = now
        lastRumbleAmp = a
        v.vibrate(VibrationEffect.createOneShot(70, a))
    }

    // ---------- 连接 ----------

    private fun applyImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
    }

    private fun connect() {
        val host = ipInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull() ?: -1

        if (host.isEmpty()) {
            toast(getString(R.string.err_ip))
            return
        }
        if (port !in 1..65535) {
            toast(getString(R.string.err_port))
            return
        }

        prefs.edit().putString("ip", host).putInt("port", port).apply()

        if (sender.start(host, port, listener)) {
            connectBtn.text = getString(R.string.btn_disconnect)
            ipInput.isEnabled = false
            portInput.isEnabled = false
            statusText.text = "正在发送…"
            statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun disconnect() {
        sender.stop()
        gamepadView.releaseAll()
        connectBtn.text = getString(R.string.btn_connect)
        ipInput.isEnabled = true
        portInput.isEnabled = true
        statusText.text = getString(R.string.status_idle)
        statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
    }

    // ---------- 生命周期 ----------

    override fun onResume() {
        super.onResume()
        resumed = true
        applyGyroState()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        // 切后台：输入回中 + 冻结陀螺仪（否则传感器持续驱动视角，放兜里会乱转）
        gamepadView.releaseAll()
        applyGyroState()
    }

    override fun onDestroy() {
        sender.stop()
        resumed = false
        gyroAim?.stop()   // 注销传感器监听，避免泄漏
        super.onDestroy()
    }

    /** 按 开关设置 × 前后台状态 统一裁决陀螺仪的启停与右摇杆清零 */
    private fun applyGyroState() {
        val g = gyroAim ?: return
        val use = wantGyro && g.available && resumed && sender.isRunning
        gamepadView.state.useGyro = use
        if (use) {
            g.start()
        } else {
            g.stop()
            gamepadView.state.gyroRx = 0
            gamepadView.state.gyroRy = 0
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
