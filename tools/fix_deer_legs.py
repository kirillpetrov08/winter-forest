#!/usr/bin/env python3
"""Пере-разделяет тело оленя на mesh_body + 4 mesh_leg по СВЯЗНОСТИ геометрии.

Старый k-means разрез провалился: одна «нога» была обломком (118 вершин, не
достаёт до земли), две другие дублировали одну заднюю ногу — отсюда «олени не
двигают ноги» и разрывы текстур при качании.

Алгоритм: грани целиком ниже y < 0.56 образуют подграф; связные компоненты
этого подграфа — и есть 4 ноги (плюс мелкий мусор, который уходит в тело).
Грани, пересекающие срез, остаются в теле, а стык закрывается «шаром тазобедренного
сустава» в рендерере (hip ball) — швов не видно при любом размахе.

Формат mesh.obj (v/vt/vn, f a/b/c) сохраняется. Запускать после
git checkout -- app-winterforest/src/main/assets/models/deer_body/mesh.obj
(повторный запуск по уже разделённым частям не нужен — раздел идёт по mesh.obj).
"""
import math
import sys

BASE = "app-winterforest/src/main/assets/models/deer_body"
CUT_Y = 0.42      # ниже брюха (0.33) — тут ноги гарантированно разъединены
GROW_Y = 0.56     # ветки выше среза, но рядом с осью ноги, растут в состав ноги (бедро)
MIN_LEG_VERTS = 50
LEG_AVG_Y_MAX = 0.48


class UnionFind:
    def __init__(self, n):
        self.p = list(range(n))

    def find(self, a):
        while self.p[a] != a:
            self.p[a] = self.p[self.p[a]]
            a = self.p[a]
        return a

    def union(self, a, b):
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            self.p[rb] = ra


def parse(path):
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
            corners = []
            for tok in line.split()[1:]:
                parts = tok.split("/")
                vi = int(parts[0]) - 1
                ti = int(parts[1]) - 1 if len(parts) > 1 and parts[1] else -1
                ni = int(parts[2]) - 1 if len(parts) > 2 and parts[2] else -1
                corners.append((vi, ti, ni))
            faces.append(corners)
    return verts, uvs, norms, faces


def write_part(path, verts, uvs, norms, faces, tag):
    used_v, used_t, used_n = set(), set(), set()
    for corners in faces:
        for vi, ti, ni in corners:
            used_v.add(vi)
            if ti >= 0:
                used_t.add(ti)
            if ni >= 0:
                used_n.add(ni)
    vmap = {old: i + 1 for i, old in enumerate(sorted(used_v))}
    tmap = {old: i + 1 for i, old in enumerate(sorted(used_t))}
    nmap = {old: i + 1 for i, old in enumerate(sorted(used_n))}
    with open(path, "w") as f:
        f.write(f"# deer_body {tag} (tools/fix_deer_legs.py — connectivity split)\n")
        for i in sorted(used_v):
            x, y, z = verts[i]
            f.write(f"v {x:.5f} {y:.5f} {z:.5f}\n")
        for i in sorted(used_t):
            u, v = uvs[i]
            f.write(f"vt {u:.5f} {v:.5f}\n")
        for i in sorted(used_n):
            x, y, z = norms[i]
            f.write(f"vn {x:.5f} {y:.5f} {z:.5f}\n")
        for corners in faces:
            toks = []
            for vi, ti, ni in corners:
                s = str(vmap[vi])
                if ti >= 0 and ni >= 0:
                    s += f"/{tmap[ti]}/{nmap[ni]}"
                elif ni >= 0:
                    s += f"//{nmap[ni]}"
                toks.append(s)
            f.write("f " + " ".join(toks) + "\n")
    return len(used_v)


def main():
    verts, uvs, norms, faces = parse(f"{BASE}/mesh.obj")
    uf = UnionFind(len(verts))
    for corners in faces:
        if all(verts[vi][1] < CUT_Y for vi, _, _ in corners):
            for k in range(1, len(corners)):
                uf.union(corners[0][0], corners[k][0])

    # компоненты подграфа ниже брюха; сросшиеся пары «лево-право» дробим по X
    comps = {}
    for vi in range(len(verts)):
        if verts[vi][1] >= CUT_Y:
            continue
        comps.setdefault(uf.find(vi), set()).add(vi)

    leg_roots = []
    next_tag = 100
    for root, members in comps.items():
        if len(members) < MIN_LEG_VERTS:
            continue
        avg_y = sum(verts[v][1] for v in members) / len(members)
        if avg_y >= LEG_AVG_Y_MAX:
            continue
        xs = [verts[v][0] for v in members]
        if max(xs) - min(xs) > 0.045 and len(members) > 140:
            # две ноги, сросшиеся по низу: режем по провалу X
            left = {v for v in members if verts[v][0] < -0.03}
            right = members - left
            if len(left) >= 40:
                leg_roots.append((next_tag, left)); next_tag += 1
            if len(right) >= 40:
                leg_roots.append((next_tag, right)); next_tag += 1
        else:
            leg_roots.append((root, members))

    # 4 крупнейшие компоненты — ноги; порядок: перед-зад × лево-право по центроидам
    leg_roots.sort(key=lambda t: -len(t[1]))
    leg_roots = leg_roots[:4]
    leg_roots.sort(key=lambda t: (
        -sum(verts[v][2] for v in t[1]) / len(t[1]),
        sum(verts[v][0] for v in t[1]) / len(t[1]),
    ))
    print(f"legs found: {[(len(m), round(sum(verts[v][1] for v in m)/len(m), 2)) for _, m in leg_roots]}")

    # Рост вверх: ветки ниже GROW_Y, ближе 0.16 по XZ к оси ноги, входят в ногу (бедро)
    centroids = []
    for _, members in leg_roots:
        cx = sum(verts[v][0] for v in members) / len(members)
        cz = sum(verts[v][2] for v in members) / len(members)
        centroids.append((cx, cz))
    vert_leg = {}
    for _, members in leg_roots:
        for v in members:
            vert_leg[v] = None
    for root, members in leg_roots:
        for v in members:
            vert_leg[v] = root
    for vi in range(len(verts)):
        if verts[vi][1] >= GROW_Y or vi in vert_leg:
            continue
        best, best_d = None, 0.16
        for k, (cx, cz) in enumerate(centroids):
            d = math.hypot(verts[vi][0] - cx, verts[vi][2] - cz)
            if d < best_d:
                best_d, best = d, k
        if best is not None:
            vert_leg[vi] = leg_roots[best][0]
    root_to_leg = {root: idx for idx, (root, _) in enumerate(leg_roots)}

    body_faces, leg_faces = [], [[], [], [], []]
    for corners in faces:
        groups = {root_to_leg[vert_leg[vi]] for vi, _, _ in corners if vi in vert_leg and vert_leg[vi] in root_to_leg}
        if len(groups) == 1 and all(vi in vert_leg for vi, _, _ in corners):
            leg_faces[groups.pop()].append(corners)
        else:
            body_faces.append(corners)

    n_body = write_part(f"{BASE}/mesh_body.obj", verts, uvs, norms, body_faces, "body")
    print(f"mesh_body: {n_body} verts, {len(body_faces)} faces")
    for k in range(4):
        n = write_part(f"{BASE}/mesh_leg{k}.obj", verts, uvs, norms, leg_faces[k], f"leg{k}")
        ys = [verts[vi][1] for c in leg_faces[k] for vi, _, _ in c]
        if ys:
            print(f"mesh_leg{k}: {n} verts, {len(leg_faces[k])} faces, y=[{min(ys):.2f},{max(ys):.2f}]")
        else:
            print(f"mesh_leg{k}: EMPTY!")


if __name__ == "__main__":
    main()
