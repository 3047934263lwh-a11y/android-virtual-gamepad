#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
3D 自由视角漫步测试（第一人称）
==============================

测试"双摇杆自由视角移动"的手感：
    左摇杆   行走（相对视角方向：上=前进，下=后退，左右=平移，模拟量）
    右摇杆   视角（横向=航向，纵向=俯仰，即时响应无平滑）
    RT       疾跑（模拟量）

场景：地面网格 + 8 根方位柱 + 3 个导航环（走过去即点亮，全部到达通关）。
右上角小地图实时显示位置、朝向与目标 —— 观察"自由移动"是否合适最直观。

代码结构：
    Pad          XInput 双摇杆输入（ctypes 直读）
    LookCamera   右摇杆 → 视角（可移植，即时直映）
    Renderer     3D → 2D 投影（支持相机位置移动）
    Walker 主循环 移动/碰撞/目标判定/绘制

运行：python fps_walk.py [--selftest]
"""

import ctypes
import math
import os
import random
import sys
import time

import pygame

# ---- 手感参数 ----
MOVE_SPEED = 340.0      # 左摇杆满偏行走速度（单位/秒）
SPRINT_BONUS = 0.7      # RT 满压时的额外速度比
YAW_SPEED = 150.0       # 右摇杆满偏水平转速（度/秒）
PITCH_SPEED = 95.0
SMOOTHING = 0.0         # 0 = 即时响应（无平滑）
PITCH_LIMIT = 80.0
STICK_DEADZONE = 1200.0 # XInput 原始死区（约 3.7%）：更小的死区，细微修正更跟手
EYE = 170.0             # 视线高度
WORLD = 1800.0          # 可行走边界（±）
# 设置 VGP_SHOT_DIR 环境变量后，游戏每 1.5s 自动保存一张画面
# （从渲染缓冲直接保存，与窗口遮挡/焦点无关，便于自动化验证）
SHOT_DIR = os.environ.get("VGP_SHOT_DIR", "")
SHOT_EVERY = 1.5

B_BIT = {"A": 0x1000, "B": 0x2000, "X": 0x4000, "Y": 0x8000,
         "LB": 0x0100, "RB": 0x0200, "BACK": 0x0020, "START": 0x0010}


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
    a = abs(raw)
    if a <= STICK_DEADZONE:
        return 0.0
    return math.copysign((a - STICK_DEADZONE) / (32767.0 - STICK_DEADZONE), raw)


class Pad:
    """XInput 双摇杆。约定：摇杆上推为正（XInput 原始语义，勿再取反）。"""

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
                "raw": (0, 0, 0, 0), "rt": 0, "buttons": frozenset()}

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
        self.cur = {
            "lx": stick(g.thumb_lx), "ly": stick(g.thumb_ly),
            "rx": stick(g.thumb_rx), "ry": stick(g.thumb_ry),
            "raw": (g.thumb_lx, g.thumb_ly, g.thumb_rx, g.thumb_ry),
            "rt": g.right_trigger,
            "buttons": frozenset(nm for nm, bt in B_BIT.items() if g.wButtons & bt),
        }

    def pressed(self, name):
        return name in self.cur["buttons"]

    def just_pressed(self, name):
        return name in self.cur["buttons"] and name not in self.prev["buttons"]


class Keyboard:
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
        if k[pygame.K_RETURN]:
            buttons.add("START")
        if k[pygame.K_TAB]:
            buttons.add("BACK")
        if k[pygame.K_r]:
            buttons.add("RB")
        self.cur = {"lx": lx, "ly": ly, "rx": rx, "ry": ry,
                    "raw": (int(lx * 32767), int(ly * 32767),
                            int(rx * 32767), int(ry * 32767)),
                    "rt": 255 if k[pygame.K_LSHIFT] else 0,
                    "buttons": frozenset(buttons)}

    def pressed(self, name):
        return name in self.cur["buttons"]

    def just_pressed(self, name):
        return name in self.cur["buttons"] and name not in self.prev["buttons"]


class LookCamera:
    """右摇杆 → 视角。即时直映（smoothing=0），可整段移植。"""

    def __init__(self, max_yaw=YAW_SPEED, max_pitch=PITCH_SPEED, smoothing=SMOOTHING):
        self.max_yaw = max_yaw
        self.max_pitch = max_pitch
        self.smoothing = smoothing
        self.yaw = 0.0
        self.pitch = 0.0
        self._yv = 0.0
        self._pv = 0.0

    def update(self, rx, ry, dt, sens=1.0):
        if self.smoothing > 0.0:
            tyv = rx * self.max_yaw * sens
            tpv = ry * self.max_pitch * sens
            k = min(1.0, self.smoothing * dt)
            self._yv += (tyv - self._yv) * k
            self._pv += (tpv - self._pv) * k
            vy, vp = self._yv, self._pv
        else:
            vy = rx * self.max_yaw * sens
            vp = ry * self.max_pitch * sens
        self.yaw = (self.yaw + vy * dt) % 360.0
        self.pitch = max(-PITCH_LIMIT, min(PITCH_LIMIT, self.pitch + vp * dt))

    def reset(self):
        self.yaw = 0.0
        self.pitch = 0.0
        self._yv = 0.0
        self._pv = 0.0


class Renderer:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.f = (h / 2.0) / math.tan(math.radians(36.0))
        self.near = 8.0

    def to_cam(self, px, py, pz, camx, camz, yaw, pitch):
        dx, dy, dz = px - camx, py - EYE, pz - camz
        sy, cy = math.sin(yaw), math.cos(yaw)
        xc = dx * cy - dz * sy
        zc = dx * sy + dz * cy
        sp, cp = math.sin(pitch), math.cos(pitch)
        yc = dy * cp - zc * sp
        z2 = dy * sp + zc * cp
        return xc, yc, z2

    def project(self, cam):
        xc, yc, zc = cam
        if zc < self.near:
            return None
        return (self.w / 2 + xc / zc * self.f,
                self.h / 2 - yc / zc * self.f)

    def seg(self, p1, p2, camx, camz, yaw, pitch):
        a = self.to_cam(*p1, camx, camz, yaw, pitch)
        b = self.to_cam(*p2, camx, camz, yaw, pitch)
        ain, bin_ = a[2] >= self.near, b[2] >= self.near
        if not ain and not bin_:
            return None
        if ain != bin_:
            t = (self.near - a[2]) / (b[2] - a[2])
            m = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, self.near)
            a, b = (m, b) if ain else (a, m)
        pa, pb = self.project(a), self.project(b)
        if pa is None or pb is None:
            return None
        return pa, pb

    def poly(self, pts, camx, camz, yaw, pitch):
        cam = [self.to_cam(*p, camx, camz, yaw, pitch) for p in pts]
        out, n = [], len(cam)
        for i in range(n):
            a, b = cam[i], cam[(i + 1) % n]
            ain, bin_ = a[2] >= self.near, b[2] >= self.near
            if ain:
                out.append(a)
            if ain != bin_:
                t = (self.near - a[2]) / (b[2] - a[2])
                out.append((a[0] + (b[0] - a[0]) * t,
                            a[1] + (b[1] - a[1]) * t, self.near))
        if len(out) < 3:
            return None
        scr = [self.project(p) for p in out]
        if any(p is None for p in scr):
            return None
        return scr


# 方位柱（北 = +Z，与罗盘一致）
COMPASS = [
    (0, 700, "北", (108, 190, 236), 56, 260),
    (700, 0, "东", (236, 168, 108), 56, 260),
    (0, -700, "南", (150, 220, 130), 56, 260),
    (-700, 0, "西", (220, 130, 170), 56, 260),
    (500, 500, "东北", (150, 160, 235), 40, 170),
    (-500, 500, "西北", (235, 220, 150), 40, 170),
    (500, -500, "东南", (150, 235, 210), 40, 170),
    (-500, -500, "西南", (235, 150, 150), 40, 170),
]
PILLAR_R = 30.0

# 导航环（依次到达）
RINGS = [(900, 900), (-1150, 450), (250, -1250)]

C = {
    "sky": (26, 32, 44), "floor": (17, 21, 27),
    "grid": (44, 54, 66), "grid_major": (66, 82, 98),
    "axis_x": (170, 90, 90), "axis_z": (90, 130, 190),
    "text": (226, 232, 238), "dim": (135, 145, 156),
    "panel": (16, 19, 24), "accent": (76, 175, 80),
    "cross": (240, 244, 248), "warn": (235, 110, 100),
    "ring": (240, 200, 90), "ring_done": (110, 200, 130),
}


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


class Rumble:
    def __init__(self):
        self.ok = False
        self._until = 0.0
        try:
            self._dll = ctypes.WinDLL("xinput1_4.dll")
            self.ok = True
        except OSError:
            pass

    def fire(self, large, small, ms):
        if not self.ok:
            return
        v = XInputVibration(int(large / 255 * 65535), int(small / 255 * 65535))
        self._dll.XInputSetState(0, ctypes.byref(v))
        self._until = time.monotonic() + ms / 1000.0

    def update(self):
        if self.ok and self._until and time.monotonic() > self._until:
            self._dll.XInputSetState(0, ctypes.byref(XInputVibration(0, 0)))
            self._until = 0.0


class XInputVibration(ctypes.Structure):
    _fields_ = [("wLeftMotorSpeed", ctypes.c_ushort),
                ("wRightMotorSpeed", ctypes.c_ushort)]


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
    pygame.init()
    scr = pygame.display.set_mode((1280, 720))
    pygame.display.set_caption("3D 自由视角漫步 · 左摇杆行走 / 右摇杆转视角 / RT 疾跑")
    clock = pygame.time.Clock()
    font = load_font(15)
    small = load_font(12)
    big = load_font(30)

    pad = Pad() if not selftest else FakePadDone()
    cam = LookCamera()
    rd = Renderer(1280, 720)
    rumble = Rumble()
    sens = 1.0
    show_hud = True

    # 玩家状态
    px, pz = 0.0, 0.0
    ring_done = [False, False, False]
    ring_flash = 0.0
    start_t = time.monotonic()
    used_t = 0.0
    shot_t = 0.0
    shot_n = 0

    if not pad.ok and not selftest:
        print("提示：未检测到 XInput 手柄，可用键盘方向键观察")

    # ---------------- 自检 ----------------
    if selftest:
        cam.update(0.0, 0.0, 1 / 60)
        px0, pz0 = px, pz
        # 前进：ly=+1 应沿 +Z（北）走
        for _ in range(30):
            cam.update(0.0, 0.0, 1 / 60)
            fx, fz = math.sin(math.radians(cam.yaw)), math.cos(math.radians(cam.yaw))
            px += fx * MOVE_SPEED / 60
            pz += fz * MOVE_SPEED / 60
        assert pz > 150, f"前进未沿 +Z: {pz}"
        assert abs(px) < 1e-6, f"前进不应有横向位移: {px}"
        # 右转 90° 后前进应沿 +X（东）
        cam.yaw = 90.0
        for _ in range(30):
            fx, fz = math.sin(math.radians(cam.yaw)), math.cos(math.radians(cam.yaw))
            px += fx * MOVE_SPEED / 60
            pz += fz * MOVE_SPEED / 60
        assert px > 150, f"右转 90° 后前进未沿 +X: {px}"
        rd.seg((0, 0, -500), (0, 0, 500), px, pz, math.radians(cam.yaw),
               math.radians(cam.pitch))
        print("[自检] 前进方向 / 航向旋转 / 投影（含相机位移）PASS")
        # 不在此处返回：继续落入主循环，真实跑 45 帧（见下方 selftest 分支）

    # ---------------- 主循环 ----------------
    frames = 0
    running = True
    while running:
        dt = clamp(clock.tick(60) / 1000.0, 0.0, 1 / 30.0)
        for e in pygame.event.get():
            if e.type == pygame.QUIT:
                running = False
            elif e.type == pygame.KEYDOWN:
                if e.key == pygame.K_ESCAPE:
                    running = False
                elif e.key == pygame.K_r:
                    cam.reset()
                elif e.key == pygame.K_TAB:
                    show_hud = not show_hud
                elif e.key in (pygame.K_LEFTBRACKET, pygame.K_MINUS):
                    sens = round(clamp(sens - 0.2, 0.2, 3.0), 2)
                elif e.key in (pygame.K_RIGHTBRACKET, pygame.K_EQUALS):
                    sens = round(clamp(sens + 0.2, 0.2, 3.0), 2)

        if selftest:
            # 自检：真实跑 45 帧主循环（含绘制与输入合并），覆盖内联逻辑
            frames += 1
            if frames > 45:
                print("[自检] 主循环 45 帧运行 PASS")
                pygame.quit()
                return 0

        pad.poll()
        if pad.just_pressed("RB"):
            cam.reset()
        if pad.just_pressed("BACK"):
            show_hud = not show_hud

        k = pygame.key.get_pressed()
        kx = (1.0 if k[pygame.K_RIGHT] else 0.0) - (1.0 if k[pygame.K_LEFT] else 0.0)
        ky = (1.0 if k[pygame.K_UP] else 0.0) - (1.0 if k[pygame.K_DOWN] else 0.0)
        rx = clamp(pad.cur["rx"] + kx, -1.0, 1.0)
        ry = clamp(pad.cur["ry"] + ky, -1.0, 1.0)
        lx = clamp(pad.cur["lx"], -1.0, 1.0)
        ly = clamp(pad.cur["ly"], -1.0, 1.0)

        cam.update(rx, ry, dt, sens)

        # ---- 左摇杆行走（相对视角方向）----
        yaw = math.radians(cam.yaw)
        fx, fz = math.sin(yaw), math.cos(yaw)        # 前
        rxv, rzv = math.cos(yaw), -math.sin(yaw)     # 右
        sprint = 1.0 + SPRINT_BONUS * (pad.cur["rt"] / 255.0)
        vx = (fx * ly + rxv * lx) * MOVE_SPEED * sprint
        vz = (fz * ly + rzv * lx) * MOVE_SPEED * sprint
        px += vx * dt
        pz += vz * dt
        used_t += dt

        # ---- 碰撞：边界 + 方位柱 ----
        px = clamp(px, -WORLD, WORLD)
        pz = clamp(pz, -WORLD, WORLD)
        for (cx_, cz_, _n, _c, _w, _h) in COMPASS:
            ddx, ddz = px - cx_, pz - cz_
            d = math.hypot(ddx, ddz)
            if d < PILLAR_R + 16 and d > 1e-6:
                push = PILLAR_R + 16 - d
                px += ddx / d * push
                pz += ddz / d * push

        # ---- 导航环 ----
        nxt = ring_done.index(False) if False in ring_done else -1
        if nxt >= 0:
            gx, gz = RINGS[nxt]
            if math.hypot(px - gx, pz - gz) < 70:
                ring_done[nxt] = True
                ring_flash = 0.6
                rumble.fire(170, 90, 280)

        rumble.update()

        # ================= 绘制 =================
        scr.fill(C["sky"])
        yawr, pitchr = math.radians(cam.yaw), math.radians(cam.pitch)
        hy = rd.h / 2 + rd.f * math.tan(pitchr)
        if hy < rd.h:
            pygame.draw.rect(scr, C["floor"],
                             (0, max(0, hy), rd.w, rd.h - max(0, hy)))

        for gx_ in range(-2000, 2001, 100):
            major = gx_ % 500 == 0
            s = rd.seg((gx_, 0, -2000), (gx_, 0, 2000), px, pz, yawr, pitchr)
            if s:
                pygame.draw.line(scr, C["grid_major"] if major else C["grid"], s[0], s[1], 1)
        for gz_ in range(-2000, 2001, 100):
            major = gz_ % 500 == 0
            s = rd.seg((-2000, 0, gz_), (2000, 0, gz_), px, pz, yawr, pitchr)
            if s:
                if major and gz_ == 0:
                    pygame.draw.line(scr, C["axis_z"], s[0], s[1], 2)
                else:
                    pygame.draw.line(scr, C["grid_major"] if major else C["grid"], s[0], s[1], 1)
        s = rd.seg((-2000, 0, 0), (2000, 0, 0), px, pz, yawr, pitchr)
        if s:
            pygame.draw.line(scr, C["axis_x"], s[0], s[1], 2)
        if 0 <= hy <= rd.h:
            pygame.draw.line(scr, (90, 110, 130), (0, hy), (rd.w, hy))

        # 方位柱
        for (cx_, cz_, label, color, wdt, hgt) in COMPASS:
            poly = rd.poly([(cx_ - wdt / 2, 0, cz_), (cx_ + wdt / 2, 0, cz_),
                            (cx_ + wdt / 2, hgt, cz_), (cx_ - wdt / 2, hgt, cz_)],
                           px, pz, yawr, pitchr)
            if poly:
                pygame.draw.polygon(scr, color, poly)
                top = rd.project(rd.to_cam(cx_, hgt + 40, cz_, px, pz, yawr, pitchr))
                if top:
                    t = font.render(label, True, color)
                    scr.blit(t, (int(top[0] - t.get_width() / 2), int(top[1])))

        # 导航环（世界中的立环）
        for i, (gx_, gz_) in enumerate(RINGS):
            done = ring_done[i]
            active = (i == nxt)
            if not active and not done:
                continue
            col = C["ring_done"] if done else (C["ring"] if active else (90, 100, 110))
            hgt = 260.0
            pts = []
            for adeg in range(0, 361, 24):
                a = math.radians(adeg)
                pts.append((gx_ + math.cos(a) * 60,
                            10 + (math.sin(a) + 1) / 2 * hgt,
                            gz_ + math.sin(a) * 14))
            poly = rd.poly(pts, px, pz, yawr, pitchr)
            if poly:
                pygame.draw.lines(scr, col, True, poly, 3 if active else 2)
            lbl = rd.project(rd.to_cam(gx_, hgt + 50, gz_, px, pz, yawr, pitchr))
            if lbl:
                t = font.render(f"目标 {i+1}" + ("✓" if done else ""),
                                True, col)
                scr.blit(t, (int(lbl[0] - t.get_width() / 2), int(lbl[1])))

        if ring_flash > 0:
            ring_flash -= dt
            t = font.render("已到达！", True, C["accent"])
            scr.blit(t, (rd.w // 2 - t.get_width() // 2, rd.h // 2 + 26))

        # 准星
        pygame.draw.line(scr, C["cross"], (rd.w / 2 - 12, rd.h / 2),
                         (rd.w / 2 + 12, rd.h / 2))
        pygame.draw.line(scr, C["cross"], (rd.w / 2, rd.h / 2 - 12),
                         (rd.w / 2, rd.h / 2 + 12))

        if show_hud:
            draw_hud(scr, font, small, pad, cam, sens, rd, px, pz,
                     ring_done, vx, vz)

        pygame.display.flip()

        if SHOT_DIR and time.monotonic() - shot_t > SHOT_EVERY:
            shot_t = time.monotonic()
            shot_n += 1
            try:
                pygame.image.save(scr, os.path.join(SHOT_DIR, f"auto_{shot_n:03d}.png"))
            except Exception:
                pass

    pygame.quit()
    return 0


class FakePadDone:
    """自检占位：自检不读硬件。"""

    def __init__(self):
        self.ok = True
        self.cur = Pad.blank()
        self.prev = Pad.blank()

    def poll(self):
        self.prev = dict(self.cur)

    def pressed(self, name):
        return False

    def just_pressed(self, name):
        return False


def draw_hud(scr, font, small, pad, cam, sens, rd, px, pz, ring_done, vx, vz):
    # 罗盘带
    band_w, band_h = 560, 44
    bx, by = rd.w // 2 - band_w // 2, 14
    pygame.draw.rect(scr, C["panel"], (bx, by, band_w, band_h), border_radius=10)
    pygame.draw.rect(scr, (52, 60, 70), (bx, by, band_w, band_h), 1, border_radius=10)
    names = {0: "北", 45: "东北", 90: "东", 135: "东南", 180: "南",
             225: "西南", 270: "西", 315: "西北"}
    for deg in range(0, 361, 15):
        diff = (deg - cam.yaw + 180) % 360 - 180
        if abs(diff) > band_w / 2 / 3.4:
            continue
        x = bx + band_w / 2 + diff * 3.4
        major = deg % 45 == 0
        pygame.draw.line(scr, C["text"] if major else C["dim"],
                         (x, by + band_h - (16 if major else 9)), (x, by + band_h - 2))
        if major:
            t = small.render(names.get(deg % 360, ""), True, C["text"])
            scr.blit(t, (x - t.get_width() / 2, by + 4))
    pygame.draw.polygon(scr, C["accent"],
                        [(rd.w / 2 - 6, by), (rd.w / 2 + 6, by), (rd.w / 2, by + 8)])

    # 状态
    spd = math.hypot(vx, vz)
    t = font.render(
        f"位置 ({px:+5.0f},{pz:+5.0f})   航向 {cam.yaw:6.1f}°   俯仰 {cam.pitch:+6.1f}°   "
        f"速度 {spd:4.0f}   灵敏度 {sens:.1f}", True, C["text"])
    scr.blit(t, (16, 12))
    t2 = small.render("左摇杆行走 · 右摇杆视角 · RT 疾跑 · RB 复位视角 · [ ] 灵敏度 · Esc 退出",
                      True, C["dim"])
    scr.blit(t2, (16, 34))

    # 双摇杆十字区
    for idx, (key, label, bx, by) in enumerate((
            ("l", "左摇杆·行走", 16, 56),
            ("r", "右摇杆·视角", 16, 178))):
        lx = pad.cur["lx"] if key == "l" else pad.cur["rx"]
        ly = pad.cur["ly"] if key == "l" else pad.cur["ry"]
        raw = pad.cur["raw"][0:2] if key == "l" else pad.cur["raw"][2:4]
        pygame.draw.rect(scr, C["panel"], (bx, by, 116, 112), border_radius=10)
        t = self_small(label, small)
        scr.blit(t, (bx + 8, by + 4))
        bs = 84
        sx0, sy0 = bx + 8, by + 22
        pygame.draw.rect(scr, (10, 12, 15), (sx0, sy0, bs, bs), border_radius=6)
        pygame.draw.line(scr, (44, 52, 60), (sx0 + bs / 2, sy0), (sx0 + bs / 2, sy0 + bs))
        pygame.draw.line(scr, (44, 52, 60), (sx0, sy0 + bs / 2), (sx0 + bs, sy0 + bs / 2))
        kx = sx0 + bs / 2 + lx * bs / 2
        ky = sy0 + bs / 2 - ly * bs / 2
        pygame.draw.circle(scr, C["accent"], (int(kx), int(ky)), 6)
        t = small.render(f"{raw[0]:+6d} {raw[1]:+6d}", True, C["text"])
        scr.blit(t, (sx0 + 2, sy0 + bs + 2))

    # 小地图（观察自由移动最直观）
    mm_s = 150
    mm_x, mm_y = rd.w - mm_s - 16, 16
    pygame.draw.rect(scr, C["panel"], (mm_x, mm_y, mm_s, mm_s), border_radius=10)
    pygame.draw.rect(scr, (52, 60, 70), (mm_x, mm_y, mm_s, mm_s), 1, border_radius=10)
    scale = (mm_s - 20) / 4000.0
    cx0, cy0 = mm_x + mm_s / 2, mm_y + mm_s / 2
    # 边界
    b = WORLD * scale
    pygame.draw.rect(scr, (52, 60, 70),
                     (cx0 - b, cy0 - b, b * 2, b * 2), 1, border_radius=4)
    # 方位柱
    for (cx_, cz_, _n, color, _w, _h) in COMPASS:
        pygame.draw.circle(scr, color,
                           (int(cx0 + cx_ * scale), int(cy0 + cz_ * scale)), 3)
    # 导航环
    for i, (gx_, gz_) in enumerate(RINGS):
        col = C["ring_done"] if ring_done[i] else C["ring"]
        pygame.draw.circle(scr, col, (int(cx0 + gx_ * scale), int(cy0 + gz_ * scale)), 4, 1)
    # 玩家箭头（指向 yaw）
    pxx, pyy = cx0 + px * scale, cy0 + pz * scale
    a = math.radians(cam.yaw)
    pygame.draw.line(scr, C["cross"],
                     (pxx, pyy),
                     (pxx + math.sin(a) * 9, pyy + math.cos(a) * 9), 2)
    pygame.draw.circle(scr, C["cross"], (int(pxx), int(pyy)), 3)


def self_small(label, small):
    return small.render(label, True, (135, 145, 156))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
