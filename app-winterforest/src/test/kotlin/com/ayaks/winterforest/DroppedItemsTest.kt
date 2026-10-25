package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.Inventory
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.SpecialLootId
import com.ayaks.winterforest.domain.rules.SurvivalEngine
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7.12: любой предмет можно выбросить — он лежит на земле и подбирается
 * кнопкой «Подобрать» в радиусе взаимодействия.
 */
class DroppedItemsTest {

    @Test
    fun `dropped resource lies ahead of player and returns on pickup`() {
        val base = BaseProgress()
        val run0 = SurvivalEngine.createNewRun(base)
            .copy(inventory = Inventory().tryAddResource(ResourceType.WOOD, 5).inventory)
        val before = run0.inventory.countOf(ResourceType.WOOD)

        val dropped = SurvivalEngine.dropItemNearPlayer(run0, base, resourceType = ResourceType.WOOD)
        assertTrue(dropped.actionSucceeded)
        assertEquals(before - 1, dropped.run.inventory.countOf(ResourceType.WOOD))
        assertEquals(1, dropped.run.droppedItems.size)

        val item = dropped.run.droppedItems.first()
        val dist = hypot(item.x - dropped.run.playerX, item.z - dropped.run.playerZ)
        assertTrue("предмет должен лежать рядом (1.25 м): $dist", dist in 1.0f..1.6f)

        // подсказка цели превращается в «Подобрать»
        val prompt = dropped.run.activeTargetPrompt()
        assertTrue(prompt?.isDroppedItem == true)
        assertEquals("Подобрать", prompt?.actionVerbRu)

        val picked = SurvivalEngine.pickupDroppedItem(dropped.run, base, item.id)
        assertTrue(picked.actionSucceeded)
        assertEquals(before, picked.run.inventory.countOf(ResourceType.WOOD))
        assertTrue(picked.run.droppedItems.isEmpty())
    }

    @Test
    fun `special consumable can be dropped and picked back`() {
        val base = BaseProgress()
        val run0 = SurvivalEngine.createNewRun(base)
            .copy(inventory = Inventory().grantSpecialLoot(SpecialLootId.FIRST_AID_KIT, 1))
        val dropped = SurvivalEngine.dropItemNearPlayer(run0, base, specialLootId = SpecialLootId.FIRST_AID_KIT)
        assertTrue(dropped.actionSucceeded)
        assertEquals(0, dropped.run.inventory.specialCount(SpecialLootId.FIRST_AID_KIT))
        val picked = SurvivalEngine.pickupDroppedItem(dropped.run, base, dropped.run.droppedItems.first().id)
        assertTrue(picked.actionSucceeded)
        assertEquals(1, picked.run.inventory.specialCount(SpecialLootId.FIRST_AID_KIT))
    }

    @Test
    fun `pickup with full inventory keeps item on ground`() {
        val base = BaseProgress()
        var full = Inventory()
        for (i in 0 until Inventory.MAX_SLOTS) {
            full = full.tryAddResource(ResourceType.WOOD, 1).inventory
        }
        // заполняем под завязку разными типами, чтобы камень не куда было класть
        var inv = Inventory()
        val types = ResourceType.entries.filter { it != ResourceType.STONE }
        for (t in types.take(Inventory.MAX_SLOTS)) {
            inv = inv.tryAddResource(t, Inventory.MAX_STACK_SIZE).inventory
        }
        val run0 = SurvivalEngine.createNewRun(base).copy(inventory = inv)
        val withDrop = run0.copy(
            droppedItems = listOf(
                com.ayaks.winterforest.domain.model.DroppedItem(
                    id = "d1", resourceType = ResourceType.STONE, count = 1,
                    x = run0.playerX, z = run0.playerZ
                )
            )
        )
        val picked = SurvivalEngine.pickupDroppedItem(withDrop, base, "d1")
        assertFalse(picked.actionSucceeded)
        assertTrue(picked.message.contains("Инвентарь полон"))
        assertEquals(1, picked.run.droppedItems.size)
        assertEquals(full.occupiedSlots, Inventory.MAX_SLOTS)
    }
}
