#!/usr/bin/env python3
"""Software preview of packed props (OBJ + diff.png) for visual QA.

Usage: python3 tools/preview_prop.py <model_id> [yaw_deg]
Output: /tmp/preview_<model_id>.png  (two views)
"""
import math
import os
import sys

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELS = os.path.join(ROOT, "app-winterforest", "src", "main", "assets", "models")


def load_obj(path):
    vs, vts, faces = [], [], []
    for line in open(path):
        p = line.split()
        if not p:
            continue
        if p[0] == "v":
            vs.append(tuple(map(float, p[1:4])))
        elif p[0] == "vt":
            vts.append(tuple(map(float, p[1:3])))
        elif p[0] == "f":
            idx = [q.split("/") for q in p[1:]]
            fv = [int(q[0]) - 1 for q in idx]
            ft = [int(q[1]) - 1 if len(q) > 1 and q[1] else 0 for q in idx]
            for k in range(1, len(fv) - 1):
                faces.append((fv[0], fv[k], fv[k + 1], ft[0], ft[k], ft[k + 1]))
    return np.array(vs, np.float32), np.array(vts, np.float32), np.array(faces, np.int64)


def render(vs, vts, faces, tex, yaw, pitch=0.32, size=512):
    cy, sy = math.cos(yaw), math.sin(yaw)
    cp, sp = math.cos(pitch), math.sin(pitch)
    # rotate yaw (Y) then pitch (X)
    m = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]], np.float32)
    m2 = np.array([[1, 0, 0], [0, cp, -sp], [0, sp, cp]], np.float32)
    p = vs @ m.T @ m2.T
    mn, mx = p.min(0), p.max(0)
    ctr = (mn + mx) / 2
    scale = 0.9 * size / max(mx - mn)
    p = (p - ctr) * scale + size / 2
    p[:, 1] = size - p[:, 1]
    th, tw = tex.shape[:2]
    img = np.zeros((size, size, 3), np.float32) + 0.72
    zbuf = np.full((size, size), 1e9, np.float32)
    for a, b, c, ta, tb, tc in faces:
        x0, y0, z0 = p[a]; x1, y1, z1 = p[b]; x2, y2, z2 = p[c]
        area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
        if abs(area) < 1e-9:
            continue
        xmin = max(int(min(x0, x1, x2)), 0); xmax = min(int(max(x0, x1, x2)) + 1, size)
        ymin = max(int(min(y0, y1, y2)), 0); ymax = min(int(max(y0, y1, y2)) + 1, size)
        if xmin >= xmax or ymin >= ymax:
            continue
        uu0, vv0 = vts[ta]; uu1, vv1 = vts[tb]; uu2, vv2 = vts[tc]
        ys_, xs_ = np.mgrid[ymin:ymax, xmin:xmax]
        w0 = (x1 - xs_) * (y2 - ys_) - (x2 - xs_) * (y1 - ys_)
        w1 = (x2 - xs_) * (y0 - ys_) - (x0 - xs_) * (y2 - ys_)
        w2 = (x0 - xs_) * (y1 - ys_) - (x1 - xs_) * (y0 - ys_)
        mask = ((w0 >= 0) == (w1 >= 0)) & ((w1 >= 0) == (w2 >= 0))
        if not mask.any():
            continue
        s = w0 + w1 + w2
        s[s == 0] = 1e-9
        l0, l1, l2 = w0 / s, w1 / s, w2 / s
        z = l0 * z0 + l1 * z1 + l2 * z2
        cur = zbuf[ymin:ymax, xmin:xmax]
        upd = mask & (z < cur)
        if not upd.any():
            continue
        u = l0 * uu0 + l1 * uu1 + l2 * uu2
        v = l0 * vv0 + l1 * vv1 + l2 * vv2
        tx = np.mod((u * tw).astype(np.int64), tw)
        ty = np.mod(((1 - v) * th).astype(np.int64), th)
        sub = img[ymin:ymax, xmin:xmax]
        sub[upd] = tex[ty[upd], tx[upd], :3] / 255.0
        cur[upd] = z[upd]
    return (img * 255).astype(np.uint8)


def main():
    mid = sys.argv[1]
    yaw = float(sys.argv[2]) if len(sys.argv) > 2 else 0.6
    base = os.path.join(MODELS, mid)
    vs, vts, faces = load_obj(os.path.join(base, "mesh.obj"))
    tex = np.asarray(Image.open(os.path.join(base, "diff.png")).convert("RGB"))
    a = render(vs, vts, faces, tex, yaw)
    b = render(vs, vts, faces, tex, yaw + math.pi * 0.7)
    out = Image.fromarray(np.concatenate([a, b], 1))
    path = "/tmp/preview_%s.png" % mid
    out.save(path)
    print("wrote", path, "tris:", len(faces))


if __name__ == "__main__":
    main()
