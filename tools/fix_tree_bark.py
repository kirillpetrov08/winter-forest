#!/usr/bin/env python3
"""Чинит «летающий» сухостой tree_bark: у модели реальная геометрия висела
в воздухе (minY=0.47 — нормализация шла по 15k мусорных вершин, которые ни
одна грань не использует), а верхняя ветвь была оторвана от пня (зазор 0.15 м).

Шаги: убрать неиспользуемые вершины, опустить оторванные куски до контакта,
посадить всё на minY=0. Формат v/vt/vn + f a/b/c сохраняется.
"""
import math

PATH = "app-winterforest/src/main/assets/models/tree_bark/mesh.obj"

verts, uvs, norms, faces = [], [], [], []
for line in open(PATH):
    s = line.strip()
    if s.startswith("v "):
        verts.append([float(x) for x in s.split()[1:4]])
    elif s.startswith("vt "):
        uvs.append([float(x) for x in s.split()[1:3]])
    elif s.startswith("vn "):
        norms.append([float(x) for x in s.split()[1:4]])
    elif s.startswith("f "):
        corners = []
        for tok in s.split()[1:]:
            p = tok.split("/")
            corners.append((int(p[0]) - 1,
                            int(p[1]) - 1 if len(p) > 1 and p[1] else -1,
                            int(p[2]) - 1 if len(p) > 2 and p[2] else -1))
        faces.append(corners)

used = sorted({vi for f in faces for vi, _, _ in f})
print(f"verts total={len(verts)} used={len(used)} faces={len(faces)}")

# компоненты связности по граням
parent = {i: i for i in used}


def find(x):
    while parent[x] != x:
        parent[x] = parent[parent[x]]
        x = parent[x]
    return x


def union(a, b):
    ra, rb = find(a), find(b)
    if ra != rb:
        parent[rb] = ra


for f in faces:
    for k in range(1, len(f)):
        union(f[0][0], f[k][0])
comps = {}
for i in used:
    comps.setdefault(find(i), set()).add(i)

comp_list = sorted(comps.values(), key=lambda m: -len(m))
main = comp_list[0]
main_y1 = max(verts[i][1] for i in main)
print(f"main comp n={len(main)} y=[{min(verts[i][1] for i in main):.2f},{main_y1:.2f}]")

# оторванные куски опускаем до контакта с основным стволом
for mem in comp_list[1:]:
    y0 = min(verts[i][1] for i in mem)
    gap = y0 - main_y1
    if gap > 0.02:
        dy = -(gap - 0.06)  # слегка перекрываем стык
        for i in mem:
            verts[i][1] += dy
        print(f"  snapped comp n={len(mem)} down by {-dy:.2f}")

# посадка на землю по РЕАЛЬНОЙ геометрии
ymin = min(verts[i][1] for i in used)
for i in used:
    verts[i][1] -= ymin
print(f"grounded: shift {-ymin:.2f}, new ymin=0")

# перезапись: только используемые вершины (индексы перенумеровываются)
remap = {old: k + 1 for k, old in enumerate(used)}
used_t = sorted({ti for f in faces for _, ti, _ in f if ti >= 0})
used_n = sorted({ni for f in faces for _, _, ni in f if ni >= 0})
tmap = {old: k + 1 for k, old in enumerate(used_t)}
nmap = {old: k + 1 for k, old in enumerate(used_n)}
with open(PATH, "w") as out:
    out.write("# tree_bark cleaned by tools/fix_tree_bark.py (grounded, no floating parts)\n")
    for i in used:
        x, y, z = verts[i]
        out.write(f"v {x:.5f} {y:.5f} {z:.5f}\n")
    for i in used_t:
        u, v = uvs[i]
        out.write(f"vt {u:.5f} {v:.5f}\n")
    for i in used_n:
        x, y, z = norms[i]
        out.write(f"vn {x:.5f} {y:.5f} {z:.5f}\n")
    for corners in faces:
        toks = []
        for vi, ti, ni in corners:
            s = str(remap[vi])
            if ti >= 0 and ni >= 0:
                s += f"/{tmap[ti]}/{nmap[ni]}"
            elif ni >= 0:
                s += f"//{nmap[ni]}"
            toks.append(s)
        out.write("f " + " ".join(toks) + "\n")

ys = [verts[i][1] for i in used]
xs = [verts[i][0] for i in used]
zs = [verts[i][2] for i in used]
print(f"result: y=[{min(ys):.2f},{max(ys):.2f}] x=[{min(xs):.2f},{max(xs):.2f}] z=[{min(zs):.2f},{max(zs):.2f}]")
