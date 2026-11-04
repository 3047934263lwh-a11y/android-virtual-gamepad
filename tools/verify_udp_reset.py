"""验证接收端在「客户端异常断开」后的存活与恢复能力。

Windows 下对已关闭的 UDP 端口发包会收到 ICMP 端口不可达，
默认被翻译成 WSAECONNRESET，未处理时接收端会空转刷屏（C#）或直接退出（Python）。

用法: verify_udp_reset.py <port> [标签]
"""
import ctypes
import socket
import struct
import sys
import time

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 26799
LABEL = sys.argv[2] if len(sys.argv) > 2 else "server"
ADDR = ("127.0.0.1", PORT)

BTN_A, BTN_B = 1 << 0, 1 << 1
XI_A, XI_B = 0x1000, 0x2000


class S(ctypes.Structure):
    _fields_ = [("pkt", ctypes.c_ulong), ("btn", ctypes.c_ushort),
                ("lt", ctypes.c_ubyte), ("rt", ctypes.c_ubyte),
                ("lx", ctypes.c_short), ("ly", ctypes.c_short),
                ("rx", ctypes.c_short), ("ry", ctypes.c_short)]


def _xinput_dll():
    # 老系统可能只有 xinput1_3.dll；都没有则跳过而非崩溃
    for name in ("xinput1_4.dll", "xinput1_3.dll"):
        try:
            return ctypes.WinDLL(name)
        except OSError:
            continue
    return None


xi = _xinput_dll()


def frame(b=0, seq=0):
    return (b"VGP1" + bytes([1, b & 0xFF, (b >> 8) & 0xFF, seq & 0xFF])
            + struct.pack("<hhhh", 0, 0, 0, 0) + bytes([0, 0]) + b"\x00\x00")


def buttons():
    st = S()
    return st.btn if xi.XInputGetState(0, ctypes.byref(st)) == 0 else None


def bind():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", 26761))
    s.settimeout(0.2)
    return s


results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  {detail}" if detail else ""), flush=True)


print(f"=== {LABEL}：客户端异常断开恢复测试 ===", flush=True)

# 1) 正常连接
a = bind()
a.sendto(frame(BTN_A, 1), ADDR)
time.sleep(0.35)
got = buttons()
check("断开前按键生效", got is not None and (got & XI_A) != 0, f"0x{got:04X}" if got is not None else "无设备")

# 2) 异常断开（不通知服务端，直接关闭 socket）
a.close()
print("  → 已强制关闭客户端 socket，等待服务端向死端口发包触发 ICMP …", flush=True)
time.sleep(3.2)          # 覆盖 3 次周期性子包发送

# 3) 同一端口重新接入，服务端应仍然存活并继续处理
b = bind()
b.sendto(frame(BTN_B, 2), ADDR)
time.sleep(0.4)
got = buttons()
check("断开后服务端仍存活并可接收", got is not None and (got & XI_B) != 0,
      f"0x{got:04X}" if got is not None else "无设备/已退出")
check("重连后旧按键未残留", got is not None and (got & XI_A) == 0,
      f"0x{got:04X}" if got is not None else "-")

# 4) 反向通道仍然可用
vgpp = None
deadline = time.time() + 3.0
while time.time() < deadline:
    try:
        data, _ = b.recvfrom(64)
    except socket.timeout:
        continue
    if data[:4] in (b"VGPP", b"VGPR") and len(data) == 8:
        vgpp = data
        break
check("重连后仍收到服务端回包", vgpp is not None, vgpp.hex() if vgpp else "超时")

b.sendto(frame(0, 3), ADDR)
b.close()

passed = sum(1 for _, ok in results if ok)
print(f"=== {passed}/{len(results)} 通过 ===", flush=True)
sys.exit(0 if passed == len(results) else 1)
