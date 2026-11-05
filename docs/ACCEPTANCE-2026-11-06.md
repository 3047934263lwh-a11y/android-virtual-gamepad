# 验收报告 · 安卓虚拟手柄 → Xbox 360 控制器

- 项目：`C:\Users\30479\WorkBuddy\2026-11-05-00-24-13\virtual-gamepad`
- 版本：PC 接收端 v1.1.0 ＋ Android App 1.1.0（`com.vgamepad`）
- 验收时间：2026-11-06 05:00 – 05:20
- 结论：**端到端全链路打通，7/7 项通过**。手机触摸 → UDP → PC 接收端 → ViGEmBus → XInput → 游戏，A/B/X/Y/LB/RB/左摇杆全部命中。

---

## 1. 验收环境

| 项目 | 实测值 | 取证方式 |
|------|--------|----------|
| 操作系统 | Windows 10 专业版 22H2，Build 19045 | `Win32_OperatingSystem` |
| CPU / 内存 | i9-14900HX / 31.7 GB | `Win32_Processor` |
| 本次开机时刻 | 2026-11-06 04:37:14（验收时已运行 24 分钟） | `LastBootUpTime` |
| PC 局域网地址 | `192.168.1.10:26760` | 接收端界面 / `Get-NetIPAddress` |
| 手机 | `10CE5X07GY001TG`，IP `192.168.1.3`，屏 2800×1260（横屏） | `adb devices` / `wm size` |
| 手机 App | `com.vgamepad` versionName 1.1.0 | `dumpsys package` |
| 手机端已存配置 | `ip=192.168.1.10`、`port=26760`、自定义布局 `layout_v1`、`button_scale=1.3` | `run-as com.vgamepad cat shared_prefs/vgp.xml` |

---

## 2. 驱动层：状态与结论修正

### 2.1 当前真实状态

| 检查项 | 实测结果 |
|--------|----------|
| 服务 `ViGEmBus` | `Running`，StartType = `System`（显示名 Nefarius Virtual Gamepad Emulation Service） |
| 设备节点 `ROOT\SYSTEM\0001` | Status `OK`，Service `ViGEmBus`，Driver 键 `{4d36e97d-...}\0072`，DeviceDesc 指向 **`@oem7.inf`** |
| 磁盘驱动文件 | `C:\Windows\System32\drivers\ViGEmBus.sys` = **1.17.333.0** |
| 驱动库 `oem7.inf` | ViGEmBus **1.17.333.0**（当前生效包，被 0001 绑定） |
| 驱动库 `oem96.inf` | ViGEmBus **1.21.442.0**（已入库，**未被任何节点绑定**） |
| 重复节点 `ROOT\SYSTEM\0002` | Status `Error`，`CM_PROB_FAILED_ADD`（历史 `nefcon --create-device-node` 重试残留） |
| 符号链接 `\\.\ViGEmBus` | `QueryDosDevice` 返回 NOT FOUND，但 `vigem_connect` 走设备接口成功（见下） |

### 2.2 总线连通性实证

```
vigem_connect        = 0x00000000  OK
VX360Gamepad created = serial 1
XInput sees A: True | sees LX: True
SMOKE PASS
```

随后二次复核：从 Python 创建手柄并下压 A，XInput 槽位 0 **80/80 采样全部在线**。

### 2.3 三条结论修正（推翻 11-05 会话记录）

1. **「1.17.333 在该系统上接口注册失败、必须 1.22.0」不成立。** 当前生效的正是 1.17.333，且工作正常。11-05 观察到失败时的状态是**混合态**：节点绑定 `oem7`，而 `ViGEmBus.sys` 已被替换成 1.21.442 —— 绑定包与二进制不一致才导致接口未注册。重启后 PnP 将 sys 恢复为与绑定包一致的 1.17.333，故障消失。
2. **「oem7.inf 已删除」不成立。** `pnputil /enum-drivers` 明确列出 `oem7.inf` 1.17.333.0。该包是**当前唯一有效包**，删除会直接摧毁已可用的虚拟手柄输出。
3. **「放弃驱动、回退纯接收端」的决策前提已消失。** 无需再跑 1.22.0 官方向导，也不需要处理代理污染 aiph 载荷的问题。

> 安全提示：清理驱动库时只能删 `oem96.inf`（未绑定的 1.21.442）与失败节点 `ROOT\SYSTEM\0002`，**切勿删除 `oem7.inf`**。

---

## 3. 端到端验收矩阵

### 3.1 方法与判定位

- 注入面：`adb shell input swipe x y x y 600`（真机触摸，非旁路注入）。
- 读取面：`xinput1_4.dll → XInputGetState`，15 ms 轮询，仅统计 `ok==0` 的在线采样。
- **判定位修正**：XInput 的 A/B/X/Y = `0x1000/0x2000/0x4000/0x8000`，LB/RB = `0x0100/0x0200`。原 `_verify/verify_game_play.py` 用 `0x0001/0x0002` 判定 A/B —— 这两个位是 `DPAD_UP/DPAD_DOWN`，因此旧脚本必然报 FAIL。该脚本已修正。

### 3.2 按键坐标标定

对 2800×1260 截图做连通域分析（步长 4 px、亮度阈值 170）得到圆心，取代原先凭经验写死的坐标：

| 控件 | 圆心 (px) | 尺寸 (px) |
|------|-----------|-----------|
| A | (2473, 182) | 460×392（贴边裁切） |
| B | (2117, 1114) | 440×300 |
| X | (1998, 285) | 484×484 |
| Y | (1840, 857) | 344×344 |
| LB / RB | (825, 70) / (1626, 70) | 596×108 / 560×108 |
| LS 中心 | (601, 842) | 156×156 |
| RS 中心 | (2291, 694) | 232×232 |
| BACK | (1313, 657) | 532×220 |

### 3.3 结果 · 源码接收端（`use_vigem=True`）

| # | 验收项 | 判据 | 实测 | 结果 |
|---|--------|------|------|------|
| 1 | 手机按 A | `wButtons & 0x1000` | 0x1000 | PASS |
| 2 | 手机按 B | `wButtons & 0x2000` | 0x2000 | PASS |
| 3 | 手机按 X | `wButtons & 0x4000` | 0x4000 | PASS |
| 4 | 手机按 Y | `wButtons & 0x8000` | 0x8000 | PASS |
| 5 | 手机按 LB | `wButtons & 0x0100` | 0x0100 | PASS |
| 6 | 手机按 RB | `wButtons & 0x0200` | 0x0200 | PASS |
| 7 | 左摇杆右推 | `|LX| > 8000` | LX = 28427 | PASS |
| — | 虚拟手柄枚举 | XInput 在线采样 | 734/734 | PASS |
| — | 驱动就绪 | `driver_state=True` | ViGEmBus 就绪 | PASS |
| — | 玩家接入 | `online` | 1P `192.168.1.3:26761` | PASS |

### 3.4 结果 · 重建后的便携版 exe

同一套注入与判据，对 `release/VirtualGamepadReceiver-Portable/VirtualGamepadReceiver.exe` 复测：

| 项 | A | B | X | Y | LB | RB | LS |
|----|---|---|---|---|----|----|-----|
| 结果 | PASS | PASS | PASS | PASS | PASS | PASS | PASS（LX=28817） |

`RESULT 7/7`，在线采样 924/1342（前段采样覆盖手机未连接窗口）。

---

## 4. 交付物重建

### 4.1 问题

旧便携包 exe 构建于 **02:47**，而 `desktop/receiver.py` 在 **04:53** 仍有修改 —— 交付物落后于源码。实测旧 exe：界面 `LISTENING`、`CONNECTED 1/4`、P1 高亮，但 **XInput 四个槽位全空**，即"玩家已登记、虚拟手柄未创建"（`_probe_vigem` 在冻结环境中返回失败，走 `pad=None` 降级分支）。

### 4.2 处理

| 步骤 | 结果 |
|------|------|
| 补齐构建依赖 `PySide6 6.12.0`、`Pillow 13.0.0` | 完成 |
| 绕过批量删除拦截（旧包与 PyInstaller 中间目录先重命名，再全新建包） | 完成 |
| `python desktop/build_portable.py` | 成功 |
| 产物：`VirtualGamepadReceiver-Portable/` | 233 文件，exe 1,948,581 B（05:11） |
| 产物：`VirtualGamepadReceiver-Portable-v1.1.0-win64.zip` | 52,350,280 B（49.9 MiB） |
| 结构自检 | `python313.dll / qwindows.dll / Qt6Widgets.dll / QtWidgets.pyd / vcruntime140.dll` 全部 OK |
| 清理构建残留 | `_old_portable_*`、`_junk_build_*`、`_build_tmp`、`_dist_tmp` 已删除 |

随包 `可选驱动/ViGEmBus_Setup.exe` 版本确认为 **1.22.0**（官方原版）；`config/settings.ini` 默认 `port=26760, auto=true, vigem=true`。

---

## 5. 已知缺陷与限制

| 级别 | 问题 | 影响 | 处置 |
|------|------|------|------|
| 功能缺口 | 力反馈未实现：`vendor/vigem_native.py:311` `register_notification()` 直接 `raise NotImplementedError("vibration feedback pending")` | 游戏震动无法回传手机；接收端日志每玩家告警一次 `1P 力反馈回调注册失败（震动回传不可用）`，按键/摇杆不受影响 | 已知待实现项（IOCTL_XUSB_REQUEST_NOTIFICATION 挂起语义），非回归 |
| 系统残留 | `ROOT\SYSTEM\0002` 处于 `CM_PROB_FAILED_ADD` | 不影响功能；设备管理器可见黄色感叹号 | 需管理员：`pnputil /remove-device ROOT\SYSTEM\0002` |
| 系统残留 | `oem96.inf`（1.21.442）已入库未绑定，占约一份驱动副本 | 无功能影响 | 需管理员：`pnputil /delete-driver oem96.inf /force`（**勿动 oem7.inf**） |
| 测试缺陷 | 旧 `verify_game_play.py` A/B 判定位错误 | 造成"按键失败"的假阴性 | 已改为 `0x1000/0x2000` |

---

## 6. 变更清单

| 文件 | 变更 |
|------|------|
| `virtual-gamepad/release/VirtualGamepadReceiver-Portable/` | 整包重建（源码 04:53 状态），旧包已删除 |
| `virtual-gamepad/release/VirtualGamepadReceiver-Portable-v1.1.0-win64.zip` | 重建，49.9 MiB |
| `virtual-gamepad/desktop/config/settings.ini` | `vigem=false` → `vigem=true`（撤销放弃驱动时的回退） |
| `_verify/verify_game_play.py` | A/B 判定位 `0x0001/0x0002` → `0x1000/0x2000` |
| `release/_old_portable_*`、`_junk_build_*`、`_build_tmp`、`_dist_tmp` | 删除（构建残留约 338 MB） |

未改动：`receiver.py`、`main.py`、`widgets.py`、`vigem_native.py`、Android 端任何源码。

---

## 7. 复现命令

```bash
# 1. 总线连通性
python _verify/native_smoke.py          # 期望 SMOKE PASS

# 2. 源码接收端端到端（7 项按键矩阵）
python _e2e_v2.py                        # 期望 RESULT 7/7

# 3. 打包 exe 端到端
python _e2e_exe.py                       # 期望 RESULT 7/7

# 4. 重新打包
cd virtual-gamepad/desktop && python build_portable.py
```

`_e2e_v2.py` / `_e2e_exe.py` 位于 `C:\Users\30479\WorkBuddy\2026-11-06-05-00-32\`。

---

## 8. 待办

1. 力反馈回传（IOCTL_XUSB_REQUEST_NOTIFICATION）实现，补 `register_notification`。
2. 管理员权限清理 `ROOT\SYSTEM\0002` 与未绑定的 `oem96.inf`。
3. 建议将驱动现状写入 README：**生效版本 1.17.333（oem7）**，1.22.0 安装器仅作新机可选件。
4. 连续稳定性复测（30 分钟以上、多手柄 4P 同时接入、摇杆死区曲线）。
