#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Pack the new hand-made / Poly Haven model packs into game-ready assets:
OBJ meshes (world-normalized, y-up) + 512px albedo atlas PNGs.

Sources: models/*.blend (Blender 5.x scenes) and models/assets/models/
(Poly Haven packs). Textures are taken from packed images inside the .blends,
beautified (contrast/saturation/cool shift) and packed into a per-material
atlas with UV remapping.

Output: app-winterforest/src/main/assets/models/<model_id>/{mesh.obj,diff.png}
"""
import os
import sys
import math
import glob

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from blend2obj import parse_file  # noqa: F401  (validates parser)
from extract_packed_images import extract_packed
from pack_animals_cabin import (
    bake_and_merge, normalize_global, decimate_cluster, write_group_obj,
)
from PIL import Image, ImageEnhance
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app-winterforest", "src", "main", "assets", "models")
TEXCACHE = "/tmp/wf_tex_cache"

# ---------------------------------------------------------------------------
# texture cache: extract packed images once per source blend
def ensure_tex(*blends):
    os.makedirs(TEXCACHE, exist_ok=True)
    for b in blends:
        if not b:
            continue
        try:
            extract_packed(os.path.join(ROOT, b), TEXCACHE)
        except Exception as e:
            print("   (extract %s failed: %s)" % (b, e))
    return TEXCACHE


def find_tex(*substrs):
    """First cached texture file whose name contains any substring (ci)."""
    for s in substrs:
        s = s.lower()
        for f in sorted(os.listdir(TEXCACHE)):
            fl = f.lower()
            if s in fl and not any(k in fl for k in ("normal", "nor_gl", "rough",
                                                     "metallic", "metal.png",
                                                     "metal.jpg", "gloss", "ao",
                                                     "ambient", "displacement",
                                                     "rma", "opacity")):
                return os.path.join(TEXCACHE, f)
    return None


def beautify(im, cool=0.03, contrast=1.07, color=1.08):
    """Albedo grade: contrast + saturation + subtle cool winter shift."""
    im = ImageEnhance.Contrast(im).enhance(contrast)
    im = ImageEnhance.Color(im).enhance(color)
    a = np.asarray(im).astype(np.float32)
    a[..., 0] *= (1.0 - cool * 0.6)
    a[..., 2] *= (1.0 + cool)
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def procedural_rust(size=512):
    rng = np.random.default_rng(20260214)
    y, x = np.mgrid[0:size, 0:size].astype(np.float32) / size

    def fbm(freq, oct_):
        t = np.zeros((size, size), np.float32)
        amp, f = 1.0, freq
        for o in range(oct_):
            g = rng.random((int(f) + 1, int(f) + 1), dtype=np.float32)
            xs = x * f; ys = y * f
            ix = np.clip(xs.astype(int), 0, int(f)); iy = np.clip(ys.astype(int), 0, int(f))
            fx = xs - ix; fy = ys - iy
            fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy)
            g00 = g[iy, ix]; g10 = g[iy, ix + 1]; g01 = g[iy + 1, ix]; g11 = g[iy + 1, ix + 1]
            t += amp * ((g00 * (1 - fx) + g10 * fx) * (1 - fy) + (g01 * (1 - fx) + g11 * fx) * fy)
            amp *= 0.5; f *= 2
        return t / 1.875
    base = fbm(6, 5)
    rust = np.clip(fbm(10, 4) * 1.4 - 0.35, 0, 1) ** 1.5
    steel = np.stack([0.30 + base * 0.12] * 3, -1)
    rust_col = np.stack([0.42 + base * 0.20, 0.22 + base * 0.10, 0.12 + base * 0.05], -1)
    out = steel * (1 - rust[..., None]) + rust_col * rust[..., None]
    return Image.fromarray(np.clip(out * 255, 0, 255).astype(np.uint8))


def procedural_wood(size=512):
    rng = np.random.default_rng(77)
    y, x = np.mgrid[0:size, 0:size].astype(np.float32) / size
    warp = rng.random((32, 32), dtype=np.float32)
    ys = (y * 31).astype(int); xs = (x * 31).astype(int)
    w = warp[ys, xs]
    grain = np.abs(np.sin((x * 14 + w * 2.2) * math.pi)) ** 0.6
    fine = rng.random((size, size), dtype=np.float32)
    g = 0.72 * grain + 0.28 * fine
    base = np.stack([0.40 + g * 0.20, 0.27 + g * 0.13, 0.15 + g * 0.08], -1)
    return Image.fromarray(np.clip(base * 255, 0, 255).astype(np.uint8))


def procedural_fish(size=512):
    """Medaka-like skin: olive-silver back, pale belly, fine scales."""
    rng = np.random.default_rng(11)
    y, x = np.mgrid[0:size, 0:size].astype(np.float32) / size
    # v-band used by the mesh is the top strip; give it the full gradient
    across = np.clip((y - 0.02) / 0.24, 0, 1)  # 0=back .. 1=belly in the used band
    back = np.array([0.28, 0.33, 0.30]); belly = np.array([0.86, 0.84, 0.74])
    col = back[None, None] * (1 - across[..., None]) + belly[None, None] * across[..., None]
    scales = 0.5 + 0.5 * np.sin(x * 220 + np.sin(y * 60) * 2.0) * np.sin(y * 180)
    col = col * (0.88 + 0.12 * scales[..., None])
    shimmer = rng.random((size, size), dtype=np.float32)
    col = col * (0.92 + 0.08 * shimmer[..., None])
    return Image.fromarray(np.clip(col * 255, 0, 255).astype(np.uint8))


# ---------------------------------------------------------------------------
def build_atlas(tiles):
    """tiles: list of PIL images (RGB or RGBA). -> (atlas, rects in 0..1 uv)."""
    n = len(tiles)
    if n <= 1:
        cols, rows = 1, 1
    elif n == 2:
        cols, rows = 2, 1
    else:
        cols, rows = 2, 2
    ts = 512 // max(cols, rows)
    atlas = Image.new("RGBA", (ts * cols, ts * rows), (255, 255, 255, 255))
    rects = []
    inset = 2
    for i, tile in enumerate(tiles):
        cx, cy = i % cols, i // cols
        t = tile.convert("RGBA").resize((ts - 2 * inset, ts - 2 * inset), Image.LANCZOS)
        px, py = cx * ts + inset, cy * ts + inset
        atlas.paste(t, (px, py))
        rects.append((cx * ts / (ts * cols), cy * ts / (ts * rows),
                      ts / (ts * cols), ts / (ts * rows), inset / (ts * cols), inset / (ts * rows)))
    return atlas.convert("RGB") if all(m.mode == "RGB" for m in tiles) else atlas, rects


def load_tile(path, alpha=None, fallback=None, size=512):
    if path is None or not os.path.exists(path):
        return fallback(size) if fallback else Image.new("RGB", (size, size), (128, 128, 128))
    im = Image.open(path)
    if alpha and os.path.exists(alpha):
        rgb = im.convert("RGB")
        a = Image.open(alpha).convert("L").resize(rgb.size, Image.LANCZOS)
        rgb = rgb.convert("RGBA"); rgb.putalpha(a)
        return rgb
    if im.mode in ("RGBA", "LA") or (im.mode == "P" and "transparency" in im.info):
        rgba = im.convert("RGBA")
        rgb = beautify(rgba.convert("RGB"))
        out = rgb.convert("RGBA")
        out.putalpha(rgba.getchannel("A"))
        return out
    return beautify(im.convert("RGB"))


def resolve_tex(v):
    if isinstance(v, str) and v.startswith("@"):
        return find_tex(v[1:])
    return v


def pack_prop(model_id, blend, keep, target, by_height=True, grid_n=None,
              mat_map=None, default_tex=None, default_fallback=None,
              min_tris=8, force_tex=None):
    mat_map = {k: resolve_tex(v) for k, v in (mat_map or {}).items()}
    default_tex = resolve_tex(default_tex)
    blend_path = os.path.join(ROOT, blend)
    all_v, all_vt, all_vn, groups = bake_and_merge(blend_path, keep_objects=keep,
                                                   min_tris=min_tris)
    if not all_v or not any(groups.values()):
        print("%-14s SKIP (no geometry)" % model_id)
        return False
    xs = [v[0] for v in all_v]; ys = [v[1] for v in all_v]; zs = [v[2] for v in all_v]
    print("%-14s src bbox x[%.2f..%.2f] y[%.2f..%.2f] z[%.2f..%.2f] mats=%s" %
          (model_id, min(xs), max(xs), min(ys), max(ys), min(zs), max(zs),
           sorted(groups.keys())))
    all_v = normalize_global(all_v, target, by_height)

    mats = sorted(groups.keys())
    tiles = []
    for m in mats:
        key = m.lower()
        tex_path = None
        for sub, path in mat_map.items():
            if sub in key:
                tex_path = path
                break
        if force_tex:
            tex_path = resolve_tex(force_tex)
        tiles.append(load_tile(tex_path if tex_path else default_tex,
                               fallback=default_fallback))

    if grid_n:
        # shared vertex quantization; faces keep their material groups
        xs = [v[0] for v in all_v]; ys = [v[1] for v in all_v]; zs = [v[2] for v in all_v]
        minx, miny, minz = min(xs), min(ys), min(zs)
        sx = max(max(xs) - minx, 1e-6); sy = max(max(ys) - miny, 1e-6); sz = max(max(zs) - minz, 1e-6)
        cell = {}
        remap = [0] * len(all_v)
        new_v = []
        for i, (x, y, z) in enumerate(all_v):
            key = (int((x - minx) / sx * grid_n), int((y - miny) / sy * grid_n),
                   int((z - minz) / sz * grid_n))
            j = cell.get(key)
            if j is None:
                j = len(new_v)
                cell[key] = j
                new_v.append((x, y, z))
            remap[i] = j
        seen = set()
        new_groups = {}
        for m in mats:
            for tri in groups[m]:
                a, b, c = remap[tri[0]], remap[tri[1]], remap[tri[2]]
                if a == b or b == c or a == c:
                    continue
                key = (a, b, c)
                if key in seen:
                    continue
                seen.add(key)
                new_groups.setdefault(m, []).append((a, b, c, tri[3], tri[4], tri[5]))
        all_v = new_v
        groups = new_groups
        mats = [m for m in mats if m in groups]

    atlas, rects = build_atlas(tiles)

    # remap UVs per face into its material tile
    new_uvs = []
    new_tris = []
    for i, m in enumerate(mats):
        rx, ry, rw, rh, ix, iy = rects[min(i, len(rects) - 1)]
        for (a, b, c, la, lb, lc) in groups[m]:
            ids = []
            for lu in (la, lb, lc):
                u, v = all_vt[lu] if lu < len(all_vt) else (0.0, 0.0)
                u = u - math.floor(u)
                v = v - math.floor(v)
                ids.append(len(new_uvs))
                new_uvs.append((rx + ix + u * (rw - 2 * ix),
                                ry + iy + v * (rh - 2 * iy)))
            new_tris.append((a, b, c, ids[0], ids[1], ids[2]))

    out_dir = os.path.join(OUT, model_id)
    os.makedirs(out_dir, exist_ok=True)
    write_group_obj(os.path.join(out_dir, "mesh.obj"), all_v, new_uvs, new_tris)
    atlas.save(os.path.join(out_dir, "diff.png"))
    print("%-14s t=%-7d v=%-7d -> %s" % (model_id, len(new_tris), len(all_v), out_dir))
    return True


# ---------------------------------------------------------------------------
MODELS = {
    # handmade / scene packs
    "spear": dict(
        blend="models/spear.blend", keep=["spear"], target=1.85, by_height=False,
        default_tex=None, default_fallback=procedural_wood),
    "pickaxe": dict(
        blend="models/pickaxe.blend", keep=["picke_dirty_01"], target=1.15, by_height=False,
        mat_map={"picke": "@picke_dirty_01_diff"},
        default_tex="@picke_dirty_01_diff",
        default_fallback=procedural_rust),
    "fence": dict(
        blend="models/fence_post.blend", keep=["sm_fence_01"], target=1.45, by_height=False,
        grid_n=26,
        mat_map={"fence": "@fencing_fence_BaseColor"},
        default_tex="@fencing_fence_BaseColor",
        default_fallback=procedural_rust),
    "winter_tree": dict(
        blend="models/winter_tree.blend",
        keep=["Arch", "horizontal beams 1", "Horizontal beams 2",
              "Large roof support beam", "Pillars", "Roof.006"],
        target=5.4, by_height=True,
        mat_map={"chalky": "@PaintedMetal013_2K_Color",
                 "painted": "@Wood076_2K_Color"},
        default_tex="@Wood076_2K_Color",
        default_fallback=procedural_wood),
    "workbench": dict(
        blend="models/workbench.blend", keep=["industrial_storage_cart"], target=1.35, by_height=False,
        mat_map={"cart": "@industrial_storage_cart_diff"},
        default_tex="@industrial_storage_cart_diff",
        default_fallback=procedural_wood),
    "barrel": dict(
        blend="models/barrel.blend", keep=["Cylinder"], target=1.02, by_height=True,
        mat_map={"rusty": "@PaintedMetal013_2K_Color"},
        default_tex=None, default_fallback=procedural_rust),
    "gate": dict(
        blend="models/gate.blend",
        keep=["large_iron_gate", "large_iron_gate_bolt",
              "large_iron_gate_left_door", "large_iron_gate_right_door"],
        target=2.55, by_height=True,
        mat_map={"gate": "@large_iron_gate_diff"},
        default_tex="@large_iron_gate_diff",
        default_fallback=procedural_rust),
    "lantern": dict(
        blend="models/lantern.blend", keep=["Base.001"], target=0.42, by_height=True,
        mat_map={"metal": "@untitled951_Lantern_metal_BaseColor"},
        default_tex="@untitled951_Lantern_metal_BaseColor",
        default_fallback=procedural_rust),
    "cache_crate": dict(
        blend="models/special_cache.blend",
        keep=["old_military_crate_a", "old_military_crate_b",
              "old_military_crate_cloth_a", "old_military_crate_cloth_b",
              "old_military_crate_latch_a", "old_military_crate_latch_b",
              "old_military_crate_lid_a", "old_military_crate_lid_b",
              "old_military_crate_loop_a", "old_military_crate_loop_b"],
        target=1.08, by_height=False,
        mat_map={"military": "@old_military_crate_diff"},
        default_tex="@old_military_crate_diff",
        default_fallback=procedural_wood),
    "woodstack": dict(
        blend="models/woodstack.blend",
        keep=["abound_20260308-0138_Log04", "abound_20260308-0204_log05",
              "abound_20260308-0528_Log06"],
        target=1.55, by_height=False, grid_n=34,
        mat_map={"log06": "@tronco1_basecolor",
                 "log05": "@tronco2_basecolor",
                 "log04": "@tronco3_basecolor"},
        default_tex="@tronco2_basecolor",
        default_fallback=procedural_wood),
    "crate": dict(
        blend="models/create.blend", keep=["Open Top Wooden Crate"], target=1.05, by_height=True,
        mat_map={"crate": "@M_Open_Top_Crate_BaseColor"},
        default_tex="@M_Open_Top_Crate_BaseColor",
        default_fallback=procedural_wood),
    # Poly Haven packs (unique content only; other packs are the sources of the
    # scene models above)
    "fish": dict(
        blend="models/assets/models/madeka-fish_9b8e3b9a-83c5-4216-95bd-56830ffcd790/"
              "madeka-fish_82361bd7-754d-4fa8-8bed-f38f6a8de089.blend",
        keep=["Medeka fish"], target=0.62, by_height=False,
        default_tex=None, default_fallback=procedural_fish),
}


def main():
    only = set(sys.argv[1:])
    ensure_tex(
        "models/lantern.blend",
        "models/pickaxe.blend",
        "models/fence_post.blend",
        "models/barrel.blend",
        "models/create.blend",
        "models/woodstack.blend",
        "models/assets/models/madeka-fish_9b8e3b9a-83c5-4216-95bd-56830ffcd790/"
        "madeka-fish_82361bd7-754d-4fa8-8bed-f38f6a8de089.blend",
    )
    # fish albedo lives under its own pack
    # packed medaka albedo JPEGs are blank/near-black; procedural skin wins
    MODELS["fish"]["default_tex"] = None
    MODELS["fish"]["mat_map"] = {}
    ok = 0
    for mid, spec in MODELS.items():
        if only and mid not in only:
            continue
        if pack_prop(mid, **spec):
            ok += 1
    print("packed %d models" % ok)


if __name__ == "__main__":
    main()
