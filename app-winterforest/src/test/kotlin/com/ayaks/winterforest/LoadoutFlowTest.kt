package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.EquippedTool
import com.ayaks.winterforest.domain.model.StarterLoadout
import com.ayaks.winterforest.domain.rules.SurvivalEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §7.4: класс применяется к zabeg'у через base.selectedLoadout, который
 * выставляется на экране выбора перед стартом; каждый класс даёт свой
 * стартовый набор, а руки при этом пустые (§7.11).
 */
class LoadoutFlowTest {

    @Test
    fun `every class grants exactly its starter kit and empty hands`() {
        for (loadout in StarterLoadout.entries) {
            val base = SurvivalEngine.selectStarterLoadout(BaseProgress(), loadout)
            assertEquals(loadout, base.selectedLoadout)
            val run = SurvivalEngine.createNewRun(base)
            for ((res, qty) in loadout.items) {
                assertEquals(
                    "${loadout.name}: ${res.titleRu} должно быть $qty",
                    qty,
                    run.inventory.countOf(res)
                )
            }
            assertEquals(EquippedTool.NONE, run.equippedTool)
        }
    }

    @Test
    fun `classes differ and each has a starter kit`() {
        val kits = StarterLoadout.entries.map { it.items }
        assertEquals(kits.size, kits.toSet().size)
        for (loadout in StarterLoadout.entries) {
            assertTrue("${loadout.name}: пустой набор", loadout.items.isNotEmpty())
            assertTrue("${loadout.name}: пустое описание", loadout.descriptionRu.isNotBlank())
            assertTrue("${loadout.name}: короткое имя", loadout.titleRu.length >= 4)
        }
    }
}
