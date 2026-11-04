#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""协议一致性测试。

用 `protocol/test_vectors.json` 这一份**三端共享**的测试向量，同时校验：
  1. Kotlin 端（UdpSender.serialize）的写入顺序 → 字节逐位一致
  2. Python 端 server.parse() 的解析结果 → 字段逐个一致
  3. C# 端 GamepadProtocol.TryParse 的位运算 → 字段逐个一致

运行：
    python tools/test_protocol.py

说明：C# 端在无 .NET SDK 的机器上无法编译，本测试用等价的位运算重建其解析
逻辑；它验证的是"协议实现是否一致"，不能替代 C# 的真实编译。
"""
import json
import os
import struct
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VEC_PATH = os.path.join(ROOT, "protocol", "test_vectors.json")
with open(VEC_PATH, encoding="utf-8") as fh:
    VEC = json.load(fh)

sys.path.insert(0, os.path.join(ROOT, "server-python"))
try:
    import server as py_server  # 顶层会 import vgamepad
except (SystemExit, ImportError):
    py_server = None


def kotlin_pack(f):
    """还原 UdpSender.serialize()：magic + ver + buttons(LE) + seq + 4×int16 + 2×uint8 + reserved。"""
    b = f["buttons"] & 0xFFFF
    return (
        b"VGP1"
        + bytes([1, b & 0xFF, (b >> 8) & 0xFF, f["seq"] & 0xFF])
        + struct.pack("<hhhh", f["lx"], f["ly"], f["rx"], f["ry"])
        + bytes([f["lt"] & 0xFF, f["rt"] & 0xFF])
        + b"\x00\x00"
    )


def csharp_parse(buf):
    """还原 GamepadProtocol.TryParse() 的位运算与严格长度检查。"""
    if len(buf) != 20 or buf[:4] != b"VGP1" or buf[4] != 1:
        return None
    return {
        "buttons": buf[5] | (buf[6] << 8),
        "seq": buf[7],
        "lx": struct.unpack_from("<h", buf, 8)[0],
        "ly": struct.unpack_from("<h", buf, 10)[0],
        "rx": struct.unpack_from("<h", buf, 12)[0],
        "ry": struct.unpack_from("<h", buf, 14)[0],
        "lt": buf[16],
        "rt": buf[17],
    }


class ProtocolVectorTest(unittest.TestCase):

    def test_vector_file_meta(self):
        self.assertEqual(VEC["protocol"], "VGP1")
        self.assertEqual(VEC["packet_size"], 20)
        self.assertGreaterEqual(len(VEC["cases"]), 5)

    def test_every_case_is_20_bytes(self):
        for c in VEC["cases"]:
            self.assertEqual(len(bytes.fromhex(c["hex"])), 20, c["name"])

    def test_kotlin_serialize_matches_vectors(self):
        for c in VEC["cases"]:
            self.assertEqual(kotlin_pack(c["fields"]).hex(), c["hex"], c["name"])

    @unittest.skipIf(py_server is None, "需要 vgamepad（pip install -r requirements.txt）")
    def test_python_parse_matches_vectors(self):
        for c in VEC["cases"]:
            got = py_server.parse(bytes.fromhex(c["hex"]))
            self.assertIsNotNone(got, c["name"])
            f = c["fields"]
            self.assertEqual(
                got,
                (f["buttons"], f["lx"], f["ly"], f["rx"], f["ry"],
                 f["lt"], f["rt"], f["seq"]),
                c["name"],
            )

    def test_csharp_logic_matches_vectors(self):
        for c in VEC["cases"]:
            got = csharp_parse(bytes.fromhex(c["hex"]))
            self.assertIsNotNone(got, c["name"])
            f = c["fields"]
            for key in ("buttons", "lx", "ly", "rx", "ry", "lt", "rt", "seq"):
                self.assertEqual(got[key], f[key], f"{c['name']}.{key}")

    @unittest.skipIf(py_server is None, "需要 vgamepad")
    def test_reject_malformed_packets(self):
        good = bytes.fromhex(VEC["cases"][0]["hex"])
        bad = {
            "短 1 字节": good[:19],
            "超长 1 字节": good + b"\x00",
            "空包": b"",
            "magic 错误": b"VGP2" + good[4:],
            "版本错误": good[:4] + b"\x02" + good[5:],
            "只有 magic": b"VGP1",
        }
        for name, pkt in bad.items():
            self.assertIsNone(py_server.parse(pkt), name)


if __name__ == "__main__":
    unittest.main(verbosity=2)
