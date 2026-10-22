#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Winter Forest — bake high-detail tileable material & location textures.

Produces deterministic 2048x2048 PNG textures into
`app-winterforest/src/main/assets/textures/`. These are packaged INTO the APK
(the app must weigh 200+ MB of fully detailed graphics) and also feed the
offline screenshot rasterizer (tools/render/scene_and_render.py).

Run:  python3 tools/bake_textures.py
"""
import os
import sys
import time

import numpy as np
from PIL import Image

OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app-winterforest", "src", "main", "assets", "textures",
)

SIZE = 2048          # material resolution
LOC_SIZE = 2048      # location splat resolution
COMPRESS_LEVEL = 1   # keep PNGs large & fast to write (app-size goal)


# ----------------------------------------------------------------------------- noise
def value_noise(size, freq, seed):
    """Tileable value noise on [0,1], smoothstep interpolation."""
    rng = np.random.default_rng(seed)
    g = rng.random((freq, freq), dtype=np.float32)
    xs = np.arange(size, dtype=np.float32) * (freq / size)
    ix = np.floor(xs).astype(np.int32)
    fx = xs - ix
    fx = fx * fx * (3.0 - 2.0 * fx)
    ixm = ix % freq
    ix1 = (ix + 1) % freq
    g00 = g[np.ix_(ixm, ixm)]
    g10 = g[np.ix_(ix1, ixm)]
    g01 = g[np.ix_(ixm, ix1)]
    g11 = g[np.ix_(ix1, ix1)]
    wx = fx[None, :]
    wy = fx[:, None]
    top = g00 * (1 - wx) + g10 * wx
    bot = g01 * (1 - wx) + g11 * wx
    return top * (1 - wy) + bot * wy


def fbm(size, base_freq, octaves, seed, stretch=(1.0, 1.0), gain=0.5, lac=2.0):
    total = np.zeros((size, size), dtype=np.float32)
    amp = 1.0
    norm = 0.0
    freq = float(base_freq)
    for o in range(octaves):
        fy = max(1, int(round(freq * stretch[0])))
        fx = max(1, int(round(freq * stretch[1])))
        n = value_noise(size, fy, seed + o * 101) * 0.5 + \
            value_noise(size, fx, seed + 5000 + o * 101) * 0.5
        total += amp * n
        norm += amp
        amp *= gain
        freq *= lac
    return total / max(norm, 1e-5)


def ridged(size, base_freq, octaves, seed, stretch=(1.0, 1.0)):
    n = fbm(size, base_freq, octaves, seed, stretch)
    return 1.0 - np.abs(2.0 * n - 1.0)


def micro(size, seed, amp):
    rng = np.random.default_rng(seed)
    return rng.normal(0.0, 1.0, (size, size)).astype(np.float32) * amp


def hexc(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float32)


def shade3(base, mid, high, micro_n, lo=0.62, hi=1.28, micro_amt=1.0):
    """base/mid/high are [0,1] fields; returns HxWx3 uint8."""
    l = lo + (hi - lo) * (0.45 * mid + 0.55 * high)
    col = base[None, None, :] * l[:, :, None]
    col += (micro_n * micro_amt)[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def save_rgb(arr, name, size):
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, name + ".png")
    Image.fromarray(arr, "RGB").save(path, compress_level=COMPRESS_LEVEL)
    mb = os.path.getsize(path) / (1024 * 1024)
    print(f"  {name:<22} {size}x{size}  {mb:6.1f} MB", flush=True)
    return mb


# ----------------------------------------------------------------------------- materials
def bake_material(name, fn, size=SIZE):
    t0 = time.time()
    arr = fn(size)
    # Финальный микронный слой: высокая энтропия = чёткая зернистость материала
    # и гарантированный вес пакета текстур (200+ МБ графики в приложении).
    arr = np.clip(arr.astype(np.int16) + micro(size, 9000 + (sum(ord(c) for c in name) % 9000), 5.5)[:, :, None].astype(np.int16), 0, 255).astype(np.uint8)
    mb = save_rgb(arr, name, size)
    print(f"      ({time.time() - t0:.1f}s)", flush=True)
    return mb


def m_snow_soft(s):
    mid = fbm(s, 6, 5, 11)
    high = fbm(s, 48, 4, 12)
    base = hexc("E8EEF6")
    mn = micro(s, 13, 3.2)
    img = shade3(base, mid, high, mn, 0.86, 1.10)
    spark = (np.random.default_rng(14).random((s, s)) > 0.9986)
    img[spark] = (255, 255, 255)
    return img


def m_snow_crust(s):
    cracks = ridged(s, 10, 5, 21, stretch=(1.0, 1.4))
    mid = fbm(s, 8, 4, 22)
    high = fbm(s, 64, 3, 23)
    base = hexc("DCE6F2")
    img = shade3(base, mid * 0.6 + cracks * 0.4, high, micro(s, 24, 3.0), 0.72, 1.18)
    img[cracks > 0.86] = (168, 186, 210)
    return img


def m_ice_cracked(s):
    cracks = ridged(s, 7, 6, 31, stretch=(0.9, 1.1))
    veins = ridged(s, 22, 4, 32)
    mid = fbm(s, 5, 4, 33)
    high = fbm(s, 40, 3, 34)
    base = hexc("BFE0F0")
    img = shade3(base, 0.55 * mid + 0.45 * veins, high, micro(s, 35, 2.4), 0.78, 1.22)
    img[cracks > 0.88] = (235, 248, 255)
    img[cracks > 0.96] = (255, 255, 255)
    return img


def m_bark_pine(s):
    ridge = ridged(s, 26, 5, 41, stretch=(5.5, 0.55))
    groove = ridged(s, 10, 4, 42, stretch=(4.0, 0.5))
    mid = fbm(s, 7, 4, 43)
    high = fbm(s, 52, 3, 44)
    base = hexc("6B4A32")
    l = 0.52 + 0.62 * (0.55 * ridge + 0.30 * groove + 0.15 * high)
    col = base[None, None, :] * l[:, :, None]
    col += (micro(s, 45, 3.4))[:, :, None]
    # moss/lichen freckles
    moss = fbm(s, 3, 3, 46) > 0.62
    col[moss] = col[moss] * 0.72 + np.array([70, 92, 58], dtype=np.float32) * 0.28
    return np.clip(col, 0, 255).astype(np.uint8)


def m_bark_birch(s):
    streak = ridged(s, 18, 4, 51, stretch=(6.5, 0.5))
    mid = fbm(s, 6, 4, 52)
    high = fbm(s, 60, 3, 53)
    base = hexc("E4DED2")
    img = shade3(base, mid, high, micro(s, 54, 2.6), 0.82, 1.12)
    rng = np.random.default_rng(55)
    lent = (fbm(s, 24, 3, 56, stretch=(8.0, 0.8)) > 0.60) & (streak > 0.45)
    img[lent] = img[lent] * 0.35 + np.array([40, 34, 30], dtype=np.float32) * 0.65
    band = (fbm(s, 3, 3, 57, stretch=(2.2, 0.35)) > 0.70)
    img[band] = img[band] * 0.55
    return img


def m_pine_needles(s):
    needle = ridged(s, 90, 3, 61, stretch=(7.0, 0.45))
    clump = fbm(s, 6, 4, 62)
    high = fbm(s, 30, 3, 63)
    base = hexc("2F5233")
    l = 0.48 + 0.72 * (0.5 * needle + 0.32 * clump + 0.18 * high)
    col = base[None, None, :] * l[:, :, None]
    col += (micro(s, 64, 3.0))[:, :, None]
    tip = needle > 0.88
    col[tip] = col[tip] * 1.35 + np.array([26, 40, 22], dtype=np.float32)
    return np.clip(col, 0, 255).astype(np.uint8)


def m_stone_granite(s):
    speck_a = fbm(s, 40, 4, 71)
    speck_b = fbm(s, 110, 3, 72)
    crack = ridged(s, 6, 4, 73)
    base = hexc("8C8A87")
    l = 0.58 + 0.55 * (0.42 * speck_a + 0.38 * speck_b + 0.20 * crack)
    col = base[None, None, :] * l[:, :, None]
    dark = (speck_b > 0.62) & (speck_a > 0.48)
    col[dark] *= 0.62
    light = speck_b > 0.82
    col[light] = col[light] * 0.6 + np.array([210, 208, 204], dtype=np.float32) * 0.4
    col += (micro(s, 74, 3.6))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_stone_mossy(s):
    rock = fbm(s, 9, 5, 81)
    crack = ridged(s, 7, 4, 82)
    moss = fbm(s, 5, 4, 83)
    base = hexc("7E8078")
    img = shade3(base, 0.5 * rock + 0.5 * crack, fbm(s, 50, 3, 84), micro(s, 85, 3.2), 0.55, 1.15)
    mask = (moss > 0.55) & (rock > 0.35)
    img[mask] = img[mask] * 0.45 + np.array([66, 96, 52], dtype=np.float32) * 0.55
    fuzz = fbm(s, 60, 3, 86) > 0.66
    img[mask & fuzz] = img[mask & fuzz] * 0.7 + np.array([96, 128, 62], dtype=np.float32) * 0.3
    return img


def m_wood_plank(s):
    grain = ridged(s, 22, 5, 91, stretch=(0.32, 4.2))
    knots = fbm(s, 5, 4, 92)
    high = fbm(s, 55, 3, 93)
    base = hexc("A97A4B")
    l = 0.55 + 0.62 * (0.52 * grain + 0.28 * knots + 0.20 * high)
    col = base[None, None, :] * l[:, :, None]
    seam = (np.abs((np.arange(s, dtype=np.float32)[:, None] / s * 6.0) % 1.0 - 0.5) > 0.478)
    col *= np.where(seam, 0.55, 1.0)
    col += (micro(s, 94, 3.0))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_wood_log_end(s):
    yy, xx = np.mgrid[0:s, 0:s].astype(np.float32)
    cy, cx = s / 2.0, s / 2.0
    r = np.sqrt((yy - cy) ** 2 + (xx - cx) ** 2) / (s / 2.0)
    wob = fbm(s, 6, 4, 101)
    rings = np.sin((r * 26.0 + wob * 2.2) * np.pi) * 0.5 + 0.5
    rings = rings ** 1.6
    base = hexc("B98D5C")
    l = 0.52 + 0.66 * (0.62 * rings + 0.38 * fbm(s, 30, 3, 102))
    col = base[None, None, :] * l[:, :, None]
    col += (micro(s, 103, 3.2))[:, :, None]
    bark_ring = r > 0.88
    col[bark_ring] = col[bark_ring] * 0.35 + hexc("5E4230")[None, :] * 0.65
    crack = (np.abs(xx - cx) < (2.0 + wob * 4.0)) & (yy > cy)
    col[crack] *= 0.45
    return np.clip(col, 0, 255).astype(np.uint8)


def m_wood_carved(s):
    grain = ridged(s, 16, 5, 111, stretch=(0.45, 3.4))
    chisel = ridged(s, 8, 3, 112, stretch=(1.0, 6.0))
    base = hexc("C29159")
    img = shade3(base, 0.62 * grain + 0.38 * chisel, fbm(s, 46, 3, 113), micro(s, 114, 2.8), 0.58, 1.22)
    return img


def m_metal_worn(s):
    brush = ridged(s, 30, 4, 121, stretch=(0.22, 7.0))
    dents = fbm(s, 6, 4, 122)
    high = fbm(s, 70, 3, 123)
    base = hexc("AEB4BD")
    l = 0.62 + 0.52 * (0.5 * brush + 0.28 * dents + 0.22 * high)
    col = base[None, None, :] * l[:, :, None]
    scratch = ridged(s, 14, 3, 124, stretch=(0.3, 9.0)) > 0.93
    col[scratch] = col[scratch] * 0.55 + np.array([235, 240, 248], dtype=np.float32) * 0.45
    col += (micro(s, 125, 2.6))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_metal_rust(s):
    rust = fbm(s, 7, 5, 131)
    pit = fbm(s, 28, 4, 132)
    base = hexc("7A4B2E")
    steel = hexc("9AA0A8")
    t = np.clip((rust - 0.35) * 2.2, 0, 1)
    col = steel[None, None, :] * (1 - t[:, :, None]) + base[None, None, :] * t[:, :, None]
    l = 0.55 + 0.6 * (0.55 * pit + 0.45 * rust)
    col *= l[:, :, None]
    flake = rust > 0.78
    col[flake] = col[flake] * 0.6 + np.array([140, 72, 38], dtype=np.float32) * 0.4
    col += (micro(s, 133, 3.4))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def fur_like(s, seed0, base_hex, tip_hex, streak=9.0, freq=48):
    strand = ridged(s, freq, 4, seed0, stretch=(streak, 0.42))
    clump = fbm(s, 6, 4, seed0 + 1)
    high = fbm(s, 34, 3, seed0 + 2)
    base = hexc(base_hex)
    tip = hexc(tip_hex)
    l = 0.50 + 0.72 * (0.52 * strand + 0.30 * clump + 0.18 * high)
    col = base[None, None, :] * l[:, :, None]
    tmask = strand > 0.72
    col[tmask] = col[tmask] * 0.55 + tip[None, :] * 0.45
    col += (micro(s, seed0 + 3, 3.0))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_fur_wolf(s):
    return fur_like(s, 141, "5A5E66", "C8CCD2", streak=10.0, freq=52)


def m_fur_deer(s):
    return fur_like(s, 151, "8A5F3C", "D8B088", streak=8.0, freq=46)


def m_cloth_canvas(s):
    wx = ((np.arange(s, dtype=np.float32)[None, :] / s * 220.0) % 1.0 < 0.5).astype(np.float32)
    wy = ((np.arange(s, dtype=np.float32)[:, None] / s * 220.0) % 1.0 < 0.5).astype(np.float32)
    weave = 0.5 + 0.5 * (wx * (1 - wy) + wy * (1 - wx)) + 0.22 * (wx * wy)
    dirt = fbm(s, 6, 4, 161)
    base = hexc("B9A482")
    l = 0.58 + 0.55 * (0.42 * weave + 0.34 * dirt + 0.24 * fbm(s, 40, 3, 162))
    col = base[None, None, :] * l[:, :, None]
    col += (micro(s, 163, 3.2))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_cloth_wool(s):
    fuzz = ridged(s, 60, 4, 171, stretch=(1.2, 1.2))
    pill = fbm(s, 14, 4, 172)
    base = hexc("7E3B3B")
    l = 0.55 + 0.62 * (0.46 * fuzz + 0.40 * pill + 0.14 * fbm(s, 8, 3, 173))
    col = base[None, None, :] * l[:, :, None]
    col += (micro(s, 174, 3.4))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_ember_coal(s):
    cells = ridged(s, 12, 5, 181)
    glow = fbm(s, 5, 4, 182)
    base = hexc("2A1410")
    hot = hexc("FF6A1E")
    hot2 = hexc("FFD08A")
    l = 0.42 + 0.72 * cells
    col = base[None, None, :] * l[:, :, None]
    t = np.clip((glow - 0.52) * 3.0, 0, 1) * np.clip(cells * 1.3, 0, 1)
    col += hot[None, None, :] * (t[:, :, None] * 0.9)
    core = t > 0.82
    col[core] = col[core] * 0.4 + hot2[None, :] * 0.6
    col += (micro(s, 183, 2.8))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_leather_tanned(s):
    pores = fbm(s, 42, 4, 191)
    crease = ridged(s, 9, 4, 192)
    base = hexc("8C5A34")
    l = 0.55 + 0.6 * (0.46 * pores + 0.40 * crease + 0.14 * fbm(s, 5, 3, 193))
    col = base[None, None, :] * l[:, :, None]
    stitch = ((np.arange(s, dtype=np.float32)[:, None] / s * 34.0) % 1.0 < 0.10) & \
             ((np.abs((np.arange(s, dtype=np.float32)[None, :] / s * 6.0) % 1.0 - 0.5)) > 0.485)
    col[stitch] = col[stitch] * 0.4 + np.array([220, 200, 160], dtype=np.float32) * 0.6
    col += (micro(s, 194, 2.8))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_meat_raw(s):
    fibre = ridged(s, 26, 4, 201, stretch=(4.0, 0.55))
    fat = fbm(s, 7, 4, 202)
    base = hexc("A63232")
    l = 0.55 + 0.6 * (0.5 * fibre + 0.34 * fat + 0.16 * fbm(s, 44, 3, 203))
    col = base[None, None, :] * l[:, :, None]
    fmask = fat > 0.62
    col[fmask] = col[fmask] * 0.4 + np.array([226, 196, 172], dtype=np.float32) * 0.6
    col += (micro(s, 204, 3.0))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_resin_amber(s):
    swirl = fbm(s, 5, 5, 211)
    crack = ridged(s, 11, 4, 212)
    base = hexc("D88A1E")
    deep = hexc("7A3E08")
    t = np.clip(swirl * 1.2, 0, 1)
    col = deep[None, None, :] * (1 - t[:, :, None]) + base[None, None, :] * t[:, :, None]
    l = 0.62 + 0.55 * (0.5 * swirl + 0.34 * crack + 0.16 * fbm(s, 40, 3, 213))
    col *= l[:, :, None]
    glint = crack > 0.92
    col[glint] = col[glint] * 0.5 + np.array([255, 230, 170], dtype=np.float32) * 0.5
    col += (micro(s, 214, 2.2))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_crystal_ice(s):
    facet = ridged(s, 9, 5, 221)
    shard = ridged(s, 24, 4, 222, stretch=(1.6, 1.6))
    base = hexc("9FD8EE")
    l = 0.62 + 0.58 * (0.5 * facet + 0.38 * shard + 0.12 * fbm(s, 60, 3, 223))
    col = base[None, None, :] * l[:, :, None]
    edge = (facet > 0.86) | (shard > 0.90)
    col[edge] = col[edge] * 0.45 + np.array([240, 252, 255], dtype=np.float32) * 0.55
    col += (micro(s, 224, 2.0))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_herb_leaf(s):
    vein = ridged(s, 12, 4, 231, stretch=(1.8, 1.8))
    patch = fbm(s, 6, 4, 232)
    base = hexc("4E7A38")
    dark = hexc("27461E")
    t = np.clip(patch * 1.15, 0, 1)
    col = dark[None, None, :] * (1 - t[:, :, None]) + base[None, None, :] * t[:, :, None]
    l = 0.58 + 0.55 * (0.42 * vein + 0.40 * patch + 0.18 * fbm(s, 48, 3, 233))
    col *= l[:, :, None]
    col += (micro(s, 234, 2.8))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_dirt_frozen(s):
    clod = fbm(s, 8, 5, 241)
    grit = fbm(s, 70, 3, 242)
    crack = ridged(s, 6, 4, 243)
    base = hexc("5C4634")
    l = 0.52 + 0.62 * (0.5 * clod + 0.3 * grit + 0.2 * crack)
    col = base[None, None, :] * l[:, :, None]
    frost = crack > 0.86
    col[frost] = col[frost] * 0.5 + np.array([190, 205, 220], dtype=np.float32) * 0.5
    col += (micro(s, 244, 3.6))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_grass_dry(s):
    blade = ridged(s, 80, 4, 251, stretch=(6.5, 0.5))
    patch = fbm(s, 5, 4, 252)
    base = hexc("9A8A4E")
    dark = hexc("5C4E2A")
    t = np.clip(patch * 1.2, 0, 1)
    col = dark[None, None, :] * (1 - t[:, :, None]) + base[None, None, :] * t[:, :, None]
    l = 0.55 + 0.6 * (0.55 * blade + 0.30 * patch + 0.15 * fbm(s, 40, 3, 253))
    col *= l[:, :, None]
    col += (micro(s, 254, 3.0))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_bone_old(s):
    stain = fbm(s, 6, 4, 261)
    pore = fbm(s, 55, 3, 262)
    crack = ridged(s, 9, 4, 263)
    base = hexc("D9CFB8")
    img = shade3(base, 0.55 * stain + 0.45 * crack, pore, micro(s, 264, 2.4), 0.72, 1.16)
    img[crack > 0.90] = img[crack > 0.90] * 0.55
    return img


def m_item_ore_raw(s):
    chunk = fbm(s, 8, 5, 401)
    facet = ridged(s, 16, 4, 402)
    glint = fbm(s, 60, 3, 403)
    base = hexc("5E5A66")
    ore = hexc("C2A45C")
    t = np.clip((chunk - 0.48) * 2.4, 0, 1)
    col = base[None, None, :] * (1 - t[:, :, None]) + ore[None, None, :] * t[:, :, None]
    l = 0.55 + 0.6 * (0.45 * facet + 0.35 * chunk + 0.2 * glint)
    col *= l[:, :, None]
    spark = facet > 0.92
    col[spark] = col[spark] * 0.5 + np.array([255, 236, 180], dtype=np.float32) * 0.5
    col += (micro(s, 404, 4.0))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_item_meal_stew(s):
    broth = fbm(s, 6, 4, 411)
    chunk = fbm(s, 14, 4, 412)
    fat = fbm(s, 30, 3, 413)
    base = hexc("8A4E22")
    cream = hexc("C98B4E")
    t = np.clip(broth * 1.3, 0, 1)
    col = base[None, None, :] * (1 - t[:, :, None]) + cream[None, None, :] * t[:, :, None]
    l = 0.58 + 0.55 * (0.42 * broth + 0.38 * chunk + 0.2 * fat)
    col *= l[:, :, None]
    piece = chunk > 0.68
    col[piece] = col[piece] * 0.55 + np.array([150, 92, 52], dtype=np.float32) * 0.45
    herb = fat > 0.82
    col[herb] = col[herb] * 0.6 + np.array([70, 100, 48], dtype=np.float32) * 0.4
    col += (micro(s, 414, 3.6))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_item_rope_coil(s):
    twist = ridged(s, 34, 4, 421, stretch=(0.35, 6.0))
    fuzz = fbm(s, 70, 3, 422)
    base = hexc("B0925E")
    dark = hexc("6E5432")
    t = np.clip(twist * 1.15, 0, 1)
    col = dark[None, None, :] * (1 - t[:, :, None]) + base[None, None, :] * t[:, :, None]
    l = 0.55 + 0.6 * (0.52 * twist + 0.3 * fuzz + 0.18 * fbm(s, 8, 3, 423))
    col *= l[:, :, None]
    col += (micro(s, 424, 3.8))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_item_torch_wrap(s):
    wrap = ridged(s, 18, 4, 431, stretch=(3.2, 0.7))
    soak = fbm(s, 5, 4, 432)
    char = fbm(s, 26, 3, 433)
    base = hexc("4E3A22")
    resin = hexc("C98B2A")
    t = np.clip((soak - 0.5) * 2.0, 0, 1)
    col = base[None, None, :] * (1 - t[:, :, None]) + resin[None, None, :] * t[:, :, None]
    l = 0.52 + 0.6 * (0.5 * wrap + 0.34 * soak + 0.16 * char)
    col *= l[:, :, None]
    burnt = char > 0.74
    col[burnt] *= 0.42
    col += (micro(s, 434, 3.6))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_item_map_paper(s):
    fiber = fbm(s, 24, 4, 441)
    stain = fbm(s, 5, 4, 442)
    base = hexc("D9CBA8")
    l = 0.72 + 0.38 * (0.5 * fiber + 0.5 * stain)
    col = base[None, None, :] * l[:, :, None]
    line_h = (np.abs((np.arange(s, dtype=np.float32)[:, None] / s * 14.0) % 1.0 - 0.5) > 0.482)
    line_v = (np.abs((np.arange(s, dtype=np.float32)[None, :] / s * 14.0) % 1.0 - 0.5) > 0.482)
    grid = np.where(line_h | line_v, 0.86, 1.0)
    col *= grid[:, :, None] if grid.shape == (s, s) else grid[:, :, None]
    blot = stain > 0.72
    col[blot] = col[blot] * 0.75
    col += (micro(s, 443, 3.0))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


def m_item_herbs_bundle(s):
    stem = ridged(s, 40, 4, 451, stretch=(5.5, 0.5))
    leaf = fbm(s, 16, 4, 452)
    dry = fbm(s, 6, 3, 453)
    base = hexc("6E7A3A")
    dark = hexc("3E4420")
    t = np.clip(leaf * 1.2, 0, 1)
    col = dark[None, None, :] * (1 - t[:, :, None]) + base[None, None, :] * t[:, :, None]
    l = 0.55 + 0.6 * (0.5 * stem + 0.32 * leaf + 0.18 * dry)
    col *= l[:, :, None]
    bud = stem > 0.86
    col[bud] = col[bud] * 0.55 + np.array([160, 140, 70], dtype=np.float32) * 0.45
    col += (micro(s, 454, 3.6))[:, :, None]
    return np.clip(col, 0, 255).astype(np.uint8)


MATERIALS = {
    "snow_soft": m_snow_soft,
    "snow_crust": m_snow_crust,
    "ice_cracked": m_ice_cracked,
    "bark_pine": m_bark_pine,
    "bark_birch": m_bark_birch,
    "pine_needles": m_pine_needles,
    "stone_granite": m_stone_granite,
    "stone_mossy": m_stone_mossy,
    "wood_plank": m_wood_plank,
    "wood_log_end": m_wood_log_end,
    "wood_carved": m_wood_carved,
    "metal_worn": m_metal_worn,
    "metal_rust": m_metal_rust,
    "fur_wolf": m_fur_wolf,
    "fur_deer": m_fur_deer,
    "cloth_canvas": m_cloth_canvas,
    "cloth_wool": m_cloth_wool,
    "ember_coal": m_ember_coal,
    "leather_tanned": m_leather_tanned,
    "meat_raw": m_meat_raw,
    "resin_amber": m_resin_amber,
    "crystal_ice": m_crystal_ice,
    "herb_leaf": m_herb_leaf,
    "dirt_frozen": m_dirt_frozen,
    "grass_dry": m_grass_dry,
    "bone_old": m_bone_old,
    "item_ore_raw": m_item_ore_raw,
    "item_meal_stew": m_item_meal_stew,
    "item_rope_coil": m_item_rope_coil,
    "item_torch_wrap": m_item_torch_wrap,
    "item_map_paper": m_item_map_paper,
    "item_herbs_bundle": m_item_herbs_bundle,
}


# ----------------------------------------------------------------------------- location splats
def location_splat(seed0, snow_hex, ground_hex, patch_hex, sparkle=False, cracky=False):
    def fn(s):
        snow = fbm(s, 5, 5, seed0)
        patch = fbm(s, 11, 4, seed0 + 1)
        detail = fbm(s, 55, 3, seed0 + 2)
        snow_c = hexc(snow_hex)
        ground_c = hexc(ground_hex)
        patch_c = hexc(patch_hex)
        t = np.clip((snow - 0.42) * 1.9, 0, 1)
        col = ground_c[None, None, :] * (1 - t[:, :, None]) + snow_c[None, None, :] * t[:, :, None]
        pm = np.clip((patch - 0.58) * 2.6, 0, 1) * (1.0 - t * 0.65)
        col = col * (1 - pm[:, :, None] * 0.55) + patch_c[None, None, :] * (pm[:, :, None] * 0.55)
        l = 0.68 + 0.42 * (0.5 * detail + 0.5 * snow)
        col *= l[:, :, None]
        col += (micro(s, seed0 + 3, 3.0))[:, :, None]
        if sparkle:
            spark = np.random.default_rng(seed0 + 4).random((s, s)) > 0.9988
            col[spark] = (255, 255, 255)
        if cracky:
            cracks = ridged(s, 8, 5, seed0 + 5)
            cm = cracks > 0.90
            col[cm] = col[cm] * 0.6 + np.array([170, 195, 215], dtype=np.float32) * 0.4
        return np.clip(col, 0, 255).astype(np.uint8)
    return fn


LOCATIONS = {
    "loc_sos_clearing": location_splat(301, "E9EFF7", "6E5238", "8C6B44", sparkle=True),
    "loc_frozen_lake": location_splat(311, "EAF2FA", "A8CBE2", "D8ECF8", sparkle=True, cracky=True),
    "loc_pine_forest": location_splat(321, "E2EAF4", "3E4A34", "5C6B4A", sparkle=True),
    "loc_abandoned_mine": location_splat(331, "DCE4EE", "4E4238", "6E5C46"),
    "loc_cliff_path": location_splat(341, "E6EDF6", "7A756E", "9A948C", sparkle=True),
    "loc_winter_valley": location_splat(351, "EDF2F9", "5E6A72", "8494A2", sparkle=True),
}


def main():
    print(f"Output: {OUT_DIR}", flush=True)
    total = 0.0
    for name, fn in MATERIALS.items():
        total += bake_material(name, fn)
    for name, fn in LOCATIONS.items():
        total += bake_material(name, fn, size=LOC_SIZE)
    print(f"TOTAL: {total:.1f} MB across {len(MATERIALS) + len(LOCATIONS)} textures", flush=True)
    if total < 200:
        print("WARNING: under the 200MB app-graphics budget!", flush=True)


if __name__ == "__main__":
    main()
