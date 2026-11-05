# -*- coding: utf-8 -*-
"""ViGEm 协议的纯 Python 客户端（替代 ViGEmClient.dll）。

直接与 ViGEmBus 1.21.442 内核驱动通过设备接口 + IOCTL 通信，
实现 receiver.py 所需的 vgamepad 兼容接口子集：
  vigem_alloc / vigem_connect / vigem_disconnect / vigem_free /
  vigem_target_remove / VX360Gamepad / XUSB_BUTTON

协议来源：ViGEmClient/include/ViGEm/km/BusShared.h + ViGEm/Common.h
"""
from __future__ import annotations

import ctypes
import enum
import threading
import uuid
from ctypes import wintypes

kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
cfg = ctypes.WinDLL("cfgmgr32", use_last_error=True)

# ---------- Win32 声明 ----------
GUID = ctypes.c_ubyte * 16
_GUID_BYTES = GUID.from_buffer_copy(uuid.UUID(
    "96e42b22-f5e9-42f8-b043-ed0f932f014f").bytes_le)

cfg.CM_Get_Device_Interface_List_SizeW.restype = ctypes.c_uint
cfg.CM_Get_Device_Interface_List_SizeW.argtypes = [ctypes.POINTER(ctypes.c_uint),
                                                   ctypes.POINTER(GUID),
                                                   ctypes.c_wchar_p, ctypes.c_uint]
cfg.CM_Get_Device_Interface_ListW.restype = ctypes.c_uint
cfg.CM_Get_Device_Interface_ListW.argtypes = [ctypes.POINTER(GUID),
                                              ctypes.c_wchar_p,
                                              ctypes.c_wchar_p, ctypes.c_uint,
                                              ctypes.c_uint]

kernel32.CreateFileW.restype = ctypes.c_void_p
kernel32.CreateFileW.argtypes = [ctypes.c_wchar_p, ctypes.c_uint32, ctypes.c_uint32,
                                 ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32,
                                 ctypes.c_void_p]
kernel32.CloseHandle.argtypes = [ctypes.c_void_p]
kernel32.DeviceIoControl.restype = wintypes.BOOL
kernel32.DeviceIoControl.argtypes = [ctypes.c_void_p, wintypes.DWORD,
                                     ctypes.c_void_p, wintypes.DWORD,
                                     ctypes.c_void_p, wintypes.DWORD,
                                     ctypes.POINTER(wintypes.DWORD),
                                     ctypes.c_void_p]

INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value
GENERIC_READ = 0x80000000
GENERIC_WRITE = 0x40000000
FILE_SHARE_RW = 1 | 2
OPEN_EXISTING = 3
FILE_ATTRIBUTE_NORMAL = 0x80

# ---------- IOCTL（CTL_CODE: dev=FILE_DEVICE_BUS_EXTENDER, base=0x801） ----------
FILE_DEVICE_BUS_EXTENDER = 0x0000002A
FILE_WRITE_DATA = 0x0002
FILE_READ_DATA = 0x0001
METHOD_BUFFERED = 0


def _ctl(func: int, access: int) -> int:
    return (FILE_DEVICE_BUS_EXTENDER << 16) | (access << 14) | (func << 2) | METHOD_BUFFERED


_BASE = 0x801
IOCTL_VIGEM_PLUGIN_TARGET = _ctl(_BASE + 0x000, FILE_WRITE_DATA)
IOCTL_VIGEM_UNPLUG_TARGET = _ctl(_BASE + 0x001, FILE_WRITE_DATA)
IOCTL_VIGEM_CHECK_VERSION = _ctl(_BASE + 0x002, FILE_WRITE_DATA)
IOCTL_VIGEM_WAIT_DEVICE_READY = _ctl(_BASE + 0x003, FILE_WRITE_DATA)
IOCTL_XUSB_SUBMIT_REPORT = _ctl(_BASE + 0x201, FILE_WRITE_DATA)

TARGET_XBOX360_WIRED = 0
VIGEM_COMMON_VERSION = 0x0001

CR_SUCCESS = 0


class XUSB_BUTTON(enum.IntEnum):
    XUSB_GAMEPAD_DPAD_UP = 0x0001
    XUSB_GAMEPAD_DPAD_DOWN = 0x0002
    XUSB_GAMEPAD_DPAD_LEFT = 0x0004
    XUSB_GAMEPAD_DPAD_RIGHT = 0x0008
    XUSB_GAMEPAD_START = 0x0010
    XUSB_GAMEPAD_BACK = 0x0020
    XUSB_GAMEPAD_LEFT_THUMB = 0x0040
    XUSB_GAMEPAD_RIGHT_THUMB = 0x0080
    XUSB_GAMEPAD_LEFT_SHOULDER = 0x0100
    XUSB_GAMEPAD_RIGHT_SHOULDER = 0x0200
    XUSB_GAMEPAD_GUIDE = 0x0400
    XUSB_GAMEPAD_A = 0x1000
    XUSB_GAMEPAD_B = 0x2000
    XUSB_GAMEPAD_X = 0x4000
    XUSB_GAMEPAD_Y = 0x8000


# 错误码（与 ViGEm 官方一致）
VIGEM_ERROR_BUS_NOT_FOUND = 0x20000000
VIGEM_ERROR_BUS_ACCESS_FAILED = 0x20000001
VIGEM_ERROR_BUS_ALREADY_CONNECTED = 0x20000002
VIGEM_ERROR_BUS_VERSION_MISMATCH = 0x20000004
VIGEM_ERROR_TARGET_UNEXPECTED = 0x2000000E


def _open_bus() -> int:
    """枚举接口 → 打开 → 版本协商。成功返回句柄，失败抛 VIGEM 错误码。"""
    size = ctypes.c_uint(0)
    cr = cfg.CM_Get_Device_Interface_List_SizeW(
        ctypes.byref(size), ctypes.byref(_GUID_BYTES), None, 0)
    if cr != CR_SUCCESS or size.value == 0:
        raise OSError(VIGEM_ERROR_BUS_NOT_FOUND)
    buf = ctypes.create_unicode_buffer(size.value)
    cr = cfg.CM_Get_Device_Interface_ListW(
        ctypes.byref(_GUID_BYTES), None, buf, size.value, 0)
    if cr != CR_SUCCESS or not buf.value:
        raise OSError(VIGEM_ERROR_BUS_NOT_FOUND)

    h = kernel32.CreateFileW(buf.value, GENERIC_READ | GENERIC_WRITE,
                             FILE_SHARE_RW, None, OPEN_EXISTING,
                             FILE_ATTRIBUTE_NORMAL, None)
    if h in (0, INVALID_HANDLE_VALUE):
        err = ctypes.get_last_error()
        raise OSError(VIGEM_ERROR_BUS_ACCESS_FAILED if err == 5
                      else VIGEM_ERROR_BUS_NOT_FOUND)

    payload = (ctypes.c_uint * 2)(8, VIGEM_COMMON_VERSION)
    ret = kernel32.DeviceIoControl(h, IOCTL_VIGEM_CHECK_VERSION,
                                   ctypes.byref(payload), 8, None, 0, None, None)
    if not ret:
        err = ctypes.get_last_error()
        kernel32.CloseHandle(h)
        raise OSError(VIGEM_ERROR_BUS_VERSION_MISMATCH if err == 0x1E7
                      else VIGEM_ERROR_BUS_NOT_FOUND)
    return h


def _ioctl(h: int, code: int, payload) -> None:
    ret = kernel32.DeviceIoControl(h, code, ctypes.byref(payload),
                                   ctypes.sizeof(payload), None, 0, None, None)
    if not ret:
        raise OSError(ctypes.get_last_error())


class _Bus:
    """进程级总线单例。"""

    def __init__(self):
        self.handle: int | None = None
        self._lock = threading.Lock()

    def ensure(self) -> int:
        with self._lock:
            if self.handle is None:
                self.handle = _open_bus()
            return self.handle

    def close(self):
        with self._lock:
            if self.handle is not None:
                kernel32.CloseHandle(self.handle)
                self.handle = None


_BUS = _Bus()
_serial_lock = threading.Lock()
_serial_next = 1


class _Client:
    """探测用客户端对象（vigem_alloc 返回）。"""

    def __init__(self):
        self.handle: int | None = None

    def open(self):
        self.handle = _open_bus()

    def close(self):
        if self.handle is not None:
            kernel32.CloseHandle(self.handle)
            self.handle = None


def vigem_alloc() -> _Client:
    return _Client()


def vigem_connect(c: _Client) -> int:
    if getattr(c, "handle", None) is not None:
        return VIGEM_ERROR_BUS_ALREADY_CONNECTED
    try:
        c.open()
        return 0
    except OSError as exc:
        return int(exc.args[0]) if exc.args else VIGEM_ERROR_BUS_NOT_FOUND


def vigem_disconnect(c: _Client) -> None:
    if getattr(c, "handle", None) is not None:
        c.close()


def vigem_free(c) -> None:
    pass


def vigem_target_remove(busp: int, serial: int) -> None:
    """拔出目标设备（receiver._unplug 调用）。"""
    payload = (ctypes.c_uint * 2)(8, int(serial))
    try:
        _ioctl(busp, IOCTL_VIGEM_UNPLUG_TARGET, payload)
    except OSError:
        pass


class XUSB_REPORT(ctypes.Structure):
    _fields_ = [("wButtons", ctypes.c_ushort),
                ("bLeftTrigger", ctypes.c_ubyte),
                ("bRightTrigger", ctypes.c_ubyte),
                ("sThumbLX", ctypes.c_short),
                ("sThumbLY", ctypes.c_short),
                ("sThumbRX", ctypes.c_short),
                ("sThumbRY", ctypes.c_short)]


class XUSB_SUBMIT_REPORT(ctypes.Structure):
    _fields_ = [("Size", ctypes.c_uint),
                ("SerialNo", ctypes.c_uint),
                ("Report", XUSB_REPORT)]


class VIGEM_PLUGIN_TARGET(ctypes.Structure):
    _fields_ = [("Size", ctypes.c_uint),
                ("SerialNo", ctypes.c_uint),
                ("TargetType", ctypes.c_uint),
                ("VendorId", ctypes.c_ushort),
                ("ProductId", ctypes.c_ushort)]


class VIGEM_WAIT_DEVICE_READY(ctypes.Structure):
    _fields_ = [("Size", ctypes.c_uint),
                ("SerialNo", ctypes.c_uint)]


class VX360Gamepad:
    """vgamepad.VX360Gamepad 的协议级替代实现。"""

    def __init__(self):
        global _serial_next
        self._client = _BUS.ensure()
        with _serial_lock:
            self.serial = _serial_next & 0xFFFFFFF
            _serial_next += 1
        plug = VIGEM_PLUGIN_TARGET()
        plug.Size = ctypes.sizeof(plug)
        plug.SerialNo = self.serial
        plug.TargetType = TARGET_XBOX360_WIRED
        _ioctl(self._client, IOCTL_VIGEM_PLUGIN_TARGET, plug)
        ready = VIGEM_WAIT_DEVICE_READY()
        ready.Size = ctypes.sizeof(ready)
        ready.SerialNo = self.serial
        try:
            _ioctl(self._client, IOCTL_VIGEM_WAIT_DEVICE_READY, ready)
        except OSError:
            pass  # 部分驱动版本立即就绪，WAIT 失败不视为致命
        self._report = XUSB_REPORT()
        self.cmp_func = None
        self._notif_thread = None

    # ---- vgamepad 兼容属性 ----
    @property
    def _busp(self):
        return self._client

    @property
    def _devicep(self):
        return self.serial

    # ---- vgamepad 兼容方法 ----
    def press_button(self, button) -> None:
        self._report.wButtons |= int(button)

    def release_button(self, button) -> None:
        self._report.wButtons &= ~int(button) & 0xFFFF

    def left_joystick(self, x_value: int, y_value: int) -> None:
        self._report.sThumbLX = max(-32768, min(32767, int(x_value)))
        self._report.sThumbLY = max(-32768, min(32767, int(y_value)))

    def right_joystick(self, x_value: int, y_value: int) -> None:
        self._report.sThumbRX = max(-32768, min(32767, int(x_value)))
        self._report.sThumbRY = max(-32768, min(32767, int(y_value)))

    def left_trigger(self, value: int) -> None:
        self._report.bLeftTrigger = int(value) & 0xFF

    def right_trigger(self, value: int) -> None:
        self._report.bRightTrigger = int(value) & 0xFF

    def update(self) -> None:
        pkt = XUSB_SUBMIT_REPORT()
        pkt.Size = ctypes.sizeof(pkt)
        pkt.SerialNo = self.serial
        pkt.Report = self._report
        _ioctl(self._client, IOCTL_XUSB_SUBMIT_REPORT, pkt)

    def register_notification(self, fn) -> None:
        # 震动回传：IOCTL_XUSB_REQUEST_NOTIFICATION 挂起语义，暂不实现，
        # receiver.py 对注册失败已有降级（仅警告，不影响按键/摇杆）。
        raise NotImplementedError("vibration feedback pending")

    def unregister_notification(self) -> None:
        pass


def bus_close() -> None:
    _BUS.close()
