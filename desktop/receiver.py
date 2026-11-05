# -*- coding: utf-8 -*-
"""PC 桌面接收端 · UDP 接收引擎。

职责与命令行接收端（server-python/server.py）一致：
  数据接收（20 字节 VGP1 报文 / 逐来源排空 / 每 IP 只应用最新一帧）
  状态反馈（玩家编号 VGPP、震动回传 VGPR、ViGEm 虚拟手柄实时更新）
  异常处理（端口占用、驱动缺失、坏包、ICMP 瞬时错误、满员、离线回中、槽位回收）

差别：运行在后台线程，通过 Qt 信号把快照与事件投递到 UI 线程；vgamepad 缺失
或 ViGEmBus 驱动不可用时不退出，降级为「仅监听 / 诊断」模式并把原因告知界面。
"""
from __future__ import annotations

import select
import socket
import struct
import threading
import time
from typing import Any

from PySide6.QtCore import QObject, Signal

MAGIC = b"VGP1"
VERSION = 1
PACKET_SIZE = 20
IDLE_RESET_SEC = 1.2
MAX_PLAYERS = 4
SLOT_TTL_SEC = 300.0
SELECT_TIMEOUT = 0.004          # 4ms：250Hz 报文下既不过度空转，也不引入排队延迟
SNAPSHOT_SEC = 0.10            # UI 快照 10Hz，避免 250Hz 刷新把界面打爆
REPORT_SEC = 0.25              # 回传链路实测速率的结算周期：连上 250ms 内即可显示真实速率
BTN_NAMES = (
    (1 << 0, "A"), (1 << 1, "B"), (1 << 2, "X"), (1 << 3, "Y"),
    (1 << 4, "LB"), (1 << 5, "RB"), (1 << 6, "BACK"), (1 << 7, "START"),
    (1 << 8, "L3"), (1 << 9, "R3"),
    (1 << 10, "UP"), (1 << 11, "DOWN"), (1 << 12, "LEFT"), (1 << 13, "RIGHT"),
    (1 << 14, "GUIDE"),
)


def vgpp_packet(slot: int, pps: int) -> bytes:
    """8 字节编号回包：VGP P / 版本 / 槽位 1..4 / PC 实测 pps / 保留。

    pps 由 PC 按 REPORT_SEC 结算并折算成每秒包数后回传，手机端只显示这个
    「链路实收速率」，不再显示本地固定 250Hz 发送计数（后者会让断开后仍显示
    高 pps）。单字节字段上限 255；当前 SEND_HZ=250 不会触顶。
    """
    return b"VGPP" + bytes([VERSION, slot, min(255, max(0, int(pps))), 0])


# PC 端 Stop：主动告知手机端会话结束，令其立即切「未连接」并清空 pps/玩家号
PACKET_QUIT = b"VGPQ" + bytes([VERSION, 0, 0, 0])


def parse(data: bytes):
    """解析 VGP1 报文；长度/魔数/版本任一不符即丢弃（不取前 20 字节）。"""
    if len(data) != PACKET_SIZE or data[:4] != MAGIC or data[4] != VERSION:
        return None
    buttons = data[5] | (data[6] << 8)
    seq = data[7]
    lx, ly, rx, ry = struct.unpack_from("<hhhh", data, 8)
    return buttons, lx, ly, rx, ry, data[16], data[17], seq


def button_names(buttons: int) -> str:
    names = [n for bit, n in BTN_NAMES if buttons & bit]
    return " ".join(names) if names else "-"


def local_ip() -> str:
    """取本机局域网 IP（UDP connect 不实际发包）。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("223.5.5.5", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


class _Player:
    __slots__ = ("idx", "pad", "addr", "last_rx", "neutral", "last_seq",
                 "packets", "lost", "state", "report_packets")

    def __init__(self, idx: int, pad, addr):
        self.idx = idx
        self.pad = pad
        self.addr = addr
        self.last_rx = time.monotonic()
        self.neutral = True
        self.last_seq = -1
        self.packets = 0
        self.lost = 0
        self.report_packets = 0      # 本速率结算周期内收到的包数
        self.state = (0, 0, 0, 0, 0, 0, 0, 0)


def _bind_hint(exc: OSError) -> str:
    """把系统错误码翻译成可执行的中文处置建议。"""
    code = getattr(exc, "winerror", None) or getattr(exc, "errno", None)
    if code in (10048, 98, 48):
        return "端口已被其它程序占用，请关闭占用程序或改用其它端口"
    if code in (10013, 13):
        return "系统拒绝绑定（端口可能被保留或权限不足），请改用 20000–60000 之间的端口"
    return "请检查端口合法性、防火墙与网络适配器状态"


class Receiver(QObject):
    """UDP 接收引擎。所有 Signal 由后台线程 emit，Qt 自动排队到 UI 线程。"""

    listening = Signal(int, str)        # port, 局域网 IP
    stopped = Signal(str)               # 停止原因
    snapshot = Signal(dict)             # 状态快照
    log = Signal(str, str)              # level: info / warn / error / debug, 文本
    error = Signal(str)                 # 需要常驻状态栏的显著错误
    driver_state = Signal(bool, str)    # ViGEm 可用, 说明

    def __init__(self, parent=None):
        super().__init__(parent)
        self._stop_evt = threading.Event()
        self._thread: threading.Thread | None = None
        self._sock: socket.socket | None = None
        self._port = 0
        self._running = False
        self._lock = threading.Lock()
        # 震动回传地址：主循环写、ViGEm 回调线程读，GIL 下单键读写安全
        self._rumble_addrs: dict[int, tuple] = {}
        self._vg = None
        self._vcli = None

    # ---------- 对外控制 ----------

    def is_running(self) -> bool:
        return self._running

    def start(self, port: int, use_vigem: bool = True, debug: bool = False) -> bool:
        if self._running:
            self.log.emit("warn", "接收已在运行，忽略重复启动")
            return False
        if not (1 <= port <= 65535):
            self.error.emit("端口必须是 1–65535 之间的整数")
            return False

        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("0.0.0.0", port))
        except OSError as exc:
            sock.close()
            msg = f"UDP {port} 绑定失败：{_bind_hint(exc)}"
            self.error.emit(msg)
            self.log.emit("error", f"绑定失败：{exc}")
            self.stopped.emit("bind_failed")
            return False

        sock.setblocking(False)
        self._sock = sock
        self._port = port
        self._stop_evt.clear()
        self._running = True
        self._thread = threading.Thread(
            target=self._loop, args=(sock, use_vigem, debug),
            name="vgp-receiver", daemon=True,
        )
        self._thread.start()
        return True

    def stop(self) -> None:
        if not self._running:
            return
        self._stop_evt.set()
        if self._thread is not None and self._thread.is_alive():
            self._thread.join(timeout=1.5)

    # ---------- ViGEm 能力探测与手柄管理 ----------

    def _probe_vigem(self, use_vigem: bool) -> tuple[bool, str]:
        if not use_vigem:
            return False, "已在配置中关闭虚拟手柄输出（仅监听模式）"
        try:
            import vgamepad as vg
            from vgamepad.win import vigem_client as vcli
        except Exception as exc:      # noqa: BLE001
            # vgamepad 在 import 期就实例化全局 VBus() 并连接总线；未装驱动时
            # 抛 VIGEM_ERROR_BUS_NOT_FOUND，这不是「运行库缺失」，须如实区分。
            if "BUS_NOT_FOUND" in str(exc) or "VIGEM_ERROR_BUS" in str(exc):
                return False, "未检测到 ViGEmBus 驱动，请安装驱动后重启"
            return False, f"未找到 vgamepad 运行库：{exc}"
        # 只连接总线、不创建设备：无驱动时 vigem_connect 返回非 0
        client = None
        connected = False
        try:
            client = vcli.vigem_alloc()
            err = vcli.vigem_connect(client)
            if err != 0:
                return False, "未检测到 ViGEmBus 驱动（错误码 0x%08X），请安装驱动后重启" % err
            connected = True
            self._vg = vg
            self._vcli = vcli
            return True, "ViGEmBus 就绪，虚拟手柄输出已启用"
        except Exception as exc:      # noqa: BLE001
            return False, f"ViGEmBus 初始化失败：{exc}"
        finally:
            if client is not None:
                try:
                    if connected:
                        vcli.vigem_disconnect(client)
                    vcli.vigem_free(client)
                except Exception:
                    pass

    def _make_pad(self, idx: int, sock: socket.socket):
        pad = self._vg.VX360Gamepad()
        addrs = self._rumble_addrs

        def on_feedback(client, target, large, small, led, user_data):
            addr = addrs.get(idx)
            if addr is None:
                return
            try:
                sock.sendto(
                    b"VGPR" + bytes([1, int(large) & 0xFF, int(small) & 0xFF, 0]),
                    (addr[0], addr[1]),
                )
            except OSError:
                pass

        try:
            pad.register_notification(on_feedback)
        except Exception as exc:      # noqa: BLE001
            self.log.emit("warn", f"{idx + 1}P 力反馈回调注册失败（震动回传不可用）：{exc}")
        return pad

    def _unplug(self, pad) -> None:
        if pad is None or self._vcli is None:
            return
        try:
            if getattr(pad, "cmp_func", None) is not None:
                pad.unregister_notification()
                pad.cmp_func = None
            self._vcli.vigem_target_remove(pad._busp, pad._devicep)
        except Exception:
            pass

    def _apply(self, pad, buttons, lx, ly, rx, ry, lt, rt) -> None:
        if pad is None or self._vg is None:
            return
        for bit, btn in self._btns():
            if buttons & bit:
                pad.press_button(button=btn)
            else:
                pad.release_button(button=btn)
        pad.left_joystick(x_value=lx, y_value=ly)
        pad.right_joystick(x_value=rx, y_value=ry)
        pad.left_trigger(value=lt)
        pad.right_trigger(value=rt)
        pad.update()

    def _btns(self):
        if not hasattr(self, "_btn_map"):
            b = self._vg.XUSB_BUTTON
            self._btn_map = (
                (1 << 0, b.XUSB_GAMEPAD_A), (1 << 1, b.XUSB_GAMEPAD_B),
                (1 << 2, b.XUSB_GAMEPAD_X), (1 << 3, b.XUSB_GAMEPAD_Y),
                (1 << 4, b.XUSB_GAMEPAD_LEFT_SHOULDER),
                (1 << 5, b.XUSB_GAMEPAD_RIGHT_SHOULDER),
                (1 << 6, b.XUSB_GAMEPAD_BACK), (1 << 7, b.XUSB_GAMEPAD_START),
                (1 << 8, b.XUSB_GAMEPAD_LEFT_THUMB),
                (1 << 9, b.XUSB_GAMEPAD_RIGHT_THUMB),
                (1 << 10, b.XUSB_GAMEPAD_DPAD_UP),
                (1 << 11, b.XUSB_GAMEPAD_DPAD_DOWN),
                (1 << 12, b.XUSB_GAMEPAD_DPAD_LEFT),
                (1 << 13, b.XUSB_GAMEPAD_DPAD_RIGHT),
                (1 << 14, b.XUSB_GAMEPAD_GUIDE),
            )
        return self._btn_map

    # ---------- 接收主循环 ----------

    def _loop(self, sock: socket.socket, use_vigem: bool, debug: bool) -> None:
        ip = local_ip()
        self.listening.emit(self._port, ip)

        ok, detail = self._probe_vigem(use_vigem)
        self.driver_state.emit(ok, detail)
        self.log.emit("info" if ok else "warn", f"ViGEm：{detail}")
        if not ok:
            self.log.emit("warn", "接收端继续以「仅监听 / 诊断」模式运行：数据仍会解析与展示，但不会驱动虚拟手柄")

        players: dict[str, _Player] = {}
        free_slots = list(range(MAX_PLAYERS))
        self._rumble_addrs.clear()
        started = time.monotonic()
        last_snapshot = 0.0
        last_report = time.monotonic()   # 链路速率回传的结算起点
        last_second = started            # 完整 1 秒窗口（快照/丢包率）的结算起点
        last_log: dict[str, float] = {}
        bad_packets = 0
        rate_window = {i: 0 for i in range(MAX_PLAYERS)}   # 上一完整秒的包数
        loss_window = {i: 0 for i in range(MAX_PLAYERS)}   # 上一完整秒的丢包数

        def throttled(key: str, level: str, msg: str, gap: float = 3.0) -> None:
            now = time.monotonic()
            if now - last_log.get(key, -1e9) >= gap:
                last_log[key] = now
                self.log.emit(level, msg)

        def emit_snapshot(now: float) -> None:
            rows = []
            for src, p in players.items():
                b, lx, ly, rx, ry, lt, rt, seq = p.state
                rows.append({
                    "slot": p.idx + 1,
                    "ip": p.addr[0] if p.addr else src,
                    "port": p.addr[1] if p.addr else 0,
                    "buttons": button_names(b),
                    "lx": lx, "ly": ly, "rx": rx, "ry": ry,
                    "lt": lt, "rt": rt, "seq": seq,
                    "pps": rate_window.get(p.idx, 0),
                    "age_ms": int((now - p.last_rx) * 1000),
                })
            rows.sort(key=lambda r: r["slot"])
            total_pkt = sum(rate_window.values())
            total_lost = sum(loss_window.values())
            loss_rate = (100.0 * total_lost / (total_pkt + total_lost)) if (total_pkt + total_lost) else 0.0
            self.snapshot.emit({
                "pps": total_pkt,
                "loss": loss_rate,
                "online": len(players),
                "bad": bad_packets,
                "uptime": now - started,
                "rows": rows,
                "vigem": ok,
                "port": self._port,
                "ip": ip,
            })

        try:
            while not self._stop_evt.is_set():
                try:
                    r, _, _ = select.select([sock], [], [], SELECT_TIMEOUT)
                except (OSError, ValueError) as exc:
                    throttled("select", "error", f"监听异常：{exc}", 5.0)
                    time.sleep(0.005)
                    continue

                now = time.monotonic()
                latest: dict[str, tuple[bytes, tuple]] = {}
                if r:
                    try:
                        while True:
                            data, addr = sock.recvfrom(64)
                            latest[addr[0]] = (data, addr)
                    except BlockingIOError:
                        pass
                    except ConnectionResetError:
                        # Windows WSAECONNRESET：手机退出后 ICMP 端口不可达，非致命
                        throttled("icmp", "warn", "收到 ICMP 端口不可达（手机端可能已退出），已忽略", 5.0)
                        time.sleep(0.005)
                    except OSError as exc:
                        throttled("recv", "error", f"接收瞬时错误：{exc}", 3.0)
                        time.sleep(0.002)

                for src, (data, addr) in latest.items():
                    parsed = parse(data)
                    if parsed is None:
                        bad_packets += 1
                        if debug:
                            throttled("bad", "warn", f"丢弃非法报文（{len(data)} 字节），累计 {bad_packets}", 2.0)
                        continue
                    buttons, lx, ly, rx, ry, lt, rt, seq = parsed

                    p = players.get(src)
                    if p is None:
                        if not free_slots:
                            throttled("full", "warn", f"4 个手柄槽位已满，忽略新设备 {src}", 3.0)
                            continue
                        idx = free_slots.pop(0)
                        if ok:
                            try:
                                pad = self._make_pad(idx, sock)
                            except Exception as exc:      # noqa: BLE001
                                free_slots.insert(0, idx)
                                free_slots.sort()
                                throttled("pad", "error", f"创建 {idx + 1}P 虚拟手柄失败：{exc}", 3.0)
                                continue
                        else:
                            pad = None
                        p = _Player(idx, pad, addr)
                        players[src] = p
                        self._rumble_addrs[idx] = addr
                        self.log.emit("info", f"{idx + 1}P 已连接  {src}:{addr[1]}")
                        try:
                            sock.sendto(vgpp_packet(idx + 1, 0), addr)
                        except OSError:
                            pass

                    prev = p.addr
                    p.addr = addr
                    self._rumble_addrs[p.idx] = addr
                    # App 重启后源端口变化：重发编号，手机端立即知道自己是几 P
                    if prev is None or prev[1] != addr[1]:
                        try:
                            sock.sendto(vgpp_packet(p.idx + 1, rate_window.get(p.idx, 0)), addr)
                        except OSError:
                            pass

                    p.state = (buttons, lx, ly, rx, ry, lt, rt, seq)
                    try:
                        self._apply(p.pad, buttons, lx, ly, rx, ry, lt, rt)
                    except Exception as exc:              # noqa: BLE001
                        throttled("apply", "error", f"{p.idx + 1}P 状态写入失败：{exc}", 3.0)
                    p.neutral = False
                    p.last_rx = now
                    p.packets += 1
                    p.report_packets += 1

                    if p.last_seq >= 0:
                        gap = (seq - ((p.last_seq + 1) & 0xFF)) & 0xFF
                        if 0 < gap < 128:
                            p.lost += gap
                    p.last_seq = seq

                    if debug:
                        throttled(f"dbg{p.idx}", "debug",
                                  f"{p.idx + 1}P  {button_names(buttons)}  "
                                  f"L({lx},{ly}) R({rx},{ry}) LT={lt} RT={rt} SEQ={seq}", 0.25)

                # 空闲回中：手机掉线时虚拟手柄不能停在"摇杆推到底"
                for src, p in list(players.items()):
                    if not p.neutral and now - p.last_rx > IDLE_RESET_SEC:
                        if p.pad is not None:
                            try:
                                self._apply(p.pad, 0, 0, 0, 0, 0, 0, 0)
                            except Exception:
                                pass
                        p.neutral = True
                        p.last_seq = -1
                        self.log.emit("warn", f"{p.idx + 1}P 输入超时，已回中（{src}）")

                # 长时间离线回收槽位
                for src, p in list(players.items()):
                    if now - p.last_rx <= SLOT_TTL_SEC:
                        continue
                    self._unplug(p.pad)
                    self._rumble_addrs.pop(p.idx, None)
                    players.pop(src, None)
                    free_slots.append(p.idx)
                    free_slots.sort()
                    rate_window[p.idx] = 0
                    loss_window[p.idx] = 0
                    self.log.emit("info", f"{p.idx + 1}P 离线超过 5 分钟，槽位已释放")

                # 链路实收速率：先结算本周期再回传，避免「发的是上一秒的值」
                # 造成首秒 0、第二秒才跳到 220+ 的先低后高观感。
                # 短周期按实际时长折算成每秒包数，连接 250ms 内即有真值。
                if now - last_report >= REPORT_SEC:
                    elapsed = now - last_report
                    for p in players.values():
                        rate = int(p.report_packets / elapsed + 0.5) if elapsed > 0 else 0
                        p.report_packets = 0
                        if p.addr is not None:
                            try:
                                sock.sendto(vgpp_packet(p.idx + 1, rate), p.addr)
                            except OSError:
                                pass
                    last_report = now

                # 完整 1 秒窗口仍独立结算，供 PC 端快照与丢包率使用
                if now - last_second >= 1.0:
                    for p in players.values():
                        rate_window[p.idx] = p.packets
                        loss_window[p.idx] = p.lost
                        p.packets = 0
                        p.lost = 0
                    for i in range(MAX_PLAYERS):
                        if i not in {p.idx for p in players.values()}:
                            rate_window[i] = 0
                            loss_window[i] = 0
                    last_second = now

                if now - last_snapshot >= SNAPSHOT_SEC:
                    last_snapshot = now
                    # pps 用上一秒窗口值，避免 100ms 快照把速率放大
                    emit_snapshot(now)
        finally:
            # Stop 时主动通知所有手机端「会话结束」：手机端收到立即切未连接、
            # 清空 pps 与玩家号，不必等 6 秒链路超时。UDP 无连接，连发两次
            # 提高到达率；旧版手机端忽略未知回包类型，向后兼容。
            for p in list(players.values()):
                if p.addr is None:
                    continue
                for _ in range(2):
                    try:
                        sock.sendto(PACKET_QUIT, p.addr)
                    except OSError:
                        break
            for p in players.values():
                self._unplug(p.pad)
            self._rumble_addrs.clear()
            try:
                sock.close()
            except OSError:
                pass
            with self._lock:
                self._running = False
                self._sock = None
            self.log.emit("info", "监听已停止，虚拟手柄已从总线移除")
            self.stopped.emit("user")
