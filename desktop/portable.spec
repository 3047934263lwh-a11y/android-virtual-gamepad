# -*- mode: python ; coding: utf-8 -*-
"""PC 便携版构建定义：onedir 原生窗口程序，所有依赖随目录分发。

运行：python -m PyInstaller portable.spec --noconfirm --clean \
          --distpath ../release/_dist_tmp --workpath ../release/_build_tmp
或直接使用 build_portable.py（推荐，会补齐运行库、组织交付目录并打 zip）。
"""
from PyInstaller.building.api import COLLECT, EXE, PYZ
from PyInstaller.building.build_main import Analysis

# vgamepad 是接收端的可选运行库（MIT）。打包器需要完整分析它，
# 否则动态 import 时其依赖的标准库（platform / pathlib / inspect 等）
# 不会被收进 PYZ，出现 "No module named 'platform'"。
# DLL 同时放到两个位置：PYZ 模块的 __file__ 相对路径、以及磁盘 vendor 路径，
# 主程序还会把 vendor 插入 sys.path，两种加载方式都能定位到 ViGEmClient.dll。
EXCLUDES = [
    'tkinter',
    'PySide6.QtWebEngineWidgets',
    'PySide6.QtWebEngineCore',
    'PySide6.QtQml',
    'PySide6.QtQuick',
    'PySide6.QtQuickWidgets',
    'PySide6.Qt3DCore',
    'PySide6.Qt3DRender',
    'PySide6.QtMultimedia',
    'PySide6.QtMultimediaWidgets',
    'PySide6.QtCharts',
    'PySide6.QtDataVisualization',
]

a = Analysis(
    ['main.py'],
    pathex=['vendor'],
    binaries=[],
    datas=[
        ('vendor', 'vendor'),
        ('vendor/vgamepad/win/vigem', 'vgamepad/win/vigem'),
        ('assets/app.ico', '.'),
        ('assets/fonts', 'assets/fonts'),
    ],
    hiddenimports=[
        'vgamepad',
        'vgamepad.api',
        'vgamepad.win',
        'vgamepad.win.vigem_client',
        'vgamepad.win.virtual_gamepad',
        'platform', 'pathlib', 'inspect', 'ctypes', 'ctypes.wintypes',
        'enum', 'abc', 'struct', 'threading', 'traceback', 'itertools', 'gc',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=EXCLUDES,
    noarchive=False,
    optimize=0,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='VirtualGamepadReceiver',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon='assets/app.ico',
    version='version_info.txt',
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name='VirtualGamepadReceiver',
)
