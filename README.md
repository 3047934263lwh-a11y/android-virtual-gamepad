# 虚拟 Xbox 360 手柄（Android → Windows）

**语言 / Language**：[简体中文](README.md) · [English](README.en.md) · [日本語](README.ja.md) · [Español](README.es.md) · [Русский](README.ru.md)

![License](https://img.shields.io/badge/license-MIT-green) ![Platform](https://img.shields.io/badge/platform-Windows%20%2B%20Android-blue) ![Protocol](https://img.shields.io/badge/transport-UDP%20125Hz-orange)

> **只想尽快用起来？** 先看 [`QUICKSTART.md`](QUICKSTART.md)（按顺序点选的清单）。
> 本文档讲原理、协议、映射与兼容性边界。

用安卓手机作为虚拟 Xbox 360 手柄，通过局域网 UDP 把摇杆、方向键、ABXY、肩键、扳机
实时映射到 PC。PC 端借助 **ViGEmBus** 内核驱动创建虚拟 XInput 设备，游戏在系统层面
看到的就是一台**真实的 Xbox 360 控制器**——不注入进程、不替换 DLL、不做 API Hook。

---

## 1. 功能特性

| 功能 | 说明 |
| --- | --- |
| 真实 Xbox 360 手柄 | 内核级虚拟设备，XInput 游戏原生识别，无需任何游戏内配置 |
| **本地多人（1P–4P）** | 多台手机连同一台电脑，每台自动分配独立手柄与玩家编号，震动按手柄回传到对应手机 |
| **陀螺仪瞄准** | 转动手机 = 转动视角（融合姿态解算，无累计漂移）；与右摇杆**叠加生效**，俯仰/左右反向可开关 |
| **双路视角灵敏度** | 「陀螺仪视角灵敏度」与「右摇杆视角灵敏度」是两个**完全独立**的设置（各 0–100），分别调节、互不影响 |
| 震动回传 | 游戏的 `XInputSetState` 马达数据原路回传手机振动 |
| **☰ 菜单键** | 独立圆形菜单键（发出 `START` 信号，即游戏里的 Menu）；界面上不再另设 START 键 |
| **Home / Xbox 键** | 顶部正中的圆形 `X` 键，发出 `GUIDE`（bit14）信号；「按手柄中间大圆键加入游戏」类游戏用它 |
| **摇杆按下（LS / RS）** | 设置里可选三种触发方式：① 轻点摇杆中心 ② 双击摇杆内任意位置 ③ 只要操作摇杆就持续按下；推动摇杆做拖动时不会误触发 |
| 逐键自定义 | 每个控件可独立调整**位置 / 大小 / 透明度 / 显示或隐藏**，自动保存；隐藏后不绘制也不响应触摸 |
| 暗色玻璃 UI | 黑曜石多层玻璃质感界面，ABXY 保留彩色高对比字母，按压下沉反馈与入场动效 |
| 断线自动重连 | 启动时自动连接上次服务器；运行中 Wi-Fi 闪断、PC 接收端重启也会按退避自动连回来（手动点「断开」则不再自动连） |
| **顶部栏可折叠** | 连接栏默认收起为原位置的「手柄」文字；点击它向右展开（300ms 缓动），再点收回，收起时只占一个文字宽度、不拦截任何游戏触控 |
| 一键启动 | PC 端脚本自动完成依赖检查、残留实例清理、端口检查并启动接收端 |
| 配套测试游戏 | 4 款专为测试手柄编写的 PC 游戏（横版跳跃 / 双摇杆射击 / 视角测试 / 3D 漫步） |

---

## 2. 结论先行

| 问题 | 答案 |
| --- | --- |
| 游戏能否识别为标准 Xbox 360 手柄 | **能**。ViGEmBus 是内核态总线驱动，向 Windows PnP 注册虚拟设备，XInput 枚举结果与实体手柄无差异 |
| 覆盖面 | 所有走 **XInput** 的游戏/模拟器/引擎（当前 PC 手柄游戏的主流路径） |
| 覆盖不到的情况 | 仅支持 **DirectInput** 的老游戏；部分限制虚拟输入设备的强反作弊产品；非 Windows 平台 |
| 通信方式 | Wi-Fi UDP 单播，20 字节定长报文，125 Hz |
| 典型端到端延迟 | 5 GHz 同路由下 8–20 ms 量级；2.4 GHz 或拥塞网络明显更差 |
| 同时支持几台手机 | 最多 **4 台**（XInput 槽位上限），按来源 IP 自动分配 1P–4P |

---

## 3. 为什么能"100% 识别"

这是整个方案的技术核心，必须理解清楚，否则排查问题时容易走错方向。

Windows 上游戏读取手柄有两条互不相通的路径：

1. **XInput**（`xinput1_4.dll`）——Xbox 360 手柄的标准接口。2006 年以后几乎所有
   支持手柄的 PC 游戏都走这条路。它只认"Xbox 360 兼容设备"。
2. **DirectInput**（`dinput8.dll`）——更老的通用接口，认得任何 HID 游戏设备。

普通用户态程序**无法**凭空向系统声明"这里插入了一个 USB 手柄"。这就是为什么
单纯的"按键映射软件"通常只能做到模拟键盘鼠标，而做不出真正的虚拟手柄。

ViGEmBus 解决的就是这一层：它是一个 **KMDF 内核态总线驱动**，注册了一条虚拟总线。
用户态程序通过 IOCTL 请求它"在这条总线上插入一台 Xbox 360 控制器"，驱动随即让
Windows PnP 管理器完成设备枚举，分配 XInput 槽位。于是：

- `XInputGetState(0, ...)` 返回 `ERROR_SUCCESS`，设备类型为 `XINPUT_DEVCTYPE_GAMEPAD`；
- 设备管理器里出现 `Xbox 360 Controller for Windows`；
- 游戏拿到的 `XINPUT_STATE` 与实体手柄完全同构，包括 16 位摇杆精度和 8 位扳机行程。

**关键推论：不存在"游戏能分辨出这是虚拟设备"的技术路径**，因为在内核对象层面它和
实体手柄没有区别。所谓"识别不了"，99% 是驱动没装、设备没 Connect、或游戏本身
只走 DirectInput。

> ViGEmBus 状态说明：项目于 2023-11-02 归档，最终版本 **1.22.0**，不再更新。
> 这不是问题——它是个成熟稳定的单一职责驱动，在 Windows 10/11（含 24H2）上正常工作。

---

## 4. 系统架构

```
┌──────────────────────────┐        Wi-Fi / UDP         ┌────────────────────────────┐
│  Android 手机 (1P–4P)     │      20 B @ 125 Hz        │  Windows PC                │
│                          │  ───────────────────────► │                            │
│  GamepadView (自绘 UI)    │                           │  server.py（接收端）        │
│    ├ 左/右摇杆  模拟量      │   ◄───────────────────── │    ├ UDP 接收 + 排空取最新   │
│    ├ 方向键     4 向       │    8 B 回传报文            │    ├ 按来源 IP 分配 1P–4P    │
│    ├ ABXY      数字量      │    （震动/玩家编号）        │    └ vgamepad → ViGEm      │
│    ├ LT/RT     0–255 模拟  │                           │           │ IOCTL          │
│    └ START/BACK           │                           │  ┌──────────────────────┐  │
│  GyroAim (陀螺仪→右摇杆)   │                           │  │ ViGEmBus 内核驱动     │  │
│  UdpSender (125 Hz 线程)   │                           │  │ 虚拟 Xbox 360 设备×4  │  │
└──────────────────────────┘                           │  └──────────┬───────────┘  │
                                                       │             │ XInput       │
                                                       │             ▼              │
                                                       │        游戏 / 模拟器        │
                                                       └────────────────────────────┘
```

数据流是**单向绝对状态**：每一帧都发送完整状态，而不是增量。因此丢包不会累积错误，
下一帧自动纠正。这也是选 UDP 而非 TCP 的根本原因。

接收端对 UDP 队列做**排空取最新**处理：一次性读空内核队列、只应用每台手机最新的
一包——处理延迟恒定在毫秒级，不会因积压产生"漂移惯性"。

---

## 5. 目录结构

```
virtual-gamepad/
├─ README.md                      本文档（简体中文）
├─ README.en.md / .ja.md / .es.md / .ru.md   多语言说明
├─ QUICKSTART.md                  傻瓜式操作清单（先看这个）
├─ LICENSE                        MIT 协议
├─ 一键启动.bat                    PC 端一键启动（依赖检查/清理/启动，英文提示）
├─ START-RECEIVER.bat             同上（英文文件名版）
├─ 启动PC接收端.bat                极简启动（要求已装好 Python + vgamepad）
├─ android/                       Android Studio 工程（Kotlin，minSdk 24）
│  ├─ app/build.gradle
│  └─ app/src/main/
│     ├─ AndroidManifest.xml
│     ├─ res/layout/activity_main.xml
│     └─ java/com/vgamepad/
│        ├─ MainActivity.kt       连接/设置界面 + 生命周期 + 自动重连
│        ├─ GamepadView.kt        自绘手柄（暗色玻璃 UI）+ 多点触控 + 逐键编辑
│        ├─ GamepadState.kt       状态容器 + 按键位定义
│        ├─ UdpSender.kt          20 字节序列化 + 125 Hz 发送线程 + 回传接收线程
│        └─ GyroAim.kt            陀螺仪瞄准（融合姿态 → 右摇杆等效值）
├─ server-python/                 PC 接收端（Python + vgamepad，功能最全，推荐）
│  ├─ server.py                   多人版：1P–4P 分配 / 排空取最新 / 震动路由
│  └─ requirements.txt
├─ server-csharp/                 PC 接收端（C# / .NET 8，单人参考实现）
│  ├─ VirtualGamepadServer.csproj
│  ├─ GamepadProtocol.cs
│  └─ Program.cs
├─ game/                          配套测试游戏（输入直读 XInput，见 §13）
└─ tools/
   ├─ verify_protocol.py          三端字节布局交叉自检
   └─ e2e_test.py                 无手机时的 PC 链路端到端自检
```

---

## 6. 快速开始

### 6.1 一键启动（推荐）

```bat
一键启动.bat        （或 START-RECEIVER.bat，内容相同）
```

脚本会依次完成：定位接收端 → 检查 Python 与 vgamepad 库（缺失自动 pip 安装）→
检查 ViGEmBus 驱动 → 清理残留的旧接收端实例 → 确认端口 26760 可用 → 启动接收端。
任何一步失败都会给出具体的中文/英文提示，不会闪退。

窗口内出现 `[1P] 手机 192.168.x.x:端口 已连接 → 虚拟手柄 1` 与 `[1P] 125pps`
即代表链路就绪（`[连接]` 是 C# 参考版的日志，Python 版不输出）。
**注意：关闭该窗口 = 虚拟手柄从系统消失**，游戏会立刻失去手柄。

### 6.2 手动启动（等价于脚本最后一步）

```bash
cd server-python
pip install -r requirements.txt    # vgamepad（首次）
python server.py                   # 默认端口 26760
```

### 6.3 C# 版接收端（单人参考实现）

需要 [.NET 8 SDK](https://dotnet.microsoft.com/download)：

```bash
cd server-csharp
dotnet run -c Release
```

> 说明：C# 版是早期的**单人参考实现**，只做单向接收与排空取最新，**没有**震动回传
> （`VGPR`）、玩家编号（`VGPP`）或多人分配；这些能力只在 Python 版中。
> 日常使用请选 Python 版（`一键启动.bat` 启动的就是它）。

### 6.4 编译 Android 端

**方式 A：Android Studio（推荐）**

1. `File → Open`，选择 `android/` 目录。
2. 等待 Gradle Sync（首次会下载 AGP 8.7.3 与 Gradle 8.9）。
3. `Build → Build Bundle(s)/APK(s) → Build APK(s)`。
4. 产物：`android/app/build/outputs/apk/debug/app-debug.apk`，传到手机安装。

**方式 B：命令行**

```bash
cd android
gradle wrapper --gradle-version 8.9   # 首次生成 wrapper（需本机已装 Gradle）
./gradlew assembleDebug
```

> 仓库内只提供 `gradle-wrapper.properties`，未包含 `gradle-wrapper.jar`（二进制）。
> Android Studio 打开工程时会自动补全；命令行方式需先执行上面的 `gradle wrapper`。

### 6.5 联调

1. 手机与 PC 连**同一个** Wi-Fi（或手机开热点、PC 连热点，延迟更低且无 AP 隔离）。
2. PC 上执行 `ipconfig`，记下 IPv4 地址，例如 `192.168.1.20`。
3. 首次运行接收端时，Windows 防火墙会弹窗——**允许专用网络**。
   或手动放行（管理员 PowerShell）：
   ```powershell
   New-NetFirewallRule -DisplayName "VirtualGamepad UDP 26760" `
     -Direction Inbound -Protocol UDP -LocalPort 26760 -Action Allow
   ```
4. 手机打开 App → 点顶部左侧的**「手柄」文字**展开连接栏 → 输入 PC 的 IP 与端口 `26760` → 点「连接」
   （之后 App 会**自动重连**，无需再点；再点「手柄」文字即可收起）。
5. 状态栏出现 `已连接 · 12x pps · 1P`（多人时显示编号），PC 控制台出现 `[1P] ... 已连接`。
6. 打开 `joy.cpl` → 属性，拨动摇杆、按 A/B/X/Y，测试面板应有实时反馈。
7. 启动游戏。

**多人模式**：每台手机装同一个 App，连到同一台电脑即可——先连接的是 1P，之后依次
2P、3P、4P（上限 4 台，XInput 槽位限制）。每台手机的状态栏会显示自己的玩家编号，
接收端控制台逐玩家显示 `[1P] 125pps 0.8%  [2P] 118pps 1.6%`。断线重连保持原编号。

---

## 7. 通信方式与协议

### 7.1 为什么是 UDP

| 备选 | 延迟 | 实现复杂度 | 结论 |
| --- | --- | --- | --- |
| **Wi-Fi UDP** | 低，无重传无队头阻塞 | 低 | **采用** |
| Wi-Fi TCP | 高，Nagle + 拥塞控制带来抖动 | 低 | 不适合实时输入 |
| USB（ADB forward） | 最低 | 中，需开发者模式 | 可作增强项 |
| 蓝牙 HID | 中高 | 高，需实现 HID over GATT | 不划算 |

报文承载的是**绝对状态**，天然幂等：丢一包只损失约 8 ms 的更新，不产生累积误差，
因此不需要重传、不需要序号重排。序号仅用于统计丢包率。

### 7.2 输入报文（20 字节定长，小端）

| 偏移 | 长度 | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | 4 | magic | ASCII `VGP1`（0x56 0x47 0x50 0x31） |
| 4 | 1 | version | 固定 `1` |
| 5 | 2 | buttons | uint16 位掩码，见 7.3 |
| 7 | 1 | sequence | 0–255 循环，丢包统计用 |
| 8 | 2 | LX | int16，-32768…32767 |
| 10 | 2 | LY | int16，-32768…32767（上为正） |
| 12 | 2 | RX | int16 |
| 14 | 2 | RY | int16 |
| 16 | 1 | LT | uint8，0…255 |
| 17 | 1 | RT | uint8，0…255 |
| 18 | 2 | reserved | 置 0 |

带宽：20 B × 125 Hz ≈ **2.5 KB/s**，可忽略。

三端字节布局的一致性由 `tools/verify_protocol.py` 交叉校验（模拟 Kotlin 写入 →
C# 解析 → Python 解析，并覆盖魔数/版本/长度三类非法输入）：

```bash
python tools/verify_protocol.py     # 结果应为「全部通过」
```

修改协议后请先跑这个脚本，再动客户端与服务端。

### 7.3 按键位定义

| 位 | 含义 | 位 | 含义 |
| --- | --- | --- | --- |
| 0 | A | 8 | L3（左摇杆按下） |
| 1 | B | 9 | R3（右摇杆按下） |
| 2 | X | 10 | 方向键 ↑ |
| 3 | Y | 11 | 方向键 ↓ |
| 4 | LB | 12 | 方向键 ← |
| 5 | RB | 13 | 方向键 → |
| 6 | BACK | 14 | GUIDE（Xbox 键） |
| 7 | START | 15 | 保留 |

这是项目自定义的紧凑位序，**不是** `XINPUT_GAMEPAD.wButtons` 的位序（例如本项目 `A` 是 bit0，
XInput 里是 `0x1000`；本项目 `UP` 是 bit10，XInput 里是 `0x0001`）。接收端必须按
`BTN_MAP` / `SetButtonState` **逐位映射**，不能把这 16 位直接写进 `wButtons`。

### 7.4 反向通道（震动回传 + 玩家编号，8 字节定长）

游戏调用 `XInputSetState` 后，驱动把左右马达强度回传给接收端；接收端再用 UDP
发回手机。手机端优先绑定 IPv4 端口 26761（强制 `0.0.0.0`，规避部分机型 IPv6 双栈
丢弃 IPv4 单播的问题）；若该端口被占用会回退到随机端口。**PC 端一律回发到收到报文的
来源端口**，不硬编码 26761，所以回退后链路依然可用：

| 偏移 | 长度 | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | 4 | magic | `VGPR`（震动）或 `VGPP`（玩家编号） |
| 4 | 1 | version | 固定 `1` |
| 5 | 1 | payload | VGPR：大马达 0–255；VGPP：玩家编号 1–4 |
| 6 | 1 | payload2 | VGPR：小马达 0–255；VGPP：0 |
| 7 | 1 | reserved | 置 0 |

手机端把 `max(large, small)` 映射为 `VibrationEffect.createOneShot(70ms, amplitude)`，
仅在强度变化或超过 100ms 时起振，避免高频事件刷成连续噪音。

实现位置：**仅 Python 端** `pad.register_notification()`。C# 参考实现没有回传通道。

### 7.5 多人分配规则

- 每个来源 IP 分配一个空闲槽位（1P–4P），短暂断线重连保持原编号；
- 离线超过 `SLOT_TTL_SEC`（默认 300 秒）后回收槽位，让给新设备；回收后该 IP 再连会被分配新编号；
- 4 个槽位都被占用时，第 5 台设备被拒绝并提示满员；
- 每个玩家**独立**执行：报文排空、空闲 1.2 秒回中、丢包统计、震动路由；
- 玩家编号通过 `VGPP` 报文周期性下发（每秒一次，迟到者也能收到）。

### 7.6 陀螺仪瞄准（手机端本地换算）

陀螺仪数据在手机端直接换算成"右摇杆等效值"，与触屏右摇杆**叠加**后发送——
PC 端、接收端、游戏全都不用改：

- 用融合姿态传感器（`TYPE_GAME_ROTATION_VECTOR`）取屏幕法线方向，相邻两帧
  方向角差 ÷ 时间差 = 转动角速度；
- 角速度 ÷ 游戏最大转速（水平 150°/s、俯仰 95°/s）= 右摇杆等效值（物理 1:1）；
- 无累计漂移（融合姿态）、3°/s 死区滤手抖、约 20ms 轻滤波；
- 设置面板提供：总开关、**陀螺仪视角灵敏度**（0–100，65 = 标准 1.0×）与
  **右摇杆视角灵敏度**（0–100，50 = 标准 1.0×）（两项独立）、俯仰反向、左右反向。
  设备不支持姿态传感器时，这几项会自动置灰，避免调到无效值。

---

## 8. 按键映射表（完整对照）

手机 UI 控件与 XInput 字段的逐项对应，三端（Kotlin / C# / Python）字节布局完全一致：

| 手机控件 | XInput 字段 | 类型与范围 | 映射规则 |
| --- | --- | --- | --- |
| 左摇杆 | `sThumbLX` | int16 −32768…32767 | 触点相对圆心归一化，半径线性映射到满量程 |
| 左摇杆 | `sThumbLY` | int16 −32768…32767 | **屏幕 Y 轴向下为正，必须取反** |
| 右摇杆 | `sThumbRX` / `sThumbRY` | int16 | 同上；陀螺仪开启时与陀螺仪值叠加 |
| 摇杆按下 | `L3` / `R3` | 数字 | 三种模式（设置→摇杆按下触发方式）：轻点中心（180ms 脉冲，位移<8dp 且未推离中心 30% 半径）/ 双击区域内任意位置（400ms 内两次，**第二次按下即触发**，容差 14dp）/ 操作摇杆即持续按住 |
| 方向键 | `wButtons` 的 UP/DOWN/LEFT/RIGHT | 数字 ×4 | XInput 中方向键是 4 个独立按键位，**不是轴** |
| A / B / X / Y | `wButtons` 位 0–3 | 数字 | — |
| LB / RB | `bLeftShoulder` / `bRightShoulder` | 数字 | — |
| LT / RT | `bLeftTrigger` / `bRightTrigger` | uint8 **0…255** | **点一下即 255（踩到底）**；按住后上下拖动超过 8dp 切换为按纵向位置连续调节，仍可半按 |
| ☰ 菜单键 | `wButtons` 位 7（START） | 数字 | 界面上唯一的 Menu 键，不再另设独立 START 键 |
| BACK | `wButtons` 位 6 | 数字 | — |
| Home / Xbox 键 | `wButtons` 位 14（GUIDE） | 数字 | 顶部正中的圆形 `X` 键，用于「按中间大圆键加入」的游戏 |

以上每个控件都可在 **设置 → 隐藏按键** 里单独勾选隐藏；隐藏后不绘制、不响应触摸、
不参与布局编辑，偏好键 `hidden_keys` 自动保存。

**三个必须注意的细节：**

1. **Y 轴方向相反**。Android 的屏幕坐标 Y 向下为正，XInput 的 `sThumbLY` 向上为正。
   漏掉这一步的症状是"上下颠倒"。
2. **扳机松开是 0，不是 −128**。XInput 扳机是无符号 8 位。
   若误用 0x80 作为中点，游戏里会看到扳机常驻半按。
3. **方向键不是轴**。很多映射实现错误地把它当 POV hat 处理，导致 XInput 下完全无效。

**摇杆死区**：手机端内置 6% 径向死区，并对死区外区间做重新归一化
（`mag = (raw - 0.06) / 0.94`），保证满量程仍然到 32767，不会因死区损失行程。

---

## 9. 确保被游戏完整识别的关键步骤（检查清单）

按顺序执行，任一项不满足都会导致"游戏里没反应"：

- [ ] **1. ViGEmBus 已安装且已重启。** 未重启时驱动服务不会加载，客户端构造会抛异常。
- [ ] **2. 设备管理器可见 `Virtual Gamepad Emulation Bus`。** 不可见说明安装失败或被安全软件拦截。
- [ ] **3. 接收端先启动，游戏后启动。** 多数引擎在初始化时枚举一次 XInput 设备；
      先进游戏再插手柄，可能需要 Alt+Tab 或重启游戏才生效。
- [ ] **4. `joy.cpl` 能看到 `Xbox 360 Controller for Windows`。** 这是"设备确实存在"的铁证。
      看不到就不要再怀疑游戏，回到第 1–3 步。
- [ ] **5. 防火墙放行 UDP 入站。** 手机端显示"正在连接"但 PC 控制台无 `[1P] ... 已连接` 日志，
      基本就是被防火墙丢弃。
- [ ] **6. 手机与 PC 处于同一子网。** 路由器开启 AP 隔离（客户端隔离）时会静默阻断。
- [ ] **7. 拔掉实体手柄。** XInput 只有 4 个槽位，部分游戏只读索引 0。
      实体手柄占住槽位 0 时，虚拟手柄会落到槽位 1 而被忽略。
- [ ] **8. 关闭 Steam Input 对该游戏的强制映射。** Steam 库 → 右键游戏 → 属性 →
      控制器 → 改为"禁用 Steam 输入"，避免双重映射或设备被 Steam 接管。
- [ ] **9. 关闭其他虚拟手柄软件。** DS4Windows / x360ce / vJoy 同时运行会争抢槽位，
      产生双输入或设备冲突。

---

## 10. 兼容性与注意事项

### 10.1 识别情况矩阵

| 目标 | 结果 | 说明 |
| --- | --- | --- |
| 使用 XInput 的现代游戏（UE / Unity / RE Engine 等） | ✅ 原生识别 | 系统层面即为 Xbox 360 控制器 |
| Steam / Epic / Xbox App 游戏 | ✅ | 建议关闭 Steam Input 强制映射 |
| 模拟器（RPCS3、Yuzu、Ryujinx、RetroArch） | ✅ | 直接选 XInput 设备即可 |
| 仅支持 DirectInput 的老游戏（2005 年前后） | ❌ 不识别 | 虚拟设备是 XInput-only；此类需 vJoy（DirectInput）+ x360ce 路线 |
| 强反作弊竞技网游 | ⚠️ 视策略 | 见 10.4 |
| macOS / Linux 主机 | ❌ | ViGEmBus 仅 Windows；Linux 可换 `uinput` + `xpad`，macOS 需 DriverKit |

### 10.2 延迟构成

| 环节 | 典型值 |
| --- | --- |
| 触摸采样（Android 触控面板） | 8–16 ms |
| 125 Hz 发送量化 | 0–8 ms |
| Wi-Fi 单程（5 GHz 同路由） | 2–6 ms |
| PC 处理 + SubmitReport | < 1 ms |
| 游戏帧同步 | 1 帧（16.7 ms @60fps） |

合计约 **35–50 ms**（含触控采样与一帧渲染）。这是 Wi-Fi 方案的物理下限，
不适合音游、格斗游戏的精确连段等场景；对动作、竞速、RPG、模拟器足够。

想进一步降低：手机开热点直连 PC（去掉路由器转发）、改用 5 GHz 频段、
或实现 USB/ADB 传输通道（可省去 Wi-Fi 的 2–6 ms 与抖动）。

### 10.3 其他注意事项

- **屏幕常亮**：已通过 `FLAG_KEEP_SCREEN_ON` 处理，避免息屏导致发送线程被挂起。
- **切后台自动回中**：`onPause` 中调用 `releaseAll()`，防止角色持续移动；陀螺仪同步冻结。
- **空闲回中保护**：PC 端每玩家 1.2 秒未收到报文即写入中立状态，避免断连后按键卡死。
- **全屏沉浸**：隐藏状态栏与导航栏，避免误触；横屏锁定为 `sensorLandscape`。
- **电池**：125 Hz 持续发送会让 Wi-Fi 保持活跃，长时间使用建议插电。

### 10.4 安全与合规（重要）

- ViGEmBus 是**签名内核驱动**，运行在最高权限层。只从官方 GitHub Releases 安装，
  不要使用任何"整合包""绿色版"。
- 虚拟输入设备在多数单机/合作游戏中完全合规。但**部分竞技类网游的反作弊策略
  可能限制或禁止虚拟手柄**，使用前请确认该游戏的用户协议。因使用本方案导致的
  账号处罚，需自行承担。
- 本项目仅在本机与局域网内传输，不经过任何第三方服务器，无隐私外泄面。

---

## 11. 手感与功能调校

| 想调什么 | 在哪调 |
| --- | --- |
| 陀螺仪开关 / 灵敏度 / 俯仰反向 / 左右反向 | App → 齿轮 → 右摇杆控制区（即时生效，偏好持久保存） |
| 右摇杆视角灵敏度 | App → 齿轮 → 右摇杆控制区（与陀螺仪灵敏度互不影响） |
| 摇杆按下（LS / RS）触发方式 | App → 齿轮 → 摇杆按下触发方式（三选一） |
| 隐藏任意按键 | App → 齿轮 → 隐藏按键（逐键勾选；「全部显示」一键恢复） |
| 控件位置 / 大小 / 透明度 | App → 齿轮 → 自定义按键布局：点选控件，拖动移位，滑杆切换"大小 ⇄ 透明度"；按系统返回键或「完成」退出 |
| 按键触感 / 游戏震动回传（各 3 个自定义值） | App → 齿轮 → 震动区 |
| 三项尺寸滑杆 / 恢复默认大小 | App → 齿轮 |
| 接收端端口 | `python server.py 26760`（默认；改端口时手机端顶部栏同步修改）。`26761` 是手机**回传**端口，不是接收端口 |
| 玩家槽位回收时间 | `server-python/server.py` 的 `SLOT_TTL_SEC`（默认 300 秒） |
| 陀螺仪等效转速 | `GyroAim.kt` 顶部 `MAX_YAW_RATE / MAX_PITCH_RATE` |
| 手机端摇杆死区 | `GamepadView.kt` 的 `DEADZONE` |

接收端控制台消息对照（**Python 版**）：`[监听] UDP 0.0.0.0:26760 等待手机端连接…`
= 正在等待手机；`[1P] 手机 x.x.x.x:端口 已连接 → 虚拟手柄 1` = 某手机已接入；
`[1P] 125pps 0.8%` = 各玩家每秒包数与丢包率；`[1P] 输入已回中（ip）`
= 手机停止发包；`[1P] 离线超过 300s，已释放槽位` = 槽位被回收。
`[连接]` 是 C# 参考版的日志格式，Python 版不会输出。

---

## 12. 故障排查

| 现象 | 定位 | 处理 |
| --- | --- | --- |
| 启动即报"无法加载 ViGEmBus 驱动" | 驱动未装/未重启 | 重装 1.22.0 并重启 |
| `joy.cpl` 里没有 Xbox 360 控制器 | 设备未 Connect | 看控制台是否打印就绪信息；被安全软件拦截时加白名单 |
| 手机显示已连接，PC 无日志 | 防火墙/AP 隔离 | 放行 UDP；关闭路由器客户端隔离 |
| `joy.cpl` 有反应，游戏内无反应 | 游戏走 DirectInput 或未切手柄模式 | 游戏设置里把输入设备改为"控制器"；老游戏见 10.1 |
| 摇杆上下颠倒 | Y 轴未取反 | 检查 `GamepadView.setStick()` 的 `-ny` |
| 陀螺仪俯仰/左右反了 | 体感约定差异 | App 设置里拨动「俯仰反向」/「左右反向」开关 |
| 扳机常驻半按 | 用了 0x80 作中点 | 松开必须是 `0` |
| 方向键无效 | 当成 POV hat 处理 | XInput 下必须写 4 个按键位 |
| 摇杆漂移 | 死区过小/触控抖动 | 调大 `GamepadView.DEADZONE` |
| 双击输入（一次按键触发两次） | 多个虚拟手柄软件并存 | 只保留一个；关闭 DS4Windows/x360ce |
| 延迟忽大忽小 | 2.4 GHz 拥塞 | 换 5 GHz；或手机热点直连 |
| 第二个接收端秒退 | 端口被旧实例占用 | 用一键启动脚本（自动清理），或手动结束旧 python 进程 |

---

## 13. 配套测试游戏（`game/`）

全部使用 **ctypes 直读 XInput**（与真实游戏同一条 API 路径，pygame 只负责渲染），
无手柄时自动回落到键盘；每个游戏都带 `--selftest` 无头自检。

| 游戏 | 启动 | 测试点 |
| --- | --- | --- |
| `xbox_platformer.py` 横版跳跃 | `game/启动测试游戏.bat` | 摇杆模拟速度 + 上推蓄力跳跃、A/X/B/Y、RT/LT 扳机模拟量、BACK 输入监测 HUD、3 关卡与通关统计 |
| `twin_stick.py` 双摇杆竞技场 | `game/启动双摇杆射击.bat` | 左摇杆移动 + 右摇杆瞄准/连发**同时独立使用**、波次敌人、炸弹、双摇杆同用时长统计 |
| `look_test.py` 右摇杆视角 | `game/启动视角测试.bat` | 航向/俯仰**逐帧直映**（推多少转多少、松手即停）、罗盘刻度带、`LookCamera` 类可直接移植 |
| `fps_walk.py` 3D 自由漫步 | `game/启动3D漫步.bat` | 双摇杆 FPS 配合（行走 + 视角）、RT 疾跑、导航环目标、右上角小地图、`VGP_SHOT_DIR` 自动存帧 |

各游戏的按键说明见启动后的画面提示；`--selftest` 用于无显示环境回归验证。

> 已知坑：pygame 的 `SysFont` 在部分机器枚举注册表字体会崩溃，本项目改为直接加载
> `C:/Windows/Fonts/msyh.ttc`。XInput 的 `sThumbLY` 上推为正，**不要**再按屏幕坐标取反。

---

## 14. 可扩展方向

1. **USB 通道**：`adb forward tcp:26760 tcp:26760` 后把 UDP 换成 TCP 即可复用协议，
   延迟再降一个档次。
2. **局域网自动发现**：PC 端周期性广播心跳，手机监听后自动填充 IP，省去手输
   （换电脑/换 Wi-Fi 目前需要在 App 里改一次 IP）。
3. **DirectInput 支持**：为仅支持 DirectInput 的老游戏提供 vJoy 后端。
4. **独立 EXE 便携版**：`pip install pyinstaller` 后
   `pyinstaller --onefile --collect-all vgamepad server.py`
   即可产出免装 Python 的单文件接收端（本项目日常即用此方式分发）。

---

## 15. 许可与致谢

- 本项目以 [MIT](LICENSE) 协议开源。
- 第三方依赖（各自遵循其原始协议，分发时请保留相应声明）：
  - [ViGEmBus](https://github.com/nefarius/ViGEmBus) 驱动 —— BSD-3-Clause
  - [Nefarius.ViGEm.Client](https://www.nuget.org/packages/Nefarius.ViGEm.Client)（C# 客户端）—— MIT
  - [vgamepad](https://pypi.org/project/vgamepad/)（Python ViGEm 客户端）—— MIT
  - [pygame](https://www.pygame.org/)（测试游戏渲染）—— LGPL
  - Material Components for Android / Kotlin / AndroidX —— Apache-2.0
