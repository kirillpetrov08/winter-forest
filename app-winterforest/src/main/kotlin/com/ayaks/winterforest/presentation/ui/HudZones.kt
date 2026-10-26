package com.ayaks.winterforest.presentation.ui

/**
 * Бюджеты ширины зон игрового HUD (§7.14 «кнопки не налезают друг на друга»).
 *
 * [com.ayaks.winterforest.presentation.ui.screens.GameScreen] собран двумя
 * структурными Row (верхняя и нижняя строки): дети размещаются последовательно,
 * поэтому физически не могут пересечься, а остатки ширины доедают Spacer(weight).
 * Остаётся риск ДРУГОЙ: если сумма худших ширин зоны превысит ширину окна, Row
 * упрётся в край и последние дети уедут за экран. Числа ниже — худшие замеренные
 * ширины содержимого каждой зоны; [topRowFits]/[bottomRowFits] проверяют бюджет
 * для representative-размеров в юнит-тесте HudZonesTest.
 */
object HudZones {

    /** Уже этой ширины хватает только компактному профилю HUD. */
    const val COMPACT_WIDTH_DP = 760f

    /** Миникарта живёт в правом верхнем углу на ЛЮБОЙ ширине (запрос пользователя). */
    const val RADAR_MIN_WIDTH_DP = 0f

    // --- верхняя строка: инфо-чип | таймер | меню+миникарта ---
    // Показатели жизни переехали вниз (компактная полоска над хотбаром),
    // сверху остался только чип «район • день • погода» — минимальной ширины.
    const val STATS_WIDTH_DP = 172f
    const val COMPACT_STATS_WIDTH_DP = 140f
    const val TIMER_WIDTH_DP = 210f
    const val COMPACT_TIMER_WIDTH_DP = 190f
    const val MENU_WIDTH_DP = 208f
    const val COMPACT_MENU_WIDTH_DP = 186f
    const val RADAR_WIDTH_DP = 106f

    // --- нижняя строка: джойстик | хотбар | действия ---
    const val JOYSTICK_DP = 148
    const val COMPACT_JOYSTICK_DP = 120
    const val HOTBAR_SLOT_DP = 50
    const val COMPACT_HOTBAR_SLOT_DP = 42
    const val ACTIONS_WIDTH_DP = 150f
    const val COMPACT_ACTIONS_WIDTH_DP = 132f

    /** Горизонтальные отступы окна сверху и снизу (10 dp × 2). */
    const val SIDE_PADDING_DP = 20f

    fun isCompact(widthDp: Float): Boolean = widthDp < COMPACT_WIDTH_DP

    fun radarVisible(widthDp: Float): Boolean = widthDp >= RADAR_MIN_WIDTH_DP // всегда true

    fun statsWidth(widthDp: Float): Float =
        if (isCompact(widthDp)) COMPACT_STATS_WIDTH_DP else STATS_WIDTH_DP

    fun timerWidth(widthDp: Float): Float =
        if (isCompact(widthDp)) COMPACT_TIMER_WIDTH_DP else TIMER_WIDTH_DP

    fun menuWidth(widthDp: Float): Float =
        if (isCompact(widthDp)) COMPACT_MENU_WIDTH_DP else MENU_WIDTH_DP +
            if (radarVisible(widthDp)) RADAR_WIDTH_DP else 0f

    fun joystickWidth(widthDp: Float): Float =
        if (isCompact(widthDp)) COMPACT_JOYSTICK_DP.toFloat() else JOYSTICK_DP.toFloat()

    fun hotbarWidth(widthDp: Float): Float {
        val slot = if (isCompact(widthDp)) COMPACT_HOTBAR_SLOT_DP else HOTBAR_SLOT_DP
        // 6 слотов + 5 промежутков 6 dp + padding 16 dp + кайма 2 dp
        return 6 * slot + 5 * 6f + 18f
    }

    fun actionsWidth(widthDp: Float): Float =
        if (isCompact(widthDp)) COMPACT_ACTIONS_WIDTH_DP else ACTIONS_WIDTH_DP

    /** Худшая ширина верхней строки HUD при данной ширине окна. */
    fun topRowWidth(widthDp: Float): Float =
        statsWidth(widthDp) + timerWidth(widthDp) + menuWidth(widthDp) + SIDE_PADDING_DP

    /** Худшая ширина нижней строки HUD при данной ширине окна. */
    fun bottomRowWidth(widthDp: Float): Float =
        joystickWidth(widthDp) + hotbarWidth(widthDp) + actionsWidth(widthDp) + SIDE_PADDING_DP

    fun topRowFits(widthDp: Float): Boolean = topRowWidth(widthDp) <= widthDp

    fun bottomRowFits(widthDp: Float): Boolean = bottomRowWidth(widthDp) <= widthDp
}
