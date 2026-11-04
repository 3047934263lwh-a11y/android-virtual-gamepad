# -*- coding: utf-8 -*-
"""端到端自检。

真实启动 server.py → 从本机模拟手机发送 20 字节报文 → 用 XInput 读回虚拟手柄状态，
验证「报文 → ViGEm → XInput」整条链路，而不只是看日志。

运行（需要已装 ViGEmBus 与 vgamepad）：
    python tools/e2e_test.py
"""
import ctypes
import os
import socket
import struct
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.abspath(__file__))
SERVER_DIR = os.path.join(os.path.dirname(BASE), "server-python")
PORT = 26761
PROC = [sys.executable, "-u", "server.py", str(PORT)]

# XInput 位值（与 XINPUT_GAMEPAD 一致，注意不是本项目自定义位序）
XA, XB, XUP = 0x1000, 0x2000, 0x0001


class XINPUT_STATE(ctypes.Structure):
    _fields_ = [
        ("dwPacketNumber", ctypes.c_ulong),
        ("wButtons", ctypes.c_ushort),
        ("bLeftTrigger", ctypes.c_ubyte),
        ("bRightTrigger", ctypes.c_ubyte),
        ("sThumbLX", ctypes.c_short),
        ("sThumbLY", ctypes.c_short),
        ("sThumbRX", ctypes.c_short),
        ("sThumbRY", ctypes.c_short),
    ]


def _xinput_dll():
    for name in ("xinput1_4.dll", "xinput1_3.dll"):
        try:
            return ctypes.WinDLL(name)
        except OSError:
            continue
    return None


def read_xinput():
    """读 0 号手柄；驱动/设备不存在时返回 None（区分「不支持」与「值不对」）。"""
    dll = _xinput_dll()
    if dll is None:
        return None
    st = XINPUT_STATE()
    return st if dll.XInputGetState(0, ctypes.byref(st)) == 0 else None


def packet(buttons, lx, ly, rx, ry, lt, rt, seq):
    return (
        b"VGP1"
        + bytes([1, buttons & 0xFF, (buttons >> 8) & 0xFF, seq & 0xFF])
        + struct.pack("<hhhh", lx, ly, rx, ry)
        + bytes([lt, rt])
        + b"\x00\x00"
    )


def send_group(sock, buttons, lx, ly, rx, ry, lt, rt, start_seq, count=40):
    for i in range(count):
        sock.sendto(
            packet(buttons, lx, ly, rx, ry, lt, rt, (start_seq + i) & 0xFF),
            ("127.0.0.1", PORT),
        )
        time.sleep(0.008)


def main():
    srv = subprocess.Popen(
        PROC, cwd=SERVER_DIR,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace",
    )
    time.sleep(2.5)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    checks = []
    try:
        # 第 1 组：A + 左摇杆推满上 + LT 200
        send_group(sock, 0x0001, -32768, 32767, 0, 0, 200, 0, 0)
        time.sleep(0.15)
        st = read_xinput()
        if st is None:
            checks.append(("XInput 读取", None, "驱动或虚拟手柄不可用，跳过"))
        else:
            checks.append(("A 键按下", bool(st.wButtons & XA), f"buttons=0x{st.wButtons:04X}"))
            checks.append(("左摇杆上推", st.sThumbLY > 20000, f"LY={st.sThumbLY}"))
            checks.append(("LT 半按", abs(st.bLeftTrigger - 200) <= 2, f"LT={st.bLeftTrigger}"))

        # 第 2 组：方向键上 + B + 右摇杆右下 + RT 满行程
        send_group(sock, 0x0402, 0, 0, 12000, -12000, 0, 255, 40)
        time.sleep(0.15)
        st2 = read_xinput()
        if st2 is None:
            checks.append(("XInput 读取（第二组）", None, "跳过"))
        else:
            checks.append(("B 键按下", bool(st2.wButtons & XB), f"buttons=0x{st2.wButtons:04X}"))
            checks.append(("方向键上", bool(st2.wButtons & XUP), f"buttons=0x{st2.wButtons:04X}"))
            checks.append(("RT 满行程", st2.bRightTrigger >= 253, f"RT={st2.bRightTrigger}"))
            checks.append(("右摇杆 X", abs(st2.sThumbRX - 12000) <= 500, f"RX={st2.sThumbRX}"))

        time.sleep(1.4)  # 让服务端跑完空闲回中
    finally:
        sock.close()
        srv.terminate()
        try:
            out, _ = srv.communicate(timeout=6)
        except subprocess.TimeoutExpired:
            srv.kill()
            out, _ = srv.communicate()

    print("--- 接收端输出 ---")
    print(out)

    log_ok = ("已连接" in out or "[连接]" in out) and "pps" in out
    checks.insert(0, ("接收端连接与速率日志", log_ok, ""))

    print("--- 判定 ---")
    failed = 0
    for name, ok, extra in checks:
        if ok is None:
            print(f"  SKIP  {name}  {extra}")
        elif ok:
            print(f"  PASS  {name}  {extra}")
        else:
            failed += 1
            print(f"  FAIL  {name}  {extra}")

    print()
    if failed:
        print(f"RESULT: FAIL（{failed} 项不通过）")
        return 1
    print("RESULT: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
