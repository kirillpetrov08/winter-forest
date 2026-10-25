package com.ayaks.winterforest.presentation.gl

/**
 * Минимальный Wavefront-OBJ парсер для импортированных 3D-моделей
 * (упакованы через tools/pack_models.py в assets/models/<id>/mesh.obj).
 *
 * Формат строго треугольный: v / vt / vn / f a/b/c. Вершины раскрываются
 * в общий межвершинный формат MeshBuilder (pos3 + normal3 + uv2 + color4).
 *
 * Парсер рукописный — сканирование символов и собственный разбор float, без
 * Regex и без боксинга. В ассетах ~3.3 млн строк / 72 МБ OBJ, а прежняя
 * реализация (`line.split(Regex("\\s+"))` в каждом цикле N + `ArrayList<Float>`)
 * компилировала Pattern на каждой строке и создавала сотни тысяч Float-объектов:
 * это давало секунды на старте и тяжёлый GC на слабых телефонах.
 */
object ObjParser {

    /** Чистый разбор в раскрытый массив 12 float на вершину. Для юнит-тестов и загрузки. */
    fun parseToExpandedFloats(text: String, tintR: Float = 1f, tintG: Float = 1f, tintB: Float = 1f): FloatArray {
        val p = Parser(text, tintR, tintG, tintB)
        p.run()
        return p.out.toArray()
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

    // ------------------------------------------------------------------
    // Реализация
    // ------------------------------------------------------------------

    private class Parser(
        private val text: String,
        private val tintR: Float,
        private val tintG: Float,
        private val tintB: Float
    ) {
        private val len = text.length
        val pos = FloatSink(8192)
        val uv = FloatSink(8192)
        val nrm = FloatSink(8192)
        val out = FloatSink(if (len / 12 > 1024) len / 12 else 1024)

        private var cur = 0

        private fun isSeparator(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r'

        fun run() {
            var lineStart = 0
            while (lineStart < len) {
                var lineEnd = text.indexOf('\n', lineStart)
                if (lineEnd < 0) lineEnd = len
                var i = lineStart
                while (i < lineEnd && isSeparator(text[i])) i++
                if (i + 1 < lineEnd) {
                    val c0 = text[i]
                    val c1 = text[i + 1]
                    when {
                        c0 == 'v' && c1 == ' ' -> {
                            cur = i + 2
                            val x = nextFloat(lineEnd)
                            val y = nextFloat(lineEnd)
                            val z = nextFloat(lineEnd)
                            pos.add(x); pos.add(y); pos.add(z)
                        }
                        c0 == 'v' && c1 == 't' -> {
                            cur = i + 3
                            val u = nextFloat(lineEnd)
                            val v = nextFloat(lineEnd)
                            uv.add(u); uv.add(v)
                        }
                        c0 == 'v' && c1 == 'n' -> {
                            cur = i + 3
                            val x = nextFloat(lineEnd)
                            val y = nextFloat(lineEnd)
                            val z = nextFloat(lineEnd)
                            nrm.add(x); nrm.add(y); nrm.add(z)
                        }
                        c0 == 'f' && c1 == ' ' -> {
                            cur = i + 2
                            faceLine(lineEnd)
                        }
                    }
                }
                lineStart = lineEnd + 1
            }
        }

        private fun faceLine(lineEnd: Int) {
            var count = 0
            while (true) {
                while (cur < lineEnd && isSeparator(text[cur])) cur++
                if (cur >= lineEnd) break
                var neg = false
                if (text[cur] == '-') { neg = true; cur++ }
                var vi = 0
                while (cur < lineEnd && text[cur] in '0'..'9') {
                    vi = vi * 10 + (text[cur] - '0'); cur++
                }
                if (neg) vi = -vi
                var ti = 0
                var ni = 0
                if (cur < lineEnd && text[cur] == '/') {
                    cur++
                    if (cur < lineEnd && text[cur] != '/') {
                        var negT = false
                        if (text[cur] == '-') { negT = true; cur++ }
                        while (cur < lineEnd && text[cur] in '0'..'9') {
                            ti = ti * 10 + (text[cur] - '0'); cur++
                        }
                        if (negT) ti = -ti
                    }
                    if (cur < lineEnd && text[cur] == '/') {
                        cur++
                        var negN = false
                        if (cur < lineEnd && text[cur] == '-') { negN = true; cur++ }
                        while (cur < lineEnd && text[cur] in '0'..'9') {
                            ni = ni * 10 + (text[cur] - '0'); cur++
                        }
                        if (negN) ni = -ni
                    }
                }
                if (count == cornerV.size) {
                    cornerV = cornerV.copyOf(count * 2)
                    cornerT = cornerT.copyOf(count * 2)
                    cornerN = cornerN.copyOf(count * 2)
                }
                cornerV[count] = vi
                cornerT[count] = ti
                cornerN[count] = ni
                count++
            }
            if (count >= 3) {
                // веер треугольников: f0-fk-f(k+1); треугольник с битой ссылкой
                // отбрасывается целиком, чтобы не рисовать «мусорные» грани
                for (k in 1 until count - 1) {
                    if (!valid(cornerV[0], cornerT[0], cornerN[0])) continue
                    if (!valid(cornerV[k], cornerT[k], cornerN[k])) continue
                    if (!valid(cornerV[k + 1], cornerT[k + 1], cornerN[k + 1])) continue
                    emit(cornerV[0], cornerT[0], cornerN[0])
                    emit(cornerV[k], cornerT[k], cornerN[k])
                    emit(cornerV[k + 1], cornerT[k + 1], cornerN[k + 1])
                }
            }
        }

        private var cornerV = IntArray(16)
        private var cornerT = IntArray(16)
        private var cornerN = IntArray(16)

        private fun valid(vi: Int, ti: Int, ni: Int): Boolean {
            val vCount = pos.size / 3
            val vIndex = if (vi > 0) vi - 1 else vCount + vi
            if (vIndex < 0 || vIndex >= vCount) return false
            if (ni != 0) {
                val nCount = nrm.size / 3
                val nIndex = if (ni > 0) ni - 1 else nCount + ni
                if (nIndex < 0 || nIndex >= nCount) return false
            }
            if (ti != 0) {
                val tCount = uv.size / 2
                val tIndex = if (ti > 0) ti - 1 else tCount + ti
                if (tIndex < 0 || tIndex >= tCount) return false
            }
            return true
        }

        private fun emit(vi: Int, ti: Int, ni: Int) {
            val vCount = pos.size / 3
            val vIndex = if (vi > 0) vi - 1 else vCount + vi
            if (vIndex < 0 || vIndex >= vCount) return
            val vBase = vIndex * 3
            out.add(pos[vBase]); out.add(pos[vBase + 1]); out.add(pos[vBase + 2])

            if (ni != 0) {
                val nCount = nrm.size / 3
                val nIndex = if (ni > 0) ni - 1 else nCount + ni
                if (nIndex >= 0 && nIndex < nCount) {
                    val nBase = nIndex * 3
                    out.add(nrm[nBase]); out.add(nrm[nBase + 1]); out.add(nrm[nBase + 2])
                } else {
                    out.add(0f); out.add(1f); out.add(0f)
                }
            } else {
                out.add(0f); out.add(1f); out.add(0f)
            }

            if (ti != 0) {
                val tCount = uv.size / 2
                val tIndex = if (ti > 0) ti - 1 else tCount + ti
                if (tIndex >= 0 && tIndex < tCount) {
                    val tBase = tIndex * 2
                    out.add(uv[tBase]); out.add(uv[tBase + 1])
                } else {
                    out.add(0f); out.add(0f)
                }
            } else {
                out.add(0f); out.add(0f)
            }
            out.add(tintR); out.add(tintG); out.add(tintB); out.add(1f)
        }

        /**
         * Разбор числа до [limit]. Быстрый путь — «знак + цифры + точка + цифры»
         * (ровно так пишет упаковщик); экспонента и прочая экзотика уходят
         * в стандартный [String.toFloat].
         */
        private fun nextFloat(limit: Int): Float {
            while (cur < limit && isSeparator(text[cur])) cur++
            if (cur >= limit) return 0f
            val from = cur
            var sign = 1.0
            when (text[cur]) {
                '-' -> { sign = -1.0; cur++ }
                '+' -> cur++
            }
            var intPart = 0.0
            var sawDigit = false
            while (cur < limit) {
                val c = text[cur]
                if (c in '0'..'9') {
                    intPart = intPart * 10.0 + (c - '0'); cur++; sawDigit = true
                } else {
                    break
                }
            }
            var frac = 0.0
            var scale = 1.0
            if (cur < limit && text[cur] == '.') {
                cur++
                while (cur < limit) {
                    val c = text[cur]
                    if (c in '0'..'9') {
                        frac = frac * 10.0 + (c - '0'); scale *= 10.0; cur++; sawDigit = true
                    } else {
                        break
                    }
                }
            }
            val cleanTail = cur >= limit || isSeparator(text[cur])
            if (sawDigit && cleanTail) {
                return ((intPart + frac / scale) * sign).toFloat()
            }
            // нестандартный токен — дочитываем его целиком и отдаём stdlib
            while (cur < limit && !isSeparator(text[cur])) cur++
            val token = text.substring(from, cur)
            return token.toFloatOrNull() ?: 0f
        }
    }

    /** Растущий буфер float — без боксинга и без лишних копий. */
    private class FloatSink(capacity: Int) {
        var data = FloatArray(if (capacity > 16) capacity else 16)
        var size = 0

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        operator fun get(i: Int): Float = data[i]

        fun toArray(): FloatArray = if (size == data.size) data else data.copyOf(size)
    }
}
