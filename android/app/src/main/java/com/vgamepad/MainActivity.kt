package com.vgamepad

import android.content.Context
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.vgamepad.updater.UpdateManager
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private companion object {
        /** 与 GamepadView 的 STICK_CLICK_* 保持一致 */
        const val CLICK_CENTER = 0
        const val CLICK_DOUBLE = 1
        const val CLICK_ALWAYS = 2
        /** 与 GamepadView 的 TRIGGER_SLIDE_* 保持一致 */
        const val TRIG_SLIDE_OFF = 0
        const val TRIG_SLIDE_ON = 1
        /** 曾收到过回包、但超过该时间没再收到 → 触发自动重连（既有行为不变） */
        const val LINK_LOST_MS = 6000L
        /** 状态栏显示用兜底超时：PC 未发 VGPQ 时也能较快切「未连接」；
         *  取 2.5s 是为了给「每秒一次 VGPP」留足余量，避免正常抖动误判 */
        const val LINK_SHOW_MS = 2500L
        const val WATCHDOG_MS = 500L
        /** 编辑模式单键大小滑杆上限：450 ↔ 缩放 5.0×（500%），与 GamepadView.MAX_CTRL_SCALE 对应 */
        const val EDIT_SIZE_SEEK_MAX = 450
        /** 链路状态着色档位：空闲 / 连接中·重连中 / 已连接 / 断开·错误 */
        const val LINK_IDLE = 0
        const val LINK_WARN = 1
        const val LINK_OK = 2
        const val LINK_ERR = 3
        /** 状态点直径（dp）。改这里即可，measureCollapsedWidth 与布局同源 */
        const val DOT_DP = 10
    }

    private lateinit var gamepadView: GamepadView
    private lateinit var ipInput: EditText
    private lateinit var portInput: EditText
    private lateinit var connectSwitch: SlideSwitch
    /** visionOS 玻璃底：手柄画面的模糊快照，仅连接栏展开时显示 */
    private lateinit var blurOverlay: ImageView
    private var blurAnim: ValueAnimator? = null
    private lateinit var settingsBtn: ImageButton
    private lateinit var statusText: TextView
    private lateinit var connBar: LinearLayout
    private lateinit var barClip: FrameLayout
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
    /** 扳机（LT/RT）触发方式：0=按下后不触发滑动 1=按下后允许滑动（默认，兼容旧版行为） */
    private var triggerSlide = TRIG_SLIDE_ON
    /** 应用内自动更新（GitHub Releases 检查 / 下载 / 校验 / 系统安装） */
    private val updateManager by lazy { UpdateManager(this) }
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
    /** 用户本次是否「主动发起过连接且未撤销」。
     *  冷启动恒为 false：没有用户点击就不存在会话，因此不显示中断/重连提示，
     *  也不允许 watchdog 或网络回调自行发起连接。仅在 connect() 置 true、
     *  disconnect() 置 false；Activity 重建时经 savedInstanceState 延续。 */
    private var sessionActive = false
    private var autoRetryCount = 0
    private var lastReplyAt = 0L
    private var everGotReply = false
    /** PC 端主动结束会话（VGPQ）：状态立即显示未连接，直到再次收到 VGPP */
    private var pcStopped = false
    private var linkError: String? = null
    private var lastPps = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    /** 是否已有待执行的重连任务。看门狗每 500ms 会调用 scheduleReconnect()，
     *  若每次都 removeCallbacks + postDelayed(backoff≥2000ms)，任务会在到期前
     *  被反复推迟，永远不执行 —— 表现为「一直显示正在自动重连、却永不发起连接」。 */
    private var reconnectScheduled = false
    private val reconnectTask = Runnable {
        reconnectScheduled = false
        attemptReconnect()
    }
    private val watchdog = object : Runnable {
        override fun run() {
            checkLink()
            renderStatus()          // 定时刷新，保证兜底超时后状态栏及时归零
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
            // 本地发送速率由 SEND_HZ 固定决定（250Hz），与 PC 是否接收无关：
            // 若继续用它做展示源，PC 停止后仍会显示 250 pps 的假数值。
            // 状态栏 PPS 改为只认 PC 回传的实测速率（onLinkStats）。
        }

        override fun onLinkStats(pps: Int) {
            if (!sender.isRunning) return
            lastPps = pps
            markReply()
        }

        override fun onDisconnected() {
            if (!sender.isRunning) return
            // PC 端 Stop：立即切未连接，且不再沿用旧的 pps / 玩家号
            pcStopped = true
            lastPps = 0
            playerNo = 0
            linkError = null
            renderStatus()
        }

        override fun onStopped() {
            // 旧发送线程迟到的回调：新发送器已在运行时直接忽略，避免熄灭新链路的绿点
            if (sender.isRunning) return
            lastPps = 0
            pcStopped = false
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
            if (!userStopped && sessionActive) scheduleReconnect()
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
        // 必须在 super.onCreate 之前落定夜间模式，AppCompat 才会按该值创建 delegate，
        // 否则会在启动后再触发一次 Activity 重建（闪一下）。
        VgTheme.applyNightMode(VgTheme.mode(this))
        super.onCreate(savedInstanceState)
        // 主题切换（uiMode 不在 configChanges 内）等场景会重建 Activity：
        // 恢复「用户已手动断开」与「会话已由用户开启」两个标记，
        // 前者避免重建后被无条件自动重连覆盖用户意图，后者保证重建后链路继续自愈
        if (savedInstanceState != null) {
            userStopped = savedInstanceState.getBoolean("user_stopped", false)
            sessionActive = savedInstanceState.getBoolean("session_active", false)
            if (userStopped) sessionActive = false
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        applyImmersive()

        gamepadView = findViewById(R.id.gamepadView)
        gamepadView.applyTheme(VgTheme.resolve(this))
        ipInput = findViewById(R.id.ipInput)
        portInput = findViewById(R.id.portInput)
        // 横屏 + 沉浸式下输入法极易切到「全屏编辑」（抽取模式）：显式禁用，
        // 保证点击后在原位置直接弹出常规/悬浮键盘，输入框内联编辑
        val noExtract = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        ipInput.imeOptions = ipInput.imeOptions or noExtract
        portInput.imeOptions = portInput.imeOptions or noExtract
        connectSwitch = findViewById(R.id.connectSwitch)
        blurOverlay = findViewById(R.id.blurOverlay)
        settingsBtn = findViewById(R.id.settingsBtn)
        statusText = findViewById(R.id.statusText)

        // ---- 顶部连接栏：默认收起，仅保留原位置「手柄」文字作为唯一开关 ----
        connBar = findViewById(R.id.connBar)
        barClip = findViewById(R.id.barClip)
        titleBox = findViewById(R.id.titleBox)
        barTitle = findViewById(R.id.barTitle)
        statusDot = findViewById(R.id.statusDot)
        // 首帧即落定中性色：drawable 默认是白底，不先着色会闪一下白块（必须在控件绑定之后）
        applyLinkState(LINK_IDLE)
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
            // 内层栏固定全宽：动画期间子控件约束恒定，不再逐帧重排
            val clp = connBar.layoutParams
            clp.width = barFullWidth
            connBar.layoutParams = clp
            barCollapsedWidth = measureCollapsedWidth()
            barCurrentWidth = barCollapsedWidth
            applyBarWidth(barCollapsedWidth)
            setBarContentVisible(false)
            barClip.visibility = View.VISIBLE
        }

        ipInput.setText(prefs.getString("ip", ""))
        portInput.setText(prefs.getInt("port", UdpSender.DEFAULT_PORT).toString())

        sender = UdpSender(gamepadView.state)
        // Wi-Fi 低延迟锁：省电模式会把 4ms 间隔的小包攒到 beacon 周期集中发出，
        // 造成输入延迟抖动与丢包（实测到达率 ~87%）。会话期间保持低延迟模式。
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else
                    @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "vgp-link"
            )?.apply { setReferenceCounted(false) }
        gyroAim = GyroAim(this)
        gyroAim?.onAim = { rx, ry ->
            gamepadView.state.gyroRx = (rx * 32767).toInt()
            gamepadView.state.gyroRy = (ry * 32767).toInt()
        }
        // 统一的「偏好 → 内存状态」入口，onCreate 与「恢复默认」共用
        loadPrefsIntoState()

        // 应用内自动更新：启动延迟检查（内部有 12h 节流 + 网络判断，失败静默）
        uiHandler.postDelayed({ updateManager.checkOnLaunch() },
            com.vgamepad.updater.UpdateConfig.LAUNCH_CHECK_DELAY_MS)

        // 开关滑块：点一下即切换，滑块左右滑动由 Material 组件自带动画完成
        connectSwitch.onCheckedChange = { checked ->
            if (checked) connect() else disconnect()
        }
        settingsBtn.setOnClickListener { collapseBar(); openSettings() }

        editBar = findViewById(R.id.editBar)
        editLabel = findViewById(R.id.editLabel)
        editSeek = findViewById(R.id.editSeek)
        val resetOne = findViewById<GlassButton>(R.id.editResetOne)
        val resetAll = findViewById<GlassButton>(R.id.editResetAll)
        val done = findViewById<GlassButton>(R.id.editDone)

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
                    gamepadView.setControlScale(key, editProgressToScale(progress))
                } else {
                    // 0..100 ↔ 0.05..1.0，与 updateEditBar 的回显公式严格互逆
                    gamepadView.setControlAlpha(key, 0.05f + progress / 100f * 0.95f)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) {
                persistLayoutAll()
            }
        })
        resetOne.setOnClickListener {
            gamepadView.selectedKey?.let {
                gamepadView.resetControl(it)
            }
            persistLayoutAll()
            updateEditBar()
        }
        resetAll.setOnClickListener {
            gamepadView.resetLayout()
            persistLayoutAll()
            updateEditBar()
        }
        done.setOnClickListener {
            persistLayoutAll()
            gamepadView.editMode = false
            editBar.visibility = android.view.View.GONE
        }

        // 网络状态回调只用于「会话已存在时」的快速重连；冷启动不触发任何连接
        registerNetworkCallback()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("user_stopped", userStopped)
        outState.putBoolean("session_active", sessionActive)
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
        gamepadView.stickStyle = prefs.getInt("stick_style", 0)
        gamepadView.bgEffect = if (prefs.contains("bg_effect")) prefs.getInt("bg_effect", 0)
        else if (prefs.getBoolean("star_field", true)) 1 else 0   // 旧偏好迁移
        gamepadView.starBurstEnabled = prefs.getBoolean("star_burst", true)
        gamepadView.loadLayout(prefs.getString("layout_v1", null))
        gamepadView.loadZorder(prefs.getString("zorder_v1", null))
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
        triggerSlide = prefs.getInt("trigger_slide", TRIG_SLIDE_ON)
            .coerceIn(TRIG_SLIDE_OFF, TRIG_SLIDE_ON)
        gamepadView.triggerSlide = triggerSlide
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
                "摇杆按下方式、扳机触发方式、陀螺仪开关与反向、按键触感与游戏震动回传。\n\n" +
                "当前连接的电脑 IP 和端口会保留，不会断开连接。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复默认") { _, _ ->
                val keepIp = prefs.getString("ip", "") ?: ""
                val keepPort = prefs.getInt("port", UdpSender.DEFAULT_PORT)
                prefs.edit().clear().apply()
                prefs.edit().putString("ip", keepIp).putInt("port", keepPort).apply()

                loadPrefsIntoState()
                // 外观也属于设置项：跟随系统 + 默认主题色
                VgTheme.applyNightMode(VgTheme.mode(this))
                gamepadView.applyTheme(VgTheme.resolve(this))
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


        // ---- 外观：Material 3 主题模式 + 主题色（Material You 动态取色 / 低饱和预设） ----
        val theme = VgTheme.resolve(this)
        container.addView(label("外观"))
        val modeRow = android.widget.RadioGroup(ctx).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
        }
        val currentMode = VgTheme.mode(this)
        listOf(
            "跟随系统" to VgTheme.Mode.SYSTEM,
            "浅色" to VgTheme.Mode.LIGHT,
            "深色" to VgTheme.Mode.DARK,
        ).forEachIndexed { i, pair ->
            val rb = com.google.android.material.radiobutton.MaterialRadioButton(ctx).apply {
                id = 3000 + i                 // 必须唯一，否则同组互斥失效
                text = pair.first
                textSize = 13f
                isChecked = currentMode == pair.second
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnCheckedChangeListener { _, checked ->
                    if (!checked) return@setOnCheckedChangeListener
                    if (VgTheme.mode(this@MainActivity) == pair.second) return@setOnCheckedChangeListener
                    // 切换夜间模式需要重建 Activity，AppCompat 会自动完成
                    VgTheme.setMode(this@MainActivity, pair.second)
                }
            }
            modeRow.addView(rb)
        }
        container.addView(modeRow)

        container.addView(label("主题色"))
        val swatchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (6 * d).toInt(), 0, (4 * d).toInt())
        }
        var currentAccent = VgTheme.accentIndex(this)
        val dotViews = ArrayList<View>()
        val dotIndex = ArrayList<Int>()
        val dotFill = ArrayList<Int>()
        val dotGrad = ArrayList<IntArray?>()

        fun swatchDrawable(
            index: Int, fill: Int, gradient: IntArray?, c: VgColors,
        ): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (gradient != null) {
                colors = gradient
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            } else {
                setColor(fill)
            }
            // 选中：主题墨色描边环；未选中：极淡描边保证浅色底上可见
            setStroke(
                (2.5f * d).toInt(),
                if (index == currentAccent) c.accentInk else c.withAlpha(c.outline, 0.9f)
            )
        }

        fun restyleSwatches(c: VgColors) {
            dotViews.forEachIndexed { i, v ->
                v.background = swatchDrawable(dotIndex[i], dotFill[i], dotGrad[i], c)
            }
        }

        val accentLabel = TextView(ctx).apply {
            text = "当前主题色：" + VgTheme.accentName(currentAccent)
            textSize = 11f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(0, 0, 0, (4 * d).toInt())
        }

        fun addSwatch(index: Int, fill: Int, gradient: IntArray?) {
            val size = (34 * d).toInt()
            val holder = android.widget.FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = (10 * d).toInt()
                }
                isClickable = true
                isFocusable = true
                contentDescription = VgTheme.accentName(index)
                setOnClickListener {
                    if (currentAccent == index) return@setOnClickListener
                    currentAccent = index
                    VgTheme.setAccentIndex(this@MainActivity, index)
                    val c = VgTheme.resolve(this@MainActivity)
                    // 背景做渐变过渡；控件色板同步切换
                    gamepadView.applyTheme(c, animated = true)
                    accentLabel.text = "当前主题色：" + VgTheme.accentName(index)
                    restyleSwatches(c)
                    // 收起面板，让整屏背景的渐变过渡完整可见
                    hideSettings()
                }
            }
            val dot = View(ctx).apply {
                background = swatchDrawable(index, fill, gradient, theme)
                layoutParams = android.widget.FrameLayout.LayoutParams(size, size)
            }
            holder.addView(dot)
            dotViews.add(dot)
            dotIndex.add(index)
            dotFill.add(fill)
            dotGrad.add(gradient)
            swatchRow.addView(holder)
        }
        // 只保留五色预设（黄 / 橙 / 绿 / 青 / 紫）；「跟随壁纸」动态取色已移除
        VgTheme.accents.forEachIndexed { i, a ->
            addSwatch(i + 1, if (theme.dark) a.dark else a.light, null)
        }
        container.addView(swatchRow)
        container.addView(accentLabel)

        // ---- 自定义布局槽位：3 套独立布局，点选即切换，长按管理（覆盖 / 重命名 / 清空） ----
        val layoutSlotRow = buildLayoutSlotRow()
        container.addView(layoutSlotRow)

        // 布局编辑入口放在最上方，展开即可见，避免被滑杆挤出屏幕
        container.addView(
            GlassButton(ctx).apply {
                text = "自定义按键布局（逐键拖动 / 调大小）"
                textSize = 13f
                setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (46 * d).toInt()
                )
                setOnClickListener {
                    hideSettings()
                    enterEditMode()
                }
            }
        )

        // ---- 隐藏任意按键：默认收起，Material 风格柔和展开 / 收起 ----
        // 选项本身、两列布局与全部功能保持不变，只把外层的显隐换成高度动画
        val hideWrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            )
        }
        val hideContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.INVISIBLE
            alpha = 0f
            translationY = -6f * d      // 展开时从这里轻微下移归位，形成层次感
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        hideWrapper.addView(hideContent)

        // 标题行：左侧自绘三角 + 文字；展开时浮起一层极淡的 stateLayer 作层次变化
        val hideTitleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding((6 * d).toInt(), (12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt())
            minimumHeight = (44 * d).toInt()
            background = GradientDrawable().apply {
                cornerRadius = 12f * d
                setColor(Color.WHITE)
                alpha = 0                       // 展开时淡入到约 8% 的 stateLayer
            }
            // Material 标准按压水波纹（foreground），与淡色 stateLayer 分层叠加
            val rippleAttr = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleAttr, true)
            if (rippleAttr.resourceId != 0) {
                foreground = ContextCompat.getDrawable(ctx, rippleAttr.resourceId)
            }
        }
        val triangle = ExpandTriangleView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt())
            rotation = 0f
        }
        hideTitleRow.addView(triangle)
        val hideTitle = TextView(ctx).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding((10 * d).toInt(), 0, 0, 0)
        }
        hideTitleRow.addView(
            hideTitle,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val tintCollapsed = ContextCompat.getColor(ctx, R.color.text_secondary)
        val tintExpanded = ContextCompat.getColor(ctx, R.color.text_primary)
        // Material standard easing：起步快、收尾缓，避免线性/生硬的跳变
        val standardEase = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        var hideExpanded = false
        var hideAnim: ValueAnimator? = null

        fun blendColor(from: Int, to: Int, t: Float): Int {
            val f = t.coerceIn(0f, 1f)
            return Color.argb(
                (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f).toInt(),
                (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt(),
                (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt(),
                (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
            )
        }

        fun hiddenCount(): Int = gamepadView.controlKeys().count { gamepadView.isHidden(it) }
        fun renderHideTitle() {
            val n = hiddenCount()
            hideTitle.text = "隐藏按键" +
                if (n > 0) "（已隐藏 $n 个）" else "（勾选后不显示、也不再响应）"
            hideTitleRow.contentDescription =
                if (hideExpanded) "隐藏按键，已展开，点击收起" else "隐藏按键，已收起，点击展开"
        }
        renderHideTitle()

        /** 一条时间轴同时驱动高度、内容淡入与轻微下移、三角旋转与变色、标题 stateLayer。 */
        fun animateHide(expand: Boolean) {
            hideAnim?.cancel()   // 连续点击时从当前高度接着走，不回弹、不跳变

            val w = hideContent.width.takeIf { it > 0 } ?: container.width
            val wSpec = View.MeasureSpec.makeMeasureSpec(
                if (w > 0) w else resources.displayMetrics.widthPixels,
                View.MeasureSpec.EXACTLY
            )
            hideContent.measure(
                wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val full = hideContent.measuredHeight
            if (expand) {
                // 固定内容高度：动画期间只裁剪外层，内部两列布局不会逐帧重排
                hideContent.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, full
                )
                hideContent.visibility = View.VISIBLE
            }

            val startH = hideWrapper.layoutParams.height.coerceAtLeast(0)
            val endH = if (expand) full else 0
            val startRot = triangle.rotation
            val endRot = if (expand) 90f else 0f
            val startAlpha = hideContent.alpha
            val endAlpha = if (expand) 1f else 0f
            val startY = hideContent.translationY
            val endY = if (expand) 0f else -6f * d
            val startLayer = (hideTitleRow.background?.alpha ?: 0).toFloat()
            val endLayer = if (expand) 22f else 0f

            hideAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (expand) 320L else 260L
                interpolator = standardEase
                addUpdateListener {
                    val f = it.animatedFraction
                    val lp = hideWrapper.layoutParams
                    lp.height = (startH + (endH - startH) * f).toInt()
                    hideWrapper.layoutParams = lp

                    hideContent.alpha = startAlpha + (endAlpha - startAlpha) * f
                    hideContent.translationY = startY + (endY - startY) * f
                    triangle.rotation = startRot + (endRot - startRot) * f
                    // 用「当前展开进度」插值颜色，中途反向点击也能平滑衔接
                    val progress = if (full > 0) (lp.height.toFloat() / full).coerceIn(0f, 1f) else 0f
                    triangle.setTint(blendColor(tintCollapsed, tintExpanded, progress))
                    hideTitleRow.background?.setAlpha(
                        (startLayer + (endLayer - startLayer) * f).toInt()
                    )

                    if (f >= 0.999f && !expand) hideContent.visibility = View.INVISIBLE
                }
                start()
            }
        }

        hideTitleRow.setOnClickListener {
            hideExpanded = !hideExpanded
            renderHideTitle()
            animateHide(hideExpanded)
        }
        container.addView(hideTitleRow)

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
            GlassButton(ctx).apply {
                text = "全部显示"
                textSize = 13f
                setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (44 * d).toInt()
                )
                setOnClickListener {
                    hideBoxes.forEach { it.isChecked = false }
                    prefs.edit().putString("hidden_keys", gamepadView.hiddenJson()).apply()
                    renderHideTitle()
                }
            }
        )
        container.addView(hideWrapper)

        // 右摇杆控制（陀螺仪相关）放在最前，展开即可见
        container.addView(label("摇杆样式"))
        container.addView(
            toggle("金属立体摇杆（Uiverse 风格）", gamepadView.stickStyle == 1) { on ->
                gamepadView.stickStyle = if (on) 1 else 0
                prefs.edit().putInt("stick_style", gamepadView.stickStyle).apply()
            }
        )

        container.addView(label("背景动态效果"))
        val bgSwitches = ArrayList<MaterialSwitch>(2)
        var syncingBgSwitch = false
        fun applyBgMode(mode: Int) {
            gamepadView.bgEffect = mode
            prefs.edit().putInt("bg_effect", mode).apply()
            syncingBgSwitch = true
            bgSwitches.forEachIndexed { i, sw -> sw.isChecked = i + 1 == mode }
            syncingBgSwitch = false
        }
        listOf("星星漂浮（视差漂移）", "午夜星空（闪烁 + 流星）").forEachIndexed { i, text ->
            val sw = toggle(text, gamepadView.bgEffect == i + 1) { checked ->
                if (syncingBgSwitch) return@toggle
                applyBgMode(if (checked) i + 1 else 0)
            }
            bgSwitches.add(sw)
            container.addView(sw)
        }

        container.addView(
            toggle("按键星星飞散动效", gamepadView.starBurstEnabled) { on ->
                gamepadView.starBurstEnabled = on
                prefs.edit().putBoolean("star_burst", on).apply()
            }
        )

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

        container.addView(label("扳机（LT / RT）触发方式"))
        val trigGroup = android.widget.RadioGroup(ctx).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val trigOptions = arrayOf(
            "按下后不触发滑动（点按即满行程，滑动不改变扳机）",
            "按下后允许滑动（纵向拖动按位置连续调节）"
        )
        trigOptions.forEachIndexed { idx, text ->
            val rb = com.google.android.material.radiobutton.MaterialRadioButton(ctx).apply {
                // 与上方 clickGroup（1000+idx）错开，避免 RadioGroup 互斥串组
                id = 1100 + idx
                this.text = text
                textSize = 14f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                isChecked = triggerSlide == idx
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        triggerSlide = idx
                        gamepadView.triggerSlide = idx
                        prefs.edit().putInt("trigger_slide", idx).apply()
                    }
                }
            }
            trigGroup.addView(rb)
        }
        container.addView(trigGroup)

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
            GlassButton(ctx).apply {
                text = "检查更新（当前 v${
                    try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
                }）"
                textSize = 13f
                setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (44 * d).toInt()
                ).also { it.topMargin = (18 * d).toInt() }
                setOnClickListener { updateManager.manualCheck() }
            }
        )

        container.addView(
            GlassButton(ctx).apply {
                text = "恢复默认"
                textSize = 13f
                setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (44 * d).toInt()
                ).also { it.topMargin = (18 * d).toInt() }
                setOnClickListener { confirmResetAll() }
            }
        )

        // ---- 分组归卡片（Material 3 设置页模式：分组标题 + 圆角卡片 + 组内分隔线）----
        // 平铺的 18 个小标题按功能归入 5 张卡片，控件本身与交互逻辑保持不变。
        // 旧分组小标题（外观/主题色/右摇杆控制/震动等）退役，由卡片标题替代。
        run {
            val SEC_LOOK = 0; val SEC_LAYOUT = 1; val SEC_STICK = 2
            val SEC_HAPTIC = 3; val SEC_RESET = 4

            // 标签 → 所属卡片；drop=true 的旧分组标题不再上屏
            data class Sec(val id: Int, val drop: Boolean)
            val labelSec = mapOf(
                "外观" to Sec(SEC_LOOK, true),
                "主题色" to Sec(SEC_LOOK, true),
                "摇杆样式" to Sec(SEC_LOOK, true),
                "背景动态效果" to Sec(SEC_LOOK, true),
                "右摇杆控制" to Sec(SEC_STICK, true),
                "陀螺仪视角灵敏度（65 = 标准）" to Sec(SEC_STICK, false),
                "右摇杆视角灵敏度（50 = 标准）" to Sec(SEC_STICK, false),
                "摇杆按下（LS / RS）触发方式" to Sec(SEC_STICK, false),
                "扳机（LT / RT）触发方式" to Sec(SEC_STICK, false),
                "控件整体大小（50 = 默认）" to Sec(SEC_LAYOUT, false),
                "摇杆大小（50 = 默认）" to Sec(SEC_LAYOUT, false),
                "按键大小（ABXY / 肩键 / 方向键 / 扳机，50 = 默认）" to Sec(SEC_LAYOUT, false),
                "震动" to Sec(SEC_HAPTIC, true),
                "触感强度" to Sec(SEC_HAPTIC, false),
                "触感时长" to Sec(SEC_HAPTIC, false),
                "触感最小间隔" to Sec(SEC_HAPTIC, false),
                "手机自身振动加强（叠加在游戏回传上）· 强度" to Sec(SEC_HAPTIC, false),
                "手机自身振动加强 · 时长" to Sec(SEC_HAPTIC, false),
                "手机自身振动加强 · 最小间隔" to Sec(SEC_HAPTIC, false),
            )

            val kids = (0 until container.childCount).map { container.getChildAt(it) }
            val buckets = Array(5) { ArrayList<View>() }
            var cur = SEC_LOOK
            for (v in kids) {
                when {
                    // 旧分组标题：只切换当前分区，不再上屏
                    v is TextView && v.text?.toString() in labelSec -> {
                        cur = labelSec[v.text.toString()]!!.id
                        if (!labelSec[v.text.toString()]!!.drop) buckets[cur].add(v)
                    }
                    v is GlassButton && v.text?.toString()?.startsWith("自定义按键布局") == true ->
                        buckets[SEC_LAYOUT].add(v)
                    v is LinearLayout && v.tag == "layout_slots" ->
                        buckets[SEC_LAYOUT].add(v)
                    v is GlassButton -> buckets[SEC_RESET].add(v)
                    v === hideTitleRow || v === hideWrapper -> buckets[SEC_LAYOUT].add(v)
                    else -> buckets[cur].add(v)
                }
            }

            fun sectionCard(items: List<View>): com.google.android.material.card.MaterialCardView {
                val card = com.google.android.material.card.MaterialCardView(ctx).apply {
                    radius = 16f * d
                    cardElevation = 0f
                    strokeWidth = (1 * d).toInt().coerceAtLeast(1)
                    strokeColor = if (theme.dark) 0x14FFFFFF.toInt() else 0x0F000000
                    setCardBackgroundColor(if (theme.dark) 0x0FFFFFFF.toInt() else 0x66FFFFFF)
                    isClickable = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (10 * d).toInt() }
                }
                val inner = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((4 * d).toInt(), (2 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
                }
                items.forEachIndexed { i, v ->
                    if (i > 0) {
                        inner.addView(View(ctx).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, 1
                            ).apply {
                                marginStart = (12 * d).toInt(); marginEnd = (12 * d).toInt()
                                topMargin = (4 * d).toInt(); bottomMargin = (4 * d).toInt()
                            }
                            background = GradientDrawable().apply {
                                setColor(if (theme.dark) 0x1AFFFFFF.toInt() else 0x12000000)
                            }
                        })
                    }
                    (v.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                        lp.marginStart = (8 * d).toInt(); lp.marginEnd = (8 * d).toInt()
                        v.layoutParams = lp
                    }
                    inner.addView(v)
                }
                card.addView(inner)
                return card
            }

            fun sectionHeader(title: String): TextView = TextView(ctx).apply {
                text = title
                textSize = 13f
                letterSpacing = 0.04f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setPadding((6 * d).toInt(), (16 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            }

            val sections = listOf(
                "布局与大小" to buckets[SEC_LAYOUT],
                "外观" to buckets[SEC_LOOK],
                "摇杆与视角" to buckets[SEC_STICK],
                "震动与触感" to buckets[SEC_HAPTIC],
            )
            container.removeAllViews()
            for ((title, items) in sections) {
                if (items.isEmpty()) continue
                container.addView(sectionHeader(title))
                container.addView(sectionCard(items))
            }
            if (buckets[SEC_RESET].isNotEmpty()) {
                container.addView(sectionCard(buckets[SEC_RESET]))
            }
        }

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

    // ---------- 自定义布局槽位（3 套独立布局） ----------

    /** 三个槽位的芯片文本引用，buildLayoutSlotRow() 每次打开设置时重建 */
    private val layoutSlotLabels = mutableListOf<TextView>()

    /** 长按菜单的三种动作 */
    private val SLOT_ACTION_COVER = 0
    private val SLOT_ACTION_RENAME = 1
    private val SLOT_ACTION_CLEAR = 2

    private fun slotName(i: Int): String =
        prefs.getString("layout_slot_name_$i", null) ?: "布局 $i"

    private fun activeSlot(): Int = prefs.getInt("layout_active_slot", 0)

    /** 当前手柄布局（位置/大小/透明度 + 层叠顺序 + 隐藏项）打包成一份槽位 JSON */
    private fun snapshotCurrentLayout(): String =
        org.json.JSONObject()
            .put("layout", gamepadView.layoutJson())
            .put("zorder", gamepadView.zorderJson())
            .put("hidden", gamepadView.hiddenJson())
            .toString()

    /** 布局落盘的唯一出口：基础键 + 当前激活槽位快照一起写，永不脱节 */
    private fun persistLayoutAll() {
        prefs.edit()
            .putString("layout_v1", gamepadView.layoutJson())
            .putString("zorder_v1", gamepadView.zorderJson())
            .putString("hidden_keys", gamepadView.hiddenJson())
            .apply()
        val act = activeSlot()
        if (act in 1..3) {
            prefs.edit().putString("layout_slot_$act", snapshotCurrentLayout()).apply()
        }
    }

    private fun applySlotData(data: String) {
        try {
            val o = org.json.JSONObject(data)
            gamepadView.loadZorder(o.optString("zorder", ""))
            gamepadView.loadLayout(o.optString("layout", ""))
            gamepadView.loadHidden(o.optString("hidden", ""))
        } catch (e: Exception) {
            // 坏数据按「布局不变」处理，避免加载半套配置
        }
    }

    private fun refreshLayoutSlotRow() {
        val act = activeSlot()
        for (i in 1..3) {
            val tv = layoutSlotLabels.getOrNull(i - 1) ?: continue
            val has = prefs.getString("layout_slot_$i", null) != null
            tv.text = when {
                act == i -> "${slotName(i)} · 使用中"
                has -> slotName(i)
                else -> "${slotName(i)}（空）"
            }
        }
    }

    private fun buildLayoutSlotRow(): LinearLayout {
        val ctx = this
        val d = resources.displayMetrics.density
        layoutSlotLabels.clear()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = "layout_slots"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * d).toInt() }
        }
        for (i in 1..3) {
            val tv = TextView(ctx).apply {
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding((6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt())
            }
            val chip = com.google.android.material.card.MaterialCardView(ctx).apply {
                radius = 14f * d
                cardElevation = 0f
                strokeWidth = (1 * d).toInt().coerceAtLeast(1)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    marginStart = (4 * d).toInt()
                    marginEnd = (4 * d).toInt()
                }
                addView(tv)
                setOnClickListener {
                    val act = activeSlot()
                    if (act == i) return@setOnClickListener
                    // 离开的槽位自动保存当前布局，编辑不丢
                    if (act in 1..3) {
                        prefs.edit().putString("layout_slot_$act", snapshotCurrentLayout()).apply()
                    }
                    val data = prefs.getString("layout_slot_$i", null)
                    if (data == null) {
                        // 空槽：把当前布局存进去并启用
                        prefs.edit()
                            .putString("layout_slot_$i", snapshotCurrentLayout())
                            .putInt("layout_active_slot", i)
                            .apply()
                        persistLayoutAll()
                        android.widget.Toast.makeText(
                            ctx, "已把当前布局保存到「${slotName(i)}」",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        prefs.edit().putInt("layout_active_slot", i).apply()
                        applySlotData(data)
                        persistLayoutAll()
                        android.widget.Toast.makeText(
                            ctx, "已切换到「${slotName(i)}」",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    refreshLayoutSlotRow()
                }
                setOnLongClickListener {
                    showSlotMenu(i)
                    true
                }
            }
            layoutSlotLabels.add(tv)
            row.addView(chip)
        }
        refreshLayoutSlotRow()
        return row
    }

    /**
     * 布局槽位长按菜单（Material 3 视觉）：
     * MaterialAlertDialogBuilder + 自绘等高列表项（24dp 图标 + 16sp 标签 + 12dp 圆角涟漪），
     * 标题走 M3 titleLarge（22sp）与 onSurface 色，替代原先的 AppCompat 纯文本列表。
     */
    private fun showSlotMenu(i: Int) {
        val ctx = this
        val onSurface = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY
        )
        val onSurfaceVariant = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY
        )
        val rippleColor = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorControlHighlight, 0x1F000000
        )

        val entries = listOf(
            Triple(R.drawable.ic_slot_cover, "用当前布局覆盖此槽", SLOT_ACTION_COVER),
            Triple(R.drawable.ic_slot_rename, "重命名", SLOT_ACTION_RENAME),
            Triple(R.drawable.ic_slot_clear, "清空此槽", SLOT_ACTION_CLEAR)
        )

        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        var dialog: androidx.appcompat.app.AlertDialog? = null
        for ((icon, label, action) in entries) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isClickable = true
                isFocusable = true
                // M3 列表项涟漪：12dp 圆角蒙版，边界内收不压边
                val mask = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.WHITE)
                }
                background = android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(rippleColor), null, mask
                )
            }
            row.addView(ImageView(ctx).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(onSurfaceVariant)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            })
            row.addView(TextView(ctx).apply {
                text = label
                textSize = 16f
                setTextColor(onSurface)
                setPadding(dp(16), 0, 0, 0)
            })
            row.setOnClickListener {
                dialog?.dismiss()
                performSlotAction(i, action)
            }
            list.addView(row)
        }

        val title = TextView(ctx).apply {
            text = slotName(i)
            textSize = 22f
            setTextColor(onSurface)
            setPadding(dp(24), dp(24), dp(24), dp(10))
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(list)
        }

        dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setView(content)
            .create()
        dialog.show()
    }

    private fun performSlotAction(i: Int, action: Int) {
        when (action) {
            SLOT_ACTION_COVER -> {
                prefs.edit()
                    .putString("layout_slot_$i", snapshotCurrentLayout())
                    .putInt("layout_active_slot", i)
                    .apply()
                persistLayoutAll()
                Toast.makeText(this, "已覆盖「${slotName(i)}」", Toast.LENGTH_SHORT).show()
            }
            SLOT_ACTION_RENAME -> renameLayoutSlot(i)
            SLOT_ACTION_CLEAR -> {
                prefs.edit().putString("layout_slot_$i", null).apply()
                if (activeSlot() == i) {
                    prefs.edit().putInt("layout_active_slot", 0).apply()
                }
                Toast.makeText(this, "已清空「${slotName(i)}」", Toast.LENGTH_SHORT).show()
            }
        }
        refreshLayoutSlotRow()
    }

    private fun renameLayoutSlot(i: Int) {
        val input = EditText(this).apply {
            setText(slotName(i))
            setSelection(text.length)
            setSingleLine()
            setBackgroundResource(R.drawable.bg_field)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("重命名布局")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    prefs.edit().putString("layout_slot_name_$i", name).apply()
                    refreshLayoutSlotRow()
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.scrim))
            isClickable = true            // 吞掉面板外的点击
            setOnClickListener { hideSettings() }
        }
        val card = android.widget.FrameLayout(ctx).apply {
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_panel)
            clipToOutline = false
            isClickable = true
            setOnClickListener { }        // 面板内点击不穿透
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (dm.heightPixels * 0.86f).toInt()
            ).apply {
                gravity = android.view.Gravity.BOTTOM
                leftMargin = (68 * dm.density).toInt()
                rightMargin = (68 * dm.density).toInt()
                bottomMargin = (10 * dm.density).toInt()
            }
        }
        card.clipToOutline = true     // 按 bg_panel 的 26dp 圆角裁剪，渐隐带贴合圆角
        card.addView(content)
        // ---- 面板上下边缘渐隐（苹果弹层边缘效果）：面板底色 100% -> 0 的垂直渐变，
        //      内容滚动到边缘自然淡出。确定性渲染：无快照取样、无每帧重绘，
        //      任何滚动位置、任何机型下视觉一致，且零卡顿。 ----
        run {
            val d = dm.density
            val stripH = (56 * d).toInt()
            val pb = ContextCompat.getColor(ctx, R.color.panel_bg)
            val pbClear = androidx.core.graphics.ColorUtils.setAlphaComponent(pb, 0)
            fun edgeStrip(edgeTop: Boolean): android.view.View {
                val v = android.view.View(ctx)
                v.background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    if (edgeTop) intArrayOf(pb, pbClear) else intArrayOf(pbClear, pb)
                )
                return v
            }
            card.addView(
                edgeStrip(edgeTop = true),
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, stripH
                ).apply { gravity = android.view.Gravity.TOP }
            )
            card.addView(
                edgeStrip(edgeTop = false),
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, stripH
                ).apply { gravity = android.view.Gravity.BOTTOM }
            )
        }
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

    // ---------- 编辑模式：单键大小 ----------

    /**
     * 单键大小滑杆：progress 0–450 ↔ 缩放 0.5×–5.0×（50% = 默认大小，450 = 500%）。
     * 写入与回显共用这两个函数，避免公式各写一遍导致互相矛盾。
     */
    private fun editProgressToScale(progress: Int): Float =
        0.5f + progress.coerceIn(0, EDIT_SIZE_SEEK_MAX) / 100f

    private fun editScaleToProgress(scale: Float): Int =
        ((scale - 0.5f) * 100f).roundToInt().coerceIn(0, EDIT_SIZE_SEEK_MAX)

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
                val pct = (gamepadView.controlScale(key) * 100).roundToInt()
                editLabel.text = "已选：${gamepadView.controlName(key)} · 大小 $pct%"
                editSeek.max = EDIT_SIZE_SEEK_MAX
                editSeek.progress = editScaleToProgress(gamepadView.controlScale(key))
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

    /** 右摇杆灵敏度滑杆值（0–100）→ 增益：0→0.4×，50→1.3×（默认，鼠标感），100→2.2×。
     *  增益提前饱和是设计意图：推约 74% 行程即达满速，等效缩短"行程"，
     *  同时让轻推输出快速越过游戏端 XInput 死区（≈26%），消除迟滞感。 */
    private fun stickGain(p: Float): Float = 0.4f + p.coerceIn(0f, 100f) * 0.018f

    /** 把 0–100 的三个触感滑杆值换算为 GamepadView 的实际参数。 */
    private fun applyHapticParams() {
        gamepadView.hapticStrength = hapticStrengthP / 100f
        gamepadView.hapticDurationMs = (10f + hapticDurP * 0.7f).toInt().coerceIn(5, 80)
        gamepadView.hapticIntervalMs = (hapticGapP * 2f).toInt().coerceIn(0, 200)
    }

    /** 「手柄」框内状态点：已连接=绿色，未连接=暗灰。 */
    /**
     * 链路状态着色：标题胶囊（色块）+ 状态点 + 「手柄」文字 + 状态文字统一取色。
     * 已连接=绿、连接中/重连中=琥珀、已断开/错误=红，三种状态在任何主题下都能一眼区分。
     */
    private fun applyLinkState(state: Int) {
        val c = ContextCompat.getColor(
            this,
            when (state) {
                LINK_OK -> R.color.ok
                LINK_WARN -> R.color.warn
                LINK_ERR -> R.color.err
                else -> R.color.text_secondary
            }
        )
        val chipAlpha = if (state == LINK_IDLE) 0x24 else 0x3D
        statusDot.backgroundTintList = ColorStateList.valueOf(c)
        titleBox.backgroundTintList =
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(c, chipAlpha))
        barTitle.setTextColor(c)
        statusText.setTextColor(c)

        // 连接开关：左=关（已断开）、右=开（连接中 / 已连接）。
        // 轨道左段取状态色（琥珀 / 绿），右段固定红；滑块滑到哪侧就露出另一侧那段颜色。
        val on = state == LINK_OK || state == LINK_WARN
        if (connectSwitch.checked != on) connectSwitch.checked = on
        connectSwitch.onColor = c
        connectSwitch.offColor = ContextCompat.getColor(
            this, if (state == LINK_IDLE) R.color.outline else R.color.err
        )
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
        // VibrationEffect 是 API 26 引入的；minSdk 24 下必须分支，否则 Android 7.x 崩溃
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(dur.toLong(), a))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(dur.toLong())
        }
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
        if (!sender.isRunning) {
            when {
                // 用户主动点了「断开」→ 红色「已断开」，与连接中的绿色形成明显反差
                userStopped -> {
                    statusText.text = "已断开"
                    applyLinkState(LINK_ERR)
                }
                // 从未点击连接（冷启动 / 用户撤销后）：无会话，不提示中断、不提示重连
                !sessionActive -> {
                    statusText.text = getString(R.string.status_idle)
                    applyLinkState(LINK_IDLE)
                }
                // 会话存在但处于后台：不显示重连横幅，回前台后由 onResume 续连
                !resumed -> {
                    statusText.text = getString(R.string.status_idle)
                    applyLinkState(LINK_IDLE)
                }
                else -> {
                    statusText.text = "连接中断，正在自动重连…"
                    applyLinkState(LINK_WARN)
                }
            }
            return
        }
        val e = linkError
        if (e != null) {
            statusText.text = e
            applyLinkState(LINK_ERR)
            return
        }
        // PC 主动结束会话（VGPQ），或超过显示兜底超时没有回包：
        // 立即切「未连接」并清空 pps / 玩家号，不再沿用旧状态与数值。
        // checkLink() 仍按 LINK_LOST_MS 触发自动重连，既有行为不变。
        if (pcStopped || (everGotReply && SystemClock.uptimeMillis() - lastReplyAt > LINK_SHOW_MS)) {
            lastPps = 0
            playerNo = 0
            statusText.text = "未连接 · 0 pps"
            applyLinkState(LINK_WARN)
            return
        }
        // UDP 本身无连接：收到过 PC 回包才算真正「已连接」
        val linked = everGotReply
        statusText.text = (if (linked) "已连接" else "正在连接") + " · $lastPps pps" +
            (if (playerNo > 0) " · ${playerNo}P" else "")
        applyLinkState(if (linked) LINK_OK else LINK_WARN)
    }

    private fun resetConnectUi() {
        ipInput.isEnabled = true
        portInput.isEnabled = true
    }

    /** 收到任何 PC 回包 = 链路确实活着（VGPP 每秒下发，VGPR 震动回传） */
    private fun markReply() {
        lastReplyAt = SystemClock.uptimeMillis()
        everGotReply = true
        pcStopped = false       // PC 重新 Start 后立即恢复「已连接」，与 Start 同步
        linkError = null
        autoRetryCount = 0
        renderStatus()
    }

    private fun startSender(host: String, port: Int) {
        if (sender.start(host, port, listener)) {
            ipInput.isEnabled = false
            portInput.isEnabled = false
        }
        renderStatus()
    }

    private fun scheduleReconnect(delayMs: Long = -1L) {
        if (!sessionActive || userStopped || !resumed) return
        // 已有待执行的重连（隐式延时）就不再重置，否则看门狗会把任务无限推迟
        if (reconnectScheduled && delayMs < 0L) return
        uiHandler.removeCallbacks(reconnectTask)
        val backoff = 2000L shl (autoRetryCount.coerceAtMost(3))
        val d = if (delayMs >= 0) delayMs else backoff.coerceAtMost(15000L)
        reconnectScheduled = true
        uiHandler.postDelayed(reconnectTask, d)
        renderStatus()
    }

    private fun attemptReconnect() {
        if (!sessionActive || userStopped || !resumed || sender.isRunning) return
        val host = ipInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull() ?: -1
        if (host.isEmpty() || port !in 1..65535) return
        autoRetryCount++
        linkError = null
        // 新链路先当作「尚未收到回包」，避免刚重连就被 watchdog 判死而无限重启
        everGotReply = false
        lastReplyAt = 0L
        pcStopped = false
        lastPps = 0
        startSender(host, port)
    }

    private fun checkLink() {
        if (!sessionActive || userStopped || !resumed) return
        if (!sender.isRunning) {
            scheduleReconnect()
            return
        }
        // 兜底：发送线程理应 250Hz 发包。若 3 秒没有任何成功发送，
        // 说明线程已死或 socket 失效，但 running 仍为 true——
        // 此时 attemptReconnect() 会因 isRunning 永久早退，链路无法自愈。
        val lastSend = sender.lastSendAt
        if (lastSend > 0L && SystemClock.elapsedRealtime() - lastSend > 3000L) {
            sender.stop()
            scheduleReconnect(500L)
            renderStatus()
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
                    if (sessionActive && !userStopped && resumed && !sender.isRunning) scheduleReconnect(500L)
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
            renderStatus()          // 参数不合法要把开关拨回「关」，不能停在开位
            return
        }
        if (port !in 1..65535) {
            toast(getString(R.string.err_port))
            renderStatus()
            return
        }

        prefs.edit().putString("ip", host).putInt("port", port).apply()
        userStopped = false
        // 用户主动发起 → 会话成立，此后才允许断线提示与自动重连
        sessionActive = true
        autoRetryCount = 0
        everGotReply = false
        lastReplyAt = 0L
        linkError = null
        uiHandler.removeCallbacks(reconnectTask)
        // 取消待执行的重连必须同时清标志，否则 reconnectScheduled 永远为 true，
        // 之后所有 scheduleReconnect(delay<0) 会被防抖判据直接丢弃 —— 链路再也无法自愈
        reconnectScheduled = false
        // 会话开始：Wi-Fi 进入低延迟模式（自动重连间隔同样受益）
        try { wifiLock?.acquire() } catch (e: Exception) { }
        startSender(host, port)
        applyGyroState()
    }

    private fun disconnect() {
        userStopped = true
        // 用户主动撤销 → 会话结束，状态回到「未连接」，不再自动重连
        sessionActive = false
        uiHandler.removeCallbacks(reconnectTask)
        reconnectScheduled = false
        try { wifiLock?.release() } catch (e: Exception) { }
        sender.stop()
        sender.sendGoodbye()   // 告别包：PC 收到立即释放槽位，不等超时
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
     * 收起宽度 = 「手柄」胶囊真实宽度 + 栏左右各 12dp 内边距。
     * 优先用布局后的 titleBox.width（零误差），胶囊在玻璃栏内严格左右对称（12dp/12dp）；
     * 首帧尚未布局时退回 Paint 估算。栏内不再留任何多余空间——之前多出的余量
     * 全部堆在胶囊右侧，看起来就是"胶囊偏左、右边一大块空白"。
     */
    private fun measureCollapsedWidth(): Int {
        if (titleBox.width > 0) return titleBox.width + dp(24)
        val text = barTitle.text?.toString() ?: ""
        val spacing = barTitle.letterSpacing * barTitle.textSize * text.length
        val textW = barTitle.paint.measureText(text) + spacing
        val titleBoxW = titleBox.paddingStart + titleBox.paddingEnd + textW + dp(DOT_DP) + dp(5)
        return dp(12) + titleBoxW.toInt() + dp(12)
    }

    /**
     * 只动画外层裁剪壳 barClip 的宽度：内层 connBar 恒为全宽、子控件约束不变，
     * 动画期间没有任何子 View 需要重新测量布局（这是之前收起卡顿的根源），
     * 超出部分由外壳 clipChildren 直接裁掉。
     */
    private fun applyBarWidth(w: Int) {
        barCurrentWidth = w
        val lp = barClip.layoutParams
        lp.width = w
        barClip.layoutParams = lp
    }

    /** 除「手柄」标题外的所有控件显隐：INVISIBLE 保留布局尺寸、但不绘制也不接收触摸。 */
    private fun setBarContentVisible(visible: Boolean) {
        for (i in 1 until connBar.childCount) {
            val c = connBar.getChildAt(i)
            if (visible) {
                c.visibility = View.VISIBLE
            } else {
                // 彻底隐藏并复位变换属性，下次入场动画从干净状态开始
                c.animate().cancel()
                c.alpha = 1f
                c.translationX = 0f
                c.visibility = View.INVISIBLE
            }
        }
    }

    private fun toggleBar() {
        if (barExpanded) collapseBar() else expandBar()
    }

    /**
     * 从「手柄」文字处向右展开：动画驱动连接栏宽度（子项尺寸固定、超出部分裁剪），
     * 视觉上即"从左向右依次显现"，且不做位移动画、无位移动画的重叠问题。
     */
    // ---------- visionOS 玻璃底：展开连接栏时模糊手柄画面 ----------

    /**
     * 抓一帧手柄画面 → 缩小 → 盒式模糊 → 铺满全屏并淡入。
     * 用静态快照而不是实时模糊：栏展开期间手柄不参与交互（点手柄区域会收起栏），
     * 每帧重算模糊在 125Hz 刷新下会明显掉帧。
     */
    private fun showBlur() {
        val base = GlassBlur.snapshot(gamepadView, 4) ?: return
        val blurred = try {
            GlassBlur.blur(base, 6, 3)
        } catch (e: Throwable) {
            base
        }
        blurOverlay.setImageBitmap(blurred)
        // 轻微压暗：模糊层整体降一档亮度，让上面的玻璃栏浮起来（visionOS 的"背景退后"感）
        blurOverlay.colorFilter = android.graphics.PorterDuffColorFilter(
            Color.argb(0x1F, 0, 0, 0), android.graphics.PorterDuff.Mode.SRC_ATOP
        )
        blurOverlay.visibility = View.VISIBLE
        blurOverlay.alpha = 0f
        blurAnim?.cancel()
        blurAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { blurOverlay.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun hideBlur() {
        if (blurOverlay.visibility != View.VISIBLE) return
        blurAnim?.cancel()
        blurAnim = ValueAnimator.ofFloat(blurOverlay.alpha, 0f).apply {
            duration = 180L
            addUpdateListener { blurOverlay.alpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // 动画可能被新的展开打断：仅在仍处于「已收起」时清掉画面
                    if (!barExpanded) {
                        blurOverlay.visibility = View.GONE
                        blurOverlay.setImageDrawable(null)
                    }
                }
            })
            start()
        }
    }

    /**
     * 临界阻尼弹簧位移曲线：x(t) = 1 - (1 + ωt)·e^(-ωt)。
     * 等价于 Compose spring(dampingRatio = 1f) / iOS UISpringTiming(damping=1)——
     * 开源生态里横展开/收起动画的标准运动模型：初速为零平滑启动、指数减速、
     * 无过冲无回弹，收尾比任何贝塞尔曲线都顺。
     */
    private fun springInterpolator(omega: Float) = android.animation.TimeInterpolator {
        val t = it * omega
        1f - (1f + t) * exp(-t)
    }

    /** 弹簧刚度：数值越小尾越长。展开稍绵长、收起稍干脆，均保证在时长内完全收敛。 */
    private val OMEGA_EXPAND = 11f
    private val OMEGA_COLLAPSE = 13.5f

    private fun expandBar() {
        if (barExpanded) return
        barExpanded = true
        barAnim?.cancel()
        setBarContentVisible(true)
        showBlur()
        val from = if (barCurrentWidth > 0) barCurrentWidth else barCollapsedWidth
        // 临界阻尼弹簧（对齐 Compose AnimatedVisibility NoBouncy spring /
        // iOS UISpringTimingAnimation damping=1）：物理减速、无过冲、收尾极顺
        barAnim = ValueAnimator.ofInt(from, barFullWidth).apply {
            duration = 420L
            interpolator = springInterpolator(OMEGA_EXPAND)
            addUpdateListener { applyBarWidth(it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    applyBarWidth(barFullWidth)
                }
            })
            start()
        }
        animateContentIn()
    }

    /**
     * 内容入场（Material stagger）：容器先动，各控件依次 淡入 + 从左侧滑到位，
     * M3 emphasized ease-out，跟随容器展开——消除"瞬间整块出现"的生硬感。
     */
    private fun animateContentIn() {
        val slide = dp(10).toFloat()
        for (i in 1 until connBar.childCount) {
            val c = connBar.getChildAt(i)
            c.animate().cancel()
            c.alpha = 0f
            c.translationX = -slide
            c.animate()
                .alpha(1f)
                .translationX(0f)
                .setStartDelay(30L + (i - 1) * 22L)
                .setDuration(300L)
                .setInterpolator(springInterpolator(OMEGA_EXPAND))
                .start()
        }
    }

    /** 内容出场：140ms 淡出并顺势向左缩回（跟随收拢方向），与容器收拢并行。 */
    private fun animateContentOut() {
        val slide = dp(8).toFloat()
        for (i in 1 until connBar.childCount) {
            val c = connBar.getChildAt(i)
            c.animate().cancel()
            c.animate()
                .alpha(0f)
                .translationX(-slide)
                .setDuration(150L)
                .setInterpolator(springInterpolator(OMEGA_COLLAPSE))
                .start()
        }
    }

    /** 从右向左收回，终点即「手柄」文字所在的窄条。 */
    private fun collapseBar() {
        if (!barExpanded) return
        barExpanded = false
        barAnim?.cancel()
        hideBlur()
        animateContentOut()
        barCollapsedWidth = measureCollapsedWidth()
        val from = if (barCurrentWidth > 0) barCurrentWidth else barFullWidth
        // 同款临界阻尼弹簧（刚度更高，收回更干脆），340ms
        barAnim = ValueAnimator.ofInt(from, barCollapsedWidth).apply {
            duration = 340L
            interpolator = springInterpolator(OMEGA_COLLAPSE)
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
            prefs.edit().putString("layout_v1", gamepadView.layoutJson())
                    .putString("zorder_v1", gamepadView.zorderJson()).apply()
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
        // 会话仍在（用户之前主动连过、未断开）→ 回前台补一次重连；从未连过则什么都不做
        if (sessionActive && !userStopped && !sender.isRunning) scheduleReconnect(500L)
        // 「未知来源安装」授权回来后，自动继续被暂挂的更新安装
        updateManager.onHostResume()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        uiHandler.removeCallbacks(watchdog)
        uiHandler.removeCallbacks(reconnectTask)
        reconnectScheduled = false   // 同上：取消任务必须清标志，回前台才能重新排程
        // 切后台：输入回中 + 冻结陀螺仪（否则传感器持续驱动视角，放兜里会乱转）
        gamepadView.releaseAll()
        applyGyroState()
    }

    override fun onDestroy() {
        userStopped = true
        uiHandler.removeCallbacksAndMessages(null)
        barAnim?.cancel()   // 释放对 Activity/View 的引用，避免销毁后动画回调继续访问
        barAnim = null
        networkCallback?.let { cb ->
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            try { cm?.unregisterNetworkCallback(cb) } catch (e: Exception) { }
        }
        networkCallback = null
        try { wifiLock?.release() } catch (e: Exception) { }
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
