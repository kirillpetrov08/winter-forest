#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Winter Forest — scene/geometry parity port (tools/render/scene_and_render.py)

Faithful Python transliteration of the game's actual Kotlin geometry & shaders:
  - ProceduralTextures.kt  -> 10 tileable materials (FBM / ridged / Voronoi / micro-relief)
  - MeshBuilder.kt         -> oriented boxes, tapered cylinders, spruce bough tiers,
                              sculpted boulders, flames, wildlife & first-person tools
  - ForestGlRenderer.kt    -> terrain grid, static architecture, node batches, dynamic layer,
                              sky dome shader (mountains / stars / aurora), lighting uniforms

Renders authentic 1920x1080 landscape frames through tools/render/raster.cpp
(the exact WORLD_FRAGMENT_SHADER lighting model, perspective-correct, z-buffered).
"""
import math
import os
import struct
import subprocess
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
RASTER = os.path.join(HERE, "raster")
OUT_DIR = os.path.join(REPO, "store", "screenshots")
os.makedirs(OUT_DIR, exist_ok=True)

W, H = 1920, 1080

# ---------------------------------------------------------------------------
# 1. Deterministic noise port (Int32 wrap semantics identical to Kotlin)
# ---------------------------------------------------------------------------

def i32(v):
    v &= 0xFFFFFFFF
    return v - 0x100000000 if v >= 0x80000000 else v

def hash_float(x, y, seed):
    h = i32(i32(x * 374761393) + i32(y * 668265263) + i32(seed * 1442695041))
    hu = h & 0xFFFFFFFF
    h = i32((h ^ (hu >> 13)) * 1274126177)
    hu2 = h & 0xFFFFFFFF
    h = h ^ (hu2 >> 16)
    return (h & 0x7FFFFFFF) / 2147483647.0

def _lerp(a, b, t):
    return a + (b - a) * t

def gradient_noise(x, y, seed):
    x0 = math.floor(x); y0 = math.floor(y)
    fx = x - x0; fy = y - y0
    ux = fx * fx * (3.0 - 2.0 * fx)
    uy = fy * fy * (3.0 - 2.0 * fy)

    def grad_dot(ix, iy, dx, dy):
        ang = hash_float(int(ix), int(iy), seed) * TWO_PI
        return math.cos(ang) * dx + math.sin(ang) * dy

    n00 = grad_dot(x0, y0, fx, fy)
    n10 = grad_dot(x0 + 1, y0, fx - 1.0, fy)
    n01 = grad_dot(x0, y0 + 1, fx, fy - 1.0)
    n11 = grad_dot(x0 + 1, y0 + 1, fx - 1.0, fy - 1.0)
    nx0 = _lerp(n00, n10, ux)
    nx1 = _lerp(n01, n11, ux)
    return max(0.0, min(1.0, 0.5 + 0.5 * _lerp(nx0, nx1, uy)))

def fbm(x, y, octaves=4, seed=0):
    s = 0.0; amp = 0.5; freq = 1.0; norm = 0.0
    for oct in range(octaves):
        s += amp * gradient_noise(x * freq, y * freq, seed + oct * 977)
        norm += amp; amp *= 0.5; freq *= 2.0
    return max(0.0, min(1.0, s / max(norm, 1e-5)))

def ridged_fbm(x, y, octaves=4, seed=0):
    s = 0.0; amp = 0.5; freq = 1.0; norm = 0.0
    for oct in range(octaves):
        n = gradient_noise(x * freq, y * freq, seed + oct * 977)
        ridge = 1.0 - abs(n * 2.0 - 1.0)
        s += amp * ridge * ridge
        norm += amp; amp *= 0.5; freq *= 2.0
    return max(0.0, min(1.0, s / max(norm, 1e-5)))

def voronoi_cell(x, y, seed):
    ix = math.floor(x); iy = math.floor(y)
    f1 = 99.0; f2 = 99.0
    for oy in (-1, 0, 1):
        for ox in (-1, 0, 1):
            cx = ix + ox; cy = iy + oy
            jx = cx + hash_float(cx, cy, seed)
            jy = cy + hash_float(cx + 57, cy - 31, seed ^ 0x55AA)
            d = math.hypot(jx - x, jy - y)
            if d < f1:
                f2 = f1; f1 = d
            elif d < f2:
                f2 = d
    return (max(0.0, min(1.0, f1)), max(0.0, min(1.0, f2 - f1)))

TWO_PI = math.pi * 2.0

# ---------------------------------------------------------------------------
# 2. ProceduralTextures.generateArgbPixels port (256x256, palette-driven)
# ---------------------------------------------------------------------------

def pack_argb(a, r, g, b):
    ia = int(max(0.0, min(1.0, a)) * 255.0) & 0xFF
    ir = int(max(0.0, min(1.0, r)) * 255.0) & 0xFF
    ig = int(max(0.0, min(1.0, g)) * 255.0) & 0xFF
    ib = int(max(0.0, min(1.0, b)) * 255.0) & 0xFF
    return (ia << 24) | (ir << 16) | (ig << 8) | ib

def generate_texture_rgb(tex_id, palette, size=256, seed=1337):
    """Returns uint8 array (size, size, 3) RGB (alpha dropped for opaque samplers)."""
    snow_hex = palette["snowTintHex"]
    st_r = ((snow_hex >> 16) & 0xFF) / 255.0
    st_g = ((snow_hex >> 8) & 0xFF) / 255.0
    st_b = (snow_hex & 0xFF) / 255.0
    out = np.zeros((size, size, 3), dtype=np.uint8)
    inv = 1.0 / size

    for y in range(size):
        v = y * inv
        for x in range(size):
            u = x * inv
            if tex_id == "SNOW":
                macro = fbm(u * 4.5, v * 8.0, 5, seed)
                wave = 0.5 + 0.5 * math.sin((u * 2.5 + v * 13.5 + macro * 2.6) * TWO_PI)
                fine = fbm(u * 32.0, v * 32.0, 3, seed + 29)
                crystal = voronoi_cell(u * 30.0, v * 30.0, seed + 41)[0]
                sparkle = 0.18 if crystal < 0.11 else 0.0
                hollow = max(0.0, min(0.22, (0.52 - macro) * 0.55))
                luma = max(0.45, min(1.08, 0.74 + 0.15 * macro + 0.08 * wave + 0.06 * fine + sparkle))
                r = max(0.0, min(1.0, st_r * luma * (1.0 - hollow * 0.45)))
                g = max(0.0, min(1.0, st_g * luma * (1.0 - hollow * 0.16)))
                b = max(0.0, min(1.0, st_b * luma * (1.03 + hollow * 0.18)))
            elif tex_id == "WOOD_BARK":
                warp = fbm(u * 6.0, v * 2.5, 3, seed + 83) * 0.12
                ridge = ridged_fbm((u + warp) * 16.0, v * 3.8, 5, seed + 101)
                plates = voronoi_cell((u + warp) * 13.0, v * 4.2, seed + 211)
                crevice = max(0.08, min(1.0, plates[1] * 1.65))
                base = max(0.06, min(1.0, 0.16 + 0.46 * ridge * crevice + 0.20 * plates[0]))
                rime = max(0.0, min(0.52, (ridge - 0.68) * 2.6))
                r = max(0.0, min(1.0, 0.44 * base + rime * 0.78))
                g = max(0.0, min(1.0, 0.29 * base + rime * 0.84))
                b = max(0.0, min(1.0, 0.20 * base + rime * 0.92))
            elif tex_id == "PINE_FOLIAGE":
                cluster = voronoi_cell(u * 14.0, v * 14.0, seed + 313)
                needles = ridged_fbm(u * 28.0, v * 22.0, 4, seed + 307)
                depth = max(0.22, min(1.0, 0.35 + 0.65 * cluster[1] * 1.4))
                snow_line = 0.44 + (fbm(u * 9.0, v * 9.0, 3, seed + 331) - 0.5) * 0.34
                snow_mask = max(0.0, min(1.0, (snow_line - v) * 4.6))
                shade = (0.26 + 0.52 * needles) * depth
                r = _lerp(0.06 + 0.12 * shade, 0.92 * st_r * (0.86 + 0.14 * needles), snow_mask)
                g = _lerp(0.16 + 0.32 * shade, 0.95 * st_g * (0.88 + 0.12 * needles), snow_mask)
                b = _lerp(0.11 + 0.20 * shade, 0.99 * st_b, snow_mask)
            elif tex_id == "STONE":
                facets = voronoi_cell(u * 7.5, v * 7.5, seed + 409)
                micro = fbm(u * 26.0, v * 26.0, 4, seed + 419)
                strata = 0.5 + 0.5 * math.sin((v * 7.0 + micro * 1.4) * TWO_PI)
                crack = max(0.12, min(1.0, facets[1] * 2.1))
                quartz = max(0.0, min(0.35, (ridged_fbm(u * 15.0, v * 15.0, 3, seed + 433) - 0.78) * 3.5))
                luma = max(0.08, min(1.0, (0.24 + 0.34 * micro + 0.16 * strata) * crack + quartz))
                r = max(0.0, min(1.0, luma * 0.89)); g = max(0.0, min(1.0, luma * 0.92)); b = max(0.0, min(1.0, luma * 0.98))
            elif tex_id == "CLOTH":
                herring = 0.5 + 0.5 * math.sin(u * 42.0 + (v * 42.0 if ((y // 6) & 1) == 0 else -v * 42.0))
                cross = 0.5 + 0.5 * math.cos(v * 64.0)
                wear = fbm(u * 9.0, v * 9.0, 4, seed + 503)
                strap = 1.0 if abs(v - 0.5) < 0.08 else 0.0
                tone = max(0.14, min(1.0, 0.36 + 0.28 * herring + 0.14 * cross + 0.24 * wear))
                r = max(0.0, min(1.0, _lerp(0.66 * tone, 0.34 * tone, strap)))
                g = max(0.0, min(1.0, _lerp(0.42 * tone, 0.21 * tone, strap)))
                b = max(0.0, min(1.0, _lerp(0.28 * tone, 0.13 * tone, strap)))
            elif tex_id == "ICE":
                deep = voronoi_cell(u * 6.5, v * 6.5, seed + 607)
                fine = voronoi_cell(u * 17.0, v * 17.0, seed + 619)
                pcrack = 1.0 - max(0.0, min(1.0, deep[1] * 8.5))
                mcrack = (1.0 - max(0.0, min(1.0, fine[1] * 11.0))) * 0.55
                depth = fbm(u * 9.0, v * 9.0, 5, seed + 613)
                bubbles = 0.22 if fine[0] < 0.09 else 0.0
                cs = max(0.0, min(1.0, pcrack + mcrack + bubbles))
                r = max(0.0, min(1.0, 0.34 + 0.24 * depth + 0.56 * cs))
                g = max(0.0, min(1.0, 0.64 + 0.18 * depth + 0.34 * cs))
                b = max(0.0, min(1.0, 0.84 + 0.10 * depth + 0.16 * cs))
            elif tex_id == "TIMBER":
                row_pos = v * 5.0
                row_frac = row_pos - math.floor(row_pos)
                profile = max(0.18, min(1.0, math.sin(row_frac * math.pi)))
                seam = 0.38 if (row_frac < 0.06 or row_frac > 0.94) else 1.0
                knot = voronoi_cell(u * 4.5, v * 5.0, seed + 709)
                ring = 0.72 + 0.28 * math.sin(knot[0] * 90.0) if knot[0] < 0.16 else 1.0
                grain = fbm(u * 5.5, v * 34.0, 4, seed + 701)
                tone = max(0.08, min(1.0, (0.34 + 0.44 * grain) * profile * seam * ring))
                r = max(0.0, min(1.0, 0.62 * tone)); g = max(0.0, min(1.0, 0.41 * tone)); b = max(0.0, min(1.0, 0.24 * tone))
            elif tex_id == "FIRE_EMBER":
                dx = (u - 0.5) * 2.0
                dy = 1.0 - v
                turb = fbm(u * 8.0, v * 13.0, 4, seed + 809)
                ember = voronoi_cell(u * 12.0, v * 12.0, seed + 821)
                core = max(0.10, min(1.0, 1.20 - abs(dx) * (1.15 + (1.0 - dy) * 0.95) - (1.0 - dy) * 0.28 + turb * 0.38))
                spark = 0.30 if ember[0] < 0.10 else 0.0
                r = max(0.12, min(1.0, 1.0 * core + spark))
                g = max(0.05, min(1.0, 0.80 * core * core + 0.16 * core))
                b = max(0.02, min(1.0, 0.34 * core * core * core))
            elif tex_id == "METAL_STEEL":
                brushed = fbm(u * 40.0, v * 7.0, 4, seed + 907)
                hammer = voronoi_cell(u * 10.0, v * 10.0, seed + 919)
                bevel = 0.24 if (u > 0.78 or v < 0.16) else 0.0
                sheen = max(0.18, min(1.0, 0.46 + 0.28 * brushed + 0.16 * hammer[1] + bevel))
                r = max(0.0, min(1.0, sheen * 0.86)); g = max(0.0, min(1.0, sheen * 0.90)); b = max(0.0, min(1.0, sheen * 0.98))
            else:  # FUR_HIDE
                strands = ridged_fbm(u * 36.0, v * 11.0, 4, seed + 971)
                patch = fbm(u * 5.5, v * 5.5, 3, seed + 983)
                shade = max(0.16, min(1.0, 0.48 + 0.34 * strands + 0.22 * patch))
                r = max(0.0, min(1.0, 0.86 * shade)); g = max(0.0, min(1.0, 0.83 * shade)); b = max(0.0, min(1.0, 0.79 * shade))

            out[y, x, 0] = int(max(0.0, min(1.0, r)) * 255.0 + 0.5)
            out[y, x, 1] = int(max(0.0, min(1.0, g)) * 255.0 + 0.5)
            out[y, x, 2] = int(max(0.0, min(1.0, b)) * 255.0 + 0.5)
    return out

# ---------------------------------------------------------------------------
# 3. MeshBuilder port
# ---------------------------------------------------------------------------

class Mesh:
    def __init__(self):
        self.v = []  # flat floats: pos3 normal3 uv2 color4

    def vert(self, px, py, pz, nx, ny, nz, u, v, r=1.0, g=1.0, b=1.0, a=1.0):
        n = math.sqrt(nx * nx + ny * ny + nz * nz)
        if n > 1e-5:
            nx, ny, nz = nx / n, ny / n, nz / n
        else:
            nx, ny, nz = 0.0, 1.0, 0.0
        self.v.extend((px, py, pz, nx, ny, nz, u, v, r, g, b, a))

    @property
    def count(self):
        return len(self.v) // 12

    def quad(self, x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3,
             nx, ny, nz, us=1.0, vs=1.0, r=1.0, g=1.0, b=1.0, a=1.0):
        self.vert(x0, y0, z0, nx, ny, nz, 0.0, vs, r, g, b, a)
        self.vert(x1, y1, z1, nx, ny, nz, us, vs, r, g, b, a)
        self.vert(x2, y2, z2, nx, ny, nz, us, 0.0, r, g, b, a)
        self.vert(x0, y0, z0, nx, ny, nz, 0.0, vs, r, g, b, a)
        self.vert(x2, y2, z2, nx, ny, nz, us, 0.0, r, g, b, a)
        self.vert(x3, y3, z3, nx, ny, nz, 0.0, 0.0, r, g, b, a)

    def quad_shaded(self, x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3,
                    nx, ny, nz, us=1.0, vs=1.0, bottom_ao=0.78, r=1.0, g=1.0, b=1.0, a=1.0):
        br, bg, bb = r * bottom_ao, g * bottom_ao, b * bottom_ao
        self.vert(x0, y0, z0, nx, ny, nz, 0.0, vs, br, bg, bb, a)
        self.vert(x1, y1, z1, nx, ny, nz, us, vs, br, bg, bb, a)
        self.vert(x2, y2, z2, nx, ny, nz, us, 0.0, r, g, b, a)
        self.vert(x0, y0, z0, nx, ny, nz, 0.0, vs, br, bg, bb, a)
        self.vert(x2, y2, z2, nx, ny, nz, us, 0.0, r, g, b, a)
        self.vert(x3, y3, z3, nx, ny, nz, 0.0, 0.0, r, g, b, a)

    def add_box(self, cx, base_y, cz, sx, sy, sz, r=1.0, g=1.0, b=1.0, a=1.0):
        self.add_oriented_box(cx, base_y + sy * 0.5, cz, sx, sy, sz, 0.0, 0.0, 0.0, r, g, b, a)

    def add_oriented_box(self, cx, cy, cz, sx, sy, sz,
                         yaw=0.0, pitch=0.0, roll=0.0,
                         r=1.0, g=1.0, b=1.0, a=1.0, uv_density=0.65):
        cyaw, syaw = math.cos(yaw), math.sin(yaw)
        cp, sp = math.cos(pitch), math.sin(pitch)
        cr, sr = math.cos(roll), math.sin(roll)

        ax_x = cyaw * cr + syaw * sp * sr
        ax_y = cp * sr
        ax_z = -syaw * cr + cyaw * sp * sr
        ay_x = -cyaw * sr + syaw * sp * cr
        ay_y = cp * cr
        ay_z = syaw * sr + cyaw * sp * cr
        az_x = syaw * cp
        az_y = -sp
        az_z = cyaw * cp

        hx, hy, hz = sx * 0.5, sy * 0.5, sz * 0.5

        def P(lx, ly, lz):
            return (cx + ax_x * lx + ay_x * ly + az_x * lz,
                    cy + ax_y * lx + ay_y * ly + az_y * lz,
                    cz + ax_z * lx + ay_z * ly + az_z * lz)

        uf = max(sx * uv_density, 0.35)
        vsd = max(sy * uv_density, 0.35)
        ur = max(sz * uv_density, 0.35)

        p = [P(-hx, -hy, hz), P(hx, -hy, hz), P(hx, hy, hz), P(-hx, hy, hz)]
        self.quad_shaded(*p[0], *p[1], *p[2], *p[3], az_x, az_y, az_z, uf, vsd, 0.80, r, g, b, a)
        p = [P(hx, -hy, -hz), P(-hx, -hy, -hz), P(-hx, hy, -hz), P(hx, hy, -hz)]
        self.quad_shaded(*p[0], *p[1], *p[2], *p[3], -az_x, -az_y, -az_z, uf, vsd, 0.80, r, g, b, a)
        p = [P(hx, -hy, hz), P(hx, -hy, -hz), P(hx, hy, -hz), P(hx, hy, hz)]
        self.quad_shaded(*p[0], *p[1], *p[2], *p[3], ax_x, ax_y, ax_z, ur, vsd, 0.80, r, g, b, a)
        p = [P(-hx, -hy, -hz), P(-hx, -hy, hz), P(-hx, hy, hz), P(-hx, hy, -hz)]
        self.quad_shaded(*p[0], *p[1], *p[2], *p[3], -ax_x, -ax_y, -ax_z, ur, vsd, 0.80, r, g, b, a)
        p = [P(-hx, hy, hz), P(hx, hy, hz), P(hx, hy, -hz), P(-hx, hy, -hz)]
        self.quad(*p[0], *p[1], *p[2], *p[3], ay_x, ay_y, ay_z, uf, ur, r * 1.04, g * 1.04, b * 1.05, a)
        p = [P(-hx, -hy, -hz), P(hx, -hy, -hz), P(hx, -hy, hz), P(-hx, -hy, hz)]
        self.quad(*p[0], *p[1], *p[2], *p[3], -ay_x, -ay_y, -ay_z, uf, ur, r * 0.62, g * 0.62, b * 0.66, a)

    def add_tapered_cylinder(self, x0, y0, z0, x1, y1, z1, r0, r1, segments=8,
                             r=1.0, g=1.0, b=1.0, a=1.0, cap_ends=True):
        dx, dy, dz = x1 - x0, y1 - y0, z1 - z0
        ln = math.sqrt(dx * dx + dy * dy + dz * dz)
        if ln < 1e-4:
            return
        ux, uy, uz = dx / ln, dy / ln, dz / ln
        ref_x, ref_y = (0.0, 1.0) if abs(uy) < 0.92 else (1.0, 0.0)
        bx, by, bz = ref_y * uz, -ref_x * uz, ref_x * uy - ref_y * ux
        bl = math.sqrt(bx * bx + by * by + bz * bz)
        bl = bl if bl > 1e-5 else 1e-5
        bx, by, bz = bx / bl, by / bl, bz / bl
        tx, ty, tz = uy * bz - uz * by, uz * bx - ux * bz, ux * by - uy * bx

        step = TWO_PI / segments
        v_tile = max(ln * 0.45, 0.5)
        for i in range(segments):
            a0, a1 = i * step, (i + 1) * step
            c0, s0 = math.cos(a0), math.sin(a0)
            c1, s1 = math.cos(a1), math.sin(a1)
            n0x, n0y, n0z = bx * c0 + tx * s0, by * c0 + ty * s0, bz * c0 + tz * s0
            n1x, n1y, n1z = bx * c1 + tx * s1, by * c1 + ty * s1, bz * c1 + tz * s1
            p00 = (x0 + n0x * r0, y0 + n0y * r0, z0 + n0z * r0)
            p10 = (x0 + n1x * r0, y0 + n1y * r0, z0 + n1z * r0)
            p11 = (x1 + n1x * r1, y1 + n1y * r1, z1 + n1z * r1)
            p01 = (x1 + n0x * r1, y1 + n0y * r1, z1 + n0z * r1)
            u0 = i / segments * 2.0
            u1 = (i + 1) / segments * 2.0
            self.vert(*p00, n0x, n0y, n0z, u0, v_tile, r * 0.78, g * 0.78, b * 0.80, a)
            self.vert(*p10, n1x, n1y, n1z, u1, v_tile, r * 0.78, g * 0.78, b * 0.80, a)
            self.vert(*p11, n1x, n1y, n1z, u1, 0.0, r, g, b, a)
            self.vert(*p00, n0x, n0y, n0z, u0, v_tile, r * 0.78, g * 0.78, b * 0.80, a)
            self.vert(*p11, n1x, n1y, n1z, u1, 0.0, r, g, b, a)
            self.vert(*p01, n0x, n0y, n0z, u0, 0.0, r, g, b, a)
            if cap_ends and r1 > 0.02:
                self.vert(x1, y1, z1, ux, uy, uz, 0.5, 0.5, r * 1.06, g * 1.02, b * 0.96, a)
                self.vert(*p01, ux, uy, uz, 0.5 + c0 * 0.5, 0.5 + s0 * 0.5, r, g, b, a)
                self.vert(*p11, ux, uy, uz, 0.5 + c1 * 0.5, 0.5 + s1 * 0.5, r, g, b, a)

    def add_spruce_bough_tier(self, cx, base_y, cz, radius, height, tips=9,
                              twist=0.0, sway_x=0.0, r=1.0, g=1.0, b=1.0):
        total = tips * 2
        step = TWO_PI / total
        hyp = math.hypot(radius, height)
        slope_y = radius / hyp
        slope_xz = height / hyp
        for i in range(total):
            a0 = twist + i * step
            a1 = twist + (i + 1) * step
            rad0 = radius * (1.06 if i % 2 == 0 else 0.74)
            rad1 = radius * (1.06 if (i + 1) % 2 == 0 else 0.74)
            drop0 = -0.10 * height if i % 2 == 0 else 0.08 * height
            drop1 = -0.10 * height if (i + 1) % 2 == 0 else 0.08 * height
            x0 = cx + sway_x * 0.35 + math.cos(a0) * rad0
            z0 = cz + math.sin(a0) * rad0
            x1 = cx + sway_x * 0.35 + math.cos(a1) * rad1
            z1 = cz + math.sin(a1) * rad1
            mid = (a0 + a1) * 0.5
            nx, nz = math.cos(mid) * slope_xz, math.sin(mid) * slope_xz
            self.vert(x0, base_y + drop0, z0, nx, slope_y, nz, 0.0, 1.0, r * 0.76, g * 0.80, b * 0.86, 1.0)
            self.vert(x1, base_y + drop1, z1, nx, slope_y, nz, 1.0, 1.0, r * 0.76, g * 0.80, b * 0.86, 1.0)
            self.vert(cx + sway_x, base_y + height, cz, nx, slope_y, nz, 0.5, 0.0, r * 1.05, g * 1.05, b * 1.06, 1.0)
            self.vert(cx, base_y + height * 0.14, cz, 0.0, -1.0, 0.0, 0.5, 0.85, r * 0.28, g * 0.32, b * 0.34, 1.0)
            self.vert(x1, base_y + drop1, z1, 0.0, -1.0, 0.0, 1.0, 1.0, r * 0.36, g * 0.40, b * 0.44, 1.0)
            self.vert(x0, base_y + drop0, z0, 0.0, -1.0, 0.0, 0.0, 1.0, r * 0.36, g * 0.40, b * 0.44, 1.0)

    def add_sculpted_boulder(self, cx, base_y, cz, rx, ry, rz, seed,
                             r=1.0, g=1.0, b=1.0, snow_cap=True):
        lat_bands, lon_segs = 5, 8

        def disp(lat_i, lon_i):
            v_ = lat_i / lat_bands
            phi = (v_ - 0.5) * math.pi
            theta = (lon_i % lon_segs) / lon_segs * TWO_PI
            cphi = math.cos(phi)
            dx_, dy_, dz_ = cphi * math.cos(theta), math.sin(phi), cphi * math.sin(theta)
            n = fbm(dx_ * 2.1 + 3.1, dz_ * 2.1 + dy_ * 1.7, 3, seed)
            d = 0.72 + 0.48 * n
            return (cx + dx_ * rx * d, base_y + ry * 0.42 + dy_ * ry * d * 0.68, cz + dz_ * rz * d)

        for i in range(lat_bands):
            for j in range(lon_segs):
                p00 = disp(i, j); p10 = disp(i, j + 1); p11 = disp(i + 1, j + 1); p01 = disp(i + 1, j)
                e1 = (p10[0] - p00[0], p10[1] - p00[1], p10[2] - p00[2])
                e2 = (p01[0] - p00[0], p01[1] - p00[1], p01[2] - p00[2])
                nx = e1[1] * e2[2] - e1[2] * e2[1]
                ny = e1[2] * e2[0] - e1[0] * e2[2]
                nz = e1[0] * e2[1] - e1[1] * e2[0]
                mid = ((p00[0] + p11[0]) * 0.5 - cx, (p00[1] + p11[1]) * 0.5 - (base_y + ry * 0.42), (p00[2] + p11[2]) * 0.5 - cz)
                if nx * mid[0] + ny * mid[1] + nz * mid[2] < 0:
                    nx, ny, nz = -nx, -ny, -nz
                n_len = math.sqrt(nx * nx + ny * ny + nz * nz) or 1e-5
                up = ny / n_len
                snow_mix = max(0.0, min(0.85, (up - 0.42) * 2.2)) if snow_cap else 0.0
                ao = max(0.58, min(1.0, 0.64 + 0.36 * (i / lat_bands)))
                fr = r * ao * (1 - snow_mix) + 0.94 * snow_mix
                fg = g * ao * (1 - snow_mix) + 0.97 * snow_mix
                fb_ = b * ao * (1 - snow_mix) + 1.00 * snow_mix
                self.quad(*p00, *p10, *p11, *p01, nx, ny, nz, 0.85, 0.85, fr, fg, fb_, 1.0)

    def add_crossed_flame(self, cx, base_y, cz, width, height, sway_x=0.0,
                          r=1.0, g=0.88, b=0.48, a=0.95):
        for i in range(3):
            ang = i / 3.0 * math.pi
            dx, dz = math.cos(ang) * width * 0.5, math.sin(ang) * width * 0.5
            nx, nz = -math.sin(ang), math.cos(ang)
            self.vert(cx - dx, base_y, cz - dz, nx, 0.25, nz, 0.0, 1.0, r, g * 0.72, b * 0.45, a)
            self.vert(cx + dx, base_y, cz + dz, nx, 0.25, nz, 1.0, 1.0, r, g * 0.72, b * 0.45, a)
            self.vert(cx + sway_x, base_y + height, cz, nx, 0.25, nz, 0.5, 0.04, 1.0, 0.96, 0.72, a * 0.92)
            self.vert(cx + dx, base_y, cz + dz, -nx, 0.25, -nz, 0.0, 1.0, r, g * 0.72, b * 0.45, a)
            self.vert(cx - dx, base_y, cz - dz, -nx, 0.25, -nz, 1.0, 1.0, r, g * 0.72, b * 0.45, a)
            self.vert(cx + sway_x, base_y + height, cz, -nx, 0.25, -nz, 0.5, 0.04, 1.0, 0.96, 0.72, a * 0.92)

    def add_shadow_disc(self, cx, y, cz, radius, alpha=0.38):
        segs = 12
        step = TWO_PI / segs
        for i in range(segs):
            a0, a1 = i * step, (i + 1) * step
            self.vert(cx, y + 0.035, cz, 0.0, 1.0, 0.0, 0.5, 0.5, 0.04, 0.07, 0.13, alpha)
            self.vert(cx + math.cos(a0) * radius, y + 0.035, cz + math.sin(a0) * radius, 0.0, 1.0, 0.0, 0.0, 0.0, 0.04, 0.07, 0.13, 0.0)
            self.vert(cx + math.cos(a1) * radius, y + 0.035, cz + math.sin(a1) * radius, 0.0, 1.0, 0.0, 0.0, 1.0, 0.04, 0.07, 0.13, 0.0)

    def add_cone(self, cx, base_y, cz, radius, height, segments=8, r=1.0, g=1.0, b=1.0, a=1.0):
        step = TWO_PI / segments
        hyp = math.hypot(radius, height)
        slope_y = radius / hyp
        slope_xz = height / hyp
        for i in range(segments):
            a0, a1 = i * step, (i + 1) * step
            x0, z0 = cx + math.cos(a0) * radius, cz + math.sin(a0) * radius
            x1, z1 = cx + math.cos(a1) * radius, cz + math.sin(a1) * radius
            mid = (a0 + a1) * 0.5
            nx, nz = math.cos(mid) * slope_xz, math.sin(mid) * slope_xz
            self.vert(x0, base_y, z0, nx, slope_y, nz, 0.0, 1.0, r * 0.84, g * 0.84, b * 0.88, a)
            self.vert(x1, base_y, z1, nx, slope_y, nz, 1.0, 1.0, r * 0.84, g * 0.84, b * 0.88, a)
            self.vert(cx, base_y + height, cz, nx, slope_y, nz, 0.5, 0.0, r, g, b, a)


def add_wildlife(fur, bark, glow, kind, x, ground_y, z, heading, anim_phase):
    yaw = -heading + math.pi * 0.5
    fwd_x, fwd_z = math.cos(heading), math.sin(heading)
    right_x, right_z = -fwd_z, fwd_x
    stride = math.sin(anim_phase) * 0.34

    if kind == "SNOW_HARE":
        hop = abs(math.sin(anim_phase * 0.85)) * 0.14
        by = ground_y + 0.24 + hop
        fur.add_shadow_disc(x, ground_y, z, 0.42, 0.30)
        fur.add_oriented_box(x, by, z, 0.26, 0.24, 0.42, yaw, -0.10, 0.0, 0.98, 0.98, 1.0)
        hx, hz = x + fwd_x * 0.24, z + fwd_z * 0.24
        fur.add_oriented_box(hx, by + 0.12, hz, 0.19, 0.18, 0.20, yaw, 0.0, 0.0, 0.99, 0.99, 1.0)
        fur.add_oriented_box(hx + right_x * 0.055, by + 0.31, hz + right_z * 0.055, 0.045, 0.22, 0.06, yaw, -0.22, 0.10, 0.94, 0.95, 0.98)
        fur.add_oriented_box(hx - right_x * 0.055, by + 0.31, hz - right_z * 0.055, 0.045, 0.22, 0.06, yaw, -0.22, -0.10, 0.94, 0.95, 0.98)
        fur.add_oriented_box(x - fwd_x * 0.24, by + 0.04, z - fwd_z * 0.24, 0.12, 0.12, 0.12, yaw, 0.0, 0.0, 1.0, 1.0, 1.0)
    elif kind == "NORTHERN_DEER":
        torso_y = ground_y + 1.02
        fur.add_shadow_disc(x, ground_y, z, 1.05, 0.36)
        fur.add_oriented_box(x, torso_y, z, 0.56, 0.58, 1.38, yaw, 0.0, 0.0, 0.68, 0.53, 0.40)
        fur.add_oriented_box(x + fwd_x * 0.56, torso_y + 0.36, z + fwd_z * 0.56, 0.30, 0.66, 0.32, yaw, 0.26, 0.0, 0.84, 0.78, 0.72)
        hx, hz, hy = x + fwd_x * 0.78, z + fwd_z * 0.78, torso_y + 0.66
        fur.add_oriented_box(hx, hy, hz, 0.26, 0.26, 0.48, yaw, -0.12, 0.0, 0.66, 0.52, 0.40)
        for side in (-1.0, 1.0):
            rx, rz = hx + right_x * 0.11 * side, hz + right_z * 0.11 * side
            bark.add_tapered_cylinder(rx, hy + 0.10, rz,
                                      rx + right_x * 0.26 * side - fwd_x * 0.12, hy + 0.58,
                                      rz + right_z * 0.26 * side - fwd_z * 0.12, 0.032, 0.016, 6, 0.90, 0.84, 0.74)
            bark.add_tapered_cylinder(rx + right_x * 0.14 * side, hy + 0.34, rz + right_z * 0.14 * side,
                                      rx + right_x * 0.24 * side + fwd_x * 0.22, hy + 0.54,
                                      rz + right_z * 0.24 * side + fwd_z * 0.22, 0.022, 0.012, 5, 0.92, 0.86, 0.76)
        for lx, lz, swing in ((0.20, 0.46, stride), (-0.20, 0.46, -stride), (0.20, -0.46, -stride), (-0.20, -0.46, stride)):
            px, pz = x + right_x * lx + fwd_x * lz, z + right_z * lx + fwd_z * lz
            fur.add_oriented_box(px, ground_y + 0.44, pz, 0.11, 0.88, 0.12, yaw, swing, 0.0, 0.56, 0.43, 0.33)
    else:  # TIMBER_WOLF
        torso_y = ground_y + 0.60
        fur.add_shadow_disc(x, ground_y, z, 0.88, 0.38)
        fur.add_oriented_box(x, torso_y, z, 0.42, 0.42, 1.08, yaw, 0.0, 0.0, 0.46, 0.48, 0.53)
        fur.add_oriented_box(x + fwd_x * 0.24, torso_y + 0.06, z + fwd_z * 0.24, 0.46, 0.46, 0.44, yaw, 0.0, 0.0, 0.54, 0.56, 0.60)
        hx, hz, hy = x + fwd_x * 0.64, z + fwd_z * 0.64, torso_y + 0.14
        fur.add_oriented_box(hx, hy, hz, 0.28, 0.26, 0.40, yaw, -0.08, 0.0, 0.50, 0.52, 0.57)
        fur.add_oriented_box(hx + right_x * 0.09, hy + 0.19, hz + right_z * 0.09, 0.07, 0.13, 0.07, yaw, 0.0, 0.0, 0.36, 0.38, 0.42)
        fur.add_oriented_box(hx - right_x * 0.09, hy + 0.19, hz - right_z * 0.09, 0.07, 0.13, 0.07, yaw, 0.0, 0.0, 0.36, 0.38, 0.42)
        fur.add_oriented_box(x - fwd_x * 0.66, torso_y - 0.06, z - fwd_z * 0.66, 0.14, 0.15, 0.46, yaw, -0.45, 0.0, 0.40, 0.42, 0.47)
        ex, ez = hx + fwd_x * 0.19, hz + fwd_z * 0.19
        glow.add_box(ex + right_x * 0.065, hy + 0.03, ez + right_z * 0.065, 0.04, 0.04, 0.04, 1.0, 0.72, 0.15, 1.0)
        glow.add_box(ex - right_x * 0.065, hy + 0.03, ez - right_z * 0.065, 0.04, 0.04, 0.04, 1.0, 0.72, 0.15, 1.0)
        for lx, lz, swing in ((0.15, 0.36, stride * 1.2), (-0.15, 0.36, -stride * 1.2),
                              (0.15, -0.36, -stride * 1.2), (-0.15, -0.36, stride * 1.2)):
            px, pz = x + right_x * lx + fwd_x * lz, z + right_z * lx + fwd_z * lz
            fur.add_oriented_box(px, ground_y + 0.26, pz, 0.095, 0.52, 0.10, yaw, swing, 0.0, 0.42, 0.44, 0.49)


def add_fp_tool(timber, metal, cloth, fire, tool, torch_lit, cam_x, cam_y, cam_z,
                yaw, pitch, swing_progress, walk_bob, time_sec):
    fwd_x = math.sin(yaw) * math.cos(pitch)
    fwd_y = math.sin(pitch)
    fwd_z = -math.cos(yaw) * math.cos(pitch)
    right_x, right_z = math.cos(yaw), math.sin(yaw)

    bob_x = math.sin(walk_bob * 0.5) * 0.022
    bob_y = abs(math.cos(walk_bob * 0.5)) * 0.020 + math.sin(time_sec * 2.1) * 0.006
    chop = math.sin(swing_progress * math.pi)

    base_x = cam_x + fwd_x * (0.58 + chop * 0.24) + right_x * (0.34 - chop * 0.10 + bob_x)
    base_y = cam_y - 0.34 + fwd_y * 0.52 - chop * 0.16 - bob_y
    base_z = cam_z + fwd_z * (0.58 + chop * 0.24) + right_z * (0.34 - chop * 0.10 + bob_x)

    tool_yaw = -yaw
    tool_pitch = pitch + 0.22 - chop * 1.05

    tip_dx = math.sin(yaw) * math.sin(0.28 + chop * 0.85)
    tip_dy = math.cos(0.28 + chop * 0.85)
    tip_dz = -math.cos(yaw) * math.sin(0.28 + chop * 0.85)

    if tool == "AXE":
        top_x = base_x + tip_dx * 0.50
        top_y = base_y + tip_dy * 0.50
        top_z = base_z + tip_dz * 0.50
        timber.add_tapered_cylinder(base_x, base_y, base_z, top_x, top_y, top_z, 0.026, 0.022, 8, 0.88, 0.72, 0.52)
        cloth.add_tapered_cylinder(base_x + tip_dx * 0.08, base_y + tip_dy * 0.08, base_z + tip_dz * 0.08,
                                   base_x + tip_dx * 0.22, base_y + tip_dy * 0.22, base_z + tip_dz * 0.22, 0.030, 0.029, 8)
        metal.add_oriented_box(top_x, top_y - 0.03, top_z, 0.055, 0.10, 0.16, tool_yaw, tool_pitch, 0.0, 0.86, 0.90, 0.96)
        metal.add_oriented_box(top_x + math.sin(yaw) * 0.09, top_y - 0.05, top_z - math.cos(yaw) * 0.09,
                               0.020, 0.16, 0.13, tool_yaw, tool_pitch, 0.0, 0.98, 0.99, 1.0)
    elif tool == "PICKAXE":
        top_x = base_x + tip_dx * 0.52
        top_y = base_y + tip_dy * 0.52
        top_z = base_z + tip_dz * 0.52
        timber.add_tapered_cylinder(base_x, base_y, base_z, top_x, top_y, top_z, 0.027, 0.024, 8, 0.80, 0.65, 0.45)
        metal.add_oriented_box(top_x, top_y - 0.02, top_z, 0.055, 0.065, 0.34, tool_yaw, tool_pitch, 0.0, 0.88, 0.92, 0.98)
        metal.add_oriented_box(top_x + math.sin(yaw) * 0.15, top_y - 0.05, top_z - math.cos(yaw) * 0.15,
                               0.032, 0.045, 0.14, tool_yaw, tool_pitch - 0.35, 0.0, 0.96, 0.98, 1.0)
    elif tool == "SPEAR":
        top_x = base_x + fwd_x * 0.62 + tip_dx * 0.26
        top_y = base_y + fwd_y * 0.62 + tip_dy * 0.26
        top_z = base_z + fwd_z * 0.62 + tip_dz * 0.26
        timber.add_tapered_cylinder(base_x - fwd_x * 0.20, base_y - 0.10, base_z - fwd_z * 0.20,
                                    top_x, top_y, top_z, 0.023, 0.018, 8, 0.84, 0.68, 0.48)
        cloth.add_tapered_cylinder(base_x, base_y, base_z, base_x + fwd_x * 0.18, base_y + 0.08, base_z + fwd_z * 0.18, 0.027, 0.026, 8)
        metal.add_tapered_cylinder(top_x, top_y, top_z,
                                   top_x + fwd_x * 0.24, top_y + fwd_y * 0.24 + 0.05, top_z + fwd_z * 0.24,
                                   0.036, 0.004, 6, 0.98, 0.99, 1.0)
    else:  # TORCH
        top_x = base_x + tip_dx * 0.48
        top_y = base_y + tip_dy * 0.48
        top_z = base_z + tip_dz * 0.48
        timber.add_tapered_cylinder(base_x, base_y, base_z, top_x, top_y, top_z, 0.028, 0.034, 8, 0.62, 0.44, 0.28)
        cloth.add_tapered_cylinder(top_x - tip_dx * 0.10, top_y - tip_dy * 0.10, top_z - tip_dz * 0.10,
                                   top_x + tip_dx * 0.03, top_y + tip_dy * 0.03, top_z + tip_dz * 0.03, 0.044, 0.048, 8, 0.45, 0.30, 0.20)
        if torch_lit:
            flick = 0.88 + 0.18 * math.sin(time_sec * 13.5)
            fire.add_crossed_flame(top_x, top_y + 0.02, top_z, 0.20, 0.34 * flick, math.sin(time_sec * 9.0) * 0.025)


# ---------------------------------------------------------------------------
# 4. World / terrain / architecture port
# ---------------------------------------------------------------------------

LOCATIONS = {
    "BASE_CLEARING": {
        "palette": dict(skyZenithHex=0x1A3B5C, skyHorizonHex=0x8CAFC9, fogHex=0xB8CFE0,
                        snowTintHex=0xEAF3FA, sunLightHex=0xFFE6C7, fogNear=18.0, fogFar=58.0),
        "snowfallChance": 0.20, "coldMultiplier": 0.85, "requiresLight": False,
    },
    "DENSE_SPRUCE": {
        "palette": dict(skyZenithHex=0x13292A, skyHorizonHex=0x5C787E, fogHex=0x8BA3A8,
                        snowTintHex=0xCFE0E5, sunLightHex=0xD7E8EC, fogNear=12.0, fogFar=42.0),
        "snowfallChance": 0.80, "coldMultiplier": 1.15, "requiresLight": False,
    },
    "FROZEN_LAKE": {
        "palette": dict(skyZenithHex=0x163A59, skyHorizonHex=0x9AD0E8, fogHex=0xC4E4F2,
                        snowTintHex=0xDDF2FC, sunLightHex=0xEAF8FF, fogNear=16.0, fogFar=54.0),
        "snowfallChance": 0.45, "coldMultiplier": 1.55, "requiresLight": False,
    },
    "ABANDONED_MINE": {
        "palette": dict(skyZenithHex=0x10131A, skyHorizonHex=0x2A303D, fogHex=0x232833,
                        snowTintHex=0x8F99A8, sunLightHex=0x9BA6B8, fogNear=4.5, fogFar=19.0),
        "snowfallChance": 0.15, "coldMultiplier": 1.25, "requiresLight": True,
    },
    "HUNTERS_CABIN": {
        "palette": dict(skyZenithHex=0x241E38, skyHorizonHex=0xB88B72, fogHex=0xCBB2A6,
                        snowTintHex=0xEFE6E1, sunLightHex=0xFFD2A6, fogNear=15.0, fogFar=50.0),
        "snowfallChance": 0.35, "coldMultiplier": 1.35, "requiresLight": False,
    },
}

NODE_KINDS = {  # id -> (kind, resourceType)
}

def terrain_height(loc, x, z):
    center = math.hypot(x, z)
    if loc == "FROZEN_LAKE" and center < 23.5:
        return -0.26 + 0.03 * math.sin(x * 0.30) * math.cos(z * 0.30)
    flatten = max(0.0, min(1.0, (center - 6.2) / 8.0))
    micro = 0.14 * math.sin(x * 0.38 + z * 0.21) + 0.08 * math.cos(x * 0.62 - z * 0.44)
    if loc == "BASE_CLEARING":
        raw = 0.85 * math.sin(x * 0.085) * math.cos(z * 0.075) + 0.38 * math.sin((x + z) * 0.15) + micro
    elif loc == "DENSE_SPRUCE":
        raw = 1.35 * math.sin(x * 0.105 + 1.1) * math.cos(z * 0.095) + 0.52 * math.cos(x * 0.20) + micro
    elif loc == "FROZEN_LAKE":
        raw = 1.55 * max(0.0, (center - 22.5) / 13.0) + 0.28 * math.sin(x * 0.14) + micro * 0.6
    elif loc == "ABANDONED_MINE":
        raw = 0.95 * math.cos(x * 0.125) * math.sin(z * 0.115) + 0.42 * math.sin(z * 0.25) + micro * 0.5
    else:
        raw = 1.55 * math.sin(x * 0.078 + 0.6) * math.cos(z * 0.088 - 0.4) + 0.58 * math.sin(x * 0.18) + micro
    return raw * flatten

def terrain_border(loc, x, z):
    base = terrain_height(loc, x, z)
    radial = math.hypot(x, z)
    edge = max(abs(x), abs(z))
    ridge = max(0.0, (edge - 38.2) * 1.55) + 0.55 * math.sin(radial * 0.45) * (max(0.0, min(4.0, edge - 38.2)) * 0.25)
    return base + ridge


def build_terrain(loc, half_extent=48.0, steps=68):
    g = steps + 1
    cell = (half_extent * 2.0) / steps
    heights = np.zeros((g, g), dtype=np.float64)
    for gz in range(g):
        z = -half_extent + gz * cell
        for gx in range(g):
            x = -half_extent + gx * cell
            heights[gz, gx] = terrain_border(loc, x, z)

    m = Mesh()
    uv_scale = 0.52
    for gz in range(steps):
        z0 = -half_extent + gz * cell
        z1 = z0 + cell
        for gx in range(steps):
            x0 = -half_extent + gx * cell
            x1 = x0 + cell
            i00 = (gz, gx); i10 = (gz, gx + 1); i01 = (gz + 1, gx); i11 = (gz + 1, gx + 1)
            u0 = gx * uv_scale; v0 = gz * uv_scale
            u1 = (gx + 1) * uv_scale; v1 = (gz + 1) * uv_scale

            def vert_at(idx, px, pz, u, vv):
                gz_i, gx_i = idx
                h_l = heights[gz_i, max(gx_i - 1, 0)]
                h_r = heights[gz_i, min(gx_i + 1, steps)]
                h_d = heights[max(gz_i - 1, 0), gx_i]
                h_u = heights[min(gz_i + 1, steps), gx_i]
                h_c = heights[gz_i, gx_i]
                dx = (h_l - h_r) / (2.0 * cell)
                dz = (h_d - h_u) / (2.0 * cell)
                inv = 1.0 / math.sqrt(dx * dx + 1.0 + dz * dz)
                nx, ny, nz = dx * inv, inv, dz * inv
                avg = (h_l + h_r + h_d + h_u) * 0.25
                cavity = max(-0.18, min(0.10, (h_c - avg) * 0.55))
                x_c = px
                z_c = pz
                on_path = 0.90 if (-36.0 <= z_c <= 7.0 and abs(x_c - math.sin(z_c * 0.14) * 1.4) < 1.35) else 1.0
                ao = max(0.74, min(1.03, (0.94 + cavity) * on_path))
                m.vert(px, h_c, pz, nx, ny, nz, u, vv, ao * 0.99, ao, min(1.04, ao * 1.02), 1.0)

            vert_at(i00, x0, z0, u0, v0)
            vert_at(i01, x0, z1, u0, v1)
            vert_at(i11, x1, z1, u1, v1)
            vert_at(i00, x0, z0, u0, v0)
            vert_at(i11, x1, z1, u1, v1)
            vert_at(i10, x1, z0, u1, v0)
    return m

# ---------------------------------------------------------------------------
# 5. Static architecture port (ForestGlRenderer private builders)
# ---------------------------------------------------------------------------

def add_pine_tree(bark, foliage, x, y, z, scale, seed, draw_shadow=True):
    if draw_shadow:
        foliage.add_shadow_disc(x, y, z, 2.25 * scale, 0.36)
    bark.add_tapered_cylinder(x, y - 0.1, z, x, y + 4.8 * scale, z, 0.30 * scale, 0.09 * scale, 8)
    for r_ in range(4):
        ra = r_ * 1.57 + seed * 0.2
        bark.add_tapered_cylinder(x + math.cos(ra) * 0.52 * scale, y - 0.06, z + math.sin(ra) * 0.52 * scale,
                                  x + math.cos(ra) * 0.14 * scale, y + 0.42 * scale, z + math.sin(ra) * 0.14 * scale,
                                  0.09 * scale, 0.07 * scale, 5)
    tiers = [(1.15, 2.35, 1.55), (2.20, 1.98, 1.48), (3.15, 1.58, 1.38), (4.02, 1.18, 1.25), (4.78, 0.72, 1.15)]
    for ti, (ty, trad, thh) in enumerate(tiers):
        foliage.add_spruce_bough_tier(x, y + ty * scale, z, trad * scale, thh * scale,
                                      tips=9 if draw_shadow else 7,
                                      twist=seed * 0.31 + ti * 0.42)


def add_log_cabin(timber, bark, stone, snow_roof, metal, glow, shadow,
                  cx, base_y, cz, width, wall_height, depth):
    shadow.add_shadow_disc(cx, base_y, cz, width * 0.85, 0.42)
    stone.add_box(cx, base_y - 0.12, cz, width + 0.24, 0.34, depth + 0.24)
    timber.add_box(cx, base_y + 0.22, cz, width, wall_height, depth)
    courses = 7
    step_y = wall_height / courses
    hw, hd = width * 0.5, depth * 0.5
    for i in range(courses):
        ly = base_y + 0.30 + i * step_y
        rl = step_y * 0.48
        bark.add_tapered_cylinder(cx - hw - 0.28, ly, cz + hd, cx + hw + 0.28, ly, cz + hd, rl, rl, 7)
        bark.add_tapered_cylinder(cx - hw - 0.28, ly, cz - hd, cx + hw + 0.28, ly, cz - hd, rl, rl, 7)
        bark.add_tapered_cylinder(cx - hw, ly + step_y * 0.5, cz - hd - 0.28, cx - hw, ly + step_y * 0.5, cz + hd + 0.28, rl, rl, 7)
        bark.add_tapered_cylinder(cx + hw, ly + step_y * 0.5, cz - hd - 0.28, cx + hw, ly + step_y * 0.5, cz + hd + 0.28, rl, rl, 7)
    roof_y = base_y + 0.22 + wall_height
    slope_len = width * 0.66
    ra = 0.56
    timber.add_oriented_box(cx - width * 0.28, roof_y + 0.62, cz, slope_len, 0.14, depth + 0.72, 0.0, 0.0, ra)
    timber.add_oriented_box(cx + width * 0.28, roof_y + 0.62, cz, slope_len, 0.14, depth + 0.72, 0.0, 0.0, -ra)
    snow_roof.add_oriented_box(cx - width * 0.28, roof_y + 0.76, cz, slope_len + 0.08, 0.18, depth + 0.80, 0.0, 0.0, ra)
    snow_roof.add_oriented_box(cx + width * 0.28, roof_y + 0.76, cz, slope_len + 0.08, 0.18, depth + 0.80, 0.0, 0.0, -ra)
    bark.add_tapered_cylinder(cx, roof_y + 1.24, cz - hd - 0.42, cx, roof_y + 1.24, cz + hd + 0.42, 0.13, 0.13, 8)
    stone.add_box(cx + hw * 0.55, roof_y + 0.35, cz + hd * 0.25, 0.62, 1.55, 0.62)
    timber.add_box(cx - 0.85, base_y + 0.24, cz + hd + 0.05, 0.96, 1.72, 0.10, 0.72, 0.52, 0.34)
    metal.add_box(cx - 0.50, base_y + 1.05, cz + hd + 0.12, 0.09, 0.09, 0.06)
    timber.add_box(cx - 0.85, base_y, cz + hd + 0.48, 1.45, 0.24, 0.78)
    glow.add_box(cx + 0.95, base_y + 1.05, cz + hd + 0.06, 0.88, 0.72, 0.08, 1.0, 0.80, 0.36, 1.0)
    timber.add_box(cx + 0.95, base_y + 1.05, cz + hd + 0.11, 0.07, 0.76, 0.05)
    timber.add_box(cx + 0.95, base_y + 1.38, cz + hd + 0.11, 0.92, 0.07, 0.05)
    glow.add_box(cx + hw + 0.06, base_y + 1.05, cz, 0.08, 0.70, 0.86, 1.0, 0.78, 0.34, 1.0)


def build_static_architecture(loc, campfire_level=1, shelter_level=1, workbench_level=1,
                              thermos=False, quality_perimeter=42):
    b = {k: Mesh() for k in ("WOOD_BARK", "TIMBER", "STONE", "METAL_STEEL", "FUR_HIDE",
                             "CLOTH", "ICE", "SNOW", "PINE_FOLIAGE", "FIRE_EMBER")}
    bark = b["WOOD_BARK"]; foliage = b["PINE_FOLIAGE"]; stone = b["STONE"]
    timber = b["TIMBER"]; cloth = b["CLOTH"]; metal = b["METAL_STEEL"]
    fire = b["FIRE_EMBER"]; snow = b["SNOW"]

    if loc == "BASE_CLEARING":
        base_y = terrain_height(loc, 0.0, 0.0)
        foliage.add_shadow_disc(0.0, base_y, 0.0, 1.85, 0.42)
        for i in range(10):
            a = i / 10.0 * TWO_PI
            stone.add_sculpted_boulder(math.cos(a) * 1.02, base_y, math.sin(a) * 1.02, 0.25, 0.20, 0.23, 50 + i)
        for i in range(4):
            a = i / 4.0 * math.pi + 0.35
            bark.add_tapered_cylinder(math.cos(a) * 0.72, base_y + 0.05, math.sin(a) * 0.72,
                                      -math.cos(a) * 0.35, base_y + 0.34, -math.sin(a) * 0.35, 0.095, 0.075, 7, 0.55, 0.40, 0.30)
        for i in range(3):
            a = i / 3.0 * TWO_PI + 0.2
            metal.add_tapered_cylinder(math.cos(a) * 1.15, base_y, math.sin(a) * 1.15, 0.0, base_y + 1.65, 0.0, 0.032, 0.022, 6, 0.52, 0.55, 0.60)
        metal.add_tapered_cylinder(0.0, base_y + 0.72, 0.0, 0.0, base_y + 1.04, 0.0, 0.24, 0.27, 10, 0.44, 0.46, 0.52)

        sh_x, sh_z = -6.8, -5.8
        sh_y = terrain_height(loc, sh_x, sh_z)
        wall_h = 2.35 + (shelter_level - 1) * 0.16
        add_log_cabin(timber, bark, stone, snow, metal, fire, foliage,
                      sh_x, sh_y, sh_z, 4.6, wall_h, 4.2)
        for row in range(3):
            for col in range(4):
                lx = sh_x + 2.75 + (row % 2) * 0.05
                ly = sh_y + 0.11 + row * 0.20
                lz = sh_z - 1.1 + col * 0.24
                bark.add_tapered_cylinder(lx - 0.45, ly, lz, lx + 0.45, ly, lz, 0.10, 0.095, 7)

        wb_x, wb_z = 4.8, -4.5
        wb_y = terrain_height(loc, wb_x, wb_z)
        foliage.add_shadow_disc(wb_x, wb_y, wb_z, 1.45, 0.34)
        for dx in (-0.82, 0.82):
            for dz in (-0.42, 0.42):
                timber.add_box(wb_x + dx, wb_y, wb_z + dz, 0.16, 0.96, 0.16)
        timber.add_box(wb_x, wb_y + 0.96, wb_z, 2.05, 0.14, 1.12)
        metal.add_box(wb_x - 0.45, wb_y + 1.10, wb_z, 0.42, 0.24, 0.24, 0.72, 0.76, 0.82)
        if thermos:
            metal.add_tapered_cylinder(wb_x + 0.52, wb_y + 1.10, wb_z, wb_x + 0.52, wb_y + 1.62, wb_z, 0.14, 0.13, 10, 0.90, 0.96, 1.0)
            cloth.add_tapered_cylinder(wb_x + 0.52, wb_y + 1.24, wb_z, wb_x + 0.52, wb_y + 1.44, wb_z, 0.146, 0.146, 10)

    elif loc == "HUNTERS_CABIN":
        cab_x, cab_z = 7.2, 8.8
        cab_y = terrain_height(loc, cab_x, cab_z)
        add_log_cabin(timber, bark, stone, snow, metal, fire, foliage,
                      cab_x, cab_y, cab_z, 5.4, 2.85, 4.8)
        bark.add_tapered_cylinder(cab_x - 4.2, cab_y, cab_z - 1.2, cab_x - 4.2, cab_y + 2.0, cab_z - 0.2, 0.08, 0.06, 6)
        bark.add_tapered_cylinder(cab_x - 4.2, cab_y, cab_z + 0.8, cab_x - 4.2, cab_y + 2.0, cab_z - 0.2, 0.08, 0.06, 6)
        cloth.add_oriented_box(cab_x - 4.2, cab_y + 1.25, cab_z - 0.2, 0.10, 1.05, 1.35, 0.0, 0.0, 0.0, 0.85, 0.72, 0.60)

    elif loc == "FROZEN_LAKE":
        px = -4.5
        for i in range(7):
            pz = 22.8 - i * 1.15
            timber.add_box(px, -0.12, pz, 2.2, 0.12, 0.95)
            bark.add_tapered_cylinder(px - 0.95, -0.8, pz, px - 0.95, 0.35, pz, 0.09, 0.08, 6)
            bark.add_tapered_cylinder(px + 0.95, -0.8, pz, px + 0.95, 0.35, pz, 0.09, 0.08, 6)

    elif loc == "ABANDONED_MINE":
        for z_step in (-18.0, -10.0, -2.0, 6.0, 14.0, 22.0):
            gy = terrain_height(loc, 0.0, z_step)
            bark.add_tapered_cylinder(-3.3, gy, z_step, -3.1, gy + 3.4, z_step, 0.20, 0.18, 8)
            bark.add_tapered_cylinder(3.3, gy, z_step, 3.1, gy + 3.4, z_step, 0.20, 0.18, 8)
            timber.add_box(0.0, gy + 3.32, z_step, 6.9, 0.36, 0.42)
            timber.add_oriented_box(-2.35, gy + 2.85, z_step, 1.4, 0.20, 0.22, 0.0, 0.0, 0.68)
            timber.add_oriented_box(2.35, gy + 2.85, z_step, 1.4, 0.20, 0.22, 0.0, 0.0, -0.68)
            stone.add_sculpted_boulder(-4.6, gy, z_step + 2.2, 1.4, 2.2, 1.6, 300 + int(z_step), snow_cap=False)
            stone.add_sculpted_boulder(4.6, gy, z_step - 1.8, 1.5, 2.3, 1.5, 400 + int(z_step), snow_cap=False)
        for i in range(-11, 12):
            rz = i * 2.1
            ry = terrain_height(loc, 0.0, rz)
            timber.add_box(0.0, ry + 0.01, rz, 1.65, 0.08, 0.24)
        metal.add_box(-0.55, 0.12, 0.0, 0.09, 0.09, 48.0)
        metal.add_box(0.55, 0.12, 0.0, 0.09, 0.09, 48.0)

    # Portal gate (all locations)
    gate_z = -35.5
    portal_y = terrain_height(loc, 0.0, gate_z)
    bark.add_tapered_cylinder(-1.85, portal_y, gate_z, -1.75, portal_y + 3.35, gate_z, 0.20, 0.16, 8)
    bark.add_tapered_cylinder(1.85, portal_y, gate_z, 1.75, portal_y + 3.35, gate_z, 0.20, 0.16, 8)
    timber.add_box(0.0, portal_y + 3.05, gate_z, 4.4, 0.34, 0.40)
    snow.add_box(0.0, portal_y + 3.39, gate_z, 4.55, 0.16, 0.52)
    fire.add_crossed_flame(-1.55, portal_y + 2.25, gate_z + 0.28, 0.26, 0.42)
    fire.add_crossed_flame(1.55, portal_y + 2.25, gate_z + 0.28, 0.26, 0.42)

    # Fallen trunks + boulders
    for i in range(7):
        a = i * 0.91 + 0.4
        rd = 13.5 + (i % 3) * 7.2
        lx, lz = math.cos(a) * rd, math.sin(a) * rd
        ly = terrain_height(loc, lx, lz)
        if loc != "FROZEN_LAKE" or rd > 24.5:
            bark.add_tapered_cylinder(lx - math.cos(a + 1.1) * 1.5, ly + 0.14, lz - math.sin(a + 1.1) * 1.5,
                                      lx + math.cos(a + 1.1) * 1.5, ly + 0.28, lz + math.sin(a + 1.1) * 1.5,
                                      0.22, 0.16, 8)
            stone.add_sculpted_boulder(lx + 1.3, ly, lz - 0.8, 0.65, 0.48, 0.58, 700 + i)

    # Perimeter forest rings
    ring = quality_perimeter
    for i in range(ring):
        ang = i / ring * TWO_PI
        if abs(math.sin(ang)) < 0.09 and math.cos(ang) < 0:
            continue
        rad = 39.2 + (i % 3) * 1.6
        bx, bz = math.sin(ang) * rad, -math.cos(ang) * rad
        by = terrain_border(loc, bx, bz)
        add_pine_tree(bark, foliage, bx, by, bz, 1.25 + (i % 4) * 0.16, i * 13, True)
    outer = (ring * 3) // 4
    for i in range(outer):
        ang = i / outer * TWO_PI + 0.11
        rad = 44.5 + (i % 2) * 1.8
        bx, bz = math.sin(ang) * rad, -math.cos(ang) * rad
        by = terrain_border(loc, bx, bz)
        add_pine_tree(bark, foliage, bx, by, bz, 1.55 + (i % 3) * 0.18, 500 + i, False)

    return b


def build_node_meshes(loc, nodes):
    b = {k: Mesh() for k in ("WOOD_BARK", "TIMBER", "STONE", "METAL_STEEL", "FUR_HIDE",
                             "CLOTH", "ICE", "SNOW", "PINE_FOLIAGE", "FIRE_EMBER")}
    bark = b["WOOD_BARK"]; foliage = b["PINE_FOLIAGE"]; stone = b["STONE"]
    timber = b["TIMBER"]; cloth = b["CLOTH"]; metal = b["METAL_STEEL"]
    fur = b["FUR_HIDE"]; fire = b["FIRE_EMBER"]; ice = b["ICE"]

    for idx, node in enumerate(nodes):
        kind, nx_, nz_ = node["kind"], node["x"], node["z"]
        if not node.get("available", True):
            continue
        y = terrain_height(loc, nx_, nz_)
        if kind == "PINE_TREE":
            add_pine_tree(bark, foliage, nx_, y, nz_, 0.94 + (idx % 4) * 0.11, 90 + idx, True)
        elif kind == "BERRY_BUSH":
            foliage.add_shadow_disc(nx_, y, nz_, 1.15, 0.34)
            foliage.add_spruce_bough_tier(nx_, y + 0.08, nz_, 0.92, 0.88, tips=7, twist=idx * 0.6)
            foliage.add_spruce_bough_tier(nx_ + 0.25, y + 0.22, nz_ - 0.18, 0.68, 0.72, tips=6, twist=1.1)
            for bb in range(7):
                ba = bb * 0.9 + idx
                cloth.add_sculpted_boulder(nx_ + math.cos(ba) * 0.46, y + 0.32 + (bb % 3) * 0.16,
                                           nz_ + math.sin(ba) * 0.46, 0.075, 0.075, 0.075, 60 + bb,
                                           r=1.0, g=0.16, b=0.20, snow_cap=False)
        elif kind == "STONE_OUTCROP":
            foliage.add_shadow_disc(nx_, y, nz_, 1.55, 0.36)
            stone.add_sculpted_boulder(nx_, y, nz_, 0.92, 0.85, 0.82, 120 + idx)
            stone.add_sculpted_boulder(nx_ + 0.62, y - 0.06, nz_ - 0.42, 0.56, 0.52, 0.54, 220 + idx)
        elif kind == "SUPPLY_CRATE":
            foliage.add_shadow_disc(nx_, y, nz_, 1.05, 0.36)
            timber.add_oriented_box(nx_, y + 0.44, nz_, 1.02, 0.88, 1.02, idx * 0.35, 0.0, 0.0)
            metal.add_oriented_box(nx_, y + 0.89, nz_, 1.06, 0.06, 1.06, idx * 0.35, 0.0, 0.0)
            cloth.add_oriented_box(nx_, y + 0.98, nz_, 0.72, 0.20, 0.62, idx * 0.35 + 0.2, 0.0, 0.0)
        elif kind == "RESIN_STUMP":
            foliage.add_shadow_disc(nx_, y, nz_, 0.95, 0.34)
            bark.add_tapered_cylinder(nx_, y, nz_, nx_, y + 0.98, nz_, 0.46, 0.38, 10)
            fire.add_sculpted_boulder(nx_ + 0.34, y + 0.44, nz_ + 0.12, 0.16, 0.22, 0.15, 310 + idx, 1.0, 0.72, 0.16, False)
            fire.add_sculpted_boulder(nx_ - 0.28, y + 0.62, nz_ - 0.20, 0.12, 0.16, 0.12, 340 + idx, 1.0, 0.78, 0.22, False)
        elif kind == "ICE_HOLE":
            for r_ in range(8):
                a = r_ / 8.0 * TWO_PI
                ice.add_sculpted_boulder(nx_ + math.cos(a) * 0.82, y + 0.08, nz_ + math.sin(a) * 0.82, 0.24, 0.14, 0.22, 410 + r_)
            ice.add_shadow_disc(nx_, y + 0.09, nz_, 0.72, 0.88)
            bark.add_tapered_cylinder(nx_ + 0.95, y + 0.05, nz_, nx_ + 0.45, y + 1.28, nz_, 0.045, 0.025, 6)
        elif kind == "COAL_SEAM":
            foliage.add_shadow_disc(nx_, y, nz_, 1.45, 0.38)
            stone.add_sculpted_boulder(nx_, y, nz_, 0.95, 0.92, 0.85, 510 + idx, 0.24, 0.26, 0.30, False)
            metal.add_sculpted_boulder(nx_ + 0.35, y + 0.25, nz_ + 0.28, 0.36, 0.34, 0.34, 540 + idx, 0.18, 0.20, 0.24, False)
        elif kind == "IRON_VEIN":
            foliage.add_shadow_disc(nx_, y, nz_, 1.45, 0.38)
            stone.add_sculpted_boulder(nx_, y, nz_, 0.90, 0.95, 0.82, 610 + idx, 0.62, 0.48, 0.40, False)
            metal.add_sculpted_boulder(nx_ - 0.28, y + 0.32, nz_ + 0.30, 0.38, 0.36, 0.32, 640 + idx, 0.94, 0.68, 0.44, False)
        elif kind == "GAME_CARCASS":
            foliage.add_shadow_disc(nx_, y, nz_, 1.10, 0.36)
            fur.add_sculpted_boulder(nx_, y - 0.05, nz_, 0.68, 0.42, 0.46, 710 + idx, 0.72, 0.56, 0.44)
        elif kind == "SPECIAL_CACHE":
            foliage.add_shadow_disc(nx_, y, nz_, 1.35, 0.44)
            timber.add_box(nx_, y, nz_, 1.24, 0.66, 0.86, 0.95, 0.78, 0.52)
            metal.add_box(nx_, y + 0.66, nz_, 1.28, 0.16, 0.90, 1.0, 0.88, 0.55)
            bark.add_tapered_cylinder(nx_ + 0.78, y, nz_, nx_ + 0.78, y + 1.85, nz_, 0.05, 0.04, 6)
    return b

# ---------------------------------------------------------------------------
# 6. Camera matrices + sky shader port + scene writer + 7 landscape shots
# ---------------------------------------------------------------------------

def mat_perspective(fovy_deg, aspect, near, far):
    f = 1.0 / math.tan(math.radians(fovy_deg) / 2.0)
    m = np.zeros(16, dtype=np.float64)
    m[0] = f / aspect
    m[5] = f
    m[10] = (far + near) / (near - far)
    m[11] = -1.0
    m[14] = (2.0 * far * near) / (near - far)
    return m

def mat_look_at(eye, center, up):
    fx = center[0] - eye[0]; fy = center[1] - eye[1]; fz = center[2] - eye[2]
    fl = math.sqrt(fx * fx + fy * fy + fz * fz) or 1.0
    fx, fy, fz = fx / fl, fy / fl, fz / fl
    ux, uy, uz = up
    # s = f x up
    sx, sy, sz = fy * uz - fz * uy, fz * ux - fx * uz, fx * uy - fy * ux
    sl = math.sqrt(sx * sx + sy * sy + sz * sz) or 1.0
    sx, sy, sz = sx / sl, sy / sl, sz / sl
    # u = s x f
    nx, ny, nz = sy * fz - sz * fy, sz * fx - sx * fz, sx * fy - sy * fx
    m = np.zeros(16, dtype=np.float64)
    m[0] = sx; m[4] = sy; m[8] = sz
    m[1] = nx; m[5] = ny; m[9] = nz
    m[2] = -fx; m[6] = -fy; m[10] = -fz
    m[12] = -(sx * eye[0] + sy * eye[1] + sz * eye[2])
    m[13] = -(nx * eye[0] + ny * eye[1] + nz * eye[2])
    m[14] = (fx * eye[0] + fy * eye[1] + fz * eye[2])
    m[15] = 1.0
    return m

def mat_mul(a, b):
    out = np.zeros(16, dtype=np.float64)
    for col in range(4):
        for row in range(4):
            out[col * 4 + row] = sum(a[k * 4 + row] * b[col * 4 + k] for k in range(4))
    return out


def compute_daylight(cycle_t):
    if cycle_t < 0.08:
        v = 0.45 + (cycle_t / 0.08) * 0.55
    elif cycle_t < 0.52:
        v = 1.0
    elif cycle_t < 0.62:
        v = 1.0 - ((cycle_t - 0.52) / 0.10) * 0.80
    elif cycle_t < 0.92:
        v = 0.20
    else:
        v = 0.20 + ((cycle_t - 0.92) / 0.08) * 0.25
    return max(0.18, min(1.0, v))

def scale_hex(hex_color, factor):
    return [max(0.0, min(1.0, ((hex_color >> 16) & 0xFF) / 255.0 * factor)),
            max(0.0, min(1.0, ((hex_color >> 8) & 0xFF) / 255.0 * factor)),
            max(0.0, min(1.0, (hex_color & 0xFF) / 255.0 * factor))]


def render_sky(zenith, horizon, fog_col, sun_uv, sun_halo, cam_yaw, cam_pitch, aspect, time_sec, aurora):
    """Vectorized port of SKY_FRAGMENT_SHADER."""
    ys, xs = np.mgrid[0:H, 0:W]
    u = (xs + 0.5) / W
    v = (ys + 0.5) / H
    ndc_x = u * 2.0 - 1.0
    ndc_y = v * 2.0 - 1.0
    tan_half = 0.60086
    rx = ndc_x * aspect * tan_half
    ry = ndc_y * tan_half
    rz = -np.ones_like(rx)
    ln = np.sqrt(rx * rx + ry * ry + rz * rz)
    rx, ry, rz = rx / ln, ry / ln, rz / ln

    cp, sp = math.cos(cam_pitch), math.sin(cam_pitch)
    py_ = ry * cp - rz * sp
    pz_ = ry * sp + rz * cp
    px_ = rx
    cyw, syw = math.cos(cam_yaw), math.sin(cam_yaw)
    dx = px_ * cyw - pz_ * syw
    dy = py_
    dz = px_ * syw + pz_ * cyw
    ln2 = np.sqrt(dx * dx + dy * dy + dz * dz)
    dx, dy, dz = dx / ln2, dy / ln2, dz / ln2

    elev = np.clip(dy, -0.35, 1.0)
    sky_grad = np.clip(np.maximum(elev, 0.0) ** 0.62, 0.0, 1.0)[..., None]
    zc = np.array(zenith); hc = np.array(horizon); fc = np.array(fog_col); sc = np.array(sun_halo)
    col = hc + (zc - hc) * sky_grad

    def hash21(px, py):
        a = np.modf(px * 123.34)[0]; a = a - np.floor(a)
        b = np.modf(py * 456.21)[0]; b = b - np.floor(b)
        dotv = a * (a + 45.32) + b * (b + 45.32)
        a = np.clip(a + dotv, None, None); b = np.clip(b + dotv, None, None)
        fr = np.modf(a * b)[0]
        return np.abs(fr)

    az = np.arctan2(dz, dx)

    if aurora > 0.02:
        star_x = np.floor(az * 46.0)
        star_y = np.floor(elev * 90.0)
        star_rnd = hash21(star_x, star_y)
        twinkle = 0.55 + 0.45 * np.sin(time_sec * 3.1 + star_rnd * 40.0)
        star_mask = (star_rnd > 0.986) * np.clip((elev - 0.02) / 0.23, 0, 1)
        col += (np.array([0.82, 0.90, 1.0]) * (twinkle * aurora * star_mask)[..., None]) * 0.35

        def noise1d(xq):
            i_ = np.floor(xq)
            f_ = xq - i_
            uu = f_ * f_ * (3.0 - 2.0 * f_)
            h0 = hash21(i_, np.full_like(i_, 1.7))
            h1 = hash21(i_ + 1.0, np.full_like(i_, 1.7))
            return h0 + (h1 - h0) * uu

        wave1 = np.sin(az * 3.0 + time_sec * 0.28 + np.sin(az * 7.0 - time_sec * 0.15) * 0.8)
        curtain = np.exp(-np.abs(elev - (0.30 + 0.09 * wave1)) * 7.5)
        streaks = 0.55 + 0.45 * np.sin(az * 26.0 + time_sec * 0.9)
        aur_mix = np.clip(elev * 1.4, 0, 1)[..., None]
        aur_col = np.array([0.10, 0.88, 0.58]) + (np.array([0.42, 0.28, 0.92]) - np.array([0.10, 0.88, 0.58])) * aur_mix
        col += aur_col * (curtain * streaks * aurora * 0.52)[..., None]

        # clouds + mountains only outside mine — always computed below
    # clouds
    elev_pos = np.maximum(elev, 0.0)

    def noise1d(xq):
        i_ = np.floor(xq)
        f_ = xq - i_
        uu = f_ * f_ * (3.0 - 2.0 * f_)
        h0 = hash21(i_, np.full_like(i_, 1.7))
        h1 = hash21(i_ + 1.0, np.full_like(i_, 1.7))
        return h0 + (h1 - h0) * uu

    cloud_az = az * 3.2 + time_sec * 0.012
    cloud = noise1d(cloud_az * 2.4 + elev * 6.0) * 0.6 + noise1d(cloud_az * 5.8 - elev * 9.0) * 0.4
    cloud_mask = np.clip((cloud - 0.56) / 0.30, 0, 1) * np.exp(-elev * 2.8) * 0.28
    cloud_mask = np.where(elev > 0.03, cloud_mask, 0.0)
    cloud_col = hc + (sc - hc) * 0.35
    col = col + (cloud_col - col) * cloud_mask[..., None]

    # sun disc + halo
    sun_d = np.sqrt((u - sun_uv[0]) ** 2 + (v - sun_uv[1]) ** 2)
    disc = np.clip((0.042 - sun_d) / (0.042 - 0.014), 0, 1)
    halo = np.exp(-sun_d * 6.5) * 0.34
    col += sc * ((disc * 0.88 + halo)[..., None])

    # mountains (two ridges)
    def mtn_ridge(azq, freq, shift):
        h_ = 0.52 * np.abs(noise1d(azq * freq + shift) * 2.0 - 1.0)
        h_ += 0.28 * np.abs(noise1d(azq * freq * 2.13 + shift * 1.7) * 2.0 - 1.0)
        h_ += 0.14 * np.abs(noise1d(azq * freq * 4.41 + shift * 2.3) * 2.0 - 1.0)
        return h_

    far_peak = 0.135 * mtn_ridge(az, 3.1, 4.2) + 0.015
    near_peak = 0.092 * mtn_ridge(az, 5.4, 11.7) - 0.004

    snow_line = np.clip((elev - far_peak * 0.45) / np.maximum(far_peak * 0.55, 1e-4), 0, 1)
    far_mtn = fc * 0.72 + ((fc + (np.array([0.92, 0.96, 1.0]) - fc) * 0.52) - fc * 0.72) * snow_line[..., None]
    col = np.where((elev < far_peak)[..., None], col + (far_mtn - col) * 0.82, col)

    snow_line2 = np.clip((elev - near_peak * 0.38) / np.maximum(near_peak * 0.62, 1e-4), 0, 1)
    near_mtn = fc * 0.56 + ((fc + (np.array([0.95, 0.98, 1.0]) - fc) * 0.42) - fc * 0.56) * snow_line2[..., None]
    col = np.where((elev < near_peak)[..., None], col + (near_mtn - col) * 0.92, col)

    horizon_haze = np.clip((0.06 - elev) / 0.11, 0, 1)
    col = col + (fc - col) * (horizon_haze * 0.94)[..., None]

    out = np.clip(col * 255.0 + 0.5, 0, 255).astype(np.uint8)
    return out


MATERIALS = ("WOOD_BARK", "TIMBER", "STONE", "METAL_STEEL", "FUR_HIDE",
             "CLOTH", "ICE", "SNOW", "PINE_FOLIAGE", "FIRE_EMBER")

OPAQUE_ORDER = ("WOOD_BARK", "TIMBER", "STONE", "METAL_STEEL", "FUR_HIDE",
                "CLOTH", "ICE", "SNOW", "PINE_FOLIAGE")

SPEC = {"ICE": 0.85, "METAL_STEEL": 0.72, "SNOW": 0.25, "STONE": 0.16}


def write_scene(path, vp, cam, uniforms, tex_list, batches):
    with open(path, "wb") as f:
        f.write(struct.pack("<Iii", 0x57465232, W, H))
        f.write(np.asarray(vp, dtype=np.float32).tobytes())
        f.write(np.asarray(cam, dtype=np.float32).tobytes())
        f.write(np.asarray(uniforms["sunDir"], dtype=np.float32).tobytes())
        f.write(np.asarray(uniforms["sunCol"], dtype=np.float32).tobytes())
        f.write(np.asarray(uniforms["ambSky"], dtype=np.float32).tobytes())
        f.write(np.asarray(uniforms["ptPos1"], dtype=np.float32).tobytes())
        f.write(np.asarray(uniforms["ptCol"], dtype=np.float32).tobytes())
        f.write(struct.pack("<f", uniforms["ptRad1"]))
        f.write(np.asarray(uniforms["ptPos2"], dtype=np.float32).tobytes())
        f.write(struct.pack("<f", uniforms["ptRad2"]))
        f.write(np.asarray(uniforms["ptPos3"], dtype=np.float32).tobytes())
        f.write(struct.pack("<f", uniforms["ptRad3"]))
        f.write(np.asarray(uniforms["fogCol"], dtype=np.float32).tobytes())
        f.write(struct.pack("<ff", uniforms["fogNear"], uniforms["fogFar"]))
        f.write(struct.pack("<I", len(tex_list)))
        for t in tex_list:
            s = t.shape[0]
            f.write(struct.pack("<i", s))
            f.write(t.tobytes())
        f.write(struct.pack("<I", len(batches)))
        for lit, tex_idx, spec, verts in batches:
            vc = len(verts) // 12
            f.write(struct.pack("<iiiII", lit, tex_idx, struct.unpack("<i", struct.pack("<f", spec))[0], vc, vc))
            f.write(np.asarray(verts, dtype=np.float32).tobytes())
            f.write(np.arange(vc, dtype=np.uint32).tobytes())


_TEX_CACHE = {}
_STATIC_CACHE = {}
_TERRAIN_CACHE = {}
_NODE_CACHE = {}


def build_shot(shot):
    loc = shot["location"]
    pal = LOCATIONS[loc]["palette"]
    night_factor_note = None

    elapsed = shot["elapsed"]
    cycle_t = (elapsed % 600.0) / 600.0
    daylight = compute_daylight(cycle_t)

    has_light = shot.get("torch_lit", False) or shot.get("portable_fire", 0) > 0
    mine_dark = 0.26 if (LOCATIONS[loc]["requiresLight"] and not has_light) else 1.0
    eff_light = max(0.12, min(1.0, daylight * mine_dark))

    zenith = scale_hex(pal["skyZenithHex"], 0.20 + 0.80 * daylight)
    horizon = scale_hex(pal["skyHorizonHex"], 0.22 + 0.78 * daylight)
    fog_col = scale_hex(pal["fogHex"], 0.20 + 0.80 * eff_light)
    sun_col = scale_hex(pal["sunLightHex"], 0.16 + 0.90 * eff_light)

    px_, py_, pz_ = shot["pos"]
    yaw, pitch = shot["yaw"], shot["pitch"]
    cam_x, cam_y, cam_z = px_, py_ + 1.72, pz_
    look = (cam_x + math.sin(yaw) * math.cos(pitch),
            cam_y + math.sin(pitch),
            cam_z - math.cos(yaw) * math.cos(pitch))

    proj = mat_perspective(62.0, W / H, 0.12, 135.0)
    view = mat_look_at((cam_x, cam_y, cam_z), look, (0.0, 1.0, 0.0))
    vp = mat_mul(proj, view)

    aurora = 0.0 if loc == "ABANDONED_MINE" else max(0.0, min(1.0, (0.68 - daylight) * 1.65))

    sun_az = cycle_t * TWO_PI - yaw * 0.35
    sun_uv = (max(-0.4, min(1.4, 0.5 + 0.34 * math.sin(sun_az))),
              max(-0.3, min(1.3, 0.56 + 0.24 * math.sin(cycle_t * TWO_PI) - pitch * 0.55)))

    # Textures (biome palette, seed offset by location as in renderer) — cached per location
    tex_seed = 2026 + list(LOCATIONS).index(loc) * 37
    tex_size = shot.get("tex_size", 256)
    tkey = (loc, tex_size, tex_seed)
    if tkey not in _TEX_CACHE:
        _TEX_CACHE[tkey] = [generate_texture_rgb(t, pal, tex_size, tex_seed) for t in MATERIALS]
    tex_list = _TEX_CACHE[tkey]
    tex_index = {t: i for i, t in enumerate(MATERIALS)}

    # Statics + nodes (statics depend only on location + defaults; cached)
    skey = (loc, shot.get("perimeter", 42))
    if skey not in _STATIC_CACHE:
        _STATIC_CACHE[skey] = build_static_architecture(loc, quality_perimeter=shot.get("perimeter", 42))
    statics = _STATIC_CACHE[skey]
    nkey = (loc, tuple((n["kind"], n["x"], n["z"]) for n in shot.get("nodes", [])))
    if nkey not in _NODE_CACHE:
        _NODE_CACHE[nkey] = build_node_meshes(loc, shot.get("nodes", []))
    nodes = _NODE_CACHE[nkey]

    batches = []

    # Terrain + lake ice (cached per location/steps)
    terr_key = (loc, shot.get("terrain_steps", 68))
    if terr_key not in _TERRAIN_CACHE:
        _TERRAIN_CACHE[terr_key] = build_terrain(loc, 48.0, shot.get("terrain_steps", 68))
    terr = _TERRAIN_CACHE[terr_key]
    ground_mat = "STONE" if loc == "ABANDONED_MINE" else "SNOW"
    batches.append((0, tex_index[ground_mat], 0.22, terr.v))
    if loc == "FROZEN_LAKE":
        ice = Mesh()
        segs, half = 12, 23.2
        stp = (half * 2.0) / segs
        for iz in range(segs):
            for ix in range(segs):
                x0 = -half + ix * stp; x1 = x0 + stp
                z0 = -half + iz * stp; z1 = z0 + stp
                ice.quad(x0, -0.16, z1, x1, -0.16, z1, x1, -0.16, z0, x0, -0.16, z0,
                         0.0, 1.0, 0.0, 1.35, 1.35, 0.90, 0.97, 1.0, 0.94)
        batches.append((0, tex_index["ICE"], 0.88, ice.v))

    for mat in OPAQUE_ORDER:
        if statics[mat].count:
            batches.append((0, tex_index[mat], SPEC.get(mat, 0.06), statics[mat].v))
        if nodes[mat].count:
            batches.append((0, tex_index[mat], SPEC.get(mat, 0.06), nodes[mat].v))
    if statics["FIRE_EMBER"].count:
        batches.append((1, tex_index["FIRE_EMBER"], 0.0, statics["FIRE_EMBER"].v))
    if nodes["FIRE_EMBER"].count:
        batches.append((1, tex_index["FIRE_EMBER"], 0.0, nodes["FIRE_EMBER"].v))

    # Dynamic layer
    dyn = {k: Mesh() for k in MATERIALS}
    bark = dyn["WOOD_BARK"]; timber = dyn["TIMBER"]; stone = dyn["STONE"]
    cloth = dyn["CLOTH"]; metal = dyn["METAL_STEEL"]; fur = dyn["FUR_HIDE"]
    fire = dyn["FIRE_EMBER"]; snow = dyn["SNOW"]

    if loc == "BASE_CLEARING":
        base_y = terrain_height(loc, 0.0, 0.0)
        flick = 0.86 + 0.20 * math.sin(elapsed * 10.5)
        fire.add_crossed_flame(0.0, base_y + 0.16, 0.0,
                               0.82 + shot.get("campfire_level", 1) * 0.08,
                               (1.15 + shot.get("campfire_level", 1) * 0.16) * flick,
                               math.sin(elapsed * 6.5) * 0.06)
        for s in range(8):
            rise = ((elapsed * 1.45 + s * 0.37) % 2.6)
            sx_ = math.sin(elapsed * 2.4 + s * 1.7) * (0.18 + rise * 0.14)
            sz_ = math.cos(elapsed * 2.1 + s * 2.3) * (0.18 + rise * 0.14)
            fire.add_box(sx_, base_y + 0.45 + rise, sz_, 0.045, 0.045, 0.045, 1.0, 0.80, 0.28, (1 - rise / 2.6) * 0.9)
        ch_y = terrain_height(loc, -6.8, -5.8) + 3.9
        for p in range(5):
            rise = ((elapsed * 0.75 + p * 0.7) % 3.5)
            rad = 0.16 + rise * 0.14
            snow.add_box(-5.1 + rise * 0.28, ch_y + rise, -4.6 - rise * 0.12, rad, rad * 0.75, rad,
                         0.82, 0.85, 0.90, max(0.05, 0.42 * (1 - rise / 3.5)))

    for fc in shot.get("field_fires", []):
        fy = terrain_height(loc, fc[0], fc[1])
        stone.add_sculpted_boulder(fc[0] + 0.35, fy, fc[1], 0.18, 0.14, 0.16, 17)
        stone.add_sculpted_boulder(fc[0] - 0.35, fy, fc[1] + 0.12, 0.18, 0.14, 0.16, 23)
        bark.add_tapered_cylinder(fc[0] - 0.35, fy + 0.04, fc[1] - 0.2, fc[0] + 0.35, fy + 0.22, fc[1] + 0.2,
                                  0.07, 0.06, 6)
        flick = 0.85 + 0.22 * math.sin(elapsed * 11.2)
        fire.add_crossed_flame(fc[0], fy + 0.12, fc[1], 0.62, 0.95 * flick, math.sin(elapsed * 7.0) * 0.05)

    for node in shot.get("nodes", []):
        if node.get("available", True) and node["kind"] == "SPECIAL_CACHE":
            ny = terrain_height(loc, node["x"], node["z"])
            hover = ny + 1.05 + 0.16 * math.sin(elapsed * 3.1)
            fire.add_crossed_flame(node["x"], hover, node["z"], 0.36, 0.58, 0.0, 1.0, 0.92, 0.42, 0.92)

    fwd_x, fwd_z = math.sin(yaw), -math.cos(yaw)
    for idx, beast in enumerate(shot.get("wildlife", [])):
        bx, bz = beast["x"], beast["z"]
        dx_, dz_ = bx - cam_x, bz - cam_z
        dist_sq = dx_ * dx_ + dz_ * dz_
        if dist_sq > 56 * 56:
            continue
        if dist_sq > 9:
            inv_d = 1.0 / math.sqrt(dist_sq)
            if (dx_ * inv_d) * fwd_x + (dz_ * inv_d) * fwd_z < -0.35:
                continue
        gy = terrain_height(loc, bx, bz)
        speed = {"SNOW_HARE": 2.6, "NORTHERN_DEER": 2.1, "TIMBER_WOLF": 3.1}[beast["kind"]]
        add_wildlife(fur, bark, fire, beast["kind"], bx, gy, bz, beast["heading"],
                     elapsed * (4.8 + speed) + idx * 1.7)

    if shot.get("fp_tool"):
        add_fp_tool(timber, metal, cloth, fire, shot["fp_tool"], shot.get("torch_lit", False),
                    cam_x, cam_y, cam_z, yaw, pitch, 0.18, 1.1, elapsed)

    snow_budget = shot.get("snow_particles", 185)
    if not shot.get("is_snowing", False):
        snow_budget //= 3
    wind_x = math.sin(elapsed * 0.35) * 1.4 + 0.9
    for i in range(snow_budget):
        local_fwd = 1.2 + ((i * 53) % 190) * 0.11
        local_side = (((i * 97) % 260) - 130) * 0.11
        fall = ((i * 0.37 + elapsed * (3.4 if shot.get("is_snowing") else 1.8)) % 9.5)
        spx = cam_x + fwd_x * local_fwd + math.cos(yaw) * local_side + math.sin(elapsed * 1.6 + i) * 0.45 + wind_x * (fall * 0.12)
        spz = cam_z + fwd_z * local_fwd + math.sin(yaw) * local_side + math.cos(elapsed * 1.3 + i) * 0.45
        spy = cam_y + 5.2 - fall
        ssz = 0.030 + (i % 3) * 0.012
        snow.add_box(spx, spy, spz, ssz, ssz * 1.25, ssz, 0.95, 0.98, 1.0, 0.78)

    for mat in OPAQUE_ORDER:
        if mat == "SNOW":
            continue
        if dyn[mat].count:
            batches.append((0, tex_index[mat], SPEC.get(mat, 0.06), dyn[mat].v))
    if dyn["SNOW"].count:
        # Dynamic snowflakes / smoke render UNLIT (bright particles, no dark box faces)
        batches.append((1, tex_index["SNOW"], 0.0, dyn["SNOW"].v))
    if fire.count:
        batches.append((1, tex_index["FIRE_EMBER"], 0.0, fire.v))

    # Lighting uniforms (exact Kotlin applyWorldUniforms math)
    fire_flicker = 0.92 + 0.08 * math.sin(elapsed * 10.5)
    pt1 = [0.0, 0.0, 0.0]; rad1 = 0.0
    if loc == "BASE_CLEARING":
        pt1 = [0.0, terrain_height(loc, 0.0, 0.0) + 1.35, 0.0]
        rad1 = (16.0 + shot.get("campfire_level", 1) * 2.8) * fire_flicker
    elif loc == "ABANDONED_MINE":
        pt1 = [0.0, 2.6, -6.0]
        rad1 = 11.0 * fire_flicker

    pt2 = [0.0, 0.0, 0.0]; rad2 = 0.0
    ffs = shot.get("field_fires", [])
    if ffs:
        fx, fz = ffs[-1]
        pt2 = [fx, terrain_height(loc, fx, fz) + 1.1, fz]
        rad2 = 15.5 * fire_flicker
    elif loc == "HUNTERS_CABIN":
        pt2 = [7.2, terrain_height(loc, 7.2, 8.8) + 1.8, 6.2]
        rad2 = 13.5 * fire_flicker
    elif loc == "BASE_CLEARING":
        pt2 = [-6.5, terrain_height(loc, -6.5, -5.5) + 1.5, -3.2]
        rad2 = 9.5

    pt3 = [0.0, 0.0, 0.0]; rad3 = 0.0
    if shot.get("torch_lit", False) or shot.get("portable_fire", 0) > 0:
        pt3 = [cam_x + math.sin(yaw) * 0.6, cam_y - 0.15, cam_z - math.cos(yaw) * 0.6]
        rad3 = (19.5 if shot.get("torch_lit", False) else 13.5) * fire_flicker

    fog_scale = shot.get("fog_scale", 1.05)
    if LOCATIONS[loc]["requiresLight"] and not has_light:
        fog_near, fog_far = 3.0, 12.5
    elif shot.get("is_snowing", False):
        fog_near = pal["fogNear"] * 0.76 * fog_scale
        fog_far = pal["fogFar"] * 0.80 * fog_scale
    else:
        fog_near = pal["fogNear"] * fog_scale
        fog_far = pal["fogFar"] * fog_scale

    sun_angle = cycle_t * TWO_PI
    sun_dir = [math.cos(sun_angle) * 0.62,
               max(0.14, 0.32 + 0.68 * math.sin(sun_angle)),
               0.46]
    amb_sky = [horizon[0] * 0.62, horizon[1] * 0.66, horizon[2] * 0.74]

    uniforms = dict(
        sunDir=sun_dir, sunCol=sun_col, ambSky=amb_sky,
        ptPos1=pt1, ptCol=[1.0, 0.70, 0.34], ptRad1=rad1,
        ptPos2=pt2, ptRad2=rad2,
        ptPos3=pt3, ptRad3=rad3,
        fogCol=fog_col, fogNear=fog_near, fogFar=fog_far,
    )

    sky = render_sky(zenith, horizon, fog_col, sun_uv, sun_col, yaw, pitch, W / H, elapsed, aurora)
    return vp, (cam_x, cam_y, cam_z), uniforms, tex_list, batches, sky


def make_nodes(kind, xs_zs):
    return [{"kind": kind, "x": x, "z": z, "available": True} for (x, z) in xs_zs]


SHOTS = [
    dict(name="01-base-clearing-campfire.png", location="BASE_CLEARING",
         pos=(2.6, 0.0, 5.2), yaw=math.atan2(-2.6, 5.2), pitch=-0.07,
         elapsed=120.0, is_snowing=True, fp_tool="AXE", campfire_level=2,
         wildlife=[dict(kind="SNOW_HARE", x=1.6, z=2.2, heading=2.2),
                   dict(kind="NORTHERN_DEER", x=-4.2, z=-3.4, heading=0.8)],
         nodes=[dict(kind="PINE_TREE", x=-3.2, z=-8.5, available=True),
                dict(kind="PINE_TREE", x=6.5, z=-7.0, available=True),
                dict(kind="BERRY_BUSH", x=2.2, z=-2.6, available=True),
                dict(kind="SPECIAL_CACHE", x=4.2, z=-3.6, available=True)],
         terrain_steps=68, perimeter=42, snow_particles=185, tex_size=256),
    dict(name="02-base-clearing-day.png", location="BASE_CLEARING",
         pos=(0.5, 0.0, 15.0), yaw=math.atan2(-2.5, 17.0), pitch=-0.05,
         elapsed=205.0, is_snowing=False, fp_tool="AXE", campfire_level=3,
         wildlife=[dict(kind="NORTHERN_DEER", x=-7.5, z=3.5, heading=1.4),
                   dict(kind="SNOW_HARE", x=3.0, z=6.0, heading=0.2)],
         nodes=[dict(kind="PINE_TREE", x=-4.5, z=-2.5, available=True),
                dict(kind="PINE_TREE", x=5.5, z=-1.0, available=True),
                dict(kind="STONE_OUTCROP", x=-2.2, z=-6.2, available=True),
                dict(kind="SUPPLY_CRATE", x=2.8, z=-4.4, available=True)],
         terrain_steps=68, perimeter=42, snow_particles=185, tex_size=256),
    dict(name="03-dense-spruce-blizzard.png", location="DENSE_SPRUCE",
         pos=(7.5, 0.0, 10.0), yaw=math.atan2(-11.5, 16.0), pitch=-0.03,
         elapsed=145.0, is_snowing=True, fp_tool="SPEAR",
         wildlife=[dict(kind="TIMBER_WOLF", x=1.5, z=2.5, heading=2.6),
                   dict(kind="SNOW_HARE", x=-2.5, z=4.0, heading=0.7)],
         nodes=[dict(kind="PINE_TREE", x=1.0, z=1.0, available=True),
                dict(kind="PINE_TREE", x=-3.5, z=-1.5, available=True),
                dict(kind="PINE_TREE", x=4.2, z=-3.2, available=True),
                dict(kind="PINE_TREE", x=-6.0, z=3.5, available=True),
                dict(kind="PINE_TREE", x=2.8, z=-6.5, available=True),
                dict(kind="RESIN_STUMP", x=-1.2, z=-3.8, available=True),
                dict(kind="SPECIAL_CACHE", x=-9.5, z=-11.0, available=True)],
         terrain_steps=68, perimeter=42, snow_particles=220, tex_size=256),
    dict(name="04-frozen-lake-ice.png", location="FROZEN_LAKE",
         pos=(-1.5, 0.0, 24.0), yaw=math.atan2(1.5, 24.0), pitch=-0.10,
         elapsed=95.0, is_snowing=False, fp_tool="SPEAR",
         wildlife=[dict(kind="NORTHERN_DEER", x=3.5, z=2.0, heading=1.1),
                   dict(kind="SNOW_HARE", x=-5.5, z=8.5, heading=0.4)],
         nodes=[dict(kind="ICE_HOLE", x=2.5, z=4.5, available=True),
                dict(kind="ICE_HOLE", x=-7.5, z=-3.5, available=True),
                dict(kind="SPECIAL_CACHE", x=10.5, z=-8.5, available=True)],
         terrain_steps=68, perimeter=36, snow_particles=120, tex_size=256),
    dict(name="05-abandoned-mine-night.png", location="ABANDONED_MINE",
         pos=(0.3, 0.0, 10.0), yaw=math.atan2(-0.3, 30.0), pitch=-0.04,
         elapsed=430.0, is_snowing=False, fp_tool="TORCH", torch_lit=True,
         wildlife=[dict(kind="TIMBER_WOLF", x=-2.5, z=-6.0, heading=1.9)],
         nodes=[dict(kind="COAL_SEAM", x=-2.4, z=-4.0, available=True),
                dict(kind="IRON_VEIN", x=2.6, z=-7.5, available=True),
                dict(kind="SPECIAL_CACHE", x=-8.0, z=10.0, available=True)],
         terrain_steps=68, perimeter=32, snow_particles=60, tex_size=256),
    dict(name="06-hunters-cabin-sunset.png", location="HUNTERS_CABIN",
         pos=(0.8, 0.0, 1.2), yaw=math.atan2(6.4, -7.6), pitch=-0.02,
         elapsed=335.0, is_snowing=True, fp_tool="AXE",
         wildlife=[dict(kind="NORTHERN_DEER", x=1.8, z=6.5, heading=2.8),
                   dict(kind="TIMBER_WOLF", x=-4.5, z=8.0, heading=0.4)],
         nodes=[dict(kind="PINE_TREE", x=-2.5, z=2.0, available=True),
                dict(kind="PINE_TREE", x=-1.5, z=9.5, available=True),
                dict(kind="GAME_CARCASS", x=2.8, z=11.2, available=True),
                dict(kind="SPECIAL_CACHE", x=9.0, z=9.5, available=True)],
         terrain_steps=68, perimeter=42, snow_particles=185, tex_size=256),
    dict(name="07-aurora-night.png", location="BASE_CLEARING",
         pos=(3.5, 0.0, 4.0), yaw=math.atan2(-3.5, 26.0), pitch=0.30,
         elapsed=455.0, is_snowing=True, fp_tool="TORCH", torch_lit=True, campfire_level=2,
         wildlife=[dict(kind="SNOW_HARE", x=1.2, z=1.8, heading=1.0)],
         nodes=[dict(kind="PINE_TREE", x=-3.5, z=-9.0, available=True),
                dict(kind="PINE_TREE", x=4.8, z=-8.2, available=True),
                dict(kind="SPECIAL_CACHE", x=4.2, z=-3.6, available=True)],
         terrain_steps=68, perimeter=42, snow_particles=185, tex_size=256),
]


def main():
    print("Building raster binary:", RASTER)
    for shot in SHOTS:
        print("== Render", shot["name"], "==")
        vp, cam, uniforms, tex_list, batches, sky = build_shot(shot)
        scene_path = "/tmp/wf_scene.bin"
        sky_path = "/tmp/wf_sky.rgb"
        out_path = "/tmp/wf_out.rgb"
        write_scene(scene_path, vp, cam, uniforms, tex_list, batches)
        sky.tofile(sky_path)
        res = subprocess.run([RASTER, scene_path, sky_path, out_path], capture_output=True, text=True)
        if res.returncode != 0:
            print(res.stdout, res.stderr)
            sys.exit(1)
        raw = np.fromfile(out_path, dtype=np.uint8)
        img = raw.reshape(H, W, 3)
        out_png = os.path.join(OUT_DIR, shot["name"])
        Image.fromarray(img, "RGB").save(out_png, optimize=True)
        print("   saved", out_png, os.path.getsize(out_png), "bytes")
    print("ALL 7 LANDSCAPE RENDERS DONE")


if __name__ == "__main__":
    main()
