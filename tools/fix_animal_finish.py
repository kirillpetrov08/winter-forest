#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Финальная отделка моделей зверей: шерстяные текстуры + правильные размеры.

1) Перерисовывает diff.png оленя и волка: направленный ворс (растянутый шум),
   агути-смешение тонов, тёмная линия хребта (u≈0.5 — по UV-развёртке меша),
   светлое брюхо (u→0/1), симметричные затемнения по краям v (голова/круп).
2) Масштабирует меши до зоологически правильных размеров (олень крупнее волка):
   олень ×1.30 (длина 1.53 м, холка ~1.2 м), волк ×0.95 (с хвостом ~1.56 м).
   Идемпотентно: повторный запуск помечает заголовок obj '# scaled-*' и не
   масштабирует дважды.
"""
import math
import os
import sys

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELS = os.path.join(ROOT, "app-winterforest", "src", "main", "assets", "models")

SIZE = 512


# ------------------------------------------------------------------ шум
def _fbm(size, fx, fy, oct_, seed):
    """Анизотропный fbm: fx/fy — частоты по осям (u, v)."""
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:size, 0:size].astype(np.float32) / size
    t = np.zeros((size, size), np.float32)
    amp = 1.0
    for o in range(oct_):
        ax = fx * 2 ** o
        ay = fy * 2 ** o
        g = rng.random((int(ay) + 2, int(ax) + 2), dtype=np.float32)
        xs = x * ax
        ys = y * ay
        ix = np.clip(xs.astype(int), 0, int(ax))
        iy = np.clip(ys.astype(int), 0, int(ay))
        gx = xs - ix
        gy = ys - iy
        gx = gx * gx * (3 - 2 * gx)
        gy = gy * gy * (3 - 2 * gy)
        g00 = g[iy, ix]
        g10 = g[iy, ix + 1]
        g01 = g[iy + 1, ix]
        g11 = g[iy + 1, ix + 1]
        t += amp * ((g00 * (1 - gx) + g10 * gx) * (1 - gy) +
                    (g01 * (1 - gx) + g11 * gx) * gy)
        amp *= 0.55
    return t / 1.9


def fur_texture(spine_rgb, belly_rgb, mid_rgb, seed, grain=0.16):
    """Шкура: хребет u=0.5 тёмный, брюхо u→0/1 светлое, ворс вдоль v."""
    u = np.linspace(0, 1, SIZE, dtype=np.float32)[None, :].repeat(SIZE, 0)
    v = np.linspace(0, 1, SIZE, dtype=np.float32)[:, None].repeat(SIZE, 1)

    # поперечный профиль: 0 на хребте, 1 на брюхе
    across = np.clip(np.abs(u - 0.5) * 2.0, 0, 1) ** 0.85

    base = (np.array(spine_rgb, np.float32)[None, None, :] * (1 - across[..., None]) +
            np.array(belly_rgb, np.float32)[None, None, :] * across[..., None])

    # тёплый промежуточный тон по бокам (агути)
    side = np.exp(-((across - 0.55) ** 2) / 0.08)[..., None]
    base = base * (1 - side * 0.45) + np.array(mid_rgb, np.float32)[None, None, :] * side * 0.45

    # крупные пятна шерсти + направленный ворс (растянут по v)
    clump = _fbm(SIZE, 4, 7, 3, seed)
    streak = _fbm(SIZE, 7, 34, 4, seed + 11)   # вертикальные прожилки
    fine = _fbm(SIZE, 26, 90, 2, seed + 23)
    shade = 0.80 + 0.34 * streak + 0.14 * (clump - 0.5) + grain * (fine - 0.5)

    col = base * shade[..., None]

    # симметричные затемнения к краям v (голова/круп не зависят от переворота UV)
    ends = np.clip(1 - np.minimum(v, 1 - v) / 0.16, 0, 1) ** 1.4
    col *= (1 - 0.16 * ends)[..., None]

    return Image.fromarray(np.clip(col * 255, 0, 255).astype(np.uint8))


DEER_TEX = lambda: fur_texture(
    spine_rgb=(0.36, 0.22, 0.11), belly_rgb=(0.84, 0.74, 0.60),
    mid_rgb=(0.55, 0.38, 0.20), seed=41)

WOLF_TEX = lambda: fur_texture(
    spine_rgb=(0.20, 0.19, 0.18), belly_rgb=(0.74, 0.72, 0.68),
    mid_rgb=(0.42, 0.40, 0.38), seed=73, grain=0.13)


# ------------------------------------------------------------------ размеры
def scale_obj(path, factor):
    marker = "# scaled-%s" % ("%.3f" % factor)
    lines = open(path).read().splitlines()
    if any(l.startswith("# scaled-") for l in lines[:3]):
        print("   %-28s уже масштабирован — пропуск" % os.path.basename(path))
        return
    out = [marker]
    for line in lines:
        if line.startswith("v "):
            p = line.split()
            out.append("v %.5f %.5f %.5f" % (float(p[1]) * factor, float(p[2]) * factor, float(p[3]) * factor))
        elif line.startswith("vn "):
            p = line.split()
            nx, ny, nz = (float(p[1]), float(p[2]), float(p[3]))
            l = math.sqrt(nx * nx + ny * ny + nz * nz) or 1.0
            out.append("vn %.5f %.5f %.5f" % (nx / l, ny / l, nz / l))
        else:
            out.append(line)
    open(path, "w").write("\n".join(out) + "\n")
    print("   %-28s ×%s" % (os.path.basename(path), factor))


def finish(model_id, tex_fn, scale):
    d = os.path.join(MODELS, model_id)
    print("== %s" % model_id)
    tex_fn().save(os.path.join(d, "diff.png"))
    print("   diff.png — новая шерстяная текстура")
    for f in sorted(os.listdir(d)):
        if f.endswith(".obj"):
            scale_obj(os.path.join(d, f), scale)


def main():
    finish("deer_body", DEER_TEX, 1.30)
    finish("wolf_body", WOLF_TEX, 0.95)


if __name__ == "__main__":
    main()
