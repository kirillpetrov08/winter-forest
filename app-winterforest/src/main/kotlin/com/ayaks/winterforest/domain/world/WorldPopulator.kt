package com.ayaks.winterforest.domain.world

import com.ayaks.winterforest.domain.model.GameRunState
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.ResourceNode
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.SpecialLootId
import com.ayaks.winterforest.domain.model.WildlifeEntity
import com.ayaks.winterforest.domain.model.WildlifeKind
import com.ayaks.winterforest.domain.model.WorldObjectKind
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object WorldPopulator {

    fun terrainHeight(location: LocationId, x: Float, z: Float): Float {
        val centerDist = sqrt(x * x + z * z)
        if (location == LocationId.FROZEN_LAKE && centerDist < 23.5f) {
            return -0.26f + 0.03f * sin(x * 0.30f) * cos(z * 0.30f)
        }
        val flatten = ((centerDist - 6.2f) / 8.0f).coerceIn(0f, 1f)
        val microDrift = 0.14f * sin(x * 0.38f + z * 0.21f) + 0.08f * cos(x * 0.62f - z * 0.44f)
        val raw = when (location) {
            LocationId.BASE_CLEARING ->
                0.85f * sin(x * 0.085f) * cos(z * 0.075f) + 0.38f * sin((x + z) * 0.15f) + microDrift
            LocationId.DENSE_SPRUCE ->
                1.35f * sin(x * 0.105f + 1.1f) * cos(z * 0.095f) + 0.52f * cos(x * 0.20f) + microDrift
            LocationId.FROZEN_LAKE ->
                1.55f * ((centerDist - 22.5f) / 13f).coerceAtLeast(0f) + 0.28f * sin(x * 0.14f) + microDrift * 0.6f
            LocationId.ABANDONED_MINE ->
                0.95f * cos(x * 0.125f) * sin(z * 0.115f) + 0.42f * sin(z * 0.25f) + microDrift * 0.5f
            LocationId.HUNTERS_CABIN ->
                1.55f * sin(x * 0.078f + 0.6f) * cos(z * 0.088f - 0.4f) + 0.58f * sin(x * 0.18f) + microDrift
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

    fun generateAllWildlife(seed: Long = 20261021L): Map<LocationId, List<WildlifeEntity>> =
        LocationId.entries.associateWith { loc ->
            generateLocationWildlife(
                location = loc,
                seed = seed + 91_823L + loc.ordinal * 4999L
            )
        }

    fun generateLocationWildlife(
        location: LocationId,
        seed: Long = 20261021L
    ): List<WildlifeEntity> {
        val rng = Random(seed)
        // На Базовой поляне — только мирная дичь (зайцы и северный олень), без хищников!
        val roster: List<WildlifeKind> = when (location) {
            LocationId.BASE_CLEARING -> listOf(
                WildlifeKind.SNOW_HARE,
                WildlifeKind.SNOW_HARE,
                WildlifeKind.NORTHERN_DEER
            )
            LocationId.DENSE_SPRUCE -> listOf(
                WildlifeKind.SNOW_HARE,
                WildlifeKind.SNOW_HARE,
                WildlifeKind.NORTHERN_DEER,
                WildlifeKind.TIMBER_WOLF
            )
            LocationId.FROZEN_LAKE -> listOf(
                WildlifeKind.SNOW_HARE,
                WildlifeKind.NORTHERN_DEER,
                WildlifeKind.TIMBER_WOLF
            )
            LocationId.ABANDONED_MINE -> listOf(
                WildlifeKind.SNOW_HARE,
                WildlifeKind.TIMBER_WOLF
            )
            LocationId.HUNTERS_CABIN -> listOf(
                WildlifeKind.SNOW_HARE,
                WildlifeKind.NORTHERN_DEER,
                WildlifeKind.NORTHERN_DEER,
                WildlifeKind.TIMBER_WOLF,
                WildlifeKind.TIMBER_WOLF
            )
        }

        return roster.mapIndexed { idx, kind ->
            val angle = (idx.toFloat() / roster.size.toFloat()) * (Math.PI.toFloat() * 2f) + 0.55f + rng.nextFloat() * 0.4f
            val radius = if (kind.isPredator) {
                20f + rng.nextFloat() * 12f
            } else {
                12f + rng.nextFloat() * 16f
            }
            WildlifeEntity(
                id = "${location.name.lowercase()}_fauna_$idx",
                kind = kind,
                x = (cos(angle) * radius).coerceIn(-33f, 33f),
                z = (sin(angle) * radius).coerceIn(-33f, 33f),
                headingRad = rng.nextFloat() * (Math.PI.toFloat() * 2f),
                hp = kind.maxHp
            )
        }
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
            LocationId.BASE_CLEARING -> 24
            LocationId.DENSE_SPRUCE -> 32
            LocationId.FROZEN_LAKE -> 22
            LocationId.ABANDONED_MINE -> 24
            LocationId.HUNTERS_CABIN -> 26
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
            val yieldAmount = rng.nextInt(1, 4)

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
