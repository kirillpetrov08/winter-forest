#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Pack the iron trail gate into a static frame + two swinging doors.

Source: models/gate.blend (objects large_iron_gate, large_iron_gate_bolt,
large_iron_gate_left_door, large_iron_gate_right_door).

All three meshes share ONE normalization transform (the one of the whole gate),
so the doors stay exactly in the frame when drawn with the same model matrix.

Output (app-winterforest/src/main/assets/models/gate/):
    mesh.obj           — full gate (frame + bolt + both doors) for static use
    mesh_frame.obj     — frame + bolt only (dynamic trail gates)
    mesh_door_l.obj    — left door leaf, hinge at its outer edge
    mesh_door_r.obj    — right door leaf

Prints the hinge X (model space, after normalization) and the door heights so
the renderer can rotate the leaves around the right axis.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pack_animals_cabin import bake_and_merge, write_group_obj  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app-winterforest", "src", "main", "assets", "models", "gate")
BLEND = os.path.join(ROOT, "models", "gate.blend")

FRAME = ["large_iron_gate", "large_iron_gate_bolt"]
LEFT = ["large_iron_gate_left_door"]
RIGHT = ["large_iron_gate_right_door"]
ALL = FRAME + LEFT + RIGHT

TARGET_HEIGHT = 2.55


def collect(keep):
    verts, uvs, _nrm, groups = bake_and_merge(BLEND, keep_objects=keep)
    tris = []
    for _mat, list_ in groups.items():
        tris.extend(list_)
    return verts, uvs, tris


def main():
    parts = {}
    for name, keep in (("all", ALL), ("frame", FRAME), ("left", LEFT), ("right", RIGHT)):
        v, vt, t = collect(keep)
        if not v or not t:
            raise SystemExit("empty part: %s" % name)
        parts[name] = (v, vt, t)

    # shared transform from the whole gate
    all_v = parts["all"][0]
    xs = [p[0] for p in all_v]
    ys = [p[1] for p in all_v]
    zs = [p[2] for p in all_v]
    minx, maxx = min(xs), max(xs)
    miny, maxy = min(ys), max(ys)
    minz, maxz = min(zs), max(zs)
    s = TARGET_HEIGHT / max(maxy - miny, 1e-6)
    cx = (minx + maxx) / 2.0
    cz = (minz + maxz) / 2.0

    def xf(verts):
        return [((x - cx) * s, (y - miny) * s, (z - cz) * s) for x, y, z in verts]

    os.makedirs(OUT, exist_ok=True)
    written = {}
    for name in ("all", "frame", "left", "right"):
        v, vt, t = parts[name]
        nv = xf(v)
        fname = "mesh.obj" if name == "all" else "mesh_%s.obj" % name
        write_group_obj(os.path.join(OUT, fname), nv, vt, t)
        xs2 = [p[0] for p in nv]
        ys2 = [p[1] for p in nv]
        zs2 = [p[2] for p in nv]
        written[name] = (len(nv), len(t))
        print(
            "%-6s %-14s verts=%-6d tris=%-6d x[%.3f,%.3f] y[%.3f,%.3f] z[%.3f,%.3f]"
            % (name, fname, len(nv), len(t), min(xs2), max(xs2), min(ys2), max(ys2), min(zs2), max(zs2))
        )

    lv = xf(parts["left"][0])
    rv = xf(parts["right"][0])
    hinge_l = min(p[0] for p in lv)
    hinge_r = max(p[0] for p in rv)
    top = max(p[1] for p in xf(all_v))
    print("\nHINGE_L_X=%.4f" % hinge_l)
    print("HINGE_R_X=%.4f" % hinge_r)
    print("GATE_HEIGHT=%.4f" % top)
    print("SCALE=%.5f" % s)


if __name__ == "__main__":
    main()
