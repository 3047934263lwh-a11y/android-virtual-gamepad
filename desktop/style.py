# -*- coding: utf-8 -*-
"""瑞士国际主义风格设计令牌与 QSS。

原则：8px 基准网格、严格左对齐、单一强调色、无圆角/无阴影/无渐变/
无装饰性图形；层级只由字号、字重、留白与 1px 分隔线建立。
"""
from PySide6.QtGui import QFont

# ---- 配色 ---------------------------------------------------------------
INK = "#111111"          # 主文字 / 主按钮 / 强调线
PAPER = "#FFFFFF"        # 背景
SURFACE = "#F5F5F5"      # 极浅底（选中 / 禁用）
LINE = "#D9D9D9"         # 1px 分隔线
MUTED = "#707070"        # 次要文字
RED = "#D8232A"          # 瑞士红：唯一强调色（状态 / 告警 / 数据点）
WHITE = "#FFFFFF"

# ---- 字体 ---------------------------------------------------------------
SANS = "Arial"           # Helvetica 系无衬线；中文由系统 fallback 到雅黑
MONO = "Consolas"        # 数据列等宽，保证纵向精准对齐

UNIT = 8                 # 8px 网格基准
SIDEBAR_W = 288
HEADER_H = 72
STATUSBAR_H = 36


def font(size: int = 10, bold: bool = False, mono: bool = False,
         tracking: float = 0.0) -> QFont:
    f = QFont(MONO if mono else SANS)
    f.setPointSizeF(float(size))
    f.setBold(bold)
    if tracking:
        f.setLetterSpacing(QFont.SpacingType.AbsoluteSpacing, tracking)
    return f


QSS = f"""
/* ---- 全局：不设背景色，避免子控件白底把黑顶栏挖穿 ---- */
QWidget {{
    color: {INK};
    font-family: "{SANS}";
    font-size: 10pt;
}}
QWidget[block="1"] {{ background: {PAPER}; }}
QMainWindow, QWidget#central, QWidget#body, QWidget#mainPane,
QWidget#sidebar, QWidget#statusBar {{
    border: none;
    background: {PAPER};
}}

/* ---- 顶栏 ---- */
QWidget#header {{ background: {INK}; }}
QWidget#headerInactive {{ background: #242424; }}
QLabel#brand {{ color: {WHITE}; font-size: 17pt; font-weight: bold; }}
QLabel#brandSub {{ color: #9A9A9A; font-size: 8pt; }}
QLabel#headerState {{ color: {WHITE}; font-size: 9pt; font-weight: bold; }}
QLabel#headerMeta {{ color: #9A9A9A; font-size: 8pt; }}
QWidget#stateChip {{ background: {RED}; }}
QWidget#stateChipIdle {{ background: #6A6A6A; }}

/* ---- 自绘窗口控制区（无原生标题栏） ---- */
QAbstractButton#winBtn {{
    background: transparent; border: none; padding: 0; margin: 0;
}}
QWidget#headerInactive QLabel#brand {{ color: #D5D5D5; }}
QWidget#headerInactive QLabel#headerState {{ color: #B4B4B4; }}
QWidget#headerInactive QLabel#brandSub,
QWidget#headerInactive QLabel#headerMeta {{ color: #787878; }}

/* ---- 错误横幅 ---- */
QWidget#alertBar {{ background: {RED}; }}
QLabel#alertText {{ color: {WHITE}; font-size: 9.5pt; }}
QPushButton#alertDismiss {{
    color: {WHITE}; background: transparent; border: 1px solid {WHITE};
    padding: 2px 10px; font-size: 8pt;
}}
QPushButton#alertDismiss:hover {{ background: rgba(255,255,255,0.18); }}

/* ---- 侧栏 ---- */
QWidget#sidebar {{ border-right: 1px solid {LINE}; }}
QLabel#section {{
    color: {MUTED}; font-size: 8pt; font-weight: bold;
    border-bottom: 1px solid {INK}; padding-bottom: 6px;
}}
QLabel#fieldLabel {{ color: {MUTED}; font-size: 8pt; font-weight: bold; }}
QLabel#helper {{ color: #909090; font-size: 8pt; }}
QLabel#kvKey {{ color: {MUTED}; font-size: 8.5pt; }}
QLabel#kvVal {{ color: {INK}; font-size: 9pt; font-weight: bold; }}
QLabel#footNote {{ color: #909090; font-size: 8pt; }}

/* ---- 输入 ---- */
QLineEdit#portEdit {{
    background: {PAPER}; color: {INK};
    border: 1px solid {INK}; border-radius: 0;
    padding: 7px 9px; font-family: "{MONO}"; font-size: 11pt;
    selection-background-color: {INK}; selection-color: {WHITE};
}}
QLineEdit#portEdit:focus {{ border: 2px solid {INK}; padding: 6px 8px; }}
QLineEdit#portEdit:disabled {{ border-color: {LINE}; color: {MUTED}; }}

QCheckBox {{
    color: {INK}; font-size: 9pt; spacing: 10px; padding: 3px 0;
}}
QCheckBox::indicator {{
    width: 15px; height: 15px; background: {PAPER};
    border: 1px solid {INK}; border-radius: 0;
}}
QCheckBox::indicator:checked {{
    background: {INK}; border: 1px solid {INK};
}}
QCheckBox::indicator:disabled {{ border-color: {LINE}; }}
QCheckBox:disabled {{ color: #A0A0A0; }}

/* ---- 按钮 ---- */
QPushButton {{
    border-radius: 0; padding: 10px 16px; font-size: 9pt; font-weight: bold;
}}
QPushButton#primary {{
    background: {INK}; color: {WHITE}; border: 1px solid {INK};
    text-align: center;
}}
QPushButton#primary:hover {{ background: #333333; }}
QPushButton#primary:pressed {{ background: #000000; }}
QPushButton#primaryStop {{
    background: {PAPER}; color: {RED}; border: 1px solid {RED};
}}
QPushButton#primaryStop:hover {{ background: #FBF0F0; }}
QPushButton#secondary {{
    background: {PAPER}; color: {INK}; border: 1px solid {LINE};
}}
QPushButton#secondary:hover {{ background: {SURFACE}; border-color: {INK}; }}
QPushButton#ghost {{
    background: transparent; color: {MUTED}; border: none;
    padding: 2px 8px; font-size: 8pt; font-weight: normal;
}}
QPushButton#ghost:hover {{ color: {INK}; }}
QPushButton:disabled {{ background: {SURFACE}; color: #A0A0A0; border-color: {LINE}; }}

/* ---- 指标区 ---- */
QWidget#metric {{ border-top: 2px solid {INK}; }}
QLabel#metricLabel {{ color: {MUTED}; font-size: 8pt; font-weight: bold; }}
QLabel#metricValue {{ color: {INK}; font-size: 25pt; font-weight: bold; }}
QLabel#metricUnit {{ color: #909090; font-size: 8pt; }}
QFrame#vRule {{ background: {LINE}; }}

/* ---- 数据表 ---- */
QTableWidget#dataTable {{
    background: {PAPER}; border: none; gridline-color: transparent;
    font-family: "{MONO}"; font-size: 9pt;
}}
QTableWidget#dataTable::item {{
    border-bottom: 1px solid {LINE}; padding: 5px 6px; color: {INK};
}}
QTableWidget#dataTable::item:selected {{ background: {SURFACE}; color: {INK}; }}
QHeaderView::section {{
    background: {PAPER}; color: {MUTED}; border: none;
    border-bottom: 1px solid {INK};
    padding: 7px 6px; font-family: "{SANS}"; font-size: 8pt;
    font-weight: bold; text-align: left;
}}
QHeaderView {{ border: none; }}
QTableCornerButton::section {{ background: {PAPER}; border: none; }}

/* ---- 日志 ---- */
QPlainTextEdit#logView {{
    background: {PAPER}; border: none; color: {INK};
    font-family: "{MONO}"; font-size: 9pt;
    selection-background-color: {SURFACE}; selection-color: {INK};
}}
QScrollBar:vertical {{
    background: {PAPER}; width: 8px; border: none; margin: 0;
}}
QScrollBar::handle:vertical {{ background: {LINE}; min-height: 24px; }}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {{ height: 0; }}

/* ---- 状态栏 ---- */
QWidget#statusBar {{ border-top: 1px solid {LINE}; }}
QWidget#statusDot {{ background: {LINE}; }}
QWidget#statusDotOn {{ background: {RED}; }}
QLabel#statusText {{ color: {INK}; font-size: 8.5pt; }}
QLabel#statusError {{ color: {RED}; font-size: 8.5pt; }}
QLabel#shortcuts {{ color: #909090; font-size: 8pt; }}
"""
