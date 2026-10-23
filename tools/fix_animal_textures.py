#!/usr/bin/env python3
"""Fix imported animal/fish/spear packs: clean UVs, full fur textures, natural stance.

Проблемы, которые чинит скрипт (по скриншотам игры):
- wolf_body: UV вне [0..1] -> чёрно-мусорный мех («паук»), тёмная текстура.
- deer_body: лоскутный атлас-фейк («полной текстурой»), морда в -Z (идёт задом).
- fish: UV сжаты в угол атласа, серая рыба.
- spear: все UV в одной точке (сплошной цвет).

Что делает:
- морда оленя разворачивается в +Z (как у волка) — единая формула yaw в рендере;
- ноги поджимаются к центру тела (убираем «паучью» стойку);
- пер-парт cylindical UV: туловище — вокруг позвоночника, ноги — вокруг своей оси;
- бесшовные процедурные текстуры: мех (серый/бурый) с тёмной спиной и светлым брюхом,
  чешуя рыбы, дерево+металл для копья;
- ноги вырезаются в mesh_leg{0..3}.obj + тело в mesh_body.obj (для анимации шага).

Выход перезаписывает app-winterforest/src/main/assets/models/<id>/...
"""
import math
import os
import sys

import numpy as np
from PIL import Image

BASE = os.path.join(os.path.dirname(__file__),
                    "..", "app-winterforest", "src", "main", "assets", "models")


def read_obj(path):
    vs, vts, vns, faces = [], [], [], []
    with open(path) as f:
        for line in f:
            if line.startswith("v "):
                p = line.split()
                vs.append((float(p[1]), float(p[2]), float(p[3])))
            elif line.startswith("vt "):
                p = line.split()
                vts.append((float(p[1]), float(p[2])))
            elif line.startswith("vn "):
                p = line.split()
                vns.append((float(p[1]), float(p[2]), float(p[3])))
            elif line.startswith("f "):
                corners = []
                for tok in line.split()[1:]:
                    parts = tok.split("/")
                    vi = int(parts[0])
                    ti = int(parts[1]) if len(parts) > 1 and parts[1] else 0
                    ni = int(parts[2]) if len(parts) > 2 and parts[2] else 0
                    corners.append((vi, ti, ni))
                if len(corners) >= 3:
                    for k in range(1, len(corners) - 1):
                        faces.append((corners[0], corners[k], corners[k + 1]))
    return (np.array(vs, dtype=np.float64),
            np.array(vts, dtype=np.float64),
            np.array(vns, dtype=np.float64),
            faces)


def write_obj(path, vs, vts, vns, faces, header):
    with open(path, "w") as f:
        f.write("# %s (tools/fix_animal_textures.py)\n" % header)
        for v in vs:
            f.write("v %.5f %.5f %.5f\n" % (v[0], v[1], v[2]))
        for t in vts:
            f.write("vt %.5f %.5f\n" % (t[0], t[1]))
        for n in vns:
            f.write("vn %.5f %.5f %.5f\n" % (n[0], n[1], n[2]))
        for a, b, c in faces:
            f.write("f %d/%d/%d %d/%d/%d %d/%d/%d\n" %
                    (a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]))


def rng(seed):
    return np.random.default_rng(seed)


def fur_texture(size, base, belly, back, seed, streak=1.0):
    """Бесшовный мех: мягкий градиент u (спина->брюхо) + шум-волоски вдоль v."""
    r = rng(seed)
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float64)
    u = xx / size
    v = yy / size
    # u: 0.5 — спина (тёмная), 0/1 — брюхо (светлое), бока — середина
    backness = 0.5 + 0.5 * np.cos((u - 0.5) * 2 * math.pi)
    img = np.zeros((size, size, 3))
    for i in range(3):
        img[:, :, i] = belly[i] + (back[i] - belly[i]) * backness
        img[:, :, i] += base[i]
    # крупные пятна (напластования меха)
    for _ in range(26):
        cx, cy = r.uniform(0, size, 2)
        rad = r.uniform(size * 0.05, size * 0.16)
        amp = r.uniform(-10, 12)
        d2 = (xx - cx) ** 2 + (yy - cy) ** 2
        img += amp * np.exp(-d2 / (2 * rad * rad))[:, :, None]
    # тонкие волосины, тянущиеся вдоль v (с швом по u)
    noise = r.normal(0, 1, (size, size))
    k = max(3, size // 64)
    soft = noise.copy()
    for _ in range(2):
        soft = 0.25 * (np.roll(soft, k, 0) + np.roll(soft, -k, 0) +
                       np.roll(soft, k, 1) + np.roll(soft, -k, 1))
    hair = np.repeat(soft.mean(axis=0, keepdims=True), size, axis=0) * 34 * streak
    hair += (soft - soft.mean()) * 22
    img += hair[:, :, None]
    img += r.normal(0, 2.2, (size, size, 3))
    return np.clip(img, 0, 255).astype(np.uint8)


def scale_texture(size, seed):
    r = rng(seed)
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float64)
    u = xx / size
    v = yy / size
    img = np.zeros((size, size, 3))
    top = (150, 168, 180)
    bot = (228, 228, 216)
    for i in range(3):
        img[:, :, i] = bot[i] + (top[i] - bot[i]) * (1.0 - u)
    # чешуйки: смещённые ряды эллипсов
    cell = size / 14.0
    for row in range(-1, 16):
        for col in range(-1, 15):
            cy = row * cell + (cell * 0.5 if col % 2 else 0)
            cx = col * cell
            d = ((xx - cx) / (cell * 0.62)) ** 2 + ((yy - cy) / (cell * 0.42)) ** 2
            edge = np.clip((d - 0.72) * 3.0, 0, 1) * np.clip((1.55 - d) * 3.0, 0, 1)
            img -= (edge * 26)[:, :, None]
    img += r.normal(0, 2.5, (size, size, 3))
    return np.clip(img, 0, 255).astype(np.uint8)


def wood_metal_texture(size, seed, metal_from=0.82):
    """Вдоль v: дерево, выше metal_from — металл."""
    r = rng(seed)
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float64)
    v = yy / size
    img = np.zeros((size, size, 3))
    grain = (np.sin(yy * 0.22 + np.sin(xx * 0.05) * 2.0) * 10 +
             np.sin(yy * 0.07) * 8)
    for i, col in enumerate((122, 86, 52)):
        img[:, :, i] = col + grain
    metal_mask = np.clip((v - metal_from) * size / 6.0, 0, 1)
    for i, col in enumerate((146, 150, 158)):
        img[:, :, i] = img[:, :, i] * (1 - metal_mask) + (col + grain * 0.2) * metal_mask
    img += r.normal(0, 2.0, (size, size, 3))
    return np.clip(img, 0, 255).astype(np.uint8)


def face_center(vs, faces):
    out = np.zeros((len(faces), 3))
    for i, (a, b, c) in enumerate(faces):
        out[i] = (vs[a[0] - 1] + vs[b[0] - 1] + vs[c[0] - 1]) / 3.0
    return out


def flip_deer(vs, vns):
    """Развернуть модель на 180° вокруг Y — морда из -Z в +Z."""
    vs = vs.copy()
    vns = vns.copy()
    vs[:, 0] *= -1
    vs[:, 2] *= -1
    vns[:, 0] *= -1
    vns[:, 2] *= -1
    return vs, vns


def tuck_legs(vs, faces, hip_y, factor):
    """Поджать ноги к центру тела по X (убрать «паучью» стойку)."""
    vs = vs.copy()
    fc = face_center(vs, faces)
    mask = np.zeros(len(vs), dtype=bool)
    for i, (a, b, c) in enumerate(faces):
        if fc[i][1] < hip_y:
            mask[a[0] - 1] = mask[b[0] - 1] = mask[c[0] - 1] = True
    vs[mask, 0] *= factor
    return vs


def process_quadruped(mid, fur_seed, base, belly, back, flip, tuck, hip_frac):
    path = os.path.join(BASE, mid)
    vs, vts, vns, faces = read_obj(os.path.join(path, "mesh.obj"))
    if flip:
        vs, vns = flip_deer(vs, vns)
    y_max = vs[:, 1].max()
    hip_y = y_max * hip_frac
    if tuck:
        vs = tuck_legs(vs, faces, hip_y, tuck)
    fc = face_center(vs, faces)

    # Кластеры ног: k-means по (x,z) самых НИЗКИХ граней (стопы), затем
    # рост вверх до hip_y только вблизи семени — живот в ноги не попадает.
    leg_pivots = []
    leg_faces = []
    foot_y = y_max * 0.32
    low_idx = np.where(fc[:, 1] < foot_y)[0]
    if len(low_idx) >= 16:
        pts = fc[low_idx][:, [0, 2]]
        sx = max(float(pts[:, 0].std()), 0.02)
        sz = max(float(pts[:, 1].std()), 0.05)
        seeds = np.array([
            [-sx, pts[:, 1].mean() + sz],
            [+sx, pts[:, 1].mean() + sz],
            [-sx, pts[:, 1].mean() - sz],
            [+sx, pts[:, 1].mean() - sz],
        ])
        for _ in range(16):
            d = ((pts[:, None, :] - seeds[None, :, :]) ** 2).sum(axis=2)
            lab = d.argmin(axis=1)
            for k in range(4):
                if (lab == k).any():
                    seeds[k] = pts[lab == k].mean(axis=0)
        # радиус каждой ноги + пиот (верхняя точка)
        radii = np.zeros(4)
        for k in range(4):
            sel = pts[lab == k]
            if len(sel) < 6:
                radii[k] = 0.0
                continue
            radii[k] = max(0.10, float(np.sqrt(((sel - seeds[k]) ** 2).sum(axis=1).max())) * 1.25)
        # рост: грань ниже hip_y и вблизи семени
        near = np.zeros((len(faces), 4), dtype=bool)
        for k in range(4):
            if radii[k] <= 0:
                continue
            d = np.sqrt(((fc[:, [0, 2]] - seeds[k]) ** 2).sum(axis=1))
            near[:, k] = (fc[:, 1] < hip_y) & (d < radii[k])
        owner = np.full(len(faces), -1, dtype=int)
        for k in range(4):
            owner[near[:, k]] = k  # последний перекрывающий выигрывает — ок
        for k in range(4):
            sel = np.where(owner == k)[0]
            if len(sel) < 12:
                continue
            leg_faces.extend(sel.tolist())
            top = fc[sel]
            thr = top[:, 1].max() - (top[:, 1].max() - top[:, 1].min()) * 0.22
            topv = top[top[:, 1] >= thr]
            leg_pivots.append((float(topv[:, 0].mean()), float(top[:, 1].max()),
                               float(topv[:, 2].mean())))

    # UV: для туловища — вокруг Z-оси (позвоночник), для каждой ноги — вокруг Y
    # Проще: общий UV по вершинам вокруг Z-оси; ноги получат v-градиент по Y.
    axis = 2
    uvs = np.zeros((len(vs), 2))
    cx, cy = vs[:, 0].mean(), vs[:, 1].mean()
    for i in range(len(vs)):
        uvs[i, 0] = math.atan2(vs[i, 0] - cx, vs[i, 1] - cy) / (2 * math.pi) + 0.5
    zmin, zmax = vs[:, 2].min(), vs[:, 2].max()
    uvs[:, 1] = (vs[:, 2] - zmin) / max(1e-6, zmax - zmin)

    # Разбиение: грани по частям, UV по вершинам (общие для тела, вертикальные для ног)
    is_leg = np.zeros(len(faces), dtype=bool)
    if leg_faces:
        is_leg[np.array(leg_faces)] = True
    y_leg_min = fc[is_leg][:, 1].min() if is_leg.any() else 0.0

    def leg_uv_for_vertex(vi):
        x, y, z = vs[vi - 1]
        return (uvs[vi - 1, 0], (y - y_leg_min) / max(1e-6, hip_y - y_leg_min))

    parts = {name: {"vmap": {}, "tmap": {}, "nmap": {}, "vs": [], "vts": [], "vns": [], "fs": []}
             for name in ["body", "leg0", "leg1", "leg2", "leg3"]}

    for fi, (a, b, c) in enumerate(faces):
        if is_leg[fi]:
            cc = fc[fi]
            best, bestd = 0, 1e18
            for li, piv in enumerate(leg_pivots):
                d = (cc[0] - piv[0]) ** 2 + (cc[2] - piv[2]) ** 2
                if d < bestd:
                    best, bestd = li, d
            part = parts["leg%d" % best]
        else:
            part = parts["body"]
        for (vi, ti, ni) in (a, b, c):
            if vi not in part["vmap"]:
                part["vmap"][vi] = len(part["vs"]) + 1
                part["vs"].append(vs[vi - 1])
                part["tmap"][vi] = len(part["vts"]) + 1
                if is_leg[fi]:
                    part["vts"].append(leg_uv_for_vertex(vi))
                else:
                    part["vts"].append((uvs[vi - 1, 0], uvs[vi - 1, 1]))
                nk = ni if ni else vi
                if nk not in part["nmap"]:
                    part["nmap"][nk] = len(part["vns"]) + 1
                    part["vns"].append(vns[nk - 1] if len(vns) >= nk else (0, 1, 0))
        part["fs"].append(tuple(
            (part["vmap"][vi], part["tmap"][vi], part["nmap"].get(ni if ni else vi, 0))
            for (vi, ti, ni) in (a, b, c)))

    write_obj(os.path.join(path, "mesh_body.obj"), parts["body"]["vs"],
              parts["body"]["vts"], parts["body"]["vns"], parts["body"]["fs"],
              mid + " body")
    for k in range(4):
        p = parts["leg%d" % k]
        if p["fs"]:
            write_obj(os.path.join(path, "mesh_leg%d.obj" % k), p["vs"],
                      p["vts"], p["vns"], p["fs"], mid + " leg%d" % k)
    # перезаписать общий mesh.obj (исправленные UV/поворот); vt теперь 1:1 с v
    faces_full = [((a[0], a[0], a[2]), (b[0], b[0], b[2]), (c[0], c[0], c[2]))
                  for (a, b, c) in faces]
    write_obj(os.path.join(path, "mesh.obj"), vs, uvs, vns, faces_full, mid + " fixed")

    tex = fur_texture(512, base, belly, back, fur_seed)
    Image.fromarray(tex).save(os.path.join(path, "diff.png"))
    n_legs = sum(1 for k in range(4) if parts["leg%d" % k]["fs"])
    print("%-10s ok: legs=%d pivots=%s" % (mid, n_legs,
          [(round(p[0], 2), round(p[1], 2), round(p[2], 2)) for p in leg_pivots]))


def process_fish():
    path = os.path.join(BASE, "fish")
    vs, vts, vns, faces = read_obj(os.path.join(path, "mesh.obj"))
    # позвоночник вдоль X
    uvs = np.zeros((len(vs), 2))
    cz, cy = vs[:, 2].mean(), vs[:, 1].mean()
    for i in range(len(vs)):
        uvs[i, 0] = math.atan2(vs[i, 2] - cz, vs[i, 1] - cy) / (2 * math.pi) + 0.5
    xmin, xmax = vs[:, 0].min(), vs[:, 0].max()
    uvs[:, 1] = (vs[:, 0] - xmin) / max(1e-6, xmax - xmin)
    faces_full = [((a[0], a[0], a[2]), (b[0], b[0], b[2]), (c[0], c[0], c[2]))
                  for (a, b, c) in faces]
    write_obj(os.path.join(path, "mesh.obj"), vs, uvs, vns, faces_full, "fish fixed")
    Image.fromarray(scale_texture(512, 7)).save(os.path.join(path, "diff.png"))
    print("fish       ok: scales texture")


def process_spear():
    path = os.path.join(BASE, "spear")
    vs, vts, vns, faces = read_obj(os.path.join(path, "mesh.obj"))
    # ствол вдоль Y
    uvs = np.zeros((len(vs), 2))
    cx, cz = vs[:, 0].mean(), vs[:, 2].mean()
    for i in range(len(vs)):
        uvs[i, 0] = math.atan2(vs[i, 0] - cx, vs[i, 2] - cz) / (2 * math.pi) + 0.5
    ymin, ymax = vs[:, 1].min(), vs[:, 1].max()
    uvs[:, 1] = (vs[:, 1] - ymin) / max(1e-6, ymax - ymin)
    faces_full = [((a[0], a[0], a[2]), (b[0], b[0], b[2]), (c[0], c[0], c[2]))
                  for (a, b, c) in faces]
    write_obj(os.path.join(path, "mesh.obj"), vs, uvs, vns, faces_full, "spear fixed")
    Image.fromarray(wood_metal_texture(512, 11, metal_from=0.80)).save(
        os.path.join(path, "diff.png"))
    print("spear      ok: wood+metal texture")


if __name__ == "__main__":
    # Олень: бурый мех, морду разворачиваем в +Z, ноги поджимаем
    process_quadruped("deer_body", 3,
                      base=(12, 8, 4), belly=(236, 222, 196), back=(120, 84, 52),
                      flip=True, tuck=0.72, hip_frac=0.52)
    # Волк: серо-бурый мех, ноги поджимаем сильнее (была «паучья» стойка)
    process_quadruped("wolf_body", 5,
                      base=(0, 0, 0), belly=(168, 162, 154), back=(56, 52, 52),
                      flip=False, tuck=0.66, hip_frac=0.50)
    process_fish()
    process_spear()
    print("done")
