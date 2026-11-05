# -*- coding: utf-8 -*-
"""瑞士国际主义 × Uiverse「Grid」设计令牌与 QSS。

参考 Uiverse Grid 设计系统（uiverse.io/design/systems/grid）：
  单一网格承载所有元素，层级由跨格与字号建立而不是装饰；
  1px 发丝线分区；单一信号色激活状态与交互；
  无浮动元素、无柔和阴影、无圆角。
本项目沿用既有黑白 + 瑞士红（而非 Grid 的 acid-lime），保持配色一致。
"""
from PySide6.QtGui import QFont

# ---- 配色 ---------------------------------------------------------------
INK = "#111111"          # 主文字 / 主按钮 / 强调线
PAPER = "#FFFFFF"        # 背景
SURFACE = "#F5F5F5"      # 极浅底
LINE = "#D9D9D9"         # 1px 发丝分区线
MUTED = "#707070"        # 次要文字
RED = "#D8232A"          # 唯一信号色：状态 / 告警 / 主操作按下
WHITE = "#FFFFFF"

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
/* ---- 全局：不设背景色，避免子控件白底把黑顶栏挖穿 ---- */
QWidget {{
    color: {INK};
    font-family: "{SANS}";
    font-size: 10pt;
}}
QWidget[block="1"] {{ background: {PAPER}; }}
QMainWindow, QWidget#central, QWidget#body, QWidget#gridCell,
QWidget#footer, QWidget#statusBar {{
    border: none;
    background: {PAPER};
}}

/* ---- 顶栏：整块黑，无渐变、无圆角、无阴影 ---- */
QWidget#header {{ background: {INK}; }}
QWidget#headerInactive {{ background: #242424; }}
QLabel#brand {{ color: {WHITE}; font-size: 17pt; font-weight: bold; }}
QLabel#brandSub {{ color: #9A9A9A; font-size: 8pt; }}
QLabel#headerState {{ color: {WHITE}; font-size: 9pt; font-weight: bold; }}
QLabel#headerMeta {{ color: #9A9A9A; font-size: 8pt; }}
QWidget#stateChip {{ background: {RED}; }}
QWidget#stateChipIdle {{ background: #6A6A6A; }}

/* ---- 自绘窗口控制区：无 tooltip、无原生外观 ---- */
QAbstractButton#winBtn {{
    background: transparent; border: none; padding: 0; margin: 0;
}}
QWidget#headerInactive QLabel#brand {{ color: #D5D5D5; }}
QWidget#headerInactive QLabel#headerState {{ color: #B4B4B4; }}
QWidget#headerInactive QLabel#brandSub,
QWidget#headerInactive QLabel#headerMeta {{ color: #787878; }}

/* ---- Grid：1px 发丝分区，单元格之间不留阴影/圆角 ---- */
QWidget#gridCell {{ border-right: 1px solid {LINE}; }}
QWidget#gridCellLast {{ border: none; }}
QWidget#footer {{ border-top: 1px solid {LINE}; }}
QFrame#vRule {{ background: {LINE}; }}
QFrame#hRule {{ background: {LINE}; }}

/* ---- 网格分区标题：编号 + 英文大写字距 ---- */
QLabel#section {{
    color: {MUTED}; font-size: 8pt; font-weight: bold;
    padding-bottom: 4px;
}}
QLabel#fieldLabel {{
    color: {MUTED}; font-size: 8pt; font-weight: bold;
}}
QLabel#address {{
    color: {INK}; font-family: "{MONO}"; font-size: 27pt; font-weight: bold;
}}
QLabel#addressPort {{
    color: {INK}; font-family: "{MONO}"; font-size: 15pt;
}}
QLabel#bigNumber {{
    color: {INK}; font-size: 54pt; font-weight: bold;
}}
QLabel#bigUnit {{
    color: {MUTED}; font-size: 8pt; font-weight: bold;
}}

/* ---- 主操作：跨整格，hover 反色，按下信号色 ---- */
QPushButton#primary {{
    background: {INK}; color: {WHITE}; border: 1px solid {INK};
    padding: 18px 24px; font-size: 11pt; font-weight: bold;
    text-align: center;
}}
QPushButton#primary:hover {{ background: {PAPER}; color: {INK}; }}
QPushButton#primary:pressed {{ background: {RED}; color: {WHITE}; border-color: {RED}; }}
QPushButton#primaryStop {{
    background: {PAPER}; color: {RED}; border: 1px solid {RED};
    padding: 18px 24px; font-size: 11pt; font-weight: bold;
    text-align: center;
}}
QPushButton#primaryStop:hover {{ background: {RED}; color: {WHITE}; }}
QPushButton#copyBtn {{
    background: transparent; color: {MUTED}; border: 1px solid {LINE};
    padding: 5px 12px; font-size: 8pt; font-weight: bold;
}}
QPushButton#copyBtn:hover {{ color: {INK}; border-color: {INK}; }}
QPushButton#copyBtn:pressed {{ background: {SURFACE}; }}

/* ---- 底部：只承载异常提示，不重复运行状态、不显示快捷键 ---- */
QLabel#statusError {{ color: {RED}; font-size: 9pt; }}
QLabel#statusText {{ color: {INK}; font-size: 8.5pt; }}
QWidget#statusDot {{ background: {LINE}; }}
QWidget#statusDotOn {{ background: {RED}; }}

/* ---- 隐藏的兼容控件：不参与视觉，保留基础绘制 ---- */
QLineEdit#portEdit {{
    background: {PAPER}; color: {INK}; border: 1px solid {INK};
}}
QWidget#alertBar {{ background: {RED}; }}
QLabel#alertText {{ color: {WHITE}; }}
QPlainTextEdit#logView {{ background: {PAPER}; color: {INK}; border: none; }}
QTableWidget#dataTable {{ background: {PAPER}; border: none; }}
QScrollBar:vertical {{ background: {PAPER}; width: 8px; border: none; margin: 0; }}
"""
