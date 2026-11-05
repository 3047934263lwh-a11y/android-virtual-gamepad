# 🎮 Android Virtual Gamepad

**Language / 语言**: [简体中文](README.md) · [English](README.en.md) · [日本語](README.ja.md) · [Español](README.es.md) · [Русский](README.ru.md)

## Turn your Android phone into a real Xbox 360 wireless controller

No controller to buy — connect your Android phone to a Windows PC over Wi-Fi and games recognise it as a standard Xbox 360 controller.

**Core features**

- 🎮 **Native controller emulation**: works with Windows XInput games.
- 👥 **Local multiplayer**: up to 4 phones connected at the same time.
- 🌀 **Gyro aiming**: use the phone's motion to control the in-game camera.
- 📳 **Rumble feedback**: in-game vibration is sent back to the phone.
- 🎨 **Full customisation**: move, resize, fade and re-layout every control.
- ⚡ **Low-latency link**: real-time transport over Wi-Fi UDP.

**Free · open source · no controller required**

> «The phone handles the input, the PC runs the game. Every idle phone becomes a controller.»

---

## Who this is for

| Your situation | What you get |
| --- | --- |
| You want to play gamepad titles on PC but own no controller | Your phone becomes the pad; both `joy.cpl` and games report *Xbox 360 Controller for Windows* |
| You need 2–4 local players but only have one controller | Up to **four phones on one PC** (P1–P4), each an independent pad, rumble routed per player |
| Stick aiming feels too slow in shooters / open worlds | **Gyro aiming**: turn the phone to turn the camera, added on top of the right stick, no accumulated drift |
| Existing gamepad apps feel awkward to hold | 19 controls with per-control position / size / opacity, five theme colours, light / dark / system |
| You want to build on it | 20-byte fixed UDP protocol with shared test vectors, MIT licensed, documented |

**Not a fit**: rhythm games and frame-perfect fighters (Wi-Fi latency sets the floor), DirectInput-only
legacy titles, or macOS / Linux hosts.

---

## How it differs from other "phone as gamepad" approaches

| Approach | Games see a real pad | What PC must install | Analogue sticks | Rumble back |
| --- | --- | --- | --- | --- |
| Keyboard/mouse mappers | ✗ keyboard-level only | nothing | ✗ | ✗ |
| Phone Bluetooth HID | depends; usually needs Steam Input | nothing | ✓ | usually ✗ |
| **This project** | **✓ native XInput** | one-time 876 KB offline driver | ✓ | ✓ |

The trade-off is explicit: a **signed kernel driver** (ViGEmBus) is required. That is a hard Windows
boundary — user mode cannot declare "a controller is plugged in". Anything games truly accept has a
driver behind it. Once installed, every XInput title picks it up **natively**.

---

## Highlights

| Capability | Detail |
| --- | --- |
| Real device, not a mapper | Kernel-level virtual bus device; XInput enumeration is indistinguishable from hardware, 16-bit sticks and 8-bit triggers |
| Local multiplayer P1–P4 | Slots assigned by source IP, numbering survives reconnects, rumble delivered to the right phone |
| Gyro aiming | Fused orientation (`Game Rotation Vector`, 50 Hz), adds to the right stick; invert pitch / yaw independently |
| Two independent sensitivities | "Gyro camera" and "right-stick camera" are separate 0–100 settings that never affect each other |
| Rumble feedback | Game `XInputSetState` → driver callback → phone vibration, with strength / duration / minimum-interval tuning |
| Complete button set | ABXY, LB/RB, LT/RT (0–255 analogue), D-pad, `☰` Menu, `X` Home, L3/R3 — 19 controls in total |
| Three stick-click modes | Tap the centre / double-tap inside the stick / hold while operating; pushing the stick never mis-clicks |
| Per-control layout editing | Position / size (50%–500%) / opacity / visibility, saved automatically; hidden controls neither draw nor receive touches |
| Material 3 + neumorphic UI | Five low-saturation palettes with a 480 ms background cross-fade, light / dark / system, press-sink animation |
| Collapsible top bar | Collapses to a 「手柄」 pill; expanding blurs the live pad and shows a frosted glass panel without stealing game touches |
| Self-healing connection | Wi-Fi drops and receiver restarts reconnect with backoff — measured **~1 s recovery** after a PC restart |
| Works out of the box | The portable bundle ships the receiver, the phone APK and the offline driver; no Python needed |

---

## Three steps to play

**1 · Unpack the portable bundle**

```
VirtualGamepadReceiver-Portable/
├─ VirtualGamepadReceiver.exe     PC receiver (portable, just double-click)
├─ 手机端/VirtualGamepad-v1.1.0-debug.apk    Phone app
├─ 可选驱动/ViGEmBusSetup_x64.msi            876 KB offline driver
└─ 使用说明.txt
```

**2 · Install the driver once**

Right-click `可选驱动/ViGEmBusSetup_x64.msi` → run as administrator → **reboot**.
It is a kernel driver, so it only loads after a restart. This is a one-time step.

**3 · Connect the phone**

Install the APK from `手机端/`, join the same Wi-Fi as the PC, tap the 「手柄」 pill at the top-left to
expand the connect bar, enter the PC's IPv4 address and port `26760`, then connect. Seeing
`已连接 · 250 pps · 1P` means the link is live.

> A click-by-click checklist is in **[QUICKSTART.md](QUICKSTART.md)** (Chinese); building from source,
> the C# reference receiver and manual launch are documented in **[docs/TECHNICAL.md](docs/TECHNICAL.md)**.
> Allow inbound UDP 26760 on **private networks** when the Windows Firewall prompt appears.

---

## Why games treat it as a real controller

Windows exposes two unrelated paths for gamepads: **XInput** (`xinput1_4.dll`, used by almost every
pad-enabled title since 2006) and the older **DirectInput**. A user-mode program cannot fabricate a
USB device, which is why plain key mappers stop at keyboard level.

**ViGEmBus** is a signed KMDF kernel bus driver: user mode asks it via IOCTL to plug an Xbox 360
controller into a virtual bus, Windows PnP enumerates it and allocates an XInput slot. The device then
appears in Device Manager, `XInputGetState(0, …)` succeeds, and the state structure the game receives
is identical to hardware.

**At kernel-object level there is no way for a game to tell the difference.** Reports of "not
recognised" are almost always a missing driver, a PC that was never rebooted, a receiver that is not
running, or a DirectInput-only title.

> ViGEmBus was archived on 2023-11-02 at version 1.22.0. It is a mature single-purpose driver and works
> on Windows 10/11 including 24H2; **the 1.17.333 build shipped in this bundle is equally usable**, so
> there is no need to chase the newest release.

---

## Capability and limits

| Item | Measured / spec |
| --- | --- |
| Transport | Wi-Fi UDP unicast, 20-byte fixed packets at **250 Hz**, ~5 KB/s |
| Latency | typically 10–20 ms on LAN; ~35–50 ms including touch sampling and one rendered frame |
| Concurrent phones | up to 4 (XInput slot limit) |
| Covered | anything on XInput — Steam, Epic, UE, Unity, RE Engine, RPCS3, Yuzu, RetroArch … |
| Not covered | DirectInput-only legacy games, some anti-cheat policies against virtual devices, non-Windows hosts |
| Verification | 22/22 XInput-layer checks, 13 buttons hit their exact single protocol bit, 250 pps with zero sequence gaps |

Safety and compliance: install the driver only from the official source or this bundle — avoid
third-party "all-in-one" packs. Virtual input devices are fine in single-player and co-op titles, but
**some competitive anti-cheat systems restrict them**; check the game's terms first. All traffic stays
on your machine and LAN, with no third-party servers involved.

---

## Documentation map

| Question | Where |
| --- | --- |
| Step-by-step setup and common blockers | [QUICKSTART.md](QUICKSTART.md) |
| Internals, packet layout, button mapping, tuning, troubleshooting | [docs/TECHNICAL.md](docs/TECHNICAL.md) |
| Protocol spec and test vectors | [docs/PROTOCOL.md](docs/PROTOCOL.md) · [VGP1](protocol/test_vectors.json) / [VGP2](protocol/test_vectors_v2.json) |
| End-to-end acceptance record (22-item matrix) | [docs/FUNCTIONAL-ACCEPTANCE-2026-11-06.md](docs/FUNCTIONAL-ACCEPTANCE-2026-11-06.md) |
| Why a driver is unavoidable | [docs/DRIVER-ANALYSIS-2026-11-06.md](docs/DRIVER-ANALYSIS-2026-11-06.md) |
| Rumble implementation (and its handle-deadlock pitfall) | [docs/RUMBLE-2026-11-06.md](docs/RUMBLE-2026-11-06.md) |
| Root cause of the endless "reconnecting" state | [docs/CONNECTION-FIX-2026-11-06.md](docs/CONNECTION-FIX-2026-11-06.md) |
| Audits and test reports | [docs/AUDIT.md](docs/AUDIT.md) · [docs/CODE-AUDIT-2026-11-04.md](docs/CODE-AUDIT-2026-11-04.md) · [docs/TEST-REPORT.md](docs/TEST-REPORT.md) |

Four companion test games live in `game/`; they read XInput directly and exercise stick travel,
trigger depth and simultaneous dual-stick input.

---

## License

MIT — see [LICENSE](LICENSE). Third-party components keep their own terms:
[ViGEmBus](https://github.com/nefarius/ViGEmBus) (BSD-3-Clause) ·
[Nefarius.ViGEm.Client](https://www.nuget.org/packages/Nefarius.ViGEm.Client) (MIT) ·
[vgamepad](https://pypi.org/project/vgamepad/) (MIT) ·
[pygame](https://www.pygame.org/) (LGPL) ·
Material Components for Android / Kotlin / AndroidX (Apache-2.0)
