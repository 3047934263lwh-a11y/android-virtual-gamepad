# 「一直显示正在重连」根因与修复（2026-11-06）

## 1. 现象

手机端状态条长期显示 **「连接中断，正在自动重连…」**（圆点橙色），
而电脑端接收端明明在运行、端口 26760 正常监听。

更关键的一点：**这种状态下手机一个数据包都没发。**

## 2. 定位过程

### 2.1 先排除网络与驱动

| 检查 | 结果 |
|---|---|
| 手机 ping 电脑 `192.168.1.10` | 通，9.6 ms，0% 丢包 |
| 电脑监听 `0.0.0.0:26760` | 正常（进程持有） |
| 接收端日志 | `ViGEmBus 就绪`，但 `online=0 pps=0 bad=0` → **收不到任何包** |
| 电脑侧直接 bind 26760 抓包 | 8 秒 **0 包** |

结论：不是驱动、不是防火墙，是**手机端根本没在发送**。

### 2.2 再看 App 内部状态

临时插入 `VGP-DBG` 日志后复现（杀掉接收端 → 5 秒 → 重启），得到铁证：

```
checkLink: sender 未运行 → scheduleReconnect (userStopped=false resumed=true)   ← 每 500ms 一条
checkLink: sender 未运行 → scheduleReconnect (userStopped=false resumed=true)
...（连续数十条，持续 15 秒以上）
```

**整个日志里没有任何一条 `attemptReconnect`** —— 重连任务从未被执行过。

## 3. 根因：看门狗把重连任务无限推迟

`MainActivity` 里两段代码互相打架：

```kotlin
// 看门狗：每 500ms 跑一次
private val watchdog = object : Runnable {
    override fun run() {
        checkLink()
        uiHandler.postDelayed(this, WATCHDOG_MS)   // 500ms
    }
}

// checkLink 发现发送器没跑 → 排一次重连
if (!sender.isRunning) { scheduleReconnect(); return }

// scheduleReconnect：先移除旧任务，再按退避延时重排
private fun scheduleReconnect(delayMs: Long = -1L) {
    uiHandler.removeCallbacks(reconnectTask)
    val backoff = 2000L shl (autoRetryCount.coerceAtMost(3))   // 2s → 4s → 8s → 15s
    uiHandler.postDelayed(reconnectTask, d)
}
```

**看门狗 500 ms 一次 `removeCallbacks` + 重新 `postDelayed(≥2000ms)`，
重连任务永远走不到执行时刻。** 退避越久越不可能执行，形成死循环：
显示“正在重连”，实际从不重连。

触发条件很常见：PC 端停止/重启、Wi-Fi 漫游、短暂丢包超过 `LINK_LOST_MS`（6 秒）。

## 4. 修复

### 4.1 幂等化重连排程（根因）

```kotlin
private var reconnectScheduled = false
private val reconnectTask = Runnable {
    reconnectScheduled = false
    attemptReconnect()
}

private fun scheduleReconnect(delayMs: Long = -1L) {
    if (userStopped || !resumed) return
    // 已有待执行的重连（隐式延时）就不再重置，否则看门狗会把任务无限推迟
    if (reconnectScheduled && delayMs < 0L) return
    ...
    reconnectScheduled = true
    uiHandler.postDelayed(reconnectTask, d)
}
```

显式传 `delayMs`（链路刚断时传 500ms）仍然允许重排，保留原有“立即重试一次”的语义。

### 4.2 发送线程异常退出自愈

`UdpSender.runLoop` 原来在 `send()` 连续抛错时只打日志、不退出循环，
`running` 恒为 true → `attemptReconnect()` 因 `sender.isRunning` 永久早退。

```kotlin
// 持续 1.5 秒发不出去（Wi-Fi 漫游、socket 被回收）：退出循环，
// 交给 onStopped → scheduleReconnect 重建 socket
if (now - lastOkAt > 1_500_000_000L) break

// 收尾：仅当代次仍属当前会话时才清 running，避免旧线程覆盖新会话
if (gen == generation.get()) running = false
```

### 4.3 看门狗兜底

`sender.lastSendAt`（最近一次成功发送时间）暴露给 UI 层：

```kotlin
val lastSend = sender.lastSendAt
if (lastSend > 0L && SystemClock.elapsedRealtime() - lastSend > 3000L) {
    sender.stop()
    scheduleReconnect(500L)
}
```

即使线程意外死亡而 `running` 残留为 true，3 秒内也会被强制重建。

## 5. 同批修复的两个关联缺陷

### 5.1 僵尸虚拟手柄导致创建设备失败（PC 端）

进程被强杀后，ViGEmBus 里会残留“已插件但无人持有”的目标，`SerialNo` 仍被占用；
新的 `PLUGIN_TARGET` 返回 `ERROR_INVALID_PARAMETER(87)`，`_make_pad` 抛异常。

`vigem_native.VX360Gamepad` 增加自愈：遇到 87 先 `UNPLUG_TARGET` 清理同号目标，
再换新序列号重试（最多 4 次）。

同时 `receiver.py` 不再在创建设备失败时 `continue`，而是**照常注册玩家并回发 VGPP**，
让手机端能区分「PC 没开」与「手柄建不出来」，并在 PC 日志给出明确错误。

### 5.2 手机端 APK 重新随包（对齐 v1.0.x）

早期 v1.0.1–v1.0.10 的便携包内含 `虚拟手柄手机端.apk`；v1.1.0 改版后丢了。
`build_portable.py` 已补回：打包时从 `android/app/build/outputs/apk/debug/` 取 APK，
放入 `手机端/` 目录并附 `安装说明.txt`。当前包 **235 个文件 / 51.2 MiB**（原 233 / 46.6 MiB）。

## 6. 验证

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 首次启动 App | 正常 | 正常（XInput 30/30 在线） |
| PC 接收端重启后 | **永久卡在重连，0 包** | **1 秒内自动恢复**（XInput 20/20） |
| 断链 2 分钟后再启动 PC | 需手动重开 App | 1 秒内自动恢复 |
| 震动回传 | — | 4 组下发 → 4 条振动记录，无回归 |

复现脚本逻辑：启动 App → 确认 XInput 在线 → 强杀接收端 → 等 5 秒 → 重启接收端 →
逐秒采样 `XInputGetState`。

## 7. 用户侧应急办法（旧版本仍会遇到时）

若暂时不更新 APK，出现该症状时点一次状态条里的**连接按钮**（断开→再连接）
或重开 App，即可强制重建发送器。
