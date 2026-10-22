package com.ayaks.winterforest.domain.model

import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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
enum class WildlifeKind(
    val titleRu: String,
    val maxHp: Float,
    val cruiseSpeedMps: Float,
    val primaryDrop: ResourceType,
    val secondaryDrop: ResourceType?,
    val xpReward: Int,
    val isPredator: Boolean
) {
    SNOW_HARE(
        titleRu = "Полярный беляк",
        maxHp = 24f,
        cruiseSpeedMps = 2.6f,
        primaryDrop = ResourceType.MEAT,
        secondaryDrop = ResourceType.CLOTH,
        xpReward = 22,
        isPredator = false
    ),
    NORTHERN_DEER(
        titleRu = "Северный олень",
        maxHp = 55f,
        cruiseSpeedMps = 2.1f,
        primaryDrop = ResourceType.MEAT,
        secondaryDrop = ResourceType.HIDE,
        xpReward = 42,
        isPredator = false
    ),
    TIMBER_WOLF(
        titleRu = "Таёжный волк",
        maxHp = 65f,
        cruiseSpeedMps = 3.1f,
        primaryDrop = ResourceType.HIDE,
        secondaryDrop = ResourceType.MEAT,
        xpReward = 55,
        isPredator = true
    )
}

@Serializable
data class WildlifeEntity(
    val id: String,
    val kind: WildlifeKind,
    val x: Float,
    val z: Float,
    val headingRad: Float = 0f,
    val hp: Float = kind.maxHp,
    val fleeTimerSec: Float = 0f,
    val attackCooldownSec: Float = 0f,
    val respawnRemainingSec: Float = 0f
) {
    val isAlive: Boolean
        get() = hp > 0f && respawnRemainingSec <= 0f
}

@Serializable
data class FieldCampfire(
    val x: Float,
    val z: Float,
    val remainingSec: Float = 95f
)

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

data class TargetPromptInfo(
    val labelRu: String,
    val actionVerbRu: String,
    val distanceMeters: Float,
    val isHostile: Boolean = false,
    val isSpecialRelic: Boolean = false
)

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
    val stamina: Float = MAX_STAT,
    val isSprinting: Boolean = false,
    val equippedTool: EquippedTool = EquippedTool.AXE,
    val xp: Int = 0,
    val level: Int = 1,
    val elapsedRealSeconds: Float = 0f,
    val daysSurvived: Int = 0,
    val inventory: Inventory = Inventory(),
    val nodesByLocation: Map<LocationId, List<ResourceNode>> = emptyMap(),
    val wildlifeByLocation: Map<LocationId, List<WildlifeEntity>> = emptyMap(),
    val fieldCampfires: List<FieldCampfire> = emptyList(),
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
    val walkBobPhaseRad: Float = 0f,
    val hitFeedbackTimerSec: Float = 0f,
    val hitWorldX: Float = 0f,
    val hitWorldY: Float = 0f,
    val hitWorldZ: Float = 0f,
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

    val isNearFieldCampfire: Boolean
        get() = fieldCampfires.any { fire ->
            fire.remainingSec > 0f &&
                ((fire.x - playerX) * (fire.x - playerX) + (fire.z - playerZ) * (fire.z - playerZ)) <= CAMPFIRE_WARMTH_RADIUS_SQ
        }

    val isTorchLit: Boolean
        get() = resinTorchRemainingSec > 0f || equippedTool == EquippedTool.TORCH

    val hasActiveLightSource: Boolean
        get() = resinTorchRemainingSec > 0f ||
            portableFireRemainingSec > 0f ||
            isNearBaseCampfire ||
            isNearFieldCampfire ||
            equippedTool == EquippedTool.TORCH

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

    fun currentWildlife(): List<WildlifeEntity> =
        wildlifeByLocation[currentLocation].orEmpty()

    fun nearestInteractableNode(): ResourceNode? {
        val reachSq = maxOf(INTERACT_RADIUS, equippedTool.reachMeters).let { it * it }
        return currentLocationNodes()
            .filter { it.isAvailable && distSqToPlayer(it) <= reachSq }
            .minByOrNull { scoreNodeForAim(it) }
    }

    fun nearestInteractableWildlife(): WildlifeEntity? {
        val reach = maxOf(4.2f, equippedTool.reachMeters)
        val reachSq = reach * reach
        return currentWildlife()
            .filter { it.isAlive && distSqToWildlife(it) <= reachSq }
            .minByOrNull { distSqToWildlife(it) }
    }

    fun activeTargetPrompt(): TargetPromptInfo? {
        val nearNode = nearestInteractableNode()
        if (nearNode?.specialLootId != null) {
            val d = hypot(nearNode.x - playerX, nearNode.z - playerZ)
            return TargetPromptInfo(
                labelRu = nearNode.specialLootId.titleRu,
                actionVerbRu = "Забрать находку",
                distanceMeters = d,
                isSpecialRelic = true
            )
        }
        val nearBeast = nearestInteractableWildlife()
        if (nearBeast != null) {
            val d = hypot(nearBeast.x - playerX, nearBeast.z - playerZ)
            return TargetPromptInfo(
                labelRu = "${nearBeast.kind.titleRu} (${nearBeast.hp.toInt()}/${nearBeast.kind.maxHp.toInt()})",
                actionVerbRu = if (nearBeast.kind.isPredator) "Отбить атаку" else "Охотиться",
                distanceMeters = d,
                isHostile = nearBeast.kind.isPredator
            )
        }
        if (nearNode != null) {
            val d = hypot(nearNode.x - playerX, nearNode.z - playerZ)
            val resName = nearNode.resourceType?.titleRu ?: "Ресурс"
            val verb = when (nearNode.kind) {
                WorldObjectKind.PINE_TREE -> "Рубить ($resName)"
                WorldObjectKind.STONE_OUTCROP,
                WorldObjectKind.COAL_SEAM,
                WorldObjectKind.IRON_VEIN -> "Добывать ($resName)"
                WorldObjectKind.ICE_HOLE -> "Ловить ($resName)"
                else -> "Собрать ($resName)"
            }
            return TargetPromptInfo(
                labelRu = resName,
                actionVerbRu = verb,
                distanceMeters = d
            )
        }
        return null
    }

    fun currentObjectiveTitleRu(base: BaseProgress): String {
        val foundSet = base.persistentSpecialLoot + inventory.specialItems.keys
        return when {
            warmth < 35f -> "Срочно согрейтесь у костра или разожгите походный огонь (2 дерева)"
            hunger < 35f -> "Восполните сытость ягодами, рыбой, мясом или готовкой у костра"
            SpecialLootId.OLD_MAP !in foundSet ->
                "Найдите тайник «Старая карта» у скалы на Базовой поляне"
            maxOf(base.lifetimeWoodGathered, runWoodGathered) < LocationId.WOOD_REQUIRED_FOR_SPRUCE ->
                "Заготовьте дерево (${maxOf(base.lifetimeWoodGathered, runWoodGathered)}/20), чтобы открыть Густой ельник"
            LocationId.DENSE_SPRUCE !in visitedLocations ->
                "Отправьтесь в «Густой ельник» через карту лагеря или северные ворота"
            level < LocationId.LEVEL_REQUIRED_FOR_LAKE ->
                "Повышайте уровень выжившего (ур. $level/3) для выхода к Замёрзшему озеру"
            SpecialLootId.PICKAXE !in foundSet ->
                "Расчистите вход в «Заброшенную шахту» (10 камня) и добудьте Кирку"
            foundSet.size < SpecialLootId.entries.size ->
                "Соберите все уникальные реликвии севера (${foundSet.size}/${SpecialLootId.entries.size})"
            else ->
                "Укрепляйте базу до максимума (${base.totalBaseLevel}/15) и ставьте рекорд выживания!"
        }
    }

    private fun scoreNodeForAim(node: ResourceNode): Float {
        val dx = node.x - playerX
        val dz = node.z - playerZ
        val distSq = dx * dx + dz * dz
        val dist = hypot(dx, dz).coerceAtLeast(0.001f)
        val yawRad = Math.toRadians(yawDeg.toDouble()).toFloat()
        val fwdX = sin(yawRad)
        val fwdZ = -cos(yawRad)
        val alignment = (dx / dist) * fwdX + (dz / dist) * fwdZ
        // Объект прямо по прицелу получает приоритет перед объектом за спиной
        return distSq * (1.25f - 0.55f * alignment)
    }

    fun distSqToPlayer(node: ResourceNode): Float {
        val dx = node.x - playerX
        val dz = node.z - playerZ
        return dx * dx + dz * dz
    }

    fun distSqToWildlife(entity: WildlifeEntity): Float {
        val dx = entity.x - playerX
        val dz = entity.z - playerZ
        return dx * dx + dz * dz
    }

    companion object {
        const val BASE_MAX_HEALTH = 100f
        const val HEALTH_PER_LEVEL = 10f
        const val MAX_STAT = 100f
        const val XP_PER_LEVEL_STEP = 60

        const val DAY_DURATION_SECONDS = 360f
        const val NIGHT_DURATION_SECONDS = 240f
        const val FULL_CYCLE_SECONDS = DAY_DURATION_SECONDS + NIGHT_DURATION_SECONDS

        const val GAME_DAY_SKIP_WINDOW_SECONDS = 360f
        const val MAX_REWARDED_SKIPS_PER_GAME_DAY = 3
        const val MAX_REVIVES_PER_RUN = 1

        const val HUNGER_LOSS_PER_SECOND = 1f / 26f
        const val STARVATION_DAMAGE_PER_SECOND = 1f / 3f
        const val HYPOTHERMIA_DAMAGE_PER_SECOND = 1f / 2f
        const val PASSIVE_HEALTH_REGEN_PER_SECOND = 0.12f

        const val STAMINA_DRAIN_PER_SECOND = 16f
        const val STAMINA_REGEN_PER_SECOND = 15f
        const val FIELD_CAMPFIRE_WOOD_COST = 2
        const val FIELD_CAMPFIRE_DURATION_SECONDS = 95f

        const val WORLD_HALF_BOUND = 38f
        const val INTERACT_RADIUS = 3.8f
        const val INTERACT_RADIUS_SQ = INTERACT_RADIUS * INTERACT_RADIUS
        const val CAMPFIRE_WARMTH_RADIUS_SQ = 5.5f * 5.5f
        const val NODE_RESPAWN_SECONDS = 90f
        const val REST_COOLDOWN_SECONDS = 60f
        const val PORTABLE_FIRE_SECONDS = 75f
    }
}
