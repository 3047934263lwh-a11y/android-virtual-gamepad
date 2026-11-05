# 版本演进与驱动依赖分析

- 仓库：`github.com/3047934263lwh-a11y/android-virtual-gamepad`（86 commits，11 个 tag）
- 分析范围：v1.0.1（2026-10-31）→ v1.1.0（2026-11-05）
- 分析方式：GitHub API 取各 tag 的 `requirements.txt` / `server-python/server.py` / `server-csharp/Program.cs` / 发布附件，并对 v1.0.10 便携包做解包核对

---

## 0. 结论先行

**从来没有任何一个版本做到过「不需要驱动」。** 首个 commit（`f65619f8c`，2026-10-31 05:53）的提交信息就是：

> 初始发布：安卓虚拟 Xbox 360 手柄（**Wi-Fi UDP + ViGEmBus 虚拟设备**），支持本地多人/陀螺仪瞄准/震动回传/逐键自检

所有 tag 的 `server-python/requirements.txt` 均只有一行 `vgamepad>=0.1.1`；`vgamepad` 在 Windows 上的存在意义就是调用 ViGEmBus。C# 接收端同理，依赖 `Nefarius.ViGEm.Client 1.21.256`。

**你之所以觉得「以前不用装驱动」，是因为驱动早就装好了，而且安装包一直是随包发的。**

证据：v1.0.10 便携包（14.0 MB）解包后只有 5 个条目：

```
虚拟手柄便携版/一键启动接收端.bat
虚拟手柄便携版/使用说明.txt
虚拟手柄便携版/接收端.exe                    9,602,188
虚拟手柄便携版/虚拟手柄手机端.apk            5,768,097
虚拟手柄便携版/ViGEmBus驱动安装包/ViGEmBusSetup_x64.msi   876,544
```

该 MSI 的版本字符串为 `1.17.333.01033`，即 **ViGEmBus 1.17.333** —— 与你这台机器当前生效的驱动（`ViGEmBus.sys` = 1.17.333.0，节点 `ROOT\SYSTEM\0001` 绑定 `oem7.inf`）**是同一个文件**。也就是说：本机现在跑的驱动，本来就是当年随包发的那一个。

---

## 1. 各版本关键差异

| 维度 | v1.0.1（10-31） | v1.0.2–v1.0.10（11-04） | v1.1.0（11-05） |
|------|-----------------|--------------------------|------------------|
| 接收端形态 | 单文件 CLI `server.py`（11.9 KB）+ C# 控制台 | 同，功能累加 | Qt GUI（`desktop/main.py` + `receiver.py` + `widgets.py`） |
| 手柄库 | pip 安装 `vgamepad`（wheel 内含 ViGEmClient.dll） | 同 | 内置 `desktop/vendor/vgamepad/`（含 x64/x86 DLL）+ 自研 `vigem_native.py`（纯 ctypes） |
| 驱动缺失时 | `vg.VX360Gamepad()` 抛异常 → 该玩家被 `continue` 跳过，仅控制台打印 | 同 | 启动即 `_probe_vigem()` 探测 → `driver_state` 信号 → 界面提示 + 自动降级「仅监听」 |
| 驱动分发 | 包内 **876 KB 离线 MSI（1.17.333）** | 同 | 原为 **6.2 MB 联网 stub（1.22.0）** |
| 文件总数 | 56 | 58 → 82 | 104 |
| 便携包体积 | 14.0 MB | 14.0 MB | 46.6 MB |
| 协议 | VGP1，20 字节定长 | + v2 测试向量、玩家编号、震动回传 | 同 + `protocol/test_vectors_v2.json` |
| 内置 demo 游戏 | `game/*.py`，读 XInput，带键盘兜底 | 同 | 同（未改） |

### 差异的实质

1. **架构没变**：手机 → Wi-Fi UDP（26760）→ PC 接收端 → 虚拟 XInput 设备 → 游戏，六代版本同一条链路。
2. **变的只有三件事**：接收端从 CLI 变成 GUI、`vgamepad` 从 pip 依赖变成内置、驱动安装器从**离线 MSI** 换成**联网 stub**。
3. **第 3 点是唯一的功能性倒退**：早期的 876 KB MSI 装完即用、不联网；v1.1.0 换成官方 1.22.0 的 6.2 MB stub，安装时还要再下 9.9 MB 载荷（`holder0.aiph`），在代理环境下被写成全零文件 → MSI 报 Error 1308。这就是 11-05 那场排障的起点。

---

## 2. 当前版本为什么必须依赖驱动

**不是版本选择问题，是 Windows 的接口边界问题。**

1. `XInputGetState` 只能枚举由 XUSB/KMDF 内核栈注册的设备。用户态进程没有任何 API 能"凭空插入一台 Xbox 360 控制器"。
2. 用户态能做的只有：把请求通过 IOCTL 发给一条**虚拟总线驱动**，由驱动完成 PDO 枚举、设备接口注册、符号链接创建。ViGEmBus 就是这条总线。
3. 因此凡是"游戏把你认成真手柄"的方案，PC 侧必然有一个内核驱动：ViGEmBus（本项目）、HIDMaestro、vJoy、Interception —— 无一例外。
4. 反向验证：本机实测 `vigem_connect = 0x00000000` 后，`VX360Gamepad()` 创建成功、XInput 立刻在槽位 0 枚举到设备；驱动停摆时，同一份代码四槽全空。**依赖点在驱动，不在代码。**

11-05 之前之所以"感觉不到"，是因为三层缓冲同时存在：驱动早已装好 → 启动脚本自动 `pip install vgamepad` → `START-RECEIVER.bat` 只做 `sc query ViGEmBus` 静默检查。直到排障时把驱动卸掉又装成混合态，依赖才第一次显形。

---

## 3. 能否移除或替代该驱动

### 3.1 想保留「游戏认成 Xbox 360 手柄」语义 → **不可免驱**

替代品只是换一家驱动：

| 方案 | 性质 | 评价 |
|------|------|------|
| ViGEmBus（现状） | KMDF 总线驱动，已归档但稳定 | 生态最成熟，1.17.333 / 1.21.442 均实测可用 |
| HIDMaestro | 签名驱动，支持 HID + XUSB 双模态 | 可作备选；API 与生态不及 ViGEm |
| vJoy | 驱动，2020 年后停更 | 不推荐 |
| Interception | 键鼠级别驱动，非手柄 | 解决不了 XInput |

### 3.2 想做到「PC 端零安装」→ 可行，但要换输入通道

| 路径 | PC 侧安装 | 代价 |
|------|-----------|------|
| **A. 手机当蓝牙 HID 手柄**（`BluetoothHidDevice`） | 零软件、零驱动 | 需蓝牙配对；延迟增加；Windows 只当通用 HID，XInput 游戏不认（要走 Steam Input 或 DirectInput 游戏）；单机；震动回传困难 |
| **B. 键鼠映射**（`SendInput`） | 零驱动 | 只能玩支持键鼠的游戏；无模拟摇杆；无手柄图标；部分反作弊会拦注入 |
| **C. demo 游戏直读 UDP** | 零驱动 | 只对本项目 `game/` 下的示例有效，不能玩商业游戏 |

**推荐组合**：保留 ViGEmBus 路线（真手柄语义不可替代），把「要下载」彻底消除 —— 已按此改造完毕；同时把 B 作为免驱兜底模式补上。

---

## 4. 具体改动建议

### 4.1 已完成（本次）

| 改动 | 文件 | 说明 |
|------|------|------|
| 驱动安装器换回离线 MSI | `desktop/assets/ViGEmBusSetup_x64.msi`（876,544 B，1.17.333） | 与 v1.0.1–v1.0.10 同一文件，安装不联网 |
| 移除联网 stub | 删除 `desktop/assets/ViGEmBus_Setup.exe`（6,278,576 B） | 该 stub 安装时仍需下载 9.9 MB 载荷，代理下必失败 |
| 打包逻辑改为优先 MSI | `desktop/build_portable.py:149` | 有 MSI 用 MSI，无则回退 stub；说明文案同步更正 |
| 便携包重出 | `release/VirtualGamepadReceiver-Portable/` + zip（46.6 MiB） | `可选驱动/` 内只保留离线 MSI |

### 4.2 建议后续实施：输入后端抽象（支持免驱兜底）

现状 `receiver.py` 把 ViGEm 写死在 `_probe_vigem` / `_make_pad` / `_apply` 三处。建议抽出后端：

```python
class InputBackend:                      # 接口
    name = "?"
    def open(self) -> tuple[bool, str]: ...
    def attach(self, idx: int, sock) -> object: ...
    def apply(self, dev, buttons, lx, ly, rx, ry, lt, rt) -> None: ...
    def close(self) -> None: ...

class VigemBackend(InputBackend):        # 现有逻辑搬入
    ...

class KeyboardBackend(InputBackend):     # 免驱兜底：SendInput
    # 左摇杆 -> WASD（阈值 ±8000 触发）
    # 右摇杆 -> 鼠标相对位移（MOUSEEVENTF_MOVE）
    # ABXY -> J K U I；LB/RB -> Q E；LT/RT -> 鼠标左右键
```

配套改动：

1. `desktop/config/settings.ini` 增 `backend = auto|vigem|keyboard`；`auto` 时先试 ViGEm，失败自动落键盘，界面提示当前后端。
2. `_probe_vigem()` 改名为 `_probe_backend()`，返回值加 backend 名称。
3. `_make_pad` / `_apply` / `_unplug` 改为 `backend.attach/apply/detach`，ViGEm 专属的 `register_notification` 留在 `VigemBackend` 内。
4. 键鼠模式必须用 `SendInput` + 扫描码（`KEYEVENTF_SCANCODE`），`keybd_event` 在部分游戏全屏独占下无效。

### 4.3 建议后续实施：驱动一键安装

驱动缺失时，界面上的提示从「请去 GitHub 下载」改为一个按钮：

```python
ctypes.windll.shell32.ShellExecuteW(
    None, "runas", "msiexec.exe",
    f'/i "{msi}" /qn /norestart', None, 1)
```

MSI 随包就在 `可选驱动/`，全程离线，用户零下载。

### 4.4 文档更正

`README.md` 第 11、346 行现写「必须先安装 ViGEmBus 1.22.0」。应改为「ViGEmBus 1.17.333 及以上」，因为 1.17.333 已实测可用，而 1.22.0 的联网 stub 反而在代理环境下装不上。

---

## 5. 一句话回答

- **以前不用装驱动**：因为驱动随包发（876 KB 离线 MSI），且你机器上一直装着；
- **现在必须装驱动**：不是版本退化，是 XInput 设备只能由内核驱动创建；真正退化的是把离线 MSI 换成了联网 stub；
- **能否免驱**：要真手柄语义就不能；要免安装就换蓝牙 HID 或键鼠映射；
- **已做的修复**：离线 MSI 已放回包内，新机器上安装也不再有下载环节。
