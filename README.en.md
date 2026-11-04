# Virtual Xbox 360 Gamepad (Android → Windows)

**Language / 语言**: [简体中文](README.md) · [English](README.en.md) · [日本語](README.ja.md) · [Español](README.es.md) · [Русский](README.ru.md)

![License](https://img.shields.io/badge/license-MIT-green) ![Platform](https://img.shields.io/badge/platform-Windows%20%2B%20Android-blue) ![Protocol](https://img.shields.io/badge/transport-UDP%20125Hz-orange)

Use an Android phone as a virtual Xbox 360 controller. Sticks, D-pad, ABXY, bumpers and triggers are streamed over LAN UDP to the PC, where the **ViGEmBus** kernel driver creates a virtual XInput device. Games see a **real Xbox 360 controller** — no process injection, no DLL replacement, no API hooking.

> Quick start: see [`QUICKSTART.md`](QUICKSTART.md) (Chinese; the steps below cover the same flow in English).

## Features

| Feature | Description |
| --- | --- |
| Real Xbox 360 pad | Kernel-level virtual device; XInput games recognize it natively |
| **Local multi-player (P1–P4)** | Several phones on one PC: each gets its own pad and player number; rumble is routed to the right phone |
| **Gyro aiming** | Turn the phone = turn the camera (fused orientation, no drift); adds on top of the right stick; invert yaw/pitch toggles |
| **Two independent sensitivities** | "Gyro camera sensitivity" and "right-stick camera sensitivity" are separate 0–100 settings; changing one never affects the other |
| Rumble feedback | `XInputSetState` motor data is streamed back to the phone |
| **☰ Menu key** | Dedicated round Menu button (same as `START`); games that ask you to press Menu to join work out of the box. Position/size editable like any other control |
| **Stick click (LS / RS)** | Three selectable trigger modes in Settings: (1) tap the stick centre, (2) double-tap anywhere inside the stick, (3) hold whenever the stick is operated. Pushing the stick never triggers a click |
| Per-control editing | Move / resize / set opacity of every control, saved automatically |
| Dark glass UI | Obsidian layered-glass look, high-contrast colored ABXY letters, press-sink animation |
| Auto reconnect | The app connects to the last server on launch |
| **Collapsible top bar** | The connect bar collapses to the original "手柄" text at its original spot; tap it to expand rightwards (300 ms ease), tap again to collapse. Collapsed, it occupies one word and never intercepts game touches |
| One-click launcher | PC script checks dependencies, cleans stale instances, frees the port, starts the receiver |
| Companion test games | 4 PC games written to test the pad (platformer / twin-stick arena / camera test / 3D walk) |

## How it is recognized (the core idea)

Games read pads through two paths: **XInput** (`xinput1_4.dll`) and the older **DirectInput**. A user-mode program cannot fabricate a USB device — that is why ordinary "key mapping" tools can only fake keyboard/mouse. **ViGEmBus** is a signed KMDF kernel bus driver: user mode asks it to plug an Xbox 360 controller into a virtual bus, the Windows PnP manager enumerates it, and an XInput slot is allocated. At the kernel-object level the device is indistinguishable from hardware — there is no technical way for a game to tell it apart. Games that are "not recognized" are almost always: driver missing (or PC not rebooted after install), receiver not started, or a DirectInput-only game.

> ViGEmBus was archived on 2023-11-02 at version **1.22.0**. It is a mature, stable driver and works fine on Windows 10/11 including 24H2.

## Quick start

1. **Install ViGEmBus** (once): download from the official releases page <https://github.com/nefarius/ViGEmBus/releases>, run as administrator, then **reboot**.
2. **Start the receiver**: double-click `一键启动.bat` (or `START-RECEIVER.bat` — same script, English messages). It checks Python + the `vgamepad` library (auto-installs if missing), the driver, kills stale instances and starts listening on UDP 26760. Allow it through the firewall for private networks when prompted.
3. **Build the Android app** (first time only): open the `android/` folder in Android Studio, let Gradle sync, then *Build APK(s)*. Install `app/build/outputs/apk/debug/app-debug.apk` on the phone.
4. **Connect**: phone and PC on the same Wi-Fi. Tap the **"手柄" text** at the top-left to expand the connect bar, enter the PC's IPv4 address and port `26760`, tap connect. After that the app **auto-connects** on launch. Tap the text again to collapse. The status bar shows `connected · 125 pps · P1`.
5. **Verify**: press `Win+R`, run `joy.cpl` — "Xbox 360 Controller for Windows" must be listed. Test sticks/buttons in its Properties panel.
6. **Start the game** after the receiver is running (games enumerate pads at startup). For Steam games, set *Steam Input* to *Disabled* for that title.

**Multi-player**: every phone that connects gets the next free slot (P1–P4, XInput limit is 4). Reconnects keep the same number; the receiver console shows per-player stats like `[1P] 125pps 0.8%`.

## Protocol summary

Input packets are **absolute state**, 20 bytes, little-endian, at 125 Hz (≈2.5 KB/s):

| Offset | Size | Field | Notes |
| --- | --- | --- | --- |
| 0 | 4 | magic | ASCII `VGP1` |
| 4 | 1 | version | `1` |
| 5 | 2 | buttons | uint16 bitmask (bit0=A, 1=B, 2=X, 3=Y, 4=LB, 5=RB, 6=BACK, 7=START, 8=L3, 9=R3, 10–13=D-pad, 14=GUIDE) — custom compact layout, NOT `XINPUT_GAMEPAD.wButtons`; the receiver maps each bit |
| 7 | 1 | sequence | 0–255, packet-loss stats |
| 8 | 2 | LX | int16 |
| 10 | 2 | LY | int16 (**up is positive**) |
| 12 | 2 | RX | int16 |
| 14 | 2 | RY | int16 |
| 16 | 1 | LT | uint8 0–255 |
| 17 | 1 | RT | uint8 0–255 |
| 18 | 2 | reserved | 0 |

Reverse channel (8 bytes, sent to the phone's fixed IPv4 port **26761**): `VGPR` rumble (large/small motor 0–255) and `VGPP` player number (1–4).

Pitfalls the implementation handles for you: screen Y axis is inverted vs. XInput (`LY` up is positive), triggers are unsigned (released = 0, not 0x80), and the D-pad is four buttons, not a POV hat. The touch layer applies a 6% radial deadzone with re-normalization so full deflection still reaches 32767.

The receiver drains the UDP queue and applies only the **latest** packet per phone, so latency stays constant — no "drift" from queued stale packets.

## Tuning

| What | Where |
| --- | --- |
| Gyro on/off, sensitivity, invert pitch/yaw | App → gear → "right stick" section (instant) |
| Control position / size / opacity | App → gear → layout editor: tap a control, drag to move, slider toggles size/opacity |
| Receiver port | `python server.py 26761` (change it in the app too) |
| Gyro rates | `GyroAim.kt`: `MAX_YAW_RATE / MAX_PITCH_RATE` |

Receiver console legend: `[监听]` listening · `[连接]` a phone connected · `[1P] 125pps 0.8%` per-player rate/loss · `[超时]` phone stopped sending, inputs neutralized.

## Companion test games (`game/`)

All read XInput directly via ctypes (same API path as real games; pygame only renders). Each has a `--selftest` headless check.

| Game | Tests |
| --- | --- |
| `xbox_platformer.py` | Analog stick speed + charged jumps, A/X/B/Y, trigger depth, input HUD, 3 levels |
| `twin_stick.py` | Left stick move + right stick aim/fire **simultaneously**, waves, bombs |
| `look_test.py` | Frame-direct camera (no smoothing), compass strip, portable `LookCamera` class |
| `fps_walk.py` | FPS-style dual-stick walking + looking, sprint on RT, objective rings, minimap |

## Troubleshooting (top items)

| Symptom | Fix |
| --- | --- |
| "Cannot load ViGEmBus" on start | Install 1.22.0 and **reboot** |
| No controller in `joy.cpl` | Check the receiver console; whitelist it in antivirus |
| Phone "connected" but PC logs nothing | Firewall / AP isolation — allow UDP 26760 inbound |
| Works in `joy.cpl`, not in game | DirectInput-only game, or in-game input set to keyboard |
| Gyro axis inverted | Toggle the invert pitch / invert yaw switches in the app |
| Double inputs | Close DS4Windows / x360ce / vJoy |
| Laggy | Use 5 GHz Wi-Fi or a phone hotspot |

Full tables: see the [Chinese README](README.md) (§12) and `QUICKSTART.md`.

## Extending

1. **USB transport**: `adb forward tcp:26760 tcp:26760` + TCP with the same protocol.
2. **LAN discovery**: receiver heartbeat broadcast so the app can auto-fill the IP.
3. **DirectInput backend** (vJoy) for legacy games.
4. **Standalone EXE**: `pyinstaller --onefile --collect-all vgamepad server.py` produces a Python-free receiver (this is how the author distributes it).

## License

MIT — see [LICENSE](LICENSE). The virtual-device layer depends on [ViGEmBus](https://github.com/nefarius/ViGEmBus) (BSD-3-Clause) and [Nefarius.ViGEm.Client](https://www.nuget.org/packages/Nefarius.ViGEm.Client) (MIT); keep their license notices when redistributing.
