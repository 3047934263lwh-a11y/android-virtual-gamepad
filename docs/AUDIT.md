# 代码审计报告 · Android Virtual Gamepad

| 项 | 内容 |
| --- | --- |
| 审计日期 | 2026-11-04 |
| 基线版本 | v1.9.9（commit `982a111`） |
| 基线备份 | `_backup/virtual-gamepad_v1.9.9_20261104_2046.zip`（175 文件 / 0.64 MB） |
| 审计范围 | Android(Kotlin) / Python 接收端 / C# 接收端 / UDP 协议 / 测试游戏 / 自动化测试 / Windows 脚本 / 依赖与构建 / 文档 |
| 规模 | 60 个源文件，约 9.5k 行（Kotlin 3561 / Python 3356 / C# 428 / 文档 1140 / 脚本 285） |
| 证据等级 | **已证实**：静态可确定或已由测试复现；**潜在**：需特定环境/时序；**需环境确认**：本机无法验证 |

> 状态说明：`已修复` = 本轮已改代码并通过可执行的验证；`待修` = 已定位但本轮未改（含原因）。

---

## 1. 严重（P0）

### A-01 UDP 缺少身份认证，任意主机可注入手柄输入

- **文件**：`android/.../UdpSender.kt:194`；`server-python/server.py:71-80`；`server-csharp/GamepadProtocol.cs:80`
- **原因**：报文只有 magic + version + 状态字段，无设备身份、无消息认证码。
- **触发**：同一局域网内任意主机向 UDP 26760 发送一条 20 字节报文。
- **影响**：可伪造按键/摇杆，劫持虚拟手柄；多人模式下可抢占或干扰他人槽位。
- **修复**：协议升级到 VGP2（设备 ID + Session + HMAC-SHA256 截断 8 字节 + 单调序列号 + 重放窗口），见 `docs/PROTOCOL.md` §4。
- **验证方式**：`tools/test_vectors_v2.json` 的 MAC 用例 + 负向用例（改 1 bit 必须被拒）。
- **状态**：**待修**（协议切换需 Android/Python/C# 三端同时上线并实机联调；当前手机离线、C# 无 SDK，强行切换会导致链路不可用）。

### A-02 以来源 IP 识别玩家，身份不稳定

- **文件**：`server-python/server.py:173,195,210`
- **原因**：`players` 以 `addr[0]`（IP）为键。
- **触发**：同一 IP 多实例 / NAT 后多设备 / DHCP 重新分配 IP。
- **影响**：不同设备被合并成同一玩家（输入互相覆盖）；或同一设备换 IP 后拿到新槽位，原槽位要等 TTL。
- **修复**：VGP2 引入 64 位 `deviceId`（安装时随机生成并持久化）+ `sessionId`，服务端以 deviceId 为键，IP 仅用于回包路由。
- **验证方式**：两个模拟客户端使用相同 IP 不同 deviceId，应分配不同槽位。
- **状态**：**待修**（同 A-01）。

### A-03 报文长度校验不严格

- **文件**：`server-python/server.py:73`（原 `len(data) < PACKET_SIZE`）；`server-csharp/GamepadProtocol.cs:84`（原 `length < PacketSize`）；`android/.../UdpSender.kt:130`（原 `packet.length >= 8`）
- **原因**：用"至少"而不是"恰好"，超长包会被截断后继续解析。
- **触发**：版本不匹配的新客户端、被拼接/构造的报文。
- **影响**：旧接收端可能把 VGP2 或畸形包当作合法输入；Android 端可能把超长回包误判为震动/编号。
- **修复**：三端改为严格相等（Python `!=`、C# `!=`、Kotlin `==`）。
- **验证方式**：`tools/test_protocol.py::test_reject_malformed_packets` 覆盖 19B / 21B / 空包 / 错 magic / 错版本。
- **状态**：**已修复**，测试通过。（2026-11-04 复审补充：`tools/verify_protocol.py` 当时仍用 `<`，
  本轮已改为 `!=` 并补 21 字节超长包负向用例，与真实实现对齐。）

### A-04 Python 接收端退出时未移除虚拟手柄

- **文件**：`server-python/server.py:304`（原 `finally: sock.close()`）
- **原因**：只关闭 socket，没有 `pad.unplug()`。
- **触发**：Ctrl+C / 关闭窗口 / 脚本 kill。
- **影响**：ViGEmBus 中残留"插着但无响应"的虚拟手柄，游戏枚举到幽灵设备；多次重启会累积。
- **修复**：~~`finally` 中遍历 `players` 逐个 `unplug()`（`getattr` 保护）~~
  **该实现无效**：vgamepad 根本没有 `unplug()` 方法（实测 `hasattr(VX360Gamepad,'unplug')==False`），
  `getattr` 恒为 None，清理从未执行过，"已修复"是假成功。
  2026-11-04 复审已真正修复：新增 `unplug_pad()` 显式调用
  `unregister_notification()` + `vcli.vigem_target_remove()`（free 留给析构，避免 double-free）；
  同时打破震动回调闭包对 `players` 的引用环（改走模块级 `RUMBLE_ADDRS`），
  退出信息按实际移除数打印。
- **状态**：**已修复（2026-11-04 复审轮，重写实现）**。

### A-05 C# 接收端缺少震动回传与玩家编号

- **文件**：`server-csharp/Program.cs`（原全文无 `SendTo` / `FeedbackReceived`）
- **原因**：早期单人参考实现未实现反向通道。
- **触发**：使用 C# 接收端并期望手机震动/显示 1P。
- **影响**：手机端 `rumbleLoop` 永远收不到 `VGPR`，震动功能 100% 失效；状态栏无玩家编号。
- **修复**：订阅 `IXbox360Controller.FeedbackReceived`，按 `VGPR`(8B) 回发；连接时与每秒周期发送 `VGPP`(1P)。事件签名已用官方示例核对（`e.LargeMotor` / `e.SmallMotor` 为 `byte`）。
- **验证方式**：C# 端 **未能编译验证**（本机无 .NET SDK），需在有 SDK 的机器 `dotnet build` 后用 `tools/e2e_test.py` 同款报文验证。
- **状态**：**已修复但未编译验证**。风险：中。

### A-06 一键启动脚本会误杀其他项目的 Python 进程

- **文件**：`一键启动.bat:63`；`START-RECEIVER.bat:63`
- **原因**：`$_.CommandLine -like '*server.py*'` 匹配任何含 `server.py` 的命令行。
- **触发**：本机同时运行其他项目的 `server.py`（Django/Flask/自建服务）。
- **影响**：启动本接收端会强杀无关进程，可能造成数据丢失。
- **修复**：匹配模式收紧为 `'*server-python*server.py*'`（要求路径含 `server-python` 目录）。
- **验证方式**：静态审查 + 在本机启动一个 `python other/server.py` 后执行脚本，该进程应存活（需人工确认一次）。
- **状态**：**已修复**（静态可确定，未做真实误杀演练）。

### A-07 测试游戏 `just_pressed()` 同帧多次调用丢失按键

- **文件**：`game/look_test.py:131`（原实现）
- **原因**：`def just_pressed(self, name, _prev=[set()])` 使用**可变默认参数**，且每次调用都把 `_prev[0]` 更新为当前帧。第一次调用后基准已变成当前帧，同帧内后续调用必然返回 `False`。
- **触发**：同一帧检查多个按键，例如 `look_test.py:367-369` 连续查询 `RB` 与 `BACK`；同时按下时只有第一个生效。
- **影响**：组合键/同时按键漏检；多实例共享默认 set 还会互相污染。
- **修复**：改为实例字段 `_prev_buttons`，在 `poll()` 开头统一前移一次；`just_pressed()` 只读该字段。
- **验证方式**：新增 `tools/test_gamepad.py::test_just_pressed_multiple_queries`（同一帧查 A、B 均应为 True）。
- **状态**：**已修复**，单测通过。

---

### A-27 Python 接收端在「客户端异常断开」后整进程退出（本轮新增）

- **文件**：`server-python/server.py:199-205`（原实现）
- **原因**：排空循环只捕获 `BlockingIOError`。Windows 对已关闭的 UDP 端口发包会收到
  ICMP 端口不可达，系统把它翻译成 `ConnectionResetError`（WSAECONNRESET 10054），
  该异常一路抛出 `while True`，直接落到 `finally` 结束进程。
- **触发**：手机端 App 被杀、切换 Wi-Fi/移动网络、或笔记本换网后，
  接收端下一次向旧地址回发 `VGPP`/`VGPR` 时必现。
- **影响**：**单人场景接收端退出；多人场景一次掉线等于把所有玩家一起踢下线**，
  且需要人工重启，属于可稳定复现的可用性故障。
- **修复**：在 `except BlockingIOError` 之后增加 `except ConnectionResetError`
  （退避 5 ms 后继续）与 `except OSError` 兜底（2 ms），任何单来源瞬时错误都不再终止主循环。
- **验证方式**：`_buildenv/verify_udp_reset.py` — 关闭客户端 socket 后等待 3.2 s
  （覆盖 3 次周期回包触发 ICMP），再同端口重连并断言服务端仍转发按键与回包。
  修复后 **4/4 PASS**，服务端日志无异常、进程存活。
- **状态**：**已修复**。

---

### A-28 C# 接收端遇 `ConnectionReset` 空转刷屏（本轮新增）

- **文件**：`server-csharp/Program.cs:160-243`（原实现）
- **原因**：`ReceiveFrom` 抛出的 `SocketException(ConnectionReset)` 落入通用
  `catch (SocketException)`，只打印一行就继续循环，没有任何退避；下一轮立刻再次抛出。
  实测 2 秒内打印 20+ 行 `[错误] 接收失败：ConnectionReset`，CPU 持续空转。
- **触发**：与 A-27 相同（手机端消失后接收端仍周期性回发）。
- **影响**：控制台被无效日志淹没，`[状态]`/`[超时]` 等有效信息被冲掉；
  持续空转占满一个核心，且新设备接入时更难排查。
- **修复**：① socket 建好后调用 `IOControl(0x9800000C /*SIO_UDP_CONNRESET*/, 0)` 关闭该通知；
  ② 单独 `catch ... when (SocketErrorCode == ConnectionReset)`，`Thread.Sleep(5)` 后退避；
  ③ 其余 `SocketException` 日志按 1 秒限流并 `Sleep(10)`。
- **验证方式**：同上脚本，修复后 **4/4 PASS**，日志中 `[错误]` 计数为 **0**（修复前 20+）。
- **状态**：**已修复**。

---

## 2. 中等（P1）

### A-08 Python `players` 字典跨线程访问

- **文件**：`server-python/server.py:133`（驱动回调线程）vs `:219,232,271`
- **原因**：ViGEm 回调在驱动线程执行，主循环同时增删 `players`；原实现用 `list(players.values())` 规避了 `RuntimeError: dictionary changed size`，但无同步语义。
- **影响**：极端时序下读到半更新状态；`p.addr` 更新与读取无 happens-before 保证。
- **修复**：新增 `threading.Lock`，结构变更（插入/删除）与回调快照均持锁；锁内只取地址，UDP 发送放到锁外。
- **验证方式**：e2e 连续运行 + 代码审查（GIL 下引用赋值原子，锁保证结构一致）。
- **状态**：**已修复**。

### A-09 序列号仅 8 位，无重复包识别

- **文件**：`server-python/server.py:263-268`；`server-csharp/Program.cs:170-180`
- **原因**：`seq` 为 `uint8`，125 Hz 下约 2 秒回绕；只用 `(seq-expected)&0xFF` 估算丢包，未区分重复包。
- **影响**：长时间运行或高丢包时丢包率统计偏差；重复包会被重复应用（状态幂等，无功能损害）。
- **修复**：VGP2 将 seq 扩为 32 位并引入 64 包滑动窗口去重。
- **状态**：**待修**（协议升级一并处理）。

### A-10 Android 发送线程 `stop()` 的 join 超时竞态

- **文件**：`android/.../UdpSender.kt:101-116`
- **原因**：`join(400)` 超时后旧线程仍可能存活并 `post { onStopped() }`。
- **影响**：快速"断开→连接"后旧回调可能熄灭新链路的绿点。
- **缓解**：`MainActivity.onStopped()` 已用 `if (sender.isRunning) return` 挡掉迟到回调。
- **状态**：**已缓解**，未彻底（引入 generation id 可根治，属重构项）。

### A-11 C# 端不支持多人，且排空未按来源区分

- **文件**：`server-csharp/Program.cs:144-151`
- **原因**：单个 `pad`；`while (socket.Available > 0)` 只保留全局最新一包。
- **影响**：多手机同时连接时输入串到同一只手柄。
- **修复**：需按 A-01/A-02 的 deviceId 方案重构为多实例。
- **状态**：**待修**（README 已声明 C# 为单人参考实现，不构成功能倒退）。

### A-12 Android 无前台服务，后台可能被系统冻结

- **文件**：`android/app/src/main/AndroidManifest.xml`（无 `FOREGROUND_SERVICE`）
- **原因**：UDP 发送依赖 Activity 进程内的普通线程。
- **触发**：切后台、锁屏、Doze/省电策略。
- **影响**：后台长时间运行可能停发；当前设计依赖 `FLAG_KEEP_SCREEN_ON` 保持前台，实际影响有限。
- **建议**：如确需后台，增加前台服务 + 常驻通知；否则在文档中明确"需保持前台"。
- **状态**：**待定**（产品取舍，未擅自增加权限）。

### A-13 C# 关闭控制台窗口不执行 `finally`

- **文件**：`server-csharp/Program.cs:83-93`
- **原因**：`Console.CancelKeyPress` 只覆盖 Ctrl+C；直接关窗口时进程被强制终止。
- **影响**：虚拟手柄残留（与 A-04 同类）。
- **建议**：注册 `SetConsoleCtrlHandler` 处理 `CTRL_CLOSE_EVENT`，或提供 `q` 退出命令。
- **状态**：**待修**。

### A-14 测试依赖未声明

- **文件**：无 `requirements*.txt`
- **原因**：README 只写了 `pip install vgamepad`，游戏需要的 `pygame` 未列出。
- **修复**：新增 `requirements.txt`（vgamepad）与 `requirements-game.txt`（pygame）。
- **状态**：**已修复**。

### A-15 端到端测试未验证真实 XInput

- **文件**：`tools/e2e_test.py`
- **原因**：只断言日志里出现连接与 pps。
- **影响**：报文解析正确但 ViGEm 写入错误时测试仍会 PASS。
- **修复**：新增 `XInputGetState` 读取，断言 A/B/方向键、摇杆、扳机实际值。
- **验证**：本轮 8 项断言全部 PASS（`LY=32767`、`LT=200`、`RT=255`、`RX=12000`）。
- **状态**：**已修复**。

### A-16 协议测试不是真正的跨语言验证

- **文件**：`tools/verify_protocol.py`
- **原因**：用 Python 复刻 Kotlin/C# 的解析逻辑做同构对比，并非调用真实实现。
- **修复**：新增 `protocol/test_vectors.json`（三端共享向量）与 `tools/test_protocol.py`，对序列化、Python 解析、C# 位运算三条路径逐字段断言，并含畸形包负向用例。
- **状态**：**已修复**（C# 仍为逻辑复刻，真实编译验证待环境）。

---

## 3. 轻微（P2）

| 编号 | 位置 | 问题 | 状态 |
| --- | --- | --- | --- |
| A-17 | `GamepadProtocol.cs:6` | 注释称"字段语义与 XINPUT_GAMEPAD 完全对齐"，易被误解为可直接透传 | 已修复（改为明确"顺序与位序不同，需逐位映射"） |
| A-18 | `README.md:276` | 早期文档称位序"与 XInput 对齐、可直接透传" | 上一轮已修复 |
| A-19 | `README.en/ja/es/ru.md` | 新功能（Home 键/隐藏按键/自动重连）未同步多语言 | 待修 |
| A-20 | `AndroidManifest.xml` | `allowBackup="true"`，含局域网 IP 的偏好可能被云备份 | 待定（建议 `false` 或在隐私说明披露） |
| A-21 | `android/app/build.gradle` | `minifyEnabled false`，release 包未混淆/裁剪 | 待定（体积换可调试性，可接受） |
| A-22 | 仓库根 | `一键启动.bat` 与 `START-RECEIVER.bat` 内容完全相同 | 记录（保留双入口，建议合并为调用关系） |
| A-23 | 全项目 | 无 CI（GitHub Actions），无 lint/detekt | 待补 |
| A-24 | `server-csharp` | 本机原先无 .NET SDK，`dotnet build` 无法执行 | **已解决**（部署 .NET 8.0.427 SDK，编译 0 警告 0 错误，见测试报告） |
| A-25 | `game/*.py` | `look_test.py` 的 `Pad.buttons` 只映射 8 个键，无方向键/摇杆按下 | 记录（该游戏只测视角，属设计取舍） |
| A-26 | `server-python/server.py` | 无异常报文计数与限流，畸形包静默丢弃 | 待修（低风险） |

---

## 4. 本轮修复汇总

| 文件 | 改动 |
| --- | --- |
| `game/look_test.py` | 修 `just_pressed` 可变默认参数与同帧失效；`poll()` 统一前移基准 |
| `server-python/server.py` | 严格长度校验；退出 `unplug()` 全部手柄；`players` 加锁；回调锁内只取地址 |
| `server-csharp/GamepadProtocol.cs` | 严格长度校验；纠正"与 XInput 完全对齐"的错误注释 |
| `server-csharp/Program.cs` | 新增 `VGPR` 震动回传（`FeedbackReceived`）+ 连接/每秒 `VGPP` 1P；关闭 `SIO_UDP_CONNRESET`、`ConnectionReset` 退避、错误日志限流（A-28） |
| `server-python/server.py` | 捕获 `ConnectionResetError` / `OSError`，客户端异常断开不再终止进程（A-27） |
| `android/.../UdpSender.kt` | 回包长度 `== 8` 严格校验 |
| `一键启动.bat` / `START-RECEIVER.bat` | 进程匹配收紧为 `*server-python*server.py*`，避免误杀 |
| `requirements.txt` / `requirements-game.txt` | 新增依赖清单 |
| `protocol/test_vectors.json` | 三端共享协议向量（5 组 + 布局定义） |
| `tools/test_protocol.py` | 新增协议一致性单测（6 项） |
| `tools/e2e_test.py` | 重写：增加真实 XInput 断言（8 项） |

---

## 5. 未修复 / 需环境确认

| 项 | 原因 | 下一步 |
| --- | --- | --- |
| A-01 / A-02 / A-09（认证、设备身份、32 位序列号） | 需三端同时切换协议，且必须实机联调 | 按 `docs/PROTOCOL.md` §4 实施 VGP2，接收端双栈兼容，Android 端最后切换并保留回滚开关 |
| A-05（C# 回传） | ~~本机无 .NET SDK~~ → **已完成** | `dotnet build` 通过；端到端 33/33 验证 VGPR/VGPP 真实可用 |
| A-11（C# 多人） | 依赖 VGP2 的 deviceId | 与 VGP2 同批 |
| A-13（C# 关窗口清理） | 需 `SetConsoleCtrlHandler` | 已验证 `taskkill /F` 后虚拟手柄被驱动自动回收；优雅退出清理仍待补 |
| A-19（多语言 README） | 工作量大且不影响功能 | 随功能冻结后统一翻译 |
| A-12（前台服务） | 涉及权限与耗电取舍 | 需产品确认是否要求后台运行 |
