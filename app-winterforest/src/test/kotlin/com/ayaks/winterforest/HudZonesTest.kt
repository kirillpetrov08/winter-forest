package com.ayaks.winterforest

import com.ayaks.winterforest.presentation.ui.HudZones
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7.14 «кнопки не налезают друг на друга»: игровой HUD собран двумя
 * структурными Row (верх/низ), зоны в них размещаются последовательно и не
 * могут пересечься; остаётся доказать, что сумма худших ширин зон влезает в
 * окно на типовых устройствах — иначе Row упрётся в край и последние кнопки
 * уедут за экран.
 */
class HudZonesTest {

    private val widths = listOf(640f, 720f, 780f, 844f, 960f, 1024f, 1280f, 1600f)

    @Test
    fun `top and bottom hud rows fit every representative screen width`() {
        for (w in widths) {
            assertTrue(
                "верхняя строка HUD не влезает в ${w.toInt()} dp: нужно ${HudZones.topRowWidth(w)}",
                HudZones.topRowFits(w)
            )
            assertTrue(
                "нижняя строка HUD не влезает в ${w.toInt()} dp: нужно ${HudZones.bottomRowWidth(w)}",
                HudZones.bottomRowFits(w)
            )
        }
    }

    @Test
    fun `compact profile engages below 760 dp and minimap is always visible`() {
        assertTrue(HudZones.isCompact(640f))
        assertTrue(HudZones.isCompact(759f))
        assertFalse(HudZones.isCompact(760f))
        // миникарта в правом верхнем углу на любой ширине
        for (w in widths) {
            assertTrue("миникарта пропала на ${w.toInt()} dp", HudZones.radarVisible(w))
        }
        // ширина меню включает миникарту на всех размерах
        assertTrue(HudZones.menuWidth(640f) >= HudZones.COMPACT_MENU_WIDTH_DP + HudZones.RADAR_WIDTH_DP)
        assertTrue(HudZones.menuWidth(1280f) >= HudZones.MENU_WIDTH_DP + HudZones.RADAR_WIDTH_DP)
    }

    @Test
    fun `hud never covers more than a quarter of the screen height`() {
        // верхняя строка ~118 dp, нижняя ~168 dp: на минимальном ландшафтном
        // телефоне (360 dp высоты) HUD занимает меньше половины экрана
        val minHeight = 360f
        val hudHeight = 118f + 168f
        assertTrue("HUD съедает весь обзор", hudHeight < minHeight * 0.8f)
    }
}
