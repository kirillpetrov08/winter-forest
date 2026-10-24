#!/usr/bin/env python3
"""Extract triangle meshes from Poly Haven .blend packs (zstd/gzip compressed,
Blender 2.9x-4.x) into Wavefront OBJ + copied albedo/alpha textures.

Uses the self-describing SDNA in the .blend file to resolve struct layouts,
so it is version-agnostic. No Blender needed.
"""
import gzip
import io
import os
import struct
import sys
import zlib

try:
    import zstandard
except ImportError:
    zstandard = None


def decompress_blend(path):
    with open(path, "rb") as f:
        raw = f.read()
    if raw[:4] == b"\x28\xb5\x2f\xfd":  # zstd (may be multi-frame)
        if zstandard is None:
            raise RuntimeError("zstandard required for " + path)
        out = bytearray()
        with zstandard.ZstdDecompressor().stream_reader(io.BytesIO(raw)) as r:
            while True:
                chunk = r.read(1 << 22)
                if not chunk:
                    break
                out += chunk
        return bytes(out)
    if raw[:2] == b"\x1f\x8b":  # gzip
        return gzip.decompress(raw)
    return raw


class Block:
    __slots__ = ("code", "size", "old_ptr", "dna_idx", "count", "data", "data_off")

    def __init__(self, code, size, old_ptr, dna_idx, count, data, data_off):
        self.code, self.size, self.old_ptr = code, size, old_ptr
        self.dna_idx, self.count, self.data, self.data_off = dna_idx, count, data, data_off


class Dna:
    def __init__(self, names, types, tlens, structs):
        self.names, self.types, self.tlens, self.structs = names, types, tlens, structs
        self.struct_by_type = {}
        for s in structs:
            self.struct_by_type[s["type"]] = s

    def field(self, struct_name, field_name):
        s = self.struct_by_type.get(struct_name)
        if s is None:
            return None
        for f in s["fields"]:
            if f["name"] == field_name:
                return f
        return None


def _align(off, align):
    return (off + align - 1) & ~(align - 1) if align > 1 else off


def field_offsets(dna, struct_name):
    """Compute byte offsets of each field (Blender DNA natural alignment)."""
    s = dna.struct_by_type.get(struct_name)
    if s is None:
        return {}
    out = {}
    off = 0
    for f in s["fields"]:
        base_t = dna.types[f["type_idx"]]
        name = dna.names[f["name_idx"]]
        # pointer declarations align to ptr size
        is_ptr = name.startswith("*") or name.startswith("**") or "*[" in name
        al = 8 if is_ptr else 1
        # scalar type sizes: use TLEN for structs / everything; align by min(size, 8)
        tlen = dna.tlens[f["type_idx"]]
        if not is_ptr:
            if base_t in ("char", "uchar"):
                al = 1
            elif base_t in ("short", "ushort"):
                al = 2
            elif base_t in ("int", "float", "uint", "eCustomDataType", "int32_t", "uint32_t", "eSubdivUVSmooth", "eBevelOffsetType"):
                al = 4
            elif base_t in ("double", "int64_t", "uint64_t", "ulong"):
                al = 8
            else:
                al = 8 if tlen >= 8 else (4 if tlen >= 4 else (2 if tlen >= 2 else 1))
        off = _align(off, al)
        clean = name.lstrip("*").split("[")[0]
        out[name] = (off, f)
        out.setdefault(clean, (off, f))
        # arrays: TLEN for structs / everything covers full field width? NO —
        # TLEN is per-element size. Field width = element size * array count.
        off += field_width(dna, f)
    return out


def array_count(name):
    """Return total element count from declaration like [3], [2][4], [*] -> 1"""
    import re
    dims = re.findall(r"\[(\d+)\]", name)
    n = 1
    for d in dims:
        n *= int(d)
    return n


def field_width(dna, f):
    name = dna.names[f["name_idx"]]
    tlen = dna.tlens[f["type_idx"]]
    if name.startswith("*") or "*[" in name:
        return 8 * array_count(name)  # 64-bit files only (we handle 32 too)
    return tlen * array_count(name)


_PTR_LISTS_BY_DNA = {}


def _register_ptr_lists(dna, blocks):
    lists = {}
    for b in blocks:
        if b.old_ptr:
            lists.setdefault(b.old_ptr, []).append(b)
    _PTR_LISTS_BY_DNA[id(dna)] = lists


def ptr_candidates(dna, ptr):
    return _PTR_LISTS_BY_DNA.get(id(dna), {}).get(ptr, [])


def parse_file(path):
    buf = decompress_blend(path)
    hdr = buf[:12]
    if not hdr.startswith(b"BLENDER"):
        raise RuntimeError("not a .blend: " + path)

    # Blender 5.x "BLENDER17-01" format: 17-byte header (magic12 + endian +
    # 4-digit version) and 32-byte block heads:
    #   code(4) sdna_idx(u32) old_ptr(u64) size(u64) count(u64).
    if hdr.startswith(b"BLENDER17-01"):
        endian_char = chr(buf[12])
        version = int(buf[13:17])
        ptr_size = 8
        little = endian_char == "v"
        end = "<" if little else ">"
        blocks = []
        pos_try = 17
        while pos_try + 32 <= len(buf):
            code = buf[pos_try:pos_try + 4]
            dna_idx = struct.unpack_from(end + "I", buf, pos_try + 4)[0]
            old_ptr = struct.unpack_from(end + "Q", buf, pos_try + 8)[0]
            size, count = struct.unpack_from(end + "QQ", buf, pos_try + 16)
            data_off = pos_try + 32
            if data_off + size > len(buf):
                blocks = None
                break
            data = buf[data_off:data_off + size]
            blocks.append(Block(code, size, old_ptr, dna_idx, count, data, data_off))
            pos_try += 32 + size
            if code == b"ENDB":
                break
        if not blocks or blocks[-1].code != b"ENDB" or not any(
                b.code == b"DNA1" for b in blocks):
            raise RuntimeError("cannot locate 5.x block structure in " + path)
        dna_block = next((b for b in blocks if b.code == b"DNA1"), None)
        dna = parse_dna(dna_block.data, end, ptr_size)
        _register_ptr_lists(dna, blocks)
        return buf, blocks, dna, end, ptr_size

    ptr_char = chr(hdr[7])
    endian_char = chr(hdr[8])
    version = int(hdr[9:12])
    ptr_size = 8 if ptr_char == "_" else 4
    little = endian_char == "v"
    end = "<" if little else ">"

    pos = 12
    blocks = None
    # header size auto-detect: Blender <=2.9 wrote 20-byte heads for 4-byte
    # pointers, but 3.x/4.x packs can use 24-byte heads regardless of the
    # pointer-size flag in the magic. Try both, keep the walk that fits.
    for hdr_len, fmt in ((24, "QII"), (20, "III")):
        if hdr_len - 12 != ptr_size and hdr_len == 24 and ptr_size == 4:
            pass  # empirically still try: some 4.0 packs use 24B heads
        pos_try = 12
        blocks_try = []
        ok = True
        try:
            while pos_try + hdr_len <= len(buf):
                code = buf[pos_try:pos_try + 4]
                size = struct.unpack_from(end + "I", buf, pos_try + 4)[0]
                old_ptr, dna_idx, count = struct.unpack_from(end + fmt, buf, pos_try + 8)
                data_off = pos_try + hdr_len
                if data_off + size > len(buf):
                    ok = False
                    break
                data = buf[data_off:data_off + size]
                blocks_try.append(Block(code, size, old_ptr, dna_idx, count, data, data_off))
                pos_try += hdr_len + size
                if code == b"ENDB":
                    break
        except struct.error:
            ok = False
        if ok and blocks_try and blocks_try[-1].code == b"ENDB" and any(
                b.code == b"DNA1" for b in blocks_try):
            blocks = blocks_try
            pos = pos_try
            break
    if blocks is None:
        raise RuntimeError("cannot locate block structure in " + path)
    # struct pointer fields use the same width as block headers
    ptr_size = 8 if hdr_len == 24 else 4

    dna_block = next((b for b in blocks if b.code == b"DNA1"), None)
    dna = parse_dna(dna_block.data, end, ptr_size)
    _register_ptr_lists(dna, blocks)
    return buf, blocks, dna, end, ptr_size


def parse_dna(data, end, ptr_size):
    pos = 0
    assert data[0:4] == b"SDNA"
    pos = 4
    assert data[pos:pos + 4] == b"NAME"; pos += 4
    n_names = struct.unpack_from(end + "I", data, pos)[0]; pos += 4
    names = []
    for _ in range(n_names):
        e = data.index(b"\x00", pos)
        names.append(data[pos:e].decode()); pos = e + 1
    pos = _align(pos, 4)
    assert data[pos:pos + 4] == b"TYPE"; pos += 4
    n_types = struct.unpack_from(end + "I", data, pos)[0]; pos += 4
    types = []
    for _ in range(n_types):
        e = data.index(b"\x00", pos)
        types.append(data[pos:e].decode()); pos = e + 1
    pos = _align(pos, 4)
    assert data[pos:pos + 4] == b"TLEN"; pos += 4
    tlens = struct.unpack_from(end + "%dH" % n_types, data, pos); pos += 2 * n_types
    pos = _align(pos, 4)
    assert data[pos:pos + 4] == b"STRC"; pos += 4
    n_strc = struct.unpack_from(end + "I", data, pos)[0]; pos += 4
    structs = []
    for _ in range(n_strc):
        t_idx, n_fields = struct.unpack_from(end + "HH", data, pos); pos += 4
        fields = []
        for _ in range(n_fields):
            ft, fn = struct.unpack_from(end + "HH", data, pos); pos += 4
            fields.append({"type_idx": ft, "name_idx": fn})
        structs.append({"type": types[t_idx], "fields": fields})
    return Dna(names, types, tlens, structs)


def block_type(dna, blk):
    if 0 <= blk.dna_idx < len(dna.structs):
        return dna.structs[blk.dna_idx]["type"]
    return "?"


def u32(d, end):
    return struct.unpack_from(end + "I", d, 0)[0]


def i32(d, end, off=0):
    return struct.unpack_from(end + "i", d, off)[0]


def extract_model(path, out_dir, name, verbose=False):
    buf, blocks, dna, end, ptr_size = parse_file(path)
    blocks_by_ptr = {b.old_ptr: b for b in blocks if b.old_ptr}

    if verbose:
        print("version-file:", len(buf), "blocks:", len(blocks))
        ms = dna.struct_by_type.get("Mesh")
        if ms:
            offs = field_offsets(dna, "Mesh")
            print("Mesh fields:", sorted(offs.keys())[:40])

    meshes = [b for b in blocks if b.code == b"ME\0\0" or b.code == b"ME"]
    if not meshes:
        # some writers use 'ME\0'; code is 4 raw bytes
        meshes = [b for b in blocks if b.code[:2] == b"ME" and b.code[2:3] in (b"\0", b" ")]
    results = []
    mat_names_all = []
    for mb in meshes:
        info = parse_mesh(mb, dna, blocks_by_ptr, end, ptr_size, verbose)
        if info and info["totloop"] > 0:
            info["mat_names"] = material_names(mb, dna, blocks_by_ptr, end, ptr_size)
            results.append(info)
    if not results:
        raise RuntimeError("no meshes found in " + path)

    os.makedirs(out_dir, exist_ok=True)
    written = []
    for idx, info in enumerate(results):
        obj_name = name if len(results) == 1 else "%s_%d" % (name, idx)
        obj_path = os.path.join(out_dir, obj_name + ".obj")
        write_obj(obj_path, info, info.get("mat_names"))
        written.append(obj_path)
    return written, results


def extract_objects(path, out_dir, name, y_up=True, verbose=False):
    """Export every OB (object) with its obmat baked into vertices."""
    buf, blocks, dna, end, ptr_size = parse_file(path)
    blocks_by_ptr = {b.old_ptr: b for b in blocks if b.old_ptr}
    offs = field_offsets(dna, "Object")
    id_o = field_offsets(dna, "ID")
    name_off = id_o["name"][0]

    def rp(d, o):
        return struct.unpack_from(end + ("Q" if ptr_size == 8 else "I"), d, o)[0]

    os.makedirs(out_dir, exist_ok=True)
    out = []
    for ob in blocks:
        if ob.code[:2] != b"OB":
            continue
        ob_name = ob.data[name_off: name_off + 66].split(b"\x00")[0].decode(errors="replace")
        if ob_name.startswith("OB"):
            ob_name = ob_name[2:]
        data_ptr = rp(ob.data, offs["data"][0]) if "data" in offs else 0
        me_blk = blocks_by_ptr.get(data_ptr)
        if me_blk is None or block_type(dna, me_blk) != "Mesh":
            continue
        info = parse_mesh(me_blk, dna, blocks_by_ptr, end, ptr_size, verbose)
        if not info or not info["tris"]:
            continue
        # obmat 4x4 (row-major, translation in row 3)
        if "obmat" not in offs:
            continue
        mat_off = offs["obmat"][0]
        m = [struct.unpack_from(end + "16f", ob.data, mat_off)[i:i + 4] for i in range(0, 16, 4)]

        def xform(v):
            x, y, z = v
            gx = x * m[0][0] + y * m[1][0] + z * m[2][0] + m[3][0]
            gy = x * m[0][1] + y * m[1][1] + z * m[2][1] + m[3][1]
            gz = x * m[0][2] + y * m[1][2] + z * m[2][2] + m[3][2]
            if y_up:
                return (gx, gz, -gy)  # blender z-up -> engine y-up
            return (gx, gy, gz)

        info["verts"] = [xform(v) for v in info["verts"]]
        safe = "".join(c if c.isalnum() or c in "_-" else "_" for c in ob_name)
        obj_path = os.path.join(out_dir, "%s__%s.obj" % (name, safe))
        write_obj(obj_path, info, info.get("mat_names"))
        out.append({"name": ob_name, "path": obj_path, "tris": len(info["tris"]),
                    "verts": info["totvert"], "mats": info.get("mat_names", {})})
    return out


def material_names(mb, dna, blocks_by_ptr, end, ptr_size):
    """{slot_index: Material ID name} from Mesh.mat array."""
    offs = field_offsets(dna, "Mesh")
    if "mat" not in offs:
        return {}
    d = mb.data
    mat_ptr = struct.unpack_from(end + ("Q" if ptr_size == 8 else "I"), d, offs["mat"][0])[0]
    arr = blocks_by_ptr.get(mat_ptr)
    if not arr:
        return {}
    id_o = field_offsets(dna, "ID")
    if "name" not in id_o:
        return {}
    name_off = id_o["name"][0]
    out = {}
    for i in range(arr.count):
        m_ptr = struct.unpack_from(end + ("Q" if ptr_size == 8 else "I"),
                                   arr.data, i * (8 if ptr_size == 8 else 4))[0]
        mb2 = blocks_by_ptr.get(m_ptr)
        if not mb2:
            continue
        nb = mb2.data[name_off: name_off + 66].split(b"\x00")[0].decode(errors="replace")
        out[i] = nb[2:] if nb.startswith("MA") else nb
    return out


def read_attribute_storage(mb, dna, blocks_by_ptr, end, ptr_size, counts=None):
    """Blender 5.x mesh attributes (AttributeStorage) -> name -> entry dict.

    Blender 5.x writers may reuse memory addresses, so several blocks can
    share one old_ptr. Entries are resolved by size-matching against the mesh
    counts when provided: {"position": totvert*12, ".corner_vert": totloop*4,
    "UVMap": totloop*8, "material_index": totpoly*4}.

    Each entry: {"count": int, "elem": bytes_per_elem, "data": raw bytes or None,
    "single": raw bytes of the constant element or None}.
    """
    offs = field_offsets(dna, "Mesh")
    if "attribute_storage" not in offs:
        return {}
    ast = field_offsets(dna, "AttributeStorage")
    if "dna_attributes" not in ast:
        return {}
    d = mb.data
    astor_off = offs["attribute_storage"][0]

    def rp(base, off):
        return struct.unpack_from(end + ("Q" if ptr_size == 8 else "I"), base, off)[0]

    num = i32(d, end, astor_off + ast["dna_attributes_num"][0])
    arr_ptr = rp(d, astor_off + ast["dna_attributes"][0])
    if num <= 0 or num > 4096:
        return {}
    at = field_offsets(dna, "Attribute")
    t_idx = next(i for i, t in enumerate(dna.types) if t == "Attribute")
    stride = dna.tlens[t_idx]
    aa = field_offsets(dna, "AttributeArray")

    def arr_size(data_blk):
        sf = aa.get("size")
        if sf is None:
            return 0
        w = dna.tlens[sf[1]["type_idx"]]
        raw = data_blk.data[sf[0]: sf[0] + w]
        return int.from_bytes(raw, "little") if raw else 0

    def resolve_data(data_ptr, want_bytes, want_count):
        """Find AttributeArray header + inner values matching expected size."""
        for hdr in ptr_candidates(dna, data_ptr):
            if hdr.size < 24:
                continue
            inner = rp(hdr.data, aa["data"][0])
            count = arr_size(hdr)
            iso_off = aa.get("is_single")
            is_single = hdr.data[iso_off[0]] if iso_off and iso_off[0] < hdr.size else 0
            if is_single:
                single_blk = blocks_by_ptr.get(inner) or hdr
                return {"count": 1, "elem": 0, "data": None, "single": single_blk.data}
            for vblk in ptr_candidates(dna, inner):
                if want_bytes and vblk.size == want_bytes:
                    return {"count": want_count or count, "elem": (vblk.size // (want_count or count)) if (want_count or count) else 0,
                            "data": vblk.data, "single": None}
            if not want_bytes:
                vblk = blocks_by_ptr.get(inner)
                if vblk is not None:
                    n = want_count or count
                    return {"count": n, "elem": (vblk.size // n) if n else 0, "data": vblk.data, "single": None}
        return None

    counts = counts or {}
    arr_candidates = [b for b in ptr_candidates(dna, arr_ptr)
                      if b.size == num * stride] or \
                     [b for b in ptr_candidates(dna, arr_ptr) if b.size >= num * stride]
    if not arr_candidates:
        arr_blk = blocks_by_ptr.get(arr_ptr)
        arr_candidates = [arr_blk] if arr_blk is not None else []
    best = {}
    for arr_blk in arr_candidates:
        out = {}
        for i in range(num):
            b = i * stride
            if b + stride > arr_blk.size:
                break
            name_ptr = rp(arr_blk.data, b + at["name"][0])
            name_blk = blocks_by_ptr.get(name_ptr)
            if name_blk is None:
                continue
            nm = name_blk.data.split(b"\x00")[0].decode(errors="replace")
            data_ptr = rp(arr_blk.data, b + at["data"][0])
            want = counts.get(nm)
            entry = resolve_data(data_ptr, want[0] if want else 0, want[1] if want else 0)
            if entry is not None:
                out[nm] = entry
        needed = ("position", ".corner_vert")
        if all(k in out for k in needed):
            return out
        if len(out) > len(best):
            best = out
    return best


def parse_mesh_attrs5(mb, dna, blocks_by_ptr, end, ptr_size, verbose):
    """Assemble mesh arrays from Blender 5.x Mesh.attribute_storage."""
    offs = field_offsets(dna, "Mesh")
    d = mb.data

    def read_ptr(off):
        return struct.unpack_from(end + ("Q" if ptr_size == 8 else "I"), d, off)[0]

    totvert = i32(d, end, offs["totvert"][0]) if "totvert" in offs else 0
    totpoly = i32(d, end, offs["totpoly"][0]) if "totpoly" in offs else 0
    totloop = i32(d, end, offs["totloop"][0]) if "totloop" in offs else 0

    counts = {
        "position": (totvert * 12, totvert),
        ".corner_vert": (totloop * 4, totloop),
        "UVMap": (totloop * 8, totloop),
        "material_index": (totpoly * 4, totpoly),
    }
    attrs = read_attribute_storage(mb, dna, blocks_by_ptr, end, ptr_size, counts)
    if not attrs:
        return None

    def take(name, want, width):
        """-> (bytes, n) of a packed array attribute, or (None, 0)."""
        a = attrs.get(name)
        if a is None or a.get("data") is None:
            return None, 0
        n = a["count"] or want
        n = min(n, want, len(a["data"]) // width) if want else min(n, len(a["data"]) // width)
        return a["data"], n

    # ---- positions (vec3f per point) ----
    pdata, nvert = take("position", totvert, 12)
    if pdata is None or nvert <= 0:
        return None
    pstride = attrs["position"]["elem"] or 12
    verts = [struct.unpack_from(end + "3f", pdata, i * pstride) for i in range(nvert)]
    totvert = len(verts)

    # ---- corner -> vertex (int per loop) ----
    ldata, nloop = take(".corner_vert", totloop, 4)
    if ldata is None or nloop <= 0:
        return None
    lstride = attrs[".corner_vert"]["elem"] or 4
    loops = [struct.unpack_from(end + "I", ldata, i * lstride)[0] for i in range(nloop)]
    totloop = len(loops)

    # ---- uvs (vec2f per corner): "UVMap" or first non-reserved 8-byte attr ----
    uvs = [(0.0, 0.0)] * totloop
    uv_name = None
    if "UVMap" in attrs:
        uv_name = "UVMap"
    else:
        for nm, a in attrs.items():
            if not nm.startswith(".") and a.get("data") is not None and \
                    (a["elem"] or 0) == 8 and (a["count"] or 0) >= totloop:
                uv_name = nm
                break
    if uv_name:
        udata, nuv = take(uv_name, totloop, 8)
        ustride = attrs[uv_name]["elem"] or 8
        for i in range(min(nuv, totloop)):
            u, v = struct.unpack_from(end + "2f", udata, i * ustride)
            uvs[i] = (u, 1.0 - v)

    # ---- polys from poly_offset_indices + material_index ----
    polys = []
    off_blk = None
    if "poly_offset_indices" in offs:
        poi_ptr = read_ptr(offs["poly_offset_indices"][0])
        want = (totpoly + 1) * 4
        for cand in ptr_candidates(dna, poi_ptr):
            if cand.size == want:
                off_blk = cand
                break
        if off_blk is None:
            off_blk = blocks_by_ptr.get(poi_ptr)
    mats_a = attrs.get("material_index")
    mat_single = 0
    if mats_a is not None and mats_a.get("single") is not None and len(mats_a["single"]) >= 4:
        mat_single = struct.unpack_from(end + "i", mats_a["single"])[0]
    mat_arr, nmat = take("material_index", totpoly, 4) if mats_a is not None else (None, 0)
    if off_blk is not None:
        n = min(totpoly + 1, off_blk.size // 4)
        offsets = [struct.unpack_from(end + "i", off_blk.data, i * 4)[0] for i in range(n)]
        for i in range(totpoly):
            if i + 1 >= len(offsets):
                break
            ls, le = offsets[i], offsets[i + 1]
            if mat_arr is not None and i < nmat:
                mn = struct.unpack_from(end + "i", mat_arr, i * 4)[0]
            else:
                mn = mat_single
            polys.append((ls, le - ls, mn))
    else:
        for i in range(totloop // 3):
            polys.append((i * 3, 3, mat_single))
    totpoly = len(polys)

    tris = []
    for ls, tl, mn in polys:
        if tl < 3 or ls + tl > totloop:
            continue
        for k in range(1, tl - 1):
            a, b, c = loops[ls], loops[ls + k], loops[ls + k + 1]
            tris.append((a, b, c, (ls, ls + k, ls + k + 1), mn))
    return {"verts": verts, "loops": loops, "uvs": uvs, "tris": tris, "totvert": totvert,
            "totloop": totloop, "totpoly": totpoly}


def compose_matrix4(loc, rot, size, quat, rotmode):
    """Blender-5.x local object matrix in stored/row form (translation row 3).

    v' = v · M  (same convention as legacy Object.obmat). rotmode 1..6 =
    Euler orders XYZ..ZYX, 0 = quaternion, else Euler XYZ.
    """
    import math

    def mul(a, b):
        return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)]
                for i in range(4)]

    def euler_mat(order, rx, ry, rz):
        cx, sx = math.cos(rx), math.sin(rx)
        cy, sy = math.cos(ry), math.sin(ry)
        cz, sz = math.cos(rz), math.sin(rz)
        rx_m = [[1, 0, 0, 0], [0, cx, sx, 0], [0, -sx, cx, 0], [0, 0, 0, 1]]
        ry_m = [[cy, 0, -sy, 0], [0, 1, 0, 0], [sy, 0, cy, 0], [0, 0, 0, 1]]
        rz_m = [[cz, sz, 0, 0], [-sz, cz, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]
        mats = {"x": rx_m, "y": ry_m, "z": rz_m}
        out = [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]
        for ax in order.lower():  # first axis applied first -> left-multiply
            out = mul(out, mats[ax])
        return out

    if rotmode == 0:
        w, x, y, z = quat
        n = (w * w + x * x + y * y + z * z) ** 0.5
        if n > 1e-8:
            w, x, y, z = w / n, x / n, y / n, z / n
            r = [[1 - 2 * (y * y + z * z), 2 * (x * y + w * z), 2 * (x * z - w * y), 0],
                 [2 * (x * y - w * z), 1 - 2 * (x * x + z * z), 2 * (y * z + w * x), 0],
                 [2 * (x * z + w * y), 2 * (y * z - w * x), 1 - 2 * (x * x + y * y), 0],
                 [0, 0, 0, 1]]
        else:
            r = euler_mat("xyz", *rot)
    else:
        order = {1: "xyz", 2: "xzy", 3: "yxz", 4: "yzx", 5: "zxy", 6: "zyx"}.get(rotmode, "xyz")
        r = euler_mat(order, *rot)

    sx, sy, sz = size
    m = [[r[0][0] * sx, r[0][1] * sx, r[0][2] * sx, 0],
         [r[1][0] * sy, r[1][1] * sy, r[1][2] * sy, 0],
         [r[2][0] * sz, r[2][1] * sz, r[2][2] * sz, 0],
         [loc[0], loc[1], loc[2], 1]]
    return m


def object_world_matrices(blocks, dna, end, ptr_size):
    """{block_id(ob): world matrix rows} for every Object in a 5.x file.

    Handles parent chains: world = local · parentinv · parent_world.
    Falls back to identity-ish local when the legacy obmat field exists.
    """
    offs = field_offsets(dna, "Object")

    def rd(data, off, fmt, n):
        return struct.unpack_from(end + fmt, data, off)[:n]

    local = {}
    parent_of = {}
    parentinv_of = {}
    for ob in blocks:
        if ob.code[:2] != b"OB":
            continue
        if "obmat" in offs:
            rows = [list(rd(ob.data, offs["obmat"][0] + i * 16, "4f", 4)) for i in range(4)]
        else:
            loc = rd(ob.data, offs["loc"][0], "3f", 3)
            rot = rd(ob.data, offs["rot"][0], "3f", 3)
            size = rd(ob.data, offs["size"][0], "3f", 3)
            quat = rd(ob.data, offs["quat"][0], "4f", 4)
            rotmode = rd(ob.data, offs["rotmode"][0], "h", 1)[0]
            rows = compose_matrix4(loc, rot, size, quat, rotmode)
        local[id(ob)] = rows
        p = rd(ob.data, offs["parent"][0], "Q" if ptr_size == 8 else "I", 1)[0]
        parent_of[id(ob)] = p
        if "parentinv" in offs:
            parentinv_of[id(ob)] = [list(rd(ob.data, offs["parentinv"][0] + i * 16, "4f", 4))
                                    for i in range(4)]
        else:
            parentinv_of[id(ob)] = [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]

    by_ptr = {b.old_ptr: b for b in blocks if b.old_ptr}

    def mul(a, b):
        return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)]
                for i in range(4)]

    memo = {}

    def world(ob_id, stack=()):
        if ob_id in memo:
            return memo[ob_id]
        if ob_id in stack:
            return local[ob_id]
        m = local[ob_id]
        p = parent_of.get(ob_id, 0)
        if p:
            pob = by_ptr.get(p)
            if pob is not None and id(pob) in local:
                m = mul(mul(m, parentinv_of[ob_id]), world(id(pob), stack + (ob_id,)))
        memo[ob_id] = m
        return m

    return {oid: world(oid) for oid in local}


def parse_mesh(mb, dna, blocks_by_ptr, end, ptr_size, verbose):
    offs = field_offsets(dna, "Mesh")
    d = mb.data

    def read_ptr(off):
        return struct.unpack_from(end + ("Q" if ptr_size == 8 else "I"), d, off)[0]

    layers = {}  # fld -> list[(name, type_code, data_block)]
    for fld in ("vdata", "edata", "fdata", "pdata", "ldata"):
        if fld not in offs:
            continue
        base = offs[fld][0]
        cdo = field_offsets(dna, "CustomData")
        totlayer = i32(d, end, base + cdo["totlayer"][0])
        lay_blk = blocks_by_ptr.get(read_ptr(base + cdo["layers"][0]))
        if not lay_blk or totlayer <= 0 or totlayer > 500:
            continue
        cdl_o = field_offsets(dna, "CustomDataLayer")
        t_idx = next(i for i, t in enumerate(dna.types) if t == "CustomDataLayer")
        stride = dna.tlens[t_idx]
        entries = []
        for i in range(totlayer):
            b = i * stride
            typ = i32(lay_blk.data, end, b + cdl_o["type"][0])
            dp = struct.unpack_from(end + ("Q" if ptr_size == 8 else "I"),
                                    lay_blk.data, b + cdl_o["data"][0])[0]
            nm = lay_blk.data[b + cdl_o["name"][0]: b + cdl_o["name"][0] + 68] \
                .split(b"\x00")[0].decode(errors="replace")
            blk = blocks_by_ptr.get(dp)
            if blk is not None:
                entries.append((nm, typ, blk))
        layers[fld] = entries

    def legacy(fld, tname):
        if fld in offs:
            p = read_ptr(offs[fld][0])
            blk = blocks_by_ptr.get(p)
            if blk is not None and block_type(dna, blk) == tname:
                return blk
        return None

    def find_layer(fld, names, legacy_type):
        """Named generic layer (4.x) or legacy typed layer (2.7x)."""
        for nm, typ, blk in layers.get(fld, []):
            if nm in names and legacy_type is None:
                return blk
            if nm in names:
                return blk
        for nm, typ, blk in layers.get(fld, []):
            if legacy_type and block_type(dna, blk) == legacy_type:
                return blk
        return None

    mv = find_layer("vdata", ("position",), "MVert") or legacy("mvert", "MVert")
    ml = find_layer("ldata", (".corner_vert",), "MLoop") or legacy("mloop", "MLoop")
    mu = find_layer("ldata", ("UVMap",), "MLoopUV") or legacy("mloopuv", "MLoopUV")
    mp_legacy = legacy("mpoly", "MPoly") or find_layer("pdata", (), "MPoly")
    mat_layer = find_layer("pdata", ("material_index",), None)

    if mv is None or ml is None:
        # Blender 5.x: geometry lives in Mesh.attribute_storage
        result = parse_mesh_attrs5(mb, dna, blocks_by_ptr, end, ptr_size, verbose)
        if result is not None:
            return result
        if verbose:
            print("layers found:", {k: block_type(dna, v[1]) for k, v in layers.items()})
        return None

    # ---- counts ----
    totvert = i32(d, end, offs["totvert"][0]) if "totvert" in offs else mv.count
    totloop = i32(d, end, offs["totloop"][0]) if "totloop" in offs else ml.count
    totpoly = i32(d, end, offs["totpoly"][0]) if "totpoly" in offs else (mp_legacy.count if mp_legacy else 0)

    # ---- positions ----
    if block_type(dna, mv) == "MVert":
        mvert_o = field_offsets(dna, "MVert")
        co_off = mvert_o["co"][0]
        t_idx = next(i for i, t in enumerate(dna.types) if t == "MVert")
        v_stride = dna.tlens[t_idx]
        verts = [struct.unpack_from(end + "3f", mv.data, i * v_stride + co_off)
                 for i in range(min(totvert, mv.count))]
    else:  # vec3f
        verts = [struct.unpack_from(end + "3f", mv.data, i * 12) for i in range(min(totvert, mv.count))]
    totvert = len(verts)

    # ---- loop -> vertex ----
    if block_type(dna, ml) == "MLoop":
        mloop_o = field_offsets(dna, "MLoop")
        v_off = mloop_o["v"][0]
        t_idx = next(i for i, t in enumerate(dna.types) if t == "MLoop")
        l_stride = dna.tlens[t_idx]
        loops = [struct.unpack_from(end + "I", ml.data, i * l_stride + v_off)[0]
                 for i in range(min(totloop, ml.count))]
    else:  # MIntProperty / int array
        loops = [struct.unpack_from(end + "I", ml.data, i * 4)[0] for i in range(min(totloop, ml.count))]
    totloop = len(loops)

    # ---- polys (ranges of loops) ----
    polys = []  # (loopstart, totloop, mat_nr)
    if mp_legacy is not None:
        mpoly_o = field_offsets(dna, "MPoly")
        ls_off, tl_off = mpoly_o["loopstart"][0], mpoly_o["totloop"][0]
        mn_off = mpoly_o.get("mat_nr", (0,))[0]
        t_idx = next(i for i, t in enumerate(dna.types) if t == "MPoly")
        p_stride = dna.tlens[t_idx]
        for i in range(min(totpoly, mp_legacy.count)):
            ls, tl, mn = struct.unpack_from(end + "iih", mp_legacy.data, i * p_stride + ls_off)
            polys.append((ls, tl, mn))
    else:
        off_blk = None
        if "poly_offset_indices" in offs:
            p = read_ptr(offs["poly_offset_indices"][0])
            off_blk = blocks_by_ptr.get(p)
        mats = mat_layer
        mat_arr = mats.data if mats is not None else None
        if off_blk is not None:
            n = min(totpoly + 1, off_blk.size // 4)
            offsets = [struct.unpack_from(end + "i", off_blk.data, i * 4)[0] for i in range(n)]
            for i in range(totpoly):
                if i + 1 >= len(offsets):
                    break
                ls, le = offsets[i], offsets[i + 1]
                mn = struct.unpack_from(end + "i", mat_arr, i * 4)[0] if mat_arr is not None and i * 4 + 4 <= len(mat_arr) else 0
                polys.append((ls, le - ls, mn))
        else:
            # triangle soup fallback
            for i in range(totloop // 3):
                polys.append((i * 3, 3, 0))
    totpoly = len(polys)

    # ---- uvs ----
    uvs = [(0.0, 0.0)] * totloop
    if mu is not None:
        if block_type(dna, mu) == "MLoopUV":
            mluv_o = field_offsets(dna, "MLoopUV")
            uv_off = mluv_o["uv"][0]
            t_idx = next(i for i, t in enumerate(dna.types) if t == "MLoopUV")
            u_stride = dna.tlens[t_idx]
            for i in range(min(totloop, mu.count)):
                u, v = struct.unpack_from(end + "2f", mu.data, i * u_stride + uv_off)
                uvs[i] = (u, 1.0 - v)
        else:  # vec2f
            for i in range(min(totloop, mu.count)):
                u, v = struct.unpack_from(end + "2f", mu.data, i * 8)
                uvs[i] = (u, 1.0 - v)

    tris = []
    for ls, tl, mn in polys:
        if tl < 3 or ls + tl > totloop:
            continue
        for k in range(1, tl - 1):
            a, b, c = loops[ls], loops[ls + k], loops[ls + k + 1]
            tris.append((a, b, c, (ls, ls + k, ls + k + 1), mn))
    return {"verts": verts, "loops": loops, "uvs": uvs, "tris": tris, "totvert": totvert,
            "totloop": totloop, "totpoly": totpoly}


def write_obj(path, info, mat_names=None):
    verts, uvs, tris = info["verts"], info["uvs"], info["tris"]
    mat_names = mat_names or {}
    with open(path, "w") as f:
        f.write("# extracted by tools/blend2obj.py\n")
        for x, y, z in verts:
            f.write("v %.5f %.5f %.5f\n" % (x, y, z))
        for u, v in uvs:
            f.write("vt %.5f %.5f\n" % (u, v))
        # smooth normals accumulate
        nrm = [[0.0, 0.0, 0.0] for _ in verts]
        for a, b, c, _l, _mn in tris:
            ax, ay, az = verts[a]; bx, by, bz = verts[b]; cx, cy, cz = verts[c]
            ux, uy, uz = bx - ax, by - ay, bz - az
            vx, vy, vz = cx - ax, cy - ay, cz - az
            nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx
            for i in (a, b, c):
                nrm[i][0] += nx; nrm[i][1] += ny; nrm[i][2] += nz
        for nx, ny, nz in nrm:
            l = (nx * nx + ny * ny + nz * nz) ** 0.5 or 1.0
            f.write("vn %.5f %.5f %.5f\n" % (nx / l, ny / l, nz / l))
        last_mn = None
        for a, b, c, (la, lb, lc), mn in tris:
            if mn != last_mn:
                f.write("usemtl %s\n" % mat_names.get(mn, "mat%d" % mn))
                last_mn = mn
            f.write("f %d/%d/%d %d/%d/%d %d/%d/%d\n" %
                    (a + 1, la + 1, a + 1, b + 1, lb + 1, b + 1, c + 1, lc + 1, c + 1))


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "--objects":
        path = sys.argv[2]
        out_dir = sys.argv[3] if len(sys.argv) > 3 else "out_obj"
        name = sys.argv[4] if len(sys.argv) > 4 else os.path.splitext(os.path.basename(path))[0]
        for r in extract_objects(path, out_dir, name, verbose=False):
            print("obj %-40s v=%-8d t=%-8d mats=%s" % (r["name"], r["verts"], r["tris"], r["mats"]))
        sys.exit(0)
    if len(sys.argv) < 2:
        print("usage: blend2obj.py file.blend [outdir] [name]")
        sys.exit(1)
    path = sys.argv[1]
    out_dir = sys.argv[2] if len(sys.argv) > 2 else "out_obj"
    name = sys.argv[3] if len(sys.argv) > 3 else os.path.splitext(os.path.basename(path))[0]
    written, results = extract_model(path, out_dir, name, verbose=True)
    for p in written:
        print("wrote", p)
    for info in results:
        print("  verts %d loops %d polys %d tris %d" %
              (info["totvert"], info["totloop"], info["totpoly"], len(info["tris"])))
