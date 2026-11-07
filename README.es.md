# 🎮 Android Virtual Gamepad

**Idioma / Language**: [简体中文](README.md) · [English](README.en.md) · [日本語](README.ja.md) · [Español](README.es.md) · [Русский](README.ru.md)

## Convierte tu Android en un mando inalámbrico real de Xbox 360

Sin comprar mando: conecta el móvil Android al PC con Windows por Wi-Fi y los juegos lo reconocen como un mando estándar de Xbox 360.

**Funciones principales**

- 🎮 **Emulación nativa**: compatible con juegos XInput de Windows.
- 👥 **Multijugador local**: hasta 4 móviles conectados a la vez.
- 🌀 **Aim por giroscopio**: usa el movimiento del móvil para la cámara.
- 📳 **Vibración de vuelta**: la vibración del juego vuelve al móvil.
- 🎨 **Personalización libre**: posición, tamaño, opacidad y disposición de cada control.
- ⚡ **Baja latencia**: transmisión en tiempo real por UDP Wi-Fi.

**Gratis · código abierto · sin mando físico**

> «El móvil pone la entrada, el PC pone el juego. Cada móvil ocioso se convierte en mando.»

---

## Para quién es

| Tu situación | Lo que consigues |
| --- | --- |
| Quieres jugar títulos con mando en PC y no tienes mando | El móvil hace de mando; `joy.cpl` y los juegos muestran *Xbox 360 Controller for Windows* |
| Quieres 2–4 jugadores locales y faltan mandos | Hasta **cuatro móviles en un PC** (1P–4P), cada uno un mando independiente, vibración para cada jugador |
| Apuntar con el stick se queda corto en shooters | **Aim por giroscopio**: gira el móvil y gira la cámara, se suma al stick derecho, sin deriva acumulada |
| La disposición de otras apps no te encaja | 19 controles con posición / tamaño / opacidad individuales, cinco temas y modo claro / oscuro / sistema |
| Quieres integrarlo en tus propios scripts | Protocolo UDP de 20 bytes con vectores de prueba compartidos, licencia MIT |

---

## En qué se diferencia de otras soluciones

| Enfoque | El juego ve un mando real | Qué instala el PC | Sticks analógicos | Vibración de vuelta |
| --- | --- | --- | --- | --- |
| Mapeadores de teclado/ratón | ✗ solo nivel teclado | nada | ✗ | ✗ |
| HID por Bluetooth del móvil | según el juego; suele requerir Steam Input | nada | ✓ | normalmente ✗ |
| **Este proyecto** | **✓ XInput nativo** | driver offline de 876 KB, una sola vez | ✓ | ✓ |

El coste es explícito: hace falta un **driver de kernel firmado** (ViGEmBus). Es un límite de Windows
—el modo usuario no puede declarar «hay un mando conectado»—. Todo lo que un juego acepta de verdad
tiene un driver detrás. Una vez instalado, cualquier juego XInput lo detecta **de forma nativa**.

---

## Puntos destacados

| Capacidad | Detalle |
| --- | --- |
| Dos sensibilidades independientes | «Cámara por giroscopio» y «cámara por stick derecho» son ajustes 0–100 separados que no se afectan |
| Conjunto completo de botones | ABXY, LB/RB, LT/RT (0–255 analógicos), cruceta, `☰` Menú, `X` Home, L3/R3 — 19 controles |
| Tres modos de pulsación del stick | Tocar el centro / doble toque dentro del stick / mantener al operarlo; empujar el stick nunca pulsa por error |
| Interfaz Material 3 + neumorfismo | Cinco paletas de baja saturación con transición de fondo de 480 ms, claro / oscuro / sistema, animación de pulsación |
| Barra superior plegable | Se recoge en una píldora «手柄»; al expandirse difumina el mando en vivo y muestra un panel de cristal esmerilado |
| Conexión autorreparable | Cortes de Wi-Fi y reinicios del receptor se recuperan con retroceso — **~1 s** medido tras reiniciar el PC |
| Listo para usar | El paquete portable incluye receptor, APK y driver offline; sin necesidad de Python |

---

## Tres pasos para jugar

**1 · Descomprime el paquete portable**

```
VirtualGamepadReceiver-Portable/
├─ VirtualGamepadReceiver.exe     Receptor de PC (portable, doble clic)
├─ 手机端/VirtualGamepad-v1.1.0-debug.apk    App del móvil
├─ 可选驱动/ViGEmBusSetup_x64.msi            Driver offline de 876 KB
└─ 使用说明.txt
```

**2 · Instala el driver una vez**

Clic derecho en `可选驱动/ViGEmBusSetup_x64.msi` → ejecutar como administrador → **reinicia**.
Es un driver de kernel, así que solo se carga tras reiniciar. No hay que repetirlo.

**3 · Conecta el móvil**

Instala el APK de `手机端/`, únete a la misma Wi-Fi que el PC, toca la píldora «手柄» arriba a la
izquierda, introduce la IPv4 del PC y el puerto `26760`, y conecta. Ver `已连接 · 250 pps · 1P`
significa que el enlace está activo.

> La lista paso a paso está en **[QUICKSTART.md](QUICKSTART.md)** (chino); compilar desde código,
> el receptor de referencia en C# y el arranque manual están en **[docs/TECHNICAL.md](docs/TECHNICAL.md)**.
> Permite UDP 26760 entrante en **redes privadas** cuando aparezca el aviso del firewall de Windows.

---

## Por qué el juego lo trata como un mando real

Windows expone dos rutas distintas: **XInput** (`xinput1_4.dll`, usada por casi todos los juegos con
mando desde 2006) y la más antigua **DirectInput**. Un programa en modo usuario no puede fabricar un
dispositivo USB, y por eso los mapeadores de teclas se quedan en ese nivel.

**ViGEmBus** es un driver de bus KMDF firmado: el modo usuario le pide por IOCTL que conecte un mando
de Xbox 360 en un bus virtual, Windows PnP lo enumera y asigna una ranura XInput. El dispositivo
aparece en el Administrador de dispositivos, `XInputGetState(0, …)` devuelve éxito y la estructura que
recibe el juego es idéntica a la del hardware.

**A nivel de objeto del kernel no existe forma de distinguirlo.** Los «no me lo reconoce» suelen ser
driver ausente, PC sin reiniciar, receptor no iniciado o un juego solo DirectInput.

> ViGEmBus se archivó el 2023-11-02 en la versión 1.22.0. Es un driver maduro de propósito único y
> funciona en Windows 10/11 incluido 24H2; **la versión 1.17.333 incluida en este paquete también sirve**,
> no hace falta perseguir la última.

---

## Capacidad y límites

| Elemento | Medido / especificación |
| --- | --- |
| Transporte | UDP unicast por Wi-Fi, 20 bytes fijos a **250 Hz**, ~5 KB/s |
| Latencia | 10–20 ms típicos en LAN; ~35–50 ms contando muestreo táctil y un frame |
| Móviles simultáneos | hasta 4 (límite de ranuras XInput) |
| Cubre | todo lo que use XInput — Steam, Epic, UE, Unity, RE Engine, RPCS3, Yuzu, RetroArch… |
| No cubre | juegos antiguos solo DirectInput, anticheats que limitan dispositivos virtuales, equipos no Windows |
| Verificación | 22/22 comprobaciones en la capa XInput, 13 botones con su bit exacto, 250 pps sin huecos de secuencia |

Seguridad y cumplimiento: instala el driver solo desde la fuente oficial o este paquete; evita los
«todo en uno» de terceros. Los dispositivos de entrada virtual son válidos en un jugador y cooperativo,
pero **algunos anticheats competitivos los restringen**; revisa los términos del juego antes de usarlo.
Todo el tráfico se queda en tu equipo y tu LAN, sin servidores de terceros.

---

## Documentación

| Qué quieres saber | Dónde |
| --- | --- |
| Pasos y bloqueos habituales | [QUICKSTART.md](QUICKSTART.md) |
| Interioridades, formato del paquete, mapeo de botones, ajuste, diagnóstico | [docs/TECHNICAL.md](docs/TECHNICAL.md) |
| Especificación del protocolo y vectores de prueba | [docs/PROTOCOL.md](docs/PROTOCOL.md) · [VGP1](protocol/test_vectors.json) / [VGP2](protocol/test_vectors_v2.json) |
| Registro de aceptación extremo a extremo (22 puntos) | [docs/FUNCTIONAL-ACCEPTANCE-2026-11-06.md](docs/FUNCTIONAL-ACCEPTANCE-2026-11-06.md) |
| Por qué el driver es inevitable | [docs/DRIVER-ANALYSIS-2026-11-06.md](docs/DRIVER-ANALYSIS-2026-11-06.md) |
| Implementación de la vibración (y su interbloqueo de handle) | [docs/RUMBLE-2026-11-06.md](docs/RUMBLE-2026-11-06.md) |
| Causa raíz del «reconectando» sin fin | [docs/CONNECTION-FIX-2026-11-06.md](docs/CONNECTION-FIX-2026-11-06.md) |
| Auditorías e informes de pruebas | [docs/AUDIT.md](docs/AUDIT.md) · [docs/CODE-AUDIT-2026-11-04.md](docs/CODE-AUDIT-2026-11-04.md) · [docs/TEST-REPORT.md](docs/TEST-REPORT.md) |

En `game/` hay cuatro juegos de prueba que leen XInput directamente y ejercitan recorrido de sticks,
profundidad de gatillos y ambos sticks a la vez.

---

## Licencia

MIT — ver [LICENSE](LICENSE). Los componentes de terceros mantienen sus términos:
[ViGEmBus](https://github.com/nefarius/ViGEmBus) (BSD-3-Clause) ·
[Nefarius.ViGEm.Client](https://www.nuget.org/packages/Nefarius.ViGEm.Client) (MIT) ·
[vgamepad](https://pypi.org/project/vgamepad/) (MIT) ·
[pygame](https://www.pygame.org/) (LGPL) ·
Material Components for Android / Kotlin / AndroidX (Apache-2.0)
