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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        // 19 слотов под завязку, один оставляем дереву — смоле места не хватит
        val fillers = ResourceType.entries.filter { it != ResourceType.RESIN }.take(19)
        for (t in fillers) {
            for (k in 0 until 40) inv = inv.tryAddResource(t, 10).inventory
        }
        assertTrue(inv.occupiedSlots >= 19, "рюкзак должен быть почти полон")

        val run = SurvivalEngine.createNewRun(BaseProgress()).copy(inventory = inv)
        val pine = run.currentLocationNodes().first { it.kind == WorldObjectKind.PINE_TREE }
        val armed = run.copy(
            playerX = pine.x,
            playerZ = pine.z,
            equippedTool = EquippedTool.AXE
        )
        val out = SurvivalEngine.gatherNearest(armed, BaseProgress())
        assertTrue(out.actionSucceeded, out.message)
        val drop = out.run.droppedItems.firstOrNull { it.resourceType == ResourceType.RESIN }
        assertNotNull(drop, "смола обязана упасть на землю, а не исчезнуть")
        assertTrue(out.message.contains("землю"), out.message)
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
        assertNull(clean.hotbarAt(0), "слот с нулевым остатком убирается автоматически")
    }
}
