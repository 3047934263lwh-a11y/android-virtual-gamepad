"""启动状态回归测试：应用打开后必须处于「已停止」，不得自动开始监听。"""
import os
import sys

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from PySide6.QtWidgets import QApplication  # noqa: E402
from PySide6.QtCore import QTimer  # noqa: E402

import main as m  # noqa: E402

app = QApplication([])
w = m.MainWindow()
result = {}


def check():
    result["running"] = w.receiver.is_running()
    result["header"] = w.header_state.text()
    result["btn_text"] = w.toggle_btn.text()
    app.quit()


# 旧逻辑在启动后 120ms 自动 start_listen；等 800ms 足以暴露回归
QTimer.singleShot(800, check)
QTimer.singleShot(4000, app.quit)
app.exec()

running = result.get("running", None)
print("自动开始监听:", running)
print("顶栏状态:", result.get("header"))
print("按钮文案:", repr(result.get("btn_text")))
if running:
    print("FAIL: 应用启动即自动监听")
    sys.exit(1)
print("PASS: 启动后处于已停止状态，等待用户手动开始")
