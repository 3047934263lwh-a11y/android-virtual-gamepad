# -*- coding: utf-8 -*-
"""一键构建 Windows x64 免安装便携版。

用法（在具备 PySide6 + PyInstaller 的 Python 3.13 环境中）：
    python build_portable.py

产物：
    ../release/VirtualGamepadReceiver-Portable/                 完整程序目录
    ../release/VirtualGamepadReceiver-Portable-v1.0.0-win64.zip  拷贝即用压缩包

脚本会：生成图标 → 冻结为原生窗口 EXE（onedir）→ 补齐 VC++ 运行库与
许可文件 → 组织交付目录 → 结构校验 → 打包 zip。
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))          # desktop/
REPO = os.path.dirname(ROOT)
RELEASE = os.path.join(REPO, "release")
DIST = os.path.join(RELEASE, "_dist_tmp")
WORK = os.path.join(RELEASE, "_build_tmp")
PKG = "VirtualGamepadReceiver-Portable"
OUT = os.path.join(RELEASE, PKG)
ZIP = os.path.join(RELEASE, f"{PKG}-v1.0.0-win64.zip")
EXE = "VirtualGamepadReceiver.exe"

# VC++ 2015-2022 运行库：Qt6 / CPython 在干净的 Windows 上也可能需要，
# 随目录附带以免目标机器缺件；系统已有同名文件时保留 PyInstaller 收集的版本。
VCRUNTIME = (
    "vcruntime140.dll", "vcruntime140_1.dll",
    "msvcp140.dll", "msvcp140_1.dll", "msvcp140_2.dll",
    "concrt140.dll",
)

README = """VIRTUAL GAMEPAD  ·  PC RECEIVER  ·  便携版 v1.0.0
==================================================

【启动】
双击本目录下的 VirtualGamepadReceiver.exe。
这是唯一入口：不需要 Python、不需要 pip、不需要安装、不需要配置环境变量。
整个文件夹可以拷贝到任意 Windows 10/11 (64 位) PC、U 盘或移动硬盘直接运行。

【目录】
VirtualGamepadReceiver.exe     主程序（窗口程序，无控制台）
_internal\\                     Python 运行时、Qt 运行库、Qt 插件、ViGEm 客户端
config\\settings.ini           个人配置（首次运行自动生成，随文件夹一起走）
LICENSE-*.txt                  随程序分发的第三方许可证
可选驱动\\                      仅「虚拟手柄输出」需要的系统驱动（见下）
error.log                      仅启动异常时生成，用于排查

【使用】
1. 手机与 PC 接入同一局域网。
2. 本程序左侧显示「局域网 IP」与「UDP 端口」（默认 26760）。
3. 在手机 App 中填写该 IP 与端口，点击「开始监听」或按 CTRL+S/F5。
4. 接收内容（按键、摇杆、扳机、序列号、速率、丢包）实时显示在主区。

快捷键： CTRL+S / F5 开始或停止监听 · CTRL+L 清空日志 · CTRL+Q 退出
        点击数据行可选中设备并查看摇杆位置 · 选中表格后 CTRL+C 复制整行

【关于 ViGEmBus 驱动（重要）】
本软件本身完全绿色：接收、解析、状态反馈、异常处理、界面展示均开箱可用。
「把报文变成 Windows 虚拟 Xbox 360 手柄」属于系统内核级能力，任何绿色软件
都无法自带该驱动。若目标 PC 未安装 ViGEmBus 1.22.0，程序不会退出，会自动
进入「仅监听」模式并在界面顶部给出提示；此时数据接收与展示一切正常。
需要手柄输出时，可选运行「可选驱动\\ViGEmBus_Setup.exe」（仅在缺驱动时需要，
安装后重启一次即可），之后重新打开本程序会自动启用。

【防火墙】
首次监听时 Windows 防火墙可能弹出「允许访问网络」提示，请选择「允许」，
否则手机发来的 UDP 报文会被系统拦截。
"""


def run(cmd: list[str], cwd: str) -> None:
    print("+", " ".join(cmd), flush=True)
    r = subprocess.run(cmd, cwd=cwd, text=True, encoding="utf-8",
                       errors="replace", capture_output=True)
    if r.stdout:
        print(r.stdout[-4000:], flush=True)
    if r.returncode != 0:
        print(r.stderr[-6000:], flush=True)
        raise SystemExit(f"命令失败（退出码 {r.returncode}）")


def build_icon() -> None:
    run([sys.executable, os.path.join("assets", "make_icon.py")], ROOT)


def freeze() -> None:
    run([sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean",
         "--log-level", "WARN", "--distpath", DIST, "--workpath", WORK,
         "portable.spec"], ROOT)


def assemble() -> None:
    src = os.path.join(DIST, "VirtualGamepadReceiver")
    if not os.path.isfile(os.path.join(src, EXE)):
        raise SystemExit("未找到 PyInstaller 产出的 EXE")
    if os.path.isdir(OUT):
        try:
            shutil.rmtree(OUT)
        except OSError as exc:
            raise SystemExit(f"无法清理旧交付目录（请先关闭正在运行的接收端）：{exc}")
    shutil.copytree(src, OUT, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))

    internal = os.path.join(OUT, "_internal")
    sys32 = os.path.join(os.environ.get("WINDIR", "C:/Windows"), "System32")
    copied = []
    for name in VCRUNTIME:
        f = os.path.join(sys32, name)
        d = os.path.join(internal, name)
        if os.path.isfile(f) and not os.path.exists(d):
            shutil.copy2(f, d)
            copied.append(name)
    print("VC++ 运行库补齐：", ", ".join(copied) or "已由 PyInstaller 收集", flush=True)

    # 许可证随二进制一起分发
    for src_name, dst_name in (
        (os.path.join(REPO, "LICENSE"), "LICENSE-project.txt"),
        (os.path.join(ROOT, "vendor", "vgamepad", "LICENSE-MIT.txt"),
         "LICENSE-vgamepad-MIT.txt"),
    ):
        if os.path.isfile(src_name):
            shutil.copy2(src_name, os.path.join(OUT, dst_name))

    os.makedirs(os.path.join(OUT, "config"), exist_ok=True)
    with open(os.path.join(OUT, "使用说明.txt"), "w", encoding="utf-8-sig") as fh:
        fh.write(README)

    driver_dir = os.path.join(OUT, "可选驱动")
    src_driver = os.path.join(ROOT, "assets", "ViGEmBus_Setup.exe")
    if os.path.isfile(src_driver):
        os.makedirs(driver_dir, exist_ok=True)
        shutil.copy2(src_driver, os.path.join(driver_dir, "ViGEmBus_Setup.exe"))
        with open(os.path.join(driver_dir, "说明.txt"), "w", encoding="utf-8-sig") as fh:
            fh.write(
                "ViGEmBus Setup 1.22.0\n"
                "来源：官方发布 nefarius/ViGEmBus release v1.22.0\n"
                "用途：仅当需要「把手机报文变成 Windows 虚拟 Xbox 360 手柄」时，\n"
                "      且本机尚未安装 ViGEmBus 驱动，才需要右键「以管理员身份运行」。\n"
                "      安装完成后重启一次电脑，再打开 VirtualGamepadReceiver.exe 即可。\n"
                "说明：这是 Windows 内核驱动，任何绿色软件都无法自带；不运行它，\n"
                "      本软件的接收、解析、状态反馈与界面展示功能全部照常可用。\n"
            )


def verify() -> None:
    must = [
        EXE,
        os.path.join("_internal", "app.ico"),
        os.path.join("_internal", "vendor", "vgamepad", "win", "vigem",
                     "client", "x64", "ViGEmClient.dll"),
        os.path.join("_internal", "vendor", "vgamepad", "win", "vigem",
                     "client", "x86", "ViGEmClient.dll"),
    ]
    missing = [p for p in must if not os.path.isfile(os.path.join(OUT, p))]
    if missing:
        raise SystemExit("交付目录缺少文件：" + ", ".join(missing))

    def find(name: str) -> bool:
        target = name.lower()
        for base, _, files in os.walk(os.path.join(OUT, "_internal")):
            if any(f.lower() == target for f in files):
                return True
        return False

    for name in ("python313.dll", "qwindows.dll", "Qt6Widgets.dll",
                 "QtWidgets.pyd", "vcruntime140.dll"):
        print(f"  校验 {name}: {'OK' if find(name) else '缺失'}", flush=True)
        if name in ("python313.dll", "qwindows.dll") and not find(name):
            raise SystemExit(f"关键运行库缺失：{name}")

    # 交付目录内不应残留源码 / 脚本启动器。
    # 例外：_internal/vendor/vgamepad 是有意以 .py 数据文件分发的可选运行库。
    forbidden = []
    for base, _, files in os.walk(OUT):
        for f in files:
            if f.endswith((".py", ".bat", ".cmd", ".ps1")) or f.endswith(".pyc"):
                rel = os.path.relpath(os.path.join(base, f), OUT)
                if rel.lower().startswith("_internal\\vendor\\") and f.endswith(".py"):
                    continue
                forbidden.append(rel)
    if forbidden:
        raise SystemExit("交付目录内出现源码/缓存/脚本：" + ", ".join(forbidden[:8]))
    print(f"交付目录就绪：{OUT}", flush=True)


def make_zip() -> None:
    if os.path.isfile(ZIP):
        os.remove(ZIP)
    n = 0
    with zipfile.ZipFile(ZIP, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for base, _, files in os.walk(OUT):
            for f in files:
                full = os.path.join(base, f)
                z.write(full, os.path.join(PKG, os.path.relpath(full, OUT)))
                n += 1
    print(f"压缩包：{ZIP}  ({n} 个文件, {os.path.getsize(ZIP) / 1048576:.1f} MiB)",
          flush=True)


def main() -> None:
    os.makedirs(RELEASE, exist_ok=True)
    build_icon()
    freeze()
    assemble()
    verify()
    make_zip()
    for tmp in (DIST, WORK):
        if os.path.isdir(tmp):
            shutil.rmtree(tmp, ignore_errors=True)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
