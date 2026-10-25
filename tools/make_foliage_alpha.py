#!/usr/bin/env python3
"""Синтез альфа-атласа листвы из непрозрачного «блинa» пака Trees & Bushes.

В паке листва — это карточки-треугольники с плоской мятой текстурой без альфы:
`bush_thicket/diff.png` — 512² коричневого шума (1938 уникальных цветов), из-за
чего куст в игре читается как комок треугольников. Готовых листовых текстур
в `.blend` нет (0 image-датаблоков), поэтому листья рисуются процедурно поверх
родной палитры, а альфа вырезается по силуэту листа — шейдер уже делает
`discard` при `a < 0.05`, так что карточки сразу становятся «резными».

Запуск: python3 tools/make_foliage_alpha.py bush_green bush_dry bush_thicket oak_a oak_b oak_c
"""

import os
import sys

import numpy as np
from PIL import Image

DENSITY = 0.66          # сколько площади атласа должно быть покрыто листьями
LEAF_LEN = (0.030, 0.075)   # длина листа в долях стороны атласа
LEAF_WID = 0.40         # отношение ширины к длине


def draw_leaf(mask, cov, cx, cy, ln, wd, ang, px_scale, shade=None, tone=1.0):
    """Эллипс-лист с заострённым кончиком и прожилкой (в долях 0..1)."""
    r = int(ln * px_scale * 0.75) + 2
    x0 = max(0, int(cx * px_scale) - r)
    x1 = min(mask.shape[1], int(cx * px_scale) + r + 1)
    y0 = max(0, int(cy * px_scale) - r)
    y1 = min(mask.shape[0], int(cy * px_scale) + r + 1)
    if x1 <= x0 or y1 <= y0:
        return
    ys, xs = np.mgrid[y0:y1, x0:x1]
    dx = (xs / px_scale - cx) * np.cos(ang) + (ys / px_scale - cy) * np.sin(ang)
    dy = -(xs / px_scale - cx) * np.sin(ang) + (ys / px_scale - cy) * np.cos(ang)
    a = ln / 2.0
    b = wd / 2.0
    # лист: эллипс, суженный к кончику
    t = np.clip((dx + a) / ln, 0.0, 1.0)
    width = b * np.sqrt(np.clip(1.0 - ((dx) / a) ** 2, 0.0, 1.0)) * (0.35 + 0.65 * np.sin(np.pi * t))
    inside = np.abs(dy) <= width
    mask[y0:y1, x0:x1] |= inside
    cov[y0:y1, x0:x1] = np.where(inside, 1.0, cov[y0:y1, x0:x1])
    if shade is not None:
        # каждый лист — свой тон + светлая прожилка и затемнённая кромка:
        # карточка получает не «блин», а читаемую листву
        r = np.clip(np.abs(dy) / np.maximum(width, 1e-6), 0.0, 1.0)
        v = tone * (1.16 - 0.42 * r * r)
        v += 0.22 * np.exp(-((dy / np.maximum(width * 0.22, 1e-6)) ** 2))
        sub = shade[y0:y1, x0:x1]
        shade[y0:y1, x0:x1] = np.where(inside, np.maximum(sub, v), sub)


def synth(path, seed=0):
    img = np.array(Image.open(path).convert("RGB")).astype(np.float32)
    h, w = img.shape[:2]
    rng = np.random.default_rng(seed + sum(map(ord, os.path.basename(os.path.dirname(path)))))
    mask = np.zeros((h, w), bool)
    cov = np.zeros((h, w), np.float32)
    shade = np.zeros((h, w), np.float32)
    target = DENSITY
    tries = 0
    while cov.mean() < target and tries < 4000:
        tries += 1
        cx, cy = rng.random(), rng.random()
        ln = rng.uniform(*LEAF_LEN)
        wd = ln * LEAF_WID * rng.uniform(0.8, 1.25)
        ang = rng.uniform(0, np.pi)
        draw_leaf(mask, cov, cx, cy, ln, wd, ang, max(w, h), shade,
                  tone=float(rng.uniform(0.72, 1.28)))
    # мягкая кромка: один пиксель размытия по маске
    a = mask.astype(np.float32)
    blur = a.copy()
    blur[1:, :] = np.maximum(blur[1:, :], a[:-1, :] * 0.55)
    blur[:-1, :] = np.maximum(blur[:-1, :], a[1:, :] * 0.55)
    blur[:, 1:] = np.maximum(blur[:, 1:], a[:, :-1] * 0.55)
    blur[:, :-1] = np.maximum(blur[:, :-1], a[:, 1:] * 0.55)
    # прожилка: чуть темнее по центральной оси листа — поверх родной мятости
    rgb = img.copy()
    m3 = mask[..., None]
    rgb = np.where(m3, np.clip(rgb * np.clip(shade, 0.35, 1.55)[..., None], 0, 255), img)
    out = np.dstack([np.clip(rgb, 0, 255), np.clip(blur, 0, 1) * 255.0]).astype(np.uint8)
    Image.fromarray(out, "RGBA").save(path)
    print(f"{os.path.basename(os.path.dirname(path)):<14} alpha>0.5: {(blur > 0.5).mean() * 100:5.1f}%  "
          f"листьев: {tries}")


if __name__ == "__main__":
    root = "app-winterforest/src/main/assets/models"
    for mid in sys.argv[1:]:
        p = os.path.join(root, mid, "diff.png")
        if os.path.exists(p):
            synth(p)
