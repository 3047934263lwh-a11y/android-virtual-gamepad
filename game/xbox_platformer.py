#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Xbox 手柄测试 · 2D 横版跳跃
==========================

配合虚拟 Xbox 360 手柄（手机 App + ViGEmBus），全面测试按键响应与摇杆模拟精度。
输入直接通过 XInput API 读取（与真实游戏完全同路径）， pygame 仅负责渲染。

手柄操作
--------
    左摇杆   移动（模拟量：偏移越大越快）；上推越满跳跃越高
    A        跳跃（力度随左摇杆上推量变化）
    X        二段跳（空中一次）
    B        冲刺（面朝方向爆发，0.9s 冷却）
    Y        下砸（空中向下砸，可砸碎木箱）
    RT       疾跑（模拟量：按压深度决定加速比）
    LT       缓行（模拟量：按压深度决定减速比，用于精确落点）
    LB       回到检查点
    RB       回到关卡起点
    BACK     切换输入监测面板
    START    暂停
    十字键   数字量移动（用于对比摇杆模拟量响应）

键盘兜底：←→/AD 移动，W/↑/空格 跳跃，J 二段跳，K 冲刺，S 下砸，
          Shift 疾跑（不触发 RB），Ctrl 缓行，1 回检查点（LB），2 回起点（RB），
          Tab 面板，Esc 退出

运行：python xbox_platformer.py [--selftest]
"""

import ctypes
import math
import os
import sys
import time

import pygame

# ----------------------------------------------------------------------------
# XInput 输入层（ctypes 直读，与真实游戏同路径）
# ----------------------------------------------------------------------------

XINPUT_GAMEPAD_DPAD_UP = 0x0001
XINPUT_GAMEPAD_DPAD_DOWN = 0x0002
XINPUT_GAMEPAD_DPAD_LEFT = 0x0004
XINPUT_GAMEPAD_DPAD_RIGHT = 0x0008
XINPUT_GAMEPAD_START = 0x0010
XINPUT_GAMEPAD_BACK = 0x0020
XINPUT_GAMEPAD_LEFT_THUMB = 0x0040
XINPUT_GAMEPAD_RIGHT_THUMB = 0x0080
XINPUT_GAMEPAD_LEFT_SHOULDER = 0x0100
XINPUT_GAMEPAD_RIGHT_SHOULDER = 0x0200
XINPUT_GAMEPAD_GUIDE = 0x0400
XINPUT_GAMEPAD_A = 0x1000
XINPUT_GAMEPAD_B = 0x2000
XINPUT_GAMEPAD_X = 0x4000
XINPUT_GAMEPAD_Y = 0x8000

BUTTON_NAMES = {
    0x1000: "A", 0x2000: "B", 0x4000: "X", 0x8000: "Y",
    0x0100: "LB", 0x0200: "RB", 0x0010: "START", 0x0020: "BACK",
    0x0040: "L3", 0x0080: "R3",
    0x0001: "十字上", 0x0002: "十字下", 0x0004: "十字左", 0x0008: "十字右",
    0x0400: "GUIDE",
}
HUD_BUTTONS = ["A", "B", "X", "Y", "LB", "RB", "START", "BACK", "L3", "R3",
               "十字上", "十字下", "十字左", "十字右"]

STICK_DEADZONE = 2000.0  # 原始值（约 6%），越出后重新归一化


class XInputState(ctypes.Structure):
    _fields_ = [
        ("packet", ctypes.c_ulong),
        ("wButtons", ctypes.c_ushort),
        ("left_trigger", ctypes.c_ubyte),
        ("right_trigger", ctypes.c_ubyte),
        ("thumb_lx", ctypes.c_short),
        ("thumb_ly", ctypes.c_short),
        ("thumb_rx", ctypes.c_short),
        ("thumb_ry", ctypes.c_short),
    ]


class Pad:
    """手柄输入快照：原始值 + 处理值 + 边沿检测 + 测试统计。"""

    def __init__(self):
        self.ok = False
        self._xinput = None
        try:
            self._xinput = ctypes.WinDLL("xinput1_4.dll")
            self.ok = True
        except OSError:
            try:
                self._xinput = ctypes.WinDLL("xinput1_3.dll")
                self.ok = True
            except OSError:
                pass
        self._st = XInputState()
        self.prev = self.blank()
        self.cur = self.blank()
        # 测试统计
        self.press_count = {name: 0 for name in HUD_BUTTONS}
        self.max_lx = 0.0
        self.max_ly_up = 0.0
        self.max_lt = 0
        self.max_rt = 0
        self.full_stick_time = 0.0

    @staticmethod
    def blank():
        return {
            "lx": 0.0, "ly": 0.0, "rx": 0.0, "ry": 0.0,
            "raw_lx": 0, "raw_ly": 0, "lt": 0, "rt": 0,
            "buttons": frozenset(),
        }

    @staticmethod
    def _stick(raw):
        """死区处理 + 重新归一化，保证满偏仍能到 1.0。"""
        a = abs(raw)
        if a <= STICK_DEADZONE:
            return 0.0
        return math.copysign((a - STICK_DEADZONE) / (32767.0 - STICK_DEADZONE), raw)

    def poll(self, dt):
        self.prev = self.cur
        if not self.ok:
            self.cur = self.blank()
            return
        rc = self._xinput.XInputGetState(0, ctypes.byref(self._st))
        if rc != 0:
            self.cur = self.blank()
            return
        g = self._st
        # XInput 约定：摇杆上推为正。手机端已按此约定发送，这里严禁再取反
        # （此前多取反一次导致"上推变下推"，跳跃力度与 HUD 全部颠倒）
        raw_lx, raw_ly = g.thumb_lx, g.thumb_ly
        self.cur = {
            "lx": self._stick(raw_lx),
            "ly": self._stick(raw_ly),
            "rx": self._stick(g.thumb_rx),
            "ry": self._stick(g.thumb_ry),
            "raw_lx": raw_lx,
            "raw_ly": raw_ly,
            "lt": g.left_trigger,
            "rt": g.right_trigger,
            "buttons": frozenset(
                name for bit, name in BUTTON_NAMES.items() if g.wButtons & bit
            ),
        }
        # 统计
        for name in self.cur["buttons"]:
            if name in self.press_count and name not in self.prev["buttons"]:
                self.press_count[name] += 1
        self.max_lx = max(self.max_lx, abs(self.cur["lx"]))
        self.max_ly_up = max(self.max_ly_up, max(0.0, self.cur["ly"]))
        self.max_lt = max(self.max_lt, self.cur["lt"])
        self.max_rt = max(self.max_rt, self.cur["rt"])
        if math.hypot(self.cur["lx"], self.cur["ly"]) > 0.97:
            self.full_stick_time += dt

    def pressed(self, name):
        return name in self.cur["buttons"]

    def just_pressed(self, name):
        return name in self.cur["buttons"] and name not in self.prev["buttons"]

    def just_released(self, name):
        return name not in self.cur["buttons"] and name in self.prev["buttons"]


class Keyboard:
    """键盘兜底：结构 与 Pad 一致，保证无手柄也能玩。"""

    def __init__(self):
        self.ok = True
        self.prev = Pad.blank()
        self.cur = Pad.blank()
        self.press_count = {name: 0 for name in HUD_BUTTONS}
        self.max_lx = self.max_ly_up = 0.0
        self.max_lt = self.max_rt = 0
        self.full_stick_time = 0.0

    def poll(self, dt):
        self.prev = self.cur
        k = pygame.key.get_pressed()
        lx = (1.0 if k[pygame.K_RIGHT] or k[pygame.K_d] else 0.0) - \
             (1.0 if k[pygame.K_LEFT] or k[pygame.K_a] else 0.0)
        ly = (1.0 if k[pygame.K_UP] or k[pygame.K_w] else 0.0) - \
             (1.0 if k[pygame.K_DOWN] or k[pygame.K_s] else 0.0)
        buttons = set()
        if k[pygame.K_SPACE]:
            buttons.add("A")
        if k[pygame.K_j]:
            buttons.add("X")
        if k[pygame.K_k]:
            buttons.add("B")
        # Shift 只做疾跑（rt）：不要再 add("RB")——RB 在游戏里是"回关卡起点"，
        # 以前每按一次 Shift 疾跑都会被瞬移回起点。回检查点/起点改绑 1/2（与文档一致）。
        if k[pygame.K_1]:
            buttons.add("LB")
        if k[pygame.K_2]:
            buttons.add("RB")
        if k[pygame.K_TAB]:
            buttons.add("BACK")
        if k[pygame.K_RETURN]:
            buttons.add("START")
        self.cur = {
            "lx": lx, "ly": ly, "rx": 0.0, "ry": 0.0,
            "raw_lx": int(lx * 32767), "raw_ly": int(ly * 32767),
            "lt": 255 if k[pygame.K_LCTRL] else 0,
            "rt": 255 if k[pygame.K_LSHIFT] else 0,
            "buttons": frozenset(buttons),
        }

    def pressed(self, name):
        return name in self.cur["buttons"]

    def just_pressed(self, name):
        return name in self.cur["buttons"] and name not in self.prev["buttons"]

    def just_released(self, name):
        return name not in self.cur["buttons"] and name in self.prev["buttons"]


# ----------------------------------------------------------------------------
# 关卡数据
# ----------------------------------------------------------------------------
# 平台: (x, y, w, h)  y 为顶面
# 移动平台: (x, y, w, h, dx, dy, period)
# 钉子: (x, y, w, h)   木箱: (x, y)   金币: (x, y)   检查点: (x)   终点: (x, y)

LEVELS = [
    {
        "name": "第 1 关 · 基础响应",
        "width": 3200,
        "spawn": (120, 560),
        "ground": [(0, 640, 900, 80), (1020, 640, 700, 80), (1840, 640, 1360, 80)],
        "platforms": [
            (560, 500, 200, 22), (900, 400, 180, 22), (1240, 310, 170, 22),
            (1600, 380, 150, 22), (2050, 470, 170, 22), (2320, 350, 170, 22),
        ],
        "moving": [],
        "spikes": [(1596, 616, 244, 24)],
        "crates": [],
        "coins": [(660, 440), (990, 340), (1325, 250), (1675, 320), (2135, 410),
                  (2405, 290), (2700, 560), (2950, 560)],
        "checkpoints": [1500],
        "goal": (3080, 560),
        "hint": "左摇杆推得越满跑得越快；上推越满 + A，跳得越高",
    },
    {
        "name": "第 2 关 · 模拟量校准",
        "width": 3600,
        "spawn": (120, 560),
        "ground": [(0, 640, 1200, 80), (2360, 640, 1240, 80)],
        "platforms": [
            (700, 585, 130, 22), (940, 550, 130, 22), (1180, 508, 130, 22),
            (1420, 458, 130, 22), (1660, 400, 130, 22), (1900, 334, 150, 22),
            (2450, 520, 180, 22), (2760, 430, 180, 22), (3080, 340, 180, 22),
        ],
        "moving": [(2160, 560, 150, 20, 0, -170, 3.2)],
        "spikes": [(1200, 616, 1160, 24)],
        "crates": [],
        "coins": [(765, 530), (1005, 495), (1245, 452), (1485, 402), (1725, 344),
                  (1975, 278), (2235, 430), (2540, 460), (2850, 370), (3170, 280)],
        "checkpoints": [1500, 2400],
        "goal": (3440, 560),
        "hint": "台阶标注了所需跳跃力度：上推越满跳越高，A 键只是触发",
    },
    {
        "name": "第 3 关 · 综合测试",
        "width": 4000,
        "spawn": (120, 560),
        "ground": [(0, 640, 700, 80), (1240, 640, 560, 80), (2280, 640, 1720, 80)],
        "platforms": [
            (820, 520, 160, 22), (1060, 470, 150, 22), (1900, 520, 160, 22),
            (2560, 500, 170, 22), (2900, 410, 170, 22), (3240, 320, 170, 22),
        ],
        "moving": [(1420, 560, 150, 20, 260, 0, 3.4), (2960, 200, 140, 20, 0, 150, 2.8)],
        "spikes": [(700, 616, 540, 24), (1800, 616, 480, 24), (3120, 616, 60, 24)],
        "crates": [(2520, 592), (2572, 592)],
        "coins": [(880, 460), (1130, 410), (1550, 470), (1975, 460), (2635, 440),
                  (2975, 350), (3315, 260), (3560, 560), (3800, 560)],
        "checkpoints": [1300, 2350],
        "goal": (3860, 560),
        "hint": "大沟用 B 冲刺；木箱用空中 Y 下砸；精确落点按住 LT 缓行",
    },
]

# ----------------------------------------------------------------------------
# 物理 & 实体
# ----------------------------------------------------------------------------

GRAVITY = 2200.0
MOVE_MAX = 340.0
ACCEL = 2800.0
FRICTION = 2400.0
AIR_ACCEL = 2000.0
JUMP_BASE = 430.0
JUMP_BONUS = 480.0
COYOTE = 0.09
JUMP_BUFFER = 0.12
DASH_SPEED = 900.0
DASH_TIME = 0.16
DASH_CD = 0.9
POUND_SPEED = 1350.0

COLOR = {
    "bg": (14, 16, 20), "bg2": (22, 26, 32), "ground": (52, 60, 70),
    "ground_top": (74, 86, 100), "plat": (64, 118, 78), "plat_top": (96, 168, 112),
    "moving": (70, 108, 158), "player": (236, 240, 244), "player_eye": (20, 22, 26),
    "coin": (244, 200, 66), "spike": (200, 84, 84), "goal": (108, 200, 120),
    "checkpoint": (120, 140, 160), "checkpoint_on": (108, 200, 120),
    "crate": (168, 128, 84), "text": (226, 232, 238), "dim": (140, 150, 160),
    "accent": (76, 175, 80), "panel": (18, 21, 26), "warn": (230, 120, 110),
}


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


class Player:
    W, H = 34, 46

    def __init__(self, x, y):
        self.x, self.y = float(x), float(y)
        self.vx = self.vy = 0.0
        self.on_ground = False
        self.facing = 1
        self.coyote = 0.0
        self.buffer = 0.0
        self.air_jumps = 0
        self.dash_t = 0.0
        self.dash_cd = 0.0
        self.pound = False
        self.squash = 0.0
        self.spawn = (x, y)

    def rect(self):
        return pygame.Rect(int(self.x), int(self.y), self.W, self.H)

    def center(self):
        return self.x + self.W / 2, self.y + self.H / 2


class Game:
    def __init__(self, selftest=False):
        pygame.init()
        self.selftest = selftest
        flags = 0
        if selftest:
            os.environ["SDL_VIDEODRIVER"] = "dummy"
        self.screen = pygame.display.set_mode((1280, 720), flags)
        pygame.display.set_caption("Xbox 手柄测试 · 2D 横版跳跃")
        self.clock = pygame.time.Clock()
        self.font = self._font(16)
        self.big = self._font(30)
        self.small = self._font(13)

        # 输入：优先 XInput（与真实游戏同路径），不可用则键盘兜底
        self.pad = Pad()
        if not self.pad.ok:
            self.pad = Keyboard()
        self.input_source = "XInput" if isinstance(self.pad, Pad) else "键盘兜底"

        self.level_index = 0
        self.show_hud = True
        self.paused = False
        self.state = "play"          # play / clear / win
        self.state_t = 0.0
        self.time_used = 0.0
        self.deaths = 0
        self.coins_total = 0
        self.coins_got = 0
        self.particles = []
        self.load_level(0)

    def _font(self, size):
        # 直接加载系统字体文件：pygame 的 SysFont 在部分机器上枚举注册表会崩溃
        for path in ("C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/msyhbd.ttc",
                     "C:/Windows/Fonts/simhei.ttf", "C:/Windows/Fonts/deng.ttf"):
            if os.path.exists(path):
                try:
                    return pygame.font.Font(path, size)
                except Exception:
                    continue
        return pygame.font.Font(None, size + 6)

    # ---------------- 关卡 ----------------

    def load_level(self, idx):
        self.level_index = idx
        lv = LEVELS[idx]
        self.solids = [pygame.Rect(*r) for r in lv["ground"]]
        self.solids += [pygame.Rect(*r[:4]) for r in lv["platforms"]]
        self.moving = []
        for (x, y, w, h, dx, dy, period) in lv["moving"]:
            self.moving.append({
                "base": pygame.Rect(x, y, w, h), "rect": pygame.Rect(x, y, w, h),
                "dx": dx, "dy": dy, "period": period, "t": 0.0, "delta": (0.0, 0.0),
            })
        self.spikes = [pygame.Rect(*r) for r in lv["spikes"]]
        self.crates = [pygame.Rect(x, y, 52, 48) for (x, y) in lv["crates"]]
        self.coins = [pygame.Rect(x - 9, y - 9, 18, 18) for (x, y) in lv["coins"]]
        self.coins_got = 0
        self.coins_total = len(self.coins)
        self.check_x = [x for x in lv["checkpoints"]]
        self.check_on = [False] * len(self.check_x)
        self.goal = pygame.Rect(lv["goal"][0], lv["goal"][1], 46, 80)
        self.level_w = lv["width"]
        self.hint = lv["hint"]
        self.respawn(full=True)

    def respawn(self, full=False):
        lv = LEVELS[self.level_index]
        if full:
            self.player = Player(*lv["spawn"])
            self.spawn_point = (self.player.x, self.player.y)
        else:
            self.player.x, self.player.y = self.spawn_point
            self.player.vx = self.player.vy = 0.0
            self.player.dash_t = self.player.dash_cd = 0.0
            self.player.pound = False
            self.player.air_jumps = 0

    # ---------------- 主循环 ----------------

    def run(self):
        if self.selftest:
            return self.self_test()
        while True:
            dt = clamp(self.clock.tick(60) / 1000.0, 0.0, 1 / 30.0)
            for e in pygame.event.get():
                if e.type == pygame.QUIT:
                    return 0
            self.pad.poll(dt)
            self.update(dt)
            self.draw()
            pygame.display.flip()

    def self_test(self):
        """无头自检：验证初始化、关卡加载与一帧物理，不打开窗口。"""
        import os
        assert os.environ.get("SDL_VIDEODRIVER") == "dummy"
        self.pad.poll(0.016)
        for _ in range(30):
            self.update(1 / 60.0)
        assert len(self.solids) > 0 and self.player is not None
        print(f"[自检] 关卡数={len(LEVELS)} 当前关={LEVELS[self.level_index]['name']}")
        print(f"[自检] 实体: 平台{len(self.solids)} 移动{len(self.moving)} "
              f"钉子{len(self.spikes)} 金币{self.coins_total}")
        print(f"[自检] 输入源: {self.input_source}")
        print("[自检] PASS")
        return 0

    # ---------------- 更新 ----------------

    def update(self, dt):
        pad = self.pad
        if pad.just_pressed("BACK"):
            self.show_hud = not self.show_hud
        if pad.just_pressed("START") and self.state == "play":
            self.paused = not self.paused

        if self.state != "play":
            self.state_t += dt
            if self.state == "clear" and self.state_t > 1.4:
                if self.level_index + 1 < len(LEVELS):
                    self.load_level(self.level_index + 1)
                    self.state = "play"
                else:
                    self.state = "win"
                    self.state_t = 0.0
            self.update_particles(dt)
            return

        if self.paused:
            if pad.just_pressed("START"):
                self.paused = False
            self.update_particles(dt)
            return

        self.time_used += dt
        p = self.player

        # ---- 重生 / 检查点 ----
        if pad.just_pressed("LB"):
            self.respawn()
        if pad.just_pressed("RB"):
            self.respawn(full=True)

        # ---- 移动（模拟量） ----
        lx = pad.cur["lx"]
        rt = pad.cur["rt"] / 255.0
        lt = pad.cur["lt"] / 255.0
        speed_scale = (1.0 + 0.8 * rt) * max(0.22, 1.0 - 0.8 * lt)
        target = lx * MOVE_MAX * speed_scale
        if pad.pressed("十字左"):
            target = -MOVE_MAX * 0.55
        elif pad.pressed("十字右"):
            target = MOVE_MAX * 0.55

        acc = ACCEL if p.on_ground else AIR_ACCEL
        if abs(target) > 1:
            p.facing = 1 if target > 0 else -1
            if p.dash_t <= 0:
                p.vx += clamp(target - p.vx, -acc * dt, acc * dt)
        elif p.on_ground and p.dash_t <= 0:
            f = FRICTION * dt
            p.vx -= clamp(p.vx, -f, f)

        # ---- 跳跃（力度 = 基础 + 摇杆上推量） ----
        charge = clamp(pad.cur["ly"], 0.0, 1.0)
        if p.on_ground:
            p.coyote = COYOTE
            p.air_jumps = 1
        else:
            p.coyote = max(0.0, p.coyote - dt)
        if pad.just_pressed("A"):
            p.buffer = JUMP_BUFFER
        else:
            p.buffer = max(0.0, p.buffer - dt)

        if p.buffer > 0 and p.coyote > 0 and p.dash_t <= 0:
            p.vy = -(JUMP_BASE + JUMP_BONUS * charge)
            p.buffer = 0.0
            p.coyote = 0.0
            p.on_ground = False
            p.squash = -0.35
            self.burst(*p.center(), 6, COLOR["player"])
        elif pad.just_pressed("X") and p.air_jumps > 0 and not p.on_ground:
            p.vy = -(JUMP_BASE + JUMP_BONUS * charge) * 0.86
            p.air_jumps -= 1
            p.squash = -0.3
            self.burst(*p.center(), 8, COLOR["accent"])

        # ---- 冲刺 ----
        p.dash_cd = max(0.0, p.dash_cd - dt)
        if pad.just_pressed("B") and p.dash_cd <= 0 and p.dash_t <= 0:
            p.dash_t = DASH_TIME
            p.dash_cd = DASH_CD
            p.pound = False
            self.burst(*p.center(), 10, COLOR["moving"])
        if p.dash_t > 0:
            p.dash_t -= dt
            p.vx = p.facing * DASH_SPEED
            p.vy = 0.0

        # ---- 下砸 ----
        if pad.just_pressed("Y") and not p.on_ground and p.dash_t <= 0:
            p.pound = True
            p.vx = 0.0
        if p.pound:
            p.vy = POUND_SPEED

        # ---- 重力 ----
        if p.dash_t <= 0 and not p.pound:
            p.vy += GRAVITY * dt
        p.vy = clamp(p.vy, -1400.0, 1500.0)

        # ---- 位移与碰撞 ----
        self.move_and_collide(dt)

        # ---- 危险与出界 ----
        pr = p.rect()
        if pr.y > 900:
            self.die()
        for s in self.spikes:
            if pr.colliderect(s.inflate(-6, -6)):
                self.die()
                break

        # ---- 金币 ----
        for c in self.coins[:]:
            if pr.colliderect(c):
                self.coins.remove(c)
                self.coins_got += 1
                self.burst(c.centerx, c.centery, 8, COLOR["coin"])

        # ---- 检查点 ----
        for i, cx in enumerate(self.check_x):
            if not self.check_on[i] and pr.centerx > cx:
                self.check_on[i] = True
                self.spawn_point = (float(cx), 560.0)

        # ---- 终点 ----
        if pr.colliderect(self.goal):
            self.state = "clear"
            self.state_t = 0.0

        p.squash += (0.0 - p.squash) * min(1.0, dt * 10)
        self.update_particles(dt)

    def die(self):
        self.deaths += 1
        self.burst(*self.player.center(), 16, COLOR["spike"])
        self.respawn()

    def move_and_collide(self, dt):
        p = self.player
        # 移动平台先走，并记录位移
        for m in self.moving:
            m["t"] += dt
            ph = math.sin(m["t"] / m["period"] * math.pi * 2)
            nx = m["base"].x + m["dx"] * ph
            ny = m["base"].y + m["dy"] * ph
            m["delta"] = (nx - m["rect"].x, ny - m["rect"].y)
            m["rect"].topleft = (nx, ny)
            # 站在移动平台上时跟随
            feet = pygame.Rect(p.x + 4, p.y + p.H, p.W - 8, 6)
            if feet.colliderect(m["rect"]) and p.vy >= 0:
                p.x += m["delta"][0]
                p.y += m["delta"][1]

        # X 轴
        p.x += p.vx * dt
        pr = p.rect()
        for s in self.solids + [m["rect"] for m in self.moving]:
            if pr.colliderect(s):
                if p.vx > 0:
                    p.x = s.x - p.W
                elif p.vx < 0:
                    p.x = s.right
                p.vx = 0.0
                pr = p.rect()

        # Y 轴
        was_air = not p.on_ground
        p.y += p.vy * dt
        p.on_ground = False
        pr = p.rect()
        for s in self.solids + [m["rect"] for m in self.moving]:
            if pr.colliderect(s):
                if p.vy > 0:
                    p.y = s.y - p.H
                    p.on_ground = True
                    if was_air and p.squash > -0.2:
                        p.squash = 0.3
                        if p.pound:
                            self.burst(p.x + p.W / 2, p.y + p.H, 14, COLOR["coin"])
                        p.pound = False
                    elif p.pound:
                        p.pound = False
                    p.vy = 0.0
                elif p.vy < 0:
                    p.y = s.bottom
                    p.vy = 0.0
                pr = p.rect()

        # 木箱（下砸破碎）
        for c in self.crates[:]:
            if pr.colliderect(c):
                if p.pound and p.vy > 0:
                    self.crates.remove(c)
                    self.burst(c.centerx, c.centery, 14, COLOR["crate"])
                    p.vy = -420.0
                    p.pound = False
                else:
                    if p.vy > 0 and p.y + p.H - p.vy * dt <= c.y + 8:
                        p.y = c.y - p.H
                        p.vy = 0.0
                        p.on_ground = True
                    pr = p.rect()

    # ---------------- 粒子 ----------------

    def burst(self, x, y, n, color):
        for _ in range(n):
            ang = math.radians(360 * _ / n + len(self.particles))
            sp = 60 + 160 * (_ % 3) / 2
            self.particles.append({
                "x": x, "y": y,
                "vx": math.cos(ang) * sp, "vy": math.sin(ang) * sp - 60,
                "life": 0.5, "color": color,
            })

    def update_particles(self, dt):
        for pt in self.particles[:]:
            pt["life"] -= dt
            if pt["life"] <= 0:
                self.particles.remove(pt)
                continue
            pt["vy"] += 900 * dt
            pt["x"] += pt["vx"] * dt
            pt["y"] += pt["vy"] * dt

    # ---------------- 绘制 ----------------

    def draw(self):
        scr = self.screen
        scr.fill(COLOR["bg"])
        lv = LEVELS[self.level_index]
        p = self.player

        # 视差背景
        cam_x = self.cam_x()
        for i in range(6):
            x = (i * 260 - cam_x * 0.25) % (1280 + 260) - 130
            pygame.draw.rect(scr, COLOR["bg2"], (x, 120 + i * 40, 160, 320), border_radius=18)

        ox, oy = -cam_x, 0.0

        # 地面与平台
        for s in self.solids:
            r = s.move(ox, oy)
            pygame.draw.rect(scr, COLOR["ground"], r, border_radius=6)
            pygame.draw.rect(scr, COLOR["ground_top"], (r.x, r.y, r.w, 6), border_radius=3)
        for m in self.moving:
            r = m["rect"].move(ox, oy)
            pygame.draw.rect(scr, COLOR["moving"], r, border_radius=8)
            pygame.draw.rect(scr, (110, 150, 205), (r.x, r.y, r.w, 5), border_radius=3)

        # 终点 / 检查点
        g = self.goal.move(ox, oy)
        pygame.draw.rect(scr, COLOR["goal"], g, border_radius=8)
        pygame.draw.rect(scr, (20, 40, 24), (g.centerx - 6, g.y + 14, 12, 40), border_radius=4)
        for i, cx in enumerate(self.check_x):
            c = COLOR["checkpoint_on"] if self.check_on[i] else COLOR["checkpoint"]
            pygame.draw.rect(scr, c, (cx + ox, 560, 8, 80), border_radius=4)

        # 钉子
        for s in self.spikes:
            r = s.move(ox, oy)
            n = max(2, int(r.w // 22))
            for k in range(n):
                x0 = r.x + k * (r.w / n)
                pygame.draw.polygon(scr, COLOR["spike"], [
                    (x0, r.bottom), (x0 + r.w / n / 2, r.y), (x0 + r.w / n, r.bottom)])

        # 木箱
        for c in self.crates:
            r = c.move(ox, oy)
            pygame.draw.rect(scr, COLOR["crate"], r, border_radius=6)
            pygame.draw.rect(scr, (120, 88, 56), r, 3, border_radius=6)

        # 金币
        for c in self.coins:
            pygame.draw.circle(scr, COLOR["coin"], (int(c.centerx + ox), int(c.centery + oy)), 9)

        # 玩家（挤压拉伸）
        sq = p.squash
        w = p.W * (1 + sq * 0.5)
        h = p.H * (1 - sq * 0.5)
        pr = pygame.Rect(0, 0, int(w), int(h))
        pr.midbottom = (int(p.x + p.W / 2 + ox), int(p.y + p.H + oy))
        pygame.draw.rect(scr, COLOR["player"], pr, border_radius=8)
        ex = pr.centerx + p.facing * 7
        pygame.draw.circle(scr, COLOR["player_eye"], (ex, pr.y + 14), 4)
        pygame.draw.circle(scr, COLOR["player_eye"], (ex + p.facing * 8, pr.y + 14), 4)

        # 粒子
        for pt in self.particles:
            a = clamp(pt["life"] / 0.5, 0, 1)
            size = max(2, int(5 * a))
            pygame.draw.rect(scr, pt["color"], (int(pt["x"] + ox), int(pt["y"] + oy), size, size))

        # 提示
        tip = self.small.render(f"{lv['name']}   {self.hint}", True, COLOR["dim"])
        scr.blit(tip, (16, 8))

        if self.show_hud:
            self.draw_hud()
        if self.paused:
            self.center_text("已暂停  ·  START 继续")
        if self.state == "clear":
            self.center_text("通关！正在进入下一关…")
        if self.state == "win":
            self.draw_win()

    def cam_x(self):
        cx = self.player.x + self.player.W / 2 - 1280 / 2
        return clamp(cx, 0, max(0, self.level_w - 1280))

    def center_text(self, text):
        t = self.big.render(text, True, COLOR["text"])
        r = t.get_rect(center=(640, 320))
        pygame.draw.rect(self.screen, COLOR["panel"], r.inflate(40, 26), border_radius=12)
        self.screen.blit(t, r)

    def draw_win(self):
        s = self.pad
        lines = [
            f"用时 {self.time_used:.1f}s    死亡 {self.deaths} 次    "
            f"金币 {self.coins_got}/{self.coins_total}",
            f"摇杆最大偏移  X={s.max_lx:.2f}  Y={s.max_ly_up:.2f}    "
            f"满偏时长 {s.full_stick_time:.1f}s",
            f"扳机峰值  LT={s.max_lt}  RT={s.max_rt}",
            "按键统计: " + "  ".join(
                f"{k}×{v}" for k, v in s.press_count.items() if v > 0),
        ]
        box = pygame.Rect(240, 200, 800, 240)
        pygame.draw.rect(self.screen, COLOR["panel"], box, border_radius=16)
        pygame.draw.rect(self.screen, COLOR["accent"], box, 2, border_radius=16)
        t1 = self.big.render("全部通关！", True, COLOR["accent"])
        self.screen.blit(t1, (box.centerx - t1.get_width() // 2, box.y + 18))
        for i, ln in enumerate(lines):
            t = self.font.render(ln, True, COLOR["text"])
            self.screen.blit(t, (box.x + 28, box.y + 78 + i * 34))

    # ---------------- 输入监测 HUD ----------------

    def draw_hud(self):
        scr = self.screen
        s = self.pad
        panel = pygame.Rect(16, 28, 336, 250)
        pygame.draw.rect(scr, COLOR["panel"], panel, border_radius=12)
        pygame.draw.rect(scr, (52, 60, 70), panel, 1, border_radius=12)

        title = self.font.render(f"输入监测  ·  {self.input_source}", True, COLOR["accent"])
        scr.blit(title, (panel.x + 12, panel.y + 8))

        # 摇杆十字区
        bx, by, bs = panel.x + 16, panel.y + 40, 108
        pygame.draw.rect(scr, (10, 12, 15), (bx, by, bs, bs), border_radius=8)
        pygame.draw.line(scr, (44, 52, 60), (bx + bs / 2, by), (bx + bs / 2, by + bs))
        pygame.draw.line(scr, (44, 52, 60), (bx, by + bs / 2), (bx + bs, by + bs / 2))
        pygame.draw.circle(scr, (56, 66, 76), (bx + bs // 2, by + bs // 2),
                           int(bs / 2 * STICK_DEADZONE / 32767), 1)
        kx = bx + bs / 2 + s.cur["lx"] * bs / 2
        ky = by + bs / 2 - s.cur["ly"] * bs / 2
        pygame.draw.circle(scr, COLOR["accent"], (int(kx), int(ky)), 7)
        t = self.small.render(
            f"LX {s.cur['raw_lx']:+6d}  LY {s.cur['raw_ly']:+6d}", True, COLOR["text"])
        scr.blit(t, (bx, by + bs + 4))

        # 右摇杆数值
        t2 = self.small.render(
            f"RX {s.cur['rx']:+.2f}  RY {s.cur['ry']:+.2f}", True, COLOR["dim"])
        scr.blit(t2, (bx + 4, by + bs + 22))

        # 扳机条
        for i, (name, val) in enumerate((("LT", s.cur["lt"]), ("RT", s.cur["rt"]))):
            x0 = panel.x + 150 + i * 56
            bh = 108
            pygame.draw.rect(scr, (10, 12, 15), (x0, by, 34, bh), border_radius=6)
            fh = int(bh * val / 255)
            pygame.draw.rect(scr, COLOR["accent"], (x0, by + bh - fh, 34, fh), border_radius=6)
            t = self.small.render(f"{name} {val:3d}", True, COLOR["text"])
            scr.blit(t, (x0 - 4, by + bh + 4))

        # 按键网格（按下点亮 + 计数）
        gx, gy = panel.x + 150, panel.y + 176
        for i, name in enumerate(HUD_BUTTONS):
            col, row = i % 7, i // 7
            x0, y0 = gx + col * 26, gy + row * 26
            on = s.pressed(name)
            pygame.draw.rect(scr, COLOR["accent"] if on else (36, 42, 50),
                             (x0, y0, 22, 18), border_radius=5)
            if s.press_count.get(name):
                c = self.small.render(str(s.press_count[name]), True, COLOR["coin"])
                scr.blit(c, (x0 + 2, y0 + 2))

        # 丢帧/连接提示
        if not s.ok:
            t = self.small.render("未检测到 XInput 手柄 —— 请确认接收端已运行", True, COLOR["warn"])
            scr.blit(t, (panel.x + 12, panel.bottom - 24))


def main(argv):
    selftest = "--selftest" in argv
    game = Game(selftest=selftest)
    return game.run()


if __name__ == "__main__":
    import os
    sys.exit(main(sys.argv[1:]))
