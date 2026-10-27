package com.ayaks.winterforest

import com.ayaks.winterforest.data.db.SavePayloadCodec
import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.EquippedTool
import com.ayaks.winterforest.domain.model.HotbarEntry
import com.ayaks.winterforest.domain.model.Inventory
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.WorldObjectKind
import com.ayaks.winterforest.domain.rules.AdvancedMechanics
import com.ayaks.winterforest.domain.rules.SurvivalEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регрессионные тесты аудита багов: персистентный кулдаун восстановления,
 * смола не исчезает при полном рюкзаке, хотбар самоочищается от нулей.
 */
class BugfixAuditTest {

    @Test
    fun `restore cooldown survives save reload and ticks down in engine`() {
        val run = SurvivalEngine.createNewRun(BaseProgress())
            .copy(restoreCooldownRemainingSec = 300f)
        val (json, crc) = SavePayloadCodec.encodeRunState(run)
        val back = SavePayloadCodec.decodeRunState(json, crc)
        assertEquals(300f, back.restoreCooldownRemainingSec, 0.001f)

        val ticked = SurvivalEngine.tick(back, BaseProgress(), deltaSeconds = 10f).run
        assertEquals(290f, ticked.restoreCooldownRemainingSec, 0.001f)
    }

    @Test
    fun `full backpack drops resin on ground instead of losing it`() {
        var inv = Inventory()
        // Детерминированная забивка: все 20 слотов заняты, в дереве запас 6
        // (максимальный выход сбора) — дереву место хватит, смоле уже нет
        for (k in 0 until 4) inv = inv.tryAddResource(ResourceType.WOOD, 1).inventory
        val fillers = ArrayDeque(ResourceType.entries.filter {
            it != ResourceType.RESIN && it != ResourceType.WOOD
        })
        while (inv.occupiedSlots < Inventory.MAX_SLOTS) {
            val t = fillers.removeFirst()
            fillers.addLast(t)
            val r = inv.tryAddResource(t, 10)
            if (r.isFullBlocked) break
            inv = r.inventory
        }
        assertEquals(Inventory.MAX_SLOTS, inv.occupiedSlots)

        val run = SurvivalEngine.createNewRun(BaseProgress()).copy(inventory = inv)
        val pine = run.currentLocationNodes().first { it.kind == WorldObjectKind.PINE_TREE }
        val armed = run.copy(
            playerX = pine.x,
            playerZ = pine.z,
            equippedTool = EquippedTool.AXE
        )
        val out = SurvivalEngine.gatherNearest(armed, BaseProgress())
        assertTrue(out.message, out.actionSucceeded)
        val drop = out.run.droppedItems.firstOrNull { it.resourceType == ResourceType.RESIN }
        assertNotNull("смола обязана упасть на землю, а не исчезнуть", drop)
        assertTrue(out.message, out.message.contains("землю"))
    }

    @Test
    fun `hotbar entry auto clears when resource hits zero by any spend`() {
        val run = SurvivalEngine.createNewRun(BaseProgress())
            .copy(inventory = Inventory().tryAddResource(ResourceType.WOOD, 1).inventory)
            .withHotbarSlot(
                0,
                HotbarEntry(
                    titleRu = ResourceType.WOOD.titleRu,
                    iconPath = "",
                    resourceType = ResourceType.WOOD
                )
            )
        val spent = SurvivalEngine.useResource(run, BaseProgress(), ResourceType.WOOD)
        assertEquals(0, spent.run.inventory.countOf(ResourceType.WOOD))
        val clean = AdvancedMechanics.sanitizeHotbarEntries(spent.run)
        assertNull("слот с нулевым остатком убирается автоматически", clean.hotbarAt(0))
    }
}
