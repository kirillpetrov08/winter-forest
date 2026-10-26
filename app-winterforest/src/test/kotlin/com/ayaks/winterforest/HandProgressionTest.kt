package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.BaseProgress
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

        // на Базовой поляне нет жилы: гейт камня проверяем на выходке породы
        val outcrop0 = run0.currentLocationNodes().first { it.kind == WorldObjectKind.STONE_OUTCROP }
        val oreHit = SurvivalEngine.gatherNearest(run0.copy(playerX = outcrop0.x, playerZ = outcrop0.z), base)
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

        // верстак 1-го уровня есть у базы с самого начала — примитивные
        // инструменты крафтятся сразу из собранных руками палок и гальки
        assertEquals(1, base0.workbenchLevel)

        val stocked = run0.copy(
            inventory = Inventory()
                .tryAddResource(ResourceType.WOOD, 6).inventory
                .tryAddResource(ResourceType.STONE, 6).inventory
        )

        val axe = SurvivalEngine.craftAtWorkbench(stocked, base0, WorkbenchRecipe.CRAFT_PRIMITIVE_AXE)
        assertTrue(axe.actionSucceeded)
        // крафт больше не берёт инструмент в руки автоматически: предмет лёг в рюкзак
        assertEquals(EquippedTool.NONE, axe.run.equippedTool)
        assertTrue("топор не лёг в рюкзак предметом", axe.run.inventory.hasTool(EquippedTool.AXE))
        val tookAxe = SurvivalEngine.equipToolItem(axe.run, axe.base, EquippedTool.AXE)
        assertTrue(tookAxe.actionSucceeded)
        assertEquals(EquippedTool.AXE, tookAxe.run.equippedTool)

        // с топором большое дерево уже поддаётся
        val pine = tookAxe.run.currentLocationNodes().first { it.kind == WorldObjectKind.PINE_TREE }
        val chop = SurvivalEngine.gatherNearest(tookAxe.run.copy(playerX = pine.x, playerZ = pine.z), tookAxe.base)
        assertTrue(chop.actionSucceeded)

        // камень по-прежнему закрыт до кирки
        val outcrop = tookAxe.run.currentLocationNodes().first { it.kind == WorldObjectKind.STONE_OUTCROP }
        val bare = SurvivalEngine.gatherNearest(tookAxe.run.copy(playerX = outcrop.x, playerZ = outcrop.z), tookAxe.base)
        assertFalse(bare.actionSucceeded)

        val pick = SurvivalEngine.craftAtWorkbench(chop.run, chop.base, WorkbenchRecipe.CRAFT_PRIMITIVE_PICKAXE)
        assertTrue(pick.actionSucceeded)
        assertEquals(EquippedTool.NONE, pick.run.equippedTool)
        assertTrue("кирка не легла в рюкзак предметом", pick.run.inventory.hasTool(EquippedTool.PICKAXE))
        val tookPick = SurvivalEngine.equipToolItem(pick.run, pick.base, EquippedTool.PICKAXE)
        assertTrue(tookPick.actionSucceeded)
        assertEquals(EquippedTool.PICKAXE, tookPick.run.equippedTool)
        val dig = SurvivalEngine.gatherNearest(
            tookPick.run.copy(playerX = outcrop.x, playerZ = outcrop.z),
            tookPick.base,
            withToolStrikeBonus = true
        )
        assertTrue(dig.actionSucceeded)
    }
}
