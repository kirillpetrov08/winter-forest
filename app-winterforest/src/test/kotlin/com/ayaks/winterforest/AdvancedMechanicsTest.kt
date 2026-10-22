package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.WorldObjectKind
import com.ayaks.winterforest.domain.rules.AdvancedMechanics
import com.ayaks.winterforest.domain.rules.DailyChallengeId
import com.ayaks.winterforest.domain.rules.PerkId
import com.ayaks.winterforest.domain.rules.SurvivalEngine
import com.ayaks.winterforest.domain.rules.WeatherEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedMechanicsTest {

    @Test
    fun `weather is deterministic and blizzard multiplies cold`() {
        val w0 = AdvancedMechanics.weatherFor(0f)
        assertEquals(w0, AdvancedMechanics.weatherFor(0f))
        assertEquals(1.8f, AdvancedMechanics.coldMultiplierFor(WeatherEvent.BLIZZARD), 0.001f)
        assertTrue(AdvancedMechanics.coldMultiplierFor(WeatherEvent.THAW) < 1f)
    }

    @Test
    fun `perks unlock with skill points and apply effects`() {
        var base = BaseProgress(perkPoints = 1)
        assertTrue(AdvancedMechanics.canUnlockPerk(base, PerkId.GATHERER))
        base = AdvancedMechanics.unlockPerk(base, PerkId.GATHERER)
        assertFalse(AdvancedMechanics.canUnlockPerk(base, PerkId.HUNTER))
        assertEquals(1, AdvancedMechanics.gatherBonus(SurvivalEngine.createNewRun(base), base))
        assertEquals(1f, AdvancedMechanics.hunterDamageMultiplier(base), 0.001f)
        assertEquals(1.5f, AdvancedMechanics.hunterDamageMultiplier(BaseProgress(perks = setOf(PerkId.HUNTER))), 0.001f)
        assertEquals(1f, AdvancedMechanics.hunterDamageMultiplier(BaseProgress()), 0.001f)
    }

    @Test
    fun `fishing works at ice hole and fails away from it`() {
        val base = BaseProgress()
        val run = SurvivalEngine.createNewRun(base).copy(currentLocation = LocationId.FROZEN_LAKE)
        val hole = run.currentLocationNodes().first { it.kind == WorldObjectKind.ICE_HOLE }
        val atHole = run.copy(playerX = hole.x, playerZ = hole.z, stamina = 60f)
        val caught = AdvancedMechanics.fishAtHole(atHole, base)
        assertTrue(caught.actionSucceeded)
        assertTrue(caught.run.inventory.countOf(ResourceType.FISH) >= 1)
        val away = run.copy(playerX = 30f, playerZ = 30f)
        assertFalse(AdvancedMechanics.fishAtHole(away, base).actionSucceeded)
    }

    @Test
    fun `snow shelter reduces cold and covers player`() {
        val base = BaseProgress()
        val run = SurvivalEngine.createNewRun(base).copy(
            inventory = SurvivalEngine.createNewRun(base).inventory
                .tryAddResource(ResourceType.WOOD, 6).inventory
                .tryAddResource(ResourceType.CLOTH, 4).inventory
        )
        val placed = AdvancedMechanics.placeSnowShelter(run, base)
        assertTrue(placed.actionSucceeded)
        val shelter = placed.run.shelterAt!!
        assertTrue(shelter.covers(placed.run.playerX, placed.run.playerZ))
        // под укрытием теплее, чем в чистом поле
        val outside = placed.run.copy(playerX = 40f, playerZ = 40f, warmth = 80f, elapsedRealSeconds = 10f)
        val inside = placed.run.copy(warmth = 80f, elapsedRealSeconds = 10f)
        val outWarmth = SurvivalEngine.tick(outside, base, deltaSeconds = 20f, isMoving = false).run.warmth
        val inWarmth = SurvivalEngine.tick(inside, base, deltaSeconds = 20f, isMoving = false).run.warmth
        assertTrue("Под укрытием должно быть теплее", inWarmth > outWarmth)
    }

    @Test
    fun `daily challenges track progress and grant reward`() {
        var base = AdvancedMechanics.syncDaily(BaseProgress(), dayIndex = 3)
        assertEquals(3, base.dailyDayIndex)
        val ch = AdvancedMechanics.currentDailySet(3).firstOrNull { it.kind == DailyChallengeId.Kind.GATHER_WOOD }
            ?: AdvancedMechanics.currentDailySet(3).first()
        var progressed = base
        repeat(ch.target) {
            progressed = AdvancedMechanics.trackChallenge(progressed, ch.kind, 1)
        }
        assertTrue(AdvancedMechanics.isChallengeComplete(progressed, ch))
        val claimed = AdvancedMechanics.claimChallenge(progressed, ch)
        assertTrue(ch.name in claimed.dailyClaimed)
        // новый день сбрасывает прогресс
        val nextDay = AdvancedMechanics.syncDaily(claimed, dayIndex = 4)
        assertTrue(nextDay.dailyProgress.isEmpty())
    }

    @Test
    fun `travel times link all locations in both directions`() {
        val t = AdvancedMechanics.TRAVEL_TIMES
        for ((key, sec) in t) {
            assertEquals(sec, t[key.second to key.first]!!, 0.001f)
            assertTrue(sec in 10f..120f)
        }
        assertTrue(t.containsKey(LocationId.BASE_CLEARING to LocationId.DENSE_SPRUCE))
    }
}
