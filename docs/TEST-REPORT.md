# 测试报告

| 项 | 内容 |
| --- | --- |
| 日期 | 2026-11-04 |
| 基线 | v1.9.9（commit `982a111`）→ 本次修复后 |
| 操作系统 | Windows 10 19045 x64 |
| Python | 3.13.14，`vgamepad` 可用，`pygame` 2.6.1 |
| JDK / Gradle | JDK 17.0.21 / Gradle 8.9（离线构建） |
| .NET | **SDK 8.0.427**（本地部署于 `_buildenv/dotnet8`，SHA512 与官方一致）+ Runtime 8.0.33 |

---

## 1. 执行结果汇总

| # | 测试 | 命令 | 结果 |
| --- | --- | --- | --- |
| 1 | Python 语法编译 | `python -m py_compile ...`（8 文件） | **PASS** |
| 2 | 协议一致性（VGP1 向量） | `python tools/test_protocol.py` | **PASS 6/6** |
| 3 | 按键边缘检测 | `python tools/test_gamepad.py` | **PASS 6/6** |
| 4 | 端到端 + 真实 XInput | `python tools/e2e_test.py` | **PASS 8/8** |
| 5 | Android Debug 构建 | `gradle --offline assembleDebug` | **PASS**（BUILD SUCCESSFUL） |
| 6 | C# 构建 | `dotnet build -c Release` | **PASS**（0 警告 / 0 错误） |
| 7 | C# 端到端（真实 XInput + 回传） | `tools/verify_csharp.py` | **PASS 33/33** |
| 8 | 客户端异常断开（C# / Python） | `tools/verify_udp_reset.py` | **PASS 4/4 + 4/4** |

---

## 2. 端到端测试明细（真实 XInput 读回）

```
PASS  接收端连接与速率日志
PASS  A 键按下        buttons=0x1000
PASS  左摇杆上推      LY=32767
PASS  LT 半按         LT=200
PASS  B 键按下        buttons=0x2001
PASS  方向键上        buttons=0x2001
PASS  RT 满行程       RT=255
PASS  右摇杆 X        RX=12000
RESULT: PASS
```

发送的两组报文（各 40 包 / 125 Hz 近似）：
- 第 1 组：`buttons=0x0001`（A）、`lx=-32768`、`ly=32767`、`lt=200`
- 第 2 组：`buttons=0x0402`（B + 方向键上）、`rx=12000`、`ry=-12000`、`rt=255`

该测试同时验证了：
- 20 字节严格解析（超长/截断包被拒）
- 报文 → ViGEm → XInput 全链路
- 1.2 秒空闲回中（日志出现「输入已回中」）
- 退出时清理虚拟手柄（2026-11-04 复审修正：vgamepad 无 `unplug()`，
  现为 `unplug_pad()` 显式 `vigem_target_remove`，e2e 的 terminate 路径并不覆盖该验证）

---

## 3. 协议一致性测试明细

| 用例 | 覆盖点 |
| --- | --- |
| `test_vector_file_meta` | 向量文件元信息（协议名、包长） |
| `test_every_case_is_20_bytes` | 每条向量确实是 20 字节 |
| `test_kotlin_serialize_matches_vectors` | Kotlin 写入顺序 ↔ 期望字节 |
| `test_python_parse_matches_vectors` | Python 解析字段 ↔ 期望字段 |
| `test_csharp_logic_matches_vectors` | C# 位运算逻辑 ↔ 期望字段 |
| `test_reject_malformed_packets` | 19B / 21B / 空包 / 错 magic / 错版本 / 仅 magic |

向量共 5 组：中性帧、A+左摇杆满上+LT200、方向键上+B+RT255、
GUIDE+L3+R3+极值、全键按下。

---

## 4. 按键边缘检测明细

| 用例 | 断言 |
| --- | --- |
| `test_signature_has_no_mutable_default` | `just_pressed(self, name)` 无多余默认参数 |
| `test_two_keys_same_frame_both_detected` | 同帧查 A、B 均为 True（原实现第二个为 False） |
| `test_held_keys_not_repeated` | 持续按住不重复触发 |
| `test_release_then_press_again` | 松开再按能再次触发 |
| `test_instances_do_not_share_state` | 两个实例状态互不影响 |
| `test_edge_detection`（twin_stick 对照） | 另一套实现行为一致 |

---

## 4.1 C# 接收端端到端（33 项，真实 XInput 读回）

环境：.NET 8.0.427 SDK（本地目录部署）+ ViGEmBus 已安装，服务端监听 26799，
测试客户端从 `0.0.0.0:26761` 发 VGP1 报文。

```
[PASS] XInput 枚举到虚拟手柄
[PASS] A/B/X/Y/LB/RB/BACK/START/L3/R3/UP/DOWN/LEFT/RIGHT 逐键映射（14 项，均为唯一置位）
[PASS] 左摇杆 X=-32768  Y=+32767   右摇杆 X=+32767  Y=-32768
[PASS] 左扳机 200   右扳机 255
[PASS] A+B+LB+UP+START 组合键 = 0x3111
[PASS] 收到 VGPP 编号包，player=1
[PASS] XInputSetState 触发 VGPR 回传（40000/20000 → 156/78，缩放正确）
[PASS] 19/21/128 字节、magic 错、版本错、全 0 共 6 类畸形包全部丢弃且状态不污染
[PASS] 停发 1.2 s 后按键/摇杆/扳机全部回中
```

> `GUIDE`（Home 键）单独验证：手机端发出 `0x4000` 后，
> 标准 `XInputGetState` **读不到**（微软设计如此），
> 未公开的 `XInputGetStateEx`（序数 100）读到 `0x0400` —— 证明该位确实送达驱动，
> 只是标准 API 不对外暴露。游戏能否用到取决于其读取方式。

## 4.2 客户端异常断开（新增回归项）

模拟手机 App 被杀/换网：客户端 socket 直接 close，等待 3.2 s（触发 3 次周期回包 → ICMP 端口不可达），
再同端口重连。

| 接收端 | 结果 | 关键观察 |
| --- | --- | --- |
| Python | **4/4 PASS** | 修复前进程直接退出；修复后存活、旧按键不残留、回包正常 |
| C# | **4/4 PASS** | 修复前 2 s 内 20+ 行 `[错误] 接收失败：ConnectionReset`；修复后 **0 行**，且新客户端可正常接入 |

---

## 4.3 UI 重设计验收（Material 3 + Neumorphism，v1.11.0）

设备：vivo V2338A（2800×1260，横屏锁定），Python 接收端 + 真实 XInput 读回。

| # | 项目 | 方法 | 结果 |
| --- | --- | --- | --- |
| 1 | 全部按键触控 | 按真实布局算出坐标，逐个按住 420ms 读 XInput | **PASS** A/B/X/Y/LB/RB/BACK/MENU(→START)/GUIDE |
| 2 | 方向键四臂 | 分别按住四个臂 | **PASS** 0x0001/2/4/8 |
| 3 | 摇杆方向与幅度 | 拖动到 45% 与 95% 行程 | **PASS** 半程 ly=13364，满程 ly=30383；四方向均 ≥18000 |
| 4 | 摇杆按下 L3/R3 | 按用户实际选择的「双击区域」方式 | **PASS** 0x0040 / 0x0080 |
| 5 | 扳机 | 单击 / 拖到中段 / 拖回顶部 | **PASS** 255 / 127 / 9 |
| 6 | 设置面板与外观 | uiautomator 定位并点击 | **PASS 12/12**（外观区、三种主题、7 个色板、深色可再次打开） |
| 7 | 分辨率适配 | `wm size 1920x1080` | **PASS** 无崩溃、布局正常 |
| 8 | 背景随主题色变化 | 换色后采样背景像素 | **PASS** 暖(231,233,236) → 冷(227,234,240) |
| 9 | 过渡为逐帧动画 | `dumpsys gfxinfo` 统计过渡期间帧数 | **PASS** 44 帧 / 90th 12ms / 错过 VSync = 0 |

**多点同时按下**：设备未 root，`/dev/input` 不可写、`input` 不支持多指，
无法注入双指事件。改用结构等价性证明：`onTouchEvent`、`bindPointer`、`releasePointer`、
`applyZone`、`zoneAt`、`setStick` 等 **21 个输入/触控函数与上一版逐字节一致**，
`UdpSender.kt` / `GamepadState.kt` / `GyroAim.kt` 未改动；本轮只替换绘制层。
多人同按的逻辑此前已在实机验证过，本次无回归。

**阴影矩形框缺陷（已修复）**：肩键等圆角矩形按钮在点击后外围出现直角边框。
根因为阴影位图留白 2.2×模糊半径不足，高斯尾部被位图边界裁断。
改为 `shadowPad = blur × 4` 后放大复检，四向衰减自然、无直边；
修复前后渲染性能：90th 15ms → 12ms，UI 线程慢 6 次 → 1 次。

**第二处阴影框（框架控件）**：连接栏 `elevation=5dp`、编辑栏 `elevation=8dp` 会在下方
投出矩形阴影带；连接按钮/设置面板按钮为 MaterialButton 默认抬升；齿轮键为方形描边背景。
已全部移除（`elevation=0` + `stateListAnimator=null`，齿轮键改无描边圆形）。
复检：长直边检测在画布上仅剩屏幕顶边；工具栏下方无阴影带，设置面板按钮为纯描边无阴影。

**第三处：胶囊/轨道的「按下内阴影」矩形裁剪**
按下（以及 LT/RT 常驻）的内阴影原先用 `canvas.clipRect` 裁剪，
矩形四角会连带涂上阴影，胶囊按钮与竖向轨道就会露出一圈直角方块。
改为 `clipPath.addRoundRect(rect, radius)` 裁剪后，按压态严格贴合圆角。
逐键复检：LB / RB / BACK / LT / RT / ☰ 按压态放大后均无直边或直角。

---

## 5. 无法执行的验证（环境限制）

| 项 | 原因 | 替代验证 | 风险 |
| --- | --- | --- | --- |
| 长时间稳定性（>1h） | 未执行 | 建议后续加 4 人 + 2 小时 soak test | — |
| 4 人并发 | 未执行 | 需 4 个模拟客户端（可扩展 e2e） | — |
| Home 键游戏内行为 | 取决于游戏读取方式 | 已用 `XInputGetStateEx` 证明位送达驱动 | — |
| VGP2 协议 | 尚未实现（设计见 `docs/PROTOCOL.md`） | 提供 `protocol/test_vectors_v2.json` 作为验收基准 | — |
| 长时间稳定性（>1h） | 未执行 | 建议后续加 4 人 + 2 小时 soak test | — |
| 4 人并发 | 未执行 | 需 4 个模拟客户端（可扩展 e2e） | — |

---

## 6. 复现方式

```bash
# 1. 依赖
pip install -r requirements.txt          # 接收端：vgamepad
pip install -r requirements-game.txt     # 测试游戏：pygame

# 2. 协议一致性
python tools/test_protocol.py

# 3. 按键边缘检测
python tools/test_gamepad.py

# 4. 端到端（需已安装 ViGEmBus；会占用 UDP 26761）
python tools/e2e_test.py

# 5. Android 构建
cd android && gradle assembleDebug

# 6. C# 构建与运行（需 .NET 8 SDK）
cd server-csharp
dotnet build -c Release
bin/Release/net8.0/VirtualGamepadServer.exe 26799

# 7. C# 端到端（另开一个终端；会占用 UDP 26761）
python tools/verify_csharp.py 26799

# 8. 客户端异常断开回归（对 C# 或 Python 接收端均可）
python tools/verify_udp_reset.py 26799 "C# 接收端"
```

> 本机 SDK 布置在 `_buildenv/dotnet8`（免管理员权限，不影响系统运行时）：
> `export DOTNET_ROOT=<repo>/_buildenv/dotnet8` 后即可使用 `dotnet8/dotnet.exe`。

---

## 7. 结论

- 三端（Android / Python / C#）均已编译通过；C# 首次完成真实 XInput 端到端验证（33/33）。
- 本轮新增并修复两个由真实断线场景暴露的缺陷（A-27 Python 进程退出、A-28 C# 空转刷屏），
  两者都有对应的自动化回归脚本。
- 未发现因本轮修改导致的功能回退：协议 6/6、按键 6/6、Python e2e 8/8 全部重跑通过。
