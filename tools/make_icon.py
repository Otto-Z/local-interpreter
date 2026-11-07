"""生成应用图标（1024x1024 PNG），无第三方依赖。"""
from __future__ import annotations

import struct
import sys
import zlib

import numpy as np

SIZE = 1024
SS = 2
N = SIZE * SS


def rounded_rect(x, y, cx, cy, hw, hh, r):
    dx = np.abs(x - cx) - (hw - r)
    dy = np.abs(y - cy) - (hh - r)
    qx = np.maximum(dx, 0.0)
    qy = np.maximum(dy, 0.0)
    dist = np.sqrt(qx * qx + qy * qy) + np.minimum(np.maximum(dx, dy), 0.0) - r
    return np.clip(0.5 - dist * N, 0.0, 1.0)


def write_png(path: str, rgba: np.ndarray) -> None:
    h, w, _ = rgba.shape
    raw = b"".join(b"\x00" + rgba[i].tobytes() for i in range(h))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as f:
        f.write(png)


def main(path: str) -> None:
    ys, xs = np.mgrid[0:N, 0:N]
    x = (xs + 0.5) / N
    y = (ys + 0.5) / N

    color = np.zeros((N, N, 3), dtype=np.float32)
    alpha = np.zeros((N, N), dtype=np.float32)

    def draw(mask, rgb):
        m = mask[..., None]
        color[:] = np.asarray(rgb, dtype=np.float32) * m + color * (1.0 - m)
        alpha[:] = mask + alpha * (1.0 - mask)

    # 背景：深色圆角方块 + 底部青色微光
    bg_mask = rounded_rect(x, y, 0.5, 0.5, 0.5 - 0.085, 0.5 - 0.085, 0.225)
    grad = np.clip((y - 0.1) / 0.9, 0, 1)[..., None]
    bg = np.array([23, 28, 35], np.float32) * (1 - grad) + np.array(
        [11, 13, 16], np.float32
    ) * grad
    glow = np.exp(-(((x - 0.5) ** 2 + (y - 0.86) ** 2) / 0.05))[..., None]
    bg = bg + np.array([18, 92, 74], np.float32) * glow * 0.55
    draw(bg_mask, np.clip(bg, 0, 255))

    # 三条字幕条
    bars = [
        (0.45, 0.395, 0.205, 0.0455, (233, 236, 240)),
        (0.515, 0.515, 0.285, 0.0455, (233, 236, 240)),
        (0.395, 0.635, 0.165, 0.0455, (79, 209, 165)),
    ]
    for cx, cy, hw, hh, rgb in bars:
        draw(rounded_rect(x, y, cx, cy, hw, hh, hh), rgb)

    # 转录点
    dot = np.sqrt((x - 0.645) ** 2 + (y - 0.635) ** 2)
    draw(np.clip(0.5 - (dot - 0.043) * N, 0, 1), (79, 209, 165))

    # 降采样抗锯齿，反预乘
    color = color.reshape(SIZE, SS, SIZE, SS, 3).mean(axis=(1, 3))
    alpha = alpha.reshape(SIZE, SS, SIZE, SS).mean(axis=(1, 3))
    safe = np.maximum(alpha, 1e-6)[..., None]
    rgb = np.clip(color / safe, 0, 255)
    out = np.concatenate([rgb, alpha[..., None] * 255.0], axis=2)
    write_png(path, out.astype(np.uint8))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "icon.png")
