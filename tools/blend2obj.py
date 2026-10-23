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


def parse_file(path):
    buf = decompress_blend(path)
    hdr = buf[:12]
    if not hdr.startswith(b"BLENDER"):
        raise RuntimeError("not a .blend: " + path)
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
