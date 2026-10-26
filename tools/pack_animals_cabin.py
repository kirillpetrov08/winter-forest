#!/usr/bin/env python3
"""Pack the imported cabin / wolf / deer .blend packs into game-ready
grouped OBJ assets + albedo PNGs (materials mapped to texture groups).

Output: app-winterforest/src/main/assets/models/<group_id>/mesh.obj + diff.png
"""
import os
import sys
import glob

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from blend2obj import (
    parse_file, parse_mesh, field_offsets, block_type, material_names, i32,
    object_world_matrices,
)
from PIL import Image, ImageDraw
import struct

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app-winterforest/src/main/assets/models")


def bake_and_merge(blend_path, keep_objects=None, skip_objects=(), min_tris=25, mirror_x=False):
    """Return merged verts/uvs/normals + per-object tris with material names.

    mirror_x=True отражает геометрию через плоскость X=0 и сшивает шов —
    для ассетов, которые в источнике являются половиной модели (см.
    mirror_scene_along_x).
    """
    buf, blocks, dna, end, ptr_size = parse_file(blend_path)
    byp = {b.old_ptr: b for b in blocks if b.old_ptr}
    id_o = field_offsets(dna, "ID")["name"][0]
    offs = field_offsets(dna, "Object")
    worlds = object_world_matrices(blocks, dna, end, ptr_size)

    all_v, all_vt, all_vn = [], [], []
    groups = {}  # mat_name -> list[(a,b,c)]
    for ob in blocks:
        if ob.code[:2] != b"OB":
            continue
        nm = ob.data[id_o: id_o + 66].split(b"\x00")[0].decode(errors="replace")
        if nm.startswith("OB"):
            nm = nm[2:]
        if skip_objects and nm in skip_objects:
            continue
        if keep_objects is not None and nm not in keep_objects:
            continue
        dp = struct.unpack_from(end + "Q", ob.data, offs["data"][0])[0]
        me = byp.get(dp)
        if me is None or block_type(dna, me) != "Mesh":
            continue
        info = parse_mesh(me, dna, byp, end, ptr_size, verbose=False)
        if not info or not info["tris"] or len(info["tris"]) < min_tris:
            continue
        mats = material_names(me, dna, byp, end, ptr_size)
        m = worlds.get(id(ob))
        if m is None:
            mat_off = offs["obmat"][0]
            m = [struct.unpack_from(end + "16f", ob.data, mat_off)[i:i + 4] for i in range(0, 16, 4)]

        def xform(v):
            x, y, z = v
            gx = x * m[0][0] + y * m[1][0] + z * m[2][0] + m[3][0]
            gy = x * m[0][1] + y * m[1][1] + z * m[2][1] + m[3][1]
            gz = x * m[0][2] + y * m[1][2] + z * m[2][2] + m[3][2]
            return (gx, gz, -gy)

        base = len(all_v)
        uvbase = len(all_vt)
        for v in info["verts"]:
            all_v.append(xform(v))
        for uv in info["uvs"]:
            all_vt.append(uv)
        all_vn.extend([(0, 1, 0)] * len(info["verts"]))
        for (a, b, c, (la, lb, lc), mn) in info["tris"]:
            gname = mats.get(mn, "mat%d" % mn)
            groups.setdefault(gname, []).append(
                (base + a, base + b, base + c, uvbase + la, uvbase + lb, uvbase + lc))
    if mirror_x:
        n0 = len(all_v)
        all_v, all_vt, groups = mirror_scene_along_x(all_v, all_vt, groups)
        all_vn = [(0, 1, 0)] * len(all_v)
        print("   mirror_x: %d -> %d verts (шов сварен)" % (n0, len(all_v)))
    return all_v, all_vt, all_vn, groups


def mirror_scene_along_x(verts, uvs, groups, weld=2e-4):
    """Отражает всю сцену (verts/uvs + группы граней по материалам) через X=0.

    Возвращает новый пул вершин, новый пул UV и те же имена групп с гранями
    исходной половины и их зеркальными копиями.
    """
    base = len(verts)
    uvbase = len(uvs)
    out_v = list(verts) + [(-x, y, z) for (x, y, z) in verts]
    out_vt = list(uvs) + list(uvs)
    out_groups = {}
    for gname, tris in groups.items():
        doubled = list(tris)
        for (a, b, c, la, lb, lc) in tris:
            # обратный обход: после пересчёта нормалей отражённая половина
            # тоже смотрит наружу
            doubled.append((base + a, base + c, base + b, uvbase + la, uvbase + lc, uvbase + lb))
        out_groups[gname] = doubled
    return weld_seam_x(out_v, out_vt, out_groups, weld=weld)


def weld_seam_x(verts, uvs, groups, weld=2e-4):
    """Сваривает вершины на плоскости зеркала и выбрасывает вырожденные грани."""
    inv = 1.0 / max(weld, 1e-9)
    remap = list(range(len(verts)))
    buckets = {}
    for i, (x, y, z) in enumerate(verts):
        ax = 0.0 if abs(x) < weld else x
        key = (int(round(ax * inv)), int(round(y * inv)), int(round(z * inv)))
        j = buckets.get(key)
        if j is None:
            buckets[key] = i
        else:
            remap[i] = j
    out_groups = {}
    used_v = set()
    for gname, tris in groups.items():
        kept = []
        for (a, b, c, la, lb, lc) in tris:
            ra, rb, rc = remap[a], remap[b], remap[c]
            if ra == rb or rb == rc or ra == rc:
                continue
            kept.append((ra, rb, rc, la, lb, lc))
            used_v.update((ra, rb, rc))
        out_groups[gname] = kept
    # уплотняем пул вершин: сваренные и неиспользованные не попадают в OBJ
    order = sorted(used_v)
    compact = {old: i for i, old in enumerate(order)}
    new_v = [verts[i] for i in order]
    out_groups = {
        gname: [(compact[a], compact[b], compact[c], la, lb, lc)
                for (a, b, c, la, lb, lc) in tris]
        for gname, tris in out_groups.items()
    }
    return new_v, uvs, out_groups


def map_group(mat_name, mapping, default):
    low = mat_name.lower()
    for key, grp in mapping.items():
        if key in low:
            return grp
    return default


def normalize_global(verts, target, by_height=True, width=None):
    """Масштабирует и центрирует геометрию в игровые единицы.

    by_height=True — цель задаёт высоту (Y), масштаб равномерный.

    width — дополнительная цель по ШИРИНЕ (X), которая применяется анизотропно
    (сжимается только X, высота и длина сохраняются). Это нужно зеркальным
    ассетам: половина туши RedDeer в источнике непропорционально широкая,
    поэтому после отражения олень выходит «бочкообразным» (ширина 0.80 м при
    длине 1.60 м вместо реальных для марала 0.42/1.60). Равномерное
    масштабирование здесь не подходит: вместе с шириной оно уронило бы высоту
    ниже квоты AnimalModelsTest (1.60–1.95 м).
    """
    xs = [v[0] for v in verts]; ys = [v[1] for v in verts]; zs = [v[2] for v in verts]
    minx, maxx = min(xs), max(xs); miny, maxy = min(ys), max(ys); minz, maxz = min(zs), max(zs)
    if by_height:
        cur = max(maxy - miny, 1e-6)
    else:
        cur = max(maxx - minx, maxy - miny, maxz - minz, 1e-6)
    s = target / cur
    cx, cz = (minx + maxx) / 2, (minz + maxz) / 2
    sx = s
    if width:
        span_x = max(maxx - minx, 1e-6) * s
        sx = s * (width / span_x)
    return [((x - cx) * sx, (y - miny) * s, (z - cz) * s) for x, y, z in verts]


def write_group_obj(path, verts, uvs, tris):
    """tris: (a, b, c, la, lb, lc) — вершины и UV индексы."""
    with open(path, "w") as f:
        f.write("# packed by tools/pack_animals_cabin.py\n")
        for x, y, z in verts:
            f.write("v %.5f %.5f %.5f\n" % (x, y, z))
        for u, v in uvs:
            f.write("vt %.5f %.5f\n" % (u, v))
        nrm = [[0.0, 0.0, 0.0] for _ in verts]
        for a, b, c, _la, _lb, _lc in tris:
            ax, ay, az = verts[a]; bx, by, bz = verts[b]; cx, cy, cz = verts[c]
            ux, uy, uz = bx - ax, by - ay, bz - az
            vx, vy, vz = cx - ax, cy - ay, cz - az
            nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx
            for i in (a, b, c):
                nrm[i][0] += nx; nrm[i][1] += ny; nrm[i][2] += nz
        for nx, ny, nz in nrm:
            l = (nx * nx + ny * ny + nz * nz) ** 0.5 or 1.0
            f.write("vn %.5f %.5f %.5f\n" % (nx / l, ny / l, nz / l))
        for a, b, c, la, lb, lc in tris:
            f.write("f %d/%d/%d %d/%d/%d %d/%d/%d\n" % (a + 1, la + 1, a + 1, b + 1, lb + 1, b + 1, c + 1, lc + 1, c + 1))


def decimate_quadric(verts, uvs, tris, target_tris):
    """Упрощение квадрикой ошибок (pyfqmr) с сохранением силуэта, симметрии и UV.

    Cluster-упрощение ([decimate_cluster]) квантует вершины по сетке и ломает
    силуэт: после него bounding box оленя становился несимметричным
    (X -0.283…+0.297 вместо -0.2961…+0.2961), а крайние точки высоты и длины
    уезжали. Квадрика держит и симметрию (asymX = 0.00000), и точные
    Ymin/Ymax/Zmin/Zmax, поэтому для животных используется она.

    pyfqmr не принимает UV, поэтому развертка восстанавливается после
    упрощения по ближайшему ИСХОДНОМУ УГЛУ треугольника (позиция + свой UV):
    так пиксельные швы развертки не свариваются. Если упрощение сдвинуло
    вершины сильнее 5 см, UV ненадёжны и функция возвращает None — вызывающий
    код откатывается на [decimate_cluster].

    Требуется `pip install pyfqmr numpy`; без него ассеты не пересобираются.
    """
    if target_tris is None or len(tris) <= target_tris:
        return verts, uvs, tris
    try:
        import numpy as np
        import pyfqmr
    except ImportError:
        return None

    v_arr = np.asarray(verts, dtype=np.float64)
    f_arr = np.asarray([(a, b, c) for (a, b, c, _la, _lb, _lc) in tris], dtype=np.int32)

    mesh = pyfqmr.Simplify()
    mesh.setMesh(v_arr, f_arr)
    try:
        result = mesh.simplify_mesh(
            target_count=int(target_tris),
            aggressiveness=7,
            preserve_border=True,
            verbose=False
        )
    except TypeError:
        return None
    if result is None:
        result = mesh.getMesh()
    out_v = np.asarray(result[0], dtype=np.float64)
    out_f = np.asarray(result[1], dtype=np.int32)
    if len(out_f) == 0 or len(out_v) == 0:
        return None

    # Исходные углы (позиция + UV) — пул кандидатов для восстановления развертки.
    corners_pos = []
    corners_uv_idx = []
    for (a, b, c, la, lb, lc) in tris:
        for vi, ti in ((a, la), (b, lb), (c, lc)):
            corners_pos.append(verts[vi])
            corners_uv_idx.append(ti)
    pos_arr = np.asarray(corners_pos, dtype=np.float64)

    # Для каждой упрощённой вершины — индекс ближайшего исходного угла.
    nearest_corner = np.zeros(len(out_v), dtype=np.int64)
    max_shift = 0.0
    step = 64
    for start in range(0, len(out_v), step):
        chunk = out_v[start:start + step]
        d2 = ((chunk[:, None, :] - pos_arr[None, :, :]) ** 2).sum(axis=2)
        best = d2.argmin(axis=1)
        shift = np.sqrt(d2.min(axis=1))
        if len(shift) and float(shift.max()) > max_shift:
            max_shift = float(shift.max())
        nearest_corner[start:start + len(chunk)] = best
    if max_shift > 0.05:
        print("   decimate_quadric: сдвиг вершин %.3f м > 5 см — UV ненадёжны, откат" % max_shift)
        return None

    vt_index = {}
    out_vt = []
    out_tris = []
    for tri in out_f:
        idx = []
        for corner in range(3):
            ti = corners_uv_idx[int(nearest_corner[int(tri[corner])])]
            uv = uvs[ti]
            ukey = (round(uv[0], 5), round(uv[1], 5))
            j = vt_index.get(ukey)
            if j is None:
                j = len(out_vt)
                vt_index[ukey] = j
                out_vt.append(uv)
            idx.append(j)
        out_tris.append((int(tri[0]), int(tri[1]), int(tri[2]), idx[0], idx[1], idx[2]))
    out_verts = [(float(x), float(y), float(z)) for x, y, z in out_v]
    print("   decimate_quadric: %d -> %d трис, %d вершин, %d vt (сдвиг до %.4f м)"
          % (len(tris), len(out_tris), len(out_verts), len(out_vt), max_shift))
    return out_verts, out_vt, out_tris


def convert_tex(src, dst, size=512, alpha=None):
    im = Image.open(src).convert("RGB").resize((size, size), Image.LANCZOS)
    if alpha:
        im = im.convert("RGBA")
        im.putalpha(Image.open(alpha).convert("L").resize((size, size), Image.LANCZOS))
    im.save(dst)


def solid_tex(dst, rgb, size=128):
    im = Image.new("RGB", (size, size), rgb)
    d = ImageDraw.Draw(im)
    import random
    random.seed(7)
    for _ in range(220):
        x, y = random.randrange(size), random.randrange(size)
        r = random.randrange(3, 12)
        k = random.randrange(-18, 18)
        d.ellipse((x - r, y - r, x + r, y + r),
                  fill=(max(0, rgb[0] + k), max(0, rgb[1] + k), max(0, rgb[2] + k)))
    im.save(dst)


def decimate_cluster(verts, tris, grid_n=48):
    """Простое упрощение: квантование вершин по сетке + удаление вырожденных треугольников."""
    if not verts or not tris:
        return verts, tris
    xs = [v[0] for v in verts]; ys = [v[1] for v in verts]; zs = [v[2] for v in verts]
    minx, miny, minz = min(xs), min(ys), min(zs)
    sx = max(max(xs) - minx, 1e-6); sy = max(max(ys) - miny, 1e-6); sz = max(max(zs) - minz, 1e-6)
    cell = {}
    remap = [0] * len(verts)
    new_verts = []
    for i, (x, y, z) in enumerate(verts):
        key = (int((x - minx) / sx * grid_n), int((y - miny) / sy * grid_n), int((z - minz) / sz * grid_n))
        j = cell.get(key)
        if j is None:
            j = len(new_verts)
            cell[key] = j
            new_verts.append((x, y, z))
        remap[i] = j
    seen = set()
    new_tris = []
    for a, b, c, la, lb, lc in tris:
        ra, rb, rc = remap[a], remap[b], remap[c]
        if ra == rb or rb == rc or ra == rc:
            continue
        key = (ra, rb, rc)
        if key in seen:
            continue
        seen.add(key)
        new_tris.append((ra, rb, rc, la, lb, lc))
    return new_verts, new_tris


def pack_model(blend_path, group_spec, target, by_height=True, keep_objects=None, skip_objects=(),
               min_tris=25, grid_n=None, mirror_x=False, target_tris=None, width=None):
    """group_spec: {out_model_id: (mat_mapping, default_group_or_None, texture_src_or_rgb)}"""
    all_v, all_vt, all_vn, groups = bake_and_merge(blend_path, keep_objects, skip_objects, min_tris,
                                                   mirror_x=mirror_x)
    all_v = normalize_global(all_v, target, by_height, width=width)
    # assign faces to output groups: explicit mapping wins; otherwise default group
    out_faces = {gid: [] for gid in group_spec}

    def resolve(mat_name):
        low = mat_name.lower()
        matched = False
        for gid, (mapping, default, _tex) in group_spec.items():
            for key, target in mapping.items():
                if key in low:
                    matched = True
                    if target is not None:
                        return target
        if matched:
            return None
        for gid, (mapping, default, _tex) in group_spec.items():
            if default:
                return gid
        return None

    for mat_name, tris in groups.items():
        gid = resolve(mat_name)
        if gid in out_faces:
            out_faces[gid].extend(tris)
        else:
            print("   (skip mat %s)" % mat_name)
    print("   materials:", sorted(groups.keys()))
    for gid, (mapping, default, tex) in group_spec.items():
        tris = out_faces[gid]
        if not tris:
            print("   %-16s EMPTY (skipped)" % gid)
            continue
        verts = all_v
        group_vt = all_vt
        if target_tris:
            q = decimate_quadric(all_v, all_vt, tris, target_tris)
            if q is None:
                print("   (pyfqmr недоступен — откат на cluster-упрощение)")
                verts, tris = decimate_cluster(all_v, tris, grid_n or 48)
            else:
                verts, group_vt, tris = q
        elif grid_n:
            verts, tris = decimate_cluster(all_v, tris, grid_n)
        out_dir = os.path.join(OUT, gid)
        os.makedirs(out_dir, exist_ok=True)
        write_group_obj(os.path.join(out_dir, "mesh.obj"), verts, group_vt, tris)
        if isinstance(tex, tuple):
            convert_tex(tex[0], os.path.join(out_dir, "diff.png"), 512, tex[1])
        elif isinstance(tex, str):
            convert_tex(tex, os.path.join(out_dir, "diff.png"), 512)
        else:
            solid_tex(os.path.join(out_dir, "diff.png"), tex)
        print("%-16s t=%-7d  %s" % (gid, len(tris), out_dir))


def main():
    # ensure packed source textures exist (they live inside the .blend files)
    from extract_packed_images import extract_packed
    extract_packed(os.path.join(ROOT, "models/The Deer Rigger/The Deer Rigger.blend"),
                   "/tmp/packed_The_Deer_Rigger")
    extract_packed(os.path.join(ROOT, "models/Log cabin with Fireplace/cabin 0324.blend"),
                   "/tmp/packed_cabin_0324")
    print("== wolf")
    wolf = glob.glob(os.path.join(ROOT, "models/Wolf*/*/*.blend"))
    wolf_one = [w for w in wolf if "One_Mesh" in w]
    pack_model(
        wolf_one[0],
        {
            "wolf_body": ({"eyes": None, "fur": None, "body": "wolf_body",
                           "teeth": "wolf_body", "claw": "wolf_body"}, True,
                          "models/Wolf Rigged Animated and Game Ready/Wolf_Blender/textures/Wolf_Body.jpg"),
            "wolf_fur": ({"fur": "wolf_fur"}, False,
                         "models/Wolf Rigged Animated and Game Ready/Wolf_Blender/textures/Wolf_Fur.jpg"),
            "wolf_eyes": ({"eyes": "wolf_eyes"}, False,
                          "models/Wolf Rigged Animated and Game Ready/Wolf_Blender/textures/Wolf_Eyes_1.jpg"),
        },
        target=0.92, by_height=True
    )
    print("== deer")
    pack_model(
        os.path.join(ROOT, "models/The Deer Rigger/The Deer Rigger.blend"),
        {
            "deer_body": ({"antler": None, "hair": "deer_body", "reddeer": "deer_body",
                           "material": "deer_body"}, True,
                          "/tmp/packed_The_Deer_Rigger/RedDeerPaint.png"),
            "deer_antlers": ({"antler": "deer_antlers"}, False, (176, 148, 110)),
        },
        # 1.82 м в холке/рогах при длине 1.61 м — габариты взрослого марала
        # (квота AnimalModelsTest: высота 1.60–1.95, длина 1.45–1.85)
        target=1.82, by_height=True,
        # ширина задаётся отдельно и сжимает только X: половина туши в источнике
        # непропорционально широкая (см. normalize_global)
        width=0.42,
        keep_objects={"RedDeer"},
        # исходный RedDeer — ПОЛОВИНА туши (зеркальный модификатор не запечён):
        # без отражения олень плоский, а ноги mesh_leg2/mesh_leg3 пустые
        mirror_x=True,
        # 6862 треугольника на зеркальной туше — тяжёлый ассет для стадного
        # животного (квота по спецификации 2–6 тыс.); квадрика держит силуэт
        target_tris=3400
    )
    print("== cabin")
    pack_model(
        os.path.join(ROOT, "models/Log cabin with Fireplace/cabin 0324.blend"),
        {
            "cabin_wood": ({"bark": None, "shingle": None, "stone": None, "fireplace": None,
                            "burned": None, "glass": None}, True,
                           "/tmp/packed_cabin_0324/WoodFlat_Color.jpg"),
            "cabin_bark": ({"bark": "cabin_bark"}, False,
                           "/tmp/packed_cabin_0324/bark1.jpg"),
            "cabin_stone": ({"stone": "cabin_stone", "fireplace": "cabin_stone"}, False,
                            "/tmp/packed_cabin_0324/NaturalStone_03.jpg.jpg"),
            "cabin_shingle": ({"shingle": "cabin_shingle", "roof": "cabin_shingle"}, False,
                              "/tmp/packed_cabin_0324/shin35L.jpg"),
            "cabin_burned": ({"burned": "cabin_burned"}, False,
                             "/tmp/packed_cabin_0324/WoodBurned_03.jpg"),
        },
        target=9.5, by_height=False, grid_n=56
    )


if __name__ == "__main__":
    main()
