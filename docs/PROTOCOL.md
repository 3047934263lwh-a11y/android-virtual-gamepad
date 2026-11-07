# 通信协议规范

| 项 | 内容 |
| --- | --- |
| 适用端 | Android（Kotlin）/ Python 接收端 / C# 接收端 / 测试工具 |
| 当前线上版本 | **VGP1**（20 字节，无认证） |
| 规划版本 | **VGP2**（48 字节，HMAC 认证 + 重放保护 + 设备身份） |
| 字节序 | 全部小端（little-endian） |
| 共享向量 | `protocol/test_vectors.json`（VGP1，已用于自动化测试） |

> **状态说明**：VGP1 为当前实际运行协议；VGP2 是已完成的安全设计，**尚未实现**，
> 实施计划见 §5。本文档中的 VGP2 部分是可执行规范，不是已交付功能。

---

## 1. VGP1（当前线上协议）

### 1.1 报文结构（20 字节定长）

| 偏移 | 长度 | 字段 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| 0 | 4 | magic | bytes | 固定 `56 47 50 31`（"VGP1"） |
| 4 | 1 | version | uint8 | 固定 `1` |
| 5 | 2 | buttons | uint16 | 按键位掩码，见 §1.2 |
| 7 | 1 | seq | uint8 | 包序号，0–255 循环，仅用于丢包统计 |
| 8 | 2 | lx | int16 | 左摇杆 X，−32768…32767，右为正 |
| 10 | 2 | ly | int16 | 左摇杆 Y，**上为正**（手机端已完成屏幕 Y 翻转） |
| 12 | 2 | rx | int16 | 右摇杆 X |
| 14 | 2 | ry | int16 | 右摇杆 Y，上为正 |
| 16 | 1 | lt | uint8 | 左扳机 0–255，松开为 0 |
| 17 | 1 | rt | uint8 | 右扳机 0–255 |
| 18 | 2 | reserved | bytes | 置 0 |

**接收端必须严格校验 `len == 20`**，不得"取前 20 字节"。发送频率 **250 Hz**
（`UdpSender.kt` 的 `SEND_HZ`），约 5 KB/s；接收端不依赖该频率，按"排空取最新"处理任意到达速率。

### 1.2 按键位（自定义紧凑位序）

| 位 | 含义 | 位 | 含义 |
| --- | --- | --- | --- |
| 0 | A | 8 | L3（左摇杆按下） |
| 1 | B | 9 | R3（右摇杆按下） |
| 2 | X | 10 | 方向键 ↑ |
| 3 | Y | 11 | 方向键 ↓ |
| 4 | LB | 12 | 方向键 ← |
| 5 | RB | 13 | 方向键 → |
| 6 | BACK | 14 | GUIDE（Home / Xbox 键） |
| 7 | START（☰ 菜单键使用此位） | 15 | 保留，必须为 0 |

> ⚠️ 该位序**不是** `XINPUT_GAMEPAD.wButtons`（XInput 中 A=0x1000、UP=0x0001）。
> 接收端必须逐位映射，不能直接透传。

### 1.3 控制通道（8 字节定长）

`VGPR` / `VGPP` 为 PC → 手机；`VGPQ` 双向使用（见 §1.3.1）。

| 偏移 | 长度 | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | 4 | magic | `VGPR`（震动）、`VGPP`（玩家编号）或 `VGPQ`（会话结束／告别） |
| 4 | 1 | version | 固定 `1` |
| 5 | 1 | payload | VGPR：大马达 0–255；VGPP：玩家编号 1–4；VGPQ：`0` |
| 6 | 1 | payload2 | VGPR：小马达 0–255；VGPP：**主接收端填链路实测 pps（0–255），参考实现填 `0`**；VGPQ：`0` |
| 7 | 1 | reserved | `0` |

> VGPP 的第 6 字节是 VGP1 的可选扩展：`desktop/receiver.py` 会把该玩家的实收包速（pps）
> 填进去供手机状态条显示；读取端只需解析第 5 字节的玩家编号，填 0 的参考实现完全兼容。

- 手机端优先绑定 UDP 26761（占用时回退随机端口）。
- **PC 端一律回发到报文的来源端口**，不硬编码 26761。
- `VGPP` 建议每秒重发一次，避免手机端接收线程晚于首包启动而漏掉编号。

#### 1.3.1 `VGPQ` 会话结束（2026-11-06 新增）

| 方向 | 触发条件 | 接收方行为 |
| --- | --- | --- |
| 手机 → PC | 用户在 App 点「断开」，`UdpSender.sendGoodbye()` 连发 2 包 | PC 按来源 IP 立即卸载虚拟手柄、释放槽位、清空震动地址与速率窗口，**不等 TTL** |
| PC → 手机 | PC 端点 STOP / 退出监听 | 手机状态立即置「未连接」，直到再次收到 `VGPP` |

- 手机端必须在 `sender.stop()` **之后**发告别包，否则会被同一轮排空中的游戏报文覆盖
  （`latest` 每个来源只保留最新一帧）。
- 解析条件：必须在 20 字节输入报文的 `parse()` **之前**拦截，否则 8 字节控制包会被
  当成非法包丢弃。主接收端 `desktop/receiver.py` 按 `magic` 前缀匹配；
  Python / C# 参考实现进一步校验 `len == 8` 与 `version == 1`，三种实现互相兼容。
- **双向向后兼容**：旧版 PC 把 VGPQ 当未知 magic 丢弃；旧版手机忽略 PC 的 VGPQ，
  靠显示兜底超时切「未连接」。两类旧端均由 PC 侧 `SLOT_TTL_SEC = 2.0` 秒兜底释放槽位。

### 1.4 已知局限（VGP2 要解决的问题）

1. 无身份认证：局域网内任意主机可伪造输入（含伪造 VGPQ 踢人）。
2. 以 IP 识别玩家：NAT/多实例/DHCP 下身份不稳定。
3. `seq` 仅 8 位：250 Hz 下约 **1 秒**回绕，无法可靠去重与排序。
4. 无重放保护：捕获的报文可原样重发。
5. 无版本协商：版本不匹配只能静默丢弃。
6. 连接拆除是「尽力而为」：VGPQ 基于 UDP 无重传无确认，丢包时靠 2 秒 TTL 兜底。

---

## 2. VGP2 设计目标

| 目标 | 手段 |
| --- | --- |
| 设备身份 | 64 位 `deviceId`，首次启动随机生成并持久化 |
| 会话隔离 | 每次连接协商 32 位 `sessionId`，密钥由会话派生 |
| 报文认证 | HMAC-SHA256 截断 8 字节（不自行设计密码学） |
| 重放保护 | 32 位单调 `seq` + 64 包滑动窗口 |
| 安全配对 | 6 位配对码 + PBKDF2 派生 PSK，仅本地显示 |
| 密钥管理 | Android Keystore 加密存储；PC 端文件权限 600；支持撤销 |
| 向后兼容 | 接收端双栈，`--allow-legacy` 显式开启 VGP1（默认关闭） |

---

## 3. VGP2 报文格式

### 3.1 通用头（40 字节）+ MAC（8 字节）= 48 字节

| 偏移 | 长度 | 字段 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| 0 | 4 | magic | bytes | `56 47 50 32`（"VGP2"） |
| 4 | 1 | version | uint8 | `2` |
| 5 | 1 | type | uint8 | 1=INPUT，2=PAIR_REQ，3=PAIR_CONFIRM，4=HELLO，5=HELLO_ACK，6=BYE |
| 6 | 1 | flags | uint8 | bit0=请求 ACK，其余保留 |
| 7 | 1 | reserved | uint8 | `0` |
| 8 | 4 | sessionId | uint32 | 配对后由服务端分配；配对阶段为 0 |
| 12 | 4 | seq | uint32 | 单调递增，从 1 开始 |
| 16 | 8 | deviceId | uint64 | 设备身份 |
| 24 | 2 | buttons | uint16 | 同 VGP1 §1.2 |
| 26 | 2 | lx | int16 | |
| 28 | 2 | ly | int16 | |
| 30 | 2 | rx | int16 | |
| 32 | 2 | ry | int16 | |
| 34 | 1 | lt | uint8 | |
| 35 | 1 | rt | uint8 | |
| 36 | 4 | reserved2 | bytes | `0` |
| 40 | 8 | mac | bytes | 见 §3.2 |

### 3.2 MAC 计算

```
mac = HMAC-SHA256(key = sessionKey, msg = packet[0..40))[0..8]
```

- 校验时必须使用**常量时间比较**（`hmac.compare_digest` / `CryptographicOperations.FixedTimeEquals`）。
- 校验失败直接丢弃，不回复错误（避免成为反射放大/探测面）。

### 3.3 配对报文（可变长，仍以 48 字节为上限）

| type | 名称 | 布局 |
| --- | --- | --- |
| 2 | PAIR_REQ | 头 24 字节（sessionId=0, seq=1）+ 16 字节 `clientNonce` + 8 字节 MAC（用 PSK 派生前的临时密钥，见 §4.2） |
| 3 | PAIR_CONFIRM | 头 24 字节 + `clientNonce(16)` + `serverNonce(16)`? → 超出 48，改为 CONFIRM 只带 `clientNonce(8)` 截断 + `proof(8)` |
| 4 | HELLO | 头 24 字节 + `nonce(16)` + MAC(8) = 48 |
| 5 | HELLO_ACK | 头 24 字节 + `sessionId` 已在头内 + `serverNonce(16)` + MAC(8) = 48 |

> 为控制复杂度，配对阶段采用**三次握手 + 双 nonce**：
> 1. 手机 → PC：`PAIR_REQ`（deviceId, clientNonce 16B）
> 2. PC 控制台显示 6 位配对码；手机输入后本地派生 PSK，发 `PAIR_CONFIRM`
>    （deviceId, clientNonce, proof = HMAC(PSK, "confirm"|deviceId|clientNonce|serverNonce)[0..8]）
> 3. PC 验证 proof → 分配 sessionId，回 `HELLO_ACK`（sessionId, serverNonce, mac）

### 3.4 错误处理

| 情况 | 行为 |
| --- | --- |
| 长度 ≠ 48（配对包按类型另定） | 丢弃，计数 |
| magic/version 不符 | 丢弃 |
| MAC 失败 | 丢弃，计数；连续 32 次可临时封禁该源 60 秒 |
| seq 落在窗口外且更旧 | 丢弃（重放） |
| seq 已置位 | 丢弃（重复） |
| sessionId 未知/过期 | 回 `BYE`，要求重新配对 |

---

## 4. 密钥与配对

### 4.1 参数（全部使用成熟标准）

| 项 | 取值 |
| --- | --- |
| 配对码 | 6 位十进制，排除易混字符；有效期 5 分钟，一次性 |
| KDF（配对码 → PSK） | PBKDF2-HMAC-SHA256，iterations = 120000，dklen = 32 |
| KDF salt | `b"VGP2|" + deviceId.to_bytes(8, "big")` |
| 会话密钥 | HKDF-SHA256(ikm=PSK, salt=sessionId_be, info=`b"VGP2 session"`, L=32) |
| MAC | HMAC-SHA256，截断 8 字节 |
| 存储（Android） | Android Keystore 生成 AES-256-GCM 主密钥，加密 PSK 后存 SharedPreferences |
| 存储（PC） | `%LOCALAPPDATA%\VirtualGamepad\keys\<deviceId>.key`，ACL 仅当前用户 |
| 撤销 | PC 删除密钥文件；Android「忘记此电脑」删除本地记录 |

> 不自创加密算法、不使用 ECB、不使用固定 IV；MAC 校验常量时间比较。

### 4.2 配对流程（时序）

```
手机                                   PC 接收端
 |  PAIR_REQ(deviceId, clientNonce)  ->  |  生成 6 位配对码，控制台显示
 |                                      |  保存 pending(deviceId, clientNonce, code, 5min)
 |  <用户把配对码输入手机>                |
 |  PAIR_CONFIRM(deviceId, proof)    ->  |  用配对码派生 PSK，验证 proof
 |                                      |  生成 sessionId + serverNonce
 |  <- HELLO_ACK(sessionId, serverNonce, mac)
 |  INPUT(seq=1, mac)                ->  |  校验 MAC + 重放窗口
```

### 4.3 已知威胁与对策

| 威胁 | 对策 |
| --- | --- |
| 局域网嗅探 | 报文含 HMAC，但**明文传输**；敏感场景建议后续加 AES-GCM（见 §6） |
| 重放 | 32 位 seq + 64 包滑动窗口 |
| 伪造设备 | 无 PSK 无法构造有效 MAC |
| 配对码暴力破解 | 6 位 + 5 分钟 + 一次性 + 失败计数封禁 |
| 密钥泄露 | 支持撤销；Keystore 保护；不在日志中打印密钥 |

---

## 5. 实施计划（分批，保留回滚点）

| 阶段 | 内容 | 验证 |
| --- | --- | --- |
| P1 | Python 接收端实现 VGP2 解析 + 双栈（`VGP1` 仅 `--allow-legacy` 时接受） | `test_vectors_v2.json` + 负向用例 |
| P2 | C# 接收端对齐（同一套向量） | `dotnet build` + 向量测试 |
| P3 | Android 端实现配对 UI、Keystore 存储、VGP2 发送，保留 `PROTOCOL=VGP1` 编译开关 | 实机联调 + 抓包 |
| P4 | 删除 VGP1 路径（或长期保留 `--allow-legacy`） | 全量回归 |

**当前状态**：P1–P3 均未开始。原因：三端必须同时切换，且需实机验证；
当前手机 adb 离线、本机无 .NET SDK，无法完成端到端验证。贸然切换会使现有链路不可用。

---

## 6. 后续可选项

- **加密传输**：在 HMAC 基础上加 AES-256-GCM（同一 sessionKey 派生加密子密钥），
  可防嗅探内容（当前仅防篡改与伪造）。代价是每包 +16 字节 tag 与少量 CPU。
- **版本协商**：`HELLO_ACK` 返回服务端支持的最高版本，手机据此降级。
- **多播发现**：PC 周期性发 UDP 广播/组播通告，手机自动发现同一局域网内的接收端，
  省去手填 IP。

---

## 7. 测试向量

- VGP1：`protocol/test_vectors.json`（5 组，覆盖中性帧、单键、方向键+扳机、
  GUIDE/L3/R3、全键按下），由 `tools/test_protocol.py` 校验三端一致性。
- VGP2：`protocol/test_vectors_v2.json`（含固定测试密钥与期望 MAC），
  作为未来实现的验收基准；当前无实现，故不参与 CI。
