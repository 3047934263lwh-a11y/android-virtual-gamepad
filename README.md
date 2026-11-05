# 🎮 Android Virtual Gamepad

**语言 / Language**：[简体中文](README.md) · [English](README.en.md) · [日本語](README.ja.md) · [Español](README.es.md) · [Русский](README.ru.md)

## 把你的安卓手机，变成一只真正的 Xbox 360 无线手柄

无需购买实体手柄，通过 Wi-Fi 即可将安卓手机连接到 Windows 电脑，让游戏直接识别为标准 Xbox 360 控制器。

**核心功能**

- 🎮 **原生手柄模拟**：兼容 Windows XInput 游戏。
- 👥 **本地多人游戏**：支持最多 4 台手机同时连接。
- 🌀 **陀螺仪瞄准**：利用手机体感控制游戏视角。
- 📳 **震动反馈**：将游戏震动同步传回手机。
- 🎨 **自由定制**：调整按键位置、大小、透明度和布局。
- ⚡ **低延迟通信**：采用 Wi-Fi UDP 实时传输。

**免费 · 开源 · 无需购买实体手柄**

> «手机负责操作，电脑负责运行游戏。让每一部闲置手机都成为游戏控制器。»

---

## 这是给谁用的

| 你的处境 | 你会得到什么 |
| --- | --- |
| 想在 PC 上玩手柄游戏，手边没有手柄 | 手机就是手柄，`joy.cpl` 与游戏内都显示为 *Xbox 360 Controller for Windows* |
| 想本地双人／四人，手柄不够 | 一台 PC 同时接 **1P–4P** 四台手机，各自独立手柄，震动按人回传 |
| 玩射击／开放世界，手柄瞄准不够快 | **陀螺仪瞄准**：转手机就是转视角，与右摇杆叠加生效，无累计漂移 |
| 手上的手柄 App 布局别扭 | 19 个控件逐个调位置／大小／透明度，三套主题色、浅色／深色／跟随系统 |
| 想接自己的脚本或设备 | 20 字节定长 UDP 协议 + 共享测试向量，MIT 许可，协议文档齐全 |

**不适合**：音游与格斗精确连段（Wi-Fi 时延决定下限）、只支持 DirectInput 的老游戏、macOS / Linux 主机。

---

## 和其他"手机当手柄"方案的区别

| 方案 | 游戏认成真手柄 | PC 端要装什么 | 模拟摇杆 | 震动回传 |
| --- | --- | --- | --- | --- |
| 键鼠映射类工具 | ✗ 只能伪装键鼠 | 无 | ✗ | ✗ |
| 手机蓝牙 HID | 视游戏而定，多数需 Steam Input 转接 | 无 | ✓ | 通常 ✗ |
| **本项目** | **✓ XInput 原生识别** | 一次性安装 876 KB 离线驱动 | ✓ | ✓ |

代价很明确：要装一个**已签名内核驱动**（ViGEmBus）。这是 Windows 的硬边界——
用户态程序无法凭空向系统声明"这里插了一只手柄"，凡是被游戏当真的方案，
PC 侧必然有驱动。好处是装完之后，任何走 XInput 的游戏都**原生**认它。

---

## 亮点

| 能力 | 说明 |
| --- | --- |
| 双路独立灵敏度 | 「陀螺仪视角灵敏度」与「右摇杆视角灵敏度」各 0–100，互不影响 |
| 完整键位 | ABXY、LB/RB、LT/RT（0–255 模拟）、十字键、`☰` 菜单、`X` Home、L3/R3——共 19 个控件 |
| 摇杆按下三种模式 | 轻点中心／双击摇杆内任意位置／操作摇杆即持续按下；推动摇杆做拖动不会误触 |
| Material 3 + 新拟态界面 | 五套低饱和主题色（切换时背景同步渐变 480 ms）、浅色／深色／跟随系统、按下下沉回弹 |
| 可折叠顶栏 | 连接栏收起为「手柄」胶囊，展开时整屏手柄实时虚化 + 磨砂玻璃面板，不拦截游戏触控 |
| 断线自愈 | Wi-Fi 闪断、PC 接收端重启都会按退避自动连回；实测 PC 重启后 **1 秒内恢复** |
| 开箱即用 | 便携包自带接收端、手机 APK、离线驱动；免装 Python，解压即跑 |

---

## 三步开始

**第 1 步 · 解压便携包**

```
VirtualGamepadReceiver-Portable/
├─ VirtualGamepadReceiver.exe      PC 接收端（免安装，双击即运行）
├─ 手机端/VirtualGamepad-v1.1.0-debug.apk    手机 App
├─ 可选驱动/ViGEmBusSetup_x64.msi           876 KB 离线驱动
└─ 使用说明.txt
```

**第 2 步 · 装一次驱动**（仅首次）

右键 `可选驱动/ViGEmBusSetup_x64.msi` → 以管理员身份安装 → **重启电脑**。
驱动是内核级的，必须重启才加载。装完无需再管。

**第 3 步 · 连手机**

手机装 APK（`手机端/` 里那个），与 PC 连同一 Wi-Fi → 点左上角「手柄」展开连接栏 →
填 PC 的 IPv4 与端口 `26760` → 连接。状态条出现 `已连接 · 250 pps · 1P` 即可开游戏。

> 详细点选清单见 **[QUICKSTART.md](QUICKSTART.md)**；从源码编译、C# 参考实现、
> 手动启动等进阶路径见 **[docs/TECHNICAL.md](docs/TECHNICAL.md)**。
> 端口要放行：首次运行 Windows 防火墙弹窗时，允许**专用网络**的 UDP 26760 入站。

---

## 为什么游戏会当真手柄

Windows 上游戏读手柄走两条互不相通的路：**XInput**（`xinput1_4.dll`，2006 年后绝大多数
手柄游戏）与更老的 **DirectInput**。用户态程序无法伪造 USB 设备，所以纯"按键映射"工具
只能做到键鼠级别。

**ViGEmBus** 是一个已签名的 KMDF 内核总线驱动：用户态通过 IOCTL 请求它"在虚拟总线上
插入一台 Xbox 360 控制器"，Windows PnP 随即完成枚举并分配 XInput 槽位。于是设备管理器
里出现手柄、`XInputGetState(0, …)` 返回成功、游戏拿到的状态结构与实体手柄完全同构。

**结论：在内核对象层面，游戏没有技术手段分辨它与硬件。** 所谓"识别不了"，99% 是
驱动没装／装完没重启、接收端没启动、或游戏本身只走 DirectInput。

> ViGEmBus 已于 2023-11-02 归档，最终版 1.22.0。它是单一职责的成熟驱动，在
> Windows 10/11（含 24H2）上稳定工作；**随包分发的 1.17.333 同样是可用版本**，
> 无需追求最新版。

---

## 能力与边界

| 项目 | 实测／指标 |
| --- | --- |
| 传输 | Wi-Fi UDP 单播，20 字节定长、**250 Hz**，约 5 KB/s |
| 延迟 | 局域网典型 10–20 ms；含触控采样与一帧渲染约 35–50 ms |
| 同时在线 | 最多 4 台手机（XInput 槽位上限） |
| 覆盖 | 所有走 XInput 的游戏／模拟器（Steam、Epic、UE、Unity、RE Engine、RPCS3、Yuzu、RetroArch…） |
| 不覆盖 | 仅 DirectInput 的老游戏、部分限制虚拟设备的竞技网游、非 Windows 主机 |
| 验收 | XInput 层 22/22 项通过；协议层 13 个按键单一位精确命中；实测 250 pps、丢序号 0 |

安全与合规：驱动只从官方或本包安装，不要用第三方"整合包"；虚拟设备在单机／合作游戏中
合规，但**部分竞技网游的反作弊可能限制虚拟手柄**，使用前请确认该游戏用户协议。
本项目全部流量只在本机与局域网内，不经任何第三方服务器。

---

## 文档导航

| 想知道什么 | 看这里 |
| --- | --- |
| 三步上手、常见卡点 | [QUICKSTART.md](QUICKSTART.md) |
| 原理、协议字节布局、按键映射、调校、排障 | [docs/TECHNICAL.md](docs/TECHNICAL.md) |
| 协议规范与测试向量 | [docs/PROTOCOL.md](docs/PROTOCOL.md) · [VGP1](protocol/test_vectors.json) / [VGP2](protocol/test_vectors_v2.json) |
| 端到端验收记录（22 项矩阵、取证命令） | [docs/FUNCTIONAL-ACCEPTANCE-2026-11-06.md](docs/FUNCTIONAL-ACCEPTANCE-2026-11-06.md) |
| 为什么必须装驱动、能否免驱 | [docs/DRIVER-ANALYSIS-2026-11-06.md](docs/DRIVER-ANALYSIS-2026-11-06.md) |
| 震动回传实现（含句柄死锁坑） | [docs/RUMBLE-2026-11-06.md](docs/RUMBLE-2026-11-06.md) |
| 「一直正在重连」根因与修复 | [docs/CONNECTION-FIX-2026-11-06.md](docs/CONNECTION-FIX-2026-11-06.md) |
| 代码审计与测试报告 | [docs/AUDIT.md](docs/AUDIT.md) · [docs/CODE-AUDIT-2026-11-04.md](docs/CODE-AUDIT-2026-11-04.md) · [docs/TEST-REPORT.md](docs/TEST-REPORT.md) |

配套 4 款测试游戏在 `game/`，直接读 XInput，用来验证摇杆行程、扳机深度、双摇杆同时操作。

---

## 许可与致谢

本项目以 [MIT](LICENSE) 开源。第三方组件遵循各自协议，分发时请保留声明：
[ViGEmBus](https://github.com/nefarius/ViGEmBus)（BSD-3-Clause）·
[Nefarius.ViGEm.Client](https://www.nuget.org/packages/Nefarius.ViGEm.Client)（MIT）·
[vgamepad](https://pypi.org/project/vgamepad/)（MIT）·
[pygame](https://www.pygame.org/)（LGPL）·
Material Components for Android / Kotlin / AndroidX（Apache-2.0）
