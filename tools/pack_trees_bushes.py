#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Паковка Trees & Bushes (модель пользователя) в игровые модели.

Каждый объект пака — «карточная» листва (тысячи несвязных пластин).
Стратегия: грани делятся на «кору» и «листву» эвристикой (низ у оси ствола),
затем вершинная кластеризация (упрощение), нормализация до высоты цели
(грунт minY=0), атлас 2-4 тайлов с ремапом UV. Выход:
app-winterforest/src/main/assets/models/<id>/{mesh.obj,diff.png}.
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pack_animals_cabin import bake_and_merge, write_group_obj
from pack_new_models import build_atlas
from PIL import Image, ImageDraw
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BLEND = os.path.join("models", "Trees  Bushes pack", "Trees & Bushes.blend")
OUT = os.path.join(ROOT, "app-winterforest", "src", "main", "assets", "models")

BARK = "bark"
LEAF_GREEN = "leaf_green"
LEAF_AUTUMN = "leaf_autumn"
LEAF_DRY = "leaf_dry"


# ---------------------------------------------------------------- текстуры
def _fbm(size, freq, oct_, seed):
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:size, 0:size].astype(np.float32) / size
    t = np.zeros((size, size), np.float32)
    amp = 1.0
    for o in range(oct_):
        f = freq * 2 ** o
        g = rng.random((int(f) + 2, int(f) + 2), dtype=np.float32)
        xs = x * f
        ys = y * f
        ix = np.clip(xs.astype(int), 0, int(f))
        iy = np.clip(ys.astype(int), 0, int(f))
        fx = xs - ix
        fy = ys - iy
        fx = fx * fx * (3 - 2 * fx)
        fy = fy * fy * (3 - 2 * fy)
        g00 = g[iy, ix]
        g10 = g[iy, ix + 1]
        g01 = g[iy + 1, ix]
        g11 = g[iy + 1, ix + 1]
        t += amp * (g00 * (1 - fx) + g10 * fx) * (1 - fy) + amp * (g01 * (1 - fx) + g11 * fx) * fy
        amp *= 0.55
    return t / 1.9


def tex_bark(size=256):
    base = _fbm(size, 6, 5, 31)
    streak = _fbm(size, 2, 3, 77)
    y, _ = np.mgrid[0:size, 0:size].astype(np.float32) / size
    v = np.clip(base * 0.55 + streak * 0.45 + 0.12 * np.sin(y * 90 + streak * 8), 0, 1)
    c = np.stack([
        0.24 + v * 0.22,
        0.16 + v * 0.15,
        0.10 + v * 0.10,
    ], -1)
    return Image.fromarray(np.clip(c * 255, 0, 255).astype(np.uint8))


def tex_leaf(base_rgb, dust=0.0, size=256, seed=5):
    n1 = _fbm(size, 14, 4, seed)
    n2 = _fbm(size, 30, 3, seed + 9)
    v = np.clip(n1 * 0.6 + n2 * 0.4, 0, 1)
    c = np.stack([
        base_rgb[0] * (0.72 + v * 0.56),
        base_rgb[1] * (0.72 + v * 0.56),
        base_rgb[2] * (0.72 + v * 0.56),
    ], -1)
    if dust > 0:
        d = np.clip(_fbm(size, 4, 3, seed + 21) * 1.8 - 0.62, 0, 1)[..., None] * dust
        c = c * (1 - d) + np.array([0.86, 0.89, 0.95], np.float32) * d
    return Image.fromarray(np.clip(c * 255, 0, 255).astype(np.uint8))


TILES = {
    BARK: lambda: tex_bark(),
    LEAF_GREEN: lambda: tex_leaf((0.22, 0.30, 0.19), dust=0.28, seed=5),
    LEAF_AUTUMN: lambda: tex_leaf((0.42, 0.27, 0.10), dust=0.10, seed=17),
    LEAF_DRY: lambda: tex_leaf((0.40, 0.33, 0.20), dust=0.16, seed=23),
}


# ---------------------------------------------------------------- классификация граней
def face_classes(verts, tris, role, leaf_key):
    """role='tree': грани у оси ствола внизу -> BARK; role='bush': всё листва."""
    if role != "tree":
        return [leaf_key] * len(tris)
    ys = [v[1] for v in verts]
    ymin, ymax = min(ys), max(ys)
    h = max(ymax - ymin, 1e-6)
    low = sorted(range(len(verts)), key=lambda i: ys[i])[: max(24, len(verts) // 20)]
    ax = sum(verts[i][0] for i in low) / len(low)
    az = sum(verts[i][2] for i in low) / len(low)
    out = []
    for a, b, c, _la, _lb, _lc in tris:
        cx = (verts[a][0] + verts[b][0] + verts[c][0]) / 3
        cy = (verts[a][1] + verts[b][1] + verts[c][1]) / 3
        cz = (verts[a][2] + verts[b][2] + verts[c][2]) / 3
        r = math.hypot(cx - ax, cz - az)
        rel = (cy - ymin) / h
        if rel < 0.42 and r < 0.17 * h:
            out.append(BARK)
        else:
            out.append(leaf_key)
    return out


# ---------------------------------------------------------------- упрощение
def quantize(verts, tris, classes, grid_n):
    xs = [v[0] for v in verts]
    ys = [v[1] for v in verts]
    zs = [v[2] for v in verts]
    minx, miny, minz = min(xs), min(ys), min(zs)
    sx = max(max(xs) - minx, 1e-6)
    sy = max(max(ys) - miny, 1e-6)
    sz = max(max(zs) - minz, 1e-6)
    cell = {}
    remap = [0] * len(verts)
    new_v = []
    for i, (x, y, z) in enumerate(verts):
        key = (int((x - minx) / sx * grid_n), int((y - miny) / sy * grid_n),
               int((z - minz) / sz * grid_n))
        j = cell.get(key)
        if j is None:
            j = len(new_v)
            cell[key] = j
            new_v.append((x, y, z))
        remap[i] = j
    seen = set()
    new_t = []
    new_c = []
    for (a, b, c, la, lb, lc), cl in zip(tris, classes):
        ra, rb, rc = remap[a], remap[b], remap[c]
        if ra == rb or rb == rc or ra == rc:
            continue
        key = (ra, rb, rc)
        if key in seen:
            continue
        seen.add(key)
        new_t.append((ra, rb, rc, la, lb, lc))
        new_c.append(cl)
    return new_v, new_t, new_c


def normalize(verts, target):
    xs = [v[0] for v in verts]
    ys = [v[1] for v in verts]
    zs = [v[2] for v in verts]
    miny = min(ys)
    h = max(max(ys) - miny, 1e-6)
    s = target / h
    cx = (min(xs) + max(xs)) / 2
    cz = (min(zs) + max(zs)) / 2
    return [((x - cx) * s, (y - miny) * s, (z - cz) * s) for x, y, z in verts]


def pack_object(obj_name, model_id, role, leaf_key, target, max_tris):
    all_v, all_vt, _all_vn, groups = bake_and_merge(os.path.join(ROOT, BLEND),
                                                    keep_objects=[obj_name], min_tris=8)
    if not all_v or not any(groups.values()):
        print("%-14s SKIP (нет геометрии)" % model_id)
        return False
    # все грани объекта (в bake_and_merge группы по материалам, у нас материал 1)
    tris = []
    for g in groups.values():
        tris.extend(g)
    classes = face_classes(all_v, tris, role, leaf_key)
    all_v = normalize(all_v, target)

    grid_n = 30
    while True:
        nv, nt, nc = quantize(all_v, tris, classes, grid_n)
        if len(nt) <= max_tris or grid_n <= 10:
            break
        grid_n = max(10, int(grid_n * 0.82))
    used = sorted({i for t in nt for i in t[:3]})
    remap = {old: new for new, old in enumerate(used)}
    nv = [nv[i] for i in used]
    nt = [(remap[a], remap[b], remap[c], la, lb, lc) for a, b, c, la, lb, lc in nt]
    # финальная посадка на грунт строго по ИСПОЛЬЗУЕМЫМ вершинам (урок tree_bark)
    ymin2 = min(v[1] for v in nv)
    nv = [(x, y - ymin2, z) for x, y, z in nv]

    mats = sorted(set(nc))
    tiles = [TILES[m]() for m in mats]
    atlas, rects = build_atlas(tiles)

    # В паке UV вырождены (константы) -> генерируем из геометрии:
    # кора — цилиндрические UV (вертикальные прожилки), листва — шумовые.
    def corner_uv(a, cl):
        x, y, z = nv[a]
        if cl == BARK:
            u = (math.atan2(z, x) / (2 * math.pi)) % 1.0
            v = (y * 0.9) % 1.0
        else:
            u = (x * 1.3 + z * 0.7) % 1.0
            v = (y * 1.3 - z * 0.4) % 1.0
        return u, v

    new_uvs = []
    new_tris = []
    for i, m in enumerate(mats):
        rx, ry, rw, rh, ix, iy = rects[min(i, len(rects) - 1)]
        for (a, b, c, _la, _lb, _lc), cl in zip(nt, nc):
            if cl != m:
                continue
            ids = []
            for vi in (a, b, c):
                u, v = corner_uv(vi, cl)
                ids.append(len(new_uvs))
                new_uvs.append((rx + ix + u * (rw - 2 * ix),
                                ry + iy + v * (rh - 2 * iy)))
            new_tris.append((a, b, c, ids[0], ids[1], ids[2]))

    out_dir = os.path.join(OUT, model_id)
    os.makedirs(out_dir, exist_ok=True)
    write_group_obj(os.path.join(out_dir, "mesh.obj"), nv, new_uvs, new_tris)
    atlas.save(os.path.join(out_dir, "diff.png"))
    ys = [v[1] for v in nv]
    print("%-14s (%s) t=%-6d v=%-6d y=[%.3f..%.3f] grid=%d mats=%s"
          % (model_id, obj_name, len(new_tris), len(nv), min(ys), max(ys), grid_n, mats))
    return True


# id -> (объект, роль, листва, высота, лимит треугольников)
# Финальный набор: 3 дерева (дубрава/осина) + 3 куста из пака Trees & Bushes.
FINAL_SET = {
    "oak_a": ("tree.005", "tree", LEAF_GREEN, 3.4, 3200),
    "oak_b": ("tree.002", "tree", LEAF_AUTUMN, 3.0, 3200),
    "oak_c": ("tree.007", "tree", LEAF_AUTUMN, 3.2, 3200),
    "bush_green": ("leaves", "bush", LEAF_GREEN, 1.05, 1500),
    "bush_dry": ("leaves.001", "bush", LEAF_DRY, 1.05, 1500),
    "bush_thicket": ("tree.004", "bush", LEAF_DRY, 0.95, 1500),
}


def main():
    want = sys.argv[1:] or list(FINAL_SET.keys())
    for mid in want:
        obj, role, leaf, target, max_tris = FINAL_SET[mid]
        pack_object(obj, mid, role, leaf, target, max_tris)


if __name__ == "__main__":
    main()
