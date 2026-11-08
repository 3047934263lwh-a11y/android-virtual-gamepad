# -*- coding: utf-8 -*-
"""虚拟 Xbox 360 手柄 · PC 桌面接收端（瑞士国际主义风格）

源码运行：python main.py
便携程序：VirtualGamepadReceiver.exe（由 build_portable.py / portable.spec 打包）
自检：    python main.py --selftest <截图目录>   （注入模拟 VGP1 报文并导出界面截图）
"""
from __future__ import annotations

import math
import os
import socket
import struct
import sys
import threading
import time
import traceback

# 资源根：源码运行时是脚本目录；冻结成 exe 后是 PyInstaller 的 _internal 目录。
# 运行库（vgamepad 纯 Python + ViGEm DLL，MIT）以数据文件随程序一起分发，
# 运行时从磁盘加载，保证 DLL 的 __file__ 相对路径与源码版完全一致。
_FROZEN = getattr(sys, "frozen", False)
_HERE = os.path.dirname(os.path.abspath(__file__))
_APP_DIR = os.path.dirname(os.path.abspath(sys.executable)) if _FROZEN else _HERE


def resource_root() -> str:
    """定位 _internal：不同 PyInstaller 版本的 sys._MEIPASS 可能指向 exe
    目录或 _internal 目录，按 vendor 实际位置探测，避免猜错导致运行库丢失。"""
    if not _FROZEN:
        return _HERE
    guesses = (
        getattr(sys, "_MEIPASS", ""),
        os.path.join(_APP_DIR, "_internal"),
        _APP_DIR,
    )
    for path in guesses:
        if path and os.path.isdir(os.path.join(path, "vendor", "vgamepad")):
            return path
    return getattr(sys, "_MEIPASS", "") or _APP_DIR


_RES = resource_root()
_VENDOR = os.path.join(_RES, "vendor")
if os.path.isdir(_VENDOR) and _VENDOR not in sys.path:
    sys.path.insert(0, _VENDOR)

from PySide6.QtCore import QEvent, QSettings, Qt, QTimer, Signal  # noqa: E402
from PySide6.QtGui import (  # noqa: E402
    QAction, QColor, QCursor, QFont, QIcon, QIntValidator, QKeySequence,
    QPainter, QPixmap, QShortcut,
)
from PySide6.QtWidgets import (  # noqa: E402
    QAbstractButton, QApplication, QCheckBox, QFrame, QHBoxLayout, QHeaderView,
    QLabel, QLineEdit, QMainWindow, QPlainTextEdit, QPushButton, QScrollArea,
    QScrollBar, QStackedLayout, QTableWidget, QVBoxLayout, QWidget,
)

from receiver import Receiver, local_ip  # noqa: E402
from style import (  # noqa: E402
    HEADER_H, INK, LINE, PAPER, RED, SANS, SIDEBAR_W,
    STATUSBAR_H, UNIT, WHITE, font,
)
import style as _style  # noqa: E402
from widgets import (  # noqa: E402
    AnimatedAction, Block, DataTable, FlipCardGrid, KeyValue, LogView, Metric,
    Starfield, StickScope, TitleBar, WindowButton, rule, section,
)

APP_ORG = "VirtualGamepad"
APP_NAME = "PCReceiver"
DEFAULT_PORT = 26760
APP_VERSION = "1.1.3"

# 边缘缩放热区不接管这些控件：避免窗口贴边时吞掉按钮/输入/表格的正常点击
RESIZE_EXCLUDED = (
    WindowButton, QScrollBar, QAbstractButton, QLineEdit, QCheckBox,
    QTableWidget, QPlainTextEdit, QHeaderView,
)


def portable_settings() -> QSettings:
    """配置写进程序目录（拷贝整个文件夹即连配置一起走）；不可写时回退用户目录。"""
    try:
        cfg = os.path.join(_APP_DIR, "config")
        os.makedirs(cfg, exist_ok=True)
        return QSettings(os.path.join(cfg, "settings.ini"), QSettings.Format.IniFormat)
    except OSError:
        return QSettings(APP_ORG, APP_NAME)


def app_icon() -> QIcon:
    """黑底白字 + 红色几何方块的克制图腾；优先使用随程序分发的多尺寸 ico。"""
    ico = os.path.join(_RES, "app.ico")
    if os.path.isfile(ico):
        return QIcon(ico)
    pm = QPixmap(64, 64)
    pm.fill(QColor(INK))
    p = QPainter(pm)
    p.setPen(QColor(WHITE))
    p.setFont(QFont(SANS, 22, QFont.Weight.Bold))
    p.drawText(6, 42, "VG")
    p.setPen(Qt.PenStyle.NoPen)
    p.fillRect(46, 46, 12, 12, QColor(RED))
    p.end()
    return QIcon(pm)


class MainWindow(QMainWindow):
    def __init__(self, selftest_dir: str | None = None):
        super().__init__()
        self.selftest_dir = selftest_dir
        # 无边框窗口：视觉全部由应用绘制；保留系统菜单/最小化/最大化 hint，
        # 以维持任务栏窗口管理、Alt+Tab 与系统级移动/缩放行为。
        self.setWindowFlags(
            Qt.WindowType.Window
            | Qt.WindowType.FramelessWindowHint
            | Qt.WindowType.WindowSystemMenuHint
            | Qt.WindowType.WindowMinimizeButtonHint
            | Qt.WindowType.WindowMaximizeButtonHint
        )
        self.setWindowTitle(f"VIRTUAL GAMEPAD  —  PC RECEIVER  v{APP_VERSION}")
        self.setWindowIcon(app_icon())
        self.setMinimumSize(1024, 680)
        self.resize(1320, 860)
        self.settings = portable_settings()

        self._resize_margin = 6       # 客户区边缘缩放热区（逻辑像素，自动适配 DPI）
        self._window_controls: list[WindowButton] = []
        self.header: TitleBar | None = None
        app = QApplication.instance()
        if app is not None:
            app.installEventFilter(self)

        self.receiver = Receiver(self)
        self._last_rows: list[dict] = []
        self._occupied: socket.socket | None = None
        self._selftest_thread: threading.Thread | None = None
        self._selftest_stop = threading.Event()

        self._build_minimal_ui()
        self._bind_signals()
        self._bind_shortcuts()
        self._load_settings()
        self._save_settings()        # 便携版启动时即落盘，配置随文件夹走

        self._set_running_ui(False)
        self._log("info", f"本机局域网地址 {local_ip()}，手机与 PC 需接入同一网络")
        self._log("info", "协议 VGP1 v1 · 20 字节报文 · 最多 4 台设备")
        self._log("info", "按 CTRL+S 或点击下方按钮开始监听")

        if self.selftest_dir:
            QTimer.singleShot(180, self._selftest_start)
        # 2026-11-06 修复：应用启动后固定为「已停止」状态，
        # 不再自动开始监听，需要用户手动点 START（或 CTRL+S）。

    # ---------- 界面构建 ----------

    def _build_ui(self) -> None:
        central = Block("central")
        root = QVBoxLayout(central)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)
        self.setCentralWidget(central)

        root.addWidget(self._build_header())
        root.addWidget(self._build_alert())

        body = Block("body")
        bl = QHBoxLayout(body)
        bl.setContentsMargins(0, 0, 0, 0)
        bl.setSpacing(0)
        bl.addWidget(self._scroll(self._build_sidebar(), sidebar=True))
        bl.addWidget(self._scroll(self._build_main_pane()), 1)
        root.addWidget(body, 1)

        root.addWidget(self._build_statusbar())

    def _scroll(self, content: QWidget, sidebar: bool = False) -> QScrollArea:
        """滚动容器：窗口再小也只出现滚动条，绝不压缩控件导致文字裁切/重叠。"""
        area = QScrollArea()
        area.setObjectName("sideScroll" if sidebar else "mainScroll")
        area.setWidgetResizable(True)
        area.setFrameShape(QFrame.Shape.NoFrame)
        area.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        area.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)
        area.setWidget(content)
        border = f"border-right: 1px solid {LINE};" if sidebar else ""
        area.setStyleSheet(
            f"QScrollArea {{ background: {PAPER}; border: none; {border} }} "
            f"QScrollArea > QWidget > QWidget {{ background: {PAPER}; }}"
        )
        if sidebar:
            area.setFixedWidth(SIDEBAR_W)
        return area

    def _build_header(self) -> TitleBar:
        h = TitleBar()
        # 右侧外边距为 0：窗口控制按钮贴齐窗口边缘，无缝衔接，无额外留白
        lay = QHBoxLayout(h)
        lay.setContentsMargins(32, 0, 0, 0)
        lay.setSpacing(16)

        brand = QLabel("VIRTUAL GAMEPAD")
        brand.setObjectName("brand")
        brand.setFont(font(17, bold=True, tracking=1.4))
        lay.addWidget(brand)
        lay.addStretch(1)

        self.header_chip = Block("stateChipIdle")
        self.header_chip.setFixedSize(10, 10)
        lay.addWidget(self.header_chip)
        self.header_state = QLabel("STOPPED")
        self.header_state.setObjectName("headerState")
        self.header_state.setFont(font(9, bold=True, tracking=1.4))
        lay.addWidget(self.header_state)
        lay.addSpacing(24)

        controls = QHBoxLayout()
        controls.setContentsMargins(0, 0, 0, 0)
        controls.setSpacing(0)
        self.btn_min = WindowButton("min", h)
        self.btn_max = WindowButton("max", h)
        self.btn_close = WindowButton("close", h)
        for btn in (self.btn_min, self.btn_max, self.btn_close):
            controls.addWidget(btn)
            h.register_control(btn)
            self._window_controls.append(btn)
        lay.addLayout(controls)
        self.header = h
        return h

    def _build_alert(self) -> QWidget:
        self.alert = Block("alertBar")
        self.alert.setFixedHeight(38)
        lay = QHBoxLayout(self.alert)
        lay.setContentsMargins(32, 0, 32, 0)
        lay.setSpacing(12)
        tag = QLabel("ERROR")
        tag.setStyleSheet(f"color: {WHITE}; font-size: 8pt; font-weight: bold;")
        tag.setFont(font(8, bold=True, tracking=1.2))
        self.alert_text = QLabel("")
        self.alert_text.setObjectName("alertText")
        dismiss = QPushButton("DISMISS  关闭")
        dismiss.setObjectName("alertDismiss")
        dismiss.setCursor(Qt.CursorShape.PointingHandCursor)
        dismiss.clicked.connect(self.alert.hide)
        lay.addWidget(tag)
        lay.addWidget(self.alert_text, 1)
        lay.addWidget(dismiss)
        self.alert.hide()
        return self.alert

    def _build_sidebar(self) -> QWidget:
        s = Block("sidebar")
        lay = QVBoxLayout(s)
        lay.setContentsMargins(24, 16, 24, 12)
        lay.setSpacing(UNIT)

        lay.addWidget(section("01", "CONFIGURATION", "配置"))
        lay.addSpacing(4)
        pl = QLabel("UDP PORT  ·  监听端口")
        pl.setObjectName("fieldLabel")
        pl.setFont(font(8, bold=True, tracking=0.8))
        lay.addWidget(pl)

        self.port_edit = QLineEdit(str(DEFAULT_PORT))
        self.port_edit.setObjectName("portEdit")
        self.port_edit.setValidator(QIntValidator(1, 65535, self.port_edit))
        self.port_edit.setFont(font(11, mono=True))
        self.port_edit.returnPressed.connect(self.start_listen)
        lay.addWidget(self.port_edit)
        helper = QLabel("手机 App 填写本机 IP 与此端口")
        helper.setObjectName("helper")
        helper.setFont(font(8))
        lay.addWidget(helper)
        lay.addSpacing(12)

        # 「启动时自动监听」已于 2026-11-06 移除：应用固定以 STOPPED 启动、
        # 需手动 START。_hidden_compat() 仍保留隐藏的 auto_cb 兼容对象，
        # 这里不再创建可见控件，避免留下一个点了没有任何效果的假开关。
        self.vigem_cb = QCheckBox("启用 ViGEm 虚拟手柄输出")
        self.debug_cb = QCheckBox("调试模式 · 打印逐帧报文")
        for cb in (self.vigem_cb, self.debug_cb):
            cb.setFont(font(9))
            cb.setCursor(Qt.CursorShape.PointingHandCursor)
            lay.addWidget(cb)
        lay.addSpacing(12)

        self.toggle_btn = QPushButton("开始监听   START")
        self.toggle_btn.setObjectName("primary")
        self.toggle_btn.setFont(font(9, bold=True))
        self.toggle_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.toggle_btn.clicked.connect(self.toggle_listen)
        lay.addWidget(self.toggle_btn)

        reset_btn = QPushButton("恢复默认配置   RESET")
        reset_btn.setObjectName("secondary")
        reset_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        reset_btn.clicked.connect(self._restore_defaults)
        lay.addWidget(reset_btn)

        lay.addSpacing(16)
        lay.addWidget(section("02", "CONNECTION", "连接信息"))
        lay.addSpacing(4)
        self.kv_ip = KeyValue("局域网 IP", local_ip())
        self.kv_port = KeyValue("监听端口", "—")
        self.kv_proto = KeyValue("协议", "VGP1  v1")
        self.kv_rate = KeyValue("报文回报率", "250 Hz")
        self.kv_vigem = KeyValue("虚拟手柄", "待检测")
        for kv in (self.kv_ip, self.kv_port, self.kv_proto, self.kv_rate, self.kv_vigem):
            lay.addWidget(kv)

        lay.addStretch(1)
        note = QLabel(
            "手机与 PC 接入同一 Wi-Fi，App 填写上方 IP 与端口。\n"
            "虚拟手柄需 ViGEmBus 驱动：运行随包「可选驱动\\ViGEmBusSetup_x64.msi」后重启即可（离线安装，无需联网）。"
        )
        note.setObjectName("footNote")
        note.setFont(font(8))
        note.setWordWrap(True)
        lay.addWidget(note)
        return s

    def _build_main_pane(self) -> QWidget:
        m = Block("mainPane")
        lay = QVBoxLayout(m)
        lay.setContentsMargins(24, 16, 24, 12)
        lay.setSpacing(10)

        lay.addWidget(section("03", "STATUS", "运行状态"))
        metrics = QHBoxLayout()
        metrics.setSpacing(0)
        self.m_pps = Metric("RECEIVE RATE", "pps · 每秒接收报文")
        self.m_loss = Metric("PACKET LOSS", "序列缺口 · 丢包率", "0.0%")
        self.m_online = Metric("DEVICES ONLINE", "在线设备 · 最多 4 台")
        self.m_uptime = Metric("UPTIME", "分:秒 · 本次监听时长", "00:00")
        for i, metric in enumerate((self.m_pps, self.m_loss, self.m_online, self.m_uptime)):
            if i:
                metrics.addWidget(rule(vertical=True))
                metrics.addSpacing(20)
            metrics.addWidget(metric, 1)
        lay.addLayout(metrics)
        lay.addSpacing(4)

        lay.addWidget(section("04", "RECEIVED DATA", "接收内容 · 逐设备最新一帧"))
        self.table = DataTable()
        lay.addWidget(self.table)
        lay.addSpacing(4)

        lay.addWidget(section("05", "STICK POSITION", "摇杆位置 · 选中设备"))
        scopes = QHBoxLayout()
        scopes.setSpacing(0)
        self.scope_l = StickScope("LEFT STICK  /  左摇杆")
        self.scope_r = StickScope("RIGHT STICK  /  右摇杆")
        scopes.addWidget(self.scope_l, 1)
        scopes.addSpacing(20)
        scopes.addWidget(rule(vertical=True))
        scopes.addSpacing(20)
        scopes.addWidget(self.scope_r, 1)
        lay.addLayout(scopes)

        self.log_view = LogView()
        self.log_view.setMinimumHeight(120)
        log_head = QHBoxLayout()
        log_head.addWidget(section("06", "EVENT LOG", "事件日志"), 1)
        clear = QPushButton("CLEAR  清空日志")
        clear.setObjectName("ghost")
        clear.setCursor(Qt.CursorShape.PointingHandCursor)
        clear.clicked.connect(self.log_view.clear)
        log_head.addWidget(clear)
        lay.addLayout(log_head)
        lay.addWidget(self.log_view, 1)
        return m

    def _build_statusbar(self) -> QWidget:
        s = Block("statusBar")
        s.setFixedHeight(STATUSBAR_H)
        lay = QHBoxLayout(s)
        lay.setContentsMargins(32, 0, 32, 0)
        lay.setSpacing(10)
        self.status_dot = Block("statusDot")
        self.status_dot.setFixedSize(9, 9)
        lay.addWidget(self.status_dot)
        self.status_text = QLabel("已停止  /  等待开始监听")
        self.status_text.setObjectName("statusText")
        self.status_text.setFont(font(8.5))
        lay.addWidget(self.status_text)
        self.status_error = QLabel("")
        self.status_error.setObjectName("statusError")
        self.status_error.setFont(font(8.5))
        lay.addWidget(self.status_error, 1)
        keys = QLabel("CTRL+S 开始/停止    CTRL+L 清空日志    CTRL+Q 退出    F5 开始/停止")
        keys.setObjectName("shortcuts")
        keys.setFont(font(8))
        lay.addWidget(keys)
        return s

    # ---------- 精简界面（Uiverse Grid 设计系统） ----------

    def _build_minimal_ui(self) -> None:
        """单一网格承载全部可见元素：1px 发丝分区，层级由跨格与字号建立，
        无卡片、无圆角、无阴影、无浮动元素。"""
        central = QWidget(self)
        central.setObjectName("central")
        stack = QStackedLayout(central)
        stack.setStackingMode(QStackedLayout.StackingMode.StackAll)
        stack.setContentsMargins(0, 0, 0, 0)
        stack.setSpacing(0)
        self.starfield = Starfield()
        page = QWidget()
        page.setObjectName("page")
        stack.addWidget(self.starfield)
        stack.addWidget(page)
        stack.setCurrentWidget(page)   # StackAll 下 current 决定最上层
        self.setCentralWidget(central)

        root = QVBoxLayout(page)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)
        root.addWidget(self._build_header())
        root.addWidget(rule())          # 贯穿全宽的 1px 发丝分区，不切断星空

        body = Block("body")
        bl = QHBoxLayout(body)
        bl.setContentsMargins(0, 0, 0, 0)
        bl.setSpacing(0)
        bl.addWidget(self._grid_connection(), 3)
        bl.addWidget(self._grid_devices(), 2)
        root.addWidget(body, 1)

        root.addWidget(self._build_footer())
        self._hidden_compat()

    def _grid_connection(self) -> QWidget:
        cell = Block("gridCell")
        lay = QVBoxLayout(cell)
        lay.setContentsMargins(32, 24, 32, 24)
        lay.setSpacing(0)

        head = QLabel("01　CONNECTION")
        head.setObjectName("section")
        head.setFont(font(8, bold=True, tracking=1.2))
        lay.addWidget(head)
        lay.addSpacing(28)

        lab = QLabel("HOST ADDRESS  ·  在手机 App 中填写")
        lab.setObjectName("fieldLabel")
        lab.setFont(font(8, bold=True, tracking=0.8))
        lay.addWidget(lab)
        lay.addSpacing(12)

        addr_row = QHBoxLayout()
        addr_row.setSpacing(12)
        self.address_ip = QLabel(local_ip())
        self.address_ip.setObjectName("address")
        self.address_ip.setFont(font(27, bold=True, mono=True))
        self.address_port = QLabel(f":{DEFAULT_PORT}")
        self.address_port.setObjectName("addressPort")
        self.address_port.setFont(font(15, mono=True))
        addr_row.addWidget(self.address_ip)
        addr_row.addWidget(self.address_port)
        addr_row.addStretch(1)
        lay.addLayout(addr_row)
        lay.addSpacing(12)

        copy = QPushButton("COPY  复制地址")
        copy.setObjectName("copyBtn")
        copy.setFixedWidth(132)
        copy.setCursor(Qt.CursorShape.PointingHandCursor)
        copy.clicked.connect(self._copy_address)
        lay.addWidget(copy)
        lay.addStretch(1)

        self.toggle_btn = AnimatedAction("START")
        self.toggle_btn.setFont(font(11, bold=True, tracking=1.0))
        self.toggle_btn.clicked.connect(self.toggle_listen)
        lay.addWidget(self.toggle_btn)
        return cell

    def _grid_devices(self) -> QWidget:
        cell = Block("gridCellLast")
        lay = QVBoxLayout(cell)
        lay.setContentsMargins(32, 24, 32, 24)
        lay.setSpacing(0)

        head = QLabel("02　DEVICES")
        head.setObjectName("section")
        head.setFont(font(8, bold=True, tracking=1.2))
        lay.addWidget(head)
        lay.addSpacing(16)

        # 在线数与说明合并为一行，把纵向空间让给 2×2 卡片
        info = QHBoxLayout()
        lab = QLabel("CONNECTED  ·  最多 4 台")
        lab.setObjectName("fieldLabel")
        lab.setFont(font(8, bold=True, tracking=0.8))
        info.addWidget(lab, 1)
        self.device_number = QLabel("0 / 4")
        self.device_number.setObjectName("bigNumber")
        self.device_number.setFont(font(13, bold=True))
        info.addWidget(self.device_number)
        lay.addLayout(info)
        lay.addSpacing(12)

        # 2026-11-06：P1–P4 由槽位条改为 2×2 翻转卡片（悬停灰→绿），仅视觉
        self.card_grid = FlipCardGrid()
        lay.addWidget(self.card_grid, 1)
        return cell

    def _build_footer(self) -> QWidget:
        bar = Block("footer")
        bar.setFixedHeight(STATUSBAR_H)
        lay = QHBoxLayout(bar)
        lay.setContentsMargins(32, 0, 32, 0)
        lay.setSpacing(10)
        self.status_error = QLabel("")
        self.status_error.setObjectName("statusError")
        self.status_error.setFont(font(9))
        lay.addWidget(self.status_error, 1)
        return bar

    def _copy_address(self) -> None:
        QApplication.clipboard().setText(
            f"{self.address_ip.text()}:{self.address_port.text().lstrip(':')}")

    def _hidden_compat(self) -> None:
        """保留既有业务对象与自动化断言所需的数据容器，但不进入任何布局。
        逐帧数据、指标、日志、配置等仍在内存中维护，界面不再呈现。"""
        holder = QWidget(self)
        holder.hide()
        self._compat_holder = holder

        def park(widget):
            widget.setParent(holder)
            return widget

        self.port_edit = QLineEdit(str(DEFAULT_PORT), holder)
        self.port_edit.setObjectName("portEdit")
        self.port_edit.setValidator(QIntValidator(1, 65535, self.port_edit))
        self.port_edit.returnPressed.connect(self.start_listen)

        self.auto_cb = QCheckBox("auto", holder)
        self.vigem_cb = QCheckBox("vigem", holder)
        self.debug_cb = QCheckBox("debug", holder)

        self.alert = Block("alertBar", holder)
        self.alert_text = QLabel("", holder)
        self.alert_text.setObjectName("alertText")
        dismiss = QPushButton("DISMISS", self.alert)
        dismiss.setObjectName("alertDismiss")
        dismiss.clicked.connect(self.alert.hide)

        self.log_view = park(LogView())
        self.table = park(DataTable())
        self.scope_l = park(StickScope(""))
        self.scope_r = park(StickScope(""))
        self.m_pps = park(Metric("", "", "0"))
        self.m_loss = park(Metric("", "", "0.0%"))
        self.m_online = park(Metric("", "", "0"))
        self.m_uptime = park(Metric("", "", "00:00"))
        self.kv_ip = park(KeyValue("", ""))
        self.kv_port = park(KeyValue("", ""))
        self.kv_proto = park(KeyValue("", ""))
        self.kv_rate = park(KeyValue("", ""))
        self.kv_vigem = park(KeyValue("", ""))
        self.status_dot = Block("statusDot", holder)
        self.status_dot.setFixedSize(9, 9)
        self.status_text = QLabel("", holder)
        self.status_text.setObjectName("statusText")

    # ---------- 信号 / 快捷键 / 配置 ----------

    def _bind_signals(self) -> None:
        self.header.moveRequested.connect(self._start_system_move)
        self.header.toggleRequested.connect(self.toggle_maximize)
        self.btn_min.clicked.connect(self.showMinimized)
        self.btn_max.clicked.connect(self.toggle_maximize)
        self.btn_close.clicked.connect(self.close)
        self.receiver.listening.connect(self._on_listening)
        self.receiver.stopped.connect(self._on_stopped)
        self.receiver.snapshot.connect(self._on_snapshot)
        self.receiver.log.connect(self._on_log_signal)
        self.receiver.error.connect(self._on_error)
        self.receiver.driver_state.connect(self._on_driver)
        self.table.itemSelectionChanged.connect(self._on_table_select)

    def _bind_shortcuts(self) -> None:
        QShortcut(QKeySequence("Ctrl+S"), self, self.toggle_listen)
        QShortcut(QKeySequence("F5"), self, self.toggle_listen)
        QShortcut(QKeySequence("Ctrl+L"), self, self.log_view.clear)
        QShortcut(QKeySequence("Ctrl+Q"), self, self.close)
        copy_sc = QShortcut(QKeySequence("Ctrl+C"), self.table)
        copy_sc.setContext(Qt.ShortcutContext.WidgetShortcut)
        copy_sc.activated.connect(self._copy_row)

    # ---------- 无边框窗口行为（Qt 6 稳定 API） ----------

    def _start_system_move(self) -> None:
        """交给 Windows 执行系统级移动：保留 Aero Snap、多显示器与窗口约束。"""
        handle = self.windowHandle()
        if handle is not None:
            handle.startSystemMove()

    def toggle_maximize(self) -> None:
        if self.isMaximized():
            self.showNormal()
        else:
            self.showMaximized()
        self._sync_window_controls()

    def _sync_window_controls(self) -> None:
        self.btn_max.set_restore(self.isMaximized())

    def _set_active_style(self, active: bool) -> None:
        """激活/失焦状态由应用自己体现，不依赖原生标题栏变色。"""
        self._restyle(self.header, "header" if active else "headerInactive")
        for child in self.header.findChildren(QWidget):
            child.style().unpolish(child)
            child.style().polish(child)

    def changeEvent(self, event) -> None:      # noqa: N802
        super().changeEvent(event)
        if event.type() == QEvent.Type.WindowStateChange:
            self._sync_window_controls()
        elif event.type() == QEvent.Type.ActivationChange:
            self._set_active_style(self.isActiveWindow())

    def _edges_at(self, global_pos) -> Qt.Edges:
        """把客户区最外层 6px 映射为系统缩放边（最大化时禁用）。"""
        if self.isMinimized() or self.isMaximized() or not self.isVisible():
            return Qt.Edges()
        r = self.frameGeometry()
        x = global_pos.x() - r.x()
        y = global_pos.y() - r.y()
        m = self._resize_margin
        if x < -2 or y < -2 or x > r.width() + 2 or y > r.height() + 2:
            return Qt.Edges()
        edges = Qt.Edges()
        if x <= m:
            edges |= Qt.Edge.LeftEdge
        if x >= r.width() - m:
            edges |= Qt.Edge.RightEdge
        if y <= m:
            edges |= Qt.Edge.TopEdge
        if y >= r.height() - m:
            edges |= Qt.Edge.BottomEdge
        return edges

    def _update_resize_cursor(self, global_pos, obj) -> None:
        if isinstance(obj, RESIZE_EXCLUDED):
            self.unsetCursor()
            return
        edges = self._edges_at(global_pos)
        if not edges:
            if self.cursor().shape() != Qt.CursorShape.ArrowCursor:
                self.unsetCursor()
            return
        left = bool(edges & Qt.Edge.LeftEdge)
        right = bool(edges & Qt.Edge.RightEdge)
        top = bool(edges & Qt.Edge.TopEdge)
        bottom = bool(edges & Qt.Edge.BottomEdge)
        if (left or right) and (top or bottom):
            shape = (Qt.CursorShape.SizeFDiagCursor
                     if (left and top) or (right and bottom)
                     else Qt.CursorShape.SizeBDiagCursor)
        elif left or right:
            shape = Qt.CursorShape.SizeHorCursor
        else:
            shape = Qt.CursorShape.SizeVerCursor
        self.setCursor(QCursor(shape))

    def _try_system_resize(self, global_pos, obj) -> bool:
        """边缘按下交给系统缩放；按钮和滚动条不参与，避免误吞点击。"""
        if isinstance(obj, RESIZE_EXCLUDED):
            return False
        edges = self._edges_at(global_pos)
        if not edges:
            return False
        handle = self.windowHandle()
        if handle is not None:
            handle.startSystemResize(edges)
        return True

    def eventFilter(self, obj, event) -> bool:      # noqa: N802
        if isinstance(obj, QWidget) and obj.window() is self:
            kind = event.type()
            if kind == QEvent.Type.MouseMove:
                self._update_resize_cursor(event.globalPosition().toPoint(), obj)
            elif (kind == QEvent.Type.MouseButtonPress
                  and event.button() == Qt.MouseButton.LeftButton
                  and self._try_system_resize(event.globalPosition().toPoint(), obj)):
                return True
        return super().eventFilter(obj, event)

    def _load_settings(self) -> None:
        st = self.settings
        self.port_edit.setText(str(st.value("port", DEFAULT_PORT)))
        # "auto"（启动时自动监听）已于 2026-11-06 移除：应用固定以停止状态启动。
        # 此行保留空实现以兼容旧配置文件中的 auto 键（读取后忽略）。
        st.value("auto", "false")
        self.vigem_cb.setChecked(str(st.value("vigem", "true")).lower() == "true")
        self.debug_cb.setChecked(str(st.value("debug", "false")).lower() == "true")
        geo = st.value("geometry")
        if geo is not None and not self.selftest_dir:
            self.restoreGeometry(geo)

    def _save_settings(self) -> None:
        if self.selftest_dir:
            return
        st = self.settings
        st.setValue("port", self.port_edit.text())
        st.setValue("vigem", self.vigem_cb.isChecked())
        st.setValue("debug", self.debug_cb.isChecked())
        st.setValue("geometry", self.saveGeometry())
        st.sync()

    def _restore_defaults(self) -> None:
        if self.receiver.is_running():
            self._log("warn", "请先停止监听再修改默认配置")
            return
        self.port_edit.setText(str(DEFAULT_PORT))
        self.vigem_cb.setChecked(True)
        self.debug_cb.setChecked(False)
        self._save_settings()
        self._log("info", "配置已恢复默认")

    # ---------- 控制 ----------

    def _port(self) -> int:
        try:
            return int(self.port_edit.text())
        except ValueError:
            return 0

    def toggle_listen(self) -> None:
        if self.receiver.is_running():
            self.stop_listen()
        else:
            self.start_listen()

    def start_listen(self) -> None:
        if self.receiver.is_running():
            return
        port = self._port()
        if not (1 <= port <= 65535):
            self._show_error("端口必须是 1–65535 之间的整数")
            return
        self.alert.hide()
        self.status_error.setText("")
        self._save_settings()        # 端口/开关改动立即持久化
        self.receiver.start(port, self.vigem_cb.isChecked(), self.debug_cb.isChecked())

    def stop_listen(self) -> None:
        self.receiver.stop()

    # ---------- 接收引擎回调（UI 线程） ----------

    def _on_listening(self, port: int, ip: str) -> None:
        self.address_ip.setText(ip)
        self.address_port.setText(f":{port}")
        self.kv_ip.set_value(ip)
        self.kv_port.set_value(str(port))
        self.header_state.setText("LISTENING")
        self.status_text.setText(f"监听中   0.0.0.0:{port}   本机 {ip}")
        self.status_error.setText("")
        self._set_running_ui(True)
        self._log("info", f"已开始监听 UDP 0.0.0.0:{port}（本机 {ip}）")

    def _on_stopped(self, reason: str) -> None:
        self.header_state.setText("STOPPED")
        self.status_text.setText("已停止  /  等待开始监听")
        self._set_running_ui(False)
        self.kv_vigem.set_value("待检测")
        if reason != "bind_failed":
            self._log("info", "监听已停止")

    def _on_snapshot(self, snap: dict) -> None:
        self.device_number.setText(f'{snap["online"]} / 4')
        self.card_grid.set_slots(snap["rows"])
        self.m_pps.set_value(str(snap["pps"]))
        self.m_loss.set_value(f'{snap["loss"]:.1f}%')
        self.m_online.set_value(str(snap["online"]))
        secs = int(snap["uptime"])
        self.m_uptime.set_value(f"{secs // 60:02d}:{secs % 60:02d}")
        self.table.update_rows(snap["rows"])
        self._on_table_select(snap["rows"])
        if self.receiver.is_running():
            self.status_text.setText(
                f'监听中   UDP {snap["port"]}   {snap["pps"]} pps   '
                f'丢包 {snap["loss"]:.1f}%   非法报文 {snap["bad"]}'
            )

    def _on_log_signal(self, level: str, msg: str) -> None:
        self._log(level, msg)

    def _log(self, level: str, msg: str) -> None:
        self.log_view.add(level, msg)

    def _on_error(self, msg: str) -> None:
        self._show_error(msg)
        self._log("error", msg)

    def _on_driver(self, ok: bool, detail: str) -> None:
        # 界面已不呈现驱动字段与错误横幅；驱动状态仅保留在数据对象中
        self.kv_vigem.set_value("已启用" if ok else "仅监听", alert=not ok)

    def _show_error(self, msg: str) -> None:
        self.alert_text.setText(msg)      # 兼容既有数据对象，不再显示横幅
        self.alert.hide()
        self.status_error.setText(msg if len(msg) <= 72 else msg[:72] + "…")

    def _on_table_select(self, rows: list[dict] | None = None) -> None:
        if rows is not None:
            self._last_rows = rows
        by_slot = {r["slot"]: r for r in self._last_rows}
        sel = self.table.currentRow()
        row = by_slot.get(sel + 1) if sel >= 0 else (by_slot.get(1) if by_slot else None)
        if row is None:
            self.scope_l.set_xy(0, 0)
            self.scope_r.set_xy(0, 0)
        else:
            self.scope_l.set_xy(row["lx"], row["ly"])
            self.scope_r.set_xy(row["rx"], row["ry"])

    def _copy_row(self) -> None:
        if not self.table.hasFocus():
            return
        r = self.table.currentRow()
        if r < 0:
            return
        text = "  ".join(self.table.item(r, c).text() for c in range(self.table.columnCount()))
        QApplication.clipboard().setText(text)

    def _set_running_ui(self, running: bool) -> None:
        self.port_edit.setEnabled(not running)
        for cb in (self.auto_cb, self.vigem_cb, self.debug_cb):
            cb.setEnabled(not running)
        self.toggle_btn.set_mode(running)
        self._restyle(self.header_chip, "stateChip" if running else "stateChipIdle")
        self._restyle(self.status_dot, "statusDotOn" if running else "statusDot")
        if not running:
            self._last_rows = []
            self.device_number.setText("0 / 4")
            self.card_grid.set_slots([])
            self.table.clear_rows()
            self.scope_l.set_xy(0, 0)
            self.scope_r.set_xy(0, 0)
            self.m_pps.set_value("0")
            self.m_loss.set_value("0.0%")
            self.m_online.set_value("0")
            self.m_uptime.set_value("00:00")

    @staticmethod
    def _restyle(widget: QWidget, name: str) -> None:
        widget.setObjectName(name)
        widget.style().unpolish(widget)
        widget.style().polish(widget)

    # ---------- 自检 ----------

    def _selftest_start(self) -> None:
        port = 26888
        self.port_edit.setText(str(port))
        self.vigem_cb.setChecked(False)      # 自检不创建系统虚拟手柄
        self._log("info", "自检模式：注入模拟 VGP1 报文")
        if not self.receiver.start(port, False, False):
            QTimer.singleShot(800, self._selftest_error_shot)
            return
        self._selftest_stop.clear()
        self._selftest_thread = threading.Thread(
            target=self._simulate, args=(port,), daemon=True)
        self._selftest_thread.start()
        QTimer.singleShot(1500, self._selftest_normal_shot)

    @staticmethod
    def _simulate(port: int) -> None:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        for i in range(95):
            buttons = ((1 << 0) | (1 << 4)) if i % 24 < 12 else ((1 << 1) | (1 << 5))
            lx = int(14000 * math.sin(i / 9.0))
            ly = int(11000 * math.cos(i / 11.0))
            rx = int(24000 * math.sin(i / 7.0))
            ry = int(19000 * math.cos(i / 8.0))
            data = (b"VGP1" + bytes([1, buttons & 0xFF, (buttons >> 8) & 0xFF, i & 0xFF])
                    + struct.pack("<hhhh", lx, ly, rx, ry)
                    + bytes([(i * 3) % 256, (i * 7) % 256]) + b"\x00\x00")
            try:
                s.sendto(data, ("127.0.0.1", port))
                if i == 30:
                    s.sendto(b"BADPACKET", ("127.0.0.1", port))
            except OSError:
                break
            time.sleep(0.016)
        s.close()

    def _shot(self, name: str) -> None:
        if not self.selftest_dir:
            return
        path = os.path.join(self.selftest_dir, name)
        ok = self.grab().save(path)
        self._log("info", f"自检截图 {os.path.basename(path)}：{'成功' if ok else '失败'}")

    def _selftest_normal_shot(self) -> None:
        self._shot("desktop-normal.png")
        QTimer.singleShot(200, self._selftest_error_case)

    def _selftest_error_case(self) -> None:
        self.receiver.stop()
        try:
            self._occupied = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self._occupied.bind(("0.0.0.0", 26888))
        except OSError:
            pass
        QTimer.singleShot(300, lambda: (
            self.start_listen(),
            QTimer.singleShot(500, self._selftest_error_shot),
        ))

    def _selftest_error_shot(self) -> None:
        self._shot("desktop-error.png")
        QTimer.singleShot(300, self.close)

    # ---------- 生命周期 ----------

    def closeEvent(self, event) -> None:
        app = QApplication.instance()
        if app is not None:
            app.removeEventFilter(self)
        self._selftest_stop.set()
        self.receiver.stop()
        if self._occupied is not None:
            try:
                self._occupied.close()
            except OSError:
                pass
        self._save_settings()
        super().closeEvent(event)


def main() -> int:
    if "--diag" in sys.argv:
        # 便携版部署诊断：分别确认「运行库是否就位」与「驱动是否安装」。
        # vgamepad 在 import 时会实例化全局 VBus() 并连接总线，无驱动时抛
        # VIGEM_ERROR_BUS_NOT_FOUND；这属于驱动状态，不是运行库缺失。
        import ctypes
        import importlib.util
        with open(os.path.join(_APP_DIR, "diag.txt"), "w", encoding="utf-8") as fh:
            fh.write(f"frozen={_FROZEN}\n_RES={_RES}\n_VENDOR={_VENDOR}\n")
            fh.write("sys.path=" + repr(sys.path) + "\n")
            try:
                spec = importlib.util.find_spec("vgamepad")
                fh.write(f"vgamepad_spec={spec.origin if spec else None}\n")
                arch = "x64" if ctypes.sizeof(ctypes.c_void_p) == 8 else "x86"
                dll = os.path.join(_VENDOR, "vgamepad", "win", "vigem",
                                   "client", arch, "ViGEmClient.dll")
                fh.write(f"dll_exists={os.path.isfile(dll)}\n")
                lib = ctypes.CDLL(dll)
                fh.write(f"ViGEmClient=OK sym={lib.vigem_alloc is not None}\n")
                try:
                    lib.vigem_alloc.restype = ctypes.c_void_p
                    lib.vigem_connect.argtypes = [ctypes.c_void_p]
                    lib.vigem_connect.restype = ctypes.c_uint
                    lib.vigem_disconnect.argtypes = [ctypes.c_void_p]
                    lib.vigem_free.argtypes = [ctypes.c_void_p]
                    bus = lib.vigem_alloc()
                    err = int(lib.vigem_connect(bus))
                    if err == 0:
                        lib.vigem_disconnect(bus)
                    lib.vigem_free(bus)
                    fh.write(f"driver={'READY' if err == 0 else 'MISSING'} err=0x{err:08X}\n")
                except Exception as exc:      # noqa: BLE001
                    fh.write(f"driver=UNKNOWN {exc!r}\n")
            except Exception as exc:      # noqa: BLE001
                fh.write(f"runtime=FAIL {exc!r}\n")
        return 0

    selftest_dir = None
    if "--selftest" in sys.argv:
        idx = sys.argv.index("--selftest")
        selftest_dir = (sys.argv[idx + 1] if len(sys.argv) > idx + 1
                        else os.path.join(_APP_DIR, "selftest"))
        os.makedirs(selftest_dir, exist_ok=True)

    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setApplicationVersion(APP_VERSION)
    app.setOrganizationName(APP_ORG)
    app.setWindowIcon(app_icon())
    app.setStyle("Fusion")           # 统一跨平台绘制，保证 QSS 精准生效
    _style.load_fonts()              # 注册随包字体（BBH Bogle / 正格点黑16）
    app.setFont(font(10))
    app.setStyleSheet(_style.QSS)
    w = MainWindow(selftest_dir)
    w.show()
    return app.exec()


def _fatal(exc: BaseException) -> None:
    """无控制台的窗口程序：出错时落磁盘日志 + 弹窗，不让用户面对静默退出。"""
    detail = "".join(traceback.format_exception(type(exc), exc, exc.__traceback__))
    try:
        with open(os.path.join(_APP_DIR, "error.log"), "w", encoding="utf-8") as fh:
            fh.write(detail)
    except OSError:
        pass
    try:
        from PySide6.QtWidgets import QMessageBox
        a = QApplication.instance() or QApplication(sys.argv)
        QMessageBox.critical(
            None, "虚拟手柄接收端 · 启动失败",
            f"{exc}\n\n详细信息已写入程序目录的 error.log。",
        )
        del a
    except Exception:      # noqa: BLE001
        pass


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except BaseException as exc:       # noqa: BLE001
        _fatal(exc)
        sys.exit(1)
