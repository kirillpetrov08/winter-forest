package com.ayaks.winterforest

import com.ayaks.winterforest.data.db.SavePayloadCodec
import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.EquippedTool
import com.ayaks.winterforest.domain.model.GameRunState
import com.ayaks.winterforest.domain.model.HotbarEntry
import com.ayaks.winterforest.domain.model.Inventory
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.WorkbenchRecipe
import com.ayaks.winterforest.domain.model.WorldObjectKind
import com.ayaks.winterforest.domain.rules.SurvivalEngine
import com.ayaks.winterforest.domain.world.WorldPopulator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Инструменты — отдельные предметы рюкзака: крафт кладёт предмет в инвентарь,
 * его можно перенести в хотбар, экипировать и убрать. Смола больше не лежит
 * на земле: она автоматически выпадает при срубе дерева.
 */
class ToolItemsTest {

    private fun stockedRun(): GameRunState {
        val inv = Inventory()
            .tryAddResource(ResourceType.WOOD, 8).inventory
            .tryAddResource(ResourceType.STONE, 6).inventory
        return SurvivalEngine.createNewRun(BaseProgress()).copy(inventory = inv)
    }

    @Test
    fun `crafted axe becomes inventory item and can be equipped and put away`() {
        val out = SurvivalEngine.craftAtWorkbench(
            stockedRun(), BaseProgress(), WorkbenchRecipe.CRAFT_PRIMITIVE_AXE
        )
        assertTrue(out.message, out.actionSucceeded)
        assertTrue("топор не лёг в рюкзак предметом", out.run.inventory.hasTool(EquippedTool.AXE))
        assertEquals("после крафта инструмент НЕ берётся в руки автоматически", EquippedTool.NONE, out.run.equippedTool)

        val uneq = SurvivalEngine.equipToolItem(out.run, out.base, EquippedTool.NONE)
        assertTrue(uneq.actionSucceeded)
        assertEquals(EquippedTool.NONE, uneq.run.equippedTool)
        assertTrue("предмет пропал из рюкзака", uneq.run.inventory.hasTool(EquippedTool.AXE))

        val eq = SurvivalEngine.equipToolItem(uneq.run, uneq.base, EquippedTool.AXE)
        assertTrue(eq.actionSucceeded)
        assertEquals(EquippedTool.AXE, eq.run.equippedTool)
    }

    @Test
    fun `cannot equip a tool you do not own`() {
        val out = SurvivalEngine.equipToolItem(stockedRun(), BaseProgress(), EquippedTool.PICKAXE)
        assertFalse(out.actionSucceeded)
        assertTrue(out.message.contains("нет"))
        assertEquals(EquippedTool.NONE, out.run.equippedTool)
    }

    @Test
    fun `tool can be carried in hotbar slot`() {
        val run = stockedRun()
        val entry = HotbarEntry(
            titleRu = EquippedTool.AXE.titleRu,
            iconPath = "",
            tool = EquippedTool.AXE
        )
        val next = run.withHotbarSlot(2, entry)
        assertEquals(EquippedTool.AXE, next.hotbarAt(2)?.tool)
        assertTrue(next.hotbarAt(2)!!.isTool)
    }

    @Test
    fun `chopping a tree drops resin into inventory automatically`() {
        var run = stockedRun()
        run = run.copy(
            inventory = run.inventory.grantTool(EquippedTool.AXE),
            equippedTool = EquippedTool.AXE
        )
        val pine = run.currentLocationNodes()
            .firstOrNull { it.kind == WorldObjectKind.PINE_TREE && it.isAvailable }
        assertNotNull("в стартовом районе нет ели", pine)
        run = run.copy(playerX = pine!!.x + 0.6f, playerZ = pine.z + 0.6f)
        val resinBefore = run.inventory.countOf(ResourceType.RESIN)
        val woodBefore = run.inventory.countOf(ResourceType.WOOD)

        val out = SurvivalEngine.gatherNearest(run, BaseProgress(), withToolStrikeBonus = true)
        assertTrue(out.message, out.actionSucceeded)
        assertTrue(out.run.inventory.countOf(ResourceType.WOOD) > woodBefore)
        assertEquals(resinBefore + 1, out.run.inventory.countOf(ResourceType.RESIN))
    }

    @Test
    fun `world has no resin stumps and plenty of hand-gathered stones and branches`() {
        val nodes = WorldPopulator.generateAllLocations()
        assertTrue(
            "на карте остались смоляные пни",
            nodes.values.flatten().none { it.kind == WorldObjectKind.RESIN_STUMP }
        )
        for ((loc, list) in nodes) {
            val hand = list.count {
                it.kind == WorldObjectKind.PEBBLES || it.kind == WorldObjectKind.FALLEN_BRANCH
            }
            assertTrue("${loc.name}: мелкого ручного материала мало ($hand)", hand >= 20)
        }
    }

    @Test
    fun `legacy save with equipped tool migrates it into inventory`() {
        val run = stockedRun().copy(equippedTool = EquippedTool.PICKAXE)
        // encodeDefaults = true: поле tools (пустое по умолчанию) должно попасть
        // в JSON, чтобы его вырезание честно имитировало старый сейв.
        val body = Json { encodeDefaults = true }
            .encodeToString(GameRunState.serializer(), run)
        val legacy = body.replace(Regex("\"tools\":\\{[^}]*\\},?"), "")
        assertTrue("поле tools не вырезалось — тест не проверяет старый сейв", legacy != body)
        val crc = SavePayloadCodec.computeCrc32(legacy)
        val decoded = SavePayloadCodec.decodeRunState(legacy, crc)
        assertTrue(
            "миграция не вернула инструмент в рюкзак",
            decoded.inventory.hasTool(EquippedTool.PICKAXE)
        )
    }
}
