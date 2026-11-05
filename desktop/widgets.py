# -*- coding: utf-8 -*-
"""桌面接收端界面组件：全部遵循 8px 网格与 1px 分隔线的瑞士风格规范。"""
from __future__ import annotations

import html
from datetime import datetime

from PySide6.QtCore import QLineF, QPoint, QPointF, QRectF, Qt, Signal
from PySide6.QtGui import QColor, QPainter, QPen
from PySide6.QtWidgets import (
    QAbstractButton, QFrame, QHBoxLayout, QHeaderView, QLabel, QPlainTextEdit,
    QSizePolicy, QTableWidget, QTableWidgetItem, QVBoxLayout, QWidget,
)

from style import HEADER_H, INK, LINE, MUTED, PAPER, RED, WHITE, font


class Block(QWidget):
    """支持 QSS 背景/边框的容器（QWidget 子类必须开启 WA_StyledBackground）。"""

    def __init__(self, name: str = "", parent: QWidget | None = None):
        super().__init__(parent)
        if name:
            self.setObjectName(name)
        self.setAttribute(Qt.WidgetAttribute.WA_StyledBackground, True)
        self.setProperty("block", "1")


class WindowButton(QAbstractButton):
    """自绘窗口控制按钮：纯几何线框图标，无任何 Windows 原生按钮外观。

    kind: min / max / close；max 在窗口最大化时切换为「还原」双框图标。
    状态反馈遵循设计系统：悬停浅白蒙层、按下加深、关闭悬停瑞士红。
    """

    WIDTH = 48

    def __init__(self, kind: str, parent: QWidget | None = None):
        super().__init__(parent)
        self.kind = kind
        self._restore = False
        self.setObjectName("winBtn")
        self.setProperty("kind", kind)
        self.setFixedSize(self.WIDTH, HEADER_H)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setFocusPolicy(Qt.FocusPolicy.NoFocus)
        # 不在悬停时弹出「最小化 / 还原 / 关闭」文字提示；
        # 名称只保留给屏幕阅读器等无障碍通道。
        labels = {
            "min": "最小化",
            "max": "最大化",
            "close": "关闭",
        }
        self.setAccessibleName(labels[kind])

    def set_restore(self, restore: bool) -> None:
        """窗口最大化时把图标切换成「还原」。"""
        if self.kind != "max" or self._restore == restore:
            return
        self._restore = restore
        self.setAccessibleName("还原" if restore else "最大化")
        self.update()

    def paintEvent(self, event) -> None:      # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        w, h = self.width(), self.height()
        bg = QColor(INK)
        if self.isDown():
            bg = QColor(184, 28, 34) if self.kind == "close" else QColor(255, 255, 255, 62)
        elif self.underMouse():
            bg = QColor(RED) if self.kind == "close" else QColor(255, 255, 255, 34)
        p.fillRect(self.rect(), bg)

        cx, cy = w / 2.0, h / 2.0
        p.setPen(QPen(QColor(WHITE), 1.25, Qt.PenStyle.SolidLine,
                      Qt.PenCapStyle.SquareCap))
        if self.kind == "min":
            p.drawLine(QLineF(cx - 6, cy, cx + 6, cy))
        elif self.kind == "max":
            if self._restore:
                back = QRectF(cx - 1.5, cy - 6.5, 8, 8)
                front = QRectF(cx - 6.5, cy - 1.5, 8, 8)
                p.drawRect(back)
                p.fillRect(front.adjusted(0.7, 0.7, -0.7, -0.7), bg)
                p.drawRect(front)
            else:
                p.drawRect(QRectF(cx - 5.5, cy - 5.5, 11, 11))
        else:
            p.drawLine(QLineF(cx - 5, cy - 5, cx + 5, cy + 5))
            p.drawLine(QLineF(cx + 5, cy - 5, cx - 5, cy + 5))

        # 与相邻按钮之间只用 1px 半透明竖线分隔，保持几何克制
        p.setPen(QPen(QColor(255, 255, 255, 40), 1))
        p.drawLine(QLineF(0.5, 14, 0.5, h - 14))
        p.end()


class TitleBar(Block):
    """应用自绘标题栏：与黑底顶栏同一块画布，负责拖动与双击切换。

    按下不立即进入系统移动循环（否则双击事件会被系统吞掉），
    而是在左键移动超过 4px 后才请求系统级移动，兼顾拖动与双击。
    """

    moveRequested = Signal()
    toggleRequested = Signal()

    def __init__(self, parent: QWidget | None = None):
        super().__init__("header", parent)
        self.setFixedHeight(HEADER_H)
        self._controls: list[QWidget] = []
        self._press_pos: QPoint | None = None

    def register_control(self, widget: QWidget) -> None:
        """注册不可用于拖动/双击的子控件（窗口控制按钮）。"""
        self._controls.append(widget)

    def _over_control(self, pos: QPoint) -> bool:
        node = self.childAt(pos)
        while node is not None and node is not self:
            if node in self._controls:
                return True
            node = node.parentWidget()
        return False

    def mousePressEvent(self, event) -> None:      # noqa: N802
        pos = event.position().toPoint()
        if (event.button() == Qt.MouseButton.LeftButton
                and not self._over_control(pos)):
            self._press_pos = pos
            event.accept()
        else:
            super().mousePressEvent(event)

    def mouseMoveEvent(self, event) -> None:      # noqa: N802
        if (self._press_pos is not None
                and event.buttons() & Qt.MouseButton.LeftButton):
            if (event.position().toPoint() - self._press_pos).manhattanLength() > 4:
                self._press_pos = None
                self.moveRequested.emit()
            event.accept()
            return
        super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event) -> None:      # noqa: N802
        self._press_pos = None
        super().mouseReleaseEvent(event)

    def mouseDoubleClickEvent(self, event) -> None:      # noqa: N802
        pos = event.position().toPoint()
        if (event.button() == Qt.MouseButton.LeftButton
                and not self._over_control(pos)):
            self.toggleRequested.emit()
            event.accept()
        else:
            super().mouseDoubleClickEvent(event)


class SlotStrip(QWidget):
    """玩家槽位：4 个等宽方格，1px 发丝分区，占用时以信号色填充。

    Uiverse Grid 风格：层级由格与色块表达，不用卡片、圆角或阴影。
    """

    def __init__(self, count: int = 4, parent: QWidget | None = None):
        super().__init__(parent)
        self.setObjectName("slotStrip")
        self.setAttribute(Qt.WidgetAttribute.WA_StyledBackground, True)
        self.setFixedHeight(56)
        self._count = count
        self._active: set[int] = set()

    def set_slots(self, rows: list[dict]) -> None:
        active = {int(r["slot"]) for r in rows}
        if active != self._active:
            self._active = active
            self.update()

    def paintEvent(self, event) -> None:      # noqa: N802
        p = QPainter(self)
        w, h = self.width(), self.height()
        cw = w / self._count
        for i in range(self._count):
            cell = QRectF(i * cw, 0, cw, h)
            on = (i + 1) in self._active
            p.fillRect(cell, QColor(RED if on else PAPER))
            p.setPen(QPen(QColor(LINE), 1))
            p.drawRect(cell.adjusted(0.5, 0.5, -0.5, -0.5))
            p.setPen(QColor(WHITE if on else MUTED))
            p.setFont(font(9, bold=True, tracking=1.0))
            p.drawText(cell, Qt.AlignmentFlag.AlignCenter, f"P{i + 1}")
        p.end()


def rule(vertical: bool = False) -> QFrame:
    f = QFrame()
    f.setObjectName("vRule" if vertical else "hRule")
    if vertical:
        f.setFrameShape(QFrame.Shape.VLine)
        f.setFixedWidth(1)
    else:
        f.setFrameShape(QFrame.Shape.HLine)
        f.setFixedHeight(1)
    f.setStyleSheet(f"background: {LINE}; border: none;")
    return f


def section(number: str, en: str, zh: str) -> QLabel:
    """编号 + 英文大写 + 中文的三层小标题，底部 1px 黑线。"""
    lab = QLabel(f'<span style="color:{MUTED}">{number}</span>　'
                 f'<span style="color:{INK}">{en}</span>　'
                 f'<span style="color:{MUTED};font-weight:normal">{zh}</span>')
    lab.setObjectName("section")
    lab.setFont(font(8, bold=True, tracking=1.1))
    lab.setTextFormat(Qt.TextFormat.RichText)
    lab.setFixedHeight(22)
    return lab


class Metric(Block):
    """指标块：2px 黑顶线 + 小标题 + 大数字 + 单位。"""

    def __init__(self, label: str, unit: str, initial: str = "0"):
        super().__init__("metric")
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 8, 0, 0)
        lay.setSpacing(1)
        self.lab = QLabel(label)
        self.lab.setObjectName("metricLabel")
        self.lab.setFont(font(8, bold=True, tracking=0.9))
        self.val = QLabel(initial)
        self.val.setObjectName("metricValue")
        self.val.setFont(font(22, bold=True))
        self.unit = QLabel(unit)
        self.unit.setObjectName("metricUnit")
        self.unit.setFont(font(8))
        lay.addWidget(self.lab)
        lay.addWidget(self.val)
        lay.addWidget(self.unit)
        lay.addStretch(1)

    def set_value(self, text: str) -> None:
        if self.val.text() != text:
            self.val.setText(text)


class KeyValue(Block):
    """侧栏键值行：左标签灰、右值黑，底部 1px 细线。"""

    def __init__(self, key: str, value: str = "—"):
        super().__init__()
        lay = QHBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        self.k = QLabel(key)
        self.k.setObjectName("kvKey")
        self.k.setFont(font(8.5))
        self.v = QLabel(value)
        self.v.setObjectName("kvVal")
        self.v.setFont(font(9, bold=True, mono=False))
        self.v.setAlignment(Qt.AlignmentFlag.AlignRight)
        lay.addWidget(self.k)
        lay.addStretch(1)
        lay.addWidget(self.v)
        self.setFixedHeight(28)
        self.setStyleSheet(f"QWidget {{ border-bottom: 1px solid {LINE}; }}")

    def set_value(self, text: str, alert: bool = False) -> None:
        self.v.setText(text)
        self.v.setStyleSheet(f"color: {RED if alert else INK}; font-weight: bold; font-size: 9pt;")


class StickScope(Block):
    """摇杆位置几何可视化：正方形网格 + 中心十字 + 1px 基准圆 + 红色数据点。"""

    def __init__(self, title: str):
        super().__init__()
        self.setFixedHeight(120)
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self._x = 0
        self._y = 0
        self.title = title
        self.value_lab = QLabel("+00000 / +00000")
        self.value_lab.setFont(font(9, mono=True))
        self.value_lab.setStyleSheet(f"color: {MUTED};")
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(4)
        top = QHBoxLayout()
        t = QLabel(title)
        t.setFont(font(8, bold=True, tracking=1.0))
        t.setStyleSheet(f"color: {INK};")
        top.addWidget(t)
        top.addStretch(1)
        top.addWidget(self.value_lab)
        lay.addLayout(top)

    def set_xy(self, x: int, y: int) -> None:
        self._x = max(-32768, min(32767, int(x)))
        self._y = max(-32768, min(32767, int(y)))
        self.value_lab.setText(f"{self._x:+06d} / {self._y:+06d}")
        self.update()

    def paintEvent(self, event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        w, h = self.width(), self.height()
        top = 26
        side = min(w, h - top - 18)
        side = max(side, 40)
        cx = (w - side) / 2
        cy = top + (h - top - 18 - side) / 2
        # 外框
        p.setPen(QPen(QColor(LINE), 1))
        p.drawRect(int(cx), int(cy), int(side), int(side))
        # 中心十字
        midx, midy = cx + side / 2, cy + side / 2
        p.drawLine(int(midx), int(cy), int(midx), int(cy + side))
        p.drawLine(int(cx), int(midy), int(cx + side), int(midy))
        # 满行程基准圆
        r = side / 2 - 4
        p.drawEllipse(int(midx - r), int(midy - r), int(2 * r), int(2 * r))
        # 数据点
        px = midx + (self._x / 32767.0) * r
        py = midy + (self._y / 32767.0) * r
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(QColor(RED))
        p.drawEllipse(int(px - 4), int(py - 4), 8, 8)
        p.end()


class DataTable(QTableWidget):
    """1P–4P 接收状态表：固定 4 行，离线行以 — 占位保持网格完整。"""

    HEADERS = ("SLOT", "SOURCE", "BUTTONS", "LEFT STICK", "RIGHT STICK",
               "TRIGGERS", "SEQ", "PPS", "AGE")

    def __init__(self):
        super().__init__(4, len(self.HEADERS))
        self.setObjectName("dataTable")
        self.setHorizontalHeaderLabels(self.HEADERS)
        self.verticalHeader().hide()
        self.setShowGrid(False)
        self.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        self.setSelectionMode(QTableWidget.SelectionMode.SingleSelection)
        self.setFocusPolicy(Qt.FocusPolicy.StrongFocus)
        hh = self.horizontalHeader()
        for i in (0, 6, 7, 8):
            hh.setSectionResizeMode(i, QHeaderView.ResizeMode.Fixed)
        self.setColumnWidth(0, 46)
        self.setColumnWidth(6, 46)
        self.setColumnWidth(7, 52)
        self.setColumnWidth(8, 58)
        for i in (1, 2, 3, 4, 5):
            hh.setSectionResizeMode(i, QHeaderView.ResizeMode.Stretch)
        for r in range(4):
            self.setRowHeight(r, 28)
        self.setFixedHeight(30 + 4 * 28)
        self.clear_rows()

    def clear_rows(self) -> None:
        for r in range(4):
            for c in range(len(self.HEADERS)):
                item = QTableWidgetItem("—" if c else f"{r + 1}P")
                if c == 0:
                    item.setData(Qt.ItemDataRole.FontRole, font(9, bold=True))
                item.setTextAlignment(Qt.AlignmentFlag.AlignVCenter | Qt.AlignmentFlag.AlignLeft)
                self.setItem(r, c, item)

    def update_rows(self, rows: list[dict]) -> None:
        # 只改文本、不重建 item：保持用户当前选中行，避免 10Hz 刷新打断键盘选择
        by_slot = {row["slot"]: row for row in rows}
        for r in range(4):
            row = by_slot.get(r + 1)
            cells = (
                (f'{r + 1}P', f'{row["ip"]}:{row["port"]}', row["buttons"],
                 f'{row["lx"]:+d},{row["ly"]:+d}',
                 f'{row["rx"]:+d},{row["ry"]:+d}',
                 f'{row["lt"]:03d}/{row["rt"]:03d}',
                 f'{row["seq"]:03d}', f'{row["pps"]}', f'{row["age_ms"]}ms')
                if row else (f"{r + 1}P", "—", "—", "—", "—", "—", "—", "—", "—")
            )
            for c, text in enumerate(cells):
                item = self.item(r, c)
                if item is not None and item.text() != text:
                    item.setText(text)
                    item.setToolTip(text if row else "")   # 列窄被省略时，鼠标悬停读完整值


class LogView(QPlainTextEdit):
    """事件日志：等宽字体、时间戳 + 等级标签，上限 600 行。"""

    LEVEL_COLOR = {
        "info": INK, "warn": RED, "error": RED, "debug": "#909090",
    }

    def __init__(self):
        super().__init__()
        self.setObjectName("logView")
        self.setReadOnly(True)
        self.setMaximumBlockCount(600)
        self.setLineWrapMode(QPlainTextEdit.LineWrapMode.WidgetWidth)
        self.setFont(font(9, mono=True))
        self.setFocusPolicy(Qt.FocusPolicy.StrongFocus)
        self.document().setDefaultStyleSheet(f"body {{ color: {INK}; }}")

    def add(self, level: str, message: str) -> None:
        ts = datetime.now().strftime("%H:%M:%S")
        color = self.LEVEL_COLOR.get(level, INK)
        tag = level.upper().ljust(5)
        weight = "bold" if level in ("warn", "error") else "normal"
        self.appendHtml(
            f'<span style="color:#909090">[{ts}]</span>  '
            f'<span style="color:{color};font-weight:{weight}">{html.escape(tag)}</span>  '
            f'<span style="color:{INK}">{html.escape(message)}</span>'
        )
        self.verticalScrollBar().setValue(self.verticalScrollBar().maximum())
