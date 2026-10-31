# -*- coding: utf-8 -*-
"""交叉验证：Kotlin 序列化 -> C# 解析 -> Python 解析，三者字节布局必须一致。"""
import struct
import sys

PACKET = 20
MAGIC_BYTES = (0x56, 0x47, 0x50, 0x31)
BTN = {
    "A": 1 << 0, "B": 1 << 1, "X": 1 << 2, "Y": 1 << 3,
    "LB": 1 << 4, "RB": 1 << 5, "BACK": 1 << 6, "START": 1 << 7,
    "L3": 1 << 8, "R3": 1 << 9, "UP": 1 << 10, "DOWN": 1 << 11,
    "LEFT": 1 << 12, "RIGHT": 1 << 13, "GUIDE": 1 << 14,
}


def kotlin_serialize(buttons, seq, lx, ly, rx, ry, lt, rt):
    """精确复刻 UdpSender.serialize() 的写入顺序与小端语义。"""
    out = bytearray()
    out += struct.pack("<I", 0x31504756)            # putInt(MAGIC)
    out += struct.pack("<B", 1)                      # put(VERSION)
    out += struct.pack("<B", buttons & 0xFF)         # put(buttons and 0xFF)
    out += struct.pack("<B", (buttons >> 8) & 0xFF)  # put(buttons ushr 8 and 0xFF)
    out += struct.pack("<B", seq & 0xFF)             # put(sequence)
    out += struct.pack("<h", lx)                     # putShort(lx)
    out += struct.pack("<h", ly)
    out += struct.pack("<h", rx)
    out += struct.pack("<h", ry)
    out += struct.pack("<B", lt & 0xFF)              # put(lt)
    out += struct.pack("<B", rt & 0xFF)              # put(rt)
    out += struct.pack("<h", 0)                      # putShort(0) reserved
    return bytes(out)


def csharp_parse(buf):
    """精确复刻 GamepadProtocol.TryParse。"""
    if len(buf) < PACKET:
        return None
    if (buf[0], buf[1], buf[2], buf[3]) != MAGIC_BYTES:
        return None
    if buf[4] != 1:
        return None
    buttons = buf[5] | (buf[6] << 8)
    seq = buf[7]
    lx = struct.unpack_from("<h", buf, 8)[0]
    ly = struct.unpack_from("<h", buf, 10)[0]
    rx = struct.unpack_from("<h", buf, 12)[0]
    ry = struct.unpack_from("<h", buf, 14)[0]
    return buttons, lx, ly, rx, ry, buf[16], buf[17], seq


def python_parse(buf):
    """复刻 server-python/server.py 的 parse()。"""
    if len(buf) < PACKET or buf[:4] != b"VGP1" or buf[4] != 1:
        return None
    buttons = buf[5] | (buf[6] << 8)
    seq = buf[7]
    lx, ly, rx, ry = struct.unpack_from("<hhhh", buf, 8)
    return buttons, lx, ly, rx, ry, buf[16], buf[17], seq


cases = [
    ("中位", 0, 0, 0, 0, 0, 0, 0, 0),
    ("全键 + 满舵", 0xFFFF, 255, -32768, 32767, -32768, 32767, 0, 255),
    ("左上推满", BTN["A"] | BTN["UP"], 7, -32768, 32767, 0, 0, 12, 200),
    ("扳机半按", BTN["LB"] | BTN["RB"], 128, 100, -100, 200, -200, 128, 64),
    ("随机边界", 0x7FFF, 254, -1, 1, 32767, -32768, 255, 1),
]

fail = 0
for name, buttons, seq, lx, ly, rx, ry, lt, rt in cases:
    pkt = kotlin_serialize(buttons, seq, lx, ly, rx, ry, lt, rt)
    assert len(pkt) == PACKET, f"{name}: 长度 {len(pkt)} != 20"
    expect = (buttons, lx, ly, rx, ry, lt, rt, seq)
    for label, fn in (("C#", csharp_parse), ("Python", python_parse)):
        got = fn(pkt)
        if got != expect:
            print(f"[FAIL] {name} / {label}\n  expect={expect}\n  got   ={got}")
            fail += 1
        else:
            print(f"[ OK ] {name:12s} / {label:6s}  {pkt.hex(' ')}")

# 反向用例：magic / 版本 / 长度 必须被拒绝
bad = [
    ("魔数错误", b"XGP1" + b"\x01" + bytes(15)),
    ("版本错误", b"VGP1" + b"\x02" + bytes(15)),
    ("长度不足", b"VGP1" + bytes(10)),
]
for label, pkt in bad:
    if csharp_parse(pkt) is not None or python_parse(pkt) is not None:
        print(f"[FAIL] 非法报文未被拒绝: {label}")
        fail += 1
    else:
        print(f"[ OK ] 正确拒绝: {label}")

print()
print("结果:", "全部通过" if fail == 0 else f"{fail} 项失败")
sys.exit(0 if fail == 0 else 1)
