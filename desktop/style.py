# -*- coding: utf-8 -*-
"""Uiverse「Grid」暗色设计系统 + 午夜星空背景的 Qt 设计令牌。

结构参考 uiverse.io/design/systems/grid（darkBrutalist / Grid）：
  单一网格、1px 发丝分区、层级靠跨格与字号、单一信号色、无圆角/阴影/浮动。
背景参考 Uiverse.io by jaykdoe 的星空动效（按需求改为中性黑，去蓝色）：
  radial-gradient(ellipse at bottom, #131316 0%, #070708 100%)。
本项目保留瑞士红作唯一信号色。
"""
from PySide6.QtGui import QFont

# ---- 暗色配色 -----------------------------------------------------------
INK = "#0D131C"          # 深色块：顶栏 / 按钮 / 未占用槽位
PAPER = "#070708"        # 星空渐变外圈 / 底（中性纯黑，无蓝色成分）
SURFACE = "#151C28"      # 悬浮态
LINE = "#2A3444"         # 1px 发丝分区线
MUTED = "#8A94A6"        # 次要文字
RED = "#D8232A"          # 唯一信号色
RED_BRIGHT = "#FF4D54"   # 暗底上的可读红
GREEN = "#2FBF71"        # 连接成功信号色：1P–4P 槽位占用填充（与 STOP 的红区分）
WHITE = "#F2F5FA"        # 亮文字
BG_DEEP = "#131316"      # 星空渐变中心色（中性黑，去蓝）
BG_CELL = "rgba(8, 8, 10, 0.46)"
BG_CELL_ALT = "rgba(8, 8, 10, 0.46)"   # 与左栏一致：整张星空亮度连续贯穿左右
BG_HEADER = "rgba(5, 5, 6, 0.88)"
BG_FOOTER = "rgba(8, 8, 10, 0.72)"

# ---- 字体 ---------------------------------------------------------------
SANS = "Arial"
MONO = "Consolas"

UNIT = 8
SIDEBAR_W = 288
HEADER_H = 72
STATUSBAR_H = 40


def font(size: int = 10, bold: bool = False, mono: bool = False,
         tracking: float = 0.0) -> QFont:
    f = QFont(MONO if mono else SANS)
    f.setPointSizeF(float(size))
    f.setBold(bold)
    if tracking:
        f.setLetterSpacing(QFont.SpacingType.AbsoluteSpacing, tracking)
    return f


QSS = f"""
/* ---- 全局：容器透明，让星空背景透出 ---- */
QWidget {{
    color: {WHITE};
    font-family: "{SANS}";
    font-size: 10pt;
}}
QWidget[block="1"] {{ background: transparent; }}
QMainWindow, QWidget#central, QWidget#page, QWidget#body,
QWidget#gridCell, QWidget#gridCellLast, QWidget#footer {{
    border: none;
    background: transparent;
}}

/* ---- 顶栏 ---- */
QWidget#header {{ background: {BG_HEADER}; }}
QWidget#headerInactive {{ background: rgba(7, 10, 16, 0.96); }}
QLabel#brand {{ color: {WHITE}; font-size: 17pt; font-weight: bold; }}
QLabel#brandSub {{ color: {MUTED}; font-size: 8pt; }}
QLabel#headerState {{ color: {WHITE}; font-size: 9pt; font-weight: bold; }}
QLabel#headerMeta {{ color: {MUTED}; font-size: 8pt; }}
QWidget#stateChip {{ background: {RED}; }}
QWidget#stateChipIdle {{ background: #5A6474; }}

/* ---- 自绘窗口控制区 ---- */
QAbstractButton#winBtn {{
    background: transparent; border: none; padding: 0; margin: 0;
}}
QWidget#headerInactive QLabel#brand {{ color: #B8C2D0; }}
QWidget#headerInactive QLabel#headerState {{ color: #98A4B4; }}

/* ---- Grid：底色与 1px 竖线保留；两栏底色一致，星空一整张贯穿左右 ---- */
QWidget#gridCell {{
    border-right: 1px solid {LINE};
    background: {BG_CELL};
}}
QWidget#gridCellLast {{ background: {BG_CELL_ALT}; }}
QWidget#footer {{
    border-top: 1px solid {LINE};
    background: {BG_FOOTER};
}}
QFrame#vRule {{ background: {LINE}; }}
QFrame#hRule {{ background: {LINE}; }}

/* ---- 网格分区标题与数据 ---- */
QLabel#section {{
    color: {MUTED}; font-size: 8pt; font-weight: bold; padding-bottom: 4px;
}}
QLabel#fieldLabel {{ color: {MUTED}; font-size: 8pt; font-weight: bold; }}
QLabel#address {{
    color: {WHITE}; font-family: "{MONO}"; font-size: 27pt; font-weight: bold;
}}
QLabel#addressPort {{ color: #AEB8C8; font-family: "{MONO}"; font-size: 15pt; }}
QLabel#bigNumber {{ color: {WHITE}; font-size: 54pt; font-weight: bold; }}
QLabel#bigUnit {{ color: {MUTED}; font-size: 8pt; font-weight: bold; }}

/* ---- 次要操作 ---- */
QPushButton#copyBtn {{
    background: transparent; color: #AEB8C8; border: 1px solid {LINE};
    padding: 5px 12px; font-size: 8pt; font-weight: bold;
}}
QPushButton#copyBtn:hover {{ color: {WHITE}; border-color: {WHITE}; }}
QPushButton#copyBtn:pressed {{ background: {SURFACE}; }}

/* ---- 底部异常提示 ---- */
QLabel#statusError {{ color: {RED_BRIGHT}; font-size: 9pt; }}
QLabel#statusText {{ color: {WHITE}; font-size: 8.5pt; }}
QWidget#statusDot {{ background: {LINE}; }}
QWidget#statusDotOn {{ background: {RED}; }}

/* ---- 隐藏的兼容控件：不参与视觉 ---- */
QLineEdit#portEdit {{ background: {PAPER}; color: {WHITE}; border: 1px solid {LINE}; }}
QWidget#alertBar {{ background: {RED}; }}
QLabel#alertText {{ color: {WHITE}; }}
QPlainTextEdit#logView {{ background: {PAPER}; color: {WHITE}; border: none; }}
QTableWidget#dataTable {{ background: {PAPER}; border: none; }}
QScrollBar:vertical {{ background: {PAPER}; width: 8px; border: none; margin: 0; }}
"""
