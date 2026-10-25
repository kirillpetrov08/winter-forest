#!/usr/bin/env python3
"""Импорт Blender-модели в игровой формат `assets/models/<id>/{mesh.obj,diff.png}`.

Делает то, что раньше делалось вручную per-model скриптами:

  1. читает `.blend` (в т.ч. Blender 5.x: геометрия в `Mesh.attribute_storage`,
     трансформ объекта — в loc/rot/size/quat, а не в `obmat`);
  2. склеивает все объекты сцены в один меш, применяя мировые матрицы;
  3. (опционально) decimate с контролем качества — квадрики `pyfqmr`;
  4. пересчитывает нормали по граням (у импортов они часто инвертированы);
  5. нормализует: центр по XZ, min-Y = 0, целевая высота в метрах;
  6. пишет `mesh.obj` (v/vt/vn/f) и `diff.png` (POT, ≤ [tex_size]).

Запуск:
    python3 tools/import_model.py models/cloth.blend \
        --id cloth_bush --height 0.54 --target-tris 6000 --tex-size 512

Текстура берётся из первого JPEG/PNG в файле; если указан `--texture path.png`,
используется он (карты нормалей и прочие вспомогательные изображения
отбрасываются эвристикой: изображение с преобладанием сине-фиолетовых
оттенков считается normal map).
"""

import argparse
import io
import math
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import blend2obj as b  # noqa: E402

from PIL import Image  # noqa: E402
import numpy as np  # noqa: E402


# ----------------------------------------------------------------------------
# 1. Чтение .blend
# ----------------------------------------------------------------------------

def load_scene(path):
    """-> список {name, verts[], uvs[], tris[(a,b,c)]} + bytes файла для текстур."""
    buf, blocks, dna, end, ptr = b.parse_file(path)
    blocks_by_ptr = {blk.old_ptr: blk for blk in blocks if blk.old_ptr}
    name_off = b.field_offsets(dna, "ID")["name"][0]
    offs_o = b.field_offsets(dna, "Object")
    world = b.object_world_matrices(blocks, dna, end, ptr)

    objects = []
    for ob in blocks:
        if ob.code[:2] != b"OB":
            continue
        name = ob.data[name_off:name_off + 66].split(b"\x00")[0].decode(errors="replace")
        if name.startswith("OB"):
            name = name[2:]
        data_ptr = struct.unpack_from(end + ("Q" if ptr == 8 else "I"), ob.data, offs_o["data"][0])[0]
        mb = blocks_by_ptr.get(data_ptr)
        if mb is None or mb.code[:2] != b"ME":
            continue

        info = b.parse_mesh(mb, dna, blocks_by_ptr, end, ptr, False)
        if not info or not info.get("tris"):
            info = b.parse_mesh_attrs5(mb, dna, blocks_by_ptr, end, ptr, False)
        if not info or not info.get("tris"):
            print(f"  ! {name}: геометрия не распознана, пропуск")
            continue

        m = world.get(id(ob)) or [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]
        verts = [
            (x * m[0][0] + y * m[1][0] + z * m[2][0] + m[3][0],
             x * m[0][1] + y * m[1][1] + z * m[2][1] + m[3][1],
             x * m[0][2] + y * m[1][2] + z * m[2][2] + m[3][2])
            for (x, y, z) in info["verts"]
        ]
        # blender z-up -> engine y-up
        verts = [(x, z, -y) for (x, y, z) in verts]
        # отражение оси Z меняет обход — компенсируем перестановкой b/c
        tris = [(t[0], t[2], t[1]) for t in ((a, bb, c) for (a, bb, c, _uv, _mn) in info["tris"])]
        objects.append({
            "name": name,
            "verts": verts,
            "uvs": list(info["uvs"]),
            "tris": tris,
            "loop_ids": [(uv[0], uv[1], uv[2]) for (_a, _b, _c, uv, _mn) in info["tris"]],
        })
        print(f"  + {name}: {len(verts)} вершин, {len(tris)} треуг.")
    return objects, buf


# ----------------------------------------------------------------------------
# 2. Склейка объектов в один меш (с переиндексацией)
# ----------------------------------------------------------------------------

def merge(objects):
    verts, uvs, tris = [], [], []
    for ob in objects:
        base = len(verts)
        verts.extend(ob["verts"])
        uvs.extend(ob["uvs"])
        for (a, bb, c), (la, lb, lc) in zip(ob["tris"], ob["loop_ids"]):
            tris.append((base + a, base + bb, base + c, la, lb, lc))
    return verts, uvs, tris


# ----------------------------------------------------------------------------
# 3. Decimate
# ----------------------------------------------------------------------------

def _components(tris, nverts):
    """Связные компоненты меша (листья и стебель — отдельные острова)."""
    parent = list(range(nverts))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for (a, bb, c, *_rest) in tris:
        ra, rb = find(a), find(bb)
        if ra != rb:
            parent[ra] = rb
        rb, rc = find(bb), find(c)
        if rb != rc:
            parent[rb] = rc

    groups = {}
    for i, t in enumerate(tris):
        groups.setdefault(find(t[0]), []).append(i)
    return sorted(groups.values(), key=len, reverse=True)


def decimate(verts, uvs, tris, target_total=0, per_component=0, unshroud=True):
    """Упрощение с сохранением UV.

    `per_component` — бюджет на каждый остров отдельно (для «листовых» сканов:
    глобальное упрощение схлопывает тонкие плоские листья в рваные ошмётки,
    покомпонентное — сохраняет силуэт каждого листа).
    `target_total` — бюджет на весь меш, распределяется пропорционально.

    UV новых вершин восстанавливаются барицентрической интерполяцией по
    ближайшему исходному треугольнику острова.
    """
    from scipy.spatial import cKDTree

    comps = _components(tris, len(verts))
    if per_component:
        budget = {ci: min(len(idxs), per_component) for ci, idxs in enumerate(comps)}
    elif target_total and len(tris) > target_total:
        total = float(len(tris))
        budget = {}
        for ci, idxs in enumerate(comps):
            share = max(24, int(len(idxs) / total * target_total))
            budget[ci] = min(len(idxs), share)
    else:
        return verts, uvs, tris

    out_verts, out_uvs, out_tris = [], [], []

    def add_vert(pos, uv):
        out_verts.append(pos)
        out_uvs.append(uv)
        return len(out_verts) - 1

    for ci, idxs in enumerate(comps):
        want = budget.get(ci, len(idxs))
        if want >= len(idxs) or len(idxs) <= 48:
            # остров остаётся как есть, но с локальной нумерацией вершин
            vmap = {}
            for ti in idxs:
                a, bb, c, ta, tb, tc = tris[ti]
                na = vmap.setdefault(a, add_vert(verts[a], uvs[ta]))
                nb = vmap.setdefault(bb, add_vert(verts[bb], uvs[tb]))
                nc = vmap.setdefault(c, add_vert(verts[c], uvs[tc]))
                # углы с одинаковой позицией, но разными UV — отдельные вершины
                out_tris.append((na, nb, nc, na, nb, nc))
            continue

        used = sorted({tris[ti][k] for ti in idxs for k in (0, 1, 2)})
        remap = {v: i for i, v in enumerate(used)}
        lv = np.asarray([verts[v] for v in used], dtype=np.float64)
        lf = np.asarray([[remap[tris[i][0]], remap[tris[i][1]], remap[tris[i][2]]] for i in idxs],
                        dtype=np.int32)

        if unshroud:
            # Скан-объект — замкнутая тонкая оболочка (лист имеет толщину ~5 мм).
            # У такой оболочки нет границы, и упрощение «съедает» её целиком,
            # превращая лист в рваные ошмётки. Оставляем только лицевую половину
            # (normal·thin > -0.15): лист становится односторонней КАРТОЙ с
            # настоящей границей, которую quadric-упрощение сохраняет.
            center = lv.mean(axis=0)
            cov = (lv - center).T @ (lv - center)
            wv, vec = np.linalg.eigh(cov)
            thin = vec[:, 0].copy()
            kk = int(np.argmax(np.abs(thin)))
            if thin[kk] < 0:
                thin *= -1
            p0 = lv[lf[:, 0]]
            p1 = lv[lf[:, 1]]
            p2 = lv[lf[:, 2]]
            n = np.cross(p1 - p0, p2 - p0)
            ln = np.linalg.norm(n, axis=1)
            ln[ln < 1e-12] = 1.0
            n = n / ln[:, None]
            keep = (n @ thin) > -0.15
            if int(keep.sum()) >= 12:
                lf = lf[keep]
                lv = lv.copy()
        luv = [((uvs[tris[i][3]][0] + uvs[tris[i][4]][0] + uvs[tris[i][5]][0]) / 3.0,
                (uvs[tris[i][3]][1] + uvs[tris[i][4]][1] + uvs[tris[i][5]][1]) / 3.0) for i in idxs]

        # fast_simplification (quadric edge collapse) — доходит до заданного бюджета
        # там, где pyfqmr останавливается втрое раньше, и не трогает границу карты.
        from fast_simplification import simplify as fs_simplify
        nv, nf = fs_simplify(lv.astype(np.float32), lf.astype(np.int32), target_count=int(want))
        nv = np.asarray(nv, dtype=np.float64)
        nf = np.asarray(nf, dtype=np.int32)
        if len(nf) == 0:
            continue

        src_v = lv.astype(np.float64)
        src_f = lf
        src_uv = np.asarray(luv, dtype=np.float64)
        cent = src_v[src_f].mean(axis=1)
        tree = cKDTree(cent)

        new_idx = []
        for p in nv:
            _, ti = tree.query(p)
            tri = src_f[int(ti)]
            p0, p1, p2 = src_v[tri[0]], src_v[tri[1]], src_v[tri[2]]
            e1, e2 = p1 - p0, p2 - p0
            n = np.cross(e1, e2)
            nn = float(np.dot(n, n))
            w0 = w1 = w2 = 1.0 / 3.0
            if nn > 1e-12:
                d = p - p0
                w1 = float(np.dot(np.cross(d, e2), n) / nn)
                w2 = float(np.dot(np.cross(e1, d), n) / nn)
                w0 = 1.0 - w1 - w2
                w0 = max(0.0, min(1.0, w0))
                w1 = max(0.0, min(1.0, w1))
                w2 = max(0.0, min(1.0, w2))
                sw = w0 + w1 + w2
                if sw > 1e-9:
                    w0, w1, w2 = w0 / sw, w1 / sw, w2 / sw
            tri_uv = [uvs[tris[idxs[int(ti)]][3 + k]] for k in range(3)]
            u = w0 * tri_uv[0][0] + w1 * tri_uv[1][0] + w2 * tri_uv[2][0]
            v = w0 * tri_uv[0][1] + w1 * tri_uv[1][1] + w2 * tri_uv[2][1]
            new_idx.append(add_vert((float(p[0]), float(p[1]), float(p[2])), (u, v)))

        for tri in nf:
            a, bb, c = new_idx[int(tri[0])], new_idx[int(tri[1])], new_idx[int(tri[2])]
            out_tris.append((a, bb, c, a, bb, c))

    # при децимации позиция и UV живут вместе — оставляем как есть
    print(f"  decimate: {len(tris)} -> {len(out_tris)} треуг., {len(verts)} -> {len(out_verts)} вершин")
    return out_verts, out_uvs, out_tris


# ----------------------------------------------------------------------------
# 4. Нормали по граням (у импортов часто инвертированы)
# ----------------------------------------------------------------------------

def face_normal(p0, p1, p2):
    ux, uy, uz = p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]
    vx, vy, vz = p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2]
    nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx
    ln = math.sqrt(nx * nx + ny * ny + nz * nz) or 1.0
    return nx / ln, ny / ln, nz / ln


def smooth_normals(verts, tris):
    """Нормали, усреднённые по совпадающим вершинам (гладкое затенение листвы)."""
    acc = {}
    for (a, bb, c, *_rest) in tris:
        n = face_normal(verts[a], verts[bb], verts[c])
        for i in (a, bb, c):
            k = (round(verts[i][0], 4), round(verts[i][1], 4), round(verts[i][2], 4))
            s = acc.get(k)
            if s is None:
                acc[k] = [n[0], n[1], n[2]]
            else:
                s[0] += n[0]; s[1] += n[1]; s[2] += n[2]
    out = []
    for p in verts:
        k = (round(p[0], 4), round(p[1], 4), round(p[2], 4))
        n = acc.get(k, (0.0, 1.0, 0.0))
        ln = math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]) or 1.0
        out.append((n[0] / ln, n[1] / ln, n[2] / ln))
    return out


# ----------------------------------------------------------------------------
# 5. Нормализация масштаба
# ----------------------------------------------------------------------------

def normalize(verts, target_height):
    ys = [p[1] for p in verts]
    min_y, max_y = min(ys), max(ys)
    h = max_y - min_y
    scale = (target_height / h) if (target_height and h > 1e-6) else 1.0
    xs = [p[0] for p in verts]
    zs = [p[2] for p in verts]
    cx = (min(xs) + max(xs)) / 2.0
    cz = (min(zs) + max(zs)) / 2.0
    out = [
        ((x - cx) * scale, (y - min_y) * scale, (z - cz) * scale)
        for (x, y, z) in verts
    ]
    return out, scale, h


# ----------------------------------------------------------------------------
# 6. Текстуры из .blend
# ----------------------------------------------------------------------------

def _is_normal_map(img):
    """Normal map: сине-фиолетовое преобладание (B высокий, R/G около 128)."""
    a = np.asarray(img.convert("RGB").resize((64, 64)), dtype=np.float32)
    r, g, bl = a[..., 0].mean(), a[..., 1].mean(), a[..., 2].mean()
    return bl > 150 and bl > r + 25 and bl > g + 25


def extract_textures(buf):
    """Все встроенные PNG/JPEG из .blend -> список PIL.Image."""
    out = []
    data = buf
    # PNG
    i = 0
    while True:
        i = data.find(b"\x89PNG\r\n\x1a\n", i)
        if i < 0:
            break
        j = data.find(b"IEND", i)
        if j < 0:
            break
        chunk = data[i:j + 8]
        try:
            im = Image.open(io.BytesIO(chunk))
            im.load()
            out.append(("png", im.copy(), len(chunk)))
        except Exception:
            pass
        i = j + 8
    # JPEG
    i = 0
    while True:
        i = data.find(b"\xff\xd8\xff", i)
        if i < 0:
            break
        j = data.find(b"\xff\xd9", i)
        if j < 0:
            break
        chunk = data[i:j + 2]
        try:
            im = Image.open(io.BytesIO(chunk))
            im.load()
            out.append(("jpeg", im.copy(), len(chunk)))
            i = j + 2
        except Exception:
            i += 3
    return out


def pick_albedo(textures, allow_gray):
    """Выбрать albedo: самый крупный не-normal и не-серый (если есть выбор)."""
    cands = [t for t in textures if not _is_normal_map(t[1])]
    if not cands:
        cands = textures
    if not allow_gray and len(cands) > 1:
        colored = []
        for kind, im, size in cands:
            a = np.asarray(im.convert("RGB").resize((32, 32)), dtype=np.float32)
            sat = float(np.abs(a.max(axis=2) - a.min(axis=2)).mean())
            colored.append((sat, kind, im, size))
        colored.sort(key=lambda t: -t[0])
        if colored and colored[0][0] > 24:
            return colored[0][2]
    cands.sort(key=lambda t: -t[2])
    return cands[0][1] if cands else None


# ----------------------------------------------------------------------------
# 7. Запись
# ----------------------------------------------------------------------------

def _fmt(v):
    s = f"{v:.5f}"
    return "0.00000" if s in ("-0.00000",) else s


def write_obj(path, verts, uvs, tris):
    lines = ["# packed by tools/import_model.py\n"]
    for (x, y, z) in verts:
        lines.append(f"v {_fmt(x)} {_fmt(y)} {_fmt(z)}\n")
    for (u, v) in uvs:
        lines.append(f"vt {_fmt(u)} {_fmt(v)}\n")
    for (x, y, z) in smooth_normals(verts, tris):
        lines.append(f"vn {_fmt(x)} {_fmt(y)} {_fmt(z)}\n")
    for (a, b, c, ta, tb, tc) in tris:
        lines.append(f"f {a + 1}/{ta + 1}/{a + 1} {b + 1}/{tb + 1}/{b + 1} {c + 1}/{tc + 1}/{c + 1}\n")
    with open(path, "w", encoding="utf-8") as f:
        f.writelines(lines)
    print(f"  записан {path}: {len(verts)} вершин, {len(tris)} треуг., "
          f"{os.path.getsize(path) / 1048576:.2f} МБ")


def write_texture(img, path, tex_size):
    im = img.convert("RGB")
    w, h = im.size
    # приводим к POT-квадрату, не искажая содержимое растяжением более чем в 1.34×
    side = tex_size
    im = im.resize((side, side), Image.LANCZOS)
    im.save(path, optimize=True)
    print(f"  записана {path}: {w}x{h} -> {side}x{side}, {os.path.getsize(path) / 1048576:.2f} МБ")


# ----------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("blend")
    ap.add_argument("--id", required=True, help="идентификатор модели (папка в assets/models)")
    ap.add_argument("--height", type=float, default=0.0, help="целевая высота в метрах (0 = не масштабировать)")
    ap.add_argument("--target-tris", type=int, default=0, help="бюджет треугольников на весь меш (0 = без лимита)")
    ap.add_argument("--per-component", type=int, default=0,
                    help="бюджет на каждый остров (лист) отдельно — для сканов листвы")
    ap.add_argument("--tex-size", type=int, default=512)
    ap.add_argument("--texture", default=None, help="явный файл текстуры (иначе берётся из .blend)")
    ap.add_argument("--out-dir", default=None)
    args = ap.parse_args()

    out_dir = args.out_dir or os.path.join("app-winterforest/src/main/assets/models", args.id)
    os.makedirs(out_dir, exist_ok=True)

    print(f"[1/6] читаю {args.blend}")
    objects, buf = load_scene(args.blend)
    if not objects:
        raise SystemExit("в сцене нет геометрии")

    print("[2/6] склейка")
    verts, uvs, tris = merge(objects)
    print(f"  итого: {len(verts)} вершин, {len(tris)} треуг.")

    if args.target_tris or args.per_component:
        print(f"[3/6] decimate: по-компонентно {args.per_component or '-'}, всего ≤ {args.target_tris or '-'}")
        verts, uvs, tris = decimate(verts, uvs, tris,
                                    target_total=args.target_tris,
                                    per_component=args.per_component)
    else:
        print("[3/6] decimate не требуется")

    print("[4/6] нормализация")
    verts, scale, src_h = normalize(verts, args.height)
    print(f"  исходная высота {src_h:.3f} -> {args.height or src_h:.3f} м (масштаб {scale:.3f})")
    ys = [p[1] for p in verts]
    xs = [p[0] for p in verts]
    zs = [p[2] for p in verts]
    print(f"  габарит: W={max(xs) - min(xs):.2f} H={max(ys) - min(ys):.2f} D={max(zs) - min(zs):.2f} м, minY={min(ys):.4f}")

    print("[5/6] текстура")
    if args.texture:
        tex = Image.open(args.texture)
    else:
        textures = extract_textures(buf)
        print(f"  в файле найдено изображений: {len(textures)}")
        tex = pick_albedo(textures, allow_gray=True)
        if tex is None:
            raise SystemExit("текстура не найдена — укажите --texture")
    write_texture(tex, os.path.join(out_dir, "diff.png"), args.tex_size)

    print("[6/6] запись меша")
    write_obj(os.path.join(out_dir, "mesh.obj"), verts, uvs, tris)


if __name__ == "__main__":
    main()
