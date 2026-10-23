#!/usr/bin/env python3
"""Bake the Snowy Night Scene landscape into domain/world/ImportedTerrain.kt.

The scene's Landscape mesh is a regular 128x128 grid over ±2.65 units; we map
it onto the game world (±48 m) and scale relief to game-pleasant heights.
Output: a pure-Kotlin height grid + bilinear sampler for WorldPopulator.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from blend2obj import parse_file, parse_mesh, field_offsets, block_type
import struct

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GRID = 128
HALF = 48.0  # game world half-extent (meters)
HEIGHT_SCALE = 9.0
BASE_OFFSET = 0.18


def extract_landscape():
    path = os.path.join(ROOT, "models/Snowy Night Scene.blend")
    buf, blocks, dna, end, ptr_size = parse_file(path)
    byp = {b.old_ptr: b for b in blocks if b.old_ptr}
    id_o = field_offsets(dna, "ID")["name"][0]
    offs = field_offsets(dna, "Object")
    for ob in blocks:
        if ob.code[:2] != b"OB":
            continue
        nm = ob.data[id_o: id_o + 66].split(b"\x00")[0].decode(errors="replace")[2:]
        if nm != "Landscape":
            continue
        dp = struct.unpack_from(end + "Q", ob.data, offs["data"][0])[0]
        me = byp.get(dp)
        if me is None or block_type(dna, me) != "Mesh":
            continue
        return parse_mesh(me, dna, byp, end, ptr_size, verbose=False)
    raise RuntimeError("Landscape not found")


def main():
    info = extract_landscape()
    verts = info["verts"]
    assert len(verts) == GRID * GRID, "unexpected vert count %d" % len(verts)

    # verify row-major layout: walks of constant x along y
    x0, y0 = verts[0][0], verts[0][1]
    step = verts[1][1] - verts[0][1]
    assert abs(verts[GRID - 1][0] - x0) < 1e-4, "layout: x not constant along walk"
    ok = all(abs(verts[i][0] - x0) < 1e-4 for i in range(GRID))
    assert ok, "layout mismatch"

    xs = [v[0] for v in verts]
    ys = [v[1] for v in verts]
    minx, maxx = min(xs), max(xs)
    miny, maxy = min(ys), max(ys)

    heights = [0.0] * (GRID * GRID)
    for i, (x, y, z) in enumerate(verts):
        heights[i] = z * HEIGHT_SCALE + BASE_OFFSET

    # flatten the base camp area (structures need flat ground at origin)
    center = heights[(GRID // 2) * GRID + GRID // 2]
    for iy in range(GRID):
        for ix in range(GRID):
            gx = minx + (maxx - minx) * ix / (GRID - 1)
            gy = miny + (maxy - miny) * iy / (GRID - 1)
            # game-space radius of this sample
            wx = gx / (maxx - minx) * (2 * HALF)
            wy = gy / (maxy - miny) * (2 * HALF)
            r = (wx * wx + wy * wy) ** 0.5
            if r < 12.0:
                t = max(0.0, min(1.0, (r - 5.0) / 7.0))
                t = t * t * (3 - 2 * t)  # smoothstep
                idx = iy * GRID + ix
                heights[idx] = center * (1 - t) + heights[idx] * t

    # stats
    hs = sorted(heights)
    print("height range: %.2f .. %.2f (p50 %.2f p90 %.2f)" % (hs[0], hs[-1], hs[len(hs)//2], hs[9*len(hs)//10]))

    out = []
    out.append("package com.ayaks.winterforest.domain.world")
    out.append("")
    out.append("/**")
    out.append(" * Высоты ландшафта «Snowy Night Scene» (импорт из models/), запечённые для")
    out.append(" * локации BASE_CLEARING. Сетка %d×%d на мир ±%.0f м; высоты в метрах." % (GRID, GRID, HALF))
    out.append(" * Порядок: HEIGHTS[iy * GRID + ix], ix — вдоль +X, iy — вдоль +Z.")
    out.append(" * Сгенерировано tools/bake_landscape.py — НЕ редактировать вручную.")
    out.append(" */")
    out.append("object ImportedTerrain {")
    out.append("")
    out.append("    const val GRID: Int = %d" % GRID)
    out.append("    const val HALF_EXTENT: Float = %.1ff" % HALF)
    out.append("")
    # encode heights as centimeters in comma strings (JVM method-size safe)
    cells = ["%d" % round(heights[i] * 100) for i in range(GRID * GRID)]
    chunk = 8192
    n_chunks = (len(cells) + chunk - 1) // chunk
    for ci in range(n_chunks):
        out.append('    private const val RAW_%d: String = "%s"' % (ci, ",".join(cells[ci * chunk:(ci + 1) * chunk])))
    out.append("")
    out.append("    val HEIGHTS: FloatArray by lazy {")
    parts = ' + "," + '.join('RAW_%d' % ci for ci in range(n_chunks))
    out.append("        val cells = (%s).split(',')" % parts)
    out.append("        FloatArray(cells.size) { cells[it].toInt() / 100f }")
    out.append("    }")
    out.append("")
    out.append("    /** Билинейная выборка высоты в мировых координатах. */")
    out.append("    fun heightAt(x: Float, z: Float): Float {")
    out.append("        val gx = ((x + HALF_EXTENT) / (2f * HALF_EXTENT) * (GRID - 1)).coerceIn(0f, (GRID - 1).toFloat())")
    out.append("        val gz = ((z + HALF_EXTENT) / (2f * HALF_EXTENT) * (GRID - 1)).coerceIn(0f, (GRID - 1).toFloat())")
    out.append("        val ix = gx.toInt()")
    out.append("        val iz = gz.toInt()")
    out.append("        val ix1 = minOf(ix + 1, GRID - 1)")
    out.append("        val iz1 = minOf(iz + 1, GRID - 1)")
    out.append("        val fx = gx - ix")
    out.append("        val fz = gz - iz")
    out.append("        val h00 = HEIGHTS[iz * GRID + ix]")
    out.append("        val h10 = HEIGHTS[iz * GRID + ix1]")
    out.append("        val h01 = HEIGHTS[iz1 * GRID + ix]")
    out.append("        val h11 = HEIGHTS[iz1 * GRID + ix1]")
    out.append("        val top = h00 * (1f - fx) + h10 * fx")
    out.append("        val bot = h01 * (1f - fx) + h11 * fx")
    out.append("        return top * (1f - fz) + bot * fz")
    out.append("    }")
    out.append("}")

    path = os.path.join(ROOT, "app-winterforest/src/main/kotlin/com/ayaks/winterforest/domain/world/ImportedTerrain.kt")
    with open(path, "w") as f:
        f.write("\n".join(out) + "\n")
    print("wrote", path, "(%d floats)" % len(heights))


if __name__ == "__main__":
    main()
