#!/usr/bin/env python3
"""Удаление неиспользуемых v/vt/vn из упакованных mesh.obj.

Упаковщик (tools/pack_animals_cabin.py) писал ПОЛНЫЙ буфер вершин исходной
сцены в каждый файл материала. Для избы это значит: cabin_wood (31915 трис)
и, например, cabin_bark (всего 518 трис) получили одинаковые 13 030 вершин и
394 652 vt, то есть ~7.9 МБ на файл, из которых 99% не участвуют ни в одной
грани.

Рантайм (ObjParser.parseToExpandedFloats) раскрывает геометрию ИЗ ГРАНЕЙ,
поэтому неиспользуемые вершины не влияют на картинку — их можно удалить без
визуальных изменений. Скрипт:

  1. собирает множество реально использованных v/vt/vn;
  2. идёт по файлу в исходном порядке и выкидывает только мёртвые строки,
     перенумеровывая ссылки в гранях (порядок строк и формат сохраняются,
     поэтому диффы минимальны);
  3. перечитывает записанный файл с диска и сверяет раскрытый поток вершин
     с исходным — любые расхождения отменяют запись.

Запуск:
    python3 tools/strip_unused_obj_verts.py --all
    python3 tools/strip_unused_obj_verts.py app-winterforest/src/main/assets/models/cabin_bark/mesh.obj
"""

import os
import sys


def parse_corners(line):
    corners = []
    for tok in line.split()[1:]:
        parts = tok.split("/")
        vi = int(parts[0])
        ti = int(parts[1]) if len(parts) > 1 and parts[1] else 0
        ni = int(parts[2]) if len(parts) > 2 and parts[2] else 0
        corners.append((vi, ti, ni))
    return corners


def load(path):
    vs, vts, vns, faces = [], [], [], []
    for line in open(path, "r", encoding="utf-8"):
        if line.startswith("v "):
            vs.append(line[2:].strip())
        elif line.startswith("vt "):
            vts.append(line[3:].strip())
        elif line.startswith("vn "):
            vns.append(line[3:].strip())
        elif line.startswith("f "):
            faces.append(parse_corners(line))
    return vs, vts, vns, faces


def expanded(vs, vts, vns, faces):
    """Точный порт ObjParser.parseToExpandedFloats: 12 float на угол грани."""
    out = []
    for corners in faces:
        for vi, ti, ni in corners:
            out.append(vs[vi - 1])
            out.append(vns[ni - 1] if ni else "0 1 0")
            out.append(vts[ti - 1] if ti else "0 0")
            out.append("1 1 1 1")
    return out


def strip(path):
    with open(path, "r", encoding="utf-8") as f:
        src = f.readlines()
    original = "".join(src)

    vs, vts, vns, faces = load(path)
    before = expanded(vs, vts, vns, faces)

    used_v, used_t, used_n = set(), set(), set()
    for corners in faces:
        for vi, ti, ni in corners:
            used_v.add(vi)
            if ti:
                used_t.add(ti)
            if ni:
                used_n.add(ni)

    # Карта перенумерации: индексы присваиваются в порядке следования строк,
    # поэтому порядок и относительное расположение строк не меняются.
    map_v, map_t, map_n = {}, {}, {}
    nv = nt = nn = 0
    for raw in src:
        if raw.startswith("v "):
            nv += 1
            if nv in used_v:
                map_v[nv] = len(map_v) + 1
        elif raw.startswith("vt "):
            nt += 1
            if nt in used_t:
                map_t[nt] = len(map_t) + 1
        elif raw.startswith("vn "):
            nn += 1
            if nn in used_n:
                map_n[nn] = len(map_n) + 1

    out = []
    nv = nt = nn = 0
    for raw in src:
        if raw.startswith("v "):
            nv += 1
            if nv in used_v:
                out.append(raw)
        elif raw.startswith("vt "):
            nt += 1
            if nt in used_t:
                out.append(raw)
        elif raw.startswith("vn "):
            nn += 1
            if nn in used_n:
                out.append(raw)
        elif raw.startswith("f "):
            toks = []
            for vi, ti, ni in parse_corners(raw):
                vi2 = map_v[vi]
                ti2 = map_t[ti] if ti else 0
                ni2 = map_n[ni] if ni else 0
                if ti2 and ni2:
                    toks.append(f"{vi2}/{ti2}/{ni2}")
                elif ni2:
                    toks.append(f"{vi2}//{ni2}")
                elif ti2:
                    toks.append(f"{vi2}/{ti2}")
                else:
                    toks.append(str(vi2))
            out.append("f " + " ".join(toks) + ("\n" if raw.endswith("\n") else ""))
        else:
            out.append(raw)

    text = "".join(out)
    if not original.endswith("\n"):
        text = text.rstrip("\n")

    src_size = len(original.encode())
    new_size = len(text.encode())
    if text == original:
        print(f"{os.path.basename(os.path.dirname(path)):<16}без изменений ({src_size / 1048576:.2f} MB)")
        return

    with open(path, "w", encoding="utf-8") as f:
        f.write(text)

    rv, rt, rn, rf = load(path)
    if expanded(rv, rt, rn, rf) != before:
        raise SystemExit(f"ОТМЕНА: после записи поток вершин изменился — {path}")

    print(
        f"{os.path.basename(os.path.dirname(path)):<16}"
        f"tris={len(faces):>6}  v {len(vs):>6}->{len(rv):<6}"
        f"vt {len(vts):>7}->{len(rt):<6}vn {len(vns):>6}->{len(rn):<6}"
        f"{src_size / 1048576:6.2f} MB -> {new_size / 1048576:5.2f} MB"
    )


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if "--all" in sys.argv:
        root = "app-winterforest/src/main/assets/models"
        paths = [
            os.path.join(root, d, n)
            for d in sorted(os.listdir(root))
            for n in sorted(os.listdir(os.path.join(root, d)))
            if n.startswith("mesh") and n.endswith(".obj")
        ]
    elif args:
        paths = args
    else:
        raise SystemExit(__doc__)
    for p in paths:
        strip(p)


if __name__ == "__main__":
    main()
