package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.BaseUpgradeType
import com.ayaks.winterforest.domain.model.EquippedTool
import com.ayaks.winterforest.domain.model.Inventory
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.WorkbenchRecipe
import com.ayaks.winterforest.domain.rules.SurvivalEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Железная цепочка: руда → Каменная печь → слитки → железные крафты с
 * постоянными бонусами к добыче и охоте.
 */
class IronCraftingTest {

    private fun inventoryWith(vararg pairs: Pair<ResourceType, Int>): Inventory {
        var inv = Inventory()
        for ((type, amount) in pairs) {
            inv = inv.tryAddResource(type, amount).inventory
        }
        return inv
    }

    @Test
    fun `furnace builds from level zero and unlocks smelting`() {
        val base = BaseProgress()
        assertEquals(0, base.furnaceLevel)
        val run = SurvivalEngine.createNewRun(base).copy(
            inventory = inventoryWith(ResourceType.STONE to 8, ResourceType.WOOD to 4)
        )

        // До постройки плавка запрещена
        val early = SurvivalEngine.smeltAtFurnace(run, base, 1)
        assertFalse(early.actionSucceeded)
        assertTrue(early.message.contains("не построена"))

        val built = SurvivalEngine.upgradeBaseStructure(run, base, BaseUpgradeType.FURNACE)
        assertTrue(built.actionSucceeded)
        assertEquals(1, built.base.furnaceLevel)
    }

    @Test
    fun `smelting converts ore and coal into iron ingots`() {
        val base = BaseProgress(furnaceLevel = 1)
        val run = SurvivalEngine.createNewRun(base).copy(
            inventory = inventoryWith(ResourceType.IRON to 6, ResourceType.COAL to 2)
        )

        val smelted = SurvivalEngine.smeltAtFurnace(run, base, 1)
        assertTrue(smelted.actionSucceeded)
        assertEquals(1, smelted.run.inventory.countOf(ResourceType.IRON_INGOT))
        // Уровень 1: уходит 3 руды + 1 уголь
        assertEquals(3, smelted.run.inventory.countOf(ResourceType.IRON))
        assertEquals(1, smelted.run.inventory.countOf(ResourceType.COAL))
    }

    @Test
    fun `high level furnace is cheaper and yields double ingots`() {
        assertEquals(mapOf(ResourceType.IRON to 2, ResourceType.COAL to 1), BaseProgress.smeltCost(3))
        assertEquals(1, BaseProgress.smeltYield(1))
        assertEquals(1, BaseProgress.smeltYield(3))
        assertEquals(2, BaseProgress.smeltYield(4))

        val base = BaseProgress(furnaceLevel = 4)
        val run = SurvivalEngine.createNewRun(base).copy(
            inventory = inventoryWith(ResourceType.IRON to 4, ResourceType.COAL to 2)
        )
        val smelted = SurvivalEngine.smeltAtFurnace(run, base, 1)
        assertTrue(smelted.actionSucceeded)
        assertEquals(2, smelted.run.inventory.countOf(ResourceType.IRON_INGOT))
    }

    @Test
    fun `batch smelting processes several batches at once`() {
        val base = BaseProgress(furnaceLevel = 3)
        val run = SurvivalEngine.createNewRun(base).copy(
            inventory = inventoryWith(ResourceType.IRON to 10, ResourceType.COAL to 5)
        )
        val smelted = SurvivalEngine.smeltAtFurnace(run, base, 3)
        assertTrue(smelted.actionSucceeded)
        assertEquals(3, smelted.run.inventory.countOf(ResourceType.IRON_INGOT))
        assertEquals(4, smelted.run.inventory.countOf(ResourceType.IRON))
    }

    @Test
    fun `iron axe recipe requires furnace and grants permanent wood bonus`() {
        val baseWithIngots = inventoryWith(ResourceType.IRON_INGOT to 2, ResourceType.WOOD to 2)
        val noFurnace = BaseProgress(furnaceLevel = 0, workbenchLevel = 2)
        val run = SurvivalEngine.createNewRun(noFurnace).copy(inventory = baseWithIngots)

        val blocked = SurvivalEngine.craftAtWorkbench(run, noFurnace, WorkbenchRecipe.CRAFT_IRON_AXE)
        assertFalse(blocked.actionSucceeded)
        assertTrue(blocked.message.contains("Каменную печь"))

        val base = BaseProgress(furnaceLevel = 1, workbenchLevel = 2)
        val crafted = SurvivalEngine.craftAtWorkbench(run, base, WorkbenchRecipe.CRAFT_IRON_AXE)
        assertTrue(crafted.actionSucceeded)
        assertTrue(crafted.base.ironAxeCrafted)
        assertFalse(crafted.base.ironPickaxeCrafted)

        // Бонус к добыче: +1 дерева поверх топора
        val gatherRun = SurvivalEngine.createNewRun(crafted.base).copy(
            inventory = Inventory(),
            equippedTool = EquippedTool.AXE
        )
        val woodNode = gatherRun.currentLocationNodes().first { it.resourceType == ResourceType.WOOD }
        val gathered = SurvivalEngine.gatherNearest(
            gatherRun.copy(playerX = woodNode.x, playerZ = woodNode.z),
            crafted.base,
            withToolStrikeBonus = true
        )
        assertTrue(gathered.actionSucceeded)
        assertEquals(woodNode.baseYield + 2, gathered.run.inventory.countOf(ResourceType.WOOD))
    }

    @Test
    fun `iron pickaxe adds ore bonus and iron spearhead boosts hunting`() {
        val base = BaseProgress(furnaceLevel = 1, workbenchLevel = 2)
        val run = SurvivalEngine.createNewRun(base).copy(
            inventory = inventoryWith(
                ResourceType.IRON_INGOT to 4,
                ResourceType.WOOD to 3,
                ResourceType.CLOTH to 1
            )
        )
        val pickCraft = SurvivalEngine.craftAtWorkbench(run, base, WorkbenchRecipe.CRAFT_IRON_PICKAXE)
        assertTrue(pickCraft.actionSucceeded)
        assertTrue(pickCraft.base.ironPickaxeCrafted)

        val spearCraft = SurvivalEngine.craftAtWorkbench(
            pickCraft.run.copy(inventory = inventoryWith(ResourceType.IRON_INGOT to 1, ResourceType.WOOD to 1, ResourceType.CLOTH to 1)),
            pickCraft.base,
            WorkbenchRecipe.CRAFT_IRON_SPEARHEAD
        )
        assertTrue(spearCraft.actionSucceeded)
        assertTrue(spearCraft.base.ironSpearCrafted)

        // Добыча камня киркой: +1 за кирку и +1 за железную кирку
        val gatherRun = SurvivalEngine.createNewRun(spearCraft.base).copy(
            inventory = Inventory(),
            equippedTool = EquippedTool.PICKAXE
        )
        val stoneNode = gatherRun.currentLocationNodes().first { it.resourceType == ResourceType.STONE }
        val gathered = SurvivalEngine.gatherNearest(
            gatherRun.copy(playerX = stoneNode.x, playerZ = stoneNode.z),
            spearCraft.base,
            withToolStrikeBonus = true
        )
        assertTrue(gathered.actionSucceeded)
        assertEquals(stoneNode.baseYield + 2, gathered.run.inventory.countOf(ResourceType.STONE))
    }
}
