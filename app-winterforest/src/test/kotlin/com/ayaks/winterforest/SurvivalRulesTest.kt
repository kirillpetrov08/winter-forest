package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.GameRunState
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.RunPhase
import com.ayaks.winterforest.domain.rules.SurvivalEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurvivalRulesTest {

    @Test
    fun `hunger decreases by 1 unit every 26 seconds of gameplay`() {
        val base = BaseProgress()
        val initial = SurvivalEngine.createNewRun(base).copy(
            playerX = 12f,
            playerZ = 12f // вдали от костра
        )

        val after30s = SurvivalEngine.tick(initial, base, deltaSeconds = 30f).run
        assertEquals(100f - 30f / 26f, after30s.hunger, 0.01f)

        val after150s = SurvivalEngine.tick(after30s, base, deltaSeconds = 120f).run
        assertEquals(100f - 150f / 26f, after150s.hunger, 0.01f)
    }

    @Test
    fun `starvation at zero hunger reduces health by 1 every 3 seconds`() {
        val base = BaseProgress()
        val starving = SurvivalEngine.createNewRun(base).copy(
            hunger = 0f,
            warmth = 80f,
            health = 90f,
            playerX = 0f,
            playerZ = 0f // у костра, чтобы тепло оставалось > 60 и не накладывался холод
        )

        val after25s = SurvivalEngine.tick(starving, base, deltaSeconds = 25f).run
        assertEquals(0f, after25s.hunger, 0.001f)
        assertEquals(90f - 25f / 3f, after25s.health, 0.02f)
    }

    @Test
    fun `hypothermia at zero warmth reduces health by 1 every 2 seconds`() {
        val base = BaseProgress()
        val freezing = SurvivalEngine.createNewRun(base).copy(
            hunger = 90f,
            warmth = 0f,
            health = 90f,
            playerX = 20f,
            playerZ = 20f
        )

        val after15s = SurvivalEngine.tick(freezing, base, deltaSeconds = 15f).run
        assertEquals(0f, after15s.warmth, 0.001f)
        assertEquals(90f - 15f / 2f, after15s.health, 0.02f)
    }

    @Test
    fun `warmth drops faster at night and while moving and recovers near base campfire`() {
        val base = BaseProgress(campfireLevel = 2)
        val dayIdle = SurvivalEngine.createNewRun(base).copy(
            playerX = 18f,
            playerZ = 18f,
            elapsedRealSeconds = 10f, // день (0..360 с)
            warmth = 90f
        )
        val nightMoving = dayIdle.copy(
            elapsedRealSeconds = 400f // ночь (360..600 с)
        )

        val afterDayIdle = SurvivalEngine.tick(dayIdle, base, deltaSeconds = 10f, isMoving = false).run
        val afterNightMoving = SurvivalEngine.tick(nightMoving, base, deltaSeconds = 10f, isMoving = true).run
        val lossDay = 90f - afterDayIdle.warmth
        val lossNightMove = 90f - afterNightMoving.warmth
        assertTrue("Ночью и в движении потеря тепла должна быть выше", lossNightMove > lossDay * 1.8f)

        val nearFire = afterNightMoving.copy(playerX = 0f, playerZ = 2f)
        val warmed = SurvivalEngine.tick(nearFire, base, deltaSeconds = 5f).run
        assertTrue("У костра на базе тепло должно расти", warmed.warmth > nearFire.warmth)
    }

    @Test
    fun `passive health regen works only near fire with high hunger and warmth and death triggers at zero hp`() {
        val base = BaseProgress()
        // У костра, сытый и тёплый — медленный реген 0.12 hp/с
        val nearFire = SurvivalEngine.createNewRun(base).copy(
            health = 70f,
            hunger = 80f,
            warmth = 80f,
            playerX = 0f,
            playerZ = 0f
        )
        val regenerated = SurvivalEngine.tick(nearFire, base, deltaSeconds = 10f).run
        assertEquals(70f + 0.12f * 10f, regenerated.health, 0.05f)

        // Без огня даже сытый и тёплый НЕ восстанавливает здоровье
        val awayFromFire = nearFire.copy(playerX = 20f, playerZ = 20f, health = 70f)
        val noRegen = SurvivalEngine.tick(awayFromFire, base, deltaSeconds = 10f).run
        assertTrue("Без костра регенерации быть не должно", noRegen.health <= 70.01f)

        val critical = nearFire.copy(
            health = 2f,
            hunger = 0f,
            warmth = 0f,
            playerX = 20f,
            playerZ = 20f
        )
        val deadOutcome = SurvivalEngine.tick(critical, base, deltaSeconds = 10f)
        assertEquals(RunPhase.DEAD, deadOutcome.run.phase)
        assertEquals(0f, deadOutcome.run.health, 0.001f)
        assertEquals(1, deadOutcome.run.deathsInRun)
    }

    @Test
    fun `entering night restores depleted world resource nodes automatically`() {
        val base = BaseProgress()
        val initial = SurvivalEngine.createNewRun(base)
        val firstNode = initial.currentLocationNodes().first { it.resourceType != null }
        val nearNode = initial.copy(
            playerX = firstNode.x,
            playerZ = firstNode.z,
            elapsedRealSeconds = 358f // за 2 секунды до наступления 4-минутной ночи (360 с)
        )
        val gathered = SurvivalEngine.gatherNearest(nearNode, base).run
        assertFalse(gathered.currentLocationNodes().first { it.id == firstNode.id }.isAvailable)

        val afterNightfall = SurvivalEngine.tick(gathered, base, deltaSeconds = 5f).run
        assertTrue(afterNightfall.isNight)
        assertTrue(
            "При наступлении ночи истощённые узлы должны восстанавливаться",
            afterNightfall.currentLocationNodes().first { it.id == firstNode.id }.isAvailable
        )
    }
}
