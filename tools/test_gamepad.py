#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""测试游戏输入层的按键边缘检测。

重点回归 A-07：look_test.Pad.just_pressed 曾使用可变默认参数，且每次调用把
基准改成当前帧，导致同一帧内第二次查询必然返回 False（同时按两个键只认第一个）。

运行：
    python tools/test_gamepad.py
"""
import inspect
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "game"))

import look_test  # noqa: E402
import twin_stick  # noqa: E402


class LookTestEdgeTest(unittest.TestCase):

    def _pad(self):
        # 跳过 __init__（避免加载 xinput dll），只保留边缘检测需要的字段
        p = look_test.Pad.__new__(look_test.Pad)
        p.buttons = frozenset()
        p._prev_buttons = frozenset()
        return p

    def _frame(self, p, names):
        p._prev_buttons = p.buttons
        p.buttons = frozenset(names)

    def test_signature_has_no_mutable_default(self):
        params = list(inspect.signature(look_test.Pad.just_pressed).parameters.values())
        self.assertEqual([x.name for x in params], ["self", "name"])
        for prm in params:
            self.assertIs(prm.default, inspect.Parameter.empty, prm.name)

    def test_two_keys_same_frame_both_detected(self):
        p = self._pad()
        self._frame(p, {"A", "B"})
        # 原实现：第一次查询后基准被更新，第二次必然 False
        self.assertTrue(p.just_pressed("A"))
        self.assertTrue(p.just_pressed("B"))

    def test_held_keys_not_repeated(self):
        p = self._pad()
        self._frame(p, {"A"})
        self.assertTrue(p.just_pressed("A"))
        self._frame(p, {"A"})
        self.assertFalse(p.just_pressed("A"))

    def test_release_then_press_again(self):
        p = self._pad()
        self._frame(p, {"A"})
        self.assertTrue(p.just_pressed("A"))
        self._frame(p, set())
        self.assertFalse(p.just_pressed("A"))
        self._frame(p, {"A"})
        self.assertTrue(p.just_pressed("A"))

    def test_instances_do_not_share_state(self):
        p1, p2 = self._pad(), self._pad()
        self._frame(p1, {"A"})
        self._frame(p2, set())
        self.assertTrue(p1.just_pressed("A"))
        self.assertFalse(p2.just_pressed("A"))


class TwinStickEdgeTest(unittest.TestCase):
    """对照实现：基于 poll() 前移 cur/prev，本来就是正确的。"""

    def _pad(self):
        p = twin_stick.Pad.__new__(twin_stick.Pad)
        p.cur = twin_stick.Pad.blank()
        p.prev = twin_stick.Pad.blank()
        return p

    def test_edge_detection(self):
        p = self._pad()
        p.prev = p.cur
        p.cur = dict(p.cur, buttons=frozenset({"A", "B"}))
        self.assertTrue(p.just_pressed("A"))
        self.assertTrue(p.just_pressed("B"))
        self.assertFalse(p.just_pressed("X"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
