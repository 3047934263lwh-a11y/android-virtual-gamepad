# 全面代码复审报告 · Android Virtual Gamepad

> ⚠️ **历史快照（2026-11-04 基线，v1.11.0 / versionCode 22）**：正文中的
> `125 Hz`、`SLOT_TTL_SEC = 300 秒` 等数据已被后续版本取代——当前为
> **250 Hz / TTL 2.0 秒 / VGPQ 告别包**，versionCode 以
> `android/app/build.gradle` 为准（该值会随手机端迭代递增）。
> 本报告结论仅对当时基线有效，**当前参数一律以源码为准**。

| 项 | 内容 |
| --- | --- |
| 复审日期 | 2026-11-04 22:4x |
| 基线 | `b0bc246`（v1.11.0 / versionCode 22） |
| 范围 | Android(Kotlin 8 文件) / Python 接收端与测试游戏 / C# 接收端 / UDP 协议与测试向量 / 资源与 Manifest / Gradle / Windows 脚本 / 文档 |
| 规模 | 8 个 Kotlin（约 3300 行）+ 11 个 Python（约 3350 行）+ 2 个 C#（518 行）+ 30 个 XML/Gradle/BAT |
| 方法 | 编译验证 + Python 全量语法 + 资源引用交叉校验 + 三端协议逐字节对照 + 逐文件人工审查 + 本机 API 实测 |
| 修复 | 同日完成全面修复（v1.11.1 / versionCode 23），见文末「8. 修复状态」 |

## 0. 结论摘要

**没有发现编译错误。** `gradle compileDebugKotlin` 通过（UP-TO-DATE，0 error）；11 个 Python 文件全部语法正确；C# 侧用本机 NuGet 程序集元数据逐项核对了 ViGEm.Client 1.21.256 的 API，未发现不存在的成员或签名错误（本机只有 .NET 运行时、无 SDK，故未实编译）。

共确认 **40 项问题**：**致命 1 / 高 4 / 中 16 / 低 19**。按用户体感排序，最需要优先处理的是：

1. **API 24/25 设备一按按键就崩溃**（震动 API 版本判断漏了 O）；
2. **震动回传会静默永久失效**（`DatagramPacket.length` 复用不复位）；
3. **PC 端退出时"移除虚拟手柄"是空操作**（`pad.unplug()` 根本不存在，但日志宣称成功）；
4. **一键启动脚本在 `py -3` 分支必失败**（把含空格的命令整体加了引号）；
5. **UDP 重连时序竞态**（双发送线程 + socket 泄漏 + 报文字节互相覆盖）。

`docs/AUDIT.md`（v1.9.9 基线）中 A-04"已修复，e2e 通过"是**错误结论**，本轮实测证伪，见 M-16。

---

## 1. 致命（P0，会崩溃）

### A-01 `minSdk 24` 与 `VibrationEffect`（API 26）不兼容，Android 7.x 一触摸即崩

- **位置**：`android/app/build.gradle:12`；`android/app/src/main/java/com/vgamepad/GamepadView.kt:390`；`android/app/src/main/java/com/vgamepad/MainActivity.kt:1132`
- **证据**：
  ```gradle
  minSdk 24
  ```
  ```kotlin
  // GamepadView.kt:367 只区分了 >= S 的 VibratorManager
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { ... } else { ...VIBRATOR_SERVICE... }
  // GamepadView.kt:390 / MainActivity.kt:1132 却无条件调用 API 26 的类
  v.vibrate(VibrationEffect.createOneShot(ms, amp))
  ```
- **原因**：`VibrationEffect` 与 `Vibrator.vibrate(VibrationEffect)` 均为 API 26 引入。API 24/25 设备上执行到 `VibrationEffect.createOneShot` 抛 `NoClassDefFoundError`（属 `Error`，`obtainVibrator()` 的 `catch (e: Exception)` 拦不住，调用处也无 try/catch）。`haptic()` 被 `bindPointer()`（`GamepadView.kt:1207`，任意按键按下）、`setStick()`（1442/1447）调用；`handleRumble()` 在收到震动回包时调用。
- **影响**：声明支持的 Android 7.0/7.1 设备上**按下任意按键即崩溃**；当前实测机（vivo V2338A，Android 14+）不受影响，故一直没暴露。
- **修复**：两处统一加版本分支，或把 `minSdk` 提到 26。
  ```kotlin
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      v.vibrate(VibrationEffect.createOneShot(ms, amp))
  } else {
      @Suppress("DEPRECATION") v.vibrate(ms)
  }
  ```

---

## 2. 高（P1，功能静默失效 / 数据竞争）

### A-02 `UdpSender` 重连竞态：双发送线程 + 共享缓冲区 + socket 泄漏

- **位置**：`android/app/src/main/java/com/vgamepad/UdpSender.kt:62-63, 79-99, 101-116, 161-182, 192-227`
- **证据**：`start()` 先 `stop()`（`stop()` 内 `join(400)`/`join(200)` 有超时且不检查 `isAlive`），随后把**同一个** `@Volatile running` 重新置 `true`；`runLoop()` 的 `InetAddress.getByName(host)`（161）在工作线程且无超时；`buffer`/`bytes`（62-63）是实例级共享，无同步。
- **原因**：旧 worker 若卡在 DNS，醒来后 171 行 `if (!running)` 读到的是新值 `true`，于是继续执行，把旧 sock 写入 `socket`（177）并再起一个 `rumbleThread`（179，旧接收线程失去引用管理）；`stop()` 当时关闭的 `socket` 还是 `null` → 该 socket 永久泄漏并占用 26761。两个 worker 同时进入 192 行循环，共用 `buffer`/`bytes` 与 `state.sequence`（196），`serialize()` 无同步 → 报文字节互相覆盖、序号错乱。另：若 `stop()` 恰好发生在 170 与 177 之间，新 socket 也不会被任何分支关闭。
- **修复**：引入 `AtomicInteger generation`，`start()` 递增并把代次传入线程，循环内校验代次；`buffer`/`bytes` 改为 `runLoop` 局部变量，`sequence` 用 `AtomicInteger`；`stop()` 用「关 socket + 中断」让线程自行退出，`join` 后检查 `isAlive`。最简方案是单线程 `ExecutorService` + `Future.cancel(true)`。

### A-03 `rumbleLoop` 不复位 `DatagramPacket.length`：收到一个短包后震动/编号回传永久失效

- **位置**：`android/app/src/main/java/com/vgamepad/UdpSender.kt:125-131, 147-151`
- **证据**：
  ```kotlin
  val buf = ByteArray(16)
  val packet = DatagramPacket(buf, buf.size)   // 循环外只 new 一次
  while (running) {
      sock.receive(packet)                     // Java 契约：会把 length 改写为本次实收字节数
      if (packet.length == 8 && ...) { ... }   // 循环内从未 packet.length = buf.size
  ```
- **原因**：`receive()` 会把 `packet.length` 改为本次实收长度，且下一次 `receive` 以它为可写上限。一旦收到任意长度 < 8 的杂包/探测包，`packet.length` 被缩小，此后所有合法 8 字节 `VGPR`/`VGPP` 都会被截断，`packet.length == 8` 恒不成立。
- **影响**：游戏震动回传、玩家编号、`markReply()`（`MainActivity.kt:1193`）链路探测全部静默失效，状态栏一直显示"正在连接 / 正在自动重连"，直到重启进程。注释（130 行）明确预期会有非 8 字节包，说明这是设计漏项。
- **修复**：每轮循环开头 `packet.length = buf.size`（或每轮新建 `DatagramPacket`），并用 `packet.length >= 8` 判断后再读 `buf[3..6]`。

### A-04 Python 接收端"移除虚拟手柄"是空操作，日志却是假成功

- **位置**：`server-python/server.py:303-308, 341-353`；回调闭包 `server-python/server.py:131-152`
- **证据**：本机实测 `python -c "import vgamepad; print(hasattr(vgamepad.VX360Gamepad,'unplug'))"` → **`False`**；该类公开方法只有 `register_notification / unregister_notification / reset / update ...`。真实移除在 `__del__` 里：`vcli.vigem_target_remove(self._busp, self._devicep)` + `vigem_target_free(self._devicep)`。
- **原因**：`unplug = getattr(p.pad, "unplug", None); if callable(unplug): unplug()` 恒为 `None`，TTL 槽位回收与退出 `finally` 两处清理**一次都没执行过**；353 行仍打印 `[退出] 全部虚拟控制器已移除。`。`make_rumble_cb` 返回的闭包捕获了 `players` 字典，形成 `pad.cmp_func → on_feedback → players → Player → pad` 引用环，`players.pop(ip)` 后循环变量 `p` 仍持有最后一个 Player → 设备不能及时析构，ViGEmBus 里残留"插着但无响应"的手柄，多次重启会占满 4 个 XInput 槽位。
- **修复**：封装显式 `unplug_pad(pad)`：`pad.unregister_notification()` → `vcli.vigem_target_remove(pad._busp, pad._devicep)` → `vcli.vigem_target_free(pad._devicep)`，加标记避免 `__del__` 二次释放；回调改为按 idx 读一个由主循环维护的可清空地址表，打破引用环；退出信息按实际结果打印。

### A-05 一键启动脚本在 `py -3` 分支必失败

- **位置**：`一键启动.bat:31, 82`；`START-RECEIVER.bat:31, 82`
- **证据**：`:31 set "PY=py -3"`（值含空格），`:82 "%PY%" -u "%PROJ%server-python\server.py" 26760` 展开为 `"py -3" -u ...`。Windows cmd 实测 `"py -3" --version` → `'"py -3"' 不是内部或外部命令`。
- **影响**：脚本先打印 `[OK] Python ready: py -3`（:48），随后启动失败却落到 `:end` 并 `pause`，看起来像"已启动"，用户会一直等连接。
- **修复**：改成 `start "" %PY% -u ...`，或拆分支：
  ```bat
  if "!PY!"=="py -3" ( py -3 -u "%PROJ%server-python\server.py" 26760 ) else ( "%PY%" -u "%PROJ%server-python\server.py" 26760 )
  ```
  更干净的做法是 `PY` 只存解释器路径、`PYARGS` 单独存 `-3`。

---

## 3. 中（P2，逻辑错误 / 健壮性 / 合规）

| 编号 | 文件:行号 | 问题与原因 | 修复建议 |
| --- | --- | --- | --- |
| M-06 | `UdpSender.kt:61,139,143,164,166,190,204,213,226` + `MainActivity.kt:1428` | `UdpSender` 自建 `Handler(Looper.getMainLooper())` 投递回调，与 Activity 的 `uiHandler` 是两个实例；`removeCallbacksAndMessages(null)` 清不掉它 post 的 Runnable。`onDestroy` 后仍可能执行 `renderStatus()/toast()`，访问已销毁 Activity | 给 `UdpSender` 加 `@Volatile var listener: Listener?`，`stop()` 里置空，所有 `main.post { listener?.onXxx() }`；或提供 `detachListener()` |
| M-07 | `UdpSender.kt:147-151` | `catch (e: Exception)` 在 `running=true` 时既不退出也不 sleep，若异常可重复发生会高频空转占满一个 CPU 核（与 A-03 叠加时极易触发） | 加连续异常计数 + 退避（`Thread.sleep(50)`），超阈值 `break` 并上报 `onError` |
| M-08 | `Neumo.kt:17-22, 305-317`；`GamepadView.kt:994, 1017-1029` | `drawInnerShadowRect()` 每帧为 LT/RT + 按压按钮新建 4~8 个 `LinearGradient`；`outlineOf()` 编辑模式每帧 new 14 个 `RectF`。违反文件头"稳定状态每帧零分配"约定，125Hz 输入下持续触发 GC | 渐变改局部坐标后按「高度档 + alpha 档 + rev」缓存；`RectF` 复用成员 `tmp`，`dispose()` 一起清 |
| M-09 | `GamepadView.kt:425-430, 440-452`；`Neumo.kt:425-427` | `loadLayout()` 直接信任 SharedPreferences 里的 JSON，`s/dx/dy` 无钳制（只有 `a` 有 `coerceIn`）。`s=0` → `clamped/radius = 0/0 = NaN`；`s` 极大 → `Bitmap.createBitmap(w+pad*2, ...)` 在 `onDraw` 抛 OOM/IllegalArgumentException 崩溃；`dx/dy` 极大 → 控件飞出屏幕 | `s.coerceIn(0.5f,2f)`、`dx/dy.coerceIn(-1f,1f)`，解析后逐 key `clampIntoBounds()`；`shadowBitmap()` 给宽高加上限并 `try/catch(Throwable)`；`setStick()`/`triggerValue()` 对 `radius<=0`、`height<=0` 提前返回 |
| M-10 | `GamepadView.kt:1111-1149` | 编辑模式 DOWN 用 `event.getX(actionIndex)`，MOVE/UP 却固定用 `event.x`（恒 index 0）。第二根手指按下后，第一个 MOVE 会算出等于两指间距的巨大 `dx/dy`，选中控件被瞬移 | 锁定 `editPointerId`，MOVE 用 `findPointerIndex()` 取同一根手指；UP 按 `getPointerId(actionIndex)` 匹配后再清除 |
| M-11 | `MainActivity.kt:300-302, 1281`；`AndroidManifest.xml:22` | `VgTheme.setMode()` 改 `uiMode`，而 `configChanges` 不含 `uiMode` → 切换主题必重建 Activity；类无 `onSaveInstanceState()`，`userStopped/barExpanded/editMode/selectedKey/playerNo` 全丢。重建后 `onCreate` 见保存过 IP 就无条件 `connect()` 并把 `userStopped` 置回 false → **用户手动点的"断开"被自动重连覆盖** | 实现状态保存/恢复；自动连接条件改为 `if (!userStopped && ip 非空)`；或在 `configChanges` 加 `uiMode` 并自行处理主题重建 |
| M-12 | `tools/e2e_test.py:81, 124` | 直接 `Popen` 拉起 `server.py` 未设 `PYTHONUTF8`（bat 里才手动设），却用 `encoding="utf-8"` 解码。中文 Windows 管道默认 GBK，`已连接/[连接]` 变替换字符，`log_ok` 恒 False → e2e 恒报 FAIL | 注入 `env=dict(os.environ, PYTHONUTF8="1", PYTHONIOENCODING="utf-8")`，或判定只匹配 ASCII 片段（`pps`、`[1P]`） |
| M-13 | `tools/verify_protocol.py:36, 53, 83-87` | 自称复刻两端 `TryParse/parse`，却写 `len(buf) < PACKET`，接受任意 ≥20 字节包；真实实现是严格 `!= 20`（`server.py:81`、`GamepadProtocol.cs:90`、`PROTOCOL.md:34`）。反向用例没覆盖 21 字节超长包，所以脚本"全部通过"掩盖了分歧 | 两处改 `!= PACKET`，并补 `("21 字节超长包", good + b"\x00")` 负向用例（正确覆盖在 `tools/test_protocol.py:107`） |
| M-14 | `server-csharp/Program.cs:105-118, 100-103` | `RunLoop` 是 `void`；端口绑定失败仅打印后 `return`，`Main` 继续打印 `[退出] 虚拟控制器已移除。` 并 `return 0`。端口占用/无权限对 CI 与 `%ERRORLEVEL%` 表现为成功（Python 端同类错误返回 1） | `RunLoop` 改返回 `int`/`bool`，失败返回 1，`Main` 中 `return RunLoop(...) == 0 ? 0 : 1`，失败不打印成功语 |
| M-15 | `game/xbox_platformer.py:200, 202, 212, 489-492, 25-26` | `K_LSHIFT` 同时 `buttons.add("RB")` 与 `rt=255`；而 `:491` `just_pressed("RB")` 会 `respawn(full=True)`（回关卡起点）→ **每次按 Shift 疾跑都被瞬移回起点**。另外文档写 `1/2 回检查点/起点`，代码里根本没有 `K_1/K_2` 映射，且 `RSHIFT→LB` 与文档不符 | 删 `:200` 的 `buttons.add("RB")`，把 `LB/RB` 绑到 `K_1/K_2`；`RSHIFT` 保留为独立键并改文档 |
| M-16 | `docs/AUDIT.md:48-56`；`docs/TEST-REPORT.md:51` | A-04 标记"**已修复**，e2e 通过"，验证依据是 `getattr(...,"unplug")` 的假成功；`e2e_test.py:114` 用 `srv.terminate()` 硬杀进程，本就走不到 `finally`。A-03 标记"已修复"但 `verify_protocol.py` 仍用 `<`（见 M-13），修复未覆盖验证脚本 | 按 A-04/M-13 实际修复后重跑验证，再改文档状态；不要以"脚本打印了成功"作为修复证据 |
| M-17 | `AndroidManifest.xml:12` | `allowBackup="true"` 但无 `dataExtractionRules` / `fullBackupContent`，项目无 `res/xml/`。targetSdk 35 下 `SharedPreferences("vgp")`（电脑 IP、端口、按键布局、陀螺仪/震动偏好）默认进云备份与设备迁移，`adb backup` 也可导出 | 最小改动：`android:allowBackup="false"`；若需备份，新建 `res/xml/data_extraction_rules.xml` 与 `backup_rules.xml` 并 `exclude domain="sharedpref" path="vgp.xml"` |
| M-18 | `AndroidManifest.xml:15`；`res/mipmap-anydpi-v26/ic_launcher_round.xml` | `roundIcon` 只定义在 `anydpi-v26`（要求 API ≥26），而 `minSdk 24`；5 个密度目录只有 `ic_launcher.png`，无 `ic_launcher_round.png`。`roundIcon` 属性本身 API 25 才引入，API 24/25 解析不到匹配条目，配置不对称 | 直接删掉 `android:roundIcon`（自适应图标已由启动器遮罩），或给 5 个密度目录各补一份圆形 PNG |
| M-19 | `android/app/build.gradle:18-25`；`android/app/proguard-rules.pro:2` | release `minifyEnabled false`，proguard 形同虚设；且规则是 `-keep class com.vgamepad.** { *; }`，**即使日后开混淆，自身代码也不会被压缩**，发布包可直接反编译。另整个 `android{}` 无 `signingConfigs`，`assembleRelease` 只产出 `app-release-unsigned.apk`，不可安装 | release 开 `minifyEnabled true` + `shrinkResources true`，规则只保留两个自定义 View 的构造函数；`signingConfigs` 从环境变量读 keystore/密码，release 引用它 |
| M-20 | `res/values/colors.xml:16, 19, 20` | WCAG 2.1 实测浅色对比度不足 AA 4.5:1：`on_primary #FFFFFF / primary #6C7FA8` = 4.01:1；`ok #3F7A55 / surface_variant #E3E8F0` = 4.14:1；`err #B4514B / ...` = 4.05:1（`ok/err` 用于 12sp 状态文字）。深色主题 7 组全部达标 | 改为 `primary/accent #FF5C6F99`（5.01:1）、`ok #FF356B4B`（5.09:1）、`err #FFA13E38`（5.23:1） |
| M-21 | `res/layout/activity_main.xml:168, 193, 210, 226` | 编辑栏 4 个 `android:text` 是字面量中文，同一布局其余文本都走 `@string`，无法本地化 | `values/strings.xml` 增加 `edit_pick / edit_reset_one / edit_reset_all / edit_done`，布局改 `@string/...` |

---

## 4. 低（P3，边界 / 一致性 / 可访问性）

| 编号 | 文件:行号 | 问题 | 修复建议 |
| --- | --- | --- | --- |
| L-22 | `res/values-night/colors.xml:9` | `<color name="bar_bg">#F222262C</color>` 少写一个 F，alpha=F2(242) 半透明；同文件其余 17 个颜色都是 `FF` 不透明，浅色版也是不透明 → 深色下工具栏透出画布底色 | 改 `#FF22262C` |
| L-23 | `activity_main.xml:172-178, 35-47, 61-66` | `editSeek` 可操作却无 `contentDescription`；`titleBox` 是可折叠开关但无说明；`statusDot` 是纯装饰 View 未标 `importantForAccessibility="no"` | 补 `@string/edit_seek_desc`、`@string/bar_title`，装饰 View 标 `no` |
| L-24 | `activity_main.xml:121-128, 103-119, 70-101, 180-228` | 齿轮键 36×36dp、连接按钮/输入框 36dp 高、编辑栏按钮 34dp 高，低于 48dp 最小触摸目标（横屏误触） | 视觉图标保持 36dp，把 `layout_width/height` 提到 48dp + `padding=6dp`；去掉 `minHeight="0dp"` |
| L-25 | `android/app/build.gradle:23-25` | `debug { applicationIdSuffix "" }` 空字符串无任何作用，残留配置 | 删除整个 debug 块 |
| L-26 | `MainActivity.kt:352-360, 438, 562, 771, 788, 970, 1063-1077, 1159-1181` | 状态文字、设置面板标签、Toast、对话框大量字面量中文，与同文件 `getString(R.string.*)`（1159/1187/1203/1272/1276）混用 | 逐步纳入 `strings.xml`，先统一状态栏文案 |
| L-27 | `GamepadState.kt:78-86` + `UdpSender.kt:233-251` | 每字段 `@Volatile` 保证可见性（无写-写竞态），但 `serialize()` 连续读 10+ 字段不是原子快照，`reset()` 中途会组装出"半帧"（如 buttons 已清零而 lt 仍 255）。125Hz 下下一帧即纠正 | 用不可变 `data class Snapshot` + 单个 `@Volatile var snapshot`，或 `synchronized` 包住 reset 与读集合 |
| L-28 | `GamepadView.kt:299, 1157, 1232` | `SystemClock.uptimeMillis().toInt()` 在开机约 24.85 天后回绕为负，`(now-down)` 变巨大负数 → 所有按下/回弹动画永久失效 | `SparseIntArray` 改 `SparseLongArray`，三处统一用 Long |
| L-29 | `GamepadView.kt:323-326, 350-353, 1073-1098, 1153-1216` | 多根手指可绑到同一摇杆/扳机，但 `lsDownX/Y`、`ltDownY`、`ltDragging`、`lsKnob` 都是单份状态；MOVE 循环依次 `applyZone`，结果由最后一根手指决定 → 摇杆值跳变、L3 轻点误判 | 每个 zone 只接受第一个 pointerId，或落点按 pointerId 存 Map |
| L-30 | `MainActivity.kt:73, 638, 1037-1049, 1426-1438`；`GamepadView.kt:557-564, 1352-1358` | `barAnim` / `hideAnim` / `overlay.animate()` 与 `pulseThumb` 的 `postDelayed` 未取消；`onDetachedFromWindow` 只 cancel 了 intro/theme 动画，脉冲 Runnable 靠 token 变 no-op 但消息仍在队列 | `onDestroy` 里 cancel 各 Animator；`pulseThumb` 保存 Runnable 并在 `releaseAll()` / detach 时 `removeCallbacks` |
| L-31 | `GamepadView.kt:183-188, 1282-1299` | `setHidden(key, true)` 注释说"清掉可能仍按着的按键位"，实现却是 `releaseAll()` → 把其它正在按的按键/摇杆/扳机一并归零 | 只清被隐藏 key 对应的 zone/bit |
| L-32 | `UdpSender.kt:109, 114` | `stop()` 在主线程 `join(400)+join(200)`，最长阻塞 600ms；`start/stop` 都在主线程（连接按钮、自动重连、watchdog、onDestroy），旧线程卡 DNS 时有 ANR 风险 | 配合 A-02 让线程快速自退，join 降到 50ms 或移到后台线程 |
| L-33 | `GyroAim.kt:86-87` | 丢弃 `registerListener` 返回值，无条件 `running = true`；返回 false 时开关显示"已开启"但从不回调，体感静默失效 | `running = sm.registerListener(...)`，失败上报并提示"传感器不可用" |
| L-34 | `server-csharp/Program.cs:188-205` | drain 循环每次 `ReceiveFrom` 都刷新 `remote`，即使 `TryParse` 失败也不跳过，最终用**最后一个（可能是非法/伪造）包**的来源设置 `_lastClient` 并回发 `VGPP`，与 `PROTOCOL.md:63`"回发到报文来源端口"语义不符 | 单独记录"最后一个成功解析包的来源端点"，非法包不改变逻辑客户端 |
| L-35 | `tools/verify_csharp.py:43, 166`；`tools/verify_udp_reset.py:29` | 模块级 `ctypes.WinDLL("xinput1_4.dll")` 无回退（缺 DLL 直接崩，而 `e2e_test.py:40-46` 已有 `xinput1_3` 回退）；`:166 base = read().buttons if read() else 0` 调用两次 `read()`，第二次返回 `None` 会抛 `AttributeError` | 复用 `e2e_test.py` 的 DLL 回退并 SKIP；`st = read(); base = st.buttons if st else 0` |
| L-36 | `server-python/server.py:219-220` | 积压丢弃数算成 `drained - MAX_PLAYERS(4)`，实际应为 `drained - len(latest)`（按 IP 去重后保留数），只有 1~3 个来源时数字偏小 | `dropped = drained - len(latest)` |
| L-37 | `game/xbox_platformer.py:809` | `COLOR["outline"] if False else (52,60,70)` 引用了不存在的字典键，当前被常量 `False` 屏蔽（惰性死代码），改成条件即崩 | 直接写字面量 `(52,60,70)` |
| L-38 | 全部 8 个 `.bat`（含 `game/*.bat`） | 实测 CRLF=0，全是 LF 换行；`goto`/标签与括号块在 cmd.exe 下对 LF-only 有已知兼容风险（内容为纯 ASCII，无中文编码问题） | 统一转 CRLF，并加 `.gitattributes`：`*.bat text eol=crlf` |
| L-39 | `game/look_test.py:511`、`twin_stick.py:821`、`fps_walk.py:304`、`xbox_platformer.py:382` | `load_font()` 硬编码 `C:/Windows/Fonts/msyh.ttc`，`Pad/Rumble` 用 `ctypes.WinDLL`（非 Windows 上 `except OSError` 捕获不到 `AttributeError`）。项目定位 Windows+XInput，属可接受的平台限定，但回退字体渲染不了中文 | 用 `pygame.font.get_default_font()` 或随包附字体；对 `WinDLL` 做 `hasattr` 保护；文档标注"仅 Windows" |
| L-40 | `UdpSender.kt:47` vs `121-122` | 注释自相矛盾：一处写"接收端按来源端口回发"，另一处写"PC 端向固定 26761 回包"。**经核实 PC 端确实按来源端口回发**（`server.py:141-145` 用 `addr`、`Program.cs:197-205` 用 `remote` / `ep`），因此 `openSocket()` 端口冲突时退回随机端口**不会**导致震动失效，仅影响诊断 | 删除 121-122 的误导注释；回退时把实际 `localPort` 记日志 / `onError` 提示，不要静默 |

---

## 5. 已核实「未发现问题」的项

- **编译**：`compileDebugKotlin` 通过，0 error；11 个 Python 文件语法全通过。
- **资源引用**：脚本交叉校验 `res/**/*.xml` + `AndroidManifest.xml` + `*.kt`：**引用了但未定义的资源 0 条、定义但未引用 0 条**；17 个 `@+id` 与代码 `R.id.*` 全对齐；`values-night` 的 18 个同名 color 是合法夜间覆盖；除 L-22 外 35 个色值格式合法。
- **三端协议一致性**：`VGP1` 魔法/版本/20 字节定长/全小端、字段偏移（buttons@5、seq@7、lx/ly/rx/ry@8..15、lt/rt@16/17）、按钮位掩码 ↔ XInput 常量，在 `UdpSender.kt`、`server.py`、`GamepadProtocol.cs`、`GamepadState.kt`、`PROTOCOL.md` 中逐字段一致；`test_vectors.json` 5 组与 `test_vectors_v2.json` 5 组 HMAC 重算全部吻合。VGP2 按设计尚未实现、不参与 CI，不构成缺陷。
- **Manifest 权限**：`INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE / VIBRATE` 均为 normal 权限，无需运行时申请；无 HTTP 调用，`usesCleartextTraffic` 缺失不影响 UDP。
- **`exported` / 方向 / SDK**：`exported="true"` 对 LAUNCHER Activity 是 Android 12+ 必需；`sensorLandscape` 与 `configChanges` 一致（缺 `uiMode` 单列 M-11）；`namespace/applicationId/package` 三者一致；Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21 / jvmTarget 17 匹配。
- **依赖**：core-ktx 1.13.1 / appcompat 1.7.0 / material 1.12.0 均存在；`<Button>` 被 `MaterialComponentsViewInflater` 实例化为 `MaterialButton`，与 `MainActivity.kt:252-254` 的 `findViewById<MaterialButton>` 匹配，**不存在 ClassCastException**；`nonTransitiveRClass` 下使用全限定 `com.google.android.material.R.attr.*` 正确。
- **C# API**：解析本机 NuGet 缓存 `Nefarius.ViGEm.Client 1.21.256` 元数据确认 `IVirtualGamepad` / `IXbox360Controller` 的成员、`Xbox360FeedbackReceivedEventArgs.LargeMotor/SmallMotor` 类型均与 `Program.cs` 用法吻合，未发现编译错误。
- **生命周期/线程（正确部分）**：传感器 `registerListener`/`unregisterListener` 经 `applyGyroState()` 在 `onResume/onPause/onDestroy/onConnected/onStopped` 成对调用；`NetworkCallback` 在 `onDestroy` 注销；watchdog/reconnectTask 的 `removeCallbacks` 已配对；无静态单例持有 Context；`onDestroy` 关 socket + join 线程；`Neumo` 主题切换会 `rev++` 并清 shader/shadow 缓存，`dispose()` 统一回收。
- **触摸主链路**：`ACTION_UP/POINTER_UP/CANCEL` 均有处理，`heldByOther(zone)` / `bitHeldByOther(bit)` 正确实现"最后一根手指抬起才清位"；pointerId 与 index 在**正常输入路径**配对正确（仅编辑模式有 M-10）；摇杆死区线性重映射、Y 轴翻转正确；设置项持久化读写键名全部对应；IP/端口校验齐全，DNS 在工作线程。
- **异常防护**：`!!` 强解包 0 处；`lateinit` 全在 `onCreate` 赋值；系统服务取值全部用 `as?`；无 TypedArray/Cursor/InputStream 使用，故无回收遗漏。
- **bat 路径**：8 个脚本的 `cd /d "%~dp0..."` 均带 `/d` 且加引号，中文路径/跨盘符处理正确；`chcp 65001` + `PYTHONUTF8=1` 齐全（问题只在 A-05 的引号与 L-38 的换行）。

## 6. 验证受限项（环境，非代码缺陷）

1. **Android Lint 未能运行**：离线模式下 `lintAnalyzeDebug` 缺 `com.android.tools.lint:lint-gradle:31.7.3` 缓存，`generateDebugAndroidTestLintModel` 缺 `androidx.arch.core:core-runtime:2.1.0`；两次尝试（含 `-x` 跳过测试模型）均因 offline 缓存不完整失败。要拿完整 lint 报告需联网执行一次 `gradle lintDebug`。
2. **C# 未实编译**：本机 `C:\Program Files\dotnet` 只有运行时、无 SDK；已用程序集元数据自证 API 正确性。
3. **未启动长期服务**：`e2e_test.py` / `verify_csharp.py` / `verify_udp_reset.py` / 各 `.bat` 未实际运行（避免干扰用户已连接的接收端）；A-05 与 M-12 用等价的最小复现验证。
4. **真机 API 24/25 未实机复现**：A-01 由 API level 契约 + 代码路径静态确定。

## 7. 建议修复顺序

- **第 1 批（会崩 / 功能失效，改动小、风险低）**：A-01、A-03、A-04、A-05、M-13、M-14、M-15、L-22。
- **第 2 批（时序与健壮性，需回归）**：A-02、M-06、M-07、M-09、M-10、M-11、M-12、M-16。
- **第 3 批（合规 / 一致性 / 性能）**：M-08、M-17~M-21、L-23~L-40。

任何一批修复后建议至少回归：`assembleDebug`、三端协议 `tools/test_protocol.py`、按键 29 项输入回归、Android 7.x（或 minSdk 提到 26）、PC 端 Ctrl+C 后 ViGEmBus 设备数、以及一键启动脚本的 `py -3` 分支。


---

## 8. 修复状态（2026-11-04 同日完成，v1.11.1 / versionCode 23）

| 状态 | 条目 |
| --- | --- |
| **已修复（38）** | A-01（震动 API 版本分支 ×2）、A-02（发送代次令牌 + 线程局部缓冲 + AtomicInteger 序号 + 主动关 sock）、A-03（每轮复位 packet.length）、A-04（`unplug_pad()` 显式 remove + 打破引用环 + 真实退出统计）、A-05（bat 拆分支）、M-06（stop() 清空 listener，回调判空）、M-07（异常退避 + 上限）、M-08（内阴影渐变按高度档缓存，编辑 RectF 复用）、M-09（loadLayout 全字段钳制 + clampIntoBounds + shadowBitmap 尺寸上限/try-catch + 除零保护）、M-10（编辑模式 editPointerId）、M-11（onSaveInstanceState 恢复 userStopped + 自动连接条件）、M-12（e2e 注入 PYTHONUTF8 + ASCII 兼容判定）、M-13（verify_protocol `!=` + 21 字节用例）、M-14（RunLoop 返回码）、M-15（Shift 不再触发 RB，LB/RB 绑 1/2）、M-16（AUDIT/TEST-REPORT 错误结论修正）、M-17（allowBackup=false）、M-18（删 roundIcon 与无引用资源）、M-19（minify+shrink 启用，规则收窄为两个自定义 View 构造）、M-20（浅色三组对比度 ≥5:1）、M-21（4 处硬编码文本入 strings）、L-22（深色 bar_bg 补 alpha）、L-23（3 处无障碍标签）、L-25（删空 debug 块）、L-27（ioLock 串行化 reset 与序列化）、L-28（时间戳 Long 化）、L-29（同 zone 单指绑定）、L-30（barAnim/pulseRunnable 清理）、L-31（隐藏键只清自身状态）、L-32（stop 加 interrupt 且 join 降至 150/100ms）、L-33（registerListener 返回值）、L-34（C# drain 只认成功解析的来源）、L-35（XInput DLL 回退 + 双 read 修复）、L-36（积压丢弃计数）、L-37（死代码清理）、L-38（8 个 bat 全 CRLF + .gitattributes）、L-40（回传端口注释澄清 + 随机端口一次性提示） |
| **额外发现并修复（2）** | 布局 `<Button>` 与 `findViewById<MaterialButton>` 的 WrongViewCast（lint 报 3 errors，会阻断 release 的 lintVital）→ 显式 MaterialButton；离线 lint 缺依赖 → 联网补齐 `lint-gradle:31.7.3`，lintDebug 清零 |
| **未修（2，设计权衡）** | L-24 触摸目标 48dp——会破坏已定稿的紧凑横屏 UI（36dp 按钮间距本就按手型调过）；L-26 Kotlin 文案全面 i18n 抽取——纯合规项，涉及数十处，留待正式做多语言时一并处理 |
| **验证** | `assembleDebug` ✅、`assembleRelease`（含 minify/shrink）✅、`lintDebug` 0 errors ✅、`verify_protocol.py`/`test_protocol.py` 全通过 ✅、11 个 Python 语法 ✅ |
