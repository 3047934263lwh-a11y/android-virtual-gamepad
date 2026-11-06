# -*- coding: utf-8 -*-
"""Uiverse「Grid」暗色设计系统 + 午夜星空背景的 Qt 设计令牌。

结构参考 uiverse.io/design/systems/grid（darkBrutalist / Grid）：
  单一网格、1px 发丝分区、层级靠跨格与字号、单一信号色、无圆角/阴影/浮动。
背景参考 Uiverse.io by jaykdoe 的星空动效（按需求改为中性黑，去蓝色）：
  radial-gradient(ellipse at bottom, #131316 0%, #070708 100%)。
本项目保留瑞士红作唯一信号色。
"""
import os
import sys

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
# 随包字体（均 OFL 许可，来源猫啃网）：
#   英文 / 数字 → BBH Bogle（assets/fonts/BBHBogle-Regular.ttf）
#   中文        → 正格点黑16（assets/fonts/ZhengGeDianHei-16.ttf）
# load_fonts() 在 QApplication 创建后注册字体并刷新族列表与 QSS；
# 加载失败时回落到系统字体（Arial / Microsoft YaHei），不影响运行。
FONT_EN = "BBH Bogle"
FONT_ZH = "正格点黑16"
FALLBACK_ZH = "Microsoft YaHei"

SANS = "Arial"            # 字体注册失败时的兜底族名
MONO = "Consolas"         # 等宽（地址 / 日志）保持系统等宽，中文回落正格点黑
UI_FAMILIES: list[str] = [SANS, FALLBACK_ZH]
MONO_FAMILIES: list[str] = [MONO, FALLBACK_ZH]

UNIT = 8
SIDEBAR_W = 288
HEADER_H = 72
STATUSBAR_H = 40


def font(size: int = 10, bold: bool = False, mono: bool = False,
         tracking: float = 0.0) -> QFont:
    f = QFont()
    # 族列表：英文/数字取第一族，中文字形回落到正格点黑16 → 实现中英各用各的
    f.setFamilies(list(MONO_FAMILIES if mono else UI_FAMILIES))
    f.setPointSizeF(float(size))
    f.setBold(bold)
    if tracking:
        f.setLetterSpacing(QFont.SpacingType.AbsoluteSpacing, tracking)
    return f


def load_fonts() -> None:
    """注册随包字体并刷新全局字体族与 QSS（须在 QApplication 创建后调用一次）。

    只做字体适配，不触碰任何业务逻辑；字体文件缺失时静默回落系统字体。
    """
    from PySide6.QtGui import QFontDatabase

    base = getattr(sys, "_MEIPASS", None)   # PyInstaller onedir 资源根
    candidates = [
        os.path.join(base, "assets", "fonts") if base else None,
        os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "assets", "fonts"),
    ]
    font_dir = next((d for d in candidates if d and os.path.isdir(d)), None)
    en_fams: list[str] = []
    zh_fams: list[str] = []
    if font_dir:
        # 注册结果里取真实族名（如正格点黑16 的族名是 "ZhengGeDianHei 16"），
        # 不按文件名或中文别名硬编码。
        for fname, bucket in (("BBHBogle-Regular.ttf", en_fams),
                              ("ZhengGeDianHei-16.ttf", zh_fams)):
            fid = QFontDatabase.addApplicationFont(os.path.join(font_dir, fname))
            if fid >= 0:
                bucket.extend(QFontDatabase.applicationFontFamilies(fid))

    global UI_FAMILIES, MONO_FAMILIES, QSS
    ui = en_fams + zh_fams + [FALLBACK_ZH, SANS]
    UI_FAMILIES = ui or list(UI_FAMILIES)
    MONO_FAMILIES = [MONO] + zh_fams + [FALLBACK_ZH]
    QSS = build_qss()


def _families_qss(families: list[str]) -> str:
    return ", ".join(f'"{name}"' for name in families)


def build_qss() -> str:
    """生成 QSS。全局规则不写死 font-family：族列表（Bogle→正格点黑→雅黑）
    由 font()/app.setFont 提供，QSS 写死族名会把逐字形回落覆盖成单一族。"""
    mono_qss = _families_qss(MONO_FAMILIES)
    return f"""
/* ---- 全局：容器透明，让星空背景透出 ---- */
QWidget {{
    color: {WHITE};
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
    color: {WHITE}; font-family: {mono_qss}; font-size: 27pt; font-weight: bold;
}}
QLabel#addressPort {{ color: #AEB8C8; font-family: {mono_qss}; font-size: 15pt; }}
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


# 导入时先用系统兜底族生成一份 QSS；load_fonts() 注册随包字体后重建。
QSS = build_qss()
