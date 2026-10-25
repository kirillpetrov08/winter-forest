package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.BaseUpgradeType
import com.ayaks.winterforest.domain.model.EquippedTool
import com.ayaks.winterforest.domain.model.Inventory
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.WorldObjectKind
import com.ayaks.winterforest.domain.model.WorkbenchRecipe
import com.ayaks.winterforest.domain.rules.SurvivalEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7.11 «Руки без инструмента + прогрессия крафта»: новый забег начинается с
 * пустыми руками, большие деревья/порода/руда без инструмента НЕ добываются
 * (жёсткий гейт с подсказкой), а первые топор и кирка крафтятся из собранных
 * руками палок и гальки.
 */
class HandProgressionTest {

    @Test
    fun `new run starts with empty hands`() {
        val run = SurvivalEngine.createNewRun(BaseProgress())
        assertEquals(EquippedTool.NONE, run.equippedTool)
    }

    @Test
    fun `big tree and ore are hard-gated without tools but hint the craft`() {
        val base = BaseProgress()
        val run0 = SurvivalEngine.createNewRun(base)

        val pine = run0.currentLocationNodes().first { it.kind == WorldObjectKind.PINE_TREE }
        val atPine = run0.copy(playerX = pine.x, playerZ = pine.z)
        val pineHit = SurvivalEngine.gatherNearest(atPine, base)
        assertFalse("дерево без топора добываться не должно", pineHit.actionSucceeded)
        assertTrue(pineHit.message.contains("Примитивный топор"))
        assertTrue(
            "узел остаётся на карте",
            pineHit.run.currentLocationNodes().first { it.id == pine.id }.isAvailable
        )

        val seam = run0.currentLocationNodes().first { it.kind == WorldObjectKind.IRON_VEIN }
        val oreHit = SurvivalEngine.gatherNearest(run0.copy(playerX = seam.x, playerZ = seam.z), base)
        assertFalse(oreHit.actionSucceeded)
        assertTrue(oreHit.message.contains("Примитивную кирку"))
    }

    @Test
    fun `fallen branches and pebbles are gatherable by hand`() {
        val base = BaseProgress()
        val run0 = SurvivalEngine.createNewRun(base)

        val branch = run0.currentLocationNodes().first { it.kind == WorldObjectKind.FALLEN_BRANCH }
        val branchHit = SurvivalEngine.gatherNearest(run0.copy(playerX = branch.x, playerZ = branch.z), base)
        assertTrue(branchHit.actionSucceeded)
        assertTrue(branchHit.run.inventory.countOf(ResourceType.WOOD) >= 1)

        val pebble = run0.currentLocationNodes().first { it.kind == WorldObjectKind.PEBBLES }
        val pebbleHit = SurvivalEngine.gatherNearest(run0.copy(playerX = pebble.x, playerZ = pebble.z), base)
        assertTrue(pebbleHit.actionSucceeded)
        assertTrue(pebbleHit.run.inventory.countOf(ResourceType.STONE) >= 1)
    }

    @Test
    fun `primitive axe and pickaxe craft from hand-gathered sticks and pebbles`() {
        val base0 = BaseProgress()
        val run0 = SurvivalEngine.createNewRun(base0)

        // верстак 1-го уровня ставится на старте базы (первый уровень бесплатен)
        val bench = SurvivalEngine.upgradeBaseStructure(run0, base0, BaseUpgradeType.WORKBENCH)
        assertTrue(bench.actionSucceeded)
        assertEquals(1, bench.base.workbenchLevel)

        val stocked = bench.run.copy(
            inventory = Inventory()
                .tryAddResource(ResourceType.WOOD, 6).inventory
                .tryAddResource(ResourceType.STONE, 6).inventory
        )

        val axe = SurvivalEngine.craftAtWorkbench(stocked, bench.base, WorkbenchRecipe.CRAFT_PRIMITIVE_AXE)
        assertTrue(axe.actionSucceeded)
        assertEquals(EquippedTool.AXE, axe.run.equippedTool)

        // с топором большое дерево уже поддаётся
        val pine = axe.run.currentLocationNodes().first { it.kind == WorldObjectKind.PINE_TREE }
        val chop = SurvivalEngine.gatherNearest(axe.run.copy(playerX = pine.x, playerZ = pine.z), axe.base)
        assertTrue(chop.actionSucceeded)

        // камень по-прежнему закрыт до кирки
        val outcrop = axe.run.currentLocationNodes().first { it.kind == WorldObjectKind.STONE_OUTCROP }
        val bare = SurvivalEngine.gatherNearest(axe.run.copy(playerX = outcrop.x, playerZ = outcrop.z), axe.base)
        assertFalse(bare.actionSucceeded)

        val pick = SurvivalEngine.craftAtWorkbench(chop.run, chop.base, WorkbenchRecipe.CRAFT_PRIMITIVE_PICKAXE)
        assertTrue(pick.actionSucceeded)
        assertEquals(EquippedTool.PICKAXE, pick.run.equippedTool)
        val dig = SurvivalEngine.gatherNearest(
            pick.run.copy(playerX = outcrop.x, playerZ = outcrop.z),
            pick.base,
            withToolStrikeBonus = true
        )
        assertTrue(dig.actionSucceeded)
    }
}
