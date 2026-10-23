#!/usr/bin/env python3
"""Pack Poly Haven .blend models from models/ into game-ready assets:
OBJ meshes (world-normalized scale, y-up) + 512px RGBA albedo PNGs.

Output: app-winterforest/src/main/assets/models/<model_id>/{mesh.obj,diff.png}
"""
import os
import sys
import glob

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from blend2obj import parse_file, parse_mesh, write_obj, field_offsets, block_type
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app-winterforest/src/main/assets/models")

# model_id -> (blend folder, target height/size in meters, texture sources)
PACKS = {
    "rock_09":        ("rock_09_1k.blend", 1.35, [("rock_09_diff_1k.jpg", None)]),
    "rock_moss_a":    ("rock_moss_set_01_1k.blend", 1.15, [("rock_moss_set_01_diff_1k.jpg", None)]),
    "rock_moss_b":    ("rock_moss_set_01_1k.blend", 1.45, [("rock_moss_set_01_diff_1k.jpg", None)]),
    "fire_pit":       ("stone_fire_pit_1k.blend", 2.2, [("stone_fire_pit_diff_1k.jpg", None)]),
    "wooden_axe":     ("wooden_axe_02_1k.blend", 1.05, [("wooden_axe_02_diff_1k.jpg", None)]),
    "dry_branches_a": ("dry_branches_medium_01_1k.blend", 1.10, [("dry_branches_medium_01_diff_1k.jpg", None)]),
    "dry_branches_b": ("dry_branches_medium_01_1k.blend", 1.45, [("dry_branches_medium_01_diff_1k.jpg", None)]),
    "calathea":       ("calathea_orbifolia_01_1k.blend", 0.95, [("calathea_orbifolia_01_diff_1k.jpg", "calathea_orbifolia_01_alpha_1k.png")]),
    "tree_bark":      ("island_tree_01_1k.blend", 7.2, [("island_tree_01_diff_1k.jpg", None)]),
    "tree_leaves":    ("island_tree_01_1k.blend", 7.2, [("island_tree_01_leaves_diff_1k.png", "island_tree_01_leaves_alpha_1k.png")]),
}

# which object (OB name) supplies each mesh; rock_moss uses two rocks from the set
OBJECT_PICK = {
    "rock_09": "rock_09_LOD3",
    "rock_moss_a": "rock_moss_set_01_rock03",
    "rock_moss_b": "rock_moss_set_01_rock02",
    "fire_pit": "stone_fire_pit",
    "wooden_axe": "wooden_axe_02",
    "dry_branches_a": "dry_branches_medium_01_a",
    "dry_branches_b": "dry_branches_medium_01_c",
    "calathea": "calathea_orbifolia_01_d",
    "tree_bark": "island_tree_01_geometry_nodes",
    "tree_leaves": "island_tree_01_geometry_nodes",
}


def load_mesh(blend_path, obj_name):
    buf, blocks, dna, end, ptr_size = parse_file(blend_path)
    byp = {b.old_ptr: b for b in blocks if b.old_ptr}
    id_o = field_offsets(dna, "ID")["name"][0]
    for ob in blocks:
        if ob.code[:2] != b"OB":
            continue
        nm = ob.data[id_o: id_o + 66].split(b"\x00")[0].decode(errors="replace")
        if nm.startswith("OB"):
            nm = nm[2:]
        if nm != obj_name:
            continue
        offs = field_offsets(dna, "Object")
        dp = int.from_bytes(ob.data[offs["data"][0]: offs["data"][0] + 8], "little")
        me = byp.get(dp)
        if me is None:
            continue
        info = parse_mesh(me, dna, byp, end, ptr_size, verbose=False)
        if info:
            return info
    raise RuntimeError("object %s not found in %s" % (obj_name, blend_path))


def normalize(info, target_size, by_height=True):
    vs = info["verts"]
    xs = [v[0] for v in vs]; ys = [v[1] for v in vs]; zs = [v[2] for v in vs]
    minx, maxx = min(xs), max(xs)
    miny, maxy = min(ys), max(ys)
    minz, maxz = min(zs), max(zs)
    if by_height:
        cur = max(maxy - miny, 1e-6)
    else:
        cur = max(max(maxx - minx, maxy - miny), maxz - minz, 1e-6)
    s = target_size / cur
    cx, cz = (minx + maxx) / 2, (minz + maxz) / 2
    info["verts"] = [((x - cx) * s, (y - miny) * s, (z - cz) * s) for x, y, z in vs]
    return info


def split_tree(info):
    """Split full-tree mesh into bark / leaves faces by height+radius heuristic."""
    vs = info["verts"]
    ys = [v[1] for v in vs]
    h = max(ys) - min(ys) or 1.0
    xs = [v[0] for v in vs]; zs = [v[2] for v in vs]
    ax, az = (min(xs) + max(xs)) / 2, (min(zs) + max(zs)) / 2
    bark_tris, leaf_tris = [], []
    for (a, b, c, lps, mn) in info["tris"]:
        cx = (vs[a][0] + vs[b][0] + vs[c][0]) / 3
        cy = (vs[a][1] + vs[b][1] + vs[c][1]) / 3
        cz = (vs[a][2] + vs[b][2] + vs[c][2]) / 3
        r = ((cx - ax) ** 2 + (cz - az) ** 2) ** 0.5
        yn = (cy - min(ys)) / h
        if (yn > 0.30 and r > 0.12 * h) or (r > 0.22 * h) or (yn > 0.62):
            leaf_tris.append((a, b, c, lps, mn))
        else:
            bark_tris.append((a, b, c, lps, mn))
    return bark_tris, leaf_tris


def convert_texture(src_path, out_path, alpha_path=None, size=512):
    im = Image.open(src_path).convert("RGB").resize((size, size), Image.LANCZOS)
    if alpha_path:
        alpha = Image.open(alpha_path).convert("L").resize((size, size), Image.LANCZOS)
        im = im.convert("RGBA")
        im.putalpha(alpha)
    im.save(out_path)


def main():
    only = set(sys.argv[1:])
    for model_id, (folder, target, texes) in PACKS.items():
        if only and model_id not in only:
            continue
        blend_path = glob.glob(os.path.join(ROOT, "models", folder, "*.blend"))[0]
        obj_name = OBJECT_PICK[model_id]
        out_dir = os.path.join(OUT, model_id)
        os.makedirs(out_dir, exist_ok=True)

        if model_id in ("tree_bark", "tree_leaves"):
            # normalize once from the full mesh so both halves match
            if model_id == "tree_bark":
                tmp = load_mesh(blend_path, obj_name)
                normalize(tmp, target, by_height=True)
                bark_tris, leaf_tris = split_tree(tmp)
                import pickle
                with open("/tmp/tree_split.pkl", "wb") as f:
                    pickle.dump({"bark": bark_tris, "leaf": leaf_tris, "info": tmp}, f)
            import pickle
            with open("/tmp/tree_split.pkl", "rb") as f:
                stash = pickle.load(f)
            info = dict(stash["info"])
            info["tris"] = stash["bark"] if model_id == "tree_bark" else stash["leaf"]
        else:
            info = load_mesh(blend_path, obj_name)
            normalize(info, target, by_height=(model_id != "fire_pit"))

        obj_path = os.path.join(out_dir, "mesh.obj")
        write_obj(obj_path, info, {0: model_id})
        for i, (tex, alpha) in enumerate(texes):
            src = os.path.join(ROOT, "models", folder, "textures", tex)
            a_src = os.path.join(ROOT, "models", folder, "textures", alpha) if alpha else None
            out_tex = os.path.join(out_dir, "diff.png" if i == 0 else "diff%d.png" % i)
            convert_texture(src, out_tex, a_src)
        tris = len(info["tris"])
        verts = len(info["verts"])
        print("%-16s obj v=%-7d t=%-7d  %s" % (model_id, verts, tris, obj_path))


if __name__ == "__main__":
    main()
