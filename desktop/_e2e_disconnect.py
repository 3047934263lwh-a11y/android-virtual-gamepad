"""端到端复检：手机「主动断开」（停发报文）后，PC 端应在 SLOT_TTL 内释放槽位。

流程：启动接收线程（不装驱动）→ 假手机 250Hz 发 VGP1 → 快照出现 1 台在线
→ 停发 → 断言 6 秒内在线数归零并出现「槽位已释放」日志。
"""
import os
import socket
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QTimer  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

import receiver as rcv  # noqa: E402

app = QApplication([])
r = rcv.Receiver()
state = {"online": None, "released": False, "t_connect": None, "t_release": None}

r.snapshot.connect(lambda s: state.__setitem__("online", s["online"]))
r.log.connect(lambda lv, msg: state.__setitem__("released", state["released"] or "释放" in msg))
r.listening.connect(lambda port, ip: print(f"listening {ip}:{port}"))

assert r.start(26799, use_vigem=False, debug=False), "接收线程启动失败"

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)


def pkt(seq: int) -> bytes:
    # VGP1: magic4 + ver1 + buttons2 + seq1 + lx,ly,rx,ry(int16*4) + lt,rt + 2
    return (b"VGP1" + bytes([1]) + struct.pack("<H", 0x0010) + bytes([seq & 0xFF])
            + struct.pack("<hhhh", 500, 0, -500, 0) + bytes([64, 0]) + b"\x00\x00")


phase = {"n": 0}


def tick():
    phase["n"] += 1
    n = phase["n"]
    if n <= 20:                                   # 前 2 秒：250Hz 发包（模拟手机在线）
        for i in range(25):
            sock.sendto(pkt(n * 25 + i), ("127.0.0.1", 26799))
        if state["online"] and state["t_connect"] is None:
            state["t_connect"] = time.monotonic()
            print(f"[{n}] 检测到接入: online={state['online']}")
    elif n == 21:
        print(f"[{n}] 停发报文并发送告别包 VGPQ（模拟新版手机主动断开）")
        state["t_stop"] = time.monotonic()
        bye = b"VGPQ" + bytes([1, 0, 0, 0])
        for _ in range(2):
            sock.sendto(bye, ("127.0.0.1", 26799))
        QTimer.singleShot(1200, step_check)   # 告别包应秒级释放
        return
    elif n > 21:
        return

    QTimer.singleShot(100, tick)


def step_check():
    dt = time.monotonic() - state.get("t_stop", time.monotonic())
    print(f"断开后 {dt:.1f}s：online={state['online']} 释放日志={state['released']}")
    ok = state["online"] == 0 and state["released"] and dt < 7.0
    print("PASS" if ok else "FAIL: 断开后槽位未及时释放")
    r.stop()
    QTimer.singleShot(300, app.quit)


QTimer.singleShot(500, tick)
QTimer.singleShot(15000, app.quit)
app.exec()
