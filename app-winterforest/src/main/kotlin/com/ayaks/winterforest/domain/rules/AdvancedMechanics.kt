package com.ayaks.winterforest.domain.rules

import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.EquippedTool
import com.ayaks.winterforest.domain.model.GameRunState
import com.ayaks.winterforest.domain.model.Inventory
import com.ayaks.winterforest.domain.model.ResourceNode
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.SpecialLootId
import com.ayaks.winterforest.domain.model.WildlifeEntity
import com.ayaks.winterforest.domain.model.WildlifeKind
import com.ayaks.winterforest.domain.model.WorldObjectKind
import kotlinx.serialization.Serializable
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Расширенные механики выживания (обнова «как Rust»):
 *  1) пешие переходы между соседними локациями (без обязательного телепорта),
 *  2) рыбалка на лунках,
 *  3) дерево навыков (перки за уровни),
 *  4) ежедневные испытания,
 *  5) погодные события (метель/оттепель/ясно),
 *  6) ночные набеги волков и сияние,
 *  7) снежные укрытия (ветрозащита).
 */
@Serializable
enum class PerkId(val titleRu: String, val descriptionRu: String) {
    WARM_BLOODED("Теплокровный", "Холод действует на 15% слабее"),
    GATHERER("Собиратель", "+1 к добыче ресурсов"),
    MARATHONER("Марафонец", "Спринт тратит на 30% меньше сил"),
    HUNTER("Охотник", "Урон зверю и добыча мяса +50%"),
    SURVIVOR("Выживальщик", "У огня регенерация вдвое быстрее"),
    FISHERMAN("Рыболов", "Рыбалка приносит +1 рыбу и реже пустует")
}

@Serializable
enum class WeatherEvent(val titleRu: String) {
    CLEAR("Ясно"),
    THAW("Оттепель"),
    BLIZZARD("Метель")
}

@Serializable
enum class DailyChallengeId(val titleRu: String, val target: Int, val rewardXp: Int, val kind: Kind) {
    CHOP_WOOD("Наколоть 25 дерева", 25, 120, Kind.GATHER_WOOD),
    GATHER_STONE("Добыть 15 камня", 15, 110, Kind.GATHER_STONE),
    CATCH_FISH("Поймать 6 рыбин", 6, 140, Kind.FISH),
    CRAFT_ITEM("Создать 3 предмета", 3, 130, Kind.CRAFT),
    SURVIVE_NIGHT("Пережить ночь без голода", 1, 150, Kind.SURVIVE_NIGHT),
    KILL_WOLF("Отбить 2 волков", 2, 160, Kind.KILL_WOLF);

    enum class Kind { GATHER_WOOD, GATHER_STONE, FISH, CRAFT, SURVIVE_NIGHT, KILL_WOLF }
}

@Serializable
data class ShelterSite(
    val x: Float,
    val z: Float,
    val remainingSec: Float = GameRunState.SHELTER_DURATION_SECONDS
) {
    fun covers(px: Float, pz: Float): Boolean {
        val dx = px - x
        val dz = pz - z
        return dx * dx + dz * dz <= GameRunState.SHELTER_RADIUS_SQ
    }
}

object AdvancedMechanics {

    /** Соседство локаций для пеших переходов (время пути в секундах). */
    val TRAVEL_TIMES: Map<Pair<com.ayaks.winterforest.domain.model.LocationId, com.ayaks.winterforest.domain.model.LocationId>, Float> = run {
        val base = com.ayaks.winterforest.domain.model.LocationId.BASE_CLEARING
        val dense = com.ayaks.winterforest.domain.model.LocationId.DENSE_SPRUCE
        val lake = com.ayaks.winterforest.domain.model.LocationId.FROZEN_LAKE
        val mine = com.ayaks.winterforest.domain.model.LocationId.ABANDONED_MINE
        val cabin = com.ayaks.winterforest.domain.model.LocationId.HUNTERS_CABIN
        val map = mutableMapOf<Pair<com.ayaks.winterforest.domain.model.LocationId, com.ayaks.winterforest.domain.model.LocationId>, Float>()
        fun link(a: com.ayaks.winterforest.domain.model.LocationId, b: com.ayaks.winterforest.domain.model.LocationId, sec: Float) {
            map[a to b] = sec
            map[b to a] = sec
        }
        link(base, dense, 38f)
        link(base, lake, 44f)
        link(dense, cabin, 46f)
        link(dense, mine, 58f)
        link(lake, mine, 54f)
        link(lake, cabin, 62f)
        link(mine, cabin, 50f)
        map
    }

    fun travelSeconds(from: com.ayaks.winterforest.domain.model.LocationId, to: com.ayaks.winterforest.domain.model.LocationId): Float =
        TRAVEL_TIMES[from to to] ?: 60f

    // ---------- Погода ----------

    /** Погодное событие по текущему времени (детерминированно, смена каждые 150 с). */
    fun weatherFor(elapsedSec: Float, seed: Int = 20261021): WeatherEvent {
        val slot = (elapsedSec / 150f).toInt()
        val r = Random(seed.toLong() + slot * 7919L).nextFloat()
        return when {
            r < 0.55f -> WeatherEvent.CLEAR
            r < 0.78f -> WeatherEvent.THAW
            else -> WeatherEvent.BLIZZARD
        }
    }

    fun coldMultiplierFor(event: WeatherEvent): Float = when (event) {
        WeatherEvent.CLEAR -> 1f
        WeatherEvent.THAW -> 0.82f
        WeatherEvent.BLIZZARD -> 1.8f
    }

    // ---------- Перки ----------

    fun availableSkillPoints(base: BaseProgress): Int =
        base.perkPoints - base.perks.size

    fun canUnlockPerk(base: BaseProgress, perk: PerkId): Boolean =
        perk !in base.perks && availableSkillPoints(base) > 0

    fun unlockPerk(base: BaseProgress, perk: PerkId): BaseProgress =
        if (canUnlockPerk(base, perk)) base.copy(perks = base.perks + perk) else base

    fun gatherBonus(run: GameRunState, base: BaseProgress): Int =
        if (PerkId.GATHERER in base.perks) 1 else 0

    fun coldPerkMultiplier(base: BaseProgress): Float =
        if (PerkId.WARM_BLOODED in base.perks) 0.85f else 1f

    fun sprintStaminaMultiplier(base: BaseProgress): Float =
        if (PerkId.MARATHONER in base.perks) 0.7f else 1f

    fun hunterDamageMultiplier(base: BaseProgress): Float =
        if (PerkId.HUNTER in base.perks) 1.5f else 1f

    fun regenMultiplier(base: BaseProgress): Float =
        if (PerkId.SURVIVOR in base.perks) 2f else 1f

    // ---------- Рыбалка ----------

    const val FISHING_STAMINA_COST = 15f

    fun hasIceHoleNearby(run: GameRunState): Boolean =
        run.nearestInteractableNode()?.kind == WorldObjectKind.ICE_HOLE

    fun fishAtHole(run: GameRunState, base: BaseProgress): EngineStepOutcome {
        if (!hasIceHoleNearby(run)) {
            val msg = "Подойдите ко льдовой лунке, чтобы ловить рыбу."
            return EngineStepOutcome(run.copy(lastStatusMessage = msg), base, false, msg)
        }
        if (run.stamina < FISHING_STAMINA_COST) {
            val msg = "Слишком устали для рыбалки — нужен отдых."
            return EngineStepOutcome(run.copy(lastStatusMessage = msg), base, false, msg)
        }
        val rng = Random(run.totalResourcesGathered * 31L + run.elapsedRealSeconds.toLong())
        val failChance = if (PerkId.FISHERMAN in base.perks) 0.14f else 0.30f
        val failed = rng.nextFloat() < failChance
        val hole = run.currentLocationNodes().first { it.kind == WorldObjectKind.ICE_HOLE }
        val nextNodes = run.nodesByLocation.toMutableMap()
        nextNodes[run.currentLocation] = run.currentLocationNodes().map {
            if (it.id == hole.id) it.copy(respawnRemainingSec = 25f) else it
        }
        if (failed) {
            val msg = "Поклёвка оборвалась — рыба сорвалась с крючка."
            return EngineStepOutcome(
                run.copy(
                    stamina = (run.stamina - FISHING_STAMINA_COST).coerceAtLeast(0f),
                    nodesByLocation = nextNodes,
                    lastStatusMessage = msg
                ),
                base, false, msg
            )
        }
        val amount = 1 + (if (PerkId.FISHERMAN in base.perks) 1 else 0) + (if (rng.nextFloat() < 0.3f) 1 else 0)
        val add = run.inventory.tryAddResource(ResourceType.FISH, amount)
        val (xp, lvl) = SurvivalEngine.applyXpGain(run.xp, run.level, 18 * add.addedAmount)
        val msg = "Улов: Рыба +${add.addedAmount}"
        return EngineStepOutcome(
            run.copy(
                inventory = add.inventory,
                nodesByLocation = nextNodes,
                stamina = (run.stamina - FISHING_STAMINA_COST).coerceAtLeast(0f),
                xp = xp,
                level = lvl,
                swingAnimRemainingSec = 0.9f,
                lastStatusMessage = msg
            ),
            trackChallenge(base, DailyChallengeId.Kind.FISH, 1), true, msg
        )
    }

    // ---------- Снежное укрытие ----------

    fun canPlaceShelter(run: GameRunState): Boolean =
        run.inventory.countOf(ResourceType.WOOD) >= GameRunState.SHELTER_WOOD_COST &&
            run.inventory.countOf(ResourceType.CLOTH) >= GameRunState.SHELTER_CLOTH_COST &&
            run.shelterAt == null

    fun placeSnowShelter(run: GameRunState, base: BaseProgress): EngineStepOutcome {
        if (run.shelterAt != null) {
            val msg = "Укрытие уже поставлено."
            return EngineStepOutcome(run.copy(lastStatusMessage = msg), base, false, msg)
        }
        val cost = mapOf(
            ResourceType.WOOD to GameRunState.SHELTER_WOOD_COST,
            ResourceType.CLOTH to GameRunState.SHELTER_CLOTH_COST
        )
        val spent = SurvivalEngine.spendResourcesAcrossInventoryAndBase(run.inventory, base, cost)
            ?: (run.inventory to base)
        val (inv, newBase) = spent
        val msg = "Снежное укрытие поставлено — ветер рядом не страшен."
        return EngineStepOutcome(
            run.copy(
                inventory = inv,
                shelterAt = ShelterSite(run.playerX + 1.4f, run.playerZ + 1.1f),
                swingAnimRemainingSec = 1.2f,
                lastStatusMessage = msg
            ),
            newBase, true, msg
        )
    }

    // ---------- Ночные события ----------

    fun nightEventTick(run: GameRunState, base: BaseProgress, deltaSeconds: Float): GameRunState {
        val night = run.elapsedRealSeconds % GameRunState.FULL_CYCLE_SECONDS >= GameRunState.DAY_DURATION_SECONDS
        if (!night || run.phase != com.ayaks.winterforest.domain.model.RunPhase.PLAYING) return run
        val slot = (run.elapsedRealSeconds / 60f).toInt()
        val roll = Random(run.level * 7717L + slot * 331L).nextFloat()
        if (roll < 0.055f * deltaSeconds) {
            // Волчий набег: хищник появляется у костра/игрока, если нет пламени
            if (!run.isTorchLit && run.portableFireRemainingSec <= 0f && !run.isNearFieldCampfire && !run.isNearBaseCampfire) {
                val angle = Random(slot.toLong()).nextFloat() * 2f * 3.1416f
                val wolf = WildlifeEntity(
                    id = "night_raid_${slot}",
                    kind = WildlifeKind.TIMBER_WOLF,
                    x = (run.playerX + kotlin.math.cos(angle) * 9f).coerceIn(-52f, 52f),
                    z = (run.playerZ + kotlin.math.sin(angle) * 9f).coerceIn(-52f, 52f),
                    headingRad = angle + 3.1416f,
                    hp = WildlifeKind.TIMBER_WOLF.maxHp
                )
                val list = run.wildlifeByLocation[run.currentLocation].orEmpty() + wolf
                return run.copy(
                    wildlifeByLocation = run.wildlifeByLocation + (run.currentLocation to list),
                    lastStatusMessage = "Ночью стая вышла на охоту — берегитесь волков!"
                )
            }
        }
        return run
    }

    // ---------- Ежедневные испытания ----------

    fun currentDailySet(dayIndex: Int): List<DailyChallengeId> {
        val all = DailyChallengeId.entries
        val start = (dayIndex * 2) % all.size
        return listOf(all[start], all[(start + 1) % all.size], all[(start + 3) % all.size])
    }

    fun syncDaily(base: BaseProgress, dayIndex: Int): BaseProgress {
        if (base.dailyDayIndex == dayIndex) return base
        return base.copy(
            dailyDayIndex = dayIndex,
            dailyProgress = emptyMap(),
            dailyClaimed = emptySet()
        )
    }

    fun trackChallenge(base: BaseProgress, kind: DailyChallengeId.Kind, amount: Int): BaseProgress {
        if (base.dailyDayIndex < 0) return base
        val active = currentDailySet(base.dailyDayIndex).filter { it.kind == kind && it.name !in base.dailyClaimed }
        if (active.isEmpty()) return base
        val progress = base.dailyProgress.toMutableMap()
        for (ch in active) {
            progress[ch.name] = (progress[ch.name] ?: 0) + amount
        }
        return base.copy(dailyProgress = progress)
    }

    fun isChallengeComplete(base: BaseProgress, ch: DailyChallengeId): Boolean =
        (base.dailyProgress[ch.name] ?: 0) >= ch.target

    fun claimChallenge(base: BaseProgress, ch: DailyChallengeId): BaseProgress =
        if (isChallengeComplete(base, ch) && ch.name !in base.dailyClaimed) {
            base.copy(dailyClaimed = base.dailyClaimed + ch.name)
        } else {
            base
        }
}
