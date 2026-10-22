package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.RunPhase
import com.ayaks.winterforest.domain.model.SpecialLootId
import com.ayaks.winterforest.domain.model.WorkbenchRecipe
import com.ayaks.winterforest.domain.rules.SurvivalEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationsAndSpecialLootTest {

    @Test
    fun `all five locations exist with unique atmospheres and unique special loot`() {
        assertEquals(5, LocationId.entries.size)
        val uniqueLoots = LocationId.entries.map { it.uniqueLoot }.toSet()
        assertEquals("У каждой локации должен быть свой уникальный спецлут", 5, uniqueLoots.size)

        assertEquals(SpecialLootId.OLD_MAP, LocationId.BASE_CLEARING.uniqueLoot)
        assertEquals(SpecialLootId.RESIN_TORCH, LocationId.DENSE_SPRUCE.uniqueLoot)
        assertEquals(SpecialLootId.THERMOS, LocationId.FROZEN_LAKE.uniqueLoot)
        assertEquals(SpecialLootId.PICKAXE, LocationId.ABANDONED_MINE.uniqueLoot)
        assertEquals(SpecialLootId.WARM_COAT, LocationId.HUNTERS_CABIN.uniqueLoot)
    }

    @Test
    fun `each location enforces exact unlock requirement and rejects premature travel`() {
        val base = BaseProgress()
        val initialRun = SurvivalEngine.createNewRun(base)

        // 1. Базовая поляна открыта со старта
        assertTrue(SurvivalEngine.evaluateLocationUnlock(LocationId.BASE_CLEARING, initialRun, base).isUnlocked)

        // 2. Густой ельник: закрыт при 19 дерева, открывается автоматически при 20 дерева
        assertFalse(
            SurvivalEngine.travelToLocation(
                initialRun.copy(runWoodGathered = 19),
                base,
                LocationId.DENSE_SPRUCE
            ).actionSucceeded
        )
        assertTrue(
            SurvivalEngine.travelToLocation(
                initialRun.copy(runWoodGathered = 20),
                base,
                LocationId.DENSE_SPRUCE
            ).actionSucceeded
        )

        // 3. Замёрзшее озеро: закрыто на ур. 2, открывается на ур. 3
        assertFalse(
            SurvivalEngine.travelToLocation(initialRun.copy(level = 2), base, LocationId.FROZEN_LAKE).actionSucceeded
        )
        assertTrue(
            SurvivalEngine.travelToLocation(initialRun.copy(level = 3), base, LocationId.FROZEN_LAKE).actionSucceeded
        )

        // 4. Заброшенная шахта: требует «Старую карту» + 10 камня
        val stoneOnlyRun = initialRun.copy(
            inventory = initialRun.inventory.tryAddResource(ResourceType.STONE, 10).inventory
        )
        assertFalse(
            "Без Старой карты шахта закрыта",
            SurvivalEngine.travelToLocation(stoneOnlyRun, base, LocationId.ABANDONED_MINE).actionSucceeded
        )
        val baseWithMap = base.copy(persistentSpecialLoot = setOf(SpecialLootId.OLD_MAP))
        val mineOpened = SurvivalEngine.travelToLocation(stoneOnlyRun, baseWithMap, LocationId.ABANDONED_MINE)
        assertTrue("Со Старой картой и 10 камнями вход в шахту открывается", mineOpened.actionSucceeded)
        assertEquals(LocationId.ABANDONED_MINE, mineOpened.run.currentLocation)

        // 5. Охотничий домик: требует 3 выжитых дня и 5 ткани
        val clothRun = initialRun.copy(
            daysSurvived = 2,
            inventory = initialRun.inventory.tryAddResource(ResourceType.CLOTH, 5).inventory
        )
        assertFalse(SurvivalEngine.travelToLocation(clothRun, base, LocationId.HUNTERS_CABIN).actionSucceeded)
        assertTrue(
            SurvivalEngine.travelToLocation(
                clothRun.copy(daysSurvived = 3),
                base,
                LocationId.HUNTERS_CABIN
            ).actionSucceeded
        )
    }

    @Test
    fun `permanent relics are singleton and survive death while consumable resin torch resets`() {
        var base = BaseProgress()
        var run = SurvivalEngine.createNewRun(base)

        // Подбираем «Старую карту» на Базовой поляне дважды (проверяем единственность = 1)
        val mapCache = run.currentLocationNodes().first { it.specialLootId == SpecialLootId.OLD_MAP }
        val gatherMap = SurvivalEngine.gatherNearest(run.copy(playerX = mapCache.x, playerZ = mapCache.z), base)
        assertTrue(gatherMap.actionSucceeded)
        run = gatherMap.run.copy(
            inventory = gatherMap.run.inventory.grantSpecialLoot(SpecialLootId.OLD_MAP, 3)
        )
        base = gatherMap.base
        assertEquals("Постоянный спецпредмет не может иметь количество > 1", 1, run.inventory.specialCount(SpecialLootId.OLD_MAP))

        // Крафтим два «Смоляных факела» на верстаке и добавляем постоянные находки Термос, Кирка, Шуба
        run = run.copy(
            inventory = run.inventory
                .tryAddResource(ResourceType.WOOD, 10).inventory
                .tryAddResource(ResourceType.STONE, 4).inventory
                .tryAddResource(ResourceType.RESIN, 6).inventory
                .grantSpecialLoot(SpecialLootId.THERMOS, 1)
                .grantSpecialLoot(SpecialLootId.PICKAXE, 1)
                .grantSpecialLoot(SpecialLootId.WARM_COAT, 1)
        )
        base = base.copy(
            persistentSpecialLoot = setOf(
                SpecialLootId.OLD_MAP,
                SpecialLootId.THERMOS,
                SpecialLootId.PICKAXE,
                SpecialLootId.WARM_COAT
            ),
            warmCoatEquipped = true
        )

        val craft1 = SurvivalEngine.craftAtWorkbench(run, base, WorkbenchRecipe.CRAFT_RESIN_TORCH)
        val craft2 = SurvivalEngine.craftAtWorkbench(craft1.run, craft1.base, WorkbenchRecipe.CRAFT_RESIN_TORCH)
        assertTrue(craft2.actionSucceeded)
        assertEquals(2, craft2.run.inventory.specialCount(SpecialLootId.RESIN_TORCH))

        // Устанавливаем Термос на базе (-15% потери тепла) и проверяем снижение потери тепла вместе с Шубой (-25%)
        val thermosInstalled = SurvivalEngine.installThermosOnBase(craft2.run, craft2.base)
        assertTrue(thermosInstalled.actionSucceeded)
        assertTrue(thermosInstalled.base.thermosInstalledOnBase)
        assertEquals(0.85f * 0.75f, thermosInstalled.base.warmthLossMultiplier(), 0.001f)

        // Персонаж умирает -> постоянные находки сохраняются, Смоляной факел сбрасывается
        val fatalOutcome = SurvivalEngine.tick(
            thermosInstalled.run.copy(health = 1f, hunger = 0f, warmth = 0f, playerX = 20f, playerZ = 20f),
            thermosInstalled.base,
            deltaSeconds = 10f
        )
        assertEquals(RunPhase.DEAD, fatalOutcome.run.phase)
        assertEquals(
            setOf(
                SpecialLootId.OLD_MAP,
                SpecialLootId.THERMOS,
                SpecialLootId.PICKAXE,
                SpecialLootId.WARM_COAT
            ),
            fatalOutcome.base.persistentSpecialLoot
        )
        assertEquals(
            "Расходуемый Смоляной факел обязан сбрасываться при смерти",
            0,
            fatalOutcome.run.inventory.specialCount(SpecialLootId.RESIN_TORCH)
        )

        // Новый забег наследует все 4 постоянных спецпредмета и постройку Термоса
        val nextRun = SurvivalEngine.createNewRun(fatalOutcome.base)
        assertTrue(nextRun.inventory.hasSpecial(SpecialLootId.OLD_MAP))
        assertTrue(nextRun.inventory.hasSpecial(SpecialLootId.THERMOS))
        assertTrue(nextRun.inventory.hasSpecial(SpecialLootId.PICKAXE))
        assertTrue(nextRun.inventory.hasSpecial(SpecialLootId.WARM_COAT))
        assertFalse(nextRun.inventory.hasSpecial(SpecialLootId.RESIN_TORCH))
    }
}
