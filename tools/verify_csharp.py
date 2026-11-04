"""C# 接收端端到端验证：真实 XInput 读数 + VGPR 震动回传 + VGPP 编号 + 畸形包。

用法：先启动 VirtualGamepadServer.exe <port>，再运行本脚本 <port>。
"""
import ctypes
import socket
import struct
import sys
import time

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 26799
ADDR = ("127.0.0.1", PORT)

BTN = {
    "A": 1 << 0, "B": 1 << 1, "X": 1 << 2, "Y": 1 << 3,
    "LB": 1 << 4, "RB": 1 << 5, "BACK": 1 << 6, "START": 1 << 7,
    "L3": 1 << 8, "R3": 1 << 9,
    "UP": 1 << 10, "DOWN": 1 << 11, "LEFT": 1 << 12, "RIGHT": 1 << 13,
    "GUIDE": 1 << 14,
}
# XInput wButtons 位（不是本项目协议位）
XI = {
    "UP": 0x0001, "DOWN": 0x0002, "LEFT": 0x0004, "RIGHT": 0x0008,
    "START": 0x0010, "BACK": 0x0020, "L3": 0x0040, "R3": 0x0080,
    "LB": 0x0100, "RB": 0x0200, "GUIDE": 0x0400,
    "A": 0x1000, "B": 0x2000, "X": 0x4000, "Y": 0x8000,
}


class XInputState(ctypes.Structure):
    _fields_ = [
        ("packet", ctypes.c_ulong), ("buttons", ctypes.c_ushort),
        ("lt", ctypes.c_ubyte), ("rt", ctypes.c_ubyte),
        ("lx", ctypes.c_short), ("ly", ctypes.c_short),
        ("rx", ctypes.c_short), ("ry", ctypes.c_short),
    ]


class XInputVibration(ctypes.Structure):
    _fields_ = [("left", ctypes.c_ushort), ("right", ctypes.c_ushort)]


xinput = ctypes.WinDLL("xinput1_4.dll")


def frame(buttons=0, lx=0, ly=0, rx=0, ry=0, lt=0, rt=0, seq=0):
    return (b"VGP1" + bytes([1, buttons & 0xFF, (buttons >> 8) & 0xFF, seq & 0xFF])
            + struct.pack("<hhhh", lx, ly, rx, ry) + bytes([lt, rt]) + b"\x00\x00")


def read(slot=0, retries=6):
    for _ in range(retries):
        st = XInputState()
        if xinput.XInputGetState(slot, ctypes.byref(st)) == 0:
            return st
        time.sleep(0.1)
    return None


def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", 26761))
    sock.settimeout(0.2)

    results = []

    def check(name, ok, detail=""):
        results.append((name, bool(ok), detail))
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  {detail}" if detail else ""), flush=True)

    # 1) 首次发包 → 虚拟手柄应被 XInput 枚举
    sock.sendto(frame(seq=1), ADDR)
    st = read()
    check("XInput 枚举到虚拟手柄", st is not None,
          f"buttons=0x{st.buttons:04X}" if st else "无设备")

    # 2) 单键映射：逐键验证协议位 → XInput 位
    print("\n-- 逐键映射 --", flush=True)
    seq = 2
    for name in ["A", "B", "X", "Y", "LB", "RB", "BACK", "START",
                 "L3", "R3", "UP", "DOWN", "LEFT", "RIGHT"]:
        sock.sendto(frame(buttons=BTN[name], seq=seq), ADDR)
        seq = (seq + 1) & 0xFF
        time.sleep(0.06)
        st = read()
        got = st.buttons if st else 0
        # 只断言目标位已置位、其余位未置位
        ok = (got & XI[name]) != 0 and (got & ~XI[name]) == 0
        check(f"{name:<6} → 0x{XI[name]:04X}", ok, f"实际 0x{got:04X}")

    # 3) 摇杆与扳机
    print("\n-- 轴与扳机 --", flush=True)
    sock.sendto(frame(lx=-32768, ly=32767, rx=32767, ry=-32768, lt=200, rt=255, seq=seq), ADDR)
    seq = (seq + 1) & 0xFF
    time.sleep(0.08)
    st = read()
    if st:
        check("左摇杆 X 下界 -32768", st.lx == -32768, f"lx={st.lx}")
        check("左摇杆 Y 上推 +32767", st.ly == 32767, f"ly={st.ly}")
        check("右摇杆 X 上界 +32767", st.rx == 32767, f"rx={st.rx}")
        check("右摇杆 Y 下推 -32768", st.ry == -32768, f"ry={st.ry}")
        check("左扳机 200", st.lt == 200, f"lt={st.lt}")
        check("右扳机 255", st.rt == 255, f"rt={st.rt}")

    # 4) 组合键（多键同时按下不丢位）
    print("\n-- 组合键 --", flush=True)
    combo = BTN["A"] | BTN["B"] | BTN["LB"] | BTN["UP"] | BTN["START"]
    sock.sendto(frame(buttons=combo, seq=seq), ADDR)
    seq = (seq + 1) & 0xFF
    time.sleep(0.08)
    st = read()
    want = XI["A"] | XI["B"] | XI["LB"] | XI["UP"] | XI["START"]
    check("A+B+LB+UP+START 同时按", st is not None and st.buttons == want,
          f"实际 0x{st.buttons:04X} 期望 0x{want:04X}")

    # 5) VGPP 玩家编号（周期重发每秒一次，等 1.5s）
    print("\n-- 反向通道 --", flush=True)
    vgpp = None
    deadline = time.time() + 2.5
    while time.time() < deadline:
        try:
            data, _ = sock.recvfrom(64)
        except socket.timeout:
            continue
        if data[:4] == b"VGPP" and len(data) == 8:
            vgpp = data
            break
    check("收到 VGPP 编号包", vgpp is not None,
          f"{vgpp.hex()} player={vgpp[5] if vgpp else '-'}" if vgpp else "超时")
    if vgpp:
        check("编号为 1P", vgpp[5] == 1, f"player={vgpp[5]}")

    # 6) VGPR 震动回传：用 XInputSetState 触发虚拟手柄力反馈
    sock.sendto(frame(seq=seq), ADDR)          # 确保 _lastClient 指向本 socket
    time.sleep(0.15)
    while True:                                 # 清空积压的 VGPP
        try:
            sock.recvfrom(64)
        except socket.timeout:
            break
    vib = XInputVibration(40000, 20000)
    rc = xinput.XInputSetState(0, ctypes.byref(vib))
    vgpr = None
    deadline = time.time() + 2.0
    while time.time() < deadline:
        try:
            data, _ = sock.recvfrom(64)
        except socket.timeout:
            continue
        if data[:4] == b"VGPR" and len(data) == 8:
            vgpr = data
            break
    check("XInputSetState 触发 VGPR 回传", vgpr is not None,
          f"{vgpr.hex()} 大={vgpr[5]} 小={vgpr[6]}" if vgpr else "超时")
    if vgpr:
        check("震动强度非零且方向正确", vgpr[5] > 0 and vgpr[6] > 0,
              f"大={vgpr[5]} 小={vgpr[6]}")
    xinput.XInputSetState(0, ctypes.byref(XInputVibration(0, 0)))

    # 7) 畸形与超长报文：必须被丢弃且不影响链路
    print("\n-- 异常报文 --", flush=True)
    sock.sendto(frame(buttons=BTN["A"], seq=seq), ADDR)
    seq = (seq + 1) & 0xFF
    time.sleep(0.08)
    base = read().buttons if read() else 0
    bad = [
        ("19 字节短包", frame()[:19]),
        ("21 字节超长包", frame() + b"\x00"),
        ("magic 错误", b"XGP1" + frame()[4:]),
        ("版本号错误", frame()[:4] + b"\x09" + frame()[5:]),
        ("全 0 数据报", b"\x00" * 20),
        ("128 字节填充", frame(buttons=BTN["B"]) + b"\x00" * 108),
    ]
    for name, payload in bad:
        sock.sendto(payload, ADDR)
        time.sleep(0.05)
        st = read()
        got = st.buttons if st else 0
        check(f"丢弃 {name}", got == base, f"按键 0x{got:04X}（应保持 0x{base:04X}）")

    # 8) 超时回中：停发 1.5s 后所有输入应归零
    print("\n-- 超时回中 --", flush=True)
    time.sleep(1.6)
    st = read()
    if st:
        check("停发后输入回中",
              st.buttons == 0 and st.lx == 0 and st.ly == 0 and st.rx == 0 and st.ry == 0
              and st.lt == 0 and st.rt == 0,
              f"btn=0x{st.buttons:04X} lx={st.lx} ly={st.ly} lt={st.lt}")

    total = len(results)
    passed = sum(1 for _, ok, _ in results if ok)
    print(f"\n=== 合计 {passed}/{total} 通过 ===", flush=True)
    failed = [n for n, ok, _ in results if not ok]
    if failed:
        print("失败项：" + "、".join(failed), flush=True)
    sock.close()
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
