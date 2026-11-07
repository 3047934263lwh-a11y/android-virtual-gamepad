package com.vgamepad.updater

/**
 * 应用内自动更新系统的唯一配置入口。
 *
 * 更换仓库 / 调整策略只改本文件，不要把仓库地址散落到其他代码里。
 * 其他 Android 项目复用本模块时，也只需修改这个 object。
 */
object UpdateConfig {

    // ---- GitHub 仓库信息（Releases API 与 APK 下载都基于它） ----
    const val REPO_OWNER = "3047934263lwh-a11y"
    const val REPO_NAME = "android-virtual-gamepad"

    /** 官方 Releases API：仅返回最新正式版（自动排除 Draft 与 Pre-release） */
    val API_LATEST: String =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    // ---- 检查策略 ----
    /** 启动检查的最小间隔：距上次检查不足该时长时跳过，避免每次启动都请求 GitHub */
    const val MIN_CHECK_INTERVAL_MS: Long = 12 * 60 * 60 * 1000L   // 12 小时

    /** 启动后延迟多久发起首次检查（等连接界面稳定，避免和建连抢资源） */
    const val LAUNCH_CHECK_DELAY_MS: Long = 3_500L

    // ---- 网络超时 ----
    const val CONNECT_TIMEOUT_MS = 10_000
    const val API_READ_TIMEOUT_MS = 15_000
    const val DOWNLOAD_READ_TIMEOUT_MS = 30_000

    /** 下载失败自动重试次数（断点续传，从已下载字节继续） */
    const val DOWNLOAD_RETRIES = 3

    /** 下载目标目录所需的剩余空间余量（含解压/安装临时占用） */
    const val MIN_FREE_MARGIN_BYTES: Long = 24L * 1024 * 1024

    // ---- 强制更新标记 ----
    /** Release 更新日志中包含该标记时弹不可关闭的更新窗（预留机制，平时不用） */
    const val FORCE_MARKER = "[force]"
}
