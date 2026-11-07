"""自动翻转 + 大字排版复检：接入→自动翻绿，断开→自动翻灰。"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PySide6.QtCore import Qt, QTimer  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

import main as m  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_card_shots")
os.makedirs(OUT, exist_ok=True)

app = QApplication([])
w = m.MainWindow()
w.resize(1320, 860)
w.setAttribute(Qt.WidgetAttribute.WA_DontShowOnScreen, True)
w.show()

FAKE = [{"slot": 1, "ip": "192.168.1.23", "port": 51001, "pps": 248,
         "seq": 10245, "buttons": ["A", "右摇杆", "LT"]}]

results = {}


def snap(name: str) -> None:
    w.grab().save(os.path.join(OUT, name))
    print("saved", name)


def step_connect():
    w.card_grid.set_slots(FAKE)                     # P1 接入 → 应自动翻绿
    QTimer.singleShot(1400, step_check_connected)


def step_check_connected():
    c1 = w.card_grid.cards[0]
    results["connected_progress"] = c1._progress
    results["connected_row"] = c1._row is not None
    snap("5-auto-flip-green.png")
    w.card_grid.set_slots([])                       # P1 断开 → 应自动翻灰
    QTimer.singleShot(1400, step_check_disconnected)


def step_check_disconnected():
    c1 = w.card_grid.cards[0]
    results["disconnected_progress"] = c1._progress
    results["disconnected_row"] = c1._row is not None
    snap("6-auto-flip-gray.png")
    app.quit()


QTimer.singleShot(300, step_connect)
QTimer.singleShot(8000, app.quit)
app.exec()

p1, p2 = results.get("connected_progress"), results.get("disconnected_progress")
print("接入后 progress =", p1, "（应为 1.0，绿背面）")
print("断开后 progress =", p2, "（应为 0.0，灰正面）")
ok = (p1 is not None and abs(p1 - 1.0) < 0.01
      and p2 is not None and abs(p2 - 0.0) < 0.01)
print("PASS" if ok else "FAIL: 自动翻转未按预期")
sys.exit(0 if ok else 1)
