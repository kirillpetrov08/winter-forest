package com.ayaks.winterforest.presentation.gl

import kotlin.math.abs

/**
 * Логика разблокировки частоты кадров (снятие лимита 60 FPS) и сглаживания
 * движения камеры между 20-герцевыми тиками симуляции.
 *
 * Всё здесь — чистые функции без Android: выбор режима дисплея и математика
 * интерполяции тестируются на JVM. Применение к окну/поверхности выполняют
 * [com.ayaks.winterforest.MainActivity] (preferredDisplayModeId) и
 * [ForestGlSurfaceView] (eglSwapInterval(0) + Surface.setFrameRate).
 */
object FramePacing {

    /** Режим дисплея (дубликат android.view.Display.Mode без Android-зависимости). */
    data class DisplayModeInfo(
        val id: Int,
        val width: Int,
        val height: Int,
        val refreshRate: Float
    )

    /**
     * Выбирает самый быстрый режим с текущим разрешением; если таких нет —
     * самый быстрый из всех. null — когда менять нечего.
     */
    fun pickFastestMode(
        modes: List<DisplayModeInfo>,
        currentWidth: Int,
        currentHeight: Int
    ): DisplayModeInfo? {
        val sameRes = modes.filter { it.width == currentWidth && it.height == currentHeight }
        val pool = if (sameRes.isNotEmpty()) sameRes else modes
        // детерминированный тай-брейк: стабильный id режима между запусками
        return pool.maxWithOrNull(compareBy<DisplayModeInfo> { it.refreshRate }.thenBy { -it.id })
    }

    /** Позиция кадра в окне интерполяции тика: 0..1 без перелёта. */
    fun interpAlpha(elapsedNs: Long, windowNs: Long): Float =
        if (windowNs <= 0L) 1f else (elapsedNs.toFloat() / windowNs).coerceIn(0f, 1f)

    /**
     * Плавная координата между тиками; при телепорте (смена локации, путешествие)
     * — мгновенный скачок, чтобы камера не «ехала» через всю карту.
     */
    fun lerpCoord(prev: Float, cur: Float, alpha: Float, snapDistance: Float): Float =
        if (abs(cur - prev) > snapDistance) cur else prev + (cur - prev) * alpha

    /** Интерполяция синуса фазы bobbing-шага (без проблем 2π-перехода). */
    fun lerpBob(prevSin: Float, curSin: Float, alpha: Float): Float =
        prevSin + (curSin - prevSin) * alpha
}
