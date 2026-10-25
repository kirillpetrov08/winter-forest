package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.BaseUpgradeType
import com.ayaks.winterforest.domain.model.GameRunState
import com.ayaks.winterforest.domain.model.Inventory
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.WorldObjectKind
import com.ayaks.winterforest.domain.model.StarterLoadout
import com.ayaks.winterforest.domain.rules.SurvivalEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeCasesTest {

    @Test
    fun `using missing resource in empty inventory cancels cleanly without crash`() {
        val base = BaseProgress()
        val emptyRun = SurvivalEngine.createNewRun(base).copy(inventory = Inventory())

        val berryAttempt = SurvivalEngine.useResource(emptyRun, base, ResourceType.BERRIES)
        assertFalse(berryAttempt.actionSucceeded)
        assertTrue(berryAttempt.message.contains("Нет ресурса"))

        val torchAttempt = SurvivalEngine.igniteResinTorch(emptyRun, base)
        assertFalse(torchAttempt.actionSucceeded)

        val quickUseAttempt = SurvivalEngine.quickUseBestSurvivalItem(emptyRun, base)
        assertFalse(quickUseAttempt.actionSucceeded)
    }

    @Test
    fun `full 20 slot inventory blocks gathering without consuming world node`() {
        val base = BaseProgress()
        var fullInv = Inventory()
        repeat(Inventory.MAX_SLOTS) {
            fullInv = fullInv.tryAddResource(ResourceType.STONE, Inventory.MAX_STACK_SIZE).inventory
        }
        assertEquals(20, fullInv.occupiedSlots)
        assertTrue(fullInv.isFull)

        val initial = SurvivalEngine.createNewRun(base)
        // §7.11: большое дерево без топора не добывается вовсе, поэтому для
        // проверки полного инвентаря берём ручной узел — валежник
        val woodNode = initial.currentLocationNodes()
            .first { it.resourceType == ResourceType.WOOD && it.kind == WorldObjectKind.FALLEN_BRANCH }
        val fullRun = initial.copy(
            inventory = fullInv,
            playerX = woodNode.x,
            playerZ = woodNode.z
        )

        val outcome = SurvivalEngine.gatherNearest(fullRun, base)
        assertFalse(outcome.actionSucceeded)
        assertTrue(outcome.message.contains("Инвентарь полон"))
        assertTrue(
            "Узел не должен исчезать, если предмет не поместился в инвентарь",
            outcome.run.currentLocationNodes().first { it.id == woodNode.id }.isAvailable
        )
    }

    @Test
    fun `insufficient resources reject base upgrade cleanly and preserve levels`() {
        val base = BaseProgress(shelterLevel = 1)
        val emptyRun = SurvivalEngine.createNewRun(base).copy(inventory = Inventory())

        val outcome = SurvivalEngine.upgradeBaseStructure(emptyRun, base, BaseUpgradeType.SHELTER)
        assertFalse(outcome.actionSucceeded)
        assertEquals(1, outcome.base.shelterLevel)
        assertTrue(outcome.message.contains("Недостаточно ресурсов"))
    }

    @Test
    fun `world border clamps player strictly within bounds and notifies user`() {
        val base = BaseProgress()
        val nearEdge = SurvivalEngine.createNewRun(base).copy(
            playerX = 162.5f,
            playerZ = -162.5f,
            yawDeg = 90f
        )

        val moved = SurvivalEngine.movePlayer(
            run = nearEdge,
            forwardInput = 1f,
            strafeInput = -1f,
            deltaSeconds = 10f
        )
        assertTrue(moved.playerX <= GameRunState.WORLD_HALF_BOUND)
        assertTrue(moved.playerZ >= -GameRunState.WORLD_HALF_BOUND)
        assertTrue(moved.lastStatusMessage.contains("Граница"))
    }

    @Test
    fun `location generation failure leaves player safely in previous zone without crash`() {
        val base = BaseProgress(lifetimeWoodGathered = 30)
        val run = SurvivalEngine.createNewRun(StarterLoadout.SURVIVOR.let { base })

        val failedTravel = SurvivalEngine.travelToLocation(
            run = run,
            base = base,
            destination = LocationId.DENSE_SPRUCE,
            simulateGenerationError = true
        )
        assertFalse(failedTravel.actionSucceeded)
        assertEquals(LocationId.BASE_CLEARING, failedTravel.run.currentLocation)
        assertTrue(failedTravel.message.contains("Не удалось сгенерировать"))
    }
}
