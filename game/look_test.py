#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
右摇杆视角控制测试
==================

专门测试虚拟 Xbox 360 手柄的**右摇杆视角**：
    右摇杆横向  →  视角左右旋转（航向 yaw）
    右摇杆纵向  →  视角上下俯仰（俯仰 pitch）
转动平滑、灵敏度适中，场景含地面网格与多个方位参照物，便于直观观察转动效果。

代码结构（便于移植）
--------------------
    Pad          XInput 输入层（ctypes 直读，与真实游戏同路径）
    LookCamera   ★ 右摇杆 → 视角 的全部逻辑，自包含，可整段拷贝到其他游戏
    Renderer     网格/参照物/罗盘带的 3D→2D 投影绘制
    main         组装与主循环

移植到其他游戏只需要两步：
    cam = LookCamera()
    每帧调用 cam.update(rx, ry, dt)   # rx/ry ∈ [-1,1]，上推为正
    然后使用 cam.yaw（航向）与 cam.pitch（俯仰）

键盘兜底：←→ 转向，↑↓ 俯仰，R 复位，Tab 面板，[ ] 调灵敏度，Esc 退出
运行：python look_test.py [--selftest]
"""

import ctypes
import math
import os
import sys
import time

import pygame

# ============================================================================
# 右摇杆 → 视角参数（全部可调）
# ============================================================================

YAW_SPEED = 150.0     # 满偏水平转速（度/秒）
PITCH_SPEED = 95.0    # 满偏俯仰转速（度/秒）
SMOOTHING = 0.0       # 平滑系数：0 = 关闭（即时响应，推荐）；>0 会产生加减速手感
INVERT_Y = False      # 俯仰反向（有人习惯推上=低头）
PITCH_LIMIT = 80.0    # 俯仰上限（度）
STICK_DEADZONE = 2000.0   # XInput 原始死区（约 6%），越出后重新归一化


# ============================================================================
# XInput 输入层
# ============================================================================

XINPUT_GAMEPAD_A = 0x1000
XINPUT_GAMEPAD_B = 0x2000
XINPUT_GAMEPAD_X = 0x4000
XINPUT_GAMEPAD_Y = 0x8000
XINPUT_GAMEPAD_LB = 0x0100
XINPUT_GAMEPAD_RB = 0x0200
XINPUT_GAMEPAD_BACK = 0x0020
XINPUT_GAMEPAD_START = 0x0010


class XInputState(ctypes.Structure):
    # 注意字段顺序：packet, wButtons, LT, RT, LX, LY, RX, RY
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
    """死区处理 + 重新归一化，保证满偏仍能到 1.0。"""
    a = abs(raw)
    if a <= STICK_DEADZONE:
        return 0.0
    return math.copysign((a - STICK_DEADZONE) / (32767.0 - STICK_DEADZONE), raw)


class Pad:
    """XInput 轮询。右摇杆 = axes rx/ry（上推为正）。"""

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
        self.rx = 0.0
        self.ry = 0.0
        self.raw_rx = 0
        self.raw_ry = 0
        self.buttons = frozenset()

    def poll(self):
        if not self.ok:
            self.rx = self.ry = 0.0
            self.buttons = frozenset()
            return
        rc = self._dll.XInputGetState(0, ctypes.byref(self._st))
        if rc != 0:
            self.rx = self.ry = 0.0
            self.buttons = frozenset()
            return
        g = self._st
        # XInput 约定：摇杆上推为正，这里保持原值，切勿再取反
        self.raw_rx = g.thumb_rx
        self.raw_ry = g.thumb_ry
        self.rx = stick(self.raw_rx)
        self.ry = stick(self.raw_ry)
        w = g.wButtons
        self.buttons = frozenset(name for bit, name in [
            (XINPUT_GAMEPAD_A, "A"), (XINPUT_GAMEPAD_B, "B"),
            (XINPUT_GAMEPAD_X, "X"), (XINPUT_GAMEPAD_Y, "Y"),
            (XINPUT_GAMEPAD_LB, "LB"), (XINPUT_GAMEPAD_RB, "RB"),
            (XINPUT_GAMEPAD_BACK, "BACK"), (XINPUT_GAMEPAD_START, "START"),
        ] if w & bit)

    def pressed(self, name):
        return name in self.buttons

    def just_pressed(self, name, _prev=[set()]):
        was = name in _prev[0]
        _prev[0] = set(self.buttons)
        return name in self.buttons and not was


# ============================================================================
# ★ 右摇杆 → 视角（可移植段：本类不依赖 pygame / XInput）
# ============================================================================

class LookCamera:
    """
    即时视角控制（默认无任何平滑/插值）。

    update(rx, ry, dt):
        rx ∈ [-1,1]  右推为正 → 向右转
        ry ∈ [-1,1]  上推为正 → 向上看（INVERT_Y 可反向）
        dt           帧间隔（秒）

    输出:
        self.yaw    航向（度，右转为正，模 360）
        self.pitch  俯仰（度，抬头为正，±PITCH_LIMIT）

    响应特性：角速度 = 摇杆偏移 × 最大转速，逐帧直映。
    推杆瞬间即达目标转速，松手瞬间停止——干脆利落，零延迟感。
    smoothing > 0 时才启用速度平滑（会产生加减速手感，默认关闭）。
    """

    def __init__(self, max_yaw=YAW_SPEED, max_pitch=PITCH_SPEED,
                 smoothing=SMOOTHING, invert_y=INVERT_Y):
        self.max_yaw = max_yaw
        self.max_pitch = max_pitch
        self.smoothing = smoothing
        self.invert_y = invert_y
        self.yaw = 0.0
        self.pitch = 0.0
        self._yaw_vel = 0.0
        self._pitch_vel = 0.0

    def update(self, rx, ry, dt, sens=1.0):
        ry = -ry if self.invert_y else ry

        if self.smoothing > 0.0:
            # 可选平滑：仅在明确想要"缓动"手感时把 smoothing 调大
            tgt_yv = rx * self.max_yaw * sens
            tgt_pv = ry * self.max_pitch * sens
            k = min(1.0, self.smoothing * dt)
            self._yaw_vel += (tgt_yv - self._yaw_vel) * k
            self._pitch_vel += (tgt_pv - self._pitch_vel) * k
            vy, vp = self._yaw_vel, self._pitch_vel
        else:
            # 即时直映：无插值、无加减速
            vy = rx * self.max_yaw * sens
            vp = ry * self.max_pitch * sens

        self.yaw = (self.yaw + vy * dt) % 360.0
        self.pitch = max(-PITCH_LIMIT, min(PITCH_LIMIT, self.pitch + vp * dt))

    def reset(self):
        self.yaw = 0.0
        self.pitch = 0.0
        self._yaw_vel = 0.0
        self._pitch_vel = 0.0


# ============================================================================
# 3D → 2D 投影
# ============================================================================

class Renderer:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.fov = math.radians(72.0)
        self.f = (h / 2.0) / math.tan(self.fov / 2.0)
        self.near = 8.0
        self.eye = 170.0          # 视线高度

    def to_cam(self, px, py, pz, yaw, pitch):
        """世界坐标 → 相机坐标 (xc, yc, zc)。zc 越大越远。"""
        dx, dy, dz = px, py - self.eye, pz
        sy, cy = math.sin(yaw), math.cos(yaw)
        xc = dx * cy - dz * sy
        zc = dx * sy + dz * cy
        sp, cp = math.sin(pitch), math.cos(pitch)
        yc = dy * cp - zc * sp
        z2 = dy * sp + zc * cp
        return xc, yc, z2

    def project(self, cam):
        """相机坐标 → 屏幕。zc < near 返回 None。"""
        xc, yc, zc = cam
        if zc < self.near:
            return None
        return (self.w / 2 + xc / zc * self.f,
                self.h / 2 - yc / zc * self.f)

    def seg(self, p1w, p2w, yaw, pitch):
        """世界线段 → 屏幕线段，带近平面裁剪。不可见返回 None。"""
        a = self.to_cam(*p1w, yaw, pitch)
        b = self.to_cam(*p2w, yaw, pitch)
        ain, bin_ = a[2] >= self.near, b[2] >= self.near
        if not ain and not bin_:
            return None
        if ain != bin_:
            t = (self.near - a[2]) / (b[2] - a[2])
            m = (a[0] + (b[0] - a[0]) * t,
                 a[1] + (b[1] - a[1]) * t,
                 self.near)
            a, b = (m, b) if ain else (a, m)
        pa, pb = self.project(a), self.project(b)
        if pa is None or pb is None:
            return None
        return pa, pb

    def poly(self, pts_w, yaw, pitch):
        """世界多边形 → 屏幕多边形（近平面 Sutherland-Hodgman 裁剪）。"""
        cam = [self.to_cam(*p, yaw, pitch) for p in pts_w]
        out, n = [], len(cam)
        for i in range(n):
            a, b = cam[i], cam[(i + 1) % n]
            ain, bin_ = a[2] >= self.near, b[2] >= self.near
            if ain:
                out.append(a)
            if ain != bin_:
                t = (self.near - a[2]) / (b[2] - a[2])
                out.append((a[0] + (b[0] - a[0]) * t,
                            a[1] + (b[1] - a[1]) * t,
                            self.near))
        if len(out) < 3:
            return None
        scr = [self.project(p) for p in out]
        if any(p is None for p in scr):
            return None
        return scr

    def horizon_y(self, pitch):
        return self.h / 2 + self.f * math.tan(pitch)


# ============================================================================
# 场景参照物
# ============================================================================

COMPASS = [
    # (x, z, 标签, 颜色, 宽, 高)
    (0, -700, "北", (108, 190, 236), 56, 260),
    (700, 0, "东", (236, 168, 108), 56, 260),
    (0, 700, "南", (150, 220, 130), 56, 260),
    (-700, 0, "西", (220, 130, 170), 56, 260),
    (500, -500, "东北", (150, 160, 235), 40, 170),
    (-500, -500, "西北", (235, 220, 150), 40, 170),
    (500, 500, "东南", (150, 235, 210), 40, 170),
    (-500, 500, "西南", (235, 150, 150), 40, 170),
]


# ============================================================================
# 主程序
# ============================================================================

C = {
    "sky": (26, 32, 44), "floor": (18, 22, 28),
    "grid": (44, 54, 66), "grid_major": (66, 82, 98),
    "axis_x": (170, 90, 90), "axis_z": (90, 130, 190),
    "text": (226, 232, 238), "dim": (140, 150, 160),
    "panel": (16, 19, 24), "accent": (76, 175, 80),
    "cross": (240, 244, 248), "warn": (230, 120, 110),
}


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def main(argv):
    selftest = "--selftest" in argv
    if selftest:
        os.environ["SDL_VIDEODRIVER"] = "dummy"
    pygame.init()
    scr = pygame.display.set_mode((1280, 720))
    pygame.display.set_caption("右摇杆视角测试 · 右推=右转 上推=抬头")
    clock = pygame.time.Clock()
    big = load_font(28)
    font = load_font(15)
    small = load_font(12)

    pad = Pad()
    cam = LookCamera()
    rd = Renderer(1280, 720)
    sens = 1.0
    show_hud = True
    aligned_flash = 0.0

    if not pad.ok and not selftest:
        print("提示：未检测到 XInput 手柄，可先用键盘方向键观察")

    # ---------------- 自检 ----------------
    if selftest:
        # 即时响应回归测试：单帧必须精确达到目标角速度，不得有缓动/延迟
        cam.reset()
        cam.update(1.0, 0.0, 1 / 60.0)
        expect = YAW_SPEED / 60.0
        assert abs(cam.yaw - expect) < 1e-6, f"单帧响应存在平滑延迟: {cam.yaw} != {expect}"
        cam.update(0.0, 0.0, 1 / 60.0)
        assert abs(cam.yaw - expect) < 1e-6, "松手后视角仍在转动（存在平滑残留）"
        for _ in range(40):
            cam.update(1.0, 0.3, 1 / 60.0)
        assert cam.yaw > 60, f"yaw 未增长: {cam.yaw}"
        assert cam.pitch > 10, f"pitch 未增长: {cam.pitch}"
        cam.reset()
        assert abs(cam.yaw) < 1e-6
        rd.seg((0, 0, -500), (0, 0, 500), 0.0, 0.0)
        rd.poly([(0, 0, -400), (80, 0, -400), (80, 260, -400), (0, 260, -400)], 0.0, 0.0)
        print("[自检] 即时响应（单帧直映，无平滑）/ 复位 / 投影 PASS")
        return 0

    # ---------------- 主循环 ----------------
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

        pad.poll()
        if pad.just_pressed("RB") or pygame.key.get_pressed()[pygame.K_r]:
            cam.reset()
        if pad.just_pressed("BACK"):
            show_hud = not show_hud

        # 键盘兜底输入（与右摇杆叠加）
        k = pygame.key.get_pressed()
        kx = (1.0 if k[pygame.K_RIGHT] else 0.0) - (1.0 if k[pygame.K_LEFT] else 0.0)
        ky = (1.0 if k[pygame.K_UP] else 0.0) - (1.0 if k[pygame.K_DOWN] else 0.0)
        rx = clamp(pad.rx + kx, -1.0, 1.0)
        ry = clamp(pad.ry + ky, -1.0, 1.0)

        cam.update(rx, ry, dt, sens)

        # -------- 绘制 --------
        yaw = math.radians(cam.yaw)
        pitch = math.radians(cam.pitch)

        # 天空 / 地面（按俯仰移动的地平线分割）
        hy = rd.horizon_y(pitch)
        scr.fill(C["sky"])
        if hy < rd.h:
            pygame.draw.rect(scr, C["floor"], (0, max(0, hy), rd.w, rd.h - max(0, hy)))

        # 地面网格（大格 500 亮，小格 100 暗）
        for gx in range(-2000, 2001, 100):
            major = gx % 500 == 0
            s = rd.seg((gx, 0, -2000), (gx, 0, 2000), yaw, pitch)
            if s:
                pygame.draw.line(scr, C["grid_major"] if major else C["grid"], s[0], s[1], 1)
        for gz in range(-2000, 2001, 100):
            major = gz % 500 == 0
            s = rd.seg((-2000, 0, gz), (2000, 0, gz), yaw, pitch)
            if s:
                if major and gz == 0:
                    pygame.draw.line(scr, C["axis_z"], s[0], s[1], 2)
                else:
                    pygame.draw.line(scr, C["grid_major"] if major else C["grid"], s[0], s[1], 1)
        # X 轴（左右方向基准线）
        s = rd.seg((-2000, 0, 0), (2000, 0, 0), yaw, pitch)
        if s:
            pygame.draw.line(scr, C["axis_x"], s[0], s[1], 2)

        # 地平线
        if 0 <= hy <= rd.h:
            pygame.draw.line(scr, (90, 110, 130), (0, hy), (rd.w, hy), 1)

        # 方位参照柱
        for (px, pz, label, color, wdt, hgt) in COMPASS:
            poly = rd.poly([(px - wdt / 2, 0, pz), (px + wdt / 2, 0, pz),
                            (px + wdt / 2, hgt, pz), (px - wdt / 2, hgt, pz)],
                           yaw, pitch)
            if poly:
                pygame.draw.polygon(scr, color, poly)
                top = rd.project(rd.to_cam(px, hgt + 40, pz, yaw, pitch))
                if top:
                    t = font.render(label, True, color)
                    scr.blit(t, (int(top[0] - t.get_width() / 2), int(top[1])))

        # 正前目标环（yaw=0 pitch≈0 时与准星重合，用于复位测试）
        tgt = rd.project(rd.to_cam(0, rd.eye + 30, -620, yaw, pitch))
        if tgt:
            d = math.hypot(tgt[0] - rd.w / 2, tgt[1] - rd.h / 2)
            hit = d < 20
            col = C["accent"] if hit else (200, 205, 210)
            pygame.draw.circle(scr, col, (int(tgt[0]), int(tgt[1])), 16, 2)
            pygame.draw.circle(scr, col, (int(tgt[0]), int(tgt[1])), 3)
            if hit:
                aligned_flash = 0.4
        if aligned_flash > 0:
            aligned_flash -= dt
            t = font.render("已对准初始朝向", True, C["accent"])
            scr.blit(t, (rd.w // 2 - t.get_width() // 2, rd.h // 2 + 26))

        # 准星
        pygame.draw.line(scr, C["cross"], (rd.w / 2 - 12, rd.h / 2), (rd.w / 2 + 12, rd.h / 2))
        pygame.draw.line(scr, C["cross"], (rd.w / 2, rd.h / 2 - 12), (rd.w / 2, rd.h / 2 + 12))

        if show_hud:
            draw_hud(scr, font, small, big, pad, cam, sens, rd)

        pygame.display.flip()

    pygame.quit()
    return 0


def draw_hud(scr, font, small, big, pad, cam, sens, rd):
    # ---- 罗盘刻度带 ----
    band_w, band_h = 560, 44
    bx, by = rd.w // 2 - band_w // 2, 14
    pygame.draw.rect(scr, C["panel"], (bx, by, band_w, band_h), border_radius=10)
    pygame.draw.rect(scr, (52, 60, 70), (bx, by, band_w, band_h), 1, border_radius=10)
    px_per_deg = 3.4
    names = {0: "北", 45: "东北", 90: "东", 135: "东南", 180: "南",
             225: "西南", 270: "西", 315: "西北"}
    for deg in range(0, 361, 15):
        diff = (deg - cam.yaw + 180) % 360 - 180
        if abs(diff) > band_w / 2 / px_per_deg:
            continue
        x = bx + band_w / 2 + diff * px_per_deg
        major = deg % 45 == 0
        pygame.draw.line(scr, C["text"] if major else C["dim"],
                         (x, by + band_h - (16 if major else 9)), (x, by + band_h - 2))
        if major:
            t = small.render(names.get(deg % 360, ""), True, C["text"])
            scr.blit(t, (x - t.get_width() / 2, by + 4))
    # 中心指针
    pygame.draw.polygon(scr, C["accent"],
                        [(rd.w / 2 - 6, by), (rd.w / 2 + 6, by), (rd.w / 2, by + 8)])

    # ---- 状态文字 ----
    t = font.render(f"航向 {cam.yaw:6.1f}°   俯仰 {cam.pitch:+6.1f}°   "
                    f"灵敏度 {sens:.1f}   （[ ] 调整）", True, C["text"])
    scr.blit(t, (16, 12))
    t2 = small.render("右摇杆转向 · RB 复位 · R 复位 · Tab 面板 · Esc 退出", True, C["dim"])
    scr.blit(t2, (16, 34))

    # ---- 右摇杆十字区 ----
    bx, by, bs = 16, 56, 100
    pygame.draw.rect(scr, C["panel"], (bx, by, bs, bs), border_radius=8)
    pygame.draw.line(scr, (44, 52, 60), (bx + bs / 2, by), (bx + bs / 2, by + bs))
    pygame.draw.line(scr, (44, 52, 60), (bx, by + bs / 2), (bx + bs, by + bs / 2))
    pygame.draw.circle(scr, (56, 66, 76), (bx + bs // 2, by + bs // 2),
                       int(bs / 2 * STICK_DEADZONE / 32767), 1)
    kx = bx + bs / 2 + pad.rx * bs / 2
    ky = by + bs / 2 - pad.ry * bs / 2
    pygame.draw.circle(scr, C["accent"], (int(kx), int(ky)), 7)
    t = small.render(f"RX {pad.raw_rx:+6d}", True, C["text"])
    scr.blit(t, (bx, by + bs + 4))
    t = small.render(f"RY {pad.raw_ry:+6d}", True, C["text"])
    scr.blit(t, (bx, by + bs + 20))

    if not pad.ok:
        t = small.render("未检测到 XInput 手柄 —— 方向键可用", True, C["warn"])
        scr.blit(t, (bx, by + bs + 40))


def load_font(size):
    # 直接加载字体文件：pygame 的 SysFont 在部分机器枚举注册表会崩溃
    for path in ("C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simhei.ttf"):
        if os.path.exists(path):
            try:
                return pygame.font.Font(path, size)
            except Exception:
                continue
    return pygame.font.Font(None, size + 6)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
