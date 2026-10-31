using System;

namespace VirtualGamepadServer;

/// <summary>
/// 一帧手柄状态。字段语义与 XINPUT_GAMEPAD 完全对齐。
/// </summary>
public readonly struct GamepadState
{
    public readonly ushort Buttons;
    public readonly short LeftX;
    public readonly short LeftY;
    public readonly short RightX;
    public readonly short RightY;
    public readonly byte LeftTrigger;
    public readonly byte RightTrigger;
    public readonly byte Sequence;

    public GamepadState(
        ushort buttons, short lx, short ly, short rx, short ry,
        byte lt, byte rt, byte sequence)
    {
        Buttons = buttons;
        LeftX = lx;
        LeftY = ly;
        RightX = rx;
        RightY = ry;
        LeftTrigger = lt;
        RightTrigger = rt;
        Sequence = sequence;
    }

    public static readonly GamepadState Neutral = new(0, 0, 0, 0, 0, 0, 0, 0);
}

/// <summary>
/// 20 字节定长报文的解析实现。多字节字段一律小端，与手机端 ByteOrder.LITTLE_ENDIAN 对应。
///
/// 报文布局：
///   0..3   magic "VGP1"
///   4      version (1)
///   5..6   buttons (uint16 LE)
///   7      sequence (uint8)
///   8..9   LX (int16 LE)
///   10..11 LY
///   12..13 RX
///   14..15 RY
///   16     LT (uint8)
///   17     RT (uint8)
///   18..19 reserved
/// </summary>
public static class GamepadProtocol
{
    public const int PacketSize = 20;
    public const byte Version = 1;

    // 小端下的 "VGP1"
    private const byte Magic0 = 0x56;
    private const byte Magic1 = 0x47;
    private const byte Magic2 = 0x50;
    private const byte Magic3 = 0x31;

    // 按键位，与手机端 Btn 对象严格一致
    public const ushort BtnA = 1 << 0;
    public const ushort BtnB = 1 << 1;
    public const ushort BtnX = 1 << 2;
    public const ushort BtnY = 1 << 3;
    public const ushort BtnLeftShoulder = 1 << 4;
    public const ushort BtnRightShoulder = 1 << 5;
    public const ushort BtnBack = 1 << 6;
    public const ushort BtnStart = 1 << 7;
    public const ushort BtnLeftThumb = 1 << 8;
    public const ushort BtnRightThumb = 1 << 9;
    public const ushort BtnUp = 1 << 10;
    public const ushort BtnDown = 1 << 11;
    public const ushort BtnLeft = 1 << 12;
    public const ushort BtnRight = 1 << 13;
    public const ushort BtnGuide = 1 << 14;

    public static bool TryParse(byte[] buffer, int length, out GamepadState state)
    {
        state = GamepadState.Neutral;

        if (buffer is null || length < PacketSize)
        {
            return false;
        }

        if (buffer[0] != Magic0 || buffer[1] != Magic1 ||
            buffer[2] != Magic2 || buffer[3] != Magic3)
        {
            return false;
        }

        if (buffer[4] != Version)
        {
            return false;
        }

        var buttons = (ushort)(buffer[5] | (buffer[6] << 8));
        var sequence = buffer[7];

        var lx = (short)(buffer[8] | (buffer[9] << 8));
        var ly = (short)(buffer[10] | (buffer[11] << 8));
        var rx = (short)(buffer[12] | (buffer[13] << 8));
        var ry = (short)(buffer[14] | (buffer[15] << 8));

        var lt = buffer[16];
        var rt = buffer[17];

        state = new GamepadState(buttons, lx, ly, rx, ry, lt, rt, sequence);
        return true;
    }
}
