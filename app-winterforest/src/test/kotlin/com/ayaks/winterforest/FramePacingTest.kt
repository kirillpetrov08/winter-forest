package com.ayaks.winterforest

import com.ayaks.winterforest.presentation.gl.FramePacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Логика разблокировки частоты кадров (лимит 60 FPS снят) и сглаживания
 * движения камеры между 20-герцевыми тиками симуляции.
 */
class FramePacingTest {

    private fun mode(id: Int, w: Int, h: Int, hz: Float) =
        FramePacing.DisplayModeInfo(id, w, h, hz)

    @Test
    fun `picks fastest mode with current resolution`() {
        val modes = listOf(
            mode(1, 1920, 1080, 60f),
            mode(2, 1920, 1080, 120f),
            mode(3, 1280, 720, 144f)
        )
        val picked = FramePacing.pickFastestMode(modes, 1920, 1080)
        assertEquals(2, picked?.id)
        assertEquals(120f, picked!!.refreshRate, 0f)
    }

    @Test
    fun `ignores faster modes with different resolution`() {
        // 144 Гц, но 720p — переключение разрешения ломает вьюпорт, нужен свой режим
        val modes = listOf(
            mode(1, 2400, 1080, 60f),
            mode(2, 1280, 720, 144f)
        )
        val picked = FramePacing.pickFastestMode(modes, 2400, 1080)
        assertEquals(1, picked?.id)
    }

    @Test
    fun `falls back to fastest overall when resolution unknown`() {
        val modes = listOf(mode(7, 1, 1, 60f), mode(9, 2, 2, 90f))
        val picked = FramePacing.pickFastestMode(modes, 999, 999)
        assertEquals(9, picked?.id)
    }

    @Test
    fun `empty modes list gives null`() {
        assertNull(FramePacing.pickFastestMode(emptyList(), 1080, 1920))
    }

    @Test
    fun `tie on refresh rate resolves deterministically`() {
        val modes = listOf(mode(4, 10, 10, 120f), mode(3, 10, 10, 120f))
        val a = FramePacing.pickFastestMode(modes, 10, 10)
        val b = FramePacing.pickFastestMode(modes.reversed(), 10, 10)
        assertEquals(a, b)
    }

    @Test
    fun `interp alpha clamps to unit interval`() {
        assertEquals(0f, FramePacing.interpAlpha(0L, 50_000_000L))
        assertEquals(0.5f, FramePacing.interpAlpha(25_000_000L, 50_000_000L))
        assertEquals(1f, FramePacing.interpAlpha(80_000_000L, 50_000_000L))
        assertEquals(0f, FramePacing.interpAlpha(-5L, 50_000_000L))
        assertEquals(1f, FramePacing.interpAlpha(10L, 0L))
    }

    @Test
    fun `lerp coord interpolates within snap distance`() {
        assertEquals(1.5f, FramePacing.lerpCoord(1f, 2f, 0.5f, snapDistance = 4f), 1e-4f)
        assertEquals(2f, FramePacing.lerpCoord(1f, 2f, 1f, snapDistance = 4f), 1e-4f)
    }

    @Test
    fun `lerp coord snaps on teleport`() {
        // телепорт через карту — мгновенный скачок, а не «поездка» камеры
        assertEquals(500f, FramePacing.lerpCoord(1f, 500f, 0.25f, snapDistance = 4f), 1e-4f)
        assertEquals(-300f, FramePacing.lerpCoord(0f, -300f, 0.9f, snapDistance = 4f), 1e-4f)
    }

    @Test
    fun `bob interpolation is linear between tick sines`() {
        assertEquals(0.5f, FramePacing.lerpBob(0f, 1f, 0.5f), 1e-4f)
        assertEquals(-1f, FramePacing.lerpBob(-1f, 1f, 0f), 1e-4f)
    }

    @Test
    fun `smooth window covers a full simulation tick`() {
        // 20 Гц тики: за окно ровно один тик проходит alpha от 0 до 1
        assertTrue(FramePacing.interpAlpha(50_000_000L, 50_000_000L) >= 1f)
        assertTrue(FramePacing.interpAlpha(20_000_000L, 50_000_000L) < 1f)
    }
}
