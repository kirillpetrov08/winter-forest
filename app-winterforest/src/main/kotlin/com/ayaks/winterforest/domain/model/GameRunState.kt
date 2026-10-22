package com.ayaks.winterforest.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class WorldObjectKind {
    PINE_TREE,
    BERRY_BUSH,
    STONE_OUTCROP,
    SUPPLY_CRATE,
    RESIN_STUMP,
    ICE_HOLE,
    COAL_SEAM,
    IRON_VEIN,
    GAME_CARCASS,
    SPECIAL_CACHE
}

@Serializable
data class ResourceNode(
    val id: String,
    val location: LocationId,
    val kind: WorldObjectKind,
    val x: Float,
    val z: Float,
    val resourceType: ResourceType? = null,
    val specialLootId: SpecialLootId? = null,
    val baseYield: Int = 2,
    val respawnRemainingSec: Float = 0f,
    val collectedPermanent: Boolean = false
) {
    val isAvailable: Boolean
        get() = !collectedPermanent && respawnRemainingSec <= 0f
}

@Serializable
enum class ActiveTimerType(val titleRu: String) {
    COOKING("Приготовление горячего пайка"),
    SHELTER_REST("Восстановление сил в укрытии"),
    NODE_RESPAWN("Восстановление ресурсного узла")
}

@Serializable
enum class RunPhase {
    PLAYING,
    DEAD
}

@Serializable
data class GameRunState(
    val phase: RunPhase = RunPhase.PLAYING,
    val currentLocation: LocationId = LocationId.BASE_CLEARING,
    val playerX: Float = 0f,
    val playerY: Float = 0f,
    val playerZ: Float = 5.5f,
    val yawDeg: Float = 180f,
    val pitchDeg: Float = -6f,
    val health: Float = BASE_MAX_HEALTH,
    val hunger: Float = MAX_STAT,
    val warmth: Float = MAX_STAT,
    val xp: Int = 0,
    val level: Int = 1,
    val elapsedRealSeconds: Float = 0f,
    val daysSurvived: Int = 0,
    val inventory: Inventory = Inventory(),
    val nodesByLocation: Map<LocationId, List<ResourceNode>> = emptyMap(),
    val visitedLocations: Set<LocationId> = setOf(LocationId.BASE_CLEARING),
    val totalResourcesGathered: Int = 0,
    val runWoodGathered: Int = 0,
    val mineEntranceCleared: Boolean = false,
    val isSnowing: Boolean = false,
    val resinTorchRemainingSec: Float = 0f,
    val portableFireRemainingSec: Float = 0f,
    val cookingRemainingSec: Float = 0f,
    val pendingCookedMealCount: Int = 0,
    val restCooldownRemainingSec: Float = 0f,
    val deathsInRun: Int = 0,
    val revivesUsedInRun: Int = 0,
    val skipsUsedInCurrentWindow: Int = 0,
    val currentSkipWindowIndex: Int = 0,
    val swingAnimRemainingSec: Float = 0f,
    val lastStatusMessage: String = "Осмотрите Базовую поляну и соберите дрова для костра."
) {
    val maxHealth: Float
        get() = BASE_MAX_HEALTH + (level - 1) * HEALTH_PER_LEVEL

    val cycleProgressSeconds: Float
        get() = elapsedRealSeconds % FULL_CYCLE_SECONDS

    val isNight: Boolean
        get() = cycleProgressSeconds >= DAY_DURATION_SECONDS

    val dayNumberDisplay: Int
        get() = daysSurvived + 1

    val xpForNextLevel: Int
        get() = level * XP_PER_LEVEL_STEP

    val gatherBonusChance: Float
        get() = ((level - 1) * 0.12f).coerceAtMost(0.60f)

    val canReviveWithAd: Boolean
        get() = phase == RunPhase.DEAD && revivesUsedInRun < MAX_REVIVES_PER_RUN

    val remainingRewardedSkipsToday: Int
        get() = (MAX_REWARDED_SKIPS_PER_GAME_DAY - skipsUsedInCurrentWindow).coerceAtLeast(0)

    val isNearBaseCampfire: Boolean
        get() = currentLocation == LocationId.BASE_CLEARING &&
            (playerX * playerX + playerZ * playerZ) <= CAMPFIRE_WARMTH_RADIUS_SQ

    val hasActiveLightSource: Boolean
        get() = resinTorchRemainingSec > 0f || portableFireRemainingSec > 0f || isNearBaseCampfire

    fun activeSkippableTimer(): Pair<ActiveTimerType, Float>? {
        if (cookingRemainingSec > 0f) {
            return ActiveTimerType.COOKING to cookingRemainingSec
        }
        if (restCooldownRemainingSec > 0f) {
            return ActiveTimerType.SHELTER_REST to restCooldownRemainingSec
        }
        val nearestDepleted = currentLocationNodes()
            .filter { !it.collectedPermanent && it.respawnRemainingSec > 0f }
            .minByOrNull { distSqToPlayer(it) }
        if (nearestDepleted != null && distSqToPlayer(nearestDepleted) <= INTERACT_RADIUS_SQ) {
            return ActiveTimerType.NODE_RESPAWN to nearestDepleted.respawnRemainingSec
        }
        return null
    }

    fun currentLocationNodes(): List<ResourceNode> =
        nodesByLocation[currentLocation].orEmpty()

    fun nearestInteractableNode(): ResourceNode? =
        currentLocationNodes()
            .filter { it.isAvailable && distSqToPlayer(it) <= INTERACT_RADIUS_SQ }
            .minByOrNull { distSqToPlayer(it) }

    fun distSqToPlayer(node: ResourceNode): Float {
        val dx = node.x - playerX
        val dz = node.z - playerZ
        return dx * dx + dz * dz
    }

    companion object {
        const val BASE_MAX_HEALTH = 100f
        const val HEALTH_PER_LEVEL = 10f
        const val MAX_STAT = 100f
        const val XP_PER_LEVEL_STEP = 60

        // День/ночь: цикл длится 10 минут реального времени (день 6 мин = 360 с, ночь 4 мин = 240 с)
        const val DAY_DURATION_SECONDS = 360f
        const val NIGHT_DURATION_SECONDS = 240f
        const val FULL_CYCLE_SECONDS = DAY_DURATION_SECONDS + NIGHT_DURATION_SECONDS // 600f

        // Игровой день для лимита пропуска ожидания (6 минут реального времени = 360 с)
        const val GAME_DAY_SKIP_WINDOW_SECONDS = 360f
        const val MAX_REWARDED_SKIPS_PER_GAME_DAY = 3
        const val MAX_REVIVES_PER_RUN = 1

        // Правила выживания из ТЗ
        const val HUNGER_LOSS_PER_SECOND = 1f / 30f // -1 ед. каждые 30 секунд
        const val STARVATION_DAMAGE_PER_SECOND = 1f / 5f // при голоде 0: -1 здоровья каждые 5 с
        const val HYPOTHERMIA_DAMAGE_PER_SECOND = 1f / 3f // при тепле 0: -1 здоровья каждые 3 с
        const val PASSIVE_HEALTH_REGEN_PER_SECOND = 0.25f

        const val WORLD_HALF_BOUND = 38f
        const val INTERACT_RADIUS = 3.8f
        const val INTERACT_RADIUS_SQ = INTERACT_RADIUS * INTERACT_RADIUS
        const val CAMPFIRE_WARMTH_RADIUS_SQ = 5.5f * 5.5f
        const val NODE_RESPAWN_SECONDS = 90f
        const val REST_COOLDOWN_SECONDS = 60f
        const val PORTABLE_FIRE_SECONDS = 75f
    }
}
