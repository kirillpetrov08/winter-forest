package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.presentation.gl.ForestGlRenderer
import com.ayaks.winterforest.presentation.gl.ProceduralTextureId
import com.ayaks.winterforest.presentation.gl.ProceduralTextures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регрессионные тесты аудита графики: кривая дневного света согласована с
 * игровой ночью движка, процедурные фолбэк-текстуры бесшовны по краям тайла.
 */
class GraphicsAuditTest {

    @Test
    fun `daylight curve matches engine night and stays continuous`() {
        // Игровая ночь движка — cycleFraction [0.6, 1.0): визуальная ночь
        // обязана быть плоской до самого рассвета (без «утра» в 0.92–1.0)
        for (f in listOf(0.63f, 0.70f, 0.80f, 0.90f, 0.99f)) {
            assertEquals("ночь на $f", 0.20f, ForestGlRenderer.computeDaylightFactor(f), 1e-4f)
        }
        // Дневное плато
        for (f in listOf(0.15f, 0.30f, 0.50f)) {
            assertEquals("день на $f", 1.0f, ForestGlRenderer.computeDaylightFactor(f), 1e-4f)
        }
        // Рассветная рампа стартует с ночного уровня и строго растёт
        val a = ForestGlRenderer.computeDaylightFactor(0.0f)
        val b = ForestGlRenderer.computeDaylightFactor(0.06f)
        val c = ForestGlRenderer.computeDaylightFactor(0.119f)
        assertEquals(0.20f, a, 1e-4f)
        assertTrue("рассвет должен расти", b > a && c > b)
        // Непрерывность на обороте цикла: конец ночи стыкуется с началом рассвета
        assertEquals(a, ForestGlRenderer.computeDaylightFactor(0.9999f), 0.01f)
        // Сумеречная рампа
        assertEquals(0.6f, ForestGlRenderer.computeDaylightFactor(0.57f), 1e-3f)
    }

    @Test
    fun `procedural fallback textures are seamless across tile borders`() {
        // Изотропные материалы блендятся по обеим осям — шов обязан быть
        // не грубее соседних пикселей внутри текстуры
        for (id in listOf(
            ProceduralTextureId.SNOW,
            ProceduralTextureId.STONE,
            ProceduralTextureId.WOOD_BARK,
            ProceduralTextureId.PINE_FOLIAGE,
            ProceduralTextureId.ICE,
            ProceduralTextureId.FUR_HIDE
        )) {
            val size = 64
            val px = ProceduralTextures.generateArgbPixels(id, LocationId.BASE_CLEARING.palette, size)
            var seamV = 0.0
            var seamH = 0.0
            var inner = 0.0
            for (y in 0 until size) {
                seamV += channelDiff(px[y * size], px[y * size + size - 1])
                inner += channelDiff(px[y * size + size / 2], px[y * size + size / 2 + 1])
            }
            for (x in 0 until size) {
                seamH += channelDiff(px[x], px[(size - 1) * size + x])
            }
            seamV /= size
            seamH /= size
            inner /= size
            assertTrue(
                "$id: вертикальный шов $seamV грубее внутренности $inner",
                seamV <= inner * 1.6 + 2.0
            )
            assertTrue(
                "$id: горизонтальный шов $seamH грубее внутренности $inner",
                seamH <= inner * 1.6 + 2.0
            )
        }
    }

    private fun channelDiff(a: Int, b: Int): Double {
        var sum = 0
        for (shift in intArrayOf(16, 8, 0)) {
            sum += kotlin.math.abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF))
        }
        return sum / 3.0
    }
}
