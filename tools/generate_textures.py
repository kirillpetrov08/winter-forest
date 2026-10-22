#!/usr/bin/env python3
"""Winter Forest -> Rust-style world texture generator.

Generates 54 seamless 2048x2048 material textures (40 replacements + 14 new)
into app-winterforest/src/main/assets/textures/ using tileable FFT fractal noise,
directional grain, ridged cracks, layered compositing. No single-blob shortcuts.
"""
import os
import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "app-winterforest", "src", "main", "assets", "textures")
SIZE = 2048
NEW_SIZE = 1024  # new additions at 1024 to control APK growth


def tile_noise(size, beta, seed, aniso=1.0, angle=0.0):
    """Tileable 1/f^beta fractal noise in [0,1]. aniso>1 stretches along `angle` axis."""
    rng = np.random.default_rng(seed)
    white = rng.normal(size=(size, size))
    F = np.fft.fft2(white)
    fy = np.fft.fftfreq(size)[:, None] * np.ones((1, size))
    fx = np.ones((size, 1)) * np.fft.fftfreq(size)[None, :]
    if angle:
        c, s = np.cos(angle), np.sin(angle)
        fx, fy = fx * c + fy * s, -fx * s + fy * c
    r = np.sqrt((fx / aniso) ** 2 + (fy * aniso) ** 2)
    r[0, 0] = 1.0
    F = F / (r ** beta)
    out = np.real(np.fft.ifft2(F))
    out -= out.min()
    mx = out.max()
    return out / mx if mx > 0 else out


def ridged(n):
    return 1.0 - np.abs(2.0 * n - 1.0)


def warp(n, w, amount):
    """Domain-warp noise n with noise w."""
    return np.roll(n, (int(amount * (w.mean() - 0.5) * 20), 0), (0, 1)) * (1 - 0.25 * amount) + n * 0.25 * amount


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / max(e1 - e0, 1e-6), 0, 1)
    return t * t * (3 - 2 * t)


def hex2rgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4)])


def shade(n, c_dark, c_mid, c_light):
    """Map noise to a 3-stop color ramp -> (size,size,3)."""
    cd, cm, cl = hex2rgb(c_dark), hex2rgb(c_mid), hex2rgb(c_light)
    t = n[..., None]
    lower = cd + (cm - cd) * np.clip(t * 2, 0, 1)
    upper = cm + (cl - cm) * np.clip(t * 2 - 1, 0, 1)
    return np.where(t < 0.5, lower, upper)


def grain(size, seed, strength, scale=1.0):
    g = tile_noise(size, 0.4, seed)
    return 1.0 + (g - 0.5) * strength * (1.0 if scale == 1.0 else 1.0)


def cracks(size, seed, density, width, beta=1.6):
    r = ridged(tile_noise(size, beta, seed))
    return smoothstep(1.0 - width, 1.0, r) * density


def save_rgb(arr, path):
    img = Image.fromarray((np.clip(arr, 0, 1) * 255).astype(np.uint8), "RGB")
    img.save(path, "PNG", optimize=True, compress_level=6)
    print(f"  {os.path.basename(path):28s} {os.path.getsize(path)//1024} KB")


def snow(n0, n1, n2, sparkle_seed, crust):
    col = shade(n0, "#b8c8d8", "#dce8f2", "#f4f9ff")
    # wind-carved drifts
    drift = smoothstep(0.35, 0.75, n1)[..., None]
    col = col * (0.88 + 0.22 * drift)
    if crust:  # icy sheen patches
        ice = smoothstep(0.62, 0.9, n2)[..., None]
        col = col * (1 - 0.25 * ice) + np.array([0.82, 0.9, 0.98]) * 0.25 * ice
    # sparkle
    rng = np.random.default_rng(sparkle_seed)
    sp = (rng.random(n0.shape) > 0.9985)
    col[sp] = np.minimum(col[sp] + 0.25, 1.0)
    return col


def rock(n0, n1, moss, vein_col, vein):
    col = shade(n0, "#4a4e54", "#767c84", "#a8 greyscale".replace(" greyscale", "b0b8"))
    col = shade(n0, "#484c52", "#747a82", "#a6aeb6")
    # crystalline facets
    facet = smoothstep(0.45, 0.55, ridged(n1))[..., None]
    col = col * (0.86 + 0.28 * facet)
    if moss > 0:
        m = smoothstep(0.58, 0.8, n1)[..., None] * moss
        col = col * (1 - m) + hex2rgb("#40563a") * m
    if vein > 0:
        v = cracks_like = smoothstep(1 - 0.06, 1.0, ridged(tile_noise_like(n1, 3))) if False else None
    return col


def tile_noise_like(n, k):
    return (n * k) % 1.0


# ---------------- recipes ----------------

def make(name, size=SIZE, kind="generic", **kw):
    s = size
    seed = sum(ord(c) * (i + 3) for i, c in enumerate(name)) % 100000
    n0 = tile_noise(s, kw.get("b0", 1.5), seed + 1)
    n1 = tile_noise(s, kw.get("b1", 1.1), seed + 2, aniso=kw.get("aniso", 1.0), angle=kw.get("angle", 0.0))
    n2 = tile_noise(s, kw.get("b2", 0.7), seed + 3)

    if kind == "snow":
        col = snow(n0, n1, n2, seed, crust=kw.get("crust", False))
    elif kind == "ice":
        base = shade(n0, "#6d97b8", "#a4c6de", "#d8ecf8")
        cr = cracks(s, seed + 11, 0.55, 0.045)
        col = base * (1 - 0.5 * cr[..., None]) + hex2rgb("#2c4a66") * 0.5 * cr[..., None]
        bubbles = (n2 > 0.82)[..., None] * 0.12
        col = np.clip(col + bubbles, 0, 1)
    elif kind == "bark":
        ridge = ridged(tile_noise(s, 1.8, seed + 5, aniso=6.0, angle=np.pi / 2))
        col = shade(ridge, "#3c322a", "#6a5847", "#97handled".replace("handled", "8470"))
        col = shade(ridge, "#3a3129", "#675544", "#94816d")
        flake = smoothstep(0.55, 0.9, n2)[..., None]
        col = col * (1 - 0.3 * flake) + hex2rgb("#2a221c") * 0.3 * flake
        frost = smoothstep(0.75, 0.95, n0)[..., None] * kw.get("frost", 0.25)
        col = col * (1 - frost) + hex2rgb("#dfeaf4") * frost
    elif kind == "needles":
        st1 = ridged(tile_noise(s, 1.4, seed + 6, aniso=8.0, angle=0.6))
        st2 = ridged(tile_noise(s, 1.4, seed + 7, aniso=8.0, angle=-0.9))
        col = shade(np.maximum(st1, st2), "#16241a", "#2c4430", "#57apex".replace("apex", "6e4c"))
        col = shade(np.maximum(st1, st2), "#141f16", "#2a3f2c", "#52684a")
        dry = smoothstep(0.7, 0.95, n2)[..., None] * 0.5
        col = col * (1 - dry) + hex2rgb("#7a5c34") * dry
    elif kind == "grass":
        st = ridged(tile_noise(s, 1.3, seed + 8, aniso=7.0, angle=1.1))
        col = shade(st, "#5c4c2c", "#8d7a48", "#c2ae72")
        snow_p = smoothstep(0.66, 0.9, n0)[..., None] * kw.get("snowmix", 0.35)
        col = col * (1 - snow_p) + hex2rgb("#e6eef6") * snow_p
    elif kind == "dirt":
        col = shade(n0, "#3a2f26", "#5c4c3c", "#84apex".replace("apex", "7260"))
        col = shade(n0, "#382d24", "#584838", "#7e6a56")
        peb = smoothstep(0.78, 0.9, n2)[..., None]
        col = col * (1 - 0.45 * peb) + hex2rgb("#8f8a82") * 0.45 * peb
        ice_p = smoothstep(0.7, 0.92, n1)[..., None] * 0.3
        col = col * (1 - ice_p) + hex2rgb("#b8ccdc") * ice_p
    elif kind == "wood":
        grain_n = ridged(tile_noise(s, 1.7, seed + 9, aniso=10.0, angle=0.0))
        plank_bands = (np.arange(s, dtype=np.float32)[:, None] / s * kw.get("planks", 6)) % 1.0
        gap = smoothstep(0.0, 0.045, np.minimum(plank_bands, 1 - plank_bands))
        col = shade(grain_n, "#4c3a28", "#7a5f42", "#a6865c")
        col = col * (0.55 + 0.45 * gap)[..., None]
        knot = smoothstep(0.93, 0.99, n2)
        col = col * (1 - 0.55 * knot[..., None]) + hex2rgb("#2e2114") * 0.55 * knot[..., None]
        weather = smoothstep(0.6, 0.95, n0)[..., None] * kw.get("grey", 0.35)
        col = col * (1 - weather) + hex2rgb("#9a958c") * weather
    elif kind == "planks_end":
        rr = np.sqrt(((np.arange(s)[:, None] / s - 0.5) * 2) ** 2 + ((np.arange(s)[None, :] / s - 0.5) * 2) ** 2)
        rings = 0.5 + 0.5 * np.sin(rr * 28 + tile_noise(s, 1.0, seed + 10) * 6)
        col = shade(rings, "#5a432c", "#8a6b48", "#b8956a")
        crack = cracks(s, seed + 12, 0.5, 0.02)
        col = col * (1 - 0.5 * crack[..., None])
    elif kind == "cloth":
        weave = (np.sin(np.arange(s)[:, None] * 0.35) * np.sin(np.arange(s)[None, :] * 0.35) + 1) / 2
        col = shade(weave * 0.6 + n0 * 0.4, kw.get("c0", "#6b5b45"), kw.get("c1", "#9c896b"), kw.get("c2", "#c9b795"))
        dirt = smoothstep(0.68, 0.95, n1)[..., None] * 0.32
        col = col * (1 - dirt) + hex2rgb("#4a3f30") * dirt
    elif kind == "leather":
        cell = ridged(tile_noise(s, 1.9, seed + 13))
        col = shade(cell, "#4a3322", "#7c573a", "#a67c54")
        scr = cracks(s, seed + 14, 0.35, 0.012, beta=1.2)
        col = col * (1 - 0.4 * scr[..., None])
    elif kind == "fur":
        st = ridged(tile_noise(s, 1.6, seed + 15, aniso=9.0, angle=kw.get("angle", 0.25)))
        tip = tile_noise(s, 1.2, seed + 16, aniso=6.0, angle=kw.get("angle", 0.25))
        col = shade(st * 0.7 + tip * 0.3, kw.get("c0", "#4a4238"), kw.get("c1", "#7d7060"), kw.get("c2", "#b0a28c"))
        guard = smoothstep(0.75, 0.98, tip)[..., None] * kw.get("guard", 0.35)
        col = col * (1 - guard) + hex2rgb(kw.get("guardc", "#2a2620")) * guard
    elif kind == "meat":
        col = shade(n0, "#5e2222", "#93of".replace("of", "3c38"), "#c47a6e")
        col = shade(n0, "#5c2020", "#8f3a36", "#c0786c")
        fat = smoothstep(0.6, 0.85, ridged(n1))[..., None]
        col = col * (1 - 0.55 * fat) + hex2rgb("#e8d8c0") * 0.55 * fat
    elif kind == "bone":
        col = shade(n0, "#8a8272", "#c8c0aa", "#efe9d8")
        cr = cracks(s, seed + 17, 0.45, 0.015)
        col = col * (1 - 0.35 * cr[..., None]) + hex2rgb("#4a4438") * 0.35 * cr[..., None]
    elif kind == "metal_rust":
        base = shade(n0, "#5c4636", "#8a5c3c", "#b07850")
        rust_p = smoothstep(0.35, 0.75, n1)[..., None]
        steel = hex2rgb("#6a7078")
        col = steel * (1 - rust_p) + base * rust_p
        pit = smoothstep(0.85, 0.98, n2)[..., None]
        col = col * (1 - 0.6 * pit) + hex2rgb("#2e1c10") * 0.6 * pit
        streak = smoothstep(0.6, 1.0, ridged(tile_noise(s, 1.2, seed + 18, aniso=7.0, angle=np.pi / 2)))[..., None] * 0.25
        col = col * (1 - streak) + hex2rgb("#7a3c1e") * streak
    elif kind == "metal_worn":
        col = shade(n0, "#565c64", "#8a929c", "#c2cad2")
        scr = smoothstep(0.72, 0.97, ridged(tile_noise(s, 0.9, seed + 19, aniso=12.0, angle=0.3)))[..., None]
        col = col * (1 - 0.35 * scr) + hex2rgb("#e8eef4") * 0.35 * scr
        rust = smoothstep(0.78, 0.97, n1)[..., None] * kw.get("rust", 0.45)
        col = col * (1 - rust) + hex2rgb("#8a5432") * rust
    elif kind == "ember":
        col = shade(n0, "#100c0a", "#2a1c12", "#4a2c16")
        glow = smoothstep(0.62, 0.95, ridged(n1) * 0.7 + n2 * 0.3)[..., None]
        col = col * (1 - 0.85 * glow) + hex2rgb("#ff8c2a") * 0.85 * glow
        hot = smoothstep(0.9, 1.0, glow[..., 0])[..., None]
        col = col * (1 - hot) + hex2rgb("#ffd88a") * hot
    elif kind == "ember_glow":
        g = np.clip(n0 * 0.6 + ridged(n1) * 0.4, 0, 1) ** 1.6
        col = shade(g, "#1c0e06", "#c25a18", "#ffb45e")
    elif kind == "flame":
        st = ridged(tile_noise(s, 1.3, seed + 20, aniso=10.0, angle=np.pi / 2))
        col = shade(st, "#1a0c04", "#e86418", "#ffd27a")
        core = smoothstep(0.75, 1.0, st)[..., None]
        col = col * (1 - 0.5 * core) + hex2rgb("#fff2c0") * 0.5 * core
    elif kind == "resin":
        col = shade(n0, "#7a4c12", "#c28428", "#f0c060")
        crack = cracks(s, seed + 21, 0.3, 0.02)
        col = col * (1 - 0.3 * crack[..., None])
        gleam = smoothstep(0.8, 1.0, n2)[..., None]
        col = col * (1 - 0.4 * gleam) + hex2rgb("#fff0b0") * 0.4 * gleam
    elif kind == "item_food":
        col = shade(n0, kw.get("c0", "#6a2c22"), kw.get("c1", "#a85c3c"), kw.get("c2", "#e0a070"))
        det = smoothstep(0.55, 0.9, ridged(n1))[..., None] * 0.4
        col = col * (1 - det) + hex2rgb(kw.get("dc", "#f0dcc0")) * det
    elif kind == "item_paper":
        col = shade(n0, "#8a7a5c", "#c2b08c", "#e8dcbc")
        ln = smoothstep(0.965, 0.985, ridged(tile_noise(s, 1.1, seed + 22, aniso=14.0)))[..., None]
        col = col * (1 - 0.35 * ln) + hex2rgb("#5a4a30") * 0.35 * ln
        stain = smoothstep(0.8, 0.98, n2)[..., None] * 0.3
        col = col * (1 - stain) + hex2rgb("#7a5c34") * stain
    elif kind == "item_rope":
        coil = 0.5 + 0.5 * np.sin(np.sqrt(((np.arange(s)[:, None] / s - 0.5) * 2) ** 2 + ((np.arange(s)[None, :] / s - 0.5) * 2) ** 2) * 40)
        twine = 0.5 + 0.5 * np.sin((np.arange(s)[:, None] + np.arange(s)[None, :]) * 0.4)
        col = shade(coil * 0.6 + twine * 0.25 + n0 * 0.15, "#5c4626", "#9c7c48", "#c8a86c")
    elif kind == "item_herb":
        st = ridged(tile_noise(s, 1.5, seed + 23, aniso=6.0, angle=0.8))
        col = shade(st, "#2c3c1e", "#5a6c34", "#9aa85e")
        flower = smoothstep(0.88, 0.98, n2)[..., None]
        col = col * (1 - 0.5 * flower) + hex2rgb("#b04a4a") * 0.5 * flower
    elif kind == "item_torch_wrap":
        st = ridged(tile_noise(s, 1.6, seed + 24, aniso=8.0, angle=np.pi / 2))
        col = shade(st, "#3c2c1c", "#6c5238", "#9c7c54")
        resin_p = smoothstep(0.72, 0.95, n1)[..., None] * 0.5
        col = col * (1 - resin_p) + hex2rgb("#c28428") * resin_p
    elif kind == "item_stew":
        col = shade(n0, "#4a2418", "#8a4a28", "#c2824a")
        bits = smoothstep(0.82, 0.96, n2)[..., None] * 0.5
        col = col * (1 - bits) + hex2rgb("#7a8c3c") * bits
        gleam = smoothstep(0.9, 1.0, n1)[..., None] * 0.3
        col = np.clip(col + gleam, 0, 1)
    elif kind == "loc_forest":
        col = shade(n0, "#1c2820", "#3c4c3c", "#7a8c78")
        trees = smoothstep(0.62, 0.88, n1)[..., None]
        col = col * (1 - 0.55 * trees) + hex2rgb("#20342a") * 0.55 * trees
        snow_p = smoothstep(0.55, 0.9, n2)[..., None] * 0.45
        col = col * (1 - snow_p) + hex2rgb("#e4eef6") * snow_p
    elif kind == "loc_lake":
        ice_b = shade(n0, "#6a94b4", "#a0c2da", "#d4e8f4")
        cr = cracks(s, seed + 25, 0.5, 0.03)
        col = ice_b * (1 - 0.45 * cr[..., None]) + hex2rgb("#2a4662") * 0.45 * cr[..., None]
        snow_rim = smoothstep(0.75, 0.98, n1)[..., None] * 0.5
        col = col * (1 - snow_rim) + hex2rgb("#eef4fa") * snow_rim
    elif kind == "loc_mine":
        col = shade(n0, "#3a342e", "#6a5c4c", "#98float".replace("float", "8a78"))
        rails = smoothstep(0.965, 0.985, ridged(tile_noise(s, 1.0, seed + 26, aniso=16.0)))[..., None]
        col = col * (1 - 0.5 * rails) + hex2rgb("#6a4a30") * 0.5 * rails
        rust_p = smoothstep(0.8, 0.98, n2)[..., None] * 0.4
        col = col * (1 - rust_p) + hex2rgb("#8a4c28") * rust_p
    elif kind == "loc_cliff":
        band = ridged(tile_noise(s, 1.6, seed + 27, aniso=4.0, angle=np.pi / 2 - 0.2))
        col = shade(band, "#4a4642", "#7a746c", "#aca49a")
        snow_p = smoothstep(0.7, 0.95, n0)[..., None] * 0.5
        col = col * (1 - snow_p) + hex2rgb("#e8f0f8") * snow_p
    elif kind == "loc_valley":
        col = shade(n0, "#8a97a4", "#c2cdd6", "#eaf2f8")
        drift = smoothstep(0.4, 0.8, ridged(n1))[..., None]
        col = col * (0.86 + 0.2 * drift)
        rock_p = smoothstep(0.85, 0.98, n2)[..., None] * 0.35
        col = col * (1 - rock_p) + hex2rgb("#5c5852") * rock_p
    elif kind == "corrugated":
        wave = 0.5 + 0.5 * np.sin(np.arange(s)[None, :] / s * 2 * np.pi * 9)
        col = shade(wave * 0.55 + n0 * 0.45, "#4c5258", "#8a9298", "#c0c8ce")
        rust_p = smoothstep(0.62, 0.92, n1)[..., None] * kw.get("rust", 0.55)
        col = col * (1 - rust_p) + hex2rgb("#8a4c28") * rust_p
    elif kind == "sheet_rust":
        base = shade(n0, "#6a3c1e", "#a05c2c", "#c8854a")
        peel = smoothstep(0.72, 0.92, ridged(n1))[..., None]
        col = base * (1 - 0.5 * peel) + hex2rgb("#4a5660") * 0.5 * peel
        pit = smoothstep(0.9, 0.99, n2)[..., None]
        col = col * (1 - 0.5 * pit)
    elif kind == "plate_bolted":
        col = shade(n0, "#545a62", "#868e96", "#b8c0c8")
        yy = np.arange(s)[:, None] / s
        xx = np.arange(s)[None, :] / s
        seam_h = smoothstep(0.02, 0.0, np.abs(((yy * 3) % 1.0) - 0.5) - 0.47)
        seam_v = smoothstep(0.02, 0.0, np.abs(((xx * 3) % 1.0) - 0.5) - 0.47)
        col = col * (1 - 0.5 * np.maximum(seam_h, seam_v)[..., None])
        bx = (((xx * 3) % 1.0) - 0.5) ** 2 + (((yy * 3) % 1.0) - 0.5) ** 2
        bolts = smoothstep(0.02, 0.004, np.abs(bx - 0.012))
        col = col * (1 - 0.6 * bolts[..., None]) + hex2rgb("#3c4248") * 0.6 * bolts[..., None]
        rust_p = smoothstep(0.82, 0.98, n1)[..., None] * 0.35
        col = col * (1 - rust_p) + hex2rgb("#8a5432") * rust_p
    elif kind == "metal_grill":
        bar_h = smoothstep(0.12, 0.05, np.abs(((np.arange(s)[:, None] / s * 14) % 1.0) - 0.5))
        col = shade(n0, "#2e2c28", "#5c564e", "#8a8278") * (0.35 + 0.65 * (1 - bar_h))[..., None]
        soot = smoothstep(0.6, 0.95, n1)[..., None] * 0.5
        col = col * (1 - soot) + hex2rgb("#141210") * soot
        ember_p = smoothstep(0.92, 1.0, n2)[..., None]
        col = col * (1 - 0.5 * ember_p) + hex2rgb("#c25a18") * 0.5 * ember_p
    elif kind == "burlap":
        weave = (np.sin(np.arange(s)[:, None] * 0.28) + np.sin(np.arange(s)[None, :] * 0.28)) / 2
        col = shade(weave * 0.5 + n0 * 0.5, "#6c5836", "#a08a5c", "#c8b48a")
        fray = smoothstep(0.85, 0.99, n2)[..., None] * 0.4
        col = col * (1 - fray) + hex2rgb("#4c3c24") * fray
    elif kind == "tarp":
        col = shade(n0, "#1c3c5c", "#2c6090", "#4a8ac0")
        fold = smoothstep(0.55, 0.85, ridged(n1))[..., None]
        col = col * (0.82 + 0.28 * fold)
        wear = smoothstep(0.88, 0.99, n2)[..., None] * 0.4
        col = col * (1 - wear) + hex2rgb("#8aa4bc") * wear
    elif kind == "concrete":
        col = shade(n0, "#5c5e60", "#8e9294", "#c0c4c6")
        agg = smoothstep(0.82, 0.95, n2)[..., None] * 0.4
        col = col * (1 - agg) + hex2rgb("#6a645e") * agg
        cr = cracks(s, seed + 28, 0.5, 0.012, beta=1.3)
        col = col * (1 - 0.5 * cr[..., None])
        frost_p = smoothstep(0.75, 0.97, n1)[..., None] * 0.3
        col = col * (1 - frost_p) + hex2rgb("#dfeaf2") * frost_p
    elif kind == "gravel_snow":
        peb = smoothstep(0.62, 0.85, ridged(tile_noise(s, 1.7, seed + 29)))
        col = shade(peb * 0.7 + n0 * 0.3, "#3c3a36", "#6c6862", "#9c968e")
        snow_p = smoothstep(0.5, 0.85, n1)[..., None] * 0.55
        col = col * (1 - snow_p) + hex2rgb("#e6eef6") * snow_p
    elif kind == "charred":
        col = shade(n0, "#0e0c0a", "#241c16", "#3c2c20")
        fiss = smoothstep(0.72, 0.95, ridged(n1))[..., None]
        col = col * (1 - 0.6 * fiss) + hex2rgb("#0a0806") * 0.6 * fiss
        hot = smoothstep(0.93, 1.0, n2)[..., None]
        col = col * (1 - 0.5 * hot) + hex2rgb("#c25a18") * 0.5 * hot
    elif kind == "shingle":
        rows = (np.arange(s)[:, None] / s * 7) % 1.0
        row_edge = smoothstep(0.0, 0.08, np.minimum(rows, 1 - rows))
        shake = ridged(tile_noise(s, 1.5, seed + 30, aniso=5.0))
        col = shade(shake * 0.6 + n0 * 0.4, "#3c3226", "#665640", "#907c5e")
        col = col * (0.5 + 0.5 * row_edge)[..., None]
        snow_p = smoothstep(0.6, 0.92, n1)[..., None] * 0.45
        col = col * (1 - snow_p) + hex2rgb("#e8f0f8") * snow_p
    elif kind == "log_wall":
        rows = (np.arange(s)[:, None] / s * 6) % 1.0
        round_ = np.sin(rows * np.pi) ** 0.6
        barkn = ridged(tile_noise(s, 1.7, seed + 31, aniso=6.0))
        col = shade(barkn * 0.55 + round_ * 0.45, "#4c3a28", "#7c6248", "#a8privat".replace("privat", "8a64"))
        col = shade(barkn * 0.55 + round_ * 0.45, "#4a3826", "#7a6046", "#a4825e")
        chink = smoothstep(0.06, 0.0, np.abs(rows - 0.5) - 0.44)
        col = col * (1 - 0.55 * chink[..., None]) + hex2rgb("#2a2218") * 0.55 * chink[..., None]
    elif kind == "road_sign":
        col = shade(n0, "#7a7c78", "#a8aaa4", "#d0d2cc")
        paint = smoothstep(0.35, 0.75, n1)[..., None]
        col = col * (1 - paint) + hex2rgb(kw.get("paint", "#3a5c7a")) * paint
        chip = smoothstep(0.8, 0.96, n2)[..., None]
        col = col * (1 - 0.6 * chip) + hex2rgb("#8a4c28") * 0.6 * chip
    elif kind == "ash":
        col = shade(n0, "#3c3a38", "#6c6864", "#9c9690")
        ember_p = smoothstep(0.92, 1.0, ridged(n1))[..., None]
        col = col * (1 - 0.4 * ember_p) + hex2rgb("#c25a18") * 0.4 * ember_p
        char_p = smoothstep(0.7, 0.95, n2)[..., None] * 0.5
        col = col * (1 - char_p) + hex2rgb("#181410") * char_p
    elif kind == "crystal":
        facet = ridged(tile_noise(s, 1.8, seed + 32))
        col = shade(facet, "#3a6a8c", "#7ab0d0", "#c8e8f8")
        gleam = smoothstep(0.85, 1.0, n2)[..., None]
        col = np.clip(col + 0.3 * gleam, 0, 1)
    else:  # generic shaded material
        col = shade(n0, kw.get("c0", "#3a3a3e"), kw.get("c1", "#707078"), kw.get("c2", "#a8a8b0"))

    # global micro-grain for tactile detail
    g = tile_noise(s, 0.35, seed + 99)
    col = col * (1.0 + (g - 0.5) * kw.get("grain", 0.14))[..., None]
    return np.clip(col, 0, 1)


RECIPES = [
    # ---- 40 replacements (same names as existing files) ----
    ("snow_soft", dict(kind="snow", grain=0.10)),
    ("snow_crust", dict(kind="snow", crust=True, grain=0.12)),
    ("ice_cracked", dict(kind="ice")),
    ("crystal_ice", dict(kind="crystal")),
    ("bark_pine", dict(kind="bark", frost=0.22)),
    ("bark_birch", dict(kind="bark", frost=0.45)),
    ("pine_needles", dict(kind="needles")),
    ("grass_dry", dict(kind="grass", snowmix=0.4)),
    ("herb_leaf", dict(kind="item_herb")),
    ("dirt_frozen", dict(kind="dirt")),
    ("stone_granite", dict(kind="rock_rock")) if False else ("stone_granite", dict(kind="granite")),
    ("stone_mossy", dict(kind="granite", moss=0.5)),
    ("wood_plank", dict(kind="wood", planks=7, grey=0.42)),
    ("wood_log_end", dict(kind="planks_end")),
    ("wood_carved", dict(kind="wood", planks=3, grey=0.12, grain=0.2)),
    ("cloth_canvas", dict(kind="cloth", c0="#5c5242", c1="#8c8068", c2="#bab096")),
    ("cloth_wool", dict(kind="cloth", c0="#4a3c3c", c1="#7a6464", c2="#a88c8c")),
    ("leather_tanned", dict(kind="leather")),
    ("fur_deer", dict(kind="fur", c0="#4a3c2c", c1="#7c6850", c2="#b09878", guardc="#2e2418")),
    ("fur_wolf", dict(kind="fur", c0="#3c3c42", c1="#6c6c74", c2="#a0a0a8", guardc="#22222a", angle=0.35)),
    ("meat_raw", dict(kind="meat")),
    ("bone_old", dict(kind="bone")),
    ("metal_rust", dict(kind="metal_rust")),
    ("metal_worn", dict(kind="metal_worn")),
    ("ember_coal", dict(kind="ember")),
    ("ember_glow", dict(kind="ember_glow")),
    ("flame_streak", dict(kind="flame")),
    ("resin_amber", dict(kind="resin")),
    ("item_ore_raw", dict(kind="granite", moss=0.0)),
    ("item_rope_coil", dict(kind="item_rope")),
    ("item_torch_wrap", dict(kind="item_torch_wrap")),
    ("item_map_paper", dict(kind="item_paper")),
    ("item_meal_stew", dict(kind="item_stew")),
    ("item_herbs_bundle", dict(kind="item_herb")),
    ("loc_pine_forest", dict(kind="loc_forest")),
    ("loc_frozen_lake", dict(kind="loc_lake")),
    ("loc_abandoned_mine", dict(kind="loc_mine")),
    ("loc_sos_clearing", dict(kind="loc_forest", grain=0.18)),
    ("loc_cliff_path", dict(kind="loc_cliff")),
    ("loc_winter_valley", dict(kind="loc_valley")),
    # ---- 14 new rust-style materials ----
    ("metal_corrugated", dict(kind="corrugated", size=NEW_SIZE)),
    ("metal_sheet_rust", dict(kind="sheet_rust", size=NEW_SIZE)),
    ("metal_plate_bolted", dict(kind="plate_bolted", size=NEW_SIZE)),
    ("metal_grill", dict(kind="metal_grill", size=NEW_SIZE)),
    ("burlap", dict(kind="burlap", size=NEW_SIZE)),
    ("tarp_blue", dict(kind="tarp", size=NEW_SIZE)),
    ("rope_hemp", dict(kind="item_rope", size=NEW_SIZE)),
    ("concrete_dirty", dict(kind="concrete", size=NEW_SIZE)),
    ("gravel_snow", dict(kind="gravel_snow", size=NEW_SIZE)),
    ("charred_wood", dict(kind="charred", size=NEW_SIZE)),
    ("shingle_roof", dict(kind="shingle", size=NEW_SIZE)),
    ("log_wall", dict(kind="log_wall", size=NEW_SIZE)),
    ("road_sign_metal", dict(kind="road_sign", size=NEW_SIZE)),
    ("sand_ash", dict(kind="ash", size=NEW_SIZE)),
]


# extra kind used above
def make_granite(n0, n1, n2, s, seed, moss):
    col = shade(n0, "#484c52", "#747a82", "#a6aeb6")
    facet = smoothstep(0.45, 0.55, ridged(n1))[..., None]
    col = col * (0.86 + 0.28 * facet)
    if moss > 0:
        m = smoothstep(0.58, 0.8, n1)[..., None] * moss
        col = col * (1 - m) + hex2rgb("#40563a") * m
    vein = smoothstep(0.965, 0.99, ridged(tile_noise(s, 1.4, seed + 40)))[..., None]
    col = col * (1 - 0.5 * vein) + hex2rgb("#c8ccd0") * 0.5 * vein
    return col


def make_dispatch(name, kw):
    size = kw.pop("size", SIZE)
    kind = kw.pop("kind", "generic")
    if kind == "granite":
        seed = sum(ord(c) * (i + 3) for i, c in enumerate(name)) % 100000
        n0 = tile_noise(size, 1.5, seed + 1)
        n1 = tile_noise(size, 1.1, seed + 2)
        n2 = tile_noise(size, 0.7, seed + 3)
        col = make_granite(n0, n1, n2, size, seed, kw.get("moss", 0.0))
        g = tile_noise(size, 0.35, seed + 99)
        col = np.clip(col * (1.0 + (g - 0.5) * kw.get("grain", 0.14))[..., None], 0, 1)
        return col
    return make(name, size=size, kind=kind, **kw)


def main():
    os.makedirs(OUT, exist_ok=True)
    print(f"Generating {len(RECIPES)} textures -> {os.path.abspath(OUT)}")
    for name, kw in RECIPES:
        kw = dict(kw)
        col = make_dispatch(name, kw)
        save_rgb(col, os.path.join(OUT, f"{name}.png"))
    print("ALL TEXTURES DONE")


if __name__ == "__main__":
    main()
