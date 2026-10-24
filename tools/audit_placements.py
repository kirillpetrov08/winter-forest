#!/usr/bin/env python3
"""Аудит «парящих» объектов: для каждого запакованного mesh.obj проверяет,
что геометрия стоит на земле (minY≈0 по ИСПОЛЬЗУЕМЫМ гранями вершинам),
что нет оторванных от земли кусков (компонентов с minY > 0.05 при том,
что основной компонент сидит на 0) и что нормализация не шла по мусорным
вершинам (unused verts не влияют на minY).

Запуск: python3 tools/audit_placements.py [model_id ...]
Выход: строка на модель + список проблем; код 1 если есть проблемы.
"""
import os
import sys

ROOT = "app-winterforest/src/main/assets/models"


def load_obj(path):
    verts, faces = [], []
    for line in open(path):
        s = line.strip()
        if s.startswith("v "):
            verts.append([float(x) for x in s.split()[1:4]])
        elif s.startswith("f "):
            idx = []
            for tok in s.split()[1:]:
                p = tok.split("/")
                idx.append(int(p[0]) - 1)
            faces.append(idx)
    return verts, faces


def components(used, faces):
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
            union(f[0], f[k])
    comps = {}
    for i in used:
        comps.setdefault(find(i), set()).add(i)
    return sorted(comps.values(), key=lambda m: -len(m))


def audit(model_id):
    path = os.path.join(ROOT, model_id, "mesh.obj")
    if not os.path.isfile(path):
        return [f"{model_id}: mesh.obj НЕ НАЙДЕН"]
    verts, faces = load_obj(path)
    if not faces:
        return [f"{model_id}: нет граней"]
    used = sorted({vi for f in faces for vi in f})
    ys = [verts[i][1] for i in used]
    ymin, ymax = min(ys), max(ys)
    unused = len(verts) - len(used)
    problems = []
    if ymin > 0.03 or ymin < -0.25:
        problems.append(f"minY={ymin:+.3f} (меш «парит» или ушёл под землю)")
    comp_list = components(used, faces)
    card_style = len(comp_list) > 12
    if len(comp_list) > 1 and not card_style:
        # «Карточная» растительность (листва из несвязных пластин) — компонентный
        # анализ неприменим: плавающими считаем куски только у цельных мешей.
        # Висящий кусок = целиком НАД/ПОД основной массой либо сбоку за её bbox.
        main = comp_list[0]
        mxs = [verts[i][0] for i in main]
        mys = [verts[i][1] for i in main]
        mzs = [verts[i][2] for i in main]
        mx0, mx1 = min(mxs), max(mxs)
        my0, my1 = min(mys), max(mys)
        mz0, mz1 = min(mzs), max(mzs)
        for c in comp_list[1:]:
            cxs = [verts[i][0] for i in c]
            cys = [verts[i][1] for i in c]
            czs = [verts[i][2] for i in c]
            cx0, cx1 = min(cxs), max(cxs)
            cy0, cy1 = min(cys), max(cys)
            cz0, cz1 = min(czs), max(czs)
            above = cy0 > my1 + 0.05
            below = cy1 < my0 - 0.05
            aside = (cx0 > mx1 + 0.35 or cx1 < mx0 - 0.35 or
                     cz0 > mz1 + 0.35 or cz1 < mz0 - 0.35)
            if above or below or aside:
                problems.append(
                    f"компонент из {len(c)} v висит: y=[{cy0:.3f},{cy1:.3f}]"
                )
    tag = "OK " if not problems else "BAD"
    print(f"{tag} {model_id:16s} v={len(verts):6d} used={len(used):6d} "
          f"y=[{ymin:+.2f},{ymax:+.2f}] comps={len(comp_list)} unused={unused}")
    for p in problems:
        print(f"     !! {p}")
    return problems


def main():
    ids = sys.argv[1:] or sorted(
        d for d in os.listdir(ROOT)
        if os.path.isfile(os.path.join(ROOT, d, "mesh.obj"))
    )
    bad = 0
    for mid in ids:
        bad += len(audit(mid))
    print(f"\nмоделей: {len(ids)}, проблем: {bad}")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
