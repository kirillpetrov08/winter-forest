#!/usr/bin/env python3
"""Пересчёт нормалей по граням для моделей с инвертированными `vn`.

У листвы (`bush_*`, `oak_*`, `cabin_burned`) 10–25% записанных нормалей
направлены ПРОТИВ геометрической: экспортёр сохранил нормали из сглаженных
групп, но геометрия была перевёрнута. При выключенном back-face culling это
даёт «двусторонние» грани с разным освещением и пятнистое тёмное затенение.

Скрипт:
  * считает геометрическую нормаль каждого треугольника по обходу;
  * перезаписывает `vn` единой нормалью треугольника (плоское затенение —
    для карт листвы это визуально лучше «чужих» сглаженных нормалей);
  * удаляет дублированные пары граней с обратным обходом (одна и та же
    геометрия, дважды запечённая с противоположными нормалями).

Запуск:
    python3 tools/fix_normals.py app-winterforest/src/main/assets/models/bush_thicket/mesh.obj
    python3 tools/fix_normals.py --all
"""

import glob
import math
import os
import sys


def load(path):
    verts, uvs, faces = [], [], []
    for line in open(path, "r", encoding="utf-8"):
        if line.startswith("v "):
            verts.append([float(t) for t in line.split()[1:4]])
        elif line.startswith("vt "):
            uvs.append([float(t) for t in line.split()[1:3]])
        elif line.startswith("f "):
            corners = []
            for tok in line.split()[1:]:
                p = tok.split("/")
                corners.append((int(p[0]), int(p[1]) if len(p) > 1 and p[1] else 0))
            faces.append(corners)
    return verts, uvs, faces


def main(path):
    verts, uvs, faces = load(path)
    if not faces:
        print(f"{path}: граней нет")
        return

    # 1. Удаление дублированных пар с обратным обходом
    seen = {}
    kept = []
    dropped = 0
    for corners in faces:
        ids = tuple(sorted(c[0] for c in corners))
        if ids in seen:
            dropped += 1
            continue
        seen[ids] = True
        kept.append(corners)

    # 2. Нормали по обходу грани
    out_vn = []
    out_faces = []
    for corners in kept:
        p = [verts[c[0] - 1] for c in corners]
        ax, ay, az = (p[1][0] - p[0][0], p[1][1] - p[0][1], p[1][2] - p[0][2])
        bx, by, bz = (p[2][0] - p[0][0], p[2][1] - p[0][1], p[2][2] - p[0][2])
        nx = ay * bz - az * by
        ny = az * bx - ax * bz
        nz = ax * by - ay * bx
        ln = math.sqrt(nx * nx + ny * ny + nz * nz)
        if ln < 1e-12:
            out_vn.append((0.0, 1.0, 0.0))
        else:
            out_vn.append((nx / ln, ny / ln, nz / ln))
        out_faces.append(corners)

    lines = [f"# flat normals by tools/fix_normals.py (dedup {dropped} faces)\n"]
    for (x, y, z) in verts:
        lines.append(f"v {x:.5f} {y:.5f} {z:.5f}\n")
    for (u, v) in uvs:
        lines.append(f"vt {u:.5f} {v:.5f}\n")
    for (x, y, z) in out_vn:
        lines.append(f"vn {x:.5f} {y:.5f} {z:.5f}\n")
    for i, corners in enumerate(out_faces):
        lines.append("f " + " ".join(
            f"{a}/{b}/{i + 1}" if b else f"{a}//{i + 1}"
            for (a, b) in corners
        ) + "\n")
    open(path, "w", encoding="utf-8").write("".join(lines))
    print(f"{os.path.basename(os.path.dirname(path)):<16} "
          f"граней {len(faces)} -> {len(out_faces)} (удалено дублей {dropped}), "
          f"нормали плоские по обходу")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if "--all" in sys.argv:
        root = "app-winterforest/src/main/assets/models"
        for d in ("bush_green", "bush_dry", "bush_thicket", "oak_a", "oak_b", "oak_c",
                  "cabin_burned", "woodstack"):
            p = os.path.join(root, d, "mesh.obj")
            if os.path.exists(p):
                main(p)
    elif args:
        for a in args:
            main(a)
    else:
        raise SystemExit(__doc__)
