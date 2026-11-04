#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
虚拟 Xbox 360 手柄 · PC 接收端 v2.0（本地多人版）

支持最多 4 台手机同时连接，每台手机分配一个独立的虚拟 Xbox 360 手柄
（按来源 IP 分配 1P-4P，断线重连保持原编号），支持每手柄独立的震动回传。

依赖：
    pip install vgamepad
    Windows 上必须先安装 ViGEmBus 1.22.0。

用法：
    python server.py [端口] [--debug]
"""

import argparse
import select
import socket
import struct
import sys
import time

try:
    import vgamepad as vg
except ImportError:
    print("[错误] 缺少 vgamepad，请先执行：pip install vgamepad")
    sys.exit(1)

MAGIC = b"VGP1"
VERSION = 1
PACKET_SIZE = 20
IDLE_RESET_SEC = 1.2
MAX_PLAYERS = 4          # XInput 最多 4 个手柄
# 离线超过该秒数就回收槽位。短暂断线（< TTL）仍保持原编号；
# 超时后才让位给新设备，避免累计 4 台手机后第 5 台永久被拒。
SLOT_TTL_SEC = 300.0

BTN_MAP = (
    (1 << 0, vg.XUSB_BUTTON.XUSB_GAMEPAD_A),
    (1 << 1, vg.XUSB_BUTTON.XUSB_GAMEPAD_B),
    (1 << 2, vg.XUSB_BUTTON.XUSB_GAMEPAD_X),
    (1 << 3, vg.XUSB_BUTTON.XUSB_GAMEPAD_Y),
    (1 << 4, vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER),
    (1 << 5, vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER),
    (1 << 6, vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK),
    (1 << 7, vg.XUSB_BUTTON.XUSB_GAMEPAD_START),
    (1 << 8, vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB),
    (1 << 9, vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB),
    (1 << 10, vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP),
    (1 << 11, vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN),
    (1 << 12, vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT),
    (1 << 13, vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT),
    (1 << 14, vg.XUSB_BUTTON.XUSB_GAMEPAD_GUIDE),
)

BTN_NAMES = (
    (1 << 0, "A"), (1 << 1, "B"), (1 << 2, "X"), (1 << 3, "Y"),
    (1 << 4, "LB"), (1 << 5, "RB"), (1 << 6, "BACK"), (1 << 7, "START"),
    (1 << 8, "L3"), (1 << 9, "R3"),
    (1 << 10, "UP"), (1 << 11, "DOWN"), (1 << 12, "LEFT"), (1 << 13, "RIGHT"),
    (1 << 14, "GUIDE"),
)


def describe(buttons, lx, ly, rx, ry, lt, rt):
    names = [n for bit, n in BTN_NAMES if buttons & bit]
    return "按键[{}] 摇杆L({},{}) R({},{}) LT={} RT={}".format(
        " ".join(names) if names else "-",
        lx, ly, rx, ry, lt, rt,
    )


def parse(data: bytes):
    """解析 20 字节报文，失败返回 None。"""
    if len(data) < PACKET_SIZE:
        return None
    if data[:4] != MAGIC or data[4] != VERSION:
        return None
    buttons = data[5] | (data[6] << 8)
    seq = data[7]
    lx, ly, rx, ry = struct.unpack_from("<hhhh", data, 8)
    return buttons, lx, ly, rx, ry, data[16], data[17], seq


def apply_state(pad, buttons, lx, ly, rx, ry, lt, rt):
    for bit, btn in BTN_MAP:
        if buttons & bit:
            pad.press_button(button=btn)
        else:
            pad.release_button(button=btn)

    pad.left_joystick(x_value=lx, y_value=ly)
    pad.right_joystick(x_value=rx, y_value=ry)
    pad.left_trigger(value=lt)
    pad.right_trigger(value=rt)


def reset_state(pad):
    for _, btn in BTN_MAP:
        pad.release_button(button=btn)
    pad.left_joystick(x_value=0, y_value=0)
    pad.right_joystick(x_value=0, y_value=0)
    pad.left_trigger(value=0)
    pad.right_trigger(value=0)


class Player:
    """一名玩家 = 一个来源 IP + 一个独立虚拟手柄。"""

    __slots__ = ("idx", "pad", "addr", "last_rx", "neutral",
                 "last_seq", "packets", "lost", "last_shown")

    def __init__(self, idx: int, pad, addr):
        self.idx = idx              # 0..3（0 即 1P）
        self.pad = pad
        self.addr = addr            # 最后一个包的来源 (ip, port)，用于震动回传
        self.last_rx = time.monotonic()
        self.neutral = True
        self.last_seq = -1
        self.packets = 0
        self.lost = 0
        self.last_shown = None


def make_rumble_cb(sock, players, idx: int, debug: bool):
    """为第 idx 号手柄生成震动回调：游戏对该手柄的震动 → UDP 回传到对应手机。"""

    def on_feedback(client, target, large_motor, small_motor, led_number, user_data):
        # 驱动线程回调：通过 idx 找到该手柄归属的手机地址。
        # 注意：本回调运行在驱动线程，而主循环可能同时向 players 插入新玩家，
        # 直接迭代 dict 会触发 "dictionary changed size" —— 必须先拍快照。
        for p in list(players.values()):
            if p.idx == idx:
                a = p.addr
                if a is not None:
                    try:
                        sock.sendto(
                            b"VGPR" + bytes(
                                [1, int(large_motor) & 0xFF,
                                 int(small_motor) & 0xFF, 0]),
                            (a[0], a[1]),
                        )
                    except OSError:
                        pass
                if debug:
                    print(f"\n[{idx + 1}P 震动] 大={large_motor} 小={small_motor}",
                          flush=True)
                return
    return on_feedback


def main() -> int:
    parser = argparse.ArgumentParser(description="虚拟 Xbox 360 手柄 PC 接收端（本地多人版）")
    parser.add_argument("port", nargs="?", type=int, default=26760)
    parser.add_argument("--debug", action="store_true", help="打印每次输入状态变化")
    args = parser.parse_args()

    print("=" * 62)
    print("  虚拟 Xbox 360 手柄 · PC 接收端 v2.0 本地多人版")
    print(f"  支持最多 {MAX_PLAYERS} 台手机同时连接（每台一个独立手柄）")
    print("  依赖: ViGEmBus 1.22.0 + pip install vgamepad")
    print("=" * 62)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("0.0.0.0", args.port))
    except OSError as exc:
        hint = "（可能已有另一个接收端在运行，请先关闭它）" if "10048" in str(exc) or "only one" in str(exc).lower() else ""
        print(f"[错误] 无法绑定 UDP {args.port}: {exc}\n{hint}")
        return 1

    print(f"[监听] UDP 0.0.0.0:{args.port}   等待手机端连接…\n")

    players: dict = {}          # 来源 IP -> Player
    free_slots = list(range(MAX_PLAYERS))
    full_warn_at = 0.0
    last_report = time.monotonic()

    sock.setblocking(False)

    try:
        while True:
            # ---- 等待数据（最长 4ms；有包立即唤醒） ----
            r, _, _ = select.select([sock], [], [], 0.004)
            now = time.monotonic()

            # ---- 排空接收队列：保留"每个来源的最新一包" ----
            # 多人模式下不能只留全局最新一包（会丢掉其他玩家的状态），
            # 因此按来源 IP 分别保留各自最新的报文。
            latest = {}
            drained = 0
            if r:
                try:
                    while True:
                        data, addr = sock.recvfrom(64)
                        latest[addr[0]] = (data, addr)
                        drained += 1
                except BlockingIOError:
                    pass

            if args.debug and drained > MAX_PLAYERS * 8:
                print(f"\n[积压] 本轮丢弃 {drained - MAX_PLAYERS} 包陈旧报文", flush=True)

            # ---- 逐玩家应用最新状态 ----
            for ip, (data, addr) in latest.items():
                parsed = parse(data)
                if parsed is None:
                    continue
                buttons, lx, ly, rx, ry, lt, rt, seq = parsed

                p = players.get(ip)
                if p is None:
                    # 新玩家：分配手柄槽位
                    if not free_slots:
                        if now - full_warn_at > 3.0:
                            print(f"\n[满员] {MAX_PLAYERS} 个手柄槽位已满，"
                                  f"忽略新设备 {ip}", flush=True)
                            full_warn_at = now
                        continue
                    idx = free_slots.pop(0)
                    try:
                        pad = vg.VX360Gamepad()
                    except Exception as exc:  # noqa: BLE001
                        free_slots.insert(0, idx)
                        print(f"\n[错误] 创建虚拟手柄 {idx + 1} 失败: {exc}", flush=True)
                        continue
                    try:
                        pad.register_notification(
                            make_rumble_cb(sock, players, idx, args.debug))
                    except Exception as exc:  # noqa: BLE001
                        print(f"[震动] {idx + 1}P 力反馈回调注册失败: {exc}", flush=True)
                    p = Player(idx, pad, addr)
                    players[ip] = p
                    try:
                        # 告知手机端它的玩家编号（App 顶部会显示）
                        sock.sendto(b"VGPP" + bytes([1, idx + 1, 0, 0]), addr)
                    except OSError:
                        pass
                    print(f"\n[{idx + 1}P] 手机 {ip}:{addr[1]} 已连接 → "
                          f"虚拟手柄 {idx + 1}", flush=True)

                prev = p.addr
                p.addr = addr
                # App 重启后源端口会变：重发玩家编号，让新会话立即知道自己是几P
                if prev is None or prev[1] != addr[1]:
                    try:
                        sock.sendto(b"VGPP" + bytes([1, p.idx + 1, 0, 0]), addr)
                    except OSError:
                        pass

                apply_state(p.pad, buttons, lx, ly, rx, ry, lt, rt)
                p.pad.update()

                if args.debug:
                    cur = (buttons, lx, ly, rx, ry, lt, rt)
                    if cur != p.last_shown:
                        p.last_shown = cur
                        print(f"\n[{p.idx + 1}P 输入] {describe(*cur)}", flush=True)

                p.neutral = False
                p.last_rx = now
                p.packets += 1

                if p.last_seq >= 0:
                    expected = (p.last_seq + 1) & 0xFF
                    gap = (seq - expected) & 0xFF
                    if 0 < gap < 128:
                        p.lost += gap
                p.last_seq = seq

            # ---- 每玩家空闲回中 ----
            for ip, p in list(players.items()):
                if not p.neutral and now - p.last_rx > IDLE_RESET_SEC:
                    reset_state(p.pad)
                    p.pad.update()
                    p.neutral = True
                    p.last_seq = -1
                    print(f"\n[{p.idx + 1}P] 输入已回中（{ip}）", flush=True)

            # ---- 槽位回收：长时间离线的玩家把位置让给新设备 ----
            for ip, p in list(players.items()):
                if now - p.last_rx <= SLOT_TTL_SEC:
                    continue
                unplug = getattr(p.pad, "unplug", None)
                if callable(unplug):
                    try:
                        unplug()
                    except Exception:  # noqa: BLE001
                        pass
                players.pop(ip, None)
                free_slots.append(p.idx)
                free_slots.sort()
                print(f"\n[{p.idx + 1}P] 离线超过 {int(SLOT_TTL_SEC)}s，"
                      f"已释放槽位（{ip}）", flush=True)

            # ---- 每秒状态行（逐玩家）+ 周期性重发玩家编号 ----
            if now - last_report >= 1.0:
                if players:
                    parts = []
                    for p in players.values():
                        total = p.packets + p.lost
                        rate = (p.lost * 100.0 / total) if total else 0.0
                        parts.append(f"[{p.idx + 1}P] {p.packets}pps {rate:.1f}%")
                        if p.addr is not None:
                            # 周期性重发编号：手机端接收线程晚于首包启动也不会漏
                            try:
                                sock.sendto(b"VGPP" + bytes([1, p.idx + 1, 0, 0]), p.addr)
                            except OSError:
                                pass
                    if args.debug:
                        print("  ".join(parts), flush=True)
                    else:
                        print("\r" + "  ".join(parts) + "          ",
                              end="", flush=True)
                for p in players.values():
                    p.packets = 0
                    p.lost = 0
                last_report = now
    except KeyboardInterrupt:
        print("\n[退出] 全部虚拟控制器已移除。")
    finally:
        sock.close()

    return 0


if __name__ == "__main__":
    sys.exit(main())
