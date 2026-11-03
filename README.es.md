# Mando virtual de Xbox 360 (Android → Windows)

**Idioma / Language**: [简体中文](README.md) · [English](README.en.md) · [日本語](README.ja.md) · [Español](README.es.md) · [Русский](README.ru.md)

![License](https://img.shields.io/badge/license-MIT-green) ![Platform](https://img.shields.io/badge/platform-Windows%20%2B%20Android-blue) ![Protocol](https://img.shields.io/badge/transport-UDP%20125Hz-orange)

Usa un teléfono Android como mando virtual de Xbox 360. Los sticks, la cruceta, ABXY, los botones traseros y los gatillos se envían por UDP en la red local hacia el PC, donde el driver de kernel **ViGEmBus** crea un dispositivo XInput virtual. Para los juegos es un **mando real de Xbox 360** — sin inyección de procesos, sin sustitución de DLL, sin hooks de API.

> Inicio rápido: [`QUICKSTART.md`](QUICKSTART.md) (en chino). Los mismos pasos se resumen abajo.

## Funciones

| Función | Descripción |
| --- | --- |
| Mando real de Xbox 360 | Dispositivo virtual a nivel de kernel; los juegos XInput lo reconocen de forma nativa |
| **Multijugador local (P1–P4)** | Varios teléfonos en un PC: cada uno recibe su propio mando y número de jugador; la vibración vuelve al teléfono correcto |
| **Puntería por giroscopio** | Girar el teléfono = girar la cámara (orientación fusionada, sin deriva); se **suma** al stick derecho; sensibilidad 40–300%; interruptores de inversión de pitch/yaw |
| Vibración | Los motores de `XInputSetState` se devuelven al teléfono |
| Edición por control | Mover / cambiar tamaño / opacidad de cada control, con guardado automático |
| Interfaz de vidrio oscuro | Estética de vidrio obsidiana en capas, letras ABXY de colores de alto contraste, animación de pulsación |
| Reconexión automática | La app se conecta sola al último servidor al abrirse |
| **Cajón de conexión flotante** | La barra superior está plegada por defecto; solo queda una pestaña «手柄» en el borde izquierdo (punto verde si hay conexión). Se abre/cierra con una animación de 300 ms; plegada no intercepta ningún toque del juego |
| Arranque con un clic | El script del PC comprueba dependencias, limpia instancias previas, libera el puerto y arranca el receptor |
| Juegos de prueba incluidos | 4 juegos de PC para probar el mando (plataformas / arena dual-stick / cámara / paseo 3D) |

## Por qué se reconoce siempre

Los juegos leen mandos por **XInput** (`xinput1_4.dll`) o por el antiguo **DirectInput**. Un programa de usuario no puede inventar un dispositivo USB: por eso las herramientas comunes de mapeo solo imitan teclado/ratón. **ViGEmBus** es un driver de kernel (KMDF) con firma digital: le pide que conecte un mando de Xbox 360 a un bus virtual, el administrador PnP de Windows lo enumera y se asigna un slot XInput. A nivel de objetos del kernel es idéntico al hardware real, así que ningún juego puede distinguirlo. Cuando "no se reconoce", casi siempre es: driver sin instalar (o sin reiniciar tras instalarlo), receptor sin arrancar, o un juego solo-DirectInput.

> ViGEmBus se archivó el 2023-11-02 con la versión **1.22.0**. Es un driver maduro y estable; funciona bien en Windows 10/11 incluido 24H2.

## Inicio rápido

1. **Instalar ViGEmBus** (una vez): descárgalo de <https://github.com/nefarius/ViGEmBus/releases>, ejecútalo como administrador y **reinicia** el PC.
2. **Arrancar el receptor**: doble clic en `一键启动.bat` (o `START-RECEIVER.bat`, mismo contenido). Comprueba Python y la librería `vgamepad` (la instala si falta), el driver, mata instancias previas y escucha en el UDP 26760. Permite el acceso en el firewall para redes privadas.
3. **Compilar la app Android** (solo la primera vez): abre la carpeta `android/` en Android Studio, espera la sincronización de Gradle y pulsa *Build APK(s)*. Instala `app/build/outputs/apk/debug/app-debug.apk` en el teléfono.
4. **Conectar**: teléfono y PC en la misma Wi-Fi. Toca la **pestaña «手柄»** del borde izquierdo para abrir el cajón de conexión, introduce la IPv4 del PC y el puerto `26760`, pulsa conectar. Después la app **se reconecta sola**. Toca la pestaña otra vez (o fuera del panel) para plegarlo; el punto verde indica conexión activa. La barra de estado muestra `connected · 125 pps · P1`.
5. **Verificar**: `Win+R` → `joy.cpl` debe mostrar "Xbox 360 Controller for Windows". Prueba sticks y botones en Propiedades.
6. **Arrancar el juego** después del receptor (los juegos enumeran mandos al iniciar). En juegos de Steam, desactiva *Steam Input* para ese título.

**Multijugador**: cada teléfono conectado toma el siguiente slot libre (P1–P4; XInput admite 4). Al reconectar conserva el número; la consola muestra estadísticas por jugador tipo `[1P] 125pps 0.8%`.

## Resumen del protocolo

Los paquetes de entrada son **estado absoluto**, de 20 bytes, little-endian, a 125 Hz (≈2,5 KB/s):

| Offset | Tamaño | Campo | Notas |
| --- | --- | --- | --- |
| 0 | 4 | magic | ASCII `VGP1` |
| 4 | 1 | version | `1` |
| 5 | 2 | buttons | máscara uint16: bit0=A, 1=B, 2=X, 3=Y, 4=LB, 5=RB, 6=BACK, 7=START, 8=L3, 9=R3, 10–13=cruceta, 14=GUIDE (coincide con `XINPUT_GAMEPAD.wButtons`) |
| 7 | 1 | sequence | 0–255, estadística de pérdida |
| 8–15 | 8 | LX/LY/RX/RY | int16 ×4 (**LY positivo hacia arriba**) |
| 16–17 | 2 | LT/RT | uint8 0–255 |
| 18 | 2 | reserved | 0 |

Canal de retorno (8 bytes, al puerto IPv4 fijo **26761** del teléfono): `VGPR` vibración (motores 0–255) y `VGPP` número de jugador (1–4).

Detalles ya resueltos: el eje Y de pantalla está invertido respecto a XInput, los gatillos son sin signo (sueltos = 0) y la cruceta son 4 botones, no un POV hat. La capa táctil aplica una zona muerta radial del 6% re-normalizada para llegar a 32767 a tope. El receptor vacía la cola UDP y aplica solo el **último** paquete de cada teléfono: la latencia se mantiene constante, sin "deriva" por paquetes viejos.

## Ajustes

| Qué | Dónde |
| --- | --- |
| Girosocopio on/off, sensibilidad, invertir pitch/yaw | App → engranaje → sección del stick derecho (instantáneo) |
| Posición / tamaño / opacidad de controles | App → engranaje → editor de layout: toca un control, arrastra; el deslizador alterna tamaño/opacidad |
| Puerto del receptor | `python server.py 26761` (cámbialo también en la app) |
| Velocidades del giroscopio | `GyroAim.kt`: `MAX_YAW_RATE / MAX_PITCH_RATE` |

Leyenda de la consola: `[监听]` escuchando · `[连接]` teléfono conectado · `[1P] 125pps 0.8%` tasa/pérdida por jugador · `[超时]` sin paquetes, entradas neutralizadas.

## Juegos de prueba (`game/`)

Todos leen XInput directamente con ctypes (la misma ruta de API que un juego real; pygame solo dibuja). Cada uno admite `--selftest`.

| Juego | Qué prueba |
| --- | --- |
| `xbox_platformer.py` | Velocidad analógica + salto cargado, A/X/B/Y, recorrido de gatillos, HUD de entrada, 3 niveles |
| `twin_stick.py` | Mover con stick izquierdo + apuntar/disparar con el derecho **a la vez**, oleadas, bombas |
| `look_test.py` | Cámara directa por frame (sin suavizado), brújula, clase `LookCamera` portable |
| `fps_walk.py` | Doble stick estilo FPS (andar + mirar), sprint con RT, anillos objetivo, minimapa |

## Solución de problemas (lo más común)

| Síntoma | Solución |
| --- | --- |
| "No se puede cargar ViGEmBus" | Reinstala 1.22.0 y **reinicia** |
| No aparece en `joy.cpl` | Revisa la consola del receptor; añade excepción en el antivirus |
| El teléfono dice "conectado" pero el PC no registra nada | Firewall / aislamiento AP: permite UDP 26760 entrante |
| Funciona en `joy.cpl` pero no en el juego | Juego solo-DirectInput o entrada mal configurada |
| Un eje del giroscopio invertido | Usa los interruptores de inversión en la app |
| Pulsaciones dobles | Cierra DS4Windows / x360ce / vJoy |
| Retrasos | Wi-Fi de 5 GHz o hotspot del teléfono |

Tablas completas: [README en chino](README.md) (§12) y `QUICKSTART.md`.

## Ampliaciones

1. **Transporte USB**: `adb forward tcp:26760 tcp:26760` + TCP con el mismo protocolo.
2. **Descubrimiento en LAN**: latido del receptor para autocompletar la IP en la app.
3. **Backend DirectInput** (vJoy) para juegos antiguos.
4. **EXE independiente**: `pyinstaller --onefile --collect-all vgamepad server.py` genera un receptor sin Python.

## Licencia

MIT — ver [LICENSE](LICENSE). La capa de dispositivo virtual depende de [ViGEmBus](https://github.com/nefarius/ViGEmBus) (BSD-3-Clause) y [Nefarius.ViGEm.Client](https://www.nuget.org/packages/Nefarius.ViGEm.Client) (MIT); conserva sus avisos de licencia al redistribuir.
