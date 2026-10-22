#!/usr/bin/env python3
"""
Генератор графических материалов для Google Play и RuStore (Winter Forest):
- store/icon-512x512.png (512x512 PNG, без прозрачных полей по контуру — требование RuStore и Google Play)
- store/feature-graphic-1024x500.png (1024x500 PNG — промо-баннер карточки магазина)
- store/screenshots/01..06-*.png (6 вертикальных рендеров 1080x1920 PNG < 3 МБ на базе
  фактических процедурных текстур FBM/Вороного, 3D-перспективы, освещения, тумана и Compose UI игры).

Важное примечание честности:
Так как среда сборки изолирована без подключённого физического Android-устройства/GPU-эмулятора,
кадры отрендерены программным растеризатором по тем же формулам рельефа, процедурных текстур
(Перлин/FBM + Вороной), освещения, тумана и верстки HUD/Compose-экранов из модуля :app-winterforest.
"""

from __future__ import annotations

import math
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
STORE_DIR = ROOT / "store"
SHOTS_DIR = STORE_DIR / "screenshots"

FONT_REGULAR = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(FONT_BOLD if bold else FONT_REGULAR, size)


def hex_rgb(hex_val: int) -> np.ndarray:
    return np.array(
        [((hex_val >> 16) & 0xFF) / 255.0, ((hex_val >> 8) & 0xFF) / 255.0, (hex_val & 0xFF) / 255.0],
        dtype=np.float32,
    )


def value_noise_2d(u: np.ndarray, v: np.ndarray, seed: int) -> np.ndarray:
    ix = np.floor(u).astype(np.int64)
    iy = np.floor(v).astype(np.int64)
    fx = u - ix
    fy = v - iy
    ux = fx * fx * (3.0 - 2.0 * fx)
    uy = fy * fy * (3.0 - 2.0 * fy)

    def h(x: np.ndarray, y: np.ndarray) -> np.ndarray:
        n = (x * 374761393 + y * 668265263 + seed * 1442695041) & 0xFFFFFFFF
        n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
        n = n ^ (n >> 16)
        return (n & 0x7FFFFFFF).astype(np.float32) / float(0x7FFFFFFF)

    n00 = h(ix, iy)
    n10 = h(ix + 1, iy)
    n01 = h(ix, iy + 1)
    n11 = h(ix + 1, iy + 1)
    nx0 = n00 + (n10 - n00) * ux
    nx1 = n01 + (n11 - n01) * ux
    return nx0 + (nx1 - nx0) * uy


def fbm_2d(u: np.ndarray, v: np.ndarray, seed: int = 1337, octaves: int = 5) -> np.ndarray:
    total = np.zeros_like(u, dtype=np.float32)
    amp = 0.5
    freq = 1.0
    norm = 0.0
    for o in range(octaves):
        total += amp * value_noise_2d(u * freq, v * freq, seed + o * 977)
        norm += amp
        amp *= 0.5
        freq *= 2.0
    return total / max(norm, 1e-5)


def voronoi_edge_2d(u: np.ndarray, v: np.ndarray, seed: int = 607) -> np.ndarray:
    ix = np.floor(u).astype(np.int64)
    iy = np.floor(v).astype(np.int64)
    f1 = np.full_like(u, 99.0, dtype=np.float32)
    f2 = np.full_like(u, 99.0, dtype=np.float32)
    for oy in (-1, 0, 1):
        for ox in (-1, 0, 1):
            cx = ix + ox
            cy = iy + oy
            h1 = ((cx * 374761393 + cy * 668265263 + seed * 977) & 0x7FFFFFFF).astype(np.float32) / float(0x7FFFFFFF)
            h2 = (((cx + 57) * 668265263 + (cy - 31) * 374761393 + seed * 313) & 0x7FFFFFFF).astype(np.float32) / float(0x7FFFFFFF)
            dx = cx + h1 - u
            dy = cy + h2 - v
            d = np.sqrt(dx * dx + dy * dy)
            mask1 = d < f1
            f2 = np.where(mask1, f1, np.minimum(f2, d))
            f1 = np.where(mask1, d, f1)
    return np.clip(f2 - f1, 0.0, 1.0)


def render_3d_scene(
    width: int,
    height: int,
    zenith_hex: int,
    horizon_hex: int,
    fog_hex: int,
    snow_hex: int,
    sun_hex: int,
    fog_near: float,
    fog_far: float,
    mode: str,
    sun_uv: tuple[float, float] = (0.68, 0.24),
    snow_particles: int = 80,
) -> Image.Image:
    """Честный 3D-рендер неба, рельефа с процедурными текстурами, освещением, точечным светом костра/факела и 3D-объектами."""
    rw, rh = width // 2, height // 2
    yy, xx = np.mgrid[0:rh, 0:rw].astype(np.float32)
    ndc_x = (xx / rw) * 2.0 - 1.0
    ndc_y = 1.0 - (yy / rh) * 2.0
    aspect = rw / float(rh)
    fov_tan = math.tan(math.radians(66.0 * 0.5))

    zenith = hex_rgb(zenith_hex)
    horizon = hex_rgb(horizon_hex)
    fog_col = hex_rgb(fog_hex)
    snow_tint = hex_rgb(snow_hex)
    sun_col = hex_rgb(sun_hex)

    # 1. Sky gradient + sun/moon disc & halo
    v_norm = np.clip(1.0 - yy / rh, 0.0, 1.0)
    t_sky = np.power(v_norm, 0.72)[..., None]
    frame = horizon[None, None, :] * (1.0 - t_sky) + zenith[None, None, :] * t_sky

    su = xx / rw - sun_uv[0]
    sv = (yy / rh - sun_uv[1]) * (rh / float(rw))
    sdist = np.sqrt(su * su + sv * sv)
    halo = (np.exp(-sdist * 8.5) * 0.42 + np.clip((0.042 - sdist) * 26.0, 0.0, 0.9))[..., None]
    frame = np.clip(frame + sun_col[None, None, :] * halo, 0.0, 1.0)

    # 2. Distant mountain silhouettes above horizon
    horizon_y = rh * 0.46
    mtn_profile = horizon_y - (
        28.0 * fbm_2d(xx * 0.011, np.zeros_like(xx), seed=71, octaves=4)
        + 14.0 * np.sin(xx * 0.025 + 1.2)
    )
    mtn_mask = (yy >= mtn_profile) & (yy <= horizon_y + 10)
    mtn_shade = np.clip(0.55 + 0.45 * fbm_2d(xx * 0.05, yy * 0.05, seed=99, octaves=3), 0.0, 1.0)[..., None]
    mtn_rgb = fog_col[None, None, :] * 0.62 + snow_tint[None, None, :] * 0.32 * mtn_shade
    frame = np.where(mtn_mask[..., None], np.clip(mtn_rgb, 0.0, 1.0), frame)

    # 3. Perspective ground plane raycast with procedural textures + directional & point lighting + fog
    cam_pos = np.array([0.0, 1.75, 8.5], dtype=np.float32)
    pitch = -0.09
    cp, sp = math.cos(pitch), math.sin(pitch)
    rx = ndc_x * aspect * fov_tan
    ry = ndc_y * fov_tan
    rz = -np.ones_like(rx)
    dir_x = rx
    dir_y = ry * cp + rz * sp
    dir_z = -ry * sp + rz * cp
    inv_len = 1.0 / np.sqrt(dir_x * dir_x + dir_y * dir_y + dir_z * dir_z)
    dir_x *= inv_len
    dir_y *= inv_len
    dir_z *= inv_len

    ground_hit = dir_y < -0.012
    t_hit = np.where(ground_hit, -cam_pos[1] / np.minimum(dir_y, -0.012), 1e4)
    wx = cam_pos[0] + dir_x * t_hit
    wz = cam_pos[2] + dir_z * t_hit

    if mode == "lake":
        edges = voronoi_edge_2d(wx * 0.55, wz * 0.55, seed=607)
        cracks = np.clip(1.0 - edges * 5.8, 0.0, 1.0)
        dnoise = fbm_2d(wx * 0.45, wz * 0.45, seed=613, octaves=4)
        tex_r = 0.54 + 0.18 * dnoise + 0.40 * cracks
        tex_g = 0.78 + 0.13 * dnoise + 0.22 * cracks
        tex_b = 0.92 + 0.07 * dnoise + 0.10 * cracks
        shore_mask = (wx * wx + (wz + 8.0) ** 2) > 22.0**2
        snow_n = fbm_2d(wx * 0.8, wz * 1.1, seed=31, octaves=5)
        tex_r = np.where(shore_mask, snow_tint[0] * (0.78 + 0.24 * snow_n), tex_r)
        tex_g = np.where(shore_mask, snow_tint[1] * (0.78 + 0.24 * snow_n), tex_g)
        tex_b = np.where(shore_mask, snow_tint[2] * (0.80 + 0.24 * snow_n), tex_b)
    elif mode == "mine":
        edges = voronoi_edge_2d(wx * 1.15, wz * 1.15, seed=409)
        grain = fbm_2d(wx * 2.2, wz * 2.2, seed=419, octaves=4)
        luma = np.clip(0.24 + 0.34 * edges * 1.4 + 0.22 * grain, 0.08, 0.9)
        tex_r, tex_g, tex_b = luma * 0.88, luma * 0.90, luma * 0.98
    else:
        drift = fbm_2d(wx * 0.65, wz * 1.05, seed=1337, octaves=5)
        ripple = 0.5 + 0.5 * np.sin((wx * 0.4 + wz * 1.7 + drift * 2.2) * 3.14159)
        glint = (voronoi_edge_2d(wx * 2.5, wz * 2.5, seed=17) > 0.72).astype(np.float32) * 0.10
        shade = np.clip(0.74 + 0.18 * drift + 0.08 * ripple + glint, 0.0, 1.08)
        tex_r = snow_tint[0] * shade
        tex_g = snow_tint[1] * shade
        tex_b = snow_tint[2] * shade * 1.02

    albedo = np.stack([tex_r, tex_g, tex_b], axis=-1)

    # Point light (campfire on base/menu, resin torch in mine)
    if mode in ("base", "menu"):
        lx, ly, lz, lrad, lpow = 0.0, 1.1, 0.2, 16.5, 1.15
    elif mode == "mine":
        lx, ly, lz, lrad, lpow = 1.1, 1.4, 5.2, 17.5, 1.35
    else:
        lx, ly, lz, lrad, lpow = 0.0, -999.0, 0.0, 1.0, 0.0

    ldist = np.sqrt((wx - lx) ** 2 + ly * ly + (wz - lz) ** 2)
    atten = np.clip(1.0 - ldist / lrad, 0.0, 1.0) ** 2 * lpow
    amb = 0.22 if mode == "mine" else 0.72
    lit = albedo * (
        (horizon * amb + sun_col * (0.08 if mode == "mine" else 0.36))[None, None, :]
        + np.array([1.0, 0.66, 0.30], dtype=np.float32)[None, None, :] * atten[..., None]
    )
    fog_f = np.clip((t_hit - fog_near) / max(fog_far - fog_near, 0.1), 0.0, 1.0)[..., None]
    ground_rgb = lit * (1.0 - fog_f) + fog_col[None, None, :] * fog_f
    frame = np.where(ground_hit[..., None], np.clip(ground_rgb, 0.0, 1.0), frame)

    img = Image.fromarray((frame * 255.0).astype(np.uint8), mode="RGB").resize(
        (width, height), resample=Image.Resampling.BILINEAR
    )
    draw = ImageDraw.Draw(img, "RGBA")

    def project(px: float, py: float, pz: float) -> tuple[float, float, float] | None:
        dx = px - cam_pos[0]
        dy = py - cam_pos[1]
        dz = pz - cam_pos[2]
        cy = dy * cp - dz * sp
        cz = dy * sp + dz * cp
        if cz >= -0.35:
            return None
        depth = -cz
        sx = (dx / (depth * aspect * fov_tan) * 0.5 + 0.5) * width
        sy = (0.5 - (cy / (depth * fov_tan)) * 0.5) * height
        scale = height / (2.0 * depth * fov_tan)
        return sx, sy, scale, depth

    def fog_blend(rgb: tuple[int, int, int], depth: float, alpha: int = 255) -> tuple[int, int, int, int]:
        f = max(0.0, min(1.0, (depth - fog_near) / max(fog_far - fog_near, 0.1)))
        fc = (fog_col * 255.0).astype(int)
        return (
            int(rgb[0] * (1 - f) + fc[0] * f),
            int(rgb[1] * (1 - f) + fc[1] * f),
            int(rgb[2] * (1 - f) + fc[2] * f),
            alpha,
        )

    def draw_spruce(tx: float, tz: float, h_scale: float = 1.0) -> None:
        p = project(tx, 0.0, tz)
        if p is None:
            return
        sx, sy, sc, depth = p
        # Shadow ellipse on snow
        srw, srh = 1.9 * sc * h_scale, 0.42 * sc * h_scale
        draw.ellipse((sx - srw, sy - srh, sx + srw, sy + srh), fill=(12, 24, 38, int(92 * max(0.15, 1.0 - depth / 45.0))))
        # Bark trunk
        tw, th = max(3.0, 0.28 * sc * h_scale), 2.1 * sc * h_scale
        draw.rectangle((sx - tw, sy - th, sx + tw, sy), fill=fog_blend((72, 49, 35), depth))
        # Snow-laden pine tiers
        for tier_y, rad, th_h in ((1.25, 2.05, 2.2), (2.75, 1.58, 1.95), (4.05, 1.05, 1.75)):
            yb = sy - tier_y * sc * h_scale
            yt = yb - th_h * sc * h_scale
            rw_px = rad * sc * h_scale
            draw.polygon([(sx - rw_px, yb), (sx + rw_px, yb), (sx, yt)], fill=fog_blend((24, 62, 48), depth))
            # Crisp snow cap on upper boughs
            cap_y = yt + (yb - yt) * 0.44
            cap_w = rw_px * 0.46
            draw.polygon([(sx - cap_w, cap_y), (sx + cap_w, cap_y), (sx, yt)], fill=fog_blend((232, 244, 252), depth))

    def draw_cabin(cx: float, cz: float, w_m: float = 4.2, h_m: float = 2.7, warm_window: bool = True) -> None:
        p = project(cx, 0.0, cz)
        if p is None:
            return
        sx, sy, sc, depth = p
        hw = w_m * 0.5 * sc
        hh = h_m * sc
        draw.ellipse((sx - hw * 1.2, sy - sc * 0.35, sx + hw * 1.2, sy + sc * 0.35), fill=(12, 22, 34, 95))
        draw.rectangle((sx - hw, sy - hh, sx + hw, sy), fill=fog_blend((98, 66, 42), depth))
        for i in range(1, 6):
            ly = sy - hh * (i / 6.0)
            draw.line((sx - hw, ly, sx + hw, ly), fill=fog_blend((58, 38, 24), depth), width=max(1, int(sc * 0.03)))
        # Peaked snow roof
        roof_top = sy - hh - 1.35 * sc
        draw.polygon([(sx - hw * 1.14, sy - hh), (sx + hw * 1.14, sy - hh), (sx, roof_top)], fill=fog_blend((236, 246, 253), depth))
        if warm_window:
            ww = hw * 0.28
            wy = sy - hh * 0.58
            draw.rectangle((sx - ww, wy - ww * 0.8, sx + ww, wy + ww * 0.8), fill=fog_blend((255, 196, 98), depth))

    def draw_rock(rx_m: float, rz_m: float, tint=(118, 128, 140), size_m: float = 1.2) -> None:
        p = project(rx_m, 0.0, rz_m)
        if p is None:
            return
        sx, sy, sc, depth = p
        r = size_m * sc * 0.55
        draw.polygon(
            [(sx - r, sy), (sx - r * 0.72, sy - r * 0.92), (sx + r * 0.22, sy - r * 1.05), (sx + r * 0.95, sy - r * 0.55), (sx + r, sy)],
            fill=fog_blend(tint, depth),
        )
        draw.polygon(
            [(sx - r * 0.70, sy - r * 0.92), (sx + r * 0.22, sy - r * 1.05), (sx + r * 0.58, sy - r * 0.76), (sx - r * 0.28, sy - r * 0.72)],
            fill=fog_blend((226, 238, 248), depth),
        )

    def draw_campfire(fx: float, fz: float) -> None:
        p = project(fx, 0.0, fz)
        if p is None:
            return
        sx, sy, sc, depth = p
        # Warm light pool on snow
        glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        gd = ImageDraw.Draw(glow)
        gr = int(3.2 * sc)
        gd.ellipse((sx - gr, sy - int(gr * 0.35), sx + gr, sy + int(gr * 0.35)), fill=(255, 152, 56, 86))
        gd.ellipse((sx - int(gr * 0.5), sy - int(gr * 1.15), sx + int(gr * 0.5), sy + int(gr * 0.2)), fill=(255, 180, 72, 110))
        glow = glow.filter(ImageFilter.GaussianBlur(radius=28))
        img.paste(Image.alpha_composite(img.convert("RGBA"), glow).convert("RGB"))
        nonlocal draw
        draw = ImageDraw.Draw(img, "RGBA")
        for ox in (-0.85, -0.5, -0.15, 0.2, 0.55, 0.85):
            draw.ellipse((sx + ox * sc - 0.18 * sc, sy - 0.14 * sc, sx + ox * sc + 0.18 * sc, sy + 0.14 * sc), fill=(95, 102, 112, 255))
        draw.polygon([(sx - 0.65 * sc, sy + 0.05 * sc), (sx + 0.65 * sc, sy - 0.18 * sc), (sx + 0.72 * sc, sy - 0.05 * sc), (sx - 0.58 * sc, sy + 0.18 * sc)], fill=(76, 48, 30, 255))
        draw.polygon([(sx - 0.45 * sc, sy), (sx + 0.45 * sc, sy), (sx + 0.10 * sc, sy - 1.35 * sc), (sx - 0.14 * sc, sy - 1.15 * sc)], fill=(248, 146, 52, 235))
        draw.polygon([(sx - 0.25 * sc, sy), (sx + 0.25 * sc, sy), (sx, sy - 0.92 * sc)], fill=(255, 232, 118, 245))

    def draw_special_chest(cx: float, cz: float) -> None:
        p = project(cx, 0.0, cz)
        if p is None:
            return
        sx, sy, sc, depth = p
        w, h = 0.58 * sc, 0.68 * sc
        draw.rectangle((sx - w, sy - h, sx + w, sy), fill=fog_blend((138, 92, 46), depth))
        draw.rectangle((sx - w * 1.05, sy - h * 1.15, sx + w * 1.05, sy - h * 0.82), fill=fog_blend((196, 152, 74), depth))
        draw.polygon([(sx, sy - h * 2.05), (sx - 0.22 * sc, sy - h * 1.45), (sx + 0.22 * sc, sy - h * 1.45)], fill=(255, 224, 102, 230))

    # Populate scene back-to-front by Z
    if mode in ("base", "menu"):
        trees = [(-18, -28), (-10, -32), (2, -34), (13, -30), (21, -26), (-15, -16), (15, -17), (-11, -6), (11, -5)]
        for tx, tz in sorted(trees, key=lambda item: item[1]):
            draw_spruce(tx, tz, 1.08)
        draw_cabin(-5.8, -5.8, 4.4, 2.75, warm_window=True)
        draw_rock(5.4, -3.2, size_m=1.15)
        draw_special_chest(3.3, 1.4)
        draw_campfire(0.0, 0.5)
    elif mode == "spruce":
        trees = [
            (-16, -26), (-9, -29), (-2, -27), (6, -30), (14, -25),
            (-13, -17), (-5, -19), (4, -18), (11, -16),
            (-9, -10), (-3.6, -11.5), (3.8, -10.5), (9.2, -9.0),
            (-6.2, -4.2), (5.6, -3.5),
        ]
        for tx, tz in sorted(trees, key=lambda item: item[1]):
            draw_spruce(tx, tz, 1.18)
        draw_rock(-2.4, 0.8, tint=(96, 72, 48), size_m=1.05)
        draw_special_chest(2.8, -1.2)
    elif mode == "lake":
        for tx, tz in [(-22, -32), (-14, -35), (-4, -37), (8, -36), (18, -33), (24, -28)]:
            draw_spruce(tx, tz, 1.0)
        draw_cabin(10.5, -18.5, 3.6, 2.3, warm_window=True)
        # Ice fishing hole in foreground
        p_hole = project(-1.4, 0.0, 1.5)
        if p_hole is not None:
            hx, hy, hsc, _ = p_hole
            draw.ellipse((hx - 0.85 * hsc, hy - 0.28 * hsc, hx + 0.85 * hsc, hy + 0.28 * hsc), fill=(22, 62, 92, 255), outline=(218, 244, 255, 255), width=4)
        draw_special_chest(3.4, -2.5)
    elif mode == "cabin":
        trees = [(-19, -28), (-11, -31), (1, -33), (15, -29), (22, -25), (-14, -15), (-7, -8), (15, -12)]
        for tx, tz in sorted(trees, key=lambda item: item[1]):
            draw_spruce(tx, tz, 1.12)
        draw_cabin(4.8, -11.5, 5.0, 3.0, warm_window=True)
        draw_rock(-4.5, -2.5, tint=(124, 114, 112), size_m=1.35)
        draw_special_chest(-1.2, -1.0)
    elif mode == "mine":
        for z_arch in (-16.0, -9.0, -3.0, 2.5):
            pa = project(0.0, 0.0, z_arch)
            if pa is not None:
                ax, ay, asc, adepth = pa
                col_timber = fog_blend((96, 66, 40), adepth)
                hw = 3.1 * asc
                hh = 3.2 * asc
                pw = max(6, int(0.36 * asc))
                draw.rectangle((ax - hw - pw, ay - hh, ax - hw + pw, ay), fill=col_timber)
                draw.rectangle((ax + hw - pw, ay - hh, ax + hw + pw, ay), fill=col_timber)
                draw.rectangle((ax - hw - pw, ay - hh - pw, ax + hw + pw, ay - hh + pw), fill=col_timber)
        draw_rock(-2.2, 0.2, tint=(44, 48, 56), size_m=1.35)
        draw_rock(2.5, -1.4, tint=(156, 102, 68), size_m=1.25)
        draw_special_chest(0.2, -0.8)

    # First-person tool / lit resin torch on gameplay screenshots
    if mode in ("base", "spruce", "lake", "mine", "cabin"):
        if mode == "mine":
            # Glowing resin torch in right hand
            glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
            gd = ImageDraw.Draw(glow)
            gd.ellipse((width - 420, height - 700, width + 40, height - 180), fill=(255, 168, 62, 115))
            glow = glow.filter(ImageFilter.GaussianBlur(radius=42))
            img.paste(Image.alpha_composite(img.convert("RGBA"), glow).convert("RGB"))
            draw = ImageDraw.Draw(img, "RGBA")
            draw.polygon([(width - 210, height - 90), (width - 175, height - 90), (width - 205, height - 420), (width - 238, height - 420)], fill=(104, 68, 42, 255))
            draw.polygon([(width - 252, height - 415), (width - 188, height - 415), (width - 205, height - 560), (width - 238, height - 530)], fill=(252, 158, 54, 245))
            draw.polygon([(width - 238, height - 420), (width - 202, height - 420), (width - 220, height - 515)], fill=(255, 238, 130, 255))
        else:
            # First-person survival hatchet
            draw.polygon([(width - 195, height - 70), (width - 160, height - 70), (width - 205, height - 395), (width - 236, height - 395)], fill=(116, 78, 48, 255))
            draw.polygon([(width - 285, height - 405), (width - 190, height - 420), (width - 185, height - 355), (width - 275, height - 365)], fill=(176, 190, 204, 255))

    # Falling snow particles
    rng = np.random.default_rng(2026 + len(mode))
    for _ in range(snow_particles):
        px = int(rng.integers(0, width))
        py = int(rng.integers(0, height))
        pr = int(rng.integers(2, 6))
        alpha = int(rng.integers(110, 225))
        draw.ellipse((px - pr, py - pr, px + pr, py + pr), fill=(240, 248, 255, alpha))

    return img


def draw_hud_overlay(
    img: Image.Image,
    location_title: str,
    subtitle_line: str,
    compass_line: str,
    hp: tuple[int, int],
    hunger: int,
    warmth: int,
    xp_pair: tuple[int, int],
    level: int,
    slots_used: int,
    status_text: str,
    gather_label: str,
    show_return_base: bool,
    timer_banner: tuple[str, bool] | None = None,
) -> Image.Image:
    rgba = img.convert("RGBA")
    overlay = Image.new("RGBA", rgba.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(overlay)
    w, h = rgba.size

    # Top row: Location badge + Base/Menu buttons
    d.rounded_rectangle((32, 54, 590, 146), radius=22, fill=(11, 23, 34, 212), outline=(124, 198, 254, 90), width=2)
    d.text((54, 68), location_title, font=font(31, bold=True), fill=(237, 246, 252, 255))
    d.text((54, 108), subtitle_line, font=font(21), fill=(124, 198, 254, 255))

    d.rounded_rectangle((612, 62, 886, 138), radius=20, fill=(124, 198, 254, 235))
    d.text((634, 86), f"База / Рюкзак ({slots_used}/20)", font=font(20, bold=True), fill=(8, 22, 34, 255))

    d.rounded_rectangle((900, 62, 1048, 138), radius=20, fill=(16, 32, 46, 220), outline=(124, 198, 254, 180), width=2)
    d.text((940, 86), "Меню", font=font(21, bold=True), fill=(237, 246, 252, 255))

    # Compass row
    d.rounded_rectangle((32, 160, 630, 214), radius=18, fill=(12, 24, 36, 196))
    d.text((52, 174), compass_line, font=font(21, bold=True), fill=(82, 183, 136, 255))

    # 4 Survival bars panel
    d.rounded_rectangle((32, 228, 1048, 386), radius=24, fill=(11, 22, 32, 198))

    def stat_bar(bx: int, by: int, bw: int, label: str, val_txt: str, frac: float, color: tuple[int, int, int, int]) -> None:
        d.text((bx, by), label, font=font(21, bold=True), fill=(237, 246, 252, 255))
        tw = d.textlength(val_txt, font=font(21))
        d.text((bx + bw - tw, by), val_txt, font=font(21), fill=(210, 228, 240, 255))
        d.rounded_rectangle((bx, by + 30, bx + bw, by + 46), radius=8, fill=(255, 255, 255, 68))
        fill_w = max(14, int(bw * max(0.0, min(1.0, frac))))
        d.rounded_rectangle((bx, by + 30, bx + fill_w, by + 46), radius=8, fill=color)

    stat_bar(58, 246, 462, "Здоровье", f"{hp[0]}/{hp[1]}", hp[0] / float(hp[1]), (231, 111, 81, 255))
    stat_bar(560, 246, 462, "Голод", f"{hunger}/100", hunger / 100.0, (244, 162, 97, 255))
    stat_bar(58, 316, 462, "Тепло", f"{warmth}/100", warmth / 100.0, (124, 198, 254, 255))
    stat_bar(560, 316, 462, f"Опыт (Ур. {level})", f"{xp_pair[0]}/{xp_pair[1]}", xp_pair[0] / float(xp_pair[1]), (82, 183, 136, 255))

    # Status line
    d.rounded_rectangle((32, 400, 1048, 458), radius=16, fill=(17, 34, 49, 210))
    d.text((54, 415), status_text, font=font(21), fill=(237, 246, 252, 255))

    # Optional active timer card with optional rewarded skip CTA
    if timer_banner is not None:
        timer_txt, show_ad_cta = timer_banner
        d.rounded_rectangle((32, 472, 1048, 548), radius=18, fill=(24, 47, 66, 226), outline=(244, 162, 97, 150), width=2)
        d.text((54, 496), timer_txt, font=font(21, bold=True), fill=(244, 162, 97, 255))
        if show_ad_cta:
            d.rounded_rectangle((620, 484, 1030, 536), radius=14, fill=(124, 198, 254, 240))
            d.text((642, 497), "Пропустить ожидание (реклама)", font=font(20, bold=True), fill=(8, 20, 32, 255))

    # Center crosshair reticle
    cx, cy = w // 2, h // 2 + 30
    d.ellipse((cx - 6, cy - 6, cx + 6, cy + 6), outline=(237, 246, 252, 190), width=2)

    # Bottom Left: Virtual Joystick
    jx, jy, jr = 185, h - 205, 132
    d.ellipse((jx - jr, jy - jr, jx + jr, jy + jr), fill=(16, 32, 46, 138), outline=(124, 198, 254, 165), width=4)
    d.ellipse((jx - 48 + 18, jy - 48 - 22, jx + 48 + 18, jy + 48 - 22), fill=(124, 198, 254, 214))

    # Bottom Right: Action Buttons
    if show_return_base:
        d.rounded_rectangle((820, h - 368, 1044, h - 304), radius=20, fill=(16, 32, 46, 224), outline=(124, 198, 254, 185), width=2)
        d.text((878, h - 349), "На базу", font=font(23, bold=True), fill=(237, 246, 252, 255))

    gw = max(330, int(d.textlength(gather_label, font=font(24, bold=True))) + 64)
    d.rounded_rectangle((1044 - gw, h - 284, 1044, h - 206), radius=24, fill=(82, 183, 136, 242))
    d.text((1044 - gw + 32, h - 260), gather_label, font=font(24, bold=True), fill=(8, 28, 18, 255))

    d.rounded_rectangle((596, h - 186, 836, h - 110), radius=24, fill=(244, 162, 97, 242))
    d.text((632, h - 162), "Использовать", font=font(23, bold=True), fill=(26, 16, 6, 255))

    d.rounded_rectangle((854, h - 186, 1044, h - 110), radius=24, fill=(124, 198, 254, 242))
    d.text((898, h - 162), "Ударить", font=font(23, bold=True), fill=(8, 22, 34, 255))

    return Image.alpha_composite(rgba, overlay).convert("RGB")


def generate_icon_512() -> None:
    size = 512
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    t = np.clip(yy / size, 0.0, 1.0)[..., None]
    top = hex_rgb(0x0B1D2E)
    mid = hex_rgb(0x183E59)
    bg = top[None, None, :] * (1.0 - t) + mid[None, None, :] * t
    aurora = np.exp(-((yy - 135.0) / 58.0) ** 2) * (0.5 + 0.5 * np.sin(xx * 0.022))
    bg += np.array([0.06, 0.34, 0.32], dtype=np.float32)[None, None, :] * aurora[..., None] * 0.55
    img = Image.fromarray((np.clip(bg, 0.0, 1.0) * 255).astype(np.uint8), "RGB")
    d = ImageDraw.Draw(img, "RGBA")

    # Full-bleed winter moon, mountains, snow slope, spruce silhouettes & central hearth fire
    d.ellipse((326, 58, 418, 150), fill=(238, 248, 255, 245))
    d.polygon([(0, 325), (145, 205), (275, 290), (402, 182), (512, 268), (512, 512), (0, 512)], fill=(22, 50, 72, 255))
    d.polygon([(0, 372), (180, 338), (355, 360), (512, 332), (512, 512), (0, 512)], fill=(222, 240, 252, 255))

    for cx, base_y, sc in ((128, 392, 1.05), (392, 384, 0.96)):
        d.rectangle((cx - 12, base_y - 60, cx + 12, base_y + 8), fill=(68, 44, 30, 255))
        for off_y, rw, rh in ((44, 88, 92), (108, 68, 84), (164, 46, 72)):
            d.polygon([(cx - rw, base_y - off_y), (cx + rw, base_y - off_y), (cx, base_y - off_y - rh)], fill=(20, 68, 52, 255))
            d.polygon([(cx - rw * 0.45, base_y - off_y - rh * 0.56), (cx + rw * 0.45, base_y - off_y - rh * 0.56), (cx, base_y - off_y - rh)], fill=(238, 248, 255, 255))

    # Central campfire warm glow + logs + flame
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse((150, 250, 362, 456), fill=(255, 154, 58, 128))
    glow = glow.filter(ImageFilter.GaussianBlur(26))
    img = Image.alpha_composite(img.convert("RGBA"), glow).convert("RGB")
    d = ImageDraw.Draw(img, "RGBA")

    d.polygon([(190, 418), (322, 390), (330, 410), (198, 438)], fill=(88, 56, 36, 255))
    d.polygon([(198, 390), (330, 418), (322, 438), (190, 410)], fill=(66, 42, 27, 255))
    d.polygon([(256, 244), (210, 344), (226, 398), (286, 398), (302, 344)], fill=(244, 132, 44, 255))
    d.polygon([(256, 286), (228, 352), (238, 394), (274, 394), (284, 352)], fill=(255, 226, 98, 255))

    img.save(STORE_DIR / "icon-512x512.png", format="PNG", optimize=True)


def generate_feature_graphic_1024x500() -> None:
    base = render_3d_scene(
        width=1024,
        height=500,
        zenith_hex=0x132E4A,
        horizon_hex=0x7AA7C7,
        fog_hex=0xAEC9DC,
        snow_hex=0xE8F3FB,
        sun_hex=0xFFE4C4,
        fog_near=14.0,
        fog_far=52.0,
        mode="base",
        sun_uv=(0.76, 0.25),
        snow_particles=55,
    ).convert("RGBA")

    ov = Image.new("RGBA", base.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    d.rounded_rectangle((38, 54, 624, 446), radius=28, fill=(9, 19, 30, 208), outline=(124, 198, 254, 120), width=2)
    d.text((70, 86), "WINTER FOREST", font=font(52, bold=True), fill=(237, 246, 252, 255))
    d.text((70, 154), "3D-выживание в заснеженном лесу", font=font(26, bold=True), fill=(244, 162, 97, 255))
    bullets = [
        "• 5 уникальных локаций и редкие находки",
        "• Голод, тепло, костёр и смена дня и ночи",
        "• Прокачка укрытия, костра и верстака",
        "• Процедурные 3D-текстуры снега, коры и льда",
    ]
    y = 216
    for line in bullets:
        d.text((70, y), line, font=font(21), fill=(208, 228, 242, 255))
        y += 42
    badge_txt = "Офлайн-прогресс • Без P2W"
    badge_w = int(d.textlength(badge_txt, font=font(18, bold=True))) + 48
    d.rounded_rectangle((70, 388, 70 + badge_w, 428), radius=14, fill=(82, 183, 136, 235))
    d.text((94, 396), badge_txt, font=font(18, bold=True), fill=(6, 24, 15, 255))

    out = Image.alpha_composite(base, ov).convert("RGB")
    out.save(STORE_DIR / "feature-graphic-1024x500.png", format="PNG", optimize=True)


def generate_main_menu_shot() -> None:
    bg = render_3d_scene(
        width=1080,
        height=1920,
        zenith_hex=0x102438,
        horizon_hex=0x5D84A3,
        fog_hex=0x8FAEC4,
        snow_hex=0xDCEBF7,
        sun_hex=0xFFE0B8,
        fog_near=14.0,
        fog_far=50.0,
        mode="menu",
        snow_particles=65,
    ).convert("RGBA")

    ov = Image.new("RGBA", bg.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)

    d.rounded_rectangle((84, 130, 996, 470), radius=32, fill=(9, 19, 30, 214), outline=(124, 198, 254, 110), width=2)
    title = "WINTER FOREST"
    tw = d.textlength(title, font=font(64, bold=True))
    d.text(((1080 - tw) / 2, 166), title, font=font(64, bold=True), fill=(237, 246, 252, 255))
    sub = "Выживание в заснеженном северном лесу"
    sw = d.textlength(sub, font=font(26))
    d.text(((1080 - sw) / 2, 252), sub, font=font(26), fill=(124, 198, 254, 255))

    d.rounded_rectangle((124, 314, 956, 434), radius=22, fill=(26, 47, 64, 235))
    stats = [("8 дн.", "Рекорд"), ("Ур. 9", "База"), ("4 / 5", "Коллекция")]
    for idx, (val_s, lbl_s) in enumerate(stats):
        cx = 262 + idx * 278
        vw = d.textlength(val_s, font=font(34, bold=True))
        lw = d.textlength(lbl_s, font=font(21))
        d.text((cx - vw / 2, 332), val_s, font=font(34, bold=True), fill=(237, 246, 252, 255))
        d.text((cx - lw / 2, 382), lbl_s, font=font(21), fill=(168, 194, 212, 255))

    buttons = [
        ("Продолжить (День 4 • Базовая поляна)", (124, 198, 254, 245), (8, 20, 32, 255)),
        ("Новая игра", (244, 162, 97, 245), (26, 16, 6, 255)),
        ("База, экипировка и коллекция", (18, 33, 46, 228), (237, 246, 252, 255)),
        ("Настройки", (18, 33, 46, 228), (237, 246, 252, 255)),
        ("Об игре", (18, 33, 46, 228), (237, 246, 252, 255)),
    ]
    by = 760
    for text_b, fill_c, txt_c in buttons:
        d.rounded_rectangle((110, by, 970, by + 108), radius=24, fill=fill_c, outline=(124, 198, 254, 140), width=2)
        bw_t = d.textlength(text_b, font=font(29, bold=True))
        d.text(((1080 - bw_t) / 2, by + 35), text_b, font=font(29, bold=True), fill=txt_c)
        by += 134

    # Bottom adaptive banner container ( ONLY on Main Menu )
    d.rounded_rectangle((40, 1748, 1040, 1882), radius=18, fill=(14, 26, 38, 238), outline=(124, 198, 254, 95), width=2)
    b_txt = "Адаптивный баннер РСЯ (только в главном меню при заданном ID)"
    btw = d.textlength(b_txt, font=font(22))
    d.text(((1080 - btw) / 2, 1802), b_txt, font=font(22), fill=(168, 194, 212, 255))

    Image.alpha_composite(bg, ov).convert("RGB").save(SHOTS_DIR / "01-main-menu.png", "PNG", optimize=True)


def generate_base_inventory_shot() -> None:
    img = Image.new("RGB", (1080, 1920), (9, 17, 25))
    d = ImageDraw.Draw(img, "RGBA")

    d.text((48, 66), "База и инвентарь", font=font(44, bold=True), fill=(237, 246, 252, 255))
    d.rounded_rectangle((856, 56, 1032, 128), radius=20, fill=(18, 33, 46, 255), outline=(124, 198, 254, 185), width=2)
    d.text((902, 76), "Назад", font=font(25, bold=True), fill=(237, 246, 252, 255))
    d.text((48, 134), "Термос установлен на базе (-15% потери тепла) • Тёплая шуба надета (-25%)", font=font(21), fill=(244, 162, 97, 255))

    # Card 1: Base upgrades
    d.rounded_rectangle((44, 184, 1036, 706), radius=26, fill=(26, 47, 64, 255))
    d.text((76, 210), "Прокачка базы (сохраняется между забегами)", font=font(28, bold=True), fill=(237, 246, 252, 255))
    upgrades = [
        ("Укрытие — Уровень 3/5", "Снижает скорость потери тепла на 8% за каждый уровень", "Улучшить (Дерево: 12, Ткань: 6, Камень: 6)"),
        ("Костёр — Уровень 4/5", "Ускоряет согревание у огня и готовку горячего пайка", "Улучшить (Камень: 16, Дерево: 12)"),
        ("Верстак — Уровень 3/5", "Открывает крафт Смоляного факела и рецептов Кирки", "Улучшить (Дерево: 15, Камень: 9, Железо: 2)"),
    ]
    uy = 266
    for u_title, u_desc, u_btn in upgrades:
        d.text((76, uy), u_title, font=font(24, bold=True), fill=(124, 198, 254, 255))
        d.text((76, uy + 34), u_desc, font=font(20), fill=(168, 194, 212, 255))
        d.rounded_rectangle((76, uy + 68, 690, uy + 118), radius=14, fill=(124, 198, 254, 235))
        d.text((98, uy + 80), u_btn, font=font(20, bold=True), fill=(8, 20, 32, 255))
        uy += 142

    # Card 2: Backpack + Special Loot Section
    d.rounded_rectangle((44, 734, 1036, 1216), radius=26, fill=(26, 47, 64, 255))
    d.text((76, 760), "Рюкзак (8/20 слотов) и склад базы", font=font(28, bold=True), fill=(237, 246, 252, 255))
    d.text((76, 808), "Дерево: 10/10   •   Рыба: 6/10   •   Уголь: 5/10   •   Смола: 4/10   •   Ткань: 7/10", font=font(22), fill=(237, 246, 252, 255))
    d.text((76, 848), "На складе базы: Дерево: 28 • Камень: 19 • Железо: 7 • Мясо: 4 • Шкуры: 5", font=font(21), fill=(124, 198, 254, 255))
    d.line((76, 896, 1004, 896), fill=(255, 255, 255, 60), width=2)
    d.text((76, 914), "Специальные предметы в инвентаре", font=font(26, bold=True), fill=(244, 162, 97, 255))
    specials_inv = [
        ("Смоляной факел ×2 (источник: Густой ельник / Верстак)", "+50% тепла и освещение тёмных штолен на 3 минуты"),
        ("Кирка ×1 (источник: Заброшенная шахта)", "Постоянный инструмент: открыты продвинутые рецепты верстака"),
        ("Тёплая шуба ×1 [Надета] (источник: Охотничий домик)", "Постоянная экипировка: снижает потерю тепла на 25%"),
    ]
    sy = 962
    for s_t, s_d in specials_inv:
        d.text((76, sy), s_t, font=font(22, bold=True), fill=(237, 246, 252, 255))
        d.text((76, sy + 30), s_d, font=font(19), fill=(168, 194, 212, 255))
        sy += 76

    # Card 3: Collection of Findings (4 out of 5)
    d.rounded_rectangle((44, 1244, 1036, 1872), radius=26, fill=(26, 47, 64, 255))
    d.text((76, 1272), "Коллекция находок: найдено 4 из 5", font=font(29, bold=True), fill=(82, 183, 136, 255))
    collection = [
        (True, "★ Старая карта (Базовая поляна)", "Открывает отображение всех 5 локаций на карте экспедиции"),
        (True, "★ Смоляной факел (Густой ельник)", "+50% сохранения тепла на 3 минуты, повторный крафт из смолы"),
        (True, "★ Термос (Замёрзшее озеро)", "Установлен на базе: снижает скорость потери тепла на 15%"),
        (True, "★ Кирка (Заброшенная шахта)", "Открывает продвинутые ступени и рецепты верстака"),
        (False, "☆ Тёплая шуба (Охотничий домик)", "Экипировка: снижает потерю тепла на 25% (выжить 3 дня + 5 ткани)"),
    ]
    cy = 1328
    for found, head_line, sub_line in collection:
        d.rounded_rectangle((72, cy, 1008, cy + 92), radius=16, fill=(16, 31, 44, 230))
        d.text((94, cy + 14), head_line, font=font(20, bold=True), fill=(237, 246, 252, 255) if found else (156, 182, 200, 255))
        d.text((94, cy + 48), sub_line, font=font(17), fill=(168, 194, 212, 255))
        cy += 104

    img.save(SHOTS_DIR / "06-base-upgrades-and-collection.png", "PNG", optimize=True)


def main() -> None:
    STORE_DIR.mkdir(parents=True, exist_ok=True)
    SHOTS_DIR.mkdir(parents=True, exist_ok=True)

    generate_icon_512()
    generate_feature_graphic_1024x500()
    generate_main_menu_shot()

    # Screenshot 02: Base Clearing
    s2 = render_3d_scene(
        width=1080,
        height=1920,
        zenith_hex=0x1A3B5C,
        horizon_hex=0x8CAFC9,
        fog_hex=0xB8CFE0,
        snow_hex=0xEAF3FA,
        sun_hex=0xFFE6C7,
        fog_near=18.0,
        fog_far=58.0,
        mode="base",
        snow_particles=35,
    )
    draw_hud_overlay(
        s2,
        location_title="Базовая поляна",
        subtitle_line="День 1 • День • Ясно • Ур. 1",
        compass_line="Компас: С  •  Костёр базы: 5 м (-14°)",
        hp=(96, 100),
        hunger=88,
        warmth=94,
        xp_pair=(25, 60),
        level=1,
        slots_used=4,
        status_text="Рядом с костром тепло восстанавливается. Осмотрите тайник у скалы!",
        gather_label="Собрать: Старая карта",
        show_return_base=False,
    ).save(SHOTS_DIR / "02-base-clearing.png", "PNG", optimize=True)

    # Screenshot 03: Dense Spruce Forest
    s3 = render_3d_scene(
        width=1080,
        height=1920,
        zenith_hex=0x13292A,
        horizon_hex=0x5C787E,
        fog_hex=0x8BA3A8,
        snow_hex=0xCFE0E5,
        sun_hex=0xD7E8EC,
        fog_near=11.0,
        fog_far=40.0,
        mode="spruce",
        snow_particles=155,
    )
    draw_hud_overlay(
        s3,
        location_title="Густой ельник",
        subtitle_line="День 2 • День • Снегопад • Ур. 2",
        compass_line="Компас: З  •  Экспедиция (16 м от тропы)",
        hp=(89, 110),
        hunger=74,
        warmth=66,
        xp_pair=(78, 120),
        level=2,
        slots_used=9,
        status_text="Собрано: Смола +2 (+18 опыта). В ельнике много дров и смолы для факелов.",
        gather_label="Собрать (Смола)",
        show_return_base=True,
        timer_banner=("Приготовление горячего пайка: 29 с", True),
    ).save(SHOTS_DIR / "03-dense-spruce.png", "PNG", optimize=True)

    # Screenshot 04: Frozen Lake
    s4 = render_3d_scene(
        width=1080,
        height=1920,
        zenith_hex=0x163A59,
        horizon_hex=0x9AD0E8,
        fog_hex=0xC4E4F2,
        snow_hex=0xDDF2FC,
        sun_hex=0xEAF8FF,
        fog_near=16.0,
        fog_far=54.0,
        mode="lake",
        snow_particles=95,
    )
    draw_hud_overlay(
        s4,
        location_title="Замёрзшее озеро",
        subtitle_line="День 3 • День • Позёмка • Ур. 3",
        compass_line="Компас: С  •  Экспедиция (22 м от тропы)",
        hp=(102, 120),
        hunger=81,
        warmth=53,
        xp_pair=(95, 180),
        level=3,
        slots_used=11,
        status_text="Сильный ветер на льду ускоряет охлаждение! У лунки можно добыть свежую рыбу.",
        gather_label="Собрать (Рыба)",
        show_return_base=True,
    ).save(SHOTS_DIR / "04-frozen-lake.png", "PNG", optimize=True)

    # Screenshot 05: Abandoned Mine
    s5 = render_3d_scene(
        width=1080,
        height=1920,
        zenith_hex=0x10131A,
        horizon_hex=0x2A303D,
        fog_hex=0x232833,
        snow_hex=0x8F99A8,
        sun_hex=0x9BA6B8,
        fog_near=5.5,
        fog_far=20.0,
        mode="mine",
        sun_uv=(0.5, -0.5),
        snow_particles=18,
    )
    draw_hud_overlay(
        s5,
        location_title="Заброшенная шахта",
        subtitle_line="День 4 • Ночь • Подземелье • Ур. 4",
        compass_line="Компас: Ю  •  Штольня (Смоляной факел: 142 с)",
        hp=(114, 130),
        hunger=69,
        warmth=79,
        xp_pair=(140, 240),
        level=4,
        slots_used=14,
        status_text="Смоляной факел освещает крепь штольни и снижает потерю тепла на 50%!",
        gather_label="Собрать: Кирка",
        show_return_base=True,
    ).save(SHOTS_DIR / "05-abandoned-mine.png", "PNG", optimize=True)

    # Screenshot 07: Hunter's Cabin
    s7 = render_3d_scene(
        width=1080,
        height=1920,
        zenith_hex=0x241E38,
        horizon_hex=0xB88B72,
        fog_hex=0xCBB2A6,
        snow_hex=0xEFE6E1,
        sun_hex=0xFFD2A6,
        fog_near=15.0,
        fog_far=50.0,
        mode="cabin",
        sun_uv=(0.28, 0.36),
        snow_particles=52,
    )
    draw_hud_overlay(
        s7,
        location_title="Охотничий домик",
        subtitle_line="День 5 • Вечер • Позёмка • Ур. 5",
        compass_line="Компас: В  •  Зимовье (12 м от перевала)",
        hp=(128, 140),
        hunger=92,
        warmth=86,
        xp_pair=(190, 300),
        level=5,
        slots_used=15,
        status_text="Найдено старое зимовье! Здесь можно добыть дичь, шкуры и «Тёплую шубу».",
        gather_label="Собрать: Тёплая шуба",
        show_return_base=True,
    ).save(SHOTS_DIR / "07-hunters-cabin.png", "PNG", optimize=True)

    generate_base_inventory_shot()
    print("Store graphics generated successfully:")
    for p in sorted(STORE_DIR.rglob("*.png")):
        im = Image.open(p)
        print(f" - {p.relative_to(ROOT)}: {im.size[0]}x{im.size[1]} ({p.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
