package com.ayaks.winterforest.domain.rules

import com.ayaks.winterforest.domain.model.ActiveTimerType
import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.BaseUpgradeType
import com.ayaks.winterforest.domain.model.GameRunState
import com.ayaks.winterforest.domain.model.Inventory
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.ResourceNode
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.RunPhase
import com.ayaks.winterforest.domain.model.SpecialLootId
import com.ayaks.winterforest.domain.model.StarterLoadout
import com.ayaks.winterforest.domain.model.UnlockStatus
import com.ayaks.winterforest.domain.model.WorkbenchRecipe
import com.ayaks.winterforest.domain.world.WorldPopulator
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

data class EngineStepOutcome(
    val run: GameRunState,
    val base: BaseProgress,
    val actionSucceeded: Boolean = true,
    val message: String = run.lastStatusMessage
)

object SurvivalEngine {

    fun createNewRun(
        baseProgress: BaseProgress,
        seed: Long = 20261021L
    ): GameRunState {
        val loadout = baseProgress.selectedLoadout
        var inv = Inventory(
            specialItems = baseProgress.persistentSpecialLoot.associateWith { 1 }
        )
        loadout.items.forEach { (res, qty) ->
            inv = inv.tryAddResource(res, qty).inventory
        }

        val allNodes = WorldPopulator.generateAllLocations(
            seed = seed,
            alreadyFoundPersistentLoot = baseProgress.persistentSpecialLoot
        )

        return GameRunState(
            phase = RunPhase.PLAYING,
            currentLocation = LocationId.BASE_CLEARING,
            playerX = 0f,
            playerY = WorldPopulator.terrainHeight(LocationId.BASE_CLEARING, 0f, 5.5f),
            playerZ = 5.5f,
            yawDeg = 180f,
            pitchDeg = -6f,
            health = GameRunState.BASE_MAX_HEALTH,
            hunger = GameRunState.MAX_STAT,
            warmth = GameRunState.MAX_STAT,
            xp = 0,
            level = 1,
            elapsedRealSeconds = 0f,
            daysSurvived = 0,
            inventory = inv,
            nodesByLocation = allNodes,
            visitedLocations = setOf(LocationId.BASE_CLEARING),
            isSnowing = false,
            lastStatusMessage = "День 1: исследуйте Базовую поляну и поддерживайте костёр."
        )
    }

    /**
     * Основной шаг симуляции выживания (deltaSeconds в секундах реального игрового времени).
     */
    fun tick(
        run: GameRunState,
        base: BaseProgress,
        deltaSeconds: Float,
        isMoving: Boolean = false
    ): EngineStepOutcome {
        if (run.phase == RunPhase.DEAD || deltaSeconds <= 0f) {
            return EngineStepOutcome(run, base)
        }

        val prevElapsed = run.elapsedRealSeconds
        val nextElapsed = prevElapsed + deltaSeconds
        val wasNight = (prevElapsed % GameRunState.FULL_CYCLE_SECONDS) >= GameRunState.DAY_DURATION_SECONDS
        val nowNight = (nextElapsed % GameRunState.FULL_CYCLE_SECONDS) >= GameRunState.DAY_DURATION_SECONDS

        // Сброс лимита наградных пропусков каждые 6 минут реального времени (1 игровой день = 360 с)
        val newSkipWindow = floor((nextElapsed / GameRunState.GAME_DAY_SKIP_WINDOW_SECONDS).toDouble()).toInt()
        val skipsUsed = if (newSkipWindow > run.currentSkipWindowIndex) {
            0
        } else {
            run.skipsUsedInCurrentWindow
        }

        // Подсчёт полных прожитых циклов дня/ночи
        val prevCycleCount = floor((prevElapsed / GameRunState.FULL_CYCLE_SECONDS).toDouble()).toInt()
        val newCycleCount = floor((nextElapsed / GameRunState.FULL_CYCLE_SECONDS).toDouble()).toInt()
        val newlyCompletedDays = (newCycleCount - prevCycleCount).coerceAtLeast(0)
        val totalDaysSurvived = run.daysSurvived + newlyCompletedDays

        // Смена погоды при смене фазы суток (в соответствии с профилем локации)
        val snowingNow = if (wasNight != nowNight) {
            val roll = ((nextElapsed.toInt() * 31 + run.currentLocation.ordinal * 17) % 100) / 100f
            roll < run.currentLocation.snowfallChance
        } else {
            run.isSnowing
        }

        // Таймеры активных эффектов
        val nextTorchSec = (run.resinTorchRemainingSec - deltaSeconds).coerceAtLeast(0f)
        val nextPortableFireSec = (run.portableFireRemainingSec - deltaSeconds).coerceAtLeast(0f)
        val nextRestCooldown = (run.restCooldownRemainingSec - deltaSeconds).coerceAtLeast(0f)
        val nextSwingSec = (run.swingAnimRemainingSec - deltaSeconds).coerceAtLeast(0f)

        // 1. ГОЛОД: -1 ед. каждые 30 секунд реального игрового времени
        var nextHunger = (run.hunger - GameRunState.HUNGER_LOSS_PER_SECOND * deltaSeconds)
            .coerceIn(0f, GameRunState.MAX_STAT)

        // 2. ТЕПЛО: зависит от локации, ночи, движения, снегопада, улучшений базы, термоса, шубы и факела
        val warmingNearFire = run.isNearBaseCampfire || run.portableFireRemainingSec > 0f
        var nextWarmth = if (warmingNearFire) {
            val fireGainPerSec = base.campfireRecoveryPerSec()
            (run.warmth + fireGainPerSec * deltaSeconds).coerceIn(0f, GameRunState.MAX_STAT)
        } else {
            val baseColdPerSec = 0.26f * run.currentLocation.coldMultiplier
            val nightFactor = if (nowNight) 1.65f else 1.0f
            val moveFactor = if (isMoving) 1.28f else 1.0f
            val weatherFactor = if (snowingNow) 1.30f else 1.0f
            val torchMitigation = if (run.resinTorchRemainingSec > 0f) 0.50f else 1.0f // +50% эффективности тепла
            val coldLossPerSec = baseColdPerSec *
                nightFactor *
                moveFactor *
                weatherFactor *
                base.warmthLossMultiplier() *
                torchMitigation
            (run.warmth - coldLossPerSec * deltaSeconds).coerceIn(0f, GameRunState.MAX_STAT)
        }

        // Завершение таймера готовки горячего пайка
        var nextCookingSec = run.cookingRemainingSec
        var nextPendingMeals = run.pendingCookedMealCount
        var mealHealthBonus = 0f
        var statusMessage = run.lastStatusMessage
        if (nextCookingSec > 0f) {
            nextCookingSec = (nextCookingSec - deltaSeconds).coerceAtLeast(0f)
            if (nextCookingSec == 0f && nextPendingMeals > 0) {
                nextHunger = (nextHunger + 42f * nextPendingMeals).coerceAtMost(GameRunState.MAX_STAT)
                nextWarmth = (nextWarmth + 20f * nextPendingMeals).coerceAtMost(GameRunState.MAX_STAT)
                mealHealthBonus = 16f * nextPendingMeals
                nextPendingMeals = 0
                statusMessage = "Горячий паёк готов: восстановлены сытость, тепло и силы!"
            }
        }

        // 3. ЗДОРОВЬЕ: урон при голоде = 0 (-1 / 5 с) и при тепле = 0 (-1 / 3 с); регенерация при голоде > 50 и тепле > 50
        var healthDelta = mealHealthBonus
        if (nextHunger <= 0f) {
            healthDelta -= GameRunState.STARVATION_DAMAGE_PER_SECOND * deltaSeconds
        }
        if (nextWarmth <= 0f) {
            healthDelta -= GameRunState.HYPOTHERMIA_DAMAGE_PER_SECOND * deltaSeconds
        }
        if (nextHunger > 50f && nextWarmth > 50f && healthDelta >= 0f) {
            healthDelta += GameRunState.PASSIVE_HEALTH_REGEN_PER_SECOND * deltaSeconds
        }

        // Начисление опыта за выжитые дни
        var xpGain = newlyCompletedDays * 35
        val (nextXp, nextLevel) = applyXpGain(run.xp, run.level, xpGain)
        val maxHp = GameRunState.BASE_MAX_HEALTH + (nextLevel - 1) * GameRunState.HEALTH_PER_LEVEL
        val nextHealth = (run.health + healthDelta).coerceIn(0f, maxHp)

        // Восстановление ресурсных узлов по таймеру И при наступлении ночи (по ТЗ п. 2.3)
        val enteredNight = !wasNight && nowNight
        val updatedNodes = run.nodesByLocation.mapValues { (_, list) ->
            list.map { node ->
                if (node.collectedPermanent || node.respawnRemainingSec <= 0f) {
                    node
                } else if (enteredNight) {
                    node.copy(respawnRemainingSec = 0f)
                } else {
                    node.copy(
                        respawnRemainingSec = (node.respawnRemainingSec - deltaSeconds).coerceAtLeast(0f)
                    )
                }
            }
        }

        if (enteredNight) {
            statusMessage = "Опустилась морозная ночь: холод усилился, но ресурсы в лесу восстановились."
        } else if (newlyCompletedDays > 0) {
            statusMessage = "Рассвет дня ${totalDaysSurvived + 1}! Получен опыт за выживание."
        }

        // Обработка смерти при здоровье = 0
        if (nextHealth <= 0f) {
            val persistentLoot = run.inventory.retainPersistentSpecialsOnly().keys
            val mergedPersistent = base.persistentSpecialLoot + persistentLoot
            val cleanedInventory = run.inventory.copy(
                specialItems = mergedPersistent.associateWith { 1 }
            )
            val updatedBase = base.copy(
                persistentSpecialLoot = mergedPersistent,
                bestDaysSurvived = maxOf(base.bestDaysSurvived, totalDaysSurvived),
                completedRunsCount = base.completedRunsCount + 1
            )
            val deadRun = run.copy(
                phase = RunPhase.DEAD,
                health = 0f,
                hunger = nextHunger,
                warmth = nextWarmth,
                xp = nextXp,
                level = nextLevel,
                elapsedRealSeconds = nextElapsed,
                daysSurvived = totalDaysSurvived,
                inventory = cleanedInventory,
                nodesByLocation = updatedNodes,
                isSnowing = snowingNow,
                resinTorchRemainingSec = 0f, // расходуемый эффект факела сбрасывается при смерти
                portableFireRemainingSec = 0f,
                cookingRemainingSec = 0f,
                pendingCookedMealCount = 0,
                restCooldownRemainingSec = 0f,
                deathsInRun = run.deathsInRun + 1,
                skipsUsedInCurrentWindow = skipsUsed,
                currentSkipWindowIndex = newSkipWindow,
                swingAnimRemainingSec = 0f,
                lastStatusMessage = "Персонаж погиб от переохлаждения и истощения."
            )
            return EngineStepOutcome(deadRun, updatedBase, message = deadRun.lastStatusMessage)
        }

        val updatedBase = if (totalDaysSurvived > base.bestDaysSurvived) {
            base.copy(bestDaysSurvived = totalDaysSurvived)
        } else {
            base
        }

        val aliveRun = run.copy(
            health = nextHealth,
            hunger = nextHunger,
            warmth = nextWarmth,
            xp = nextXp,
            level = nextLevel,
            elapsedRealSeconds = nextElapsed,
            daysSurvived = totalDaysSurvived,
            nodesByLocation = updatedNodes,
            isSnowing = snowingNow,
            resinTorchRemainingSec = nextTorchSec,
            portableFireRemainingSec = nextPortableFireSec,
            cookingRemainingSec = nextCookingSec,
            pendingCookedMealCount = nextPendingMeals,
            restCooldownRemainingSec = nextRestCooldown,
            skipsUsedInCurrentWindow = skipsUsed,
            currentSkipWindowIndex = newSkipWindow,
            swingAnimRemainingSec = nextSwingSec,
            lastStatusMessage = statusMessage
        )
        return EngineStepOutcome(aliveRun, updatedBase, message = statusMessage)
    }

    /**
     * Перемещение игрока по виртуальному джойстику с проверкой границ мира [-38, +38].
     */
    fun movePlayer(
        run: GameRunState,
        forwardInput: Float,
        strafeInput: Float,
        deltaSeconds: Float,
        speedMetersPerSec: Float = 5.4f
    ): GameRunState {
        if (run.phase == RunPhase.DEAD || deltaSeconds <= 0f) return run
        val yawRad = Math.toRadians(run.yawDeg.toDouble()).toFloat()
        val sinYaw = sin(yawRad)
        val cosYaw = cos(yawRad)

        val worldDx = (sinYaw * forwardInput + cosYaw * strafeInput) * speedMetersPerSec * deltaSeconds
        val worldDz = (-cosYaw * forwardInput + sinYaw * strafeInput) * speedMetersPerSec * deltaSeconds

        val rawX = run.playerX + worldDx
        val rawZ = run.playerZ + worldDz
        val bound = GameRunState.WORLD_HALF_BOUND
        val hitBoundary = rawX < -bound || rawX > bound || rawZ < -bound || rawZ > bound

        val clampedX = rawX.coerceIn(-bound, bound)
        val clampedZ = rawZ.coerceIn(-bound, bound)
        val groundY = WorldPopulator.terrainHeight(run.currentLocation, clampedX, clampedZ)

        val msg = if (hitBoundary) {
            "Граница локации: впереди непроходимый лес и крутой обрыв."
        } else {
            run.lastStatusMessage
        }
        return run.copy(
            playerX = clampedX,
            playerY = groundY,
            playerZ = clampedZ,
            lastStatusMessage = msg
        )
    }

    fun rotateCamera(
        run: GameRunState,
        deltaYawDeg: Float,
        deltaPitchDeg: Float,
        sensitivity: Float = 1.0f
    ): GameRunState {
        if (run.phase == RunPhase.DEAD) return run
        val nextYaw = ((run.yawDeg + deltaYawDeg * sensitivity) % 360f + 360f) % 360f
        val nextPitch = (run.pitchDeg + deltaPitchDeg * sensitivity).coerceIn(-75f, 75f)
        return run.copy(yawDeg = nextYaw, pitchDeg = nextPitch)
    }

    /**
     * Сбор ближайшего ресурсного узла или тайника специального лута (кнопка «Собрать» / «Ударить»).
     */
    fun gatherNearest(
        run: GameRunState,
        base: BaseProgress,
        withToolStrikeBonus: Boolean = false
    ): EngineStepOutcome {
        if (run.phase == RunPhase.DEAD) {
            return EngineStepOutcome(run, base, false, "Невозможно собирать ресурсы после гибели.")
        }
        val target = run.nearestInteractableNode()
            ?: return EngineStepOutcome(
                run.copy(
                    swingAnimRemainingSec = if (withToolStrikeBonus) 0.35f else run.swingAnimRemainingSec,
                    lastStatusMessage = "Рядом нет доступных объектов для сбора."
                ),
                base,
                actionSucceeded = false,
                message = "Рядом нет доступных объектов для сбора."
            )

        // Сбор уникального специального предмета локации
        if (target.specialLootId != null) {
            val special = target.specialLootId
            val updatedInventory = run.inventory.grantSpecialLoot(special, 1)
            val updatedPersistent = if (special.persistsAcrossRuns) {
                base.persistentSpecialLoot + special
            } else {
                base.persistentSpecialLoot
            }
            val autoEquipCoat = base.warmCoatEquipped || special == SpecialLootId.WARM_COAT
            val updatedBase = base.copy(
                persistentSpecialLoot = updatedPersistent,
                warmCoatEquipped = autoEquipCoat
            )
            val updatedNodes = replaceNode(
                run.nodesByLocation,
                run.currentLocation,
                target.copy(
                    respawnRemainingSec = if (special.isConsumable) GameRunState.NODE_RESPAWN_SECONDS * 2f else 0f,
                    collectedPermanent = special.persistsAcrossRuns
                )
            )
            val (nextXp, nextLevel) = applyXpGain(run.xp, run.level, 40)
            val msg = "Найдена уникальная находка: «${special.titleRu}»!"
            val updatedRun = run.copy(
                inventory = updatedInventory,
                nodesByLocation = updatedNodes,
                xp = nextXp,
                level = nextLevel,
                swingAnimRemainingSec = if (withToolStrikeBonus) 0.35f else run.swingAnimRemainingSec,
                lastStatusMessage = msg
            )
            return EngineStepOutcome(updatedRun, updatedBase, true, msg)
        }

        // Обычный ресурсный узел
        val resType = target.resourceType ?: ResourceType.WOOD
        val pickaxeBonus = if (
            withToolStrikeBonus &&
            SpecialLootId.PICKAXE in base.persistentSpecialLoot &&
            resType in setOf(ResourceType.STONE, ResourceType.COAL, ResourceType.IRON)
        ) {
            1
        } else {
            0
        }
        val levelBonus = if (run.level >= 3 && (run.totalResourcesGathered % 2 == 0)) 1 else 0
        val rawYield = (target.baseYield + pickaxeBonus + levelBonus).coerceIn(1, 5)
        val addResult = run.inventory.tryAddResource(resType, rawYield)

        if (addResult.isFullBlocked) {
            val fullMsg = "Инвентарь полон (${Inventory.MAX_SLOTS}/${Inventory.MAX_SLOTS} слотов)!"
            return EngineStepOutcome(
                run.copy(lastStatusMessage = fullMsg),
                base,
                actionSucceeded = false,
                message = fullMsg
            )
        }

        val added = addResult.addedAmount
        val updatedNodes = replaceNode(
            run.nodesByLocation,
            run.currentLocation,
            target.copy(respawnRemainingSec = GameRunState.NODE_RESPAWN_SECONDS)
        )
        val woodDelta = if (resType == ResourceType.WOOD) added else 0
        val updatedBase = base.copy(
            lifetimeWoodGathered = base.lifetimeWoodGathered + woodDelta
        )
        val xpEarned = resType.xpReward * added
        val (nextXp, nextLevel) = applyXpGain(run.xp, run.level, xpEarned)
        val maxHp = GameRunState.BASE_MAX_HEALTH + (nextLevel - 1) * GameRunState.HEALTH_PER_LEVEL
        val msg = "Собрано: ${resType.titleRu} +$added (+$xpEarned опыта)"

        val updatedRun = run.copy(
            inventory = addResult.inventory,
            nodesByLocation = updatedNodes,
            totalResourcesGathered = run.totalResourcesGathered + added,
            runWoodGathered = run.runWoodGathered + woodDelta,
            xp = nextXp,
            level = nextLevel,
            health = run.health.coerceAtMost(maxHp),
            swingAnimRemainingSec = if (withToolStrikeBonus) 0.35f else run.swingAnimRemainingSec,
            lastStatusMessage = msg
        )
        return EngineStepOutcome(updatedRun, updatedBase, true, msg)
    }

    /**
     * Использование ресурса (еда, топливо или Смоляной факел).
     * Если ресурса нет — действие безопасно отменяется с понятным сообщением.
     */
    fun useResource(
        run: GameRunState,
        base: BaseProgress,
        type: ResourceType
    ): EngineStepOutcome {
        if (run.phase == RunPhase.DEAD) {
            return EngineStepOutcome(run, base, false, "Персонаж мёртв.")
        }
        val afterConsume = run.inventory.tryConsumeResource(type, 1)
        if (afterConsume == null) {
            val msg = "Нет ресурса «${type.titleRu}» в инвентаре."
            return EngineStepOutcome(run.copy(lastStatusMessage = msg), base, false, msg)
        }

        return when {
            type.isEdible -> {
                val maxHp = run.maxHealth
                val nextHunger = (run.hunger + type.hungerRestore).coerceAtMost(GameRunState.MAX_STAT)
                val nextHp = (run.health + type.healthRestore).coerceAtMost(maxHp)
                val msg = "Съедено: ${type.titleRu} (+${type.hungerRestore.toInt()} сытости)."
                EngineStepOutcome(
                    run.copy(
                        inventory = afterConsume,
                        hunger = nextHunger,
                        health = nextHp,
                        lastStatusMessage = msg
                    ),
                    base,
                    true,
                    msg
                )
            }
            type.isFuel -> {
                val nextWarmth = (run.warmth + type.warmthRestore).coerceAtMost(GameRunState.MAX_STAT)
                val nextFireSec = maxOf(run.portableFireRemainingSec, GameRunState.PORTABLE_FIRE_SECONDS)
                val msg = "Разожжён походный огонь (${type.titleRu}): +${type.warmthRestore.toInt()} тепла."
                EngineStepOutcome(
                    run.copy(
                        inventory = afterConsume,
                        warmth = nextWarmth,
                        portableFireRemainingSec = nextFireSec,
                        lastStatusMessage = msg
                    ),
                    base,
                    true,
                    msg
                )
            }
            else -> {
                val msg = "Ресурс «${type.titleRu}» используется на верстаке или для улучшений базы."
                // Возвращаем неиспользуемый напрямую материал обратно в инвентарь
                EngineStepOutcome(run.copy(lastStatusMessage = msg), base, false, msg)
            }
        }
    }

    fun igniteResinTorch(run: GameRunState, base: BaseProgress): EngineStepOutcome {
        val after = run.inventory.consumeSpecialLoot(SpecialLootId.RESIN_TORCH, 1)
        if (after == null) {
            val msg = "Нет «Смоляного факела» в инвентаре."
            return EngineStepOutcome(run.copy(lastStatusMessage = msg), base, false, msg)
        }
        val msg = "Смоляной факел зажжён (+50% сохранения тепла и свет на 3 минуты)!"
        return EngineStepOutcome(
            run.copy(
                inventory = after,
                resinTorchRemainingSec = SpecialLootId.RESIN_TORCH_DURATION_SEC,
                warmth = (run.warmth + 15f).coerceAtMost(GameRunState.MAX_STAT),
                lastStatusMessage = msg
            ),
            base,
            true,
            msg
        )
    }

    /**
     * Быстрое контекстное действие для кнопки «Использовать» на HUD.
     */
    fun quickUseBestSurvivalItem(run: GameRunState, base: BaseProgress): EngineStepOutcome {
        if (run.currentLocation.requiresLightForVisibility &&
            run.resinTorchRemainingSec <= 0f &&
            run.inventory.hasSpecial(SpecialLootId.RESIN_TORCH)
        ) {
            return igniteResinTorch(run, base)
        }
        if (run.warmth <= run.hunger && run.warmth < 75f) {
            for (fuel in listOf(ResourceType.COAL, ResourceType.WOOD, ResourceType.RESIN)) {
                if (run.inventory.countOf(fuel) > 0) return useResource(run, base, fuel)
            }
            if (run.inventory.hasSpecial(SpecialLootId.RESIN_TORCH)) {
                return igniteResinTorch(run, base)
            }
        }
        for (food in listOf(ResourceType.MEAT, ResourceType.FISH, ResourceType.BERRIES)) {
            if (run.inventory.countOf(food) > 0) return useResource(run, base, food)
        }
        for (fuel in listOf(ResourceType.COAL, ResourceType.WOOD, ResourceType.RESIN)) {
            if (run.inventory.countOf(fuel) > 0) return useResource(run, base, fuel)
        }
        val msg = "Нет подходящей еды, дров или факела для быстрого использования."
        return EngineStepOutcome(run.copy(lastStatusMessage = msg), base, false, msg)
    }

    /**
     * Проверка условий открытия каждой из 5 изучаемых локаций (раздел 2.4 ТЗ),
     * включая защиту от потери квест-предмета «Старая карта».
     */
    fun evaluateLocationUnlock(
        target: LocationId,
        run: GameRunState,
        base: BaseProgress
    ): UnlockStatus {
        val totalWoodEver = maxOf(base.lifetimeWoodGathered, run.runWoodGathered)
        val hasOldMap = run.inventory.hasSpecial(SpecialLootId.OLD_MAP) ||
            SpecialLootId.OLD_MAP in base.persistentSpecialLoot

        return when (target) {
            LocationId.BASE_CLEARING -> UnlockStatus(
                isUnlocked = true,
                conditionSummaryRu = "Доступна с начала игры"
            )
            LocationId.DENSE_SPRUCE -> {
                val ok = totalWoodEver >= LocationId.WOOD_REQUIRED_FOR_SPRUCE
                UnlockStatus(
                    isUnlocked = ok,
                    conditionSummaryRu = "Собрать 20 ед. дерева суммарно ($totalWoodEver/20)",
                    missingRequirementRu = if (ok) "" else "Нужно собрать 20 ед. дерева суммарно (сейчас $totalWoodEver/20)."
                )
            }
            LocationId.FROZEN_LAKE -> {
                val ok = run.level >= LocationId.LEVEL_REQUIRED_FOR_LAKE
                UnlockStatus(
                    isUnlocked = ok,
                    conditionSummaryRu = "Уровень персонажа ≥ 3 (текущий: ${run.level})",
                    missingRequirementRu = if (ok) "" else "Требуется 3-й уровень персонажа (сейчас ур. ${run.level})."
                )
            }
            LocationId.ABANDONED_MINE -> {
                if (run.mineEntranceCleared) {
                    return UnlockStatus(
                        isUnlocked = true,
                        conditionSummaryRu = "Вход в шахту расчищен"
                    )
                }
                val stoneCount = totalAvailableResource(run, base, ResourceType.STONE)
                // Альтернативный страховочный путь на крайний случай (ур. >= 5 + 15 камня), если Старая карта ещё не взята
                val emergencyPath = run.level >= 5 && stoneCount >= 15
                val ok = (hasOldMap && stoneCount >= LocationId.STONE_REQUIRED_FOR_MINE) || emergencyPath
                val missing = buildList {
                    if (!hasOldMap) add("найти «Старую карту» на Базовой поляне")
                    if (stoneCount < LocationId.STONE_REQUIRED_FOR_MINE) {
                        add("собрать 10 ед. камня для расчистки входа (есть $stoneCount/10)")
                    }
                }.joinToString(" и ")
                UnlockStatus(
                    isUnlocked = ok,
                    conditionSummaryRu = "Иметь «Старую карту» и 10 ед. камня",
                    missingRequirementRu = if (ok) "" else "Для входа нужно $missing."
                )
            }
            LocationId.HUNTERS_CABIN -> {
                val bestOrCurrentDays = maxOf(run.daysSurvived, base.bestDaysSurvived)
                val clothCount = totalAvailableResource(run, base, ResourceType.CLOTH)
                val ok = bestOrCurrentDays >= LocationId.DAYS_REQUIRED_FOR_CABIN &&
                    clothCount >= LocationId.CLOTH_REQUIRED_FOR_CABIN
                val missing = buildList {
                    if (bestOrCurrentDays < LocationId.DAYS_REQUIRED_FOR_CABIN) {
                        add("выжить 3 игровых дня (сейчас $bestOrCurrentDays/3)")
                    }
                    if (clothCount < LocationId.CLOTH_REQUIRED_FOR_CABIN) {
                        add("иметь 5 ед. ткани (есть $clothCount/5)")
                    }
                }.joinToString(" и ")
                UnlockStatus(
                    isUnlocked = ok,
                    conditionSummaryRu = "Выжить 3 игровых дня и иметь 5 ед. ткани",
                    missingRequirementRu = if (ok) "" else "Путь закрыт: необходимо $missing."
                )
            }
        }
    }

    /**
     * Восстановление квестовой целостности: если предмет числился найденным в мета-прогрессе,
     * но пропал из текущего сохранения или наоборот — восстанавливает его без потери прогресса.
     */
    fun repairQuestItemsConsistency(run: GameRunState, base: BaseProgress): EngineStepOutcome {
        val unionPersistent = base.persistentSpecialLoot +
            run.inventory.specialItems.keys.filter { it.persistsAcrossRuns }
        var fixedInv = run.inventory
        unionPersistent.forEach { loot ->
            if (!fixedInv.hasSpecial(loot)) {
                fixedInv = fixedInv.grantSpecialLoot(loot, 1)
            }
        }
        val fixedBase = base.copy(persistentSpecialLoot = unionPersistent)
        return EngineStepOutcome(run.copy(inventory = fixedInv), fixedBase, true)
    }

    /**
     * Переход в другую локацию с проверкой условий открытия и безопасной защитой от сбоя генерации.
     */
    fun travelToLocation(
        run: GameRunState,
        base: BaseProgress,
        destination: LocationId,
        simulateGenerationError: Boolean = false
    ): EngineStepOutcome {
        val synced = repairQuestItemsConsistency(run, base)
        val curRun = synced.run
        var curBase = synced.base

        if (destination == curRun.currentLocation) {
            val msg = "Вы уже находитесь в локации «${destination.titleRu}»."
            return EngineStepOutcome(curRun.copy(lastStatusMessage = msg), curBase, true, msg)
        }

        val unlock = evaluateLocationUnlock(destination, curRun, curBase)
        if (!unlock.isUnlocked) {
            return EngineStepOutcome(
                curRun.copy(lastStatusMessage = unlock.missingRequirementRu),
                curBase,
                actionSucceeded = false,
                message = unlock.missingRequirementRu
            )
        }

        // Расход камня при первой расчистке входа в Заброшенную шахту
        var nextInv = curRun.inventory
        var clearedMine = curRun.mineEntranceCleared
        if (destination == LocationId.ABANDONED_MINE && !clearedMine) {
            val spendOutcome = spendResourcesAcrossInventoryAndBase(
                nextInv,
                curBase,
                mapOf(ResourceType.STONE to LocationId.STONE_REQUIRED_FOR_MINE)
            )
            if (spendOutcome != null) {
                nextInv = spendOutcome.first
                curBase = spendOutcome.second
                clearedMine = true
            }
        }

        val updatedNodesMap = curRun.nodesByLocation.toMutableMap()
        try {
            if (simulateGenerationError || updatedNodesMap[destination].isNullOrEmpty()) {
                updatedNodesMap[destination] = WorldPopulator.generateLocationNodes(
                    location = destination,
                    seed = 20261021L + destination.ordinal * 7919L,
                    alreadyFoundPersistentLoot = curBase.persistentSpecialLoot,
                    simulateFailureForTest = simulateGenerationError
                )
            }
        } catch (_: Throwable) {
            val safeError = "Не удалось сгенерировать локацию «${destination.titleRu}». Вы остались в «${curRun.currentLocation.titleRu}»."
            return EngineStepOutcome(
                curRun.copy(lastStatusMessage = safeError),
                curBase,
                actionSucceeded = false,
                message = safeError
            )
        }

        val isFirstVisit = destination !in curRun.visitedLocations
        val discoveryXp = if (isFirstVisit) 45 else 0
        val (nextXp, nextLevel) = applyXpGain(curRun.xp, curRun.level, discoveryXp)
        val spawnZ = if (destination == LocationId.BASE_CLEARING) 5.5f else 0f
        val groundY = WorldPopulator.terrainHeight(destination, 0f, spawnZ)

        val msg = if (isFirstVisit) {
            "Открыта новая локация: «${destination.titleRu}» (+$discoveryXp опыта)!"
        } else {
            "Переход в локацию «${destination.titleRu}»."
        }

        val nextRun = curRun.copy(
            currentLocation = destination,
            playerX = 0f,
            playerY = groundY,
            playerZ = spawnZ,
            inventory = nextInv,
            nodesByLocation = updatedNodesMap,
            visitedLocations = curRun.visitedLocations + destination,
            mineEntranceCleared = clearedMine,
            xp = nextXp,
            level = nextLevel,
            isSnowing = destination.snowfallChance >= 0.5f,
            lastStatusMessage = msg
        )
        return EngineStepOutcome(nextRun, curBase, true, msg)
    }

    fun returnToBase(run: GameRunState, base: BaseProgress): EngineStepOutcome =
        travelToLocation(run, base, LocationId.BASE_CLEARING)

    /**
     * Складирование всех обычных ресурсов из рюкзака на склад базы.
     */
    fun depositAllToBaseStorage(run: GameRunState, base: BaseProgress): EngineStepOutcome {
        val newStorage = base.storageResources.toMutableMap()
        var movedTotal = 0
        for (slot in run.inventory.slots) {
            newStorage[slot.type] = (newStorage[slot.type] ?: 0) + slot.count
            movedTotal += slot.count
        }
        if (movedTotal == 0) {
            val msg = "В рюкзаке нет обычных ресурсов для переноса на склад базы."
            return EngineStepOutcome(run.copy(lastStatusMessage = msg), base, false, msg)
        }
        val msg = "Перенесено на склад базы: $movedTotal ед. ресурсов."
        return EngineStepOutcome(
            run.copy(
                inventory = run.inventory.copy(slots = emptyList()),
                lastStatusMessage = msg
            ),
            base.copy(storageResources = newStorage),
            true,
            msg
        )
    }

    /**
     * Прокачка построек базы (Укрытие / Костёр / Верстак) с сохранением между забегами.
     */
    fun upgradeBaseStructure(
        run: GameRunState,
        base: BaseProgress,
        type: BaseUpgradeType
    ): EngineStepOutcome {
        val currentLevel = base.levelOf(type)
        val cost = base.upgradeCost(type)
            ?: return EngineStepOutcome(
                run.copy(lastStatusMessage = "«${type.titleRu}» уже улучшено до максимума."),
                base,
                false,
                "«${type.titleRu}» уже улучшено до максимума."
            )

        if (type == BaseUpgradeType.WORKBENCH &&
            currentLevel >= 3 &&
            SpecialLootId.PICKAXE !in base.persistentSpecialLoot &&
            !run.inventory.hasSpecial(SpecialLootId.PICKAXE)
        ) {
            val msg = "Для улучшения верстака выше 3-го уровня нужна «Кирка» из Заброшенной шахты."
            return EngineStepOutcome(run.copy(lastStatusMessage = msg), base, false, msg)
        }

        val spent = spendResourcesAcrossInventoryAndBase(run.inventory, base, cost)
        if (spent == null) {
            val costText = cost.entries.joinToString(", ") { "${it.key.titleRu}: ${it.value}" }
            val msg = "Недостаточно ресурсов для «${type.titleRu}» (нужно: $costText)."
            return EngineStepOutcome(run.copy(lastStatusMessage = msg), base, false, msg)
        }

        val (nextInv, baseAfterSpend) = spent
        val nextLevelVal = currentLevel + 1
        val upgradedBase = when (type) {
            BaseUpgradeType.SHELTER -> baseAfterSpend.copy(shelterLevel = nextLevelVal)
            BaseUpgradeType.CAMPFIRE -> baseAfterSpend.copy(campfireLevel = nextLevelVal)
            BaseUpgradeType.WORKBENCH -> baseAfterSpend.copy(workbenchLevel = nextLevelVal)
        }

        val (nextXp, nextPlayerLevel) = applyXpGain(run.xp, run.level, 35)
        val msg = "«${type.titleRu}» улучшено до уровня $nextLevelVal (+35 опыта)!"
        return EngineStepOutcome(
            run.copy(
                inventory = nextInv,
                xp = nextXp,
                level = nextPlayerLevel,
                lastStatusMessage = msg
            ),
            upgradedBase,
            true,
            msg
        )
    }

    /**
     * Постройка/установка найденного «Термоса» на базе (-15% скорости потери тепла навсегда).
     */
    fun installThermosOnBase(run: GameRunState, base: BaseProgress): EngineStepOutcome {
        val hasThermos = SpecialLootId.THERMOS in base.persistentSpecialLoot ||
            run.inventory.hasSpecial(SpecialLootId.THERMOS)
        if (!hasThermos) {
            val msg = "Сначала найдите «Термос» на локации «Замёрзшее озеро»."
            return EngineStepOutcome(run.copy(lastStatusMessage = msg), base, false, msg)
        }
        if (base.thermosInstalledOnBase) {
            val msg = "Термос уже установлен на базе (-15% потери тепла активно)."
            return EngineStepOutcome(run.copy(lastStatusMessage = msg), base, false, msg)
        }
        val spent = spendResourcesAcrossInventoryAndBase(
            run.inventory,
            base,
            BaseProgress.THERMOS_INSTALL_COST
        )
        if (spent == null) {
            val msg = "Для установки Термоса на базе нужно: Дерево: 2, Камень: 2."
            return EngineStepOutcome(run.copy(lastStatusMessage = msg), base, false, msg)
        }
        val (nextInv, baseAfterSpend) = spent
        val updatedBase = baseAfterSpend.copy(
            persistentSpecialLoot = baseAfterSpend.persistentSpecialLoot + SpecialLootId.THERMOS,
            thermosInstalledOnBase = true
        )
        val msg = "Термос установлен на базе! Потеря тепла снижена на 15% навсегда."
        return EngineStepOutcome(run.copy(inventory = nextInv, lastStatusMessage = msg), updatedBase, true, msg)
    }

    fun craftAtWorkbench(
        run: GameRunState,
        base: BaseProgress,
        recipe: WorkbenchRecipe
    ): EngineStepOutcome {
        if (base.workbenchLevel < recipe.minWorkbenchLevel) {
            val msg = "Требуется Верстак ур. ${recipe.minWorkbenchLevel}."
            return EngineStepOutcome(run.copy(lastStatusMessage = msg), base, false, msg)
        }
        val hasPickaxe = SpecialLootId.PICKAXE in base.persistentSpecialLoot ||
            run.inventory.hasSpecial(SpecialLootId.PICKAXE)
        if (recipe.requiresPickaxe && !hasPickaxe) {
            val msg = "Этот рецепт открывается после нахождения «Кирки» в Заброшенной шахте."
            return EngineStepOutcome(run.copy(lastStatusMessage = msg), base, false, msg)
        }
        val spent = spendResourcesAcrossInventoryAndBase(run.inventory, base, recipe.cost)
        if (spent == null) {
            val needed = recipe.cost.entries.joinToString(", ") { "${it.key.titleRu}: ${it.value}" }
            val msg = "Не хватает материалов для «${recipe.titleRu}» ($needed)."
            return EngineStepOutcome(run.copy(lastStatusMessage = msg), base, false, msg)
        }
        val (invAfterCost, baseAfterCost) = spent
        return when (recipe) {
            WorkbenchRecipe.CRAFT_RESIN_TORCH -> {
                val withTorch = invAfterCost.grantSpecialLoot(SpecialLootId.RESIN_TORCH, 1)
                val msg = "Изготовлен «Смоляной факел»!"
                EngineStepOutcome(run.copy(inventory = withTorch, lastStatusMessage = msg), baseAfterCost, true, msg)
            }
            WorkbenchRecipe.CRAFT_WARM_COMPRESS -> {
                val msg = "Использован тёплый пакет: +28 здоровья и +15 тепла!"
                EngineStepOutcome(
                    run.copy(
                        inventory = invAfterCost,
                        health = (run.health + 28f).coerceAtMost(run.maxHealth),
                        warmth = (run.warmth + 15f).coerceAtMost(GameRunState.MAX_STAT),
                        lastStatusMessage = msg
                    ),
                    baseAfterCost,
                    true,
                    msg
                )
            }
            WorkbenchRecipe.CRAFT_MINERS_HEATER -> {
                val msg = "Активирована угольная грелка: +65 тепла и +20 здоровья!"
                EngineStepOutcome(
                    run.copy(
                        inventory = invAfterCost,
                        health = (run.health + 20f).coerceAtMost(run.maxHealth),
                        warmth = (run.warmth + 65f).coerceAtMost(GameRunState.MAX_STAT),
                        portableFireRemainingSec = maxOf(run.portableFireRemainingSec, 120f),
                        lastStatusMessage = msg
                    ),
                    baseAfterCost,
                    true,
                    msg
                )
            }
        }
    }

    /**
     * Запуск готовки горячего пайка на костре (таймер, ускоряемый уровнем костра или rewarded-пропуском).
     */
    fun startCookingOnCampfire(run: GameRunState, base: BaseProgress): EngineStepOutcome {
        val foodType = listOf(ResourceType.MEAT, ResourceType.FISH, ResourceType.BERRIES)
            .firstOrNull { totalAvailableResource(run, base, it) > 0 }
        if (foodType == null) {
            val msg = "Нет ягод, рыбы или мяса для приготовления горячего пайка."
            return EngineStepOutcome(run.copy(lastStatusMessage = msg), base, false, msg)
        }
        val spent = spendResourcesAcrossInventoryAndBase(run.inventory, base, mapOf(foodType to 1))
            ?: return EngineStepOutcome(run, base, false, "Недостаточно ингредиентов.")
        val duration = base.cookingDurationSeconds()
        val msg = "Готовится горячий паёк (${duration.toInt()} с)..."
        return EngineStepOutcome(
            run.copy(
                inventory = spent.first,
                cookingRemainingSec = duration,
                pendingCookedMealCount = run.pendingCookedMealCount + 1,
                lastStatusMessage = msg
            ),
            spent.second,
            true,
            msg
        )
    }

    /**
     * Отдых в укрытии базы с кулдауном восстановления сил.
     */
    fun restInBaseShelter(run: GameRunState, base: BaseProgress): EngineStepOutcome {
        if (run.restCooldownRemainingSec > 0f) {
            val msg = "Укрытие готовится к следующему отдыху (${run.restCooldownRemainingSec.toInt()} с)."
            return EngineStepOutcome(run.copy(lastStatusMessage = msg), base, false, msg)
        }
        val heal = 30f + (base.shelterLevel - 1) * 6f
        val msg = "Отдых у очага восстановил +${heal.toInt()} здоровья и согрел персонажа!"
        return EngineStepOutcome(
            run.copy(
                health = (run.health + heal).coerceAtMost(run.maxHealth),
                warmth = (run.warmth + 35f).coerceAtMost(GameRunState.MAX_STAT),
                restCooldownRemainingSec = GameRunState.REST_COOLDOWN_SECONDS,
                lastStatusMessage = msg
            ),
            base,
            true,
            msg
        )
    }

    /**
     * Rewarded-награда А: возрождение после смерти СТРОГО один раз за забег на Базовой поляне
     * с 50% здоровья, 50% голода и 50% тепла. Вызывается ТОЛЬКО из колбэка onRewarded!
     */
    fun applyRewardedRevive(run: GameRunState, base: BaseProgress): EngineStepOutcome {
        if (run.phase != RunPhase.DEAD) {
            return EngineStepOutcome(run, base, false, "Возрождение доступно только после гибели.")
        }
        if (run.revivesUsedInRun >= GameRunState.MAX_REVIVES_PER_RUN) {
            return EngineStepOutcome(
                run.copy(lastStatusMessage = "Лимит возрождений за этот забег уже исчерпан."),
                base,
                false,
                "Лимит возрождений за этот забег уже исчерпан."
            )
        }
        val revivedRun = run.copy(
            phase = RunPhase.PLAYING,
            currentLocation = LocationId.BASE_CLEARING,
            playerX = 0f,
            playerY = WorldPopulator.terrainHeight(LocationId.BASE_CLEARING, 0f, 5.5f),
            playerZ = 5.5f,
            health = run.maxHealth * 0.5f,
            hunger = GameRunState.MAX_STAT * 0.5f,
            warmth = GameRunState.MAX_STAT * 0.5f,
            revivesUsedInRun = run.revivesUsedInRun + 1,
            lastStatusMessage = "Вы пришли в себя у костра на Базовой поляне (50% сил)."
        )
        return EngineStepOutcome(revivedRun, base, true, revivedRun.lastStatusMessage)
    }

    /**
     * Rewarded-награда Б: немедленное завершение активного таймера/кулдауна (не более 3 раз в игровой день = 6 мин).
     * НЕ выдаёт лишних ресурсов сверх штатного завершения таймера и НИКОГДА не выдаёт спецлут.
     * Вызывается ТОЛЬКО из колбэка onRewarded!
     */
    fun applyRewardedSkipTimer(run: GameRunState, base: BaseProgress): EngineStepOutcome {
        val currentBucket = floor(
            (run.elapsedRealSeconds / GameRunState.GAME_DAY_SKIP_WINDOW_SECONDS).toDouble()
        ).toInt()
        val usedInBucket = if (currentBucket > run.currentSkipWindowIndex) {
            0
        } else {
            run.skipsUsedInCurrentWindow
        }

        if (usedInBucket >= GameRunState.MAX_REWARDED_SKIPS_PER_GAME_DAY) {
            val msg = "Достигнут лимит пропусков ожидания на этот игровой день (3/3)."
            return EngineStepOutcome(
                run.copy(
                    skipsUsedInCurrentWindow = usedInBucket,
                    currentSkipWindowIndex = currentBucket,
                    lastStatusMessage = msg
                ),
                base,
                false,
                msg
            )
        }

        val activeTimer = run.activeSkippableTimer()
            ?: return EngineStepOutcome(
                run.copy(lastStatusMessage = "Сейчас нет активного таймера для пропуска."),
                base,
                false,
                "Сейчас нет активного таймера для пропуска."
            )

        val nextSkips = usedInBucket + 1
        return when (activeTimer.first) {
            ActiveTimerType.COOKING -> {
                val meals = run.pendingCookedMealCount.coerceAtLeast(1)
                val msg = "Готовка завершена! Осталось пропусков сегодня: ${GameRunState.MAX_REWARDED_SKIPS_PER_GAME_DAY - nextSkips}."
                EngineStepOutcome(
                    run.copy(
                        cookingRemainingSec = 0f,
                        pendingCookedMealCount = 0,
                        hunger = (run.hunger + 42f * meals).coerceAtMost(GameRunState.MAX_STAT),
                        warmth = (run.warmth + 20f * meals).coerceAtMost(GameRunState.MAX_STAT),
                        health = (run.health + 16f * meals).coerceAtMost(run.maxHealth),
                        skipsUsedInCurrentWindow = nextSkips,
                        currentSkipWindowIndex = currentBucket,
                        lastStatusMessage = msg
                    ),
                    base,
                    true,
                    msg
                )
            }
            ActiveTimerType.SHELTER_REST -> {
                val msg = "Укрытие снова готово к отдыху! Осталось пропусков сегодня: ${GameRunState.MAX_REWARDED_SKIPS_PER_GAME_DAY - nextSkips}."
                EngineStepOutcome(
                    run.copy(
                        restCooldownRemainingSec = 0f,
                        skipsUsedInCurrentWindow = nextSkips,
                        currentSkipWindowIndex = currentBucket,
                        lastStatusMessage = msg
                    ),
                    base,
                    true,
                    msg
                )
            }
            ActiveTimerType.NODE_RESPAWN -> {
                val nearestDepleted = run.currentLocationNodes()
                    .filter { !it.collectedPermanent && it.respawnRemainingSec > 0f }
                    .minByOrNull { run.distSqToPlayer(it) }
                val updatedMap = if (nearestDepleted != null) {
                    replaceNode(
                        run.nodesByLocation,
                        run.currentLocation,
                        nearestDepleted.copy(respawnRemainingSec = 0f)
                    )
                } else {
                    run.nodesByLocation
                }
                val msg = "Ресурсный узел восстановлен! Осталось пропусков сегодня: ${GameRunState.MAX_REWARDED_SKIPS_PER_GAME_DAY - nextSkips}."
                EngineStepOutcome(
                    run.copy(
                        nodesByLocation = updatedMap,
                        skipsUsedInCurrentWindow = nextSkips,
                        currentSkipWindowIndex = currentBucket,
                        lastStatusMessage = msg
                    ),
                    base,
                    true,
                    msg
                )
            }
        }
    }

    fun selectStarterLoadout(base: BaseProgress, loadout: StarterLoadout): BaseProgress =
        base.copy(selectedLoadout = loadout)

    fun toggleWarmCoat(base: BaseProgress, equipped: Boolean): BaseProgress =
        if (SpecialLootId.WARM_COAT in base.persistentSpecialLoot) {
            base.copy(warmCoatEquipped = equipped)
        } else {
            base
        }

    fun totalAvailableResource(
        run: GameRunState,
        base: BaseProgress,
        type: ResourceType
    ): Int = run.inventory.countOf(type) + (base.storageResources[type] ?: 0)

    private fun spendResourcesAcrossInventoryAndBase(
        inventory: Inventory,
        base: BaseProgress,
        cost: Map<ResourceType, Int>
    ): Pair<Inventory, BaseProgress>? {
        for ((type, required) in cost) {
            val total = inventory.countOf(type) + (base.storageResources[type] ?: 0)
            if (total < required) return null
        }

        var workingInv = inventory
        val workingStorage = base.storageResources.toMutableMap()
        for ((type, required) in cost) {
            val inStorage = workingStorage[type] ?: 0
            val takeFromStorage = minOf(inStorage, required)
            if (takeFromStorage > 0) {
                val left = inStorage - takeFromStorage
                if (left == 0) workingStorage.remove(type) else workingStorage[type] = left
            }
            val takeFromInv = required - takeFromStorage
            if (takeFromInv > 0) {
                workingInv = workingInv.tryConsumeResource(type, takeFromInv) ?: return null
            }
        }
        return workingInv to base.copy(storageResources = workingStorage)
    }

    private fun applyXpGain(currentXp: Int, currentLevel: Int, addedXp: Int): Pair<Int, Int> {
        var xp = currentXp + addedXp.coerceAtLeast(0)
        var lvl = currentLevel
        while (xp >= lvl * GameRunState.XP_PER_LEVEL_STEP) {
            xp -= lvl * GameRunState.XP_PER_LEVEL_STEP
            lvl += 1
        }
        return xp to lvl
    }

    private fun replaceNode(
        map: Map<LocationId, List<ResourceNode>>,
        location: LocationId,
        replacement: ResourceNode
    ): Map<LocationId, List<ResourceNode>> {
        val updatedList = map[location].orEmpty().map { existing ->
            if (existing.id == replacement.id) replacement else existing
        }
        return map + (location to updatedList)
    }
}
