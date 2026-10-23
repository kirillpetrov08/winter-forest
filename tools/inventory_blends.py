#!/usr/bin/env python3
"""Quick inventory of .blend files: objects, vert/tri counts, packed images."""
import os
import sys
import struct

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from blend2obj import parse_file, parse_mesh, field_offsets, block_type

def inventory(blend_path):
    buf, blocks, dna, end, ptr_size = parse_file(blend_path)
    byp = {b.old_ptr: b for b in blocks if b.old_ptr}
    id_o = field_offsets(dna, "ID")["name"][0]
    offs = field_offsets(dna, "Object")
    print("== %s ==" % os.path.relpath(blend_path))
    imgs = []
    for im in blocks:
        if im.code[:2] == b"IM":
            nm = im.data[id_o: id_o + 66].split(b"\x00")[0].decode(errors="replace")
            if nm.startswith("IM"):
                nm = nm[2:]
            if nm:
                imgs.append(nm)
    if imgs:
        print("  images: %s" % ", ".join(imgs[:12]))
    for ob in blocks:
        if ob.code[:2] != b"OB":
            continue
        nm = ob.data[id_o: id_o + 66].split(b"\x00")[0].decode(errors="replace")
        if nm.startswith("OB"):
            nm = nm[2:]
        try:
            dp = struct.unpack_from(end + "Q", ob.data, offs["data"][0])[0]
        except Exception:
            continue
        me = byp.get(dp)
        if me is None:
            print("  OB %-40s (no mesh / type=%s)" % (nm, block_type(dna, me) if me else "?"))
            continue
        bt = block_type(dna, me)
        if bt != "Mesh":
            print("  OB %-40s (type=%s)" % (nm, bt))
            continue
        try:
            info = parse_mesh(me, dna, byp, end, ptr_size, verbose=False)
        except Exception as e:
            print("  OB %-40s (parse err %s)" % (nm, e))
            continue
        if not info:
            print("  OB %-40s (empty)" % nm)
            continue
        print("  OB %-40s v=%-6d t=%-6d" % (nm, len(info["verts"]), len(info["tris"])))

for p in sys.argv[1:]:
    try:
        inventory(p)
    except Exception as e:
        print("== %s == FAILED: %s" % (p, e))
