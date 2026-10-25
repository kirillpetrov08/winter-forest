#!/usr/bin/env python3
"""Восстановление UV трипланарной проекцией для моделей со схлопнутой развёрткой.

`cabin_wood/mesh.obj` (экспорт из `cabin 0324.blend`) имеет 65% треугольников,
у которых все три UV совпадают — такая геометрия сэмплит ОДИН тексель и
рисуется плоской заливкой. Переразвернуть исходник без Blender нельзя, но можно
спроецировать UV по трём мировым осям прямо в упакованном OBJ:

  * для каждого треугольника берётся доминирующая ось геометрической нормали;
  * UV = координаты двух других осей × масштаб (1 / texelMeters);
  * треугольники с осмысленной развёрткой (все три UV различаются) НЕ трогаются.

Запуск:
    python3 tools/triplanar_uv.py app-winterforest/src/main/assets/models/cabin_wood/mesh.obj
"""

import sys


def load(path):
    verts, uvs, vns, faces = [], [], [], []
    for line in open(path, "r", encoding="utf-8"):
        if line.startswith("v "):
            verts.append([float(t) for t in line.split()[1:4]])
        elif line.startswith("vt "):
            uvs.append([float(t) for t in line.split()[1:3]])
        elif line.startswith("vn "):
            vns.append([float(t) for t in line.split()[1:4]])
        elif line.startswith("f "):
            corners = []
            for tok in line.split()[1:]:
                p = tok.split("/")
                corners.append((int(p[0]), int(p[1]) if len(p) > 1 and p[1] else 0,
                                int(p[2]) if len(p) > 2 and p[2] else 0))
            faces.append(corners)
    return verts, uvs, vns, faces


def main(path, texel_meters=1.0):
    verts, uvs, vns, faces = load(path)
    print(f"{path}: {len(verts)} вершин, {len(uvs)} vt, {len(faces)} граней")

    collapsed_count = 0
    new_uvs = []
    new_faces = []

    for corners in faces:
        uvs_of = [uvs[c[1] - 1] if c[1] else [0.0, 0.0] for c in corners]
        # «схлопнуто» = площадь треугольника в UV-пространстве ~0: сюда попадают
        # и все три UV в одной точке, и вырожденные в линию UV (как у крышек бочки,
        # где развёртка сжата в радиус и даёт радиальные «смазы»).
        if len(uvs_of) >= 3:
            ux, uy = uvs_of[1][0] - uvs_of[0][0], uvs_of[1][1] - uvs_of[0][1]
            vx, vy = uvs_of[2][0] - uvs_of[0][0], uvs_of[2][1] - uvs_of[0][1]
            uv_area = abs(ux * vy - uy * vx) / 2.0
            # относительный порог: сравниваем с 3D-площадью треугольника
            p = [verts[c[0] - 1] for c in corners]
            ax, ay, az = (p[1][0] - p[0][0], p[1][1] - p[0][1], p[1][2] - p[0][2])
            bx, by, bz = (p[2][0] - p[0][0], p[2][1] - p[0][1], p[2][2] - p[0][2])
            cx, cy, cz = (ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx)
            area3 = 0.5 * (cx * cx + cy * cy + cz * cz) ** 0.5
            is_collapsed = uv_area <= max(1e-9, 3e-4 * area3)
        else:
            is_collapsed = True
        if not is_collapsed:
            new_faces.append(corners)
            continue

        collapsed_count += 1
        p = [verts[c[0] - 1] for c in corners]
        ux, uy, uz = (p[1][0] - p[0][0], p[1][1] - p[0][1], p[1][2] - p[0][2])
        vx, vy, vz = (p[2][0] - p[0][0], p[2][1] - p[0][1], p[2][2] - p[0][2])
        nx, ny, nz = (uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
        ax, ay, az = abs(nx), abs(ny), abs(nz)
        out = []
        for c in corners:
            x, y, z = verts[c[0] - 1]
            if ax >= ay and ax >= az:          # нормаль вдоль X -> проекция YZ
                u, v = z, y
            elif ay >= ax and ay >= az:        # вдоль Y -> XZ
                u, v = x, z
            else:                              # вдоль Z -> XY
                u, v = x, y
            new_uvs.append([u / texel_meters, v / texel_meters])
            out.append((c[0], len(uvs) + len(new_uvs), c[2]))
        new_faces.append(out)

    if collapsed_count == 0:
        print("  схлопнутых граней нет — файл не изменён")
        return

    all_uvs = uvs + new_uvs
    lines = [f"# triplanar UV by tools/triplanar_uv.py (scale 1/{texel_meters} m)\n"]
    for (x, y, z) in verts:
        lines.append(f"v {x:.5f} {y:.5f} {z:.5f}\n")
    for (u, v) in all_uvs:
        lines.append(f"vt {u:.5f} {v:.5f}\n")
    for (x, y, z) in vns:
        lines.append(f"vn {x:.5f} {y:.5f} {z:.5f}\n")
    for corners in new_faces:
        lines.append("f " + " ".join(
            f"{a}/{b}/{c}" if b and c else (f"{a}//{c}" if c else f"{a}/{b}" if b else str(a))
            for (a, b, c) in corners
        ) + "\n")
    open(path, "w", encoding="utf-8").write("".join(lines))
    print(f"  трипланарно переразвёрнуто граней: {collapsed_count} из {len(faces)}")
    print(f"  vt: {len(uvs)} -> {len(all_uvs)}")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:]]
    if not args:
        raise SystemExit(__doc__)
    main(args[0], float(args[1]) if len(args) > 1 else 1.0)
