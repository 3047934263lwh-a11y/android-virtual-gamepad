package com.vgamepad.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/* ======================================================================
 * ReleaseInfo：一次 Release 的关键信息
 * ==================================================================== */

/**
 * GitHub Release 的关键信息。
 * [versionName] 已去掉 tag 的 v 前缀，直接与 App 的 versionName 比较。
 */
data class ReleaseInfo(
    val tagName: String,       // 如 v1.1.1
    val versionName: String,   // 如 1.1.1
    val body: String,          // 更新日志（Markdown 文本）
    val apkUrl: String,        // browser_download_url（会 302 到 CDN）
    val apkName: String,
    val apkSize: Long,
    val sha256Url: String?,    // <apk 名>.sha256 附件；缺失 = 无法校验完整性
    val force: Boolean,        // 更新日志含 [force] 标记 → 强制更新
) {
    companion object {
        /** 解析 /releases/latest 响应；结构异常、无 APK 附件时返回 null */
        fun fromJson(json: String): ReleaseInfo? {
            return try {
                val o = JSONObject(json)
                // /releases/latest 本身不会返回 draft / prerelease，这里再挡一道
                if (o.optBoolean("draft", false) || o.optBoolean("prerelease", false)) {
                    null
                } else {
                    val assets = o.getJSONArray("assets")
                    var apk: JSONObject? = null
                    var sha: JSONObject? = null
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        val name = a.optString("name", "")
                        when {
                            name.lowercase().endsWith(".apk") && apk == null -> apk = a
                            name.lowercase().endsWith(".sha256") && sha == null -> sha = a
                        }
                    }
                    val a = apk ?: return null
                    val tag = o.optString("tag_name", "").trim()
                    if (tag.isEmpty()) return null
                    val body = o.optString("body", "")
                    ReleaseInfo(
                        tagName = tag,
                        versionName = tag.removePrefix("v").removePrefix("V"),
                        body = body,
                        apkUrl = a.getString("browser_download_url"),
                        apkName = a.getString("name"),
                        apkSize = a.optLong("size", 0L),
                        sha256Url = sha?.getString("browser_download_url"),
                        force = body.contains(UpdateConfig.FORCE_MARKER, ignoreCase = true),
                    )
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/* ======================================================================
 * UpdateChecker：网络可用性 / 拉取最新 Release / 版本比较
 * ==================================================================== */

object UpdateChecker {

    sealed class Result {
        /** 网络不可用：静默跳过，不影响软件其他功能 */
        object NoNetwork : Result()
        /** 已是最新版本 */
        object UpToDate : Result()
        /** GitHub 不可达 / 超时 / 无正式 Release / 数据异常 */
        object Unavailable : Result()
        /** 检测到新版本 */
        data class Available(val release: ReleaseInfo) : Result()
    }

    /** 网络是否连通（无网时调用方直接跳过检查，不发请求） */
    fun isNetworkAvailable(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val cap = cm?.getNetworkCapabilities(cm.activeNetwork)
        cap?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) {
        false
    }

    /** 请求 GitHub Releases API；任何异常（断网/超时/限流）都返回 null，不抛出 */
    fun fetchLatest(): ReleaseInfo? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(UpdateConfig.API_LATEST).openConnection() as HttpURLConnection).apply {
                connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
                readTimeout = UpdateConfig.API_READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "vgamepad-updater")
            }
            if (conn.responseCode != 200) {
                null
            } else {
                ReleaseInfo.fromJson(conn.inputStream.bufferedReader().readText())
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 语义化版本比较（"1.2.10" vs "1.2.9" 这类按数字逐段比较，不是字符串比较）。
     * 返回：a>b → 1，a==b → 0，a<b → -1。非数字段按 0 处理。
     */
    fun compareVersions(a: String, b: String): Int {
        fun segs(s: String) = s.trim().removePrefix("v").removePrefix("V")
            .split('.').map { it.filter { c -> c.isDigit() }.toLongOrNull() ?: 0L }
        val sa = segs(a); val sb = segs(b)
        val n = maxOf(sa.size, sb.size)
        for (i in 0 until n) {
            val x = sa.getOrElse(i) { 0L }
            val y = sb.getOrElse(i) { 0L }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }
}

/* ======================================================================
 * ApkDownloader：支持断点续传的 APK 下载
 * ==================================================================== */

object ApkDownloader {

    sealed class DownloadResult {
        object Success : DownloadResult()
        data class Failure(val reason: String) : DownloadResult()
    }

    /**
     * 下载 [url] 到 [dest]。
     * - 已存在部分文件时以 Range 请求续传（GitHub CDN 支持 206）；
     * - 服务端返回 200（不支持断点）时从头重下；
     * - IOException 时保留已下载字节，交给上层重试；
     * - [isCancelled] 返回 true 时中止（保留断点），返回 Failure("已取消")；
     * - 通过 [onProgress] 汇报 (已下载字节, 总字节)，总字节未知时 total=-1。
     */
    fun download(
        url: String,
        dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): DownloadResult {
        dest.parentFile?.mkdirs()
        var lastError = "未知错误"
        repeat(UpdateConfig.DOWNLOAD_RETRIES) { attempt ->
            var start = if (dest.exists()) dest.length() else 0L
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
                    readTimeout = UpdateConfig.DOWNLOAD_READ_TIMEOUT_MS
                    instanceFollowRedirects = true     // GitHub 302 → objects.githubusercontent.com（同为 https，自动跟随）
                    setRequestProperty("User-Agent", "vgamepad-updater")
                    if (start > 0L) setRequestProperty("Range", "bytes=$start-")
                }
                val code = conn.responseCode
                when {
                    code == 206 -> { /* 断点续传：追加写入 */ }
                    code == 200 -> { start = 0L; dest.delete() }   // 服务端不支持 Range，从头下
                    else -> return DownloadResult.Failure("服务器返回 HTTP $code")
                }
                val total = start + (conn.contentLengthLong.let { if (it > 0) it else 0L })
                // 存储空间预检
                dest.parentFile?.let { dir ->
                    val free = StatFs(dir.absolutePath).availableBytes
                    if (total > 0 && free < total - start + UpdateConfig.MIN_FREE_MARGIN_BYTES) {
                        return DownloadResult.Failure("手机存储空间不足（剩余 ${free / 1048576} MB，需要约 ${total / 1048576} MB）")
                    }
                }
                var downloaded = start
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(dest, code == 206 && start > 0L).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var lastReport = 0L
                        while (true) {
                            if (isCancelled()) return DownloadResult.Failure("已取消下载（已下载部分保留，下次从断点继续）")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            downloaded += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 200) {       // 节流，避免刷爆 UI 线程
                                lastReport = now
                                onProgress(downloaded, if (total > 0) total else -1)
                            }
                        }
                        out.fd.sync()
                    }
                }
                onProgress(downloaded, if (total > 0) total else downloaded)
                if (total > 0 && downloaded != total) {
                    lastError = "下载不完整（$downloaded / $total 字节），网络中断"
                } else {
                    return DownloadResult.Success
                }
            } catch (e: Exception) {
                lastError = when (e) {
                    is IOException -> "网络异常：${e.message ?: "连接中断"}"
                    else -> "下载出错：${e.message ?: e.javaClass.simpleName}"
                }
            } finally {
                conn?.disconnect()
            }
        }
        return DownloadResult.Failure("$lastError（已重试 ${UpdateConfig.DOWNLOAD_RETRIES} 次，可稍后重试）")
    }
}

/* ======================================================================
 * UpdateVerifier：安装前四重安全校验
 * ==================================================================== */

object UpdateVerifier {

    sealed class Verdict {
        object Ok : Verdict()
        data class Failed(val reason: String) : Verdict()
    }

    fun sha256Hex(file: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    /**
     * 依次校验：文件存在 → SHA-256 → 包名 → versionCode → 签名证书。
     * 任何一步失败都返回 Failed（含用户可读原因），调用方必须禁止安装。
     */
    fun verify(ctx: Context, apk: File, release: ReleaseInfo, expectedSha256: String?): Verdict {
        // 1) 文件基本形态
        if (!apk.isFile || apk.length() <= 0L) return Verdict.Failed("安装包文件不存在或不完整")
        release.apkSize.takeIf { it > 0 }?.let {
            if (apk.length() != it) return Verdict.Failed(
                "安装包大小与发布信息不符（${apk.length()} ≠ $it 字节）")
        }
        // 2) SHA-256 完整性：发布者未提供校验文件时同样禁止安装（防篡改底线）
        if (expectedSha256.isNullOrBlank()) {
            return Verdict.Failed("发布者未提供 SHA-256 校验文件，无法验证安装包完整性")
        }
        val actual = sha256Hex(apk) ?: return Verdict.Failed("无法计算安装包 SHA-256")
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            return Verdict.Failed("SHA-256 校验不一致，安装包可能被篡改或下载不完整")
        }
        // 3)~5) 解析 APK 元数据：包名 / versionCode / 签名
        val flags = (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                     else 0) or PackageManager.GET_SIGNATURES
        val info: PackageInfo = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return Verdict.Failed("无法解析安装包（文件可能已损坏）")
        if (info.packageName != ctx.packageName) {
            return Verdict.Failed("包名不一致（${info.packageName} ≠ ${ctx.packageName}），已阻止安装")
        }
        val cur = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
                if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
            }
        } catch (e: Exception) { 0L }
        val newCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
                      else @Suppress("DEPRECATION") info.versionCode.toLong()
        if (newCode <= cur) {
            return Verdict.Failed("新版本号（$newCode）不高于当前版本（$cur），已阻止安装")
        }
        // 签名证书一致性：下载包的证书 SHA-256 必须与当前已装应用完全相同
        val apkCerts = archiveCertSha256(info)
        val instCerts = installedCertSha256(ctx)
        if (apkCerts.isEmpty() || instCerts.isEmpty()) {
            return Verdict.Failed("无法读取安装包签名信息，已阻止安装")
        }
        if (apkCerts.intersect(instCerts).isEmpty()) {
            return Verdict.Failed("签名证书与当前应用不一致，已阻止安装（请从官方渠道获取更新）")
        }
        return Verdict.Ok
    }

    private fun archiveCertSha256(info: PackageInfo): Set<String> {
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION") info.signatures?.map { it.toByteArray() }
        } ?: emptyList()
        return certs.mapNotNull { certSha256(it) }.toSet()
    }

    private fun installedCertSha256(ctx: Context): Set<String> = try {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                    else PackageManager.GET_SIGNATURES
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, flags)
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            pi.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION") pi.signatures?.map { it.toByteArray() }
        } ?: emptyList()
        certs.mapNotNull { certSha256(it) }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    private fun certSha256(certBytes: ByteArray): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        md.digest(certBytes).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }
}
