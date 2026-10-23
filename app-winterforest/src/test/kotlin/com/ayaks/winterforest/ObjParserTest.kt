package com.ayaks.winterforest

import com.ayaks.winterforest.presentation.gl.MeshBuilder
import com.ayaks.winterforest.presentation.gl.ObjParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
