using System;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;

namespace VirtualGamepadServer;

/// <summary>
/// PC 接收端：把手机发来的 UDP 报文转成 ViGEmBus 上的虚拟 Xbox 360 控制器输入。
///
/// 关键点：ViGEmBus 是内核态总线驱动，它向 Windows PnP 子系统注册的是"真实插入的
/// Xbox 360 控制器"。因此游戏通过 XInput 查询到的设备与实体手柄在系统层面没有任何差异，
/// 不需要代理 DLL、不需要 API Hook，也不会被识别成"映射工具"。
/// </summary>
internal static class Program
{
    private const int DefaultPort = 26760;
    private const int IdleResetMs = 1200;

    private static volatile bool _running = true;

    /// <summary>最近一次收到报文的手机地址，供震动回传使用（驱动回调线程读取）。</summary>
    private static volatile IPEndPoint? _lastClient;

    private static int Main(string[] args)
    {
        TrySetUtf8();
        PrintBanner();

        if (!TryParsePort(args, out int port))
        {
            return 2;
        }

        ViGEmClient client;
        try
        {
            client = new ViGEmClient();
        }
        catch (Exception ex)
        {
            Fail("无法加载 ViGEmBus 驱动", ex);
            Console.WriteLine();
            Console.WriteLine("  请依次确认：");
            Console.WriteLine("  1. 已安装 ViGEmBus 1.22.0（ViGEmBus_1.22.0_x64_x86_arm64.exe）");
            Console.WriteLine("  2. 安装完成后已重启计算机（驱动服务需要重启才能加载）");
            Console.WriteLine("  3. 设备管理器 → 系统设备 中存在 \"Virtual Gamepad Emulation Bus\"");
            Console.WriteLine("  下载: https://github.com/nefarius/ViGEmBus/releases");
            return 1;
        }

        using (client)
        {
            IXbox360Controller pad;
            try
            {
                pad = client.CreateXbox360Controller();
                // 关闭自动提交：每收到一包手动提交一次，输入与报文严格 1:1，延迟可控
                pad.AutoSubmitReport = false;
                pad.Connect();
            }
            catch (Exception ex)
            {
                Fail("创建虚拟 Xbox 360 控制器失败", ex);
                return 1;
            }

            Console.WriteLine("[就绪] 虚拟 Xbox 360 控制器已接入，XInput 设备已枚举");
            Console.WriteLine("[提示] 现在启动游戏，控制器列表应出现 \"Xbox 360 Controller\"");
            Console.WriteLine();

            Console.CancelKeyPress += (_, e) =>
            {
                e.Cancel = true;
                _running = false;
            };

            try
            {
                RunLoop(pad, port);
            }
            finally
            {
                try
                {
                    pad.Disconnect();
                }
                catch
                {
                    // 驱动已卸载时忽略
                }
            }
        }

        Console.WriteLine();
        Console.WriteLine("[退出] 虚拟控制器已移除。");
        return 0;
    }

    private static void RunLoop(IXbox360Controller pad, int port)
    {
        using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        try
        {
            socket.Bind(new IPEndPoint(IPAddress.Any, port));
        }
        catch (SocketException ex)
        {
            Fail($"无法绑定 UDP 端口 {port}", ex);
            Console.WriteLine("  端口可能被占用，或需要管理员权限。可指定其他端口：");
            Console.WriteLine("  VirtualGamepadServer.exe 26761");
            return;
        }

        socket.ReceiveTimeout = 250;

        // ---- 反向通道：游戏震动 → 手机 ----
        // 8 字节定长，与 Android 端 UdpSender.rumbleLoop / Python 端完全一致：
        //   "VGPR" + version(1) + large(0-255) + small(0-255) + reserved(0)
        pad.FeedbackReceived += (_, e) =>
        {
            var target = _lastClient;
            if (target is null)
            {
                return;
            }

            try
            {
                var payload = new byte[8]
                {
                    0x56, 0x47, 0x50, 0x52, 1, e.LargeMotor, e.SmallMotor, 0
                };
                socket.SendTo(payload, target);
            }
            catch
            {
                // 手机已离线或网络切换：忽略本次震动，不影响输入主链路
            }
        };

        var buffer = new byte[128];
        EndPoint remote = new IPEndPoint(IPAddress.Any, 0);

        string? activeClient = null;
        long lastPacketTs = Stopwatch.GetTimestamp();
        long lastReportTs = lastPacketTs;
        bool neutral = true;
        long packets = 0;
        long lost = 0;
        int lastSeq = -1;

        Console.WriteLine($"[监听] UDP 0.0.0.0:{port}   等待手机端数据…");
        Console.WriteLine();

        while (_running)
        {
            try
            {
                int received = socket.ReceiveFrom(buffer, ref remote);
                if (!GamepadProtocol.TryParse(buffer, received, out var state))
                {
                    continue;
                }

                // 排空积压，只应用最新一包：
                // 陈旧报文逐个"补放"会造成视角漂移惯性（松手后仍滑动）。
                while (socket.Available > 0)
                {
                    received = socket.ReceiveFrom(buffer, ref remote);
                    if (GamepadProtocol.TryParse(buffer, received, out var newer))
                    {
                        state = newer;
                    }
                }

                if (remote is IPEndPoint ep)
                {
                    _lastClient = ep;   // 供震动回传定位手机
                    string who = ep.ToString();
                    if (who != activeClient)
                    {
                        activeClient = who;
                        Console.WriteLine($"[连接] 手机端 {who}");
                    }

                    // 告知手机端玩家编号；C# 端是单人实现，固定 1P
                    try
                    {
                        socket.SendTo(
                            new byte[] { 0x56, 0x47, 0x50, 0x50, 1, 1, 0, 0 }, ep);
                    }
                    catch
                    {
                        // 忽略：手机可能刚好切网
                    }
                }

                ApplyState(pad, state);
                pad.SubmitReport();

                lastPacketTs = Stopwatch.GetTimestamp();
                neutral = false;
                packets++;

                if (lastSeq >= 0)
                {
                    int expected = (lastSeq + 1) & 0xFF;
                    int gap = (state.Sequence - expected) & 0xFF;
                    if (gap > 0 && gap < 128)
                    {
                        lost += gap;
                    }
                }

                lastSeq = state.Sequence;
            }
            catch (SocketException ex) when (ex.SocketErrorCode == SocketError.TimedOut)
            {
                if (!neutral && ElapsedMs(lastPacketTs) >= IdleResetMs)
                {
                    ApplyState(pad, GamepadState.Neutral);
                    pad.SubmitReport();
                    neutral = true;
                    lastSeq = -1;
                    Console.WriteLine();
                    Console.WriteLine($"[超时] {IdleResetMs} ms 未收到数据，输入已回中。");
                }
            }
            catch (SocketException ex)
            {
                if (!_running)
                {
                    break;
                }

                Console.WriteLine();
                Console.WriteLine($"[错误] 接收失败：{ex.SocketErrorCode}");
            }
            catch (ObjectDisposedException)
            {
                break;
            }

            if (ElapsedMs(lastReportTs) >= 1000)
            {
                if (packets > 0 || lost > 0)
                {
                    long total = packets + lost;
                    double rate = total > 0 ? lost * 100.0 / total : 0;
                    Console.Write($"\r[状态] {packets,4} pps    丢包率 {rate,5:F1}%    ");
                }

                // 周期性重发玩家编号：手机端接收线程晚于首包启动时也能拿到 1P
                var target = _lastClient;
                if (target is not null)
                {
                    try
                    {
                        socket.SendTo(
                            new byte[] { 0x56, 0x47, 0x50, 0x50, 1, 1, 0, 0 }, target);
                    }
                    catch
                    {
                        // 忽略
                    }
                }

                packets = 0;
                lost = 0;
                lastReportTs = Stopwatch.GetTimestamp();
            }
        }
    }

    /// <summary>
    /// 把一帧状态完整写入虚拟控制器。
    /// 注意：SetButtonState / SetAxisValue / SetSliderValue 只修改本地报表缓冲区，
    /// 必须再调用 SubmitReport() 才会真正下发到驱动。
    /// </summary>
    private static void ApplyState(IXbox360Controller pad, in GamepadState s)
    {
        pad.SetButtonState(Xbox360Button.A, (s.Buttons & GamepadProtocol.BtnA) != 0);
        pad.SetButtonState(Xbox360Button.B, (s.Buttons & GamepadProtocol.BtnB) != 0);
        pad.SetButtonState(Xbox360Button.X, (s.Buttons & GamepadProtocol.BtnX) != 0);
        pad.SetButtonState(Xbox360Button.Y, (s.Buttons & GamepadProtocol.BtnY) != 0);

        pad.SetButtonState(Xbox360Button.LeftShoulder, (s.Buttons & GamepadProtocol.BtnLeftShoulder) != 0);
        pad.SetButtonState(Xbox360Button.RightShoulder, (s.Buttons & GamepadProtocol.BtnRightShoulder) != 0);
        pad.SetButtonState(Xbox360Button.Back, (s.Buttons & GamepadProtocol.BtnBack) != 0);
        pad.SetButtonState(Xbox360Button.Start, (s.Buttons & GamepadProtocol.BtnStart) != 0);
        pad.SetButtonState(Xbox360Button.LeftThumb, (s.Buttons & GamepadProtocol.BtnLeftThumb) != 0);
        pad.SetButtonState(Xbox360Button.RightThumb, (s.Buttons & GamepadProtocol.BtnRightThumb) != 0);
        pad.SetButtonState(Xbox360Button.Guide, (s.Buttons & GamepadProtocol.BtnGuide) != 0);

        // 方向键在 XInput 中是 4 个独立的按键位，不是轴
        pad.SetButtonState(Xbox360Button.Up, (s.Buttons & GamepadProtocol.BtnUp) != 0);
        pad.SetButtonState(Xbox360Button.Down, (s.Buttons & GamepadProtocol.BtnDown) != 0);
        pad.SetButtonState(Xbox360Button.Left, (s.Buttons & GamepadProtocol.BtnLeft) != 0);
        pad.SetButtonState(Xbox360Button.Right, (s.Buttons & GamepadProtocol.BtnRight) != 0);

        // 摇杆：short，-32768..32767，中心 0
        pad.SetAxisValue(Xbox360Axis.LeftThumbX, s.LeftX);
        pad.SetAxisValue(Xbox360Axis.LeftThumbY, s.LeftY);
        pad.SetAxisValue(Xbox360Axis.RightThumbX, s.RightX);
        pad.SetAxisValue(Xbox360Axis.RightThumbY, s.RightY);

        // 扳机：byte，0..255，松开为 0（不是 -128）
        pad.SetSliderValue(Xbox360Slider.LeftTrigger, s.LeftTrigger);
        pad.SetSliderValue(Xbox360Slider.RightTrigger, s.RightTrigger);
    }

    private static void PrintBanner()
    {
        Console.WriteLine("============================================================");
        Console.WriteLine("  虚拟 Xbox 360 手柄 · PC 接收端");
        Console.WriteLine("  依赖: ViGEmBus 1.22.0（内核驱动，安装后需重启系统）");
        Console.WriteLine("  用法: VirtualGamepadServer.exe [端口]   默认 26760");
        Console.WriteLine("============================================================");
        Console.WriteLine();
    }

    private static bool TryParsePort(string[] args, out int port)
    {
        port = DefaultPort;
        if (args.Length == 0)
        {
            return true;
        }

        if (int.TryParse(args[0], out int value) && value is > 0 and < 65536)
        {
            port = value;
            return true;
        }

        Console.WriteLine($"[错误] 端口参数无效：{args[0]}");
        return false;
    }

    private static double ElapsedMs(long since)
    {
        return (Stopwatch.GetTimestamp() - since) * 1000.0 / Stopwatch.Frequency;
    }

    private static void Fail(string title, Exception ex)
    {
        Console.ForegroundColor = ConsoleColor.Red;
        Console.WriteLine($"[错误] {title}");
        Console.ResetColor();
        Console.WriteLine($"        {ex.GetType().Name}: {ex.Message}");
    }

    private static void TrySetUtf8()
    {
        try
        {
            Console.OutputEncoding = Encoding.UTF8;
        }
        catch
        {
            // 某些重定向场景下不允许修改编码，忽略即可
        }
    }
}
