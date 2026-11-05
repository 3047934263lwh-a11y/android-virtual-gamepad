# -*- coding: utf-8 -*-
"""桌面接收端界面组件：全部遵循 8px 网格与 1px 分隔线的瑞士风格规范。"""
from __future__ import annotations

import html
import math
import random
import time
from datetime import datetime

from PySide6.QtCore import (
    QEasingCurve, QLineF, QPoint, QPointF, QRectF, Qt, QTimer,
    QVariantAnimation, Signal,
)
from PySide6.QtGui import (
    QColor, QPainter, QPainterPath, QPen, QRadialGradient,
)
from PySide6.QtWidgets import (
    QAbstractButton, QFrame, QHBoxLayout, QHeaderView, QLabel, QPlainTextEdit,
    QSizePolicy, QTableWidget, QTableWidgetItem, QVBoxLayout, QWidget,
)

from style import (
    BG_DEEP, GREEN, HEADER_H, INK, LINE, MUTED, PAPER, RED, RED_BRIGHT,
    WHITE, font,
)


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
            # 占用格用绿色（连接成功语义），红色保留给 STOP / 错误
            p.fillRect(cell, QColor(GREEN if on else PAPER))
            p.setPen(QPen(QColor(LINE), 1))
            p.drawRect(cell.adjusted(0.5, 0.5, -0.5, -0.5))
            p.setPen(QColor(WHITE if on else MUTED))
            p.setFont(font(9, bold=True, tracking=1.0))
            p.drawText(cell, Qt.AlignmentFlag.AlignCenter, f"P{i + 1}")
        p.end()


class Starfield(QWidget):
    """Uiverse.io by jaykdoe 星空动效的 Qt 自绘复刻。

    CSS 的三层 box-shadow 星点与 :after 复制层无法在 Qt 直接使用，改为确定性
    随机生成同密度星点；三层 1/2/3px 分别以 50s / 100s / 150s 走完 2000px
    周期向上平移，第二份星图位于 +2000px，实现与 CSS 一致的无缝循环。
    背景为底部向外的径向渐变 #1B2735 → #090A0F。
    """

    PERIOD = 2000.0

    def __init__(self, parent: QWidget | None = None):
        super().__init__(parent)
        self.setObjectName("starfield")
        self.setAttribute(Qt.WidgetAttribute.WA_StyledBackground, True)
        rnd = random.Random(20261106)
        self._layers = []
        for count, size, speed, alpha in (
            (700, 1.0, self.PERIOD / 50.0, 165),
            (200, 2.0, self.PERIOD / 100.0, 210),
            (100, 3.0, self.PERIOD / 150.0, 255),
        ):
            pts = [(rnd.uniform(0.0, self.PERIOD), rnd.uniform(0.0, self.PERIOD))
                   for _ in range(count)]
            self._layers.append((pts, size, speed, alpha))
        self._t0 = time.monotonic()
        self._timer = QTimer(self)
        self._timer.setInterval(33)
        self._timer.timeout.connect(self.update)
        self._timer.start()

    def paintEvent(self, event) -> None:      # noqa: N802
        p = QPainter(self)
        w, h = self.width(), self.height()
        if w <= 0 or h <= 0:
            p.end()
            return
        grad = QRadialGradient(QPointF(w / 2.0, h), max(w, h) * 1.15)
        grad.setColorAt(0.0, QColor(BG_DEEP))
        grad.setColorAt(1.0, QColor(PAPER))
        p.fillRect(self.rect(), grad)

        elapsed = time.monotonic() - self._t0
        for pts, size, speed, alpha in self._layers:
            off = (elapsed * speed) % self.PERIOD
            col = QColor(255, 255, 255, alpha)
            for sx, sy in pts:
                x = sx % w
                for y in (sy - off, sy + self.PERIOD - off):
                    if 0.0 <= y < h:
                        p.fillRect(QRectF(x, y, size, size), col)
        p.end()


def _css_easing() -> QEasingCurve:
    """CSS cubic-bezier(0.23, 1, 0.32, 1)：Qt 的 BezierSpline 可精确构造。"""
    e = QEasingCurve(QEasingCurve.Type.BezierSpline)
    e.addCubicBezierSegment(QPointF(0.23, 1.0), QPointF(0.32, 1.0),
                            QPointF(1.0, 1.0))
    return e


def _mix(a: QColor, b: QColor, t: float) -> QColor:
    return QColor(round(a.red() + (b.red() - a.red()) * t),
                  round(a.green() + (b.green() - a.green()) * t),
                  round(a.blue() + (b.blue() - a.blue()) * t),
                  round(a.alpha() + (b.alpha() - a.alpha()) * t))


class AnimatedAction(QAbstractButton):
    """Uiverse.io by gharsh11032000 的 .animated-button 完整 Qt 复刻。

    静态态保持 Grid 暗色直角按钮；鼠标悬停或按下时按钮「转换成」该 CSS 的
    动效形态，动画结束后回到静态。CSS 的 box-shadow / SVG / transition 在 Qt
    没有对应实现，全部用 QPainter 自绘 + QVariantAnimation 等价映射：

      border-radius 100px -> 12px      圆角胶囊收敛为 12px 圆角
      box-shadow 2px -> 12px transparent  外环扩散并淡出（:active 4px 实色）
      .circle 20px -> 220px, opacity 0 -> 1   中心绿黄圆扩散
      .arr-1 right 16px -> -25%       右侧箭头滑出
      .arr-2 left -25% -> 16px        左侧箭头滑入
      .text translateX(-12 -> +12px)  文字位移
      color greenyellow -> #212121     文字与箭头反色
      :active scale(0.95)             按压缩放
      transition 0.6s / 0.8s cubic-bezier(0.23,1,0.32,1)  双时长 + 精确缓动
    """

    DUR_ALL = 600        # transition: all .6s
    DUR_PARTS = 800      # svg / circle / text .8s
    LIME = QColor("#ADFF2F")     # greenyellow
    DARK = QColor("#212121")

    def __init__(self, text: str = "START", parent: QWidget | None = None):
        super().__init__(parent)
        self.setText(text)
        self._mode = "start"
        self._progress = 0.0     # 0..1 线性动画进度（800ms 全程）
        self._target = 0.0
        self._pressed = False
        self.setObjectName("actionBtn")
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setAttribute(Qt.WidgetAttribute.WA_Hover, True)
        self.setFixedHeight(64)
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self._ease = _css_easing()
        self._anim = QVariantAnimation(self)
        self._anim.setDuration(self.DUR_PARTS)
        self._anim.setStartValue(0.0)
        self._anim.setEndValue(1.0)
        self._anim.valueChanged.connect(self._on_anim)
        self._anim.finished.connect(self._on_finished)

    def set_mode(self, running: bool) -> None:
        self._mode = "stop" if running else "start"
        self.setText("STOP" if running else "START")
        self.update()

    # ---------- 状态机 ----------
    def _animate_to(self, target: float) -> None:
        # STOP 态按需求不播动效：保持静态，任何触发立即归零
        if self._mode == "stop":
            self._target = 0.0
            self._anim.stop()
            if self._progress != 0.0:
                self._progress = 0.0
                self.update()
            return
        # leaveEvent 与 _on_finished 可能同时请求回退；目标一致且动画在跑时不重启，
        # 否则回退会被反复拉长，动效迟迟不结束。
        if (abs(self._target - target) < 1e-6
                and self._anim.state() == QVariantAnimation.State.Running):
            return
        self._target = target
        self._anim.stop()
        start = self._progress
        self._anim.setStartValue(start)
        self._anim.setEndValue(target)
        self._anim.setDuration(max(140, int(self.DUR_PARTS * abs(target - start))))
        self._anim.start()

    def _on_anim(self, value) -> None:
        self._progress = float(value)
        self.update()

    def _on_finished(self) -> None:
        # 点击触发（不在 hover 上）时动效完整播完再退回静态；hover 时保持动效态
        if (self._target > 0.0 and not self.underMouse()
                and not self._pressed and not self.isDown()):
            self._animate_to(0.0)

    def enterEvent(self, event) -> None:      # noqa: N802
        self._animate_to(1.0)
        super().enterEvent(event)

    def leaveEvent(self, event) -> None:      # noqa: N802
        if not self._pressed and not self.isDown():
            self._animate_to(0.0)
        super().leaveEvent(event)

    def mousePressEvent(self, event) -> None:      # noqa: N802
        self._pressed = True
        if self._mode == "start":
            # 点击必定触发完整动效（合成点击不产生 enterEvent），从 0 重新播放
            self._anim.stop()
            self._progress = 0.0
            self._target = 1.0
            self._anim.setStartValue(0.0)
            self._anim.setEndValue(1.0)
            self._anim.setDuration(self.DUR_PARTS)
            self._anim.start()
        self.update()
        super().mousePressEvent(event)

    def mouseReleaseEvent(self, event) -> None:      # noqa: N802
        # 回退统一由 _on_finished 在动效播完后触发；若动效已先播完，
        # 则在此立即回退，避免按钮永久停在动效态。
        self._pressed = False
        if (not self.underMouse()
                and self._anim.state() != QVariantAnimation.State.Running
                and self._target > 0.0):
            self._animate_to(0.0)
        self.update()
        super().mouseReleaseEvent(event)

    # ---------- 绘制 ----------
    @staticmethod
    def _arrow(p: QPainter, x: float, y: float, w: float, h: float,
               color: QColor) -> None:
        """24x24 右向箭头（对应 .animated-button svg 的 arr-1 / arr-2）。"""
        path = QPainterPath()
        path.moveTo(x, y + h * 0.46)
        path.lineTo(x + w * 0.58, y + h * 0.46)
        path.lineTo(x + w * 0.58, y + h * 0.18)
        path.lineTo(x + w, y + h * 0.50)
        path.lineTo(x + w * 0.58, y + h * 0.82)
        path.lineTo(x + w * 0.58, y + h * 0.54)
        path.lineTo(x, y + h * 0.54)
        path.closeSubpath()
        p.fillPath(path, color)

    def paintEvent(self, event) -> None:      # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        w, h = self.width(), self.height()
        pressed = self._pressed or self.isDown()
        scaled = pressed and self._mode == "start"
        if scaled:                             # :active scale(0.95)——仅 START 态
            p.save()
            p.translate(w / 2.0, h / 2.0)
            p.scale(0.95, 0.95)
            p.translate(-w / 2.0, -h / 2.0)

        animated = (self._mode == "start"
                    and (self._progress > 0.0 or self._target > 0.0 or pressed))
        if not animated:
            self._paint_static(p, w, h)
            if scaled:
                p.restore()
            p.end()
            return

        raw = max(0.0, min(1.0, self._progress))
        t_all = self._ease.valueForProgress(min(1.0, raw * self.DUR_PARTS / self.DUR_ALL))
        t_part = self._ease.valueForProgress(raw)

        # border-radius: 100px -> 12px（按钮高 64px，100px 等价胶囊 32px）
        radius = min(h / 2.0, 100.0 * (1.0 - t_all) + 12.0 * t_all)
        shape = QPainterPath()
        shape.addRoundedRect(QRectF(0, 0, w, h), radius, radius)

        # background-color: inherit —— 沿用项目深色底
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(QColor(PAPER))
        p.drawPath(shape)

        # box-shadow: 0 0 0 2px greenyellow -> 0 0 0 12px transparent
        ring_w = 2.0 + 10.0 * t_all
        ring_alpha = 1.0 - t_all
        if pressed:                            # :active 0 0 0 4px greenyellow
            ring_w, ring_alpha = 4.0, 1.0
        ring = QColor(self.LIME)
        ring.setAlphaF(max(0.0, min(1.0, ring_alpha)))
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.setPen(QPen(ring, ring_w))
        p.drawRoundedRect(QRectF(ring_w / 2.0, ring_w / 2.0,
                                 w - ring_w, h - ring_w), radius, radius)

        # overflow: hidden —— 圆、箭头、文字都裁在按钮轮廓内
        p.save()
        p.setClipPath(shape)

        # .circle: 20px 起步扩散到覆盖整个按钮（圆心在中心，直径取对角线
        # 长度 + 余量即恰好盖住四角；CSS 原值 220px 是其小容器下的覆盖态）
        d_max = math.hypot(w, h) + 24.0
        d = 20.0 + (d_max - 20.0) * t_part
        circle = QColor(self.LIME)
        circle.setAlphaF(min(1.0, t_part * 1.15))
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(circle)
        p.drawEllipse(QPointF(w / 2.0, h / 2.0), d / 2.0, d / 2.0)

        arrow_color = _mix(self.LIME, self.DARK, t_all)
        aw = 24.0
        # arr-1: right 16px -> -25%（滑出）；arr-2: left -25% -> 16px（滑入）
        x1 = (w - 16.0 - aw) * (1.0 - t_part) + (-w * 0.25) * t_part
        x2 = (-w * 0.25) * (1.0 - t_part) + 16.0 * t_part
        self._arrow(p, x1, (h - aw) / 2.0, aw, aw, arrow_color)
        self._arrow(p, x2, (h - aw) / 2.0, aw, aw, arrow_color)

        # .text: translateX(-12px) -> +12px，color greenyellow -> #212121
        shift = -12.0 + 24.0 * t_part
        fg = _mix(self.LIME, self.DARK, t_all)
        p.setPen(fg)
        p.setFont(self.font())
        p.drawText(QRectF(shift, 0, w, h), Qt.AlignmentFlag.AlignCenter, self.text())
        p.restore()

        if scaled:
            p.restore()
        p.end()

    def _paint_static(self, p: QPainter, w: float, h: float) -> None:
        """未悬停/未按下的静态态：Grid 暗色直角，保持整套界面语言统一。"""
        hover = self.underMouse()
        if self._mode == "start":
            bg = QColor(WHITE) if hover else QColor(INK)
            fg = QColor(INK) if hover else QColor(WHITE)
            border = QColor(WHITE) if hover else QColor(LINE)
        else:
            bg = QColor(RED) if hover else QColor(PAPER)
            fg = QColor(WHITE) if hover else QColor(RED_BRIGHT)
            border = QColor(RED)
        p.fillRect(self.rect(), bg)
        p.setPen(QPen(border, 1))
        p.drawRect(QRectF(0.5, 0.5, w - 1, h - 1))
        p.setPen(fg)
        p.setFont(self.font())
        p.drawText(QRectF(0, 0, w, h), Qt.AlignmentFlag.AlignCenter, self.text())


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
