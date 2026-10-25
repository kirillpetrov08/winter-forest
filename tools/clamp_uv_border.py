#!/usr/bin/env python3
"""Сдвиг UV с самой кромки атласа внутрь на полтекселя.

У `cabin_stone` 1939 вершин, у `cabin_shingle` 198/234 граней, у `cabin_bark`
359/518 имеют UV ровно 0.0 или 1.0. При GL_REPEAT выборка на границе заворачивается
на противоположную сторону: на брёвнах и крыше проступают полосы чужой текстуры
(«шов на кромке»). Сдвиг на полтекселя (1/1024 при 512²) убирает заворот, не
двигая рисунок заметно.

Запуск: python3 tools/clamp_uv_border.py cabin_stone cabin_shingle cabin_bark
"""

import os
import sys

EPS = 1.0 / 1024.0


def clamp(path):
    lines = open(path, "r", encoding="utf-8").read().splitlines(keepends=True)
    out = []
    moved = 0
    for line in lines:
        if line.startswith("vt "):
            p = line.split()
            u = float(p[1])
            v = float(p[2]) if len(p) > 2 else 0.0
            # трогаем ТОЛЬКО UV в пределах [0,1] — у тайлящихся развёрток
            # (трипланарный cabin_wood) координаты лежат за пределами атласа,
            # и clamp их схлопнул бы в одну линию
            nu = u
            nv = v
            if -1e-9 <= u <= 1.0 + 1e-9:
                nu = min(max(u, EPS), 1.0 - EPS)
            if -1e-9 <= v <= 1.0 + 1e-9:
                nv = min(max(v, EPS), 1.0 - EPS)
            if nu != u or nv != v:
                moved += 1
            out.append(f"vt {nu:.6f} {nv:.6f}\n")
        else:
            out.append(line)
    open(path, "w", encoding="utf-8").write("".join(out))
    print(f"{os.path.basename(os.path.dirname(path)):<14} UV сдвинуто внутрь: {moved}")


if __name__ == "__main__":
    root = "app-winterforest/src/main/assets/models"
    for mid in sys.argv[1:]:
        p = os.path.join(root, mid, "mesh.obj")
        if os.path.exists(p):
            clamp(p)
