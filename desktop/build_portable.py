# -*- coding: utf-8 -*-
"""一键构建 Windows x64 免安装便携版。

用法（在具备 PySide6 + PyInstaller 的 Python 3.13 环境中）：
    python build_portable.py

产物：
    ../release/VirtualGamepadReceiver-Portable/                 完整程序目录
    ../release/VirtualGamepadReceiver-Portable-v1.1.1-win64.zip  拷贝即用压缩包

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
ZIP = os.path.join(RELEASE, f"{PKG}-v1.1.1-win64.zip")
EXE = "VirtualGamepadReceiver.exe"

# VC++ 2015-2022 运行库：Qt6 / CPython 在干净的 Windows 上也可能需要，
# 随目录附带以免目标机器缺件；系统已有同名文件时保留 PyInstaller 收集的版本。
VCRUNTIME = (
    "vcruntime140.dll", "vcruntime140_1.dll",
    "msvcp140.dll", "msvcp140_1.dll", "msvcp140_2.dll",
    "concrt140.dll",
)

README = """VIRTUAL GAMEPAD  ·  PC RECEIVER  ·  便携版 v1.1.1
==================================================

【启动】
双击本目录下的 VirtualGamepadReceiver.exe。
这是唯一入口：不需要 Python、不需要 pip、不需要安装、不需要配置环境变量。
整个文件夹可以拷贝到任意 Windows 10/11 (64 位) PC、U 盘或移动硬盘直接运行。

【目录】
VirtualGamepadReceiver.exe     主程序（窗口程序，无控制台）
_internal\\                     Python 运行时、Qt 运行库、Qt 插件、ViGEm 客户端
config\\settings.ini           默认配置（随包分发，运行时自动更新，随文件夹一起走）
LICENSE-*.txt                  随程序分发的第三方许可证
可选驱动\\                      仅「虚拟手柄输出」需要的系统驱动（见下）
error.log                      仅启动异常时生成，用于排查

【使用】
界面为深色星空主题（星点持续缓慢上升，不影响操作与文字阅读）。
1. 手机与 PC 接入同一局域网。
2. 左栏显示本机地址与端口，点 COPY 可复制；在手机 App 中填写该地址与端口。
3. 点击 START 或按 CTRL+S / F5 开始监听；运行中按钮变为 STOP，再点一次停止。
4. 右栏显示已连接设备数与 P1–P4 槽位；异常时底部显示一行简短提示。

快捷键： CTRL+S / F5 开始或停止监听 · CTRL+L 清空日志 · CTRL+Q 退出
        选中表格后 CTRL+C 复制整行

【窗口】
顶部黑色区域是应用自绘标题栏，无 Windows 原生标题栏与系统按钮。
按住顶栏空白处可拖动窗口，双击空白处最大化 / 还原；
右上角三个按钮依次为最小化、最大化 / 还原、关闭（悬停与按下均有反馈，
关闭按钮悬停为红色）。窗口四边 6px 为缩放热区，靠近时鼠标变为双向箭头，
拖动即可调整大小；最大化时自动禁用缩放。
任务栏图标、Alt+Tab、Aero Snap 拖到屏幕边缘贴靠、Win+方向键均照常可用。
窗口失焦时顶栏会自动降低对比度，激活后恢复。

【关于 ViGEmBus 驱动（重要）】
本软件本身完全绿色：接收、解析、状态反馈、异常处理、界面展示均开箱可用。
「把报文变成 Windows 虚拟 Xbox 360 手柄」属于系统内核级能力，任何绿色软件
都无法自带该驱动。若目标 PC 未安装 ViGEmBus，程序不会退出，会自动
进入「仅监听」模式并在界面顶部给出提示；此时数据接收与展示一切正常。
需要手柄输出时，可选运行「可选驱动\\ViGEmBusSetup_x64.msi」（仅在缺驱动时需要，
离线安装、无需联网，安装后重启一次即可），之后重新打开本程序会自动启用。

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

    cfg_dir = os.path.join(OUT, "config")
    os.makedirs(cfg_dir, exist_ok=True)
    # 预置默认配置：解压后目录结构完整，用户也可直接改端口后再分发
    with open(os.path.join(cfg_dir, "settings.ini"), "w", encoding="utf-8") as fh:
        # 与 desktop/main.py 保持一致：auto（启动时自动监听）已于 2026-11-06 移除，
        # 应用固定以 STOPPED 启动、需手动 START，故预置配置不再写入 auto 键。
        fh.write("[General]\nport=26760\nvigem=true\ndebug=false\n")
    with open(os.path.join(OUT, "使用说明.txt"), "w", encoding="utf-8-sig") as fh:
        fh.write(README)

    driver_dir = os.path.join(OUT, "可选驱动")
    # 优先随包离线 MSI（1.17.333，876 KB，装完即用，不联网）；
    # 官方 1.22.0 的 Setup.exe 是联网 stub（6.2 MB），装的时候还要再下 9.9 MB
    # 载荷，代理环境下会被写坏（全零文件 → Error 1308），故仅作后备。
    msi = os.path.join(ROOT, "assets", "ViGEmBusSetup_x64.msi")
    stub = os.path.join(ROOT, "assets", "ViGEmBus_Setup.exe")
    if os.path.isfile(msi):
        os.makedirs(driver_dir, exist_ok=True)
        shutil.copy2(msi, os.path.join(driver_dir, "ViGEmBusSetup_x64.msi"))
        with open(os.path.join(driver_dir, "说明.txt"), "w", encoding="utf-8-sig") as fh:
            fh.write(
                "ViGEmBus 1.17.333（离线 MSI，推荐）\n"
                "来源：官方 nefarius/ViGEmBus 驱动（与 v1.0.1–v1.0.10 便携包所带为同一文件）\n"
                "用途：仅当需要「把手机报文变成 Windows 虚拟 Xbox 360 手柄」时，\n"
                "      且本机尚未安装 ViGEmBus 驱动，才需要右键「以管理员身份运行」。\n"
                "      安装完成后重启一次电脑，再打开 VirtualGamepadReceiver.exe 即可。\n"
                "说明：本 MSI 完全离线，安装过程不下载任何东西；已在 Windows 10 22H2 上验证可用。\n"
                "      这是 Windows 内核驱动，任何绿色软件都无法自带；不运行它，\n"
                "      本软件的接收、解析、状态反馈与界面展示功能全部照常可用。\n"
            )
    elif os.path.isfile(stub):
        os.makedirs(driver_dir, exist_ok=True)
        shutil.copy2(stub, os.path.join(driver_dir, "ViGEmBus_Setup.exe"))
        with open(os.path.join(driver_dir, "说明.txt"), "w", encoding="utf-8-sig") as fh:
            fh.write(
                "ViGEmBus Setup 1.22.0（联网安装器）\n"
                "注意：安装过程需联网下载约 9.9 MB 载荷；代理/VPN 环境下可能下载失败。\n"
            )


    # 手机端 APK 随包：优先取与当前 Release 同版的待发布 APK
    # （release/_staging/），其次正式签名的 assembleRelease 产物，
    # 最后回退 debug 产物。解压后无需再单独下载手机端。
    apk_name = "VirtualGamepad-v1.1.1.apk"
    apk_candidates = [
        os.path.join(RELEASE, "_staging", apk_name),
        os.path.join(REPO, "android", "app", "build", "outputs", "apk",
                     "release", "app-release.apk"),
        os.path.join(REPO, "android", "app", "build", "outputs", "apk",
                     "debug", "app-debug.apk"),
    ]
    apk_src = next((p for p in apk_candidates if os.path.isfile(p)), None)
    if apk_src:
        phone_dir = os.path.join(OUT, "手机端")
        os.makedirs(phone_dir, exist_ok=True)
        shutil.copy2(apk_src, os.path.join(phone_dir, apk_name))
        print(f"随包 APK：{apk_src}", flush=True)
        with open(os.path.join(phone_dir, "安装说明.txt"), "w",
                  encoding="utf-8-sig") as fh:
            fh.write(
                "安装步骤\n"
                f"1. 把 {apk_name} 传到安卓手机并安装\n"
                "   （首次安装需在系统里允许「安装未知来源应用」）。\n"
                "2. 手机与电脑连同一个 Wi-Fi。\n"
                "3. 电脑上运行 VirtualGamepadReceiver.exe，点 START。\n"
                "4. 手机打开 App，把 IP 填成电脑的局域网地址（接收端界面左上角显示），\n"
                "   端口保持 26760，状态条变绿即已连接。\n"
                "说明：随包 APK 与当前 Release 的手机端版本一致；\n"
                "      也可在 App 内「检查更新」获取最新版本。\n"
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
