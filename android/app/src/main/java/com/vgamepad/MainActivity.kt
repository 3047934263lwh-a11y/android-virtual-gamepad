package com.vgamepad

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private companion object {
        /** 与 GamepadView 的 STICK_CLICK_* 保持一致 */
        const val CLICK_CENTER = 0
        const val CLICK_DOUBLE = 1
        const val CLICK_ALWAYS = 2
        /** 曾收到过回包、但超过该时间没再收到 → 判定链路已断 */
        const val LINK_LOST_MS = 6000L
        const val WATCHDOG_MS = 3000L
    }

    private lateinit var gamepadView: GamepadView
    private lateinit var ipInput: EditText
    private lateinit var portInput: EditText
    private lateinit var connectBtn: MaterialButton
    private lateinit var settingsBtn: ImageButton
    private lateinit var statusText: TextView
    private lateinit var connBar: LinearLayout
    private lateinit var titleBox: LinearLayout
    private lateinit var barTitle: TextView
    private lateinit var statusDot: View
    /** 展开态下点击栏外时吞掉本次手势，避免同时触发底下的虚拟按键 */
    private var swallowTouch = false
    private var barExpanded = false
    private var barFullWidth = 0
    private var barCollapsedWidth = 0
    private var barCurrentWidth = 0
    private var barAnim: ValueAnimator? = null
    private var gyroSens = 65f
    /** 右摇杆视角灵敏度（0–100 滑杆值，50 = 标准 1.0×），与陀螺仪灵敏度完全独立 */
    private var stickSens = 50f
    /** 摇杆按下（LS/RS）触发方式：0=轻点中心 1=双击区域内任意位置 2=操作摇杆即按下 */
    private var stickClickMode = CLICK_CENTER
    private lateinit var sender: UdpSender

    private lateinit var editBar: LinearLayout
    private lateinit var editLabel: TextView
    private lateinit var editSeek: SeekBar

    private val prefs by lazy { getSharedPreferences("vgp", Context.MODE_PRIVATE) }

    private var hapticsOn = true
    // 三组用户自定义震动参数（均为 0–100 的滑杆值）
    private var hapticStrengthP = 55f   // 触感强度
    private var hapticDurP = 26f        // 触感时长
    private var hapticGapP = 30f        // 触感最小间隔
    private var rumbleGainP = 80f       // 回传加强强度
    private var rumbleDurP = 24f        // 回传持续时长
    private var rumbleGapP = 32f        // 回传最小间隔
    private var rumbleOn = true
    private var gyroAim: GyroAim? = null
    private var wantGyro = false
    private var resumed = false
    private var playerNo = 0

    // ---- 运行期自动重连（用户主动断开时不再自动连回） ----
    private var userStopped = false
    private var autoRetryCount = 0
    private var lastReplyAt = 0L
    private var everGotReply = false
    private var linkError: String? = null
    private var lastPps = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val reconnectTask = Runnable { attemptReconnect() }
    private val watchdog = object : Runnable {
        override fun run() {
            checkLink()
            uiHandler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private var lastRumbleAt = 0L
    private var lastRumbleAmp = -1

    private val vibrator: Vibrator? by lazy { obtainVibrator() }

    private val listener = object : UdpSender.Listener {
        override fun onConnected(host: String, port: Int) {
            linkError = null
            applyGyroState()          // 链路建立后立刻按当前开关裁决陀螺仪
            renderStatus()
        }

        override fun onError(message: String) {
            linkError = message
            renderStatus()
        }

        override fun onStats(pps: Int) {
            if (!sender.isRunning) return
            lastPps = pps
            renderStatus()
        }

        override fun onStopped() {
            // 旧发送线程迟到的回调：新发送器已在运行时直接忽略，避免熄灭新链路的绿点
            if (sender.isRunning) return
            lastPps = 0
            if (userStopped) {
                resetConnectUi()
            } else {
                // 自动重连等待中：IP/端口保持可编辑，方便改掉填错的地址；
                // 按钮仍显示「断开」，点它即取消自动重连
                ipInput.isEnabled = true
                portInput.isEnabled = true
            }
            applyGyroState()          // 链路停止 → 同步停传感器，避免后台耗电
            renderStatus()
            if (!userStopped) scheduleReconnect()
        }

        override fun onRumble(largeMotor: Int, smallMotor: Int) {
            markReply()
            handleRumble(largeMotor, smallMotor)
        }

        override fun onPlayerAssigned(playerIndex: Int) {
            // 接收端每秒重发编号包：只在编号变化时弹 Toast。
            // 状态栏统一交给 renderStatus()，不再各自写 text，避免每秒抖动。
            val changed = playerNo != playerIndex
            playerNo = playerIndex
            if (changed) toast("本手机分配为 ${playerIndex}P 手柄")
            markReply()
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

        // ---- 顶部连接栏：默认收起，仅保留原位置「手柄」文字作为唯一开关 ----
        connBar = findViewById(R.id.connBar)
        titleBox = findViewById(R.id.titleBox)
        barTitle = findViewById(R.id.barTitle)
        statusDot = findViewById(R.id.statusDot)
        titleBox.setOnClickListener { toggleBar() }
        // 让连接栏自身消费落在栏内空白处的触摸：否则会穿透到下层全屏 GamepadView，
        // 被误判为"点击外部"而收起（点击栏内任何位置都不应收起）。
        connBar.isClickable = true
        connBar.setOnClickListener {
            if (!barExpanded) expandBar()
        }

        // 展开态：点击连接栏以外的任何区域也收起（与点「手柄」文字走同一个 collapseBar 动画）。
        // 点击栏内部由栏自身消费，不会走到这里，因此不会误收起。
        gamepadView.setOnTouchListener { _, ev ->
            if (barExpanded && ev.actionMasked == MotionEvent.ACTION_DOWN) {
                swallowTouch = true
                collapseBar()
            }
            if (swallowTouch) {
                if (ev.actionMasked == MotionEvent.ACTION_UP ||
                    ev.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    swallowTouch = false
                }
                true    // 吞掉整段手势，避免误触发底下的虚拟按键
            } else {
                false
            }
        }

        connBar.post {
            val dm = resources.displayMetrics
            barFullWidth = dm.widthPixels - dp(20)
            barCollapsedWidth = measureCollapsedWidth()
            barCurrentWidth = barCollapsedWidth
            applyBarWidth(barCollapsedWidth)
            setBarContentVisible(false)
            connBar.visibility = View.VISIBLE
        }

        ipInput.setText(prefs.getString("ip", ""))
        portInput.setText(prefs.getInt("port", UdpSender.DEFAULT_PORT).toString())

        sender = UdpSender(gamepadView.state)
        gyroAim = GyroAim(this)
        gyroAim?.onAim = { rx, ry ->
            gamepadView.state.gyroRx = (rx * 32767).toInt()
            gamepadView.state.gyroRy = (ry * 32767).toInt()
        }
        // 统一的「偏好 → 内存状态」入口，onCreate 与「恢复默认」共用
        loadPrefsIntoState()

        connectBtn.setOnClickListener {
            if (sender.isRunning) disconnect() else connect()
        }
        settingsBtn.setOnClickListener { collapseBar(); openSettings() }

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
                // updateEditBar() 会程序化设置 progress；不区分来源会反过来改写刚选中的控件
                if (!fromUser) return
                val key = gamepadView.selectedKey ?: return
                if (gamepadView.editTarget == 0) {
                    gamepadView.setControlScale(key, 0.5f + progress / 100f)
                } else {
                    // 0..100 ↔ 0.05..1.0，与 updateEditBar 的回显公式严格互逆
                    gamepadView.setControlAlpha(key, 0.05f + progress / 100f * 0.95f)
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

        // 一键体验：保存过服务器地址就自动连接；之后由自动重连状态机维持链路
        registerNetworkCallback()
        if (prefs.getString("ip", "")?.isNotBlank() == true) {
            connect()
        }
    }

    /**
     * 把 SharedPreferences 中的全部可调设置读入内存并应用到各模块。
     * onCreate 与「恢复默认」共用，保证默认值只在一处定义。
     * 不含 IP / 端口 —— 那属于连接目标，不是可调设置。
     */
    private fun loadPrefsIntoState() {
        hapticsOn = prefs.getBoolean("haptics", true)
        hapticStrengthP = prefs.getFloat("haptic_strength", 55f).coerceIn(0f, 100f)
        hapticDurP = prefs.getFloat("haptic_dur", 26f).coerceIn(0f, 100f)
        hapticGapP = prefs.getFloat("haptic_gap", 30f).coerceIn(0f, 100f)
        rumbleOn = prefs.getBoolean("rumble", true)
        rumbleGainP = prefs.getFloat("rumble_gain", 80f).coerceIn(0f, 100f)
        rumbleDurP = prefs.getFloat("rumble_dur", 24f).coerceIn(0f, 100f)
        rumbleGapP = prefs.getFloat("rumble_gap", 32f).coerceIn(0f, 100f)

        gamepadView.uiScale = prefs.getFloat("ui_scale", 1f)
        gamepadView.stickScale = prefs.getFloat("stick_scale", 1f)
        gamepadView.buttonScale = prefs.getFloat("button_scale", 1f)
        gamepadView.loadLayout(prefs.getString("layout_v1", null))
        gamepadView.loadHidden(prefs.getString("hidden_keys", null))
        gamepadView.hapticsEnabled = hapticsOn
        applyHapticParams()

        wantGyro = prefs.getBoolean("gyro", false)
        // 灵敏度是 0–100 的百分比；旧版 gyro_gain（倍率）一次性迁移
        if (!prefs.contains("gyro_sens")) {
            val oldGain = prefs.getFloat("gyro_gain", 1.0f)
                .coerceIn(GyroAim.SENS_MIN_GAIN, GyroAim.SENS_MAX_GAIN)
            val t = (kotlin.math.ln((oldGain / GyroAim.SENS_MIN_GAIN).toDouble()) /
                     kotlin.math.ln((GyroAim.SENS_MAX_GAIN / GyroAim.SENS_MIN_GAIN).toDouble()))
                .toFloat().coerceIn(0f, 1f)
            prefs.edit().putFloat("gyro_sens", (t * 100f).roundToInt().toFloat()).apply()
        }
        gyroSens = prefs.getFloat("gyro_sens", 65f).coerceIn(0f, 100f).roundToInt().toFloat()
        gyroAim?.gain = GyroAim.gainForSens(gyroSens / 100f)
        stickSens = prefs.getFloat("stick_sens", 50f).coerceIn(0f, 100f).roundToInt().toFloat()
        gamepadView.state.stickSens = stickGain(stickSens)
        stickClickMode = prefs.getInt("stick_click_mode", CLICK_CENTER)
            .coerceIn(CLICK_CENTER, CLICK_ALWAYS)
        gamepadView.stickClickMode = stickClickMode
        gyroAim?.invertY = prefs.getBoolean("gyro_invert_y", false)
        gyroAim?.invertX = prefs.getBoolean("gyro_invert_x", false)
    }

    /** 恢复出厂默认：清空除连接地址外的全部偏好，重新载入默认值并重建设置面板。 */
    private fun confirmResetAll() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("恢复默认设置")
            .setMessage(
                "将把所有设置恢复为软件默认值：\n" +
                "按键布局、隐藏的按键、三个尺寸滑杆、两路视角灵敏度、" +
                "摇杆按下方式、陀螺仪开关与反向、按键触感与游戏震动回传。\n\n" +
                "当前连接的电脑 IP 和端口会保留，不会断开连接。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复默认") { _, _ ->
                val keepIp = prefs.getString("ip", "") ?: ""
                val keepPort = prefs.getInt("port", UdpSender.DEFAULT_PORT)
                prefs.edit().clear().apply()
                prefs.edit().putString("ip", keepIp).putInt("port", keepPort).apply()

                loadPrefsIntoState()
                applyGyroState()
                gamepadView.releaseAll()
                ipInput.setText(keepIp)
                portInput.setText(keepPort.toString())

                hideSettings()
                // 等上一层遮罩淡出结束再重建，避免两层叠加闪烁
                uiHandler.postDelayed({
                    if (!isFinishing && !isDestroyed) openSettings()
                }, 200L)
            }
            .show()
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
         * 0–100 百分比滑杆：数值范围/步长/标签与「陀螺仪灵敏度」滑杆完全一致。
         * stepSize=1 时 value 必须是整数，赋值前统一取整（否则 Material Slider 抛异常）。
         */
        fun pctSlider(value: Float, onChange: (Float) -> Unit): Slider = Slider(ctx).apply {
            valueFrom = 0f
            valueTo = 100f
            stepSize = 1f
            this.value = value.roundToInt().toFloat().coerceIn(0f, 100f)
            setLabelFormatter { "${it.roundToInt()}%" }
            addOnChangeListener { _, v, _ -> onChange(v) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        /** 尺寸滑杆：0–100 映射到 0.5×–1.5×（50 = 默认大小）。 */
        fun sizeSlider(scale: Float, onChange: (Float) -> Unit): Slider =
            pctSlider((scale - 0.5f) * 100f) { onChange(0.5f + it / 100f) }

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
                    hideSettings()
                    enterEditMode()
                }
            }
        )

        // ---- 隐藏任意按键：默认收起，点标题展开，避免一屏铺开 14 个勾选项 ----
        val hideContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val hideTitle = TextView(ctx).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(0, (16 * d).toInt(), 0, (12 * d).toInt())
            minHeight = (40 * d).toInt()
            gravity = android.view.Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }
        var hideExpanded = false
        fun hiddenCount(): Int = gamepadView.controlKeys().count { gamepadView.isHidden(it) }
        fun renderHideTitle() {
            val n = hiddenCount()
            val suffix = if (n > 0) "（已隐藏 $n 个）" else "（勾选后不显示、也不再响应）"
            hideTitle.text = (if (hideExpanded) "▾  " else "▸  ") + "隐藏按键" + suffix
        }
        renderHideTitle()
        hideTitle.setOnClickListener {
            hideExpanded = !hideExpanded
            hideContent.visibility = if (hideExpanded) View.VISIBLE else View.GONE
            renderHideTitle()
        }
        container.addView(hideTitle)

        val hideRows = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val hideBoxes = ArrayList<MaterialCheckBox>()
        var row: LinearLayout? = null
        gamepadView.controlKeys().forEachIndexed { i, key ->
            if (i % 2 == 0) {
                val r = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                hideRows.addView(r)
                row = r
            }
            val cb = MaterialCheckBox(ctx).apply {
                text = gamepadView.controlName(key)
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                // 先赋值再注册监听，避免初始化时误写入偏好
                isChecked = gamepadView.isHidden(key)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnCheckedChangeListener { _, checked ->
                    gamepadView.setHidden(key, checked)
                    prefs.edit().putString("hidden_keys", gamepadView.hiddenJson()).apply()
                    renderHideTitle()
                }
            }
            hideBoxes.add(cb)
            row?.addView(cb)
        }
        hideContent.addView(hideRows)
        hideContent.addView(
            MaterialButton(
                ctx, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "全部显示"
                textSize = 13f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    hideBoxes.forEach { it.isChecked = false }
                    prefs.edit().putString("hidden_keys", gamepadView.hiddenJson()).apply()
                    renderHideTitle()
                }
            }
        )
        container.addView(hideContent)

        // 右摇杆控制（陀螺仪相关）放在最前，展开即可见
        container.addView(label("右摇杆控制"))
        val gyroSw = toggle("陀螺仪瞄准（转手机 = 转视角）", wantGyro) {
            wantGyro = it
            prefs.edit().putBoolean("gyro", it).apply()   // 重启后仍保持用户选择
            applyGyroState()
            if (it) {
                toast("陀螺仪瞄准已开启：转动手机即可转视角")
            }
        }
        container.addView(gyroSw)
        container.addView(label("陀螺仪视角灵敏度（65 = 标准）"))
        val gyroSensSlider = Slider(ctx).apply {
            valueFrom = 0f
            valueTo = 100f
            stepSize = 1f
            value = gyroSens.roundToInt().toFloat().coerceIn(0f, 100f)
            setLabelFormatter { "${it.roundToInt()}%" }
            addOnChangeListener { _, v, _ ->
                gyroSens = v
                gyroAim?.gain = GyroAim.gainForSens(v / 100f)
                prefs.edit().putFloat("gyro_sens", v).apply()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(gyroSensSlider)
        container.addView(label("右摇杆视角灵敏度（50 = 标准）"))
        container.addView(
            pctSlider(stickSens) { v ->
                stickSens = v
                gamepadView.state.stickSens = stickGain(v)
                prefs.edit().putFloat("stick_sens", v).apply()
            }
        )
        val invYSw = toggle("陀螺仪俯仰反向（若抬头变低头则打开）",
            prefs.getBoolean("gyro_invert_y", false)) {
            prefs.edit().putBoolean("gyro_invert_y", it).apply()
            gyroAim?.invertY = it
        }
        container.addView(invYSw)
        val invXSw = toggle("陀螺仪左右反向（若左转变右转则打开）",
            prefs.getBoolean("gyro_invert_x", false)) {
            prefs.edit().putBoolean("gyro_invert_x", it).apply()
            gyroAim?.invertX = it
        }
        container.addView(invXSw)
        if (gyroAim?.available != true) {
            // 无传感器时下游三项完全无效，直接禁用而不是让用户白调
            gyroSw.isEnabled = false
            gyroSw.text = "陀螺仪瞄准（此设备不支持）"
            gyroSensSlider.isEnabled = false
            invYSw.isEnabled = false
            invXSw.isEnabled = false
        }

        container.addView(label("摇杆按下（LS / RS）触发方式"))
        val clickGroup = android.widget.RadioGroup(ctx).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val clickOptions = arrayOf(
            "轻点摇杆中心 = 按下",
            "双击摇杆内任意位置 = 按下",
            "只要操作摇杆就持续按下"
        )
        clickOptions.forEachIndexed { idx, text ->
            val rb = com.google.android.material.radiobutton.MaterialRadioButton(ctx).apply {
                // RadioGroup 依赖子项 id 做互斥，没有 id 时会出现多项同时选中
                id = 1000 + idx
                this.text = text
                textSize = 14f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                isChecked = stickClickMode == idx
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        stickClickMode = idx
                        gamepadView.stickClickMode = idx
                        prefs.edit().putInt("stick_click_mode", idx).apply()
                    }
                }
            }
            clickGroup.addView(rb)
        }
        container.addView(clickGroup)

        container.addView(label("控件整体大小（50 = 默认）"))
        container.addView(
            sizeSlider(gamepadView.uiScale) {
                gamepadView.uiScale = it
                saveScale("ui_scale", it)
            }
        )

        container.addView(label("摇杆大小（50 = 默认）"))
        container.addView(
            sizeSlider(gamepadView.stickScale) {
                gamepadView.stickScale = it
                saveScale("stick_scale", it)
            }
        )

        container.addView(label("按键大小（ABXY / 肩键 / 方向键 / 扳机，50 = 默认）"))
        container.addView(
            sizeSlider(gamepadView.buttonScale) {
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
        // 按键触感反馈：三个自定义值（强度 / 时长 / 最小间隔）
        container.addView(label("触感强度"))
        container.addView(
            pctSlider(hapticStrengthP) {
                hapticStrengthP = it
                prefs.edit().putFloat("haptic_strength", it).apply()
                applyHapticParams()
            }
        )
        container.addView(label("触感时长"))
        container.addView(
            pctSlider(hapticDurP) {
                hapticDurP = it
                prefs.edit().putFloat("haptic_dur", it).apply()
                applyHapticParams()
            }
        )
        container.addView(label("触感最小间隔"))
        container.addView(
            pctSlider(hapticGapP) {
                hapticGapP = it
                prefs.edit().putFloat("haptic_gap", it).apply()
                applyHapticParams()
            }
        )

        container.addView(
            toggle("游戏震动回传（由电脑端游戏驱动）", rumbleOn) {
                rumbleOn = it
                prefs.edit().putBoolean("rumble", it).apply()
            }
        )
        // 手机自身振动叠加，用于加强游戏回传的振动：三个自定义值
        container.addView(label("手机自身振动加强（叠加在游戏回传上）· 强度"))
        container.addView(
            pctSlider(rumbleGainP) {
                rumbleGainP = it
                prefs.edit().putFloat("rumble_gain", it).apply()
            }
        )
        container.addView(label("手机自身振动加强 · 时长"))
        container.addView(
            pctSlider(rumbleDurP) {
                rumbleDurP = it
                prefs.edit().putFloat("rumble_dur", it).apply()
            }
        )
        container.addView(label("手机自身振动加强 · 最小间隔"))
        container.addView(
            pctSlider(rumbleGapP) {
                rumbleGapP = it
                prefs.edit().putFloat("rumble_gap", it).apply()
            }
        )

        container.addView(
            MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "恢复默认"
                textSize = 13f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (18 * d).toInt() }
                setOnClickListener { confirmResetAll() }
            }
        )

        // 内容包进可滚动容器：横屏下设置项超过一屏，否则底部项被裁掉看不见
        // 显式 MATCH_PARENT：默认 WRAP_CONTENT 会让面板内容收缩变窄
        container.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val scroll = androidx.core.widget.NestedScrollView(ctx)
        // 横屏下 WRAP_CONTENT 会被压成一条（内容被裁），必须给显式高度，内部滚动
        scroll.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        scroll.addView(container)
        showSettingsPanel(scroll)
    }

    // ---------- 设置面板（自绘覆盖层，不依赖 BottomSheet） ----------

    private var settingsOverlay: android.view.View? = null

    private fun showSettingsPanel(content: android.view.View) {
        hideSettings()
        val ctx = this
        val root = findViewById<android.view.ViewGroup>(R.id.root)
        val dm = resources.displayMetrics

        val overlay = android.widget.FrameLayout(ctx).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xCC05070A.toInt())
            isClickable = true            // 吞掉面板外的点击
            setOnClickListener { hideSettings() }
        }
        val card = android.widget.FrameLayout(ctx).apply {
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_bar)
            clipToOutline = false
            isClickable = true
            setOnClickListener { }        // 面板内点击不穿透
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (dm.heightPixels * 0.86f).toInt()
            ).apply {
                gravity = android.view.Gravity.BOTTOM
                leftMargin = (34 * dm.density).toInt()
                rightMargin = (34 * dm.density).toInt()
                bottomMargin = (10 * dm.density).toInt()
            }
        }
        card.addView(content)
        overlay.addView(card)
        root.addView(overlay)
        settingsOverlay = overlay

        card.translationY = dm.heightPixels.toFloat()
        card.animate().translationY(0f).setDuration(280L)
            .setInterpolator(DecelerateInterpolator(1.4f)).start()
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(220L).start()
    }

    private fun hideSettings() {
        val overlay = settingsOverlay ?: return
        settingsOverlay = null
        overlay.animate().alpha(0f).setDuration(180L)
            .withEndAction { (overlay.parent as? android.view.ViewGroup)?.removeView(overlay) }
            .start()
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
                editSeek.progress = ((gamepadView.controlAlpha(key) - 0.05f) / 0.95f * 100f)
                    .toInt().coerceIn(0, editSeek.max)
            }
            editSeek.isEnabled = true
        }
    }

    // ---------- 震动回传 ----------

    /** 右摇杆灵敏度滑杆值（0–100）→ 增益：0→0.4×，50→1.0×（标准），100→1.6×。 */
    private fun stickGain(p: Float): Float = 0.4f + p.coerceIn(0f, 100f) * 0.012f

    /** 把 0–100 的三个触感滑杆值换算为 GamepadView 的实际参数。 */
    private fun applyHapticParams() {
        gamepadView.hapticStrength = hapticStrengthP / 100f
        gamepadView.hapticDurationMs = (10f + hapticDurP * 0.7f).toInt().coerceIn(5, 80)
        gamepadView.hapticIntervalMs = (hapticGapP * 2f).toInt().coerceIn(0, 200)
    }

    /** 「手柄」框内状态点：已连接=绿色，未连接=暗灰。 */
    private fun setLinkDot(on: Boolean) {
        statusDot.setBackgroundResource(if (on) R.drawable.bg_dot_ok else R.drawable.bg_dot_idle)
    }

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

        val gain = rumbleGainP / 100f
        if (gain <= 0f) return
        val a = (amp * gain).toInt().coerceIn(1, 255)
        val now = SystemClock.uptimeMillis()
        val gap = (30f + rumbleGapP * 2.2f).toInt()
        val dur = (30f + rumbleDurP * 1.7f).toInt()
        // 震动事件由游戏高频下发，仅在强度变化或超过最小间隔时真正起振
        if (a == lastRumbleAmp && now - lastRumbleAt < gap) return
        lastRumbleAt = now
        lastRumbleAmp = a
        v.vibrate(VibrationEffect.createOneShot(dur.toLong(), a))
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

    /** 状态栏单一数据源：连接态、错误、重连提示、pps、玩家号只在这里渲染 */
    private fun renderStatus() {
        val ok = ContextCompat.getColor(this, R.color.ok)
        val err = ContextCompat.getColor(this, R.color.err)
        val dim = ContextCompat.getColor(this, R.color.text_secondary)

        if (!sender.isRunning) {
            statusText.text = if (userStopped || !resumed) getString(R.string.status_idle)
                              else "连接中断，正在自动重连…"
            statusText.setTextColor(dim)
            setLinkDot(false)
            return
        }
        val e = linkError
        if (e != null) {
            statusText.text = e
            statusText.setTextColor(err)
            setLinkDot(false)
            return
        }
        if (everGotReply && SystemClock.uptimeMillis() - lastReplyAt > LINK_LOST_MS) {
            statusText.text = "连接中断，正在自动重连…"
            statusText.setTextColor(dim)
            setLinkDot(false)
            return
        }
        // UDP 本身无连接：收到过 PC 回包才算真正「已连接」
        val linked = everGotReply
        statusText.text = (if (linked) "已连接" else "正在连接") + " · $lastPps pps" +
            (if (playerNo > 0) " · ${playerNo}P" else "")
        statusText.setTextColor(if (linked) ok else dim)
        setLinkDot(linked)
    }

    private fun resetConnectUi() {
        connectBtn.text = getString(R.string.btn_connect)
        ipInput.isEnabled = true
        portInput.isEnabled = true
    }

    /** 收到任何 PC 回包 = 链路确实活着（Python 端每秒下发 VGPP） */
    private fun markReply() {
        lastReplyAt = SystemClock.uptimeMillis()
        everGotReply = true
        linkError = null
        autoRetryCount = 0
        renderStatus()
    }

    private fun startSender(host: String, port: Int) {
        if (sender.start(host, port, listener)) {
            connectBtn.text = getString(R.string.btn_disconnect)
            ipInput.isEnabled = false
            portInput.isEnabled = false
        }
        renderStatus()
    }

    private fun scheduleReconnect(delayMs: Long = -1L) {
        if (userStopped || !resumed) return
        uiHandler.removeCallbacks(reconnectTask)
        val backoff = 2000L shl (autoRetryCount.coerceAtMost(3))
        val d = if (delayMs >= 0) delayMs else backoff.coerceAtMost(15000L)
        uiHandler.postDelayed(reconnectTask, d)
        renderStatus()
    }

    private fun attemptReconnect() {
        if (userStopped || !resumed || sender.isRunning) return
        val host = ipInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull() ?: -1
        if (host.isEmpty() || port !in 1..65535) return
        autoRetryCount++
        linkError = null
        // 新链路先当作「尚未收到回包」，避免刚重连就被 watchdog 判死而无限重启
        everGotReply = false
        lastReplyAt = 0L
        startSender(host, port)
    }

    private fun checkLink() {
        if (userStopped || !resumed) return
        if (!sender.isRunning) {
            scheduleReconnect()
            return
        }
        // 曾连上过却长时间再无回包：多半是 PC 接收端重启或 Wi-Fi 切换，
        // 重启发送器以重建 socket / 重新解析地址
        if (everGotReply && SystemClock.uptimeMillis() - lastReplyAt > LINK_LOST_MS) {
            sender.stop()
            scheduleReconnect(500L)
            renderStatus()
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                uiHandler.post {
                    if (!userStopped && resumed && !sender.isRunning) scheduleReconnect(500L)
                }
            }
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(req, cb)
            networkCallback = cb
        } catch (e: Exception) {
            // 个别机型无权限时退化为定时重试，不影响主流程
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
        userStopped = false
        autoRetryCount = 0
        everGotReply = false
        lastReplyAt = 0L
        linkError = null
        uiHandler.removeCallbacks(reconnectTask)
        startSender(host, port)
        applyGyroState()
    }

    private fun disconnect() {
        userStopped = true
        uiHandler.removeCallbacks(reconnectTask)
        sender.stop()
        gamepadView.releaseAll()
        playerNo = 0
        lastPps = 0
        everGotReply = false
        linkError = null
        autoRetryCount = 0
        applyGyroState()
        resetConnectUi()
        renderStatus()
    }

    // ---------- 顶部连接栏展开 / 收起 ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 收起宽度 = 栏左右内边距 + 「手柄」框完整盒宽（自身 padding + 文字 + 状态点 + marginEnd）。
     * 用 Paint 测量，避免首帧 View.width 尚未测量导致算成 0、文字被压成两行或裁掉。
     */
    private fun measureCollapsedWidth(): Int {
        val text = barTitle.text?.toString() ?: ""
        val spacing = barTitle.letterSpacing * barTitle.textSize * text.length
        val textW = barTitle.paint.measureText(text) + spacing
        val dotW = dp(7) + dp(5)    // 状态点直径 + 与文字的间距
        val titleBoxW = titleBox.paddingStart + titleBox.paddingEnd + textW + dotW
        val marginEnd = (titleBox.layoutParams as? ViewGroup.MarginLayoutParams)?.rightMargin ?: 0
        // 必须容纳：栏 paddingStart + 标题盒宽 + 标题 marginEnd + 栏 paddingEnd，再留 4dp 余量
        return dp(12) + titleBoxW.toInt() + marginEnd + dp(12) + dp(4)
    }

    private fun applyBarWidth(w: Int) {
        barCurrentWidth = w
        val lp = connBar.layoutParams
        lp.width = w
        connBar.layoutParams = lp
    }

    /** 除「手柄」标题外的所有控件显隐：INVISIBLE 保留布局尺寸、但不绘制也不接收触摸。 */
    private fun setBarContentVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        for (i in 1 until connBar.childCount) connBar.getChildAt(i).visibility = v
    }

    private fun toggleBar() {
        if (barExpanded) collapseBar() else expandBar()
    }

    /**
     * 从「手柄」文字处向右展开：动画驱动连接栏宽度（子项尺寸固定、超出部分裁剪），
     * 视觉上即"从左向右依次显现"，且不做位移动画、无位移动画的重叠问题。
     */
    private fun expandBar() {
        if (barExpanded) return
        barExpanded = true
        barAnim?.cancel()
        setBarContentVisible(true)
        val from = if (barCurrentWidth > 0) barCurrentWidth else barCollapsedWidth
        barAnim = ValueAnimator.ofInt(from, barFullWidth).apply {
            duration = 300L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { applyBarWidth(it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    applyBarWidth(barFullWidth)
                }
            })
            start()
        }
    }

    /** 从右向左收回，终点即「手柄」文字所在的窄条。 */
    private fun collapseBar() {
        if (!barExpanded) return
        barExpanded = false
        barAnim?.cancel()
        barCollapsedWidth = measureCollapsedWidth()
        val from = if (barCurrentWidth > 0) barCurrentWidth else barFullWidth
        barAnim = ValueAnimator.ofInt(from, barCollapsedWidth).apply {
            duration = 280L
            interpolator = AccelerateInterpolator(1.5f)
            addUpdateListener { applyBarWidth(it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    setBarContentVisible(false)   // 收尾彻底隐藏，避免边缘露出下个控件
                }
            })
            start()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (settingsOverlay != null) {
            hideSettings()
            return
        }
        if (gamepadView.editMode) {
            prefs.edit().putString("layout_v1", gamepadView.layoutJson()).apply()
            gamepadView.editMode = false
            editBar.visibility = View.GONE
            return
        }
        if (barExpanded) {
            collapseBar()
            return
        }
        super.onBackPressed()
    }

    // ---------- 生命周期 ----------

    override fun onResume() {
        super.onResume()
        resumed = true
        applyGyroState()
        uiHandler.removeCallbacks(watchdog)
        uiHandler.post(watchdog)
        // 之前在后台断开的，回到前台立即补一次重连
        if (!userStopped && !sender.isRunning) scheduleReconnect(500L)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        uiHandler.removeCallbacks(watchdog)
        uiHandler.removeCallbacks(reconnectTask)
        // 切后台：输入回中 + 冻结陀螺仪（否则传感器持续驱动视角，放兜里会乱转）
        gamepadView.releaseAll()
        applyGyroState()
    }

    override fun onDestroy() {
        userStopped = true
        uiHandler.removeCallbacksAndMessages(null)
        networkCallback?.let { cb ->
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            try { cm?.unregisterNetworkCallback(cb) } catch (e: Exception) { }
        }
        networkCallback = null
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
