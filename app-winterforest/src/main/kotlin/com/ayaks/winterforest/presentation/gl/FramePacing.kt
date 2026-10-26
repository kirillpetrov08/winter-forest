package com.ayaks.winterforest.presentation.gl

import kotlin.math.abs

/**
 * Кадровая дисциплина: целевая частота кадров, ожидание vsync и сглаживание
 * движения камеры между 20-герцевыми тиками симуляции.
 *
 * Всё здесь — чистые функции без Android: их тестируют на JVM. Применение к
 * окну/поверхности выполняют [com.ayaks.winterforest.MainActivity] и
 * [ForestGlSurfaceView].
 *
 * ИСТОРИЯ: раньше игра намеренно снимала лимит 60 FPS (eglSwapInterval(0),
 * Surface.setFrameRate(0f) и переключение дисплея в самый быстрый режим
 * 90/120/144 Гц). На Galaxy S24+ это давало 120 полных кадров тяжёлой сцены в
 * секунду: GPU упирался в термопакет, включался троттлинг, и кадры шли
 * рывками — те самые «микрофризы». Рендер с частотой выше 60 Гц не добавляет
 * игре отзывчивости (симуляция всё равно тикает на 20 Гц), зато вдвое-втрое
 * увеличивает нагрузку. Теперь цель — ровные 60 кадров на vsync.
 */
object FramePacing {

    /** Целевая частота кадров игры. */
    const val TARGET_FPS = 60

    /**
     * Интервал обмена буферов для EGL: 1 = ждать вертикальный синхроимпульс.
     * Ровно 60 FPS на 60-Гц панели и 60 из 120 на 120-Гц (система сама делит).
     */
    const val TARGET_SWAP_INTERVAL = 1

    /**
     * Частота, которую игра просит у поверхности (Android 11+). Значение 0f
     * означало «не троттлите меня вообще» и разрешало системе держать 120 Гц.
     */
    const val TARGET_SURFACE_FRAME_RATE = 60f

    /** Период тика симуляции в наносекундах (20 Гц). */
    const val TICK_INTERVAL_NS = 50_000_000L

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

    /**
     * Плавный поворот по кратчайшей дуге: разница углов сворачивается в (-π, π],
     * поэтому зверь не проворачивается через 2π при обходе нуля.
     */
    fun lerpAngle(prevRad: Float, curRad: Float, alpha: Float): Float {
        var delta = (curRad - prevRad) % TWO_PI
        if (delta > Math.PI.toFloat()) delta -= TWO_PI
        if (delta < -Math.PI.toFloat()) delta += TWO_PI
        return prevRad + delta * alpha
    }

    /**
     * Скользящее среднее времени кадра в миллисекундах: используется, чтобы
     * отличить единичный всплеск (сборка VBO, GC) от устойчивой просадки FPS.
     */
    fun smoothedFrameMs(previousMs: Float, measuredMs: Float, weight: Float = 0.2f): Float =
        if (previousMs <= 0f) measuredMs else previousMs + (measuredMs - previousMs) * weight

    /** Устойчивая просадка ниже целевой частоты (порог 45 FPS при цели 60). */
    fun isSustainedSlowdown(smoothedFrameMs: Float, fpsFloor: Int = 45): Boolean =
        smoothedFrameMs > 1000f / fpsFloor

    private const val TWO_PI = 6.2831855f
}
