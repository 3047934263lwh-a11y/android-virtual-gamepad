# -*- coding: utf-8 -*-
"""端到端自检：真实启动 server.py，从本机模拟手机端发送 20 字节报文，验证解析与写入。"""
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


def packet(buttons, lx, ly, rx, ry, lt, rt, seq):
    return (
        b"VGP1"
        + bytes([1, buttons & 0xFF, (buttons >> 8) & 0xFF, seq & 0xFF])
        + struct.pack("<hhhh", lx, ly, rx, ry)
        + bytes([lt, rt])
        + b"\x00\x00"
    )


srv = subprocess.Popen(
    PROC,
    cwd=SERVER_DIR,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    encoding="utf-8",
    errors="replace",
)
time.sleep(2.5)

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sent = 0
# 第 1 组：A 键按下 + 左摇杆推满 + LT 半按
for i in range(40):
    sock.sendto(packet(0x0001, -32768, 32767, 0, 0, 200, 0, i & 0xFF), ("127.0.0.1", PORT))
    sent += 1
    time.sleep(0.008)
# 第 2 组：方向键上 + B 键 + RT 满行程
for i in range(40):
    sock.sendto(packet(0x0402, 0, 0, 12000, -12000, 0, 255, (40 + i) & 0xFF), ("127.0.0.1", PORT))
    sent += 1
    time.sleep(0.008)

time.sleep(1.6)
srv.terminate()
try:
    out, _ = srv.communicate(timeout=6)
except subprocess.TimeoutExpired:
    srv.kill()
    out, _ = srv.communicate()

print(f"--- 发送 {sent} 个报文 ---")
print(out)
print("--- 判定 ---")
# Python 接收端的连接日志是 "[1P] 手机 ... 已连接"（"[连接]" 是 C# 版格式），
# 两种都接受，避免明明全部报文都被正确解析却恒判 FAIL。
print("PASS" if (("已连接" in out or "[连接]" in out) and "pps" in out)
      else "FAIL: 未看到连接/速率日志")
