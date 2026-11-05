# -*- coding: utf-8 -*-
"""生成便携程序图标（瑞士国际主义：黑底、白字、单一红色几何方块）。

输出：assets/app.ico，含 16/24/32/48/64/128/256 七种尺寸，
供 EXE 图标、任务栏图标与窗口图标共同使用。
"""
from __future__ import annotations

import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "app.ico")
INK = (17, 17, 17)
WHITE = (255, 255, 255)
RED = (216, 35, 42)
FONT_CANDIDATES = (
    "C:/Windows/Fonts/arialbd.ttf",
    "C:/Windows/Fonts/arial.ttf",
    "C:/Windows/Fonts/segoeuib.ttf",
)


def _font(size: int):
    for path in FONT_CANDIDATES:
        if os.path.isfile(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def draw(size: int) -> Image.Image:
    img = Image.new("RGB", (size, size), INK)
    d = ImageDraw.Draw(img)
    f = _font(max(8, int(size * 0.48)))
    d.text((int(size * 0.07), int(size * 0.10)), "VG", font=f, fill=WHITE)
    r = max(2, int(size * 0.19))
    m = max(1, int(size * 0.06))
    d.rectangle((size - r - m, size - r - m, size - m, size - m), fill=RED)
    return img


def main() -> None:
    base = draw(256)
    base.save(
        OUT,
        format="ICO",
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48),
               (64, 64), (128, 128), (256, 256)],
    )
    print("ICON", OUT, os.path.getsize(OUT), "bytes")


if __name__ == "__main__":
    main()
