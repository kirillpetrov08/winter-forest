package com.ayaks.winterforest.domain.world

import com.ayaks.winterforest.domain.model.GameRunState
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.ResourceNode
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.SpecialLootId
import com.ayaks.winterforest.domain.model.WorldObjectKind
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object WorldPopulator {

    fun terrainHeight(location: LocationId, x: Float, z: Float): Float {
        // Плавная площадка у костра и построек на Базовой поляне и у ледяной глади озера
        val centerDist = sqrt(x * x + z * z)
        if (location == LocationId.FROZEN_LAKE && centerDist < 24f) {
            return -0.25f + 0.04f * sin(x * 0.25f) * cos(z * 0.25f)
        }
        val flatten = ((centerDist - 6f) / 8f).coerceIn(0f, 1f)
        val raw = when (location) {
            LocationId.BASE_CLEARING ->
                0.75f * sin(x * 0.09f) * cos(z * 0.08f) + 0.35f * sin((x + z) * 0.17f)
            LocationId.DENSE_SPRUCE ->
                1.15f * sin(x * 0.11f + 1.1f) * cos(z * 0.10f) + 0.45f * cos(x * 0.21f)
            LocationId.FROZEN_LAKE ->
                1.30f * ((centerDist - 22f) / 14f).coerceAtLeast(0f) + 0.25f * sin(x * 0.14f)
            LocationId.ABANDONED_MINE ->
                0.85f * cos(x * 0.13f) * sin(z * 0.12f) + 0.40f * sin(z * 0.26f)
            LocationId.HUNTERS_CABIN ->
                1.35f * sin(x * 0.08f + 0.6f) * cos(z * 0.09f - 0.4f) + 0.50f * sin(x * 0.19f)
        }
        return raw * flatten
    }

    fun generateAllLocations(
        seed: Long = 20261021L,
        alreadyFoundPersistentLoot: Set<SpecialLootId> = emptySet()
    ): Map<LocationId, List<ResourceNode>> =
        LocationId.entries.associateWith { loc ->
            generateLocationNodes(
                location = loc,
                seed = seed + loc.ordinal * 7919L,
                alreadyFoundPersistentLoot = alreadyFoundPersistentLoot
            )
        }

    fun generateLocationNodes(
        location: LocationId,
        seed: Long = 20261021L,
        alreadyFoundPersistentLoot: Set<SpecialLootId> = emptySet(),
        simulateFailureForTest: Boolean = false
    ): List<ResourceNode> {
        if (simulateFailureForTest) {
            throw IllegalStateException("Искусственная ошибка генерации локации ${location.name}")
        }
        val rng = Random(seed)
        val result = mutableListOf<ResourceNode>()
        val weights = location.resourceWeights.entries.toList()
        val totalWeight = weights.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(0.01f)

        val nodeCount = when (location) {
            LocationId.BASE_CLEARING -> 18
            LocationId.DENSE_SPRUCE -> 24
            LocationId.FROZEN_LAKE -> 16
            LocationId.ABANDONED_MINE -> 18
            LocationId.HUNTERS_CABIN -> 18
        }

        for (index in 0 until nodeCount) {
            val angle = (index.toFloat() / nodeCount.toFloat()) * (Math.PI.toFloat() * 2f) +
                rng.nextFloat() * 0.35f
            val radius = 7.5f + rng.nextFloat() * (GameRunState.WORLD_HALF_BOUND - 11.5f)
            val x = (cos(angle) * radius).coerceIn(-34f, 34f)
            val z = (sin(angle) * radius).coerceIn(-34f, 34f)

            val roll = rng.nextFloat() * totalWeight
            var acc = 0f
            var chosenType = weights.first().key
            for ((resType, w) in weights) {
                acc += w
                if (roll <= acc) {
                    chosenType = resType
                    break
                }
            }

            val kind = kindForResource(location, chosenType)
            val yieldAmount = rng.nextInt(1, 4) // строго 1..3 единицы ресурса по ТЗ

            result += ResourceNode(
                id = "${location.name.lowercase()}_node_$index",
                location = location,
                kind = kind,
                x = x,
                z = z,
                resourceType = chosenType,
                specialLootId = null,
                baseYield = yieldAmount
            )
        }

        // Уникальный тайник специального лута локации (фиксированная ориентирная точка рядом с центром/тропой)
        val specialLoot = location.uniqueLoot
        val alreadyCollectedPermanent =
            specialLoot.persistsAcrossRuns && specialLoot in alreadyFoundPersistentLoot
        val (sx, sz) = specialCacheCoordinates(location)
        result += ResourceNode(
            id = "${location.name.lowercase()}_special_${specialLoot.name.lowercase()}",
            location = location,
            kind = WorldObjectKind.SPECIAL_CACHE,
            x = sx,
            z = sz,
            resourceType = null,
            specialLootId = specialLoot,
            baseYield = 1,
            collectedPermanent = alreadyCollectedPermanent
        )

        return result
    }

    fun specialCacheCoordinates(location: LocationId): Pair<Float, Float> = when (location) {
        LocationId.BASE_CLEARING -> 4.2f to -3.6f
        LocationId.DENSE_SPRUCE -> -9.5f to -11.0f
        LocationId.FROZEN_LAKE -> 10.5f to -8.5f
        LocationId.ABANDONED_MINE -> -8.0f to 10.0f
        LocationId.HUNTERS_CABIN -> 9.0f to 9.5f
    }

    private fun kindForResource(location: LocationId, type: ResourceType): WorldObjectKind =
        when (type) {
            ResourceType.WOOD -> WorldObjectKind.PINE_TREE
            ResourceType.BERRIES -> WorldObjectKind.BERRY_BUSH
            ResourceType.STONE -> WorldObjectKind.STONE_OUTCROP
            ResourceType.CLOTH -> WorldObjectKind.SUPPLY_CRATE
            ResourceType.RESIN -> WorldObjectKind.RESIN_STUMP
            ResourceType.FISH -> WorldObjectKind.ICE_HOLE
            ResourceType.ICE -> if (location == LocationId.FROZEN_LAKE) {
                WorldObjectKind.STONE_OUTCROP
            } else {
                WorldObjectKind.SUPPLY_CRATE
            }
            ResourceType.COAL -> WorldObjectKind.COAL_SEAM
            ResourceType.IRON -> WorldObjectKind.IRON_VEIN
            ResourceType.MEAT, ResourceType.HIDE -> WorldObjectKind.GAME_CARCASS
        }
}
