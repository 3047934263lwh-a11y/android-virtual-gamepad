"""翻转卡片视觉复检：渲染空闲 / 有设备 / 翻转中 / 背面四张截图。"""
import os
import sys

# os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")  # 改用原生平台渲染真实字体
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from PySide6.QtWidgets import QApplication  # noqa: E402
from PySide6.QtCore import QTimer  # noqa: E402

import main as m  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_card_shots")
os.makedirs(OUT, exist_ok=True)

app = QApplication([])
w = m.MainWindow()
w.resize(1320, 860)
# 不在屏幕上弹窗，但用原生 windows 平台的真实字体栈渲染（offscreen 平台无法栅格化字体）
w.setAttribute(__import__("PySide6.QtCore", fromlist=["Qt"]).Qt.WidgetAttribute.WA_DontShowOnScreen, True)
w.show()

FAKE = [
    {"slot": 1, "ip": "192.168.1.23", "port": 51001, "pps": 248,
     "seq": 10245, "buttons": ["A", "右摇杆", "LT"]},
    {"slot": 3, "ip": "192.168.1.45", "port": 51003, "pps": 251,
     "seq": 9877, "buttons": []},
]


def snap(name: str) -> None:
    w.grab().save(os.path.join(OUT, name))
    print("saved", name)


def step1():
    w.card_grid.set_slots([])          # 空闲：四张灰卡
    QTimer.singleShot(120, lambda: (snap("1-idle.png"), step2()))


def step2():
    w.card_grid.set_slots(FAKE)        # P1/P3 有设备
    QTimer.singleShot(120, lambda: (snap("2-front-active.png"), step3()))


def step3():
    # 悬停翻转到一半（ progress=0.45，仍正面，明显压缩）
    w.card_grid.cards[0]._on_anim(0.45)
    QTimer.singleShot(120, lambda: (snap("3-mid-flip.png"), step4()))


def step4():
    # 悬停翻满（progress=1，背面绿）
    w.card_grid.cards[0]._on_anim(1.0)
    w.card_grid.cards[1]._on_anim(0.999)
    QTimer.singleShot(120, lambda: (snap("4-back-green.png"), app.quit()))


QTimer.singleShot(300, step1)
QTimer.singleShot(6000, app.quit)
app.exec()
print("done")
