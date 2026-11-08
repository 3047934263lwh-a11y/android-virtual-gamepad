package com.vgamepad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 链路保活前台服务。
 *
 * 为什么需要它：UDP 发送线程跑在本进程里，而本应用没有任何前台组件时，
 * 退到后台的进程会很快变成 cached 进程 —— Android 11+ 的 CachedAppsFreezer
 * 会把它的所有线程（包括 250Hz 发送线程）整体冻结，再加上 Doze / 厂商后台
 * 清理会掐断网络。发送线程一停，PC 接收端的槽位 TTL（2 秒）到期就会释放，
 * 用户看到的就是「退到后台就断开」。
 *
 * 前台服务把进程固定在 FOREGROUND_SERVICE 重要性上（不会被冻结、不被当作可
 * 回收的 cached 进程），配合 PARTIAL_WAKE_LOCK（熄屏后 CPU 不休眠）与
 * Wi-Fi 低延迟锁（避免省电模式把 4ms 小包攒到 beacon 周期），链路才能在后
 * 台持续发送。
 *
 * 本服务不持有 UdpSender：它只负责「让进程活着、锁住 CPU 与 Wi-Fi、把连接
 * 状态显示在常驻通知里」。连接状态机仍在 MainActivity，服务生命周期严格跟随
 * 用户会话（connect → start，disconnect / 退出 → stop）。
 */
class LinkService : Service() {

    companion object {
        private const val CHANNEL_ID = "vgp_link"
        private const val NOTIF_ID = 26760
        private const val WAKE_TAG = "vgp:link"
        private const val WIFI_TAG = "vgp-link-fgs"

        /** 仅同进程访问：Activity 用它把状态文字推给常驻通知 */
        @Volatile
        private var instance: LinkService? = null

        /** 会话开始：拉起前台服务。调用方必须在前台（connect 由用户点击触发） */
        fun start(ctx: Context) {
            val i = Intent(ctx, LinkService::class.java)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // 部分厂商 ROM 在后台限制下会抛 ForegroundServiceStartNotAllowedException：
                // 退化为普通启动，至少不让连接流程崩掉
                try {
                    ctx.startService(i)
                } catch (e2: Exception) {
                    // 彻底失败也不抛出：链路本身仍在，只是失去后台保活
                }
            }
        }

        /** 会话结束：撤下通知并释放所有锁 */
        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, LinkService::class.java))
            } catch (e: Exception) {
            }
        }

        /** 把状态栏文案同步到常驻通知（服务未运行时是空操作） */
        fun updateStatus(text: String) {
            instance?.let { svc ->
                svc.statusText = text
                try {
                    svc.notifyStatus()
                } catch (e: Exception) {
                }
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var notificationManager: NotificationManager? = null
    private var statusText: String = ""
    private var started = false


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        createChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 5 秒内必须进前台，否则系统直接 ANR/杀进程 —— 必须放在最前面
        goForeground()
        // 会话由 Activity 管理：服务被系统重启时没有发送线程在手，不能假装还在连，
        // 直接结束，等用户下次点击连接时再拉起
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        started = false
        if (instance === this) instance = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        releaseLocks()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = notificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.link_channel_name),
            NotificationManager.IMPORTANCE_LOW   // 无声音、不弹横幅，只在通知栏常驻
        ).apply {
            description = getString(R.string.link_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = statusText.ifBlank { getString(R.string.link_notif_text) }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(getString(R.string.link_notif_title))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)              // 不可被划掉：进程保活的前提
            .setOnlyAlertOnce(true)        // 每秒刷 pps 不重复提醒
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    private fun goForeground() {
        if (started) {
            notifyStatus()
            return
        }
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIF_ID, n)
            }
            started = true
        } catch (e: Exception) {
            // 类型不被 ROM 接受（少数定制系统）：退回无类型前台，保活效果基本一致
            try {
                startForeground(NOTIF_ID, n)
                started = true
            } catch (e2: Exception) {
                started = false
            }
        }
    }

    private fun notifyStatus() {
        if (!started) return
        try {
            notificationManager?.notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
        }
    }

    /** 熄屏后 CPU 不休眠 → 250Hz 发送线程不被调度器挂起 */
    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wm?.createWifiLock(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else
                    @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                WIFI_TAG
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        wakeLock = null
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        wifiLock = null
    }
}
