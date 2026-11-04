# 测试报告

| 项 | 内容 |
| --- | --- |
| 日期 | 2026-11-04 |
| 基线 | v1.9.9（commit `982a111`）→ 本次修复后 |
| 操作系统 | Windows 10 19045 x64 |
| Python | 3.13.14，`vgamepad` 可用，`pygame` 2.6.1 |
| JDK / Gradle | JDK 17.0.21 / Gradle 8.9（离线构建） |
| .NET | **仅 Runtime 10.0.7，未安装 SDK** |

---

## 1. 执行结果汇总

| # | 测试 | 命令 | 结果 |
| --- | --- | --- | --- |
| 1 | Python 语法编译 | `python -m py_compile ...`（8 文件） | **PASS** |
| 2 | 协议一致性（VGP1 向量） | `python tools/test_protocol.py` | **PASS 6/6** |
| 3 | 按键边缘检测 | `python tools/test_gamepad.py` | **PASS 6/6** |
| 4 | 端到端 + 真实 XInput | `python tools/e2e_test.py` | **PASS 8/8** |
| 5 | Android Debug 构建 | `gradle --offline assembleDebug` | **PASS**（BUILD SUCCESSFUL） |
| 6 | C# 构建 | `dotnet build` | **无法执行**（No .NET SDKs were found） |

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
- 退出时 `unplug()` 不抛异常

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

## 5. 无法执行的验证（环境限制）

| 项 | 原因 | 替代验证 | 风险 |
| --- | --- | --- | --- |
| C# 端编译 | 本机 `dotnet --info` 显示 **SDKs installed 为空**，只有 Runtime | ① 用官方示例核对了 `FeedbackReceived` / `e.LargeMotor` / `e.SmallMotor` 签名；② `test_protocol.py` 用位运算复刻其解析逻辑并断言 | **中**：新增回传代码未经过编译器检查，需在有 SDK 的机器 `dotnet build` |
| Android 实机安装 | adb `devices` 为空（USB 复合设备存在但无 ADB 接口） | Gradle 编译通过；上一版本已实机验证 | **低**：本次仅改一行长度校验 |
| 真实手机链路 | 同上 | e2e 用本机模拟客户端 + 真实 ViGEm/XInput | **低** |
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

# 6. C# 构建（需 .NET 8 SDK）
cd server-csharp && dotnet build
```

---

## 7. 结论

- 本轮修改的 Python / Kotlin 代码均通过可执行的验证（语法、单测、端到端）。
- **C# 改动未经编译验证**，这是本轮最大的未闭合项，必须在有 .NET SDK 的环境补验。
- 未发现因本轮修改导致的功能回退：e2e 的 8 项断言覆盖了按键、摇杆、扳机与空闲回中。
