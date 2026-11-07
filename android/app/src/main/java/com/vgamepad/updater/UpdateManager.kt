package com.vgamepad.updater

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.vgamepad.R
import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内自动更新编排：检查 → 弹窗 → 下载（断点续传）→ 四重校验 → 系统安装器。
 *
 * 线程模型：检查与下载在后台线程；所有 UI 更新经 [Activity.runOnUiThread]。
 * 任何失败只提示用户，绝不影响已安装软件的正常使用。
 *
 * 其他项目复用时：复制 updater/ 包 → 改 [UpdateConfig] → 在 Activity 中
 * 创建本类并调用 [checkOnLaunch] / [manualCheck] / [onHostResume] →
 * 在 Manifest 中加 REQUEST_INSTALL_PACKAGES 权限与 FileProvider。
 */
class UpdateManager(private val activity: Activity) {

    private val prefs = activity.getSharedPreferences("update_prefs", Activity.MODE_PRIVATE)
    private val app_name: String by lazy { activity.getString(R.string.app_name) }

    /** 下载进度对话框（引用持有以便更新百分比 / 取消） */
    private var progressDialog: androidx.appcompat.app.AlertDialog? = null
    private var progressText: TextView? = null
    private var progressBar: ProgressBar? = null
    private val downloadCancelled = AtomicBoolean(false)

    /** 下载中标记：防止重复触发下载 */
    private val downloading = AtomicBoolean(false)

    /** 因「未知来源安装」授权未开启而暂挂的安装包；onHostResume 授权后自动续装 */
    private var pendingInstallApk: File? = null

    // ------------------------------------------------------------------
    // 检查入口
    // ------------------------------------------------------------------

    /** 启动时调用：距上次检查超过间隔且网络可用时，后台静默检查 */
    fun checkOnLaunch() {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (now - last < UpdateConfig.MIN_CHECK_INTERVAL_MS) return
        Thread {
            val r = doCheck()
            if (r is UpdateChecker.Result.Available) {
                activity.runOnUiThread { showUpdateDialog(r.release) }
            }
        }.start()
    }

    /** 设置页「检查更新」手动入口：即时检查并反馈结果 */
    fun manualCheck() {
        Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show()
        Thread {
            when (val r = doCheck()) {
                is UpdateChecker.Result.Available ->
                    activity.runOnUiThread { showUpdateDialog(r.release) }
                is UpdateChecker.Result.UpToDate ->
                    activity.runOnUiThread {
                        Toast.makeText(activity, "当前已是最新版本 v${currentVersionName()}",
                            Toast.LENGTH_SHORT).show()
                    }
                else ->
                    activity.runOnUiThread {
                        Toast.makeText(activity,
                            "暂时无法检查更新（需要能够访问 GitHub），请稍后重试",
                            Toast.LENGTH_LONG).show()
                    }
            }
        }.start()
    }

    /** Activity.onResume 调用：用户从「未知来源安装」授权页返回后续装 */
    fun onHostResume() {
        val apk = pendingInstallApk ?: return
        if (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()) {
            pendingInstallApk = null
            openInstaller(apk)
        }
    }

    // ------------------------------------------------------------------
    // 检查实现
    // ------------------------------------------------------------------

    private fun doCheck(): UpdateChecker.Result {
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        if (!UpdateChecker.isNetworkAvailable(activity)) return UpdateChecker.Result.NoNetwork
        val release = UpdateChecker.fetchLatest() ?: return UpdateChecker.Result.Unavailable
        return if (UpdateChecker.compareVersions(
                release.versionName, currentVersionName()) > 0) {
            UpdateChecker.Result.Available(release)
        } else {
            UpdateChecker.Result.UpToDate
        }
    }

    private fun currentVersionName(): String = try {
        activity.packageManager
            .getPackageInfo(activity.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    private fun currentVersionCode(): Long = try {
        val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (e: Exception) { 0L }

    // ------------------------------------------------------------------
    // 更新弹窗
    // ------------------------------------------------------------------

    private fun showUpdateDialog(release: ReleaseInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        val d = activity.resources.displayMetrics.density

        // Material 3 内容区：版本迁移行（旧 → 新，新版本用主题色强调）+ 大小行
        val primary = com.google.android.material.color.MaterialColors.getColor(
            activity, com.google.android.material.R.attr.colorPrimary, 0xFF6750A4.toInt())
        val secondary = androidx.core.content.ContextCompat.getColor(activity, R.color.text_secondary)
        val normal = androidx.core.content.ContextCompat.getColor(activity, R.color.text_primary)

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (20 * d).toInt(), (24 * d).toInt(), 0)
        }
        body.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = "v${currentVersionName()}"
                textSize = 15f
                setTextColor(secondary)
            })
            addView(TextView(activity).apply {
                text = "  →  "
                textSize = 15f
                setTextColor(secondary)
            })
            addView(TextView(activity).apply {
                text = release.tagName.removePrefix("v")
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(primary)
            })
        })
        body.addView(TextView(activity).apply {
            text = "安装包大小：${"%.1f".format(release.apkSize / 1048576f)} MB"
            textSize = 13f
            setTextColor(secondary)
            setPadding(0, (8 * d).toInt(), 0, 0)
        })
        body.addView(TextView(activity).apply {
            text = "更新将覆盖安装，数据保留；校验通过后自动进入系统安装器。"
            textSize = 12.5f
            setTextColor(secondary)
            setPadding(0, (4 * d).toInt(), 0, 0)
        })

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("发现新版本")
            .setView(body)
            .setCancelable(!release.force)          // 强制更新时不可关闭
            .setPositiveButton("立即更新") { _, _ -> startDownload(release) }
            .setNegativeButton(if (release.force) "" else "暂不更新", null)
            .create()
        dialog.setCanceledOnTouchOutside(!release.force)
        dialog.show()
        if (release.force) dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.visibility = View.GONE
    }

    // ------------------------------------------------------------------
    // 下载 → 校验 → 安装
    // ------------------------------------------------------------------

    private fun updateDir(): File? =
        activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let {
            File(it, "update").apply { mkdirs() }
        }

    private fun startDownload(release: ReleaseInfo) {
        if (!downloading.compareAndSet(false, true)) return
        downloadCancelled.set(false)
        val dir = updateDir()
        if (dir == null) {
            downloading.set(false)
            failDialog(release, "无法创建下载目录（存储不可用）")
            return
        }
        val dest = File(dir, release.apkName)

        // 进度对话框：百分比 + 已下载/总量 + 取消
        val d = activity.resources.displayMetrics.density
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (10 * d).toInt(), (24 * d).toInt(), 0)
        }
        val tv = TextView(activity).apply { textSize = 14f; text = "准备下载…" }
        box.addView(tv)
        box.addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * d).toInt() }
            max = 100
        })
        progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("正在下载更新")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("取消下载") { _, _ -> downloadCancelled.set(true) }
            .create()
        progressDialog?.show()
        progressText = tv
        progressBar = box.getChildAt(1) as ProgressBar

        // 后台下载（GitHub 302 → CDN 由 HttpURLConnection 自动跟随）
        Thread {
            val resumed = if (dest.exists()) dest.length() else 0L
            val result = ApkDownloader.download(
                release.apkUrl, dest,
                onProgress = { done, total ->
                    activity.runOnUiThread {
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            progressBar?.progress = pct
                            progressText?.text =
                                "$pct%（${"%.1f".format(done / 1048576f)} / " +
                                "${"%.1f".format(total / 1048576f)} MB）"
                        } else {
                            progressText?.text = "已下载 ${"%.1f".format(done / 1048576f)} MB"
                        }
                    }
                },
                isCancelled = { downloadCancelled.get() },
            )
            activity.runOnUiThread {
                progressDialog?.dismiss()
                downloading.set(false)
                when (result) {
                    is ApkDownloader.DownloadResult.Success -> {
                        progressText?.text = "下载完成，正在校验…"
                        verifyAndInstall(release, dest)
                    }
                    is ApkDownloader.DownloadResult.Failure ->
                        if (result.reason.startsWith("已取消")) {
                            Toast.makeText(activity,
                                "已取消下载（保留断点，共 ${"%.1f".format((dest.takeIf { it.exists() }?.length() ?: resumed) / 1048576f)} MB）",
                                Toast.LENGTH_SHORT).show()
                        } else {
                            failDialog(release, result.reason)
                        }
                }
            }
        }.start()
    }

    /** 下载失败 / 校验失败：显示具体原因，支持重新下载（保留断点） */
    private fun failDialog(release: ReleaseInfo, reason: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("更新失败")
            .setMessage("$reason\n\n已安装的版本不受影响，可以继续正常使用。")
            .setCancelable(true)
            .setPositiveButton("重新下载") { _, _ -> startDownload(release) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------
    // 校验与安装
    // ------------------------------------------------------------------

    private fun fetchExpectedSha256(release: ReleaseInfo, onDone: (String?) -> Unit) {
        if (release.sha256Url.isNullOrBlank()) { onDone(null); return }
        Thread {
            val hash = try {
                val conn = URL(release.sha256Url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
                conn.readTimeout = UpdateConfig.API_READ_TIMEOUT_MS
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "vgamepad-updater")
                val text = if (conn.responseCode == 200)
                    conn.inputStream.bufferedReader().readText().trim() else null
                conn.disconnect()
                // 文件格式兼容："abc123..." 或 "abc123...  filename"
                text?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()
                    ?.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' } }
            } catch (e: Exception) {
                null
            }
            activity.runOnUiThread { onDone(hash) }
        }.start()
    }

    private fun verifyAndInstall(release: ReleaseInfo, apk: File) {
        fetchExpectedSha256(release) { expected ->
            val verdict = UpdateVerifier.verify(activity, apk, release, expected)
            if (verdict is UpdateVerifier.Verdict.Failed) {
                apk.delete()   // 校验失败：删除可疑文件，绝不安装
                failDialog(release, verdict.reason)
            } else {
                openInstaller(apk)
            }
        }
    }

    /** 调起系统安装界面（覆盖安装，不卸载，用户数据保留） */
    private fun openInstaller(apk: File) {
        if (activity.isFinishing || activity.isDestroyed) return
        // Android 8.0+：需要「允许安装未知应用」授权（本应用自己安装自己）
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            pendingInstallApk = apk
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("需要安装权限")
                .setMessage(
                    "安装更新需要允许本应用「安装未知应用」。\n\n" +
                    "点击「去授权」→ 打开「允许来自此来源的应用」→ 返回后自动继续安装。\n" +
                    "该授权只影响本应用安装自己的更新。")
                .setNegativeButton("暂不更新", null)
                .setPositiveButton("去授权") { _, _ ->
                    try {
                        activity.startActivity(Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")))
                    } catch (e: ActivityNotFoundException) {
                        try {
                            activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                        } catch (e2: Exception) { }
                    }
                }
                .show()
            return
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.updateprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            failDialog(ReleaseInfo("", "", "", "", "", 0, null, false),
                "无法调起系统安装器：${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val KEY_LAST_CHECK = "last_check_ms"
    }
}
