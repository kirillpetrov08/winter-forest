package com.ayaks.winterforest.presentation.gl

/**
 * Минимальный Wavefront-OBJ парсер для импортированных 3D-моделей
 * (упакованы через tools/pack_models.py в assets/models/<id>/mesh.obj).
 *
 * Формат строго треугольный: v / vt / vn / f a/b/c. Вершины раскрываются
 * в общий межвершинный формат MeshBuilder (pos3 + normal3 + uv2 + color4).
 */
object ObjParser {

    /** Чистый разбор в раскрытый массив 12 float на вершину. Для юнит-тестов и загрузки. */
    fun parseToExpandedFloats(text: String, tintR: Float = 1f, tintG: Float = 1f, tintB: Float = 1f): FloatArray {
        val vs = ArrayList<Float>(4096)
        val vts = ArrayList<Float>(4096)
        val vns = ArrayList<Float>(4096)
        val out = ArrayList<Float>(8192)

        fun emitCorner(token: String) {
            // форматы: i, i/t, i//n, i/t/n (индексы 1-based, отрицательные = с конца)
            val parts = token.split('/')
            val vi = parts[0].toInt()
            val ti = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toInt() else 0
            val ni = if (parts.size > 2 && parts[2].isNotEmpty()) parts[2].toInt() else 0
            val vBase = if (vi > 0) (vi - 1) * 3 else (vs.size / 3 + vi) * 3
            out.add(vs[vBase]); out.add(vs[vBase + 1]); out.add(vs[vBase + 2])
            if (ni != 0) {
                val nBase = if (ni > 0) (ni - 1) * 3 else (vns.size / 3 + ni) * 3
                out.add(vns[nBase]); out.add(vns[nBase + 1]); out.add(vns[nBase + 2])
            } else {
                out.add(0f); out.add(1f); out.add(0f)
            }
            if (ti != 0) {
                val tBase = if (ti > 0) (ti - 1) * 2 else (vts.size / 2 + ti) * 2
                out.add(vts[tBase]); out.add(vts[tBase + 1])
            } else {
                out.add(0f); out.add(0f)
            }
            out.add(tintR); out.add(tintG); out.add(tintB); out.add(1f)
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("v ") -> {
                    val p = line.split(Regex("\\s+"))
                    vs.add(p[1].toFloat()); vs.add(p[2].toFloat()); vs.add(p[3].toFloat())
                }
                line.startsWith("vt ") -> {
                    val p = line.split(Regex("\\s+"))
                    vts.add(p[1].toFloat()); vts.add(p[2].toFloat())
                }
                line.startsWith("vn ") -> {
                    val p = line.split(Regex("\\s+"))
                    vns.add(p[1].toFloat()); vns.add(p[2].toFloat()); vns.add(p[3].toFloat())
                }
                line.startsWith("f ") -> {
                    val p = line.split(Regex("\\s+"))
                    // треугольники; полигоны (если встретятся) — веером
                    for (k in 2 until p.size - 1) {
                        emitCorner(p[1]); emitCorner(p[k]); emitCorner(p[k + 1])
                    }
                }
            }
        }
        return out.toFloatArray()
    }

    /** Загрузка раскрытых вершин в MeshBuilder. */
    fun loadInto(builder: MeshBuilder, text: String, tintR: Float = 1f, tintG: Float = 1f, tintB: Float = 1f) {
        val f = parseToExpandedFloats(text, tintR, tintG, tintB)
        var i = 0
        while (i + 11 < f.size) {
            builder.addVertex(
                f[i], f[i + 1], f[i + 2],
                f[i + 3], f[i + 4], f[i + 5],
                f[i + 6], f[i + 7],
                f[i + 8], f[i + 9], f[i + 10], f[i + 11]
            )
            i += 12
        }
    }
}
