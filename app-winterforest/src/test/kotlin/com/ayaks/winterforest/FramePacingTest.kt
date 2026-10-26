package com.ayaks.winterforest

import com.ayaks.winterforest.presentation.gl.FramePacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кадровая дисциплина: целевые 60 FPS (лимит больше НЕ снимается) и сглаживание
 * движения камеры между 20-герцевыми тиками симуляции.
 */
class FramePacingTest {

    @Test
    fun `game targets exactly 60 frames per second`() {
        // Пользовательский дефект: «при включённых 120 FPS постоянные микрофризы».
        // Цель игры — 60 кадров, поэтому ни запрос режима дисплея, ни swap interval
        // не имеют права уходить выше.
        assertEquals(60, FramePacing.TARGET_FPS)
        assertEquals(60f, FramePacing.TARGET_SURFACE_FRAME_RATE, 0f)
    }

    @Test
    fun `swap interval waits for vsync`() {
        // 0 = рендер без ожидания вертикального синхроимпульса (прежнее поведение,
        // которое разогревало SoC). 1 = ровно один кадр на vsync.
        assertEquals(1, FramePacing.TARGET_SWAP_INTERVAL)
    }

    @Test
    fun `sustained slowdown detected below target fps`() {
        // 30 мс/кадр ≈ 33 FPS — устойчивая просадка
        assertTrue(FramePacing.isSustainedSlowdown(30f))
        // 16.7 мс/кадр = 60 FPS — норма
        assertFalse(FramePacing.isSustainedSlowdown(16.7f))
    }

    @Test
    fun `smoothed frame time follows measurement with weight`() {
        assertEquals(20f, FramePacing.smoothedFrameMs(0f, 20f), 1e-4f)
        assertEquals(18f, FramePacing.smoothedFrameMs(20f, 10f, weight = 0.2f), 1e-4f)
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
    fun `angle interpolation takes shortest arc across zero`() {
        // 350deg -> 10deg кратчайший путь через ноль, а не через 340 градусов
        val a = Math.toRadians(350.0).toFloat()
        val b = Math.toRadians(10.0).toFloat()
        val mid = FramePacing.lerpAngle(a, b, 0.5f)
        val midDeg = Math.toDegrees((((mid % 6.2831855f) + 6.2831855f) % 6.2831855f).toDouble()).toFloat()
        assertTrue("mid=$midDeg", midDeg > 350f || midDeg < 20f)
        // обратный переход тоже короткий
        val mid2 = FramePacing.lerpAngle(b, a, 0.5f)
        val mid2Deg = Math.toDegrees((((mid2 % 6.2831855f) + 6.2831855f) % 6.2831855f).toDouble()).toFloat()
        assertTrue("mid2=$mid2Deg", mid2Deg > 350f || mid2Deg < 20f)
    }

    @Test
    fun `angle interpolation endpoints and full turn`() {
        assertEquals(2.0f, FramePacing.lerpAngle(0.4f, 2.0f, 1f), 1e-4f)
        assertEquals(0.4f, FramePacing.lerpAngle(0.4f, 2.0f, 0f), 1e-4f)
        // полный оборот — это не поворот
        val full = FramePacing.lerpAngle(0.5f, 0.5f + 6.2831855f, 0.5f)
        assertEquals(0.5f, full, 1e-3f)
    }

    @Test
    fun `smooth window covers a full simulation tick`() {
        // 20 Гц тики: за окно ровно один тик проходит alpha от 0 до 1
        assertTrue(FramePacing.interpAlpha(50_000_000L, 50_000_000L) >= 1f)
        assertTrue(FramePacing.interpAlpha(20_000_000L, 50_000_000L) < 1f)
    }
}
