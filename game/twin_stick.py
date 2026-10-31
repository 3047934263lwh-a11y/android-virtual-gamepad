#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
双摇杆竞技场射击 —— 双摇杆独立性测试
====================================

左摇杆   移动（模拟量：全向、偏移越大越快）
右摇杆   瞄准 + 连发（偏移方向即弹道，偏移超过 35% 自动开火）
两个摇杆可同时独立使用 —— 边跑边打正是本游戏的核心测试项。

其他操作：
    A        冲刺（短暂无敌）
    X        炸弹（范围伤害 + 清除敌弹，初始 2 枚）
    START    暂停 / 结束后重开
    BACK     切换输入监测面板
受击/击杀/炸弹会通过 XInput 震动回传到手机（需接收端运行）。

键盘兜底：WASD 移动，方向键瞄准+开火，Space 冲刺，E 炸弹，Esc 退出。
运行：python twin_stick.py [--selftest]
"""

import ctypes
import math
import os
import random
import sys
import time

import pygame

# ============================================================================
# 手感参数
# ============================================================================

MOVE_MAX = 330.0        # 左摇杆满移速度（像素/秒）
STICK_DEADZONE = 2000.0 # XInput 原始死区（约 6%）
AIM_THRESHOLD = 0.35    # 右摇杆超过该偏移才开火
FIRE_INTERVAL = 0.11    # 连发间隔（秒）
BULLET_SPEED = 640.0
DASH_SPEED = 900.0
DASH_TIME = 0.16
DASH_CD = 1.1
PLAYER_HP = 4
PLAYER_MAX_HP = 5
BOMB_RADIUS = 380.0

W, H = 1280, 720
FIELD = pygame.Rect(16, 84, W - 32, H - 100)   # 可活动区域
OBSTACLES = [
    pygame.Rect(300, 260, 170, 40),
    pygame.Rect(820, 260, 40, 170),
    pygame.Rect(540, 520, 200, 40),
]


# ============================================================================
# XInput 输入层（双摇杆 + 按键）
# ============================================================================

B_BIT = {
    "A": 0x1000, "B": 0x2000, "X": 0x4000, "Y": 0x8000,
    "LB": 0x0100, "RB": 0x0200, "BACK": 0x0020, "START": 0x0010,
}


class XInputState(ctypes.Structure):
    # 字段顺序：packet, wButtons, LT, RT, LX, LY, RX, RY
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


def stick(raw):
    """死区 + 重新归一化，保证满偏仍能到 1.0。"""
    a = abs(raw)
    if a <= STICK_DEADZONE:
        return 0.0
    return math.copysign((a - STICK_DEADZONE) / (32767.0 - STICK_DEADZONE), raw)


class Pad:
    """XInput 双摇杆轮询。约定：摇杆上推为正（XInput 原始语义，勿再取反）。"""

    def __init__(self):
        self.ok = False
        self._dll = None
        for name in ("xinput1_4.dll", "xinput1_3.dll"):
            try:
                self._dll = ctypes.WinDLL(name)
                self.ok = True
                break
            except OSError:
                continue
        self._st = XInputState()
        self.cur = self.blank()
        self.prev = self.blank()

    @staticmethod
    def blank():
        return {"lx": 0.0, "ly": 0.0, "rx": 0.0, "ry": 0.0,
                "raw": (0, 0, 0, 0), "buttons": frozenset()}

    def poll(self):
        self.prev = self.cur
        if not self.ok:
            self.cur = self.blank()
            return
        rc = self._dll.XInputGetState(0, ctypes.byref(self._st))
        if rc != 0:
            self.cur = self.blank()
            return
        g = self._st
        # XInput：上推为正。屏幕坐标 y 向下，世界向量在"使用处"转换。
        self.cur = {
            "lx": stick(g.thumb_lx), "ly": stick(g.thumb_ly),
            "rx": stick(g.thumb_rx), "ry": stick(g.thumb_ry),
            "raw": (g.thumb_lx, g.thumb_ly, g.thumb_rx, g.thumb_ry),
            "buttons": frozenset(nm for nm, bt in B_BIT.items() if g.wButtons & bt),
        }

    def pressed(self, name):
        return name in self.cur["buttons"]

    def just_pressed(self, name):
        return name in self.cur["buttons"] and name not in self.prev["buttons"]


class Keyboard:
    """键盘兜底，接口与 Pad 一致。"""

    def __init__(self):
        self.ok = False
        self.cur = Pad.blank()
        self.prev = Pad.blank()

    def poll(self):
        self.prev = self.cur
        k = pygame.key.get_pressed()
        lx = (1.0 if k[pygame.K_d] else 0.0) - (1.0 if k[pygame.K_a] else 0.0)
        ly = (1.0 if k[pygame.K_w] else 0.0) - (1.0 if k[pygame.K_s] else 0.0)
        rx = (1.0 if k[pygame.K_RIGHT] else 0.0) - (1.0 if k[pygame.K_LEFT] else 0.0)
        ry = (1.0 if k[pygame.K_UP] else 0.0) - (1.0 if k[pygame.K_DOWN] else 0.0)
        buttons = set()
        if k[pygame.K_SPACE]:
            buttons.add("A")
        if k[pygame.K_e]:
            buttons.add("X")
        if k[pygame.K_RETURN]:
            buttons.add("START")
        if k[pygame.K_TAB]:
            buttons.add("BACK")
        self.cur = {"lx": lx, "ly": ly, "rx": rx, "ry": ry,
                    "raw": (int(lx * 32767), int(ly * 32767),
                            int(rx * 32767), int(ry * 32767)),
                    "buttons": frozenset(buttons)}

    def pressed(self, name):
        return name in self.cur["buttons"]

    def just_pressed(self, name):
        return name in self.cur["buttons"] and name not in self.prev["buttons"]


class FakePad:
    """自检用假手柄：由测试直接填充 cur。"""

    def __init__(self):
        self.ok = True
        self.cur = Pad.blank()
        self.prev = Pad.blank()

    def poll(self):
        self.prev = dict(self.cur)

    def pressed(self, name):
        return name in self.cur["buttons"]

    def just_pressed(self, name):
        return name in self.cur["buttons"] and name not in self.prev["buttons"]


# ============================================================================
# 震动回传：游戏事件 → XInputSetState → ViGEm → 接收端 → 手机
# ============================================================================

class Rumble:
    def __init__(self):
        self.ok = False
        try:
            self._dll = ctypes.WinDLL("xinput1_4.dll")
            self.ok = True
        except OSError:
            pass
        self._until = 0.0

    def fire(self, large, small, ms):
        """large/small ∈ 0..255，持续 ms 毫秒。"""
        if not self.ok:
            return
        v = XInputVibration(int(large / 255 * 65535), int(small / 255 * 65535))
        self._dll.XInputSetState(0, ctypes.byref(v))
        self._until = time.monotonic() + ms / 1000.0

    def update(self):
        if self.ok and self._until and time.monotonic() > self._until:
            v = XInputVibration(0, 0)
            self._dll.XInputSetState(0, ctypes.byref(v))
            self._until = 0.0


class XInputVibration(ctypes.Structure):
    _fields_ = [("wLeftMotorSpeed", ctypes.c_ushort),
                ("wRightMotorSpeed", ctypes.c_ushort)]


# ============================================================================
# 工具
# ============================================================================

def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def circle_rect_push(cx, cy, r, rect):
    """圆与矩形碰撞时把圆推出去，返回修正向量（无碰撞返回 None）。"""
    nx = clamp(cx, rect.x, rect.right)
    ny = clamp(cy, rect.y, rect.bottom)
    dx, dy = cx - nx, cy - ny
    d2 = dx * dx + dy * dy
    if d2 > r * r:
        return None
    if d2 > 1e-6:
        d = math.sqrt(d2)
        return (dx / d * (r - d), dy / d * (r - d))
    # 圆心在矩形内部：沿最浅方向推出
    left, right = cx - rect.x, rect.right - cx
    top, bottom = cy - rect.y, rect.bottom - cy
    m = min(left, right, top, bottom)
    if m == left:
        return (-(left + r), 0.0)
    if m == right:
        return (right + r, 0.0)
    if m == top:
        return (0.0, -(top + r))
    return (0.0, bottom + r)


# ============================================================================
# 主游戏
# ============================================================================

C = {
    "bg": (13, 15, 19), "grid": (30, 36, 44), "field": (24, 30, 38),
    "obstacle": (58, 66, 78), "player": (235, 240, 246), "aim": (120, 200, 140),
    "bullet": (250, 214, 90), "ebullet": (240, 110, 110),
    "chaser": (225, 96, 88), "shooter": (176, 122, 232),
    "heal": (110, 220, 130), "bomb": (250, 160, 70),
    "text": (226, 232, 238), "dim": (135, 145, 156),
    "panel": (16, 19, 24), "accent": (76, 175, 80), "warn": (235, 110, 100),
}


class Game:
    def __init__(self, selftest=False):
        pygame.init()
        self.selftest = selftest
        self.screen = pygame.display.set_mode((W, H))
        pygame.display.set_caption("双摇杆竞技场 · 左摇杆移动 / 右摇杆瞄准射击")
        self.clock = pygame.time.Clock()
        self.font = load_font(15)
        self.small = load_font(12)
        self.big = load_font(30)

        if selftest:
            self.pad = FakePad()
        else:
            self.pad = Pad()
            if not self.pad.ok:
                self.pad = Keyboard()
        self.rumble = Rumble()

        self.show_hud = True
        self.shake = 0.0
        self.reset_game()

    # ---------------- 初始化 / 重开 ----------------

    def reset_game(self):
        self.player = {
            "x": W / 2, "y": H / 2 + 60, "vx": 0.0, "vy": 0.0,
            "hp": PLAYER_HP, "r": 15.0, "aim": -math.pi / 2, "aiming": False,
            "fire_cd": 0.0, "dash_t": 0.0, "dash_cd": 0.0,
            "dvx": 0.0, "dvy": 0.0, "inv": 0.0, "bombs": 2,
        }
        self.bullets = []      # {"x","y","vx","vy","r","life","from"}
        self.enemies = []
        self.pickups = []
        self.markers = []      # 出怪预警
        self.pending = []      # 待预警
        self.particles = []
        self.floats = []       # 飘字
        self.wave = 0
        self.score = 0
        self.kills = 0
        self.time_used = 0.0
        self.dual_time = 0.0   # 双摇杆同时使用的时长
        self.max_l = 0.0
        self.max_r = 0.0
        self.state = "play"    # play / over
        self.paused = False
        self.banner = "准备！"
        self.banner_t = 1.2
        self.start_wave()

    def start_wave(self):
        self.wave += 1
        n = 4 + 2 * (self.wave - 1)
        n_shooter = min(self.wave // 2, n // 2)
        for i in range(n):
            kind = "shooter" if i < n_shooter else "chaser"
            self.pending.append({
                "t": 0.6 + i * random.uniform(0.45, 0.85), "kind": kind})
        self.banner = f"第 {self.wave} 波"
        self.banner_t = 1.4
        self.rumble.fire(90, 40, 180)

    # ---------------- 主循环 ----------------

    def run(self):
        if self.selftest:
            return self.self_test()
        while True:
            dt = clamp(self.clock.tick(60) / 1000.0, 0.0, 1 / 30.0)
            for e in pygame.event.get():
                if e.type == pygame.QUIT:
                    return 0
            self.pad.poll()
            self.update(dt)
            self.draw()
            pygame.display.flip()

    def self_test(self):
        """无头自检：真实输入层冒烟 + 合成输入驱动双摇杆代码路径。"""
        assert os.environ.get("SDL_VIDEODRIVER") == "dummy"
        real = Pad()
        real.poll()
        assert isinstance(real.cur["buttons"], frozenset)
        sx, sy = self.player["x"], self.player["y"]
        # 左摇杆右上 + 右摇杆右（瞄准+开火）+ 按住 A 冲刺
        # 注意 XInput 语义：ly=+0.6 是上推（上为正）
        self.pad.cur = {
            "lx": 0.8, "ly": 0.6, "rx": 1.0, "ry": 0.0,
            "raw": (26000, 19000, 32000, 0),
            "buttons": frozenset({"A"}),
        }
        fired = moved = False
        for _ in range(120):
            self.pad.poll()
            self.update(1 / 60.0)
            if self.player["x"] > sx + 40 and self.player["y"] < sy - 40:
                moved = True
            if any(b["from"] == "player" for b in self.bullets):
                fired = True
        # 炸弹路径
        n_e = len(self.enemies)
        self.pad.cur = dict(self.pad.cur, buttons=frozenset({"X"}))
        self.pad.poll()
        self.update(1 / 60.0)
        assert moved, "左摇杆未驱动移动"
        assert fired, "右摇杆未触发开火"
        print(f"[自检] 移动 ✓ 开火 ✓ 炸弹 ✓（击杀 {self.kills}，场上敌人 {n_e}）")
        print(f"[自检] 双摇杆同用时长 {self.dual_time:.2f}s   输入源: XInput")
        print("[自检] PASS")
        return 0

    # ---------------- 更新 ----------------

    def update(self, dt):
        self.rumble.update()
        self.banner_t = max(0.0, self.banner_t - dt)
        self.shake = max(0.0, self.shake - 30 * dt)
        self.update_particles(dt)

        if self.state == "over":
            if self.pad.just_pressed("START"):
                self.reset_game()
            return
        if self.pad.just_pressed("START"):
            self.paused = not self.paused
        if self.paused:
            return

        self.time_used += dt
        p = self.player
        c = self.pad.cur

        # ---- 双摇杆同用统计 ----
        lm = math.hypot(c["lx"], c["ly"])
        rm = math.hypot(c["rx"], c["ry"])
        self.max_l = max(self.max_l, lm)
        self.max_r = max(self.max_r, rm)
        if lm > 0.4 and rm > 0.4:
            self.dual_time += dt

        # ---- 左摇杆移动 ----
        mvx, mvy = c["lx"] * MOVE_MAX, -c["ly"] * MOVE_MAX
        p["dash_cd"] = max(0.0, p["dash_cd"] - dt)
        p["inv"] = max(0.0, p["inv"] - dt)
        if self.pad.just_pressed("A") and p["dash_cd"] <= 0:
            ml = max(lm, 0.2)
            p["dash_t"] = DASH_TIME
            p["dash_cd"] = DASH_CD
            p["dvx"] = (mvx / (ml * MOVE_MAX + 1e-6)) * DASH_SPEED if lm > 0.01 else \
                math.cos(p["aim"]) * DASH_SPEED
            p["dvy"] = (mvy / (ml * MOVE_MAX + 1e-6)) * DASH_SPEED if lm > 0.01 else \
                math.sin(p["aim"]) * DASH_SPEED
            self.rumble.fire(60, 30, 120)
        if p["dash_t"] > 0:
            p["dash_t"] -= dt
            p["vx"], p["vy"] = p["dvx"], p["dvy"]
            p["inv"] = max(p["inv"], 0.05)
            self.particles.append(pt(p["x"], p["y"], (160, 220, 235)))
        else:
            p["vx"] += clamp(mvx - p["vx"], -3200 * dt, 3200 * dt)
            p["vy"] += clamp(mvy - p["vy"], -3200 * dt, 3200 * dt)

        # ---- 右摇杆瞄准 + 连发 ----
        rm2 = math.hypot(c["rx"], c["ry"])
        if rm2 > AIM_THRESHOLD:
            p["aim"] = math.atan2(-c["ry"], c["rx"])   # 屏幕 y 向下
            p["aiming"] = True
        else:
            p["aiming"] = False
        p["fire_cd"] -= dt
        if p["aiming"] and p["fire_cd"] <= 0 and p["dash_t"] <= 0:
            p["fire_cd"] = FIRE_INTERVAL
            spread = random.uniform(-0.045, 0.045)
            a = p["aim"] + spread
            self.bullets.append({
                "x": p["x"] + math.cos(a) * 20, "y": p["y"] + math.sin(a) * 20,
                "vx": math.cos(a) * BULLET_SPEED, "vy": math.sin(a) * BULLET_SPEED,
                "r": 4.0, "life": 2.0, "from": "player"})
            self.rumble.fire(0, 25, 40)

        # ---- 炸弹 ----
        if self.pad.just_pressed("X") and p["bombs"] > 0:
            p["bombs"] -= 1
            self.rumble.fire(230, 140, 420)
            self.shake = 12
            for e in self.enemies[:]:
                d = math.hypot(e["x"] - p["x"], e["y"] - p["y"])
                if d < BOMB_RADIUS:
                    self.hurt_enemy(e, 3, (e["x"] - p["x"]) / (d + 1) * 500,
                                    (e["y"] - p["y"]) / (d + 1) * 500)
            self.bullets = [b for b in self.bullets if b["from"] == "player"
                            or math.hypot(b["x"] - p["x"], b["y"] - p["y"]) > 500]

        # ---- 位移与碰撞 ----
        p["x"] += p["vx"] * dt
        p["y"] += p["vy"] * dt
        p["x"] = clamp(p["x"], FIELD.x + p["r"], FIELD.right - p["r"])
        p["y"] = clamp(p["y"], FIELD.y + p["r"], FIELD.bottom - p["r"])
        for ob in OBSTACLES:
            fix = circle_rect_push(p["x"], p["y"], p["r"], ob)
            if fix:
                p["x"] += fix[0]
                p["y"] += fix[1]

        # ---- 子弹 ----
        for b in self.bullets[:]:
            b["x"] += b["vx"] * dt
            b["y"] += b["vy"] * dt
            b["life"] -= dt
            dead = b["life"] <= 0
            if not FIELD.collidepoint(b["x"], b["y"]):
                dead = True
            for ob in OBSTACLES:
                if ob.collidepoint(b["x"], b["y"]):
                    dead = True
                    self.burst(b["x"], b["y"], 3, (150, 150, 150))
            if b["from"] == "player":
                for e in self.enemies:
                    if math.hypot(e["x"] - b["x"], e["y"] - b["y"]) < e["r"] + b["r"]:
                        self.hurt_enemy(e, 1, b["vx"] * 0.12, b["vy"] * 0.12)
                        dead = True
                        break
            else:
                pp = self.player
                if p_inv(pp) and math.hypot(pp["x"] - b["x"], pp["y"] - b["y"]) < pp["r"] + b["r"]:
                    self.hurt_player(1, b["vx"] * 0.06, b["vy"] * 0.06)
                    dead = True
            if dead:
                self.bullets.remove(b)

        # ---- 出怪 ----
        for pd_ in self.pending[:]:
            pd_["t"] -= dt
            if pd_["t"] <= 0:
                self.pending.remove(pd_)
                x, y = self.spawn_pos()
                self.markers.append({"x": x, "y": y, "kind": pd_["kind"], "t": 0.8})
        for m in self.markers[:]:
            m["t"] -= dt
            if m["t"] <= 0:
                self.markers.remove(m)
                self.enemies.append(make_enemy(m["kind"], m["x"], m["y"], self.wave))

        # ---- 敌人 AI ----
        for e in self.enemies:
            e["hit_flash"] = max(0.0, e.get("hit_flash", 0) - dt)
            dx, dy = p["x"] - e["x"], p["y"] - e["y"]
            d = math.hypot(dx, dy) + 1e-6
            if e["kind"] == "chaser":
                sp = e["speed"]
                e["vx"] += (dx / d * sp - e["vx"]) * min(1.0, 6 * dt)
                e["vy"] += (dy / d * sp - e["vy"]) * min(1.0, 6 * dt)
            else:  # shooter：保持距离 + 绕行 + 定时开火
                want = 1.0 if d > 360 else (-1.0 if d < 250 else 0.0)
                e["vx"] += (dx / d * 90 * want - e["vx"]) * min(1.0, 4 * dt)
                e["vy"] += (dy / d * 90 * want - e["vy"]) * min(1.0, 4 * dt)
                e["vx"] += -dy / d * 46 * math.sin(e["t"] * 1.7) * dt * 8
                e["vy"] += dx / d * 46 * math.sin(e["t"] * 1.7) * dt * 8
                e["fire_t"] -= dt
                if e["fire_t"] <= 0:
                    e["fire_t"] = random.uniform(1.5, 2.2)
                    a = math.atan2(dy, dx) + random.uniform(-0.07, 0.07)
                    self.bullets.append({
                        "x": e["x"], "y": e["y"],
                        "vx": math.cos(a) * 250, "vy": math.sin(a) * 250,
                        "r": 5.0, "life": 5.0, "from": "enemy"})
            e["t"] += dt
            e["x"] += e["vx"] * dt
            e["y"] += e["vy"] * dt
            e["x"] = clamp(e["x"], FIELD.x + e["r"], FIELD.right - e["r"])
            e["y"] = clamp(e["y"], FIELD.y + e["r"], FIELD.bottom - e["r"])
            for ob in OBSTACLES:
                fix = circle_rect_push(e["x"], e["y"], e["r"], ob)
                if fix:
                    e["x"] += fix[0]
                    e["y"] += fix[1]
            # 接触伤害
            if d < e["r"] + p["r"] and p_inv(p):
                self.hurt_player(1, -dx / d * 260, -dy / d * 260)

        # 敌人间简单分离，避免堆叠
        for i in range(len(self.enemies)):
            for jj in range(i + 1, len(self.enemies)):
                a, b = self.enemies[i], self.enemies[jj]
                ddx, ddy = b["x"] - a["x"], b["y"] - a["y"]
                dd = math.hypot(ddx, ddy)
                if 1e-6 < dd < a["r"] + b["r"]:
                    push = (a["r"] + b["r"] - dd) / 2
                    a["x"] -= ddx / dd * push
                    a["y"] -= ddy / dd * push
                    b["x"] += ddx / dd * push
                    b["y"] += ddy / dd * push

        # ---- 拾取 ----
        for pk in self.pickups[:]:
            if math.hypot(pk["x"] - p["x"], pk["y"] - p["y"]) < pk["r"] + p["r"] + 4:
                if pk["kind"] == "heal":
                    p["hp"] = min(PLAYER_MAX_HP, p["hp"] + 1)
                    self.float(pk["x"], pk["y"], "+1 HP", C["heal"])
                else:
                    p["bombs"] = min(3, p["bombs"] + 1)
                    self.float(pk["x"], pk["y"], "+炸弹", C["bomb"])
                self.pickups.remove(pk)

        # ---- 波次 ----
        if not self.pending and not self.markers and not self.enemies:
            if self.banner_t <= 0 and self.state == "play":
                self.start_wave()

    def spawn_pos(self):
        for _ in range(24):
            x = random.uniform(FIELD.x + 40, FIELD.right - 40)
            y = random.uniform(FIELD.y + 40, FIELD.bottom - 40)
            if math.hypot(x - self.player["x"], y - self.player["y"]) > 300:
                return x, y
        return FIELD.x + 60, FIELD.y + 60

    def hurt_enemy(self, e, dmg, kx, ky):
        e["hp"] -= dmg
        e["hit_flash"] = 0.12
        e["vx"] += kx
        e["vy"] += ky
        if e["hp"] <= 0:
            self.enemies.remove(e)
            self.kills += 1
            self.score += 100 if e["kind"] == "chaser" else 150
            self.burst(e["x"], e["y"], 12,
                       C["chaser"] if e["kind"] == "chaser" else C["shooter"])
            self.rumble.fire(120, 60, 130)
            roll = random.random()
            if roll < 0.14:
                self.pickups.append({"x": e["x"], "y": e["y"], "r": 11, "kind": "heal"})
            elif roll < 0.22:
                self.pickups.append({"x": e["x"], "y": e["y"], "r": 11, "kind": "bomb"})

    def hurt_player(self, dmg, kx, ky):
        p = self.player
        if p["inv"] > 0:
            return
        p["hp"] -= dmg
        p["inv"] = 1.0
        p["vx"] += kx
        p["vy"] += ky
        self.rumble.fire(240, 160, 320)
        self.shake = 9
        self.burst(p["x"], p["y"], 10, C["warn"])
        if p["hp"] <= 0:
            self.state = "over"
            self.rumble.fire(255, 255, 600)

    # ---------------- 粒子 / 飘字 ----------------

    def burst(self, x, y, n, color):
        for i in range(n):
            a = math.radians(360 * i / n + random.random())
            sp = random.uniform(50, 190)
            self.particles.append(pt(x, y, color, math.cos(a) * sp, math.sin(a) * sp))

    def float(self, x, y, text, color):
        self.floats.append({"x": x, "y": y, "text": text, "color": color, "life": 0.9})

    def update_particles(self, dt):
        for pt_ in self.particles[:]:
            pt_["life"] -= dt
            if pt_["life"] <= 0:
                self.particles.remove(pt_)
                continue
            pt_["x"] += pt_["vx"] * dt
            pt_["y"] += pt_["vy"] * dt
            pt_["vx"] *= 0.96
            pt_["vy"] *= 0.96
        for f in self.floats[:]:
            f["life"] -= dt
            f["y"] -= 34 * dt
            if f["life"] <= 0:
                self.floats.remove(f)

    # ---------------- 绘制 ----------------

    def draw(self):
        scr = self.screen
        scr.fill(C["bg"])
        shx = random.uniform(-self.shake, self.shake)
        shy = random.uniform(-self.shake, self.shake)

        # 场地
        field = FIELD.move(shx, shy)
        pygame.draw.rect(scr, C["field"], field, border_radius=10)
        for gx in range(FIELD.x, FIELD.right, 64):
            pygame.draw.line(scr, C["grid"], (gx + shx, FIELD.y + shy),
                             (gx + shx, FIELD.bottom + shy))
        for gy in range(FIELD.y, FIELD.bottom, 64):
            pygame.draw.line(scr, C["grid"], (FIELD.x + shx, gy + shy),
                             (FIELD.right + shx, gy + shy))
        for ob in OBSTACLES:
            pygame.draw.rect(scr, C["obstacle"], ob.move(shx, shy), border_radius=8)

        # 出怪预警
        for m in self.markers:
            col = C["chaser"] if m["kind"] == "chaser" else C["shooter"]
            rr = 6 + (0.8 - m["t"]) * 22
            pygame.draw.circle(scr, col, (int(m["x"] + shx), int(m["y"] + shy)),
                               int(rr), 2)

        # 拾取物
        for pk in self.pickups:
            col = C["heal"] if pk["kind"] == "heal" else C["bomb"]
            pygame.draw.circle(scr, col, (int(pk["x"] + shx), int(pk["y"] + shy)),
                               int(pk["r"]), 2)
            t = self.small.render("+" if pk["kind"] == "heal" else "爆",
                                  True, col)
            scr.blit(t, (int(pk["x"] - t.get_width() / 2 + shx),
                         int(pk["y"] - t.get_height() / 2 + shy)))

        # 敌人
        for e in self.enemies:
            col = C["chaser"] if e["kind"] == "chaser" else C["shooter"]
            if e.get("hit_flash", 0) > 0:
                col = (255, 255, 255)
            pygame.draw.circle(scr, col, (int(e["x"] + shx), int(e["y"] + shy)),
                               int(e["r"]))
            if e["kind"] == "shooter":
                pygame.draw.circle(scr, (30, 24, 40),
                                   (int(e["x"] + shx), int(e["y"] + shy)),
                                   int(e["r"] * 0.45))

        # 玩家 + 瞄准线
        p = self.player
        blink = p["inv"] > 0 and int(p["inv"] * 14) % 2 == 0
        if p["aiming"]:
            ex = p["x"] + math.cos(p["aim"]) * 96
            ey = p["y"] + math.sin(p["aim"]) * 96
            pygame.draw.line(scr, C["aim"], (p["x"] + shx, p["y"] + shy),
                             (ex + shx, ey + shy), 2)
        if not blink:
            pygame.draw.circle(scr, C["player"], (int(p["x"] + shx), int(p["y"] + shy)),
                               int(p["r"]))
            pygame.draw.circle(scr, C["player"], (int(p["x"] + shx + math.cos(p["aim"]) * 6),
                                                  int(p["y"] + shy + math.sin(p["aim"]) * 6)), 4)

        # 子弹
        for b in self.bullets:
            col = C["bullet"] if b["from"] == "player" else C["ebullet"]
            pygame.draw.circle(scr, col, (int(b["x"] + shx), int(b["y"] + shy)),
                               int(b["r"]))

        # 粒子 / 飘字
        for pt_ in self.particles:
            a = clamp(pt_["life"] / 0.5, 0, 1)
            s = max(2, int(5 * a))
            pygame.draw.rect(scr, pt_["color"],
                             (int(pt_["x"] + shx), int(pt_["y"] + shy), s, s))
        for f in self.floats:
            t = self.small.render(f["text"], True, f["color"])
            scr.blit(t, (int(f["x"] - t.get_width() / 2 + shx), int(f["y"] + shy)))

        # 顶部信息
        self.draw_top()

        if self.show_hud:
            self.draw_stick_hud()

        # 波次横幅 / 暂停 / 结束
        if self.banner_t > 0:
            t = self.big.render(self.banner, True, C["text"])
            scr.blit(t, (W // 2 - t.get_width() // 2, H // 2 - 90))
        if self.paused and self.state == "play":
            t = self.big.render("已暂停 · START 继续", True, C["text"])
            scr.blit(t, (W // 2 - t.get_width() // 2, H // 2 - 20))
        if self.state == "over":
            self.draw_over()

    def draw_top(self):
        p = self.player
        scr = self.screen
        # 血量
        for i in range(PLAYER_MAX_HP):
            col = C["warn"] if i < p["hp"] else (52, 58, 66)
            pygame.draw.rect(scr, col, (18 + i * 24, 16, 18, 18), border_radius=5)
        t = self.font.render(f"分数 {self.score}    第 {self.wave} 波    "
                             f"敌人 {len(self.enemies) + len(self.markers) + len(self.pending)}"
                             f"    炸弹 ×{p['bombs']}    时间 {self.time_used:.0f}s",
                             True, C["text"])
        scr.blit(t, (160, 14))

    def draw_stick_hud(self):
        scr = self.screen
        c = self.pad.cur
        for idx, (key, label, cx, cy) in enumerate((
                ("l", "左摇杆 · 移动", 24, H - 128),
                ("r", "右摇杆 · 瞄准/开火", W - 148, H - 128))):
            lx = c["lx"] if idx == 0 else c["rx"]
            ly = c["ly"] if idx == 0 else c["ry"]
            raw = c["raw"][0:2] if idx == 0 else c["raw"][2:4]
            pygame.draw.rect(scr, C["panel"], (cx, cy, 124, 112), border_radius=10)
            t = self.small.render(label, True, C["dim"])
            scr.blit(t, (cx + 8, cy + 4))
            bx, by, bs = cx + 8, cy + 20, 84
            pygame.draw.rect(scr, (10, 12, 15), (bx, by, bs, bs), border_radius=6)
            pygame.draw.line(scr, (44, 52, 60), (bx + bs / 2, by), (bx + bs / 2, by + bs))
            pygame.draw.line(scr, (44, 52, 60), (bx, by + bs / 2), (bx + bs, by + bs / 2))
            vx = lx if idx == 0 else c["rx"]
            vy = ly if idx == 0 else c["ry"]
            kx = bx + bs / 2 + vx * bs / 2
            ky = by + bs / 2 - vy * bs / 2
            pygame.draw.circle(scr, C["accent"], (int(kx), int(ky)), 6)
            t = self.small.render(f"{raw[0]:+6d} {raw[1]:+6d}", True, C["text"])
            scr.blit(t, (bx + 2, by + bs + 2))
        # 双摇杆同用统计
        t = self.small.render(f"双摇杆同用 {self.dual_time:.1f}s", True, C["accent"])
        scr.blit(t, (W // 2 - t.get_width() / 2, H - 40))

    def draw_over(self):
        box = pygame.Rect(300, 170, 680, 300)
        pygame.draw.rect(self.screen, C["panel"], box, border_radius=16)
        pygame.draw.rect(self.screen, C["warn"], box, 2, border_radius=16)
        t1 = self.big.render("游戏结束", True, C["warn"])
        self.screen.blit(t1, (box.centerx - t1.get_width() // 2, box.y + 22))
        lines = [
            f"分数 {self.score}    到达第 {self.wave} 波    击杀 {self.kills}    "
            f"用时 {self.time_used:.0f}s",
            f"双摇杆同用 {self.dual_time:.1f}s    左摇杆最大偏移 {self.max_l:.2f}"
            f"    右摇杆 {self.max_r:.2f}",
            "按 START 重新开始",
        ]
        for i, ln in enumerate(lines):
            t = self.font.render(ln, True, C["text"])
            self.screen.blit(t, (box.centerx - t.get_width() // 2, box.y + 92 + i * 34))


def p_inv(p):
    return p["inv"] <= 0


def pt(x, y, color, vx=0.0, vy=0.0):
    return {"x": x, "y": y, "vx": vx, "vy": vy, "life": 0.5, "color": color}


def make_enemy(kind, x, y, wave):
    if kind == "chaser":
        return {"kind": kind, "x": x, "y": y, "vx": 0.0, "vy": 0.0,
                "hp": 2, "r": 14.0, "t": 0.0, "speed": min(210, 120 + 12 * wave),
                "fire_t": 9e9}
    return {"kind": kind, "x": x, "y": y, "vx": 0.0, "vy": 0.0,
            "hp": 3, "r": 15.0, "t": 0.0, "speed": 92,
            "fire_t": random.uniform(0.8, 1.6)}


def load_font(size):
    for path in ("C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simhei.ttf"):
        if os.path.exists(path):
            try:
                return pygame.font.Font(path, size)
            except Exception:
                continue
    return pygame.font.Font(None, size + 6)


def main(argv):
    selftest = "--selftest" in argv
    if selftest:
        os.environ["SDL_VIDEODRIVER"] = "dummy"
    game = Game(selftest=selftest)
    return game.run()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
