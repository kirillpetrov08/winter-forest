package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.presentation.gl.GlMesh
import com.ayaks.winterforest.presentation.gl.MeshBuilder
import com.ayaks.winterforest.presentation.gl.ProceduralTextureId
import com.ayaks.winterforest.presentation.gl.ProceduralTextures
import com.ayaks.winterforest.presentation.gl.Shaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProceduralGraphicsAndRendererTest {

    @Test
    fun `procedural texture generator produces non uniform RGBA data for all 8 materials and distinct biome snows`() {
        val snowClearing = ProceduralTextures.generateRgbaBuffer(
            id = ProceduralTextureId.SNOW,
            palette = LocationId.BASE_CLEARING.palette,
            size = 32
        )
        val snowMine = ProceduralTextures.generateRgbaBuffer(
            id = ProceduralTextureId.SNOW,
            palette = LocationId.ABANDONED_MINE.palette,
            size = 32
        )
        assertEquals(32 * 32 * 4, snowClearing.capacity())

        for (texId in ProceduralTextureId.entries) {
            val buf = ProceduralTextures.generateRgbaBuffer(
                id = texId,
                palette = LocationId.BASE_CLEARING.palette,
                size = 32
            )
            val distinctLuminances = HashSet<Int>()
            for (px in 0 until 32 * 32) {
                val r = buf.get(px * 4).toInt() and 0xFF
                val g = buf.get(px * 4 + 1).toInt() and 0xFF
                val b = buf.get(px * 4 + 2).toInt() and 0xFF
                val a = buf.get(px * 4 + 3).toInt() and 0xFF
                assertTrue(a > 0)
                distinctLuminances.add(r * 3 + g * 5 + b)
            }
            assertTrue(
                "Текстура $texId должна быть процедурно-шумовой, а не однотонной заливкой",
                distinctLuminances.size >= 8
            )
        }

        // Проверяем отличие снежного покрова Базовой поляны от угольной Заброшенной шахты
        var diffSum = 0L
        for (i in 0 until snowClearing.capacity()) {
            diffSum += kotlin.math.abs(
                (snowClearing.get(i).toInt() and 0xFF) - (snowMine.get(i).toInt() and 0xFF)
            )
        }
        assertTrue("Палитра снега Шахты должна заметно отличаться от Базовой поляны", diffSum > 15_000L)
    }

    @Test
    fun `procedural 3d meshes build finite vertices unit normals and valid GLSL shaders`() {
        val pineAndCabinMesh = MeshBuilder().apply {
            addBox(0f, 0f, 0f, 3.2f, 2.4f, 3.2f)
            addCone(0f, 2.4f, 0f, 2.1f, 3.0f, segments = 8)
            addShadowDisc(0f, 0f, 0f, 2.4f)
        }.build()

        // Legacy-террейн отдельной локации удалён (мёртвый код): проверяем
        // единственный прод-билдер рельефа единого мира
        val meshes = listOf(
            MeshBuilder.buildWorldTerrainIntoBuilder(steps = 120).build()
        ) + pineAndCabinMesh

        for (mesh in meshes) {
            assertTrue(mesh.vertexCount >= 6)
            assertEquals(mesh.vertexCount * GlMesh.FLOATS_PER_VERTEX, mesh.vertexBuffer.capacity())
            for (v in 0 until mesh.vertexCount) {
                val base = v * GlMesh.FLOATS_PER_VERTEX
                for (k in 0 until GlMesh.FLOATS_PER_VERTEX) {
                    val value = mesh.vertexBuffer.get(base + k)
                    assertTrue("Координата/нормаль/UV не должна быть NaN или Infinity", value.isFinite())
                }
                val nx = mesh.vertexBuffer.get(base + 3)
                val ny = mesh.vertexBuffer.get(base + 4)
                val nz = mesh.vertexBuffer.get(base + 5)
                val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
                assertEquals("Нормаль вершины должна быть единичной длины", 1f, len, 0.02f)
            }
        }

        assertTrue(Shaders.WORLD_VERTEX_SHADER.contains("uMvpMatrix"))
        assertTrue(Shaders.WORLD_FRAGMENT_SHADER.contains("uPointLightColor"))
        assertTrue(Shaders.WORLD_FRAGMENT_SHADER.contains("uFogColor"))
        assertTrue(Shaders.SKY_FRAGMENT_SHADER.contains("uSkyZenith"))
    }
}
