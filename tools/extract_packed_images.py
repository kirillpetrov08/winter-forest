#!/usr/bin/env python3
"""Extract packed images (Image.packedfile) from .blend files."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from blend2obj import parse_file, field_offsets, block_type


def extract_packed(blend_path, out_dir):
    buf, blocks, dna, end, ptr_size = parse_file(blend_path)
    byp = {b.old_ptr: b for b in blocks if b.old_ptr}
    id_o = field_offsets(dna, "ID")["name"][0]
    try:
        im_offs = field_offsets(dna, "Image")
    except Exception:
        return []
    if "packedfile" not in im_offs:
        return []
    pf_off = im_offs["packedfile"][0]
    pf_o = field_offsets(dna, "PackedFile")
    if "data" not in pf_o or "size" not in pf_o:
        return []
    os.makedirs(out_dir, exist_ok=True)
    out = []
    for im in blocks:
        if im.code[:2] != b"IM":
            continue
        nm = im.data[id_o: id_o + 66].split(b"\x00")[0].decode(errors="replace")
        if nm.startswith("IM"):
            nm = nm[2:]
        pf_ptr = int.from_bytes(im.data[pf_off: pf_off + ptr_size], "little")
        pf = byp.get(pf_ptr)
        if pf is None:
            continue
        # PackedFile: size int, seek int, *data (or data follows as DATA block)
        size = int.from_bytes(pf.data[pf_o["size"][0]: pf_o["size"][0] + 4], "little")
        data_ptr = int.from_bytes(pf.data[pf_o["data"][0]: pf_o["data"][0] + ptr_size], "little")
        db = byp.get(data_ptr)
        payload = db.data if db is not None else pf.data[pf_o["data"][0]: pf_o["data"][0] + size]
        if not payload or size <= 0:
            continue
        safe = "".join(c if c.isalnum() or c in "._-" else "_" for c in nm)
        ext = ".jpg" if payload[:2] == b"\xff\xd8" else (".png" if payload[:4] == b"\x89PNG" else ".bin")
        if not safe.endswith(ext) and ext != ".bin":
            safe = os.path.splitext(safe)[0] + ext
        path = os.path.join(out_dir, safe)
        with open(path, "wb") as f:
            f.write(payload[:size] if len(payload) >= size else payload)
        out.append((nm, path, min(size, len(payload))))
    return out


if __name__ == "__main__":
    for p in sys.argv[1:]:
        out_dir = "/tmp/packed_" + os.path.splitext(os.path.basename(p))[0].replace(" ", "_")
        for nm, path, size in extract_packed(p, out_dir):
            print("%-40s -> %s (%d bytes)" % (nm, path, size))
