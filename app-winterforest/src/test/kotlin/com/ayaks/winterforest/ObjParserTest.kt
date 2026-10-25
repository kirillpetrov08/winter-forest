package com.ayaks.winterforest

import com.ayaks.winterforest.presentation.gl.MeshBuilder
import com.ayaks.winterforest.presentation.gl.ObjParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ObjParserTest {

    @Test
    fun `simple triangle expands to three corners`() {
        val obj = """
            v 0.0 1.0 2.0
            v 1.0 0.0 0.0
            v 0.0 0.0 1.0
            vt 0.0 0.5
            vt 0.5 1.0
            vt 1.0 0.0
            vn 0.0 1.0 0.0
            f 1/1/1 2/2/1 3/3/1
        """.trimIndent()
        val f = ObjParser.parseToExpandedFloats(obj)
        assertEquals(36, f.size) // 3 угла × 12 float
        assertEquals(0.0f, f[0], 1e-6f)
        assertEquals(1.0f, f[1], 1e-6f)
        assertEquals(2.0f, f[2], 1e-6f)
        assertEquals(0.0f, f[3], 1e-6f) // normal
        assertEquals(1.0f, f[4], 1e-6f)
        assertEquals(0.5f, f[7], 1e-6f) // uv v первого угла
        assertEquals(1.0f, f[11], 1e-6f) // alpha
    }

    @Test
    fun `quad is fanned into two triangles`() {
        val obj = """
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            f 1 2 3 4
        """.trimIndent()
        val f = ObjParser.parseToExpandedFloats(obj)
        assertEquals(72, f.size) // 2 треугольника × 3 угла × 12
    }

    @Test
    fun `negative indices resolve from the end`() {
        val obj = """
            v 5 6 7
            v 1 1 1
            f -2 -1 1
        """.trimIndent()
        val f = ObjParser.parseToExpandedFloats(obj)
        assertEquals(5.0f, f[0], 1e-6f)
        assertEquals(6.0f, f[1], 1e-6f)
        assertEquals(7.0f, f[2], 1e-6f)
    }

    @Test
    fun `loadInto fills MeshBuilder with expanded vertices`() {
        val obj = """
            v 0 0 0
            v 1 0 0
            v 0 1 0
            f 1 2 3
        """.trimIndent()
        val builder = MeshBuilder(64)
        ObjParser.loadInto(builder, obj, tintR = 0.5f, tintG = 0.6f, tintB = 0.7f)
        assertEquals(3, builder.vertexCount)
        assertTrue(builder.floatSize == 36)
    }

    // ------------------------------------------------------------------
    // Совместимость с прежней (Regex-based) реализацией
    // ------------------------------------------------------------------

    /**
     * Эталон: дословный порт прежнего парсера (split(Regex) + список Float).
     * Каждый новый быстрый путь обязан выдавать ровно тот же поток вершин.
     */
    private fun reference(text: String, tintR: Float = 1f, tintG: Float = 1f, tintB: Float = 1f): FloatArray {
        val vs = ArrayList<Float>(4096)
        val vts = ArrayList<Float>(4096)
        val vns = ArrayList<Float>(4096)
        val out = ArrayList<Float>(8192)

        fun emitCorner(token: String) {
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
                    for (k in 2 until p.size - 1) {
                        emitCorner(p[1]); emitCorner(p[k]); emitCorner(p[k + 1])
                    }
                }
            }
        }
        return out.toFloatArray()
    }

    @Test
    fun `fast parser matches reference implementation on generated meshes`() {
        val rnd = Random(20261025)
        repeat(40) { iter ->
            val sb = StringBuilder("# generated\n")
            val vCount = 3 + rnd.nextInt(40)
            val tCount = 1 + rnd.nextInt(vCount)
            for (i in 0 until vCount) {
                sb.append("v ")
                sb.append(fmt(rnd.nextFloat() * 4f - 2f)).append(' ')
                sb.append(fmt(rnd.nextFloat() * 4f - 2f)).append(' ')
                sb.append(fmt(rnd.nextFloat() * 4f - 2f)).append('\n')
            }
            for (i in 0 until tCount) {
                sb.append("vt ").append(fmt(rnd.nextFloat())).append(' ').append(fmt(rnd.nextFloat())).append('\n')
            }
            for (i in 0 until vCount) {
                sb.append("vn ").append(fmt(rnd.nextFloat())).append(' ').append(fmt(rnd.nextFloat()))
                    .append(' ').append(fmt(rnd.nextFloat())).append('\n')
            }
            val faces = 1 + rnd.nextInt(30)
            for (i in 0 until faces) {
                val a = 1 + rnd.nextInt(vCount)
                val b = 1 + rnd.nextInt(vCount)
                val c = 1 + rnd.nextInt(vCount)
                val ta = 1 + rnd.nextInt(tCount)
                when (iter % 3) {
                    0 -> sb.append("f $a/$ta/$a $b/$ta/$b $c/$ta/$c\n")
                    1 -> sb.append("f $a//$a $b//$b $c//$c\n")
                    else -> sb.append("f $a $b $c\n")
                }
            }
            val text = sb.toString()
            val expected = reference(text)
            val actual = ObjParser.parseToExpandedFloats(text)
            assertEquals("iter=$iter size", expected.size, actual.size)
            for (i in expected.indices) {
                assertEquals("iter=$iter idx=$i", expected[i], actual[i], 1e-6f)
            }
        }
    }

    @Test
    fun `windows line endings and tabs are accepted`() {
        val obj = "v 0 0 0\r\nv 1 0 0\r\nv 0\t1\t0\r\nf 1 2 3\r\n"
        val f = ObjParser.parseToExpandedFloats(obj)
        assertEquals(36, f.size)
        assertEquals(1.0f, f[12], 1e-6f) // вершина 2, X
        assertEquals(1.0f, f[25], 1e-6f) // вершина 3, Y
    }

    @Test
    fun `scientific notation numbers survive`() {
        val obj = "v 1e-3 2.5E-2 -3.5e1\nv 0 0 0\nv 1 1 1\nf 1 2 3\n"
        val f = ObjParser.parseToExpandedFloats(obj)
        assertEquals(0.001f, f[0], 1e-7f)
        assertEquals(0.025f, f[1], 1e-7f)
        assertEquals(-35f, f[2], 1e-4f)
    }

    @Test
    fun `broken indices are skipped instead of crashing the renderer`() {
        val obj = """
            v 0 0 0
            v 1 0 0
            v 0 1 0
            f 1 2 3
            f 1 2 99
        """.trimIndent()
        val f = ObjParser.parseToExpandedFloats(obj)
        // вторая грань отброшена целиком — битой считается ссылка на угол
        assertEquals(36, f.size)
    }

    @Test
    fun `parser handles two hundred thousand corners quickly`() {
        val sb = StringBuilder()
        val n = 120_000
        for (i in 0 until n) {
            sb.append("v ").append(i * 0.01f).append(' ').append(i * 0.02f).append(' ').append(-i * 0.03f).append('\n')
        }
        for (i in 0 until n) {
            sb.append("f ").append(i + 1).append('/').append(i + 1).append('/').append(i + 1)
                .append(' ').append((i + 1) % n + 1).append('/').append((i + 1) % n + 1).append('/').append((i + 1) % n + 1)
                .append(' ').append((i + 2) % n + 1).append('/').append((i + 2) % n + 1).append('/').append((i + 2) % n + 1)
                .append('\n')
        }
        val text = sb.toString()
        val start = System.nanoTime()
        val f = ObjParser.parseToExpandedFloats(text)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(n * 3 * 12, f.size)
        assertTrue("разбор ${text.length} символов занял $ms мс", ms < 4000)
    }

    private fun fmt(v: Float): String {
        val scaled = kotlin.math.round(v * 100000f).toInt()
        return String.format(java.util.Locale.US, "%.5f", v).let { if (scaled == 0) "0.00000" else it }
    }
}
