#!/usr/bin/env python3
"""Укладывает камни (rock_09 / rock_moss_*) на широкую грань: авторские модели
лежали «на бок» — плоской плитой стоймя. Поворот Rx(-90°): (x,y,z)->(x,z,-y),
затем небольшой наклон для естественности и посадка на minY=-0.05 (лёгкая утапка).

Формат mesh.obj: v / vt / vn / f a/b/c — сохраняется без изменений.
Запуск идемпотентен только после git checkout -- assets/models/rock*/.
"""
import math
import sys

MODELS = ["rock_09", "rock_moss_a", "rock_moss_b"]
BASE = "app-winterforest/src/main/assets/models"

# Лёгкий наклон вокруг Z после укладки — камень не выглядит идеальной плиткой
TILT_DEG = {"rock_09": 14.0, "rock_moss_a": 9.0, "rock_moss_b": 11.0}


def transform(x, y, z, tilt_deg):
    # 1) укладка: (x, y, z) -> (x, z, -y)
    x1, y1, z1 = x, z, -y
    # 2) наклон вокруг Z
    t = math.radians(tilt_deg)
    ct, st = math.cos(t), math.sin(t)
    x2 = x1 * ct - y1 * st
    y2 = x1 * st + y1 * ct
    return x2, y2, z1


def transform_normal(nx, ny, nz, tilt_deg):
    t = math.radians(tilt_deg)
    ct, st = math.cos(t), math.sin(t)
    # укладка для нормалей та же
    x1, y1, z1 = nx, nz, -ny
    x2 = x1 * ct - y1 * st
    y2 = x1 * st + y1 * ct
    ln = math.sqrt(x2 * x2 + y2 * y2 + z1 * z1) or 1.0
    return x2 / ln, y2 / ln, z1 / ln


def process(model):
    path = f"{BASE}/{model}/mesh.obj"
    verts, uvs, norms, faces = [], [], [], []
    for line in open(path):
        line = line.strip()
        if line.startswith("v "):
            verts.append([float(v) for v in line.split()[1:4]])
        elif line.startswith("vt "):
            uvs.append([float(v) for v in line.split()[1:3]])
        elif line.startswith("vn "):
            norms.append([float(v) for v in line.split()[1:4]])
        elif line.startswith("f "):
            faces.append(line.split()[1:])

    tilt = TILT_DEG[model]
    out_v = []
    for x, y, z in verts:
        out_v.append(transform(x, y, z, tilt))
    out_n = []
    for nx, ny, nz in norms:
        out_n.append(transform_normal(nx, ny, nz, tilt))

    # Посадка: minY = -0.05 (камень слегка утоплен в снег)
    min_y = min(v[1] for v in out_v)
    dy = -0.05 - min_y
    out_v = [(x, y + dy, z) for (x, y, z) in out_v]

    with open(path, "w") as f:
        f.write(f"# {model} re-posed by tools/fix_rock_orientation.py (lying flat, slight tilt)\n")
        for x, y, z in out_v:
            f.write(f"v {x:.5f} {y:.5f} {z:.5f}\n")
        for u, v in uvs:
            f.write(f"vt {u:.5f} {v:.5f}\n")
        for x, y, z in out_n:
            f.write(f"vn {x:.5f} {y:.5f} {z:.5f}\n")
        for corners in faces:
            f.write("f " + " ".join(corners) + "\n")

    size = (
        max(v[0] for v in out_v) - min(v[0] for v in out_v),
        max(v[1] for v in out_v) - min(v[1] for v in out_v),
        max(v[2] for v in out_v) - min(v[2] for v in out_v),
    )
    print(f"{model}: size={tuple(round(s, 2) for s in size)} minY={min(v[1] for v in out_v):.2f}")


if __name__ == "__main__":
    for m in MODELS:
        process(m)
    print("done")
