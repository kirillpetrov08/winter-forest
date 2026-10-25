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
import kotlin.math.hypot
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
        val microDrift = 0.08f * sin(x * 0.38f + z * 0.21f) + 0.05f * cos(x * 0.62f - z * 0.44f) +
            0.035f * sin(x * 1.15f - z * 0.9f) + 0.022f * cos(x * 1.8f + z * 1.55f)
        val raw = when (location) {
            // Импортированный ландшафт «Snowy Night Scene» (запечённая сетка высот)
            LocationId.BASE_CLEARING ->
                ImportedTerrain.heightAt(x, z)
            LocationId.DENSE_SPRUCE ->
                1.35f * sin(x * 0.105f + 1.1f) * cos(z * 0.095f) + 0.52f * cos(x * 0.20f) +
                0.34f * sin(x * 0.31f + z * 0.27f) + microDrift
            LocationId.FROZEN_LAKE ->
                1.55f * ((centerDist - 22.5f) / 13f).coerceAtLeast(0f) + 0.28f * sin(x * 0.14f) + microDrift * 0.6f
            LocationId.ABANDONED_MINE ->
                0.95f * cos(x * 0.125f) * sin(z * 0.115f) + 0.42f * sin(z * 0.25f) +
                0.30f * cos(x * 0.29f - z * 0.33f) + microDrift * 0.5f
            LocationId.HUNTERS_CABIN ->
                1.55f * sin(x * 0.078f + 0.6f) * cos(z * 0.088f - 0.4f) + 0.58f * sin(x * 0.18f) +
                0.36f * cos(x * 0.24f + z * 0.29f) + microDrift
        }
        var height = raw * flatten
        // Дом охотника стоит на РОВНОЙ поляне (иначе углы сруба висят над склоном)
        if (location == LocationId.HUNTERS_CABIN) {
            height = plateauBlend(height, x, z, 7.2f, 8.8f, 15f, 26f)
        }
        // Площадка штольни с рельсами — ровная траншея
        if (location == LocationId.ABANDONED_MINE) {
            val corridor = (1f - (Math.abs(x) / 12f).coerceIn(0f, 1f)) *
                (1f - ((Math.abs(z) - 28f) / 14f).coerceIn(0f, 1f))
            if (corridor > 0f) {
                val flatY = 0.12f
                height = height * (1f - corridor) + flatY * corridor
            }
        }
        return height
    }

    /** Плавно выравнивает рельеф до плато вокруг (px, pz): полный штиль внутри r0, переход до r1. */
    private fun plateauBlend(
        height: Float,
        x: Float,
        z: Float,
        px: Float,
        pz: Float,
        r0: Float,
        r1: Float
    ): Float {
        val d = hypot(x - px, z - pz)
        if (d >= r1) return height
        val plateau = terrainHeightRawAt(location = LocationId.HUNTERS_CABIN, x = px, z = pz)
        val t = (1f - (d - r0) / (r1 - r0)).coerceIn(0f, 1f)
        val s = t * t * (3f - 2f * t)
        return height * (1f - s) + plateau * s
    }

    /** Высота без плато — для опорной точки плато (избегаем рекурсии). */
    private fun terrainHeightRawAt(location: LocationId, x: Float, z: Float): Float {
        val centerDist = sqrt(x * x + z * z)
        val flatten = ((centerDist - 6.2f) / 8.0f).coerceIn(0f, 1f)
        val micro = 0.08f * sin(x * 0.38f + z * 0.21f) + 0.05f * cos(x * 0.62f - z * 0.44f) +
            0.035f * sin(x * 1.15f - z * 0.9f) + 0.022f * cos(x * 1.8f + z * 1.55f)
        val raw = when (location) {
            LocationId.BASE_CLEARING -> ImportedTerrain.heightAt(x, z)
            LocationId.DENSE_SPRUCE ->
                1.35f * sin(x * 0.105f + 1.1f) * cos(z * 0.095f) + 0.52f * cos(x * 0.20f) +
                0.34f * sin(x * 0.31f + z * 0.27f) + micro
            LocationId.FROZEN_LAKE ->
                1.55f * ((centerDist - 22.5f) / 13f).coerceAtLeast(0f) + 0.28f * sin(x * 0.14f) + micro * 0.6f
            LocationId.ABANDONED_MINE ->
                0.95f * cos(x * 0.125f) * sin(z * 0.115f) + 0.42f * sin(z * 0.25f) +
                0.30f * cos(x * 0.29f - z * 0.33f) + micro * 0.5f
            LocationId.HUNTERS_CABIN ->
                1.55f * sin(x * 0.078f + 0.6f) * cos(z * 0.088f - 0.4f) + 0.58f * sin(x * 0.18f) +
                0.36f * cos(x * 0.24f + z * 0.29f) + micro
        }
        return raw * flatten
    }

    /**
     * Единая высота БОЛЬШОГО мира в глобальных координатах: рельеф района
     * плавно смешивается с соседями на границах, по краю мира — снежный кряж.
     */
    fun worldHeight(x: Float, z: Float): Float {
        var wSum = 0f
        var hSum = 0f
        for (r in WorldRegions.REGIONS) {
            val w = WorldRegions.regionWeight(x, z, r)
            if (w <= 0f) continue
            wSum += w
            hSum += w * terrainHeight(r.id, x - r.cx, z - r.cz)
        }
        val base = if (wSum > 0.0001f) hSum / wSum else 0f
        // Внешний кряж по периметру мира
        val edge = maxOf(Math.abs(x), Math.abs(z))
        val ridge = ((edge - 158f) * 1.35f).coerceAtLeast(0f)
        return base + ridge
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
                WildlifeKind.TAIGA_RAT,
                WildlifeKind.SNOW_HARE,
                WildlifeKind.NORTHERN_DEER,
                WildlifeKind.NORTHERN_DEER
            )
            LocationId.DENSE_SPRUCE -> listOf(
                WildlifeKind.SNOW_HARE,
                WildlifeKind.SNOW_HARE,
                WildlifeKind.NORTHERN_DEER,
                WildlifeKind.NORTHERN_DEER,
                WildlifeKind.TIMBER_WOLF,
                WildlifeKind.TIMBER_WOLF
            )
            LocationId.FROZEN_LAKE -> listOf(
                WildlifeKind.SNOW_HARE,
                WildlifeKind.NORTHERN_DEER,
                WildlifeKind.NORTHERN_DEER,
                WildlifeKind.TIMBER_WOLF
            )
            LocationId.ABANDONED_MINE -> listOf(
                WildlifeKind.SNOW_HARE,
                WildlifeKind.TIMBER_WOLF,
                WildlifeKind.TIMBER_WOLF
            )
            LocationId.HUNTERS_CABIN -> listOf(
                WildlifeKind.SNOW_HARE,
                WildlifeKind.SNOW_HARE,
                WildlifeKind.NORTHERN_DEER,
                WildlifeKind.NORTHERN_DEER,
                WildlifeKind.TIMBER_WOLF,
                WildlifeKind.TIMBER_WOLF,
                WildlifeKind.TIMBER_WOLF
            )
        }

        val (cx, cz) = WorldRegions.centerOf(location)
        return roster.mapIndexed { idx, kind ->
            val angle = (idx.toFloat() / roster.size.toFloat()) * (Math.PI.toFloat() * 2f) + 0.55f + rng.nextFloat() * 0.4f
            val radius = if (kind.isPredator) {
                20f + rng.nextFloat() * 16f
            } else {
                12f + rng.nextFloat() * 20f
            }
            WildlifeEntity(
                id = "${location.name.lowercase()}_fauna_$idx",
                kind = kind,
                x = cx + (cos(angle) * radius).coerceIn(-42f, 42f),
                z = cz + (sin(angle) * radius).coerceIn(-42f, 42f),
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
            LocationId.BASE_CLEARING -> 46
            LocationId.DENSE_SPRUCE -> 62
            LocationId.FROZEN_LAKE -> 42
            LocationId.ABANDONED_MINE -> 50
            LocationId.HUNTERS_CABIN -> 52
        }

        for (index in 0 until nodeCount) {
            val angle = (index.toFloat() / nodeCount.toFloat()) * (Math.PI.toFloat() * 2f) +
                rng.nextFloat() * 0.35f
            val radius = 7.5f + rng.nextFloat() * 36.5f
            val x = (cos(angle) * radius).coerceIn(-46f, 46f)
            val z = (sin(angle) * radius).coerceIn(-46f, 46f)

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

            val kind = kindForResource(location, chosenType, index)
            val yieldAmount = rng.nextInt(1, 3)

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

        // Ящиков должно быть немного: оставляем до 4 обычных на район
        var cratesKept = 0
        val trimmed = result.filter { node ->
            if (node.kind != WorldObjectKind.SUPPLY_CRATE) {
                true
            } else if (cratesKept >= 4) {
                false
            } else {
                cratesKept += 1
                true
            }
        }.toMutableList()

        // 2 военных ящика с редким лутом на район (у троп и строений)
        val militarySpots = when (location) {
            LocationId.BASE_CLEARING -> listOf(11.4f to 8.2f, -14.2f to -12.4f)
            LocationId.DENSE_SPRUCE -> listOf(13.6f to 14.1f, -13.2f to -6.5f)
            LocationId.FROZEN_LAKE -> listOf(-15.4f to 12.8f, 8.9f to 14.6f)
            LocationId.ABANDONED_MINE -> listOf(12.8f to -12.2f, -13.6f to -8.4f)
            LocationId.HUNTERS_CABIN -> listOf(-11.8f to 13.4f, 13.2f to -6.2f)
        }
        militarySpots.forEachIndexed { mIdx, (mx, mz) ->
            trimmed += ResourceNode(
                id = "${location.name.lowercase()}_military_$mIdx",
                location = location,
                kind = WorldObjectKind.MILITARY_CRATE,
                x = mx,
                z = mz,
                resourceType = null,
                specialLootId = null,
                baseYield = 1
            )
        }
        result.clear()
        result.addAll(trimmed)

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

        // Глобальные координаты единого мира: район смещён от центра (0, 0)
        val (cx, cz) = WorldRegions.centerOf(location)
        return result.map { it.copy(x = it.x + cx, z = it.z + cz) }
    }

    fun specialCacheCoordinates(location: LocationId): Pair<Float, Float> = when (location) {
        LocationId.BASE_CLEARING -> 4.2f to -3.6f
        LocationId.DENSE_SPRUCE -> -9.5f to -11.0f
        LocationId.FROZEN_LAKE -> 10.5f to -8.5f
        LocationId.ABANDONED_MINE -> -8.0f to 10.0f
        LocationId.HUNTERS_CABIN -> 9.0f to 9.5f
    }

    private fun kindForResource(
        location: LocationId,
        type: ResourceType,
        index: Int
    ): WorldObjectKind =
        when (type) {
            // Каждый 4-й лесной узел — лиственное дерево пользователя (в заброшенной
            // шахте растёт только хвоя), каждый 3-й ягодный — полевой куст.
            ResourceType.WOOD ->
                if (index % 4 == 3 && location != LocationId.ABANDONED_MINE) {
                    WorldObjectKind.OAK_TREE
                } else {
                    WorldObjectKind.PINE_TREE
                }
            ResourceType.BERRIES ->
                if (index % 3 == 2) WorldObjectKind.SCRUB_BUSH else WorldObjectKind.BERRY_BUSH
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
            ResourceType.IRON_INGOT -> WorldObjectKind.IRON_VEIN
            ResourceType.MEAT, ResourceType.HIDE -> WorldObjectKind.GAME_CARCASS
        }
}
