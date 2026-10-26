package com.ayaks.winterforest.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class LocationPalette(
    val skyZenithHex: Int,
    val skyHorizonHex: Int,
    val fogHex: Int,
    val snowTintHex: Int,
    val sunLightHex: Int,
    val fogNear: Float,
    val fogFar: Float
)

@Serializable
data class UnlockStatus(
    val isUnlocked: Boolean,
    val conditionSummaryRu: String,
    val missingRequirementRu: String = ""
)

@Serializable
enum class LocationId(
    val titleRu: String,
    val titleEn: String,
    val subtitleRu: String,
    val uniqueLoot: SpecialLootId,
    val snowfallChance: Float,
    val coldMultiplier: Float,
    val requiresLightForVisibility: Boolean,
    val isSafeBase: Boolean,
    val palette: LocationPalette,
    val resourceWeights: Map<ResourceType, Float>
) {
    BASE_CLEARING(
        titleRu = "Базовая поляна",
        titleEn = "Base Clearing",
        subtitleRu = "Безопасная стартовая стоянка с костром, укрытием и верстаком",
        uniqueLoot = SpecialLootId.OLD_MAP,
        snowfallChance = 0.20f,
        coldMultiplier = 0.85f,
        requiresLightForVisibility = false,
        isSafeBase = true,
        palette = LocationPalette(
            skyZenithHex = 0x1A3B5C,
            skyHorizonHex = 0x8CAFC9,
            fogHex = 0xB8CFE0,
            snowTintHex = 0xEAF3FA,
            sunLightHex = 0xFFE6C7,
            fogNear = 18f,
            fogFar = 58f
        ),
        resourceWeights = mapOf(
            ResourceType.WOOD to 1.0f,
            ResourceType.BERRIES to 1.0f,
            ResourceType.STONE to 0.75f,
            ResourceType.CLOTH to 0.35f
        )
    ),
    DENSE_SPRUCE(
        titleRu = "Густой ельник",
        titleEn = "Dense Spruce Forest",
        subtitleRu = "Вековые заснеженные ели, частые снегопады и богатые запасы смолы",
        uniqueLoot = SpecialLootId.RESIN_TORCH,
        snowfallChance = 0.80f,
        coldMultiplier = 1.15f,
        requiresLightForVisibility = false,
        isSafeBase = false,
        palette = LocationPalette(
            skyZenithHex = 0x13292A,
            skyHorizonHex = 0x5C787E,
            fogHex = 0x8BA3A8,
            snowTintHex = 0xCFE0E5,
            sunLightHex = 0xD7E8EC,
            fogNear = 12f,
            fogFar = 42f
        ),
        // Смола убрана из весов: она автоматически выпадает при срубе дерева
        resourceWeights = mapOf(
            ResourceType.WOOD to 2.6f,
            ResourceType.BERRIES to 0.75f,
            ResourceType.STONE to 0.5f
        )
    ),
    FROZEN_LAKE(
        titleRu = "Замёрзшее озеро",
        titleEn = "Frozen Lake",
        subtitleRu = "Открытая ледяная гладь, пронизывающий ветер и рыбацкие лунки",
        uniqueLoot = SpecialLootId.THERMOS,
        snowfallChance = 0.45f,
        coldMultiplier = 1.55f,
        requiresLightForVisibility = false,
        isSafeBase = false,
        palette = LocationPalette(
            skyZenithHex = 0x163A59,
            skyHorizonHex = 0x9AD0E8,
            fogHex = 0xC4E4F2,
            snowTintHex = 0xDDF2FC,
            sunLightHex = 0xEAF8FF,
            fogNear = 16f,
            fogFar = 54f
        ),
        resourceWeights = mapOf(
            ResourceType.FISH to 1.35f,
            ResourceType.ICE to 1.50f,
            ResourceType.WOOD to 0.45f,
            ResourceType.CLOTH to 0.50f
        )
    ),
    ABANDONED_MINE(
        titleRu = "Заброшенная шахта",
        titleEn = "Abandoned Mine",
        subtitleRu = "Тёмные штольни с залежами угля и железа — нужен свет факела",
        uniqueLoot = SpecialLootId.PICKAXE,
        snowfallChance = 0.15f,
        coldMultiplier = 1.25f,
        requiresLightForVisibility = true,
        isSafeBase = false,
        palette = LocationPalette(
            skyZenithHex = 0x10131A,
            skyHorizonHex = 0x2A303D,
            fogHex = 0x232833,
            snowTintHex = 0x8F99A8,
            sunLightHex = 0x9BA6B8,
            fogNear = 4.5f,
            fogFar = 19f
        ),
        resourceWeights = mapOf(
            ResourceType.COAL to 1.45f,
            ResourceType.IRON to 1.30f,
            ResourceType.STONE to 1.40f,
            ResourceType.WOOD to 0.40f
        )
    ),
    HUNTERS_CABIN(
        titleRu = "Охотничий домик",
        titleEn = "Hunter's Cabin",
        subtitleRu = "Удалённый горный перевал со следами зверя и старым зимовьем",
        uniqueLoot = SpecialLootId.WARM_COAT,
        snowfallChance = 0.35f,
        coldMultiplier = 1.35f,
        requiresLightForVisibility = false,
        isSafeBase = false,
        palette = LocationPalette(
            skyZenithHex = 0x241E38,
            skyHorizonHex = 0xB88B72,
            fogHex = 0xCBB2A6,
            snowTintHex = 0xEFE6E1,
            sunLightHex = 0xFFD2A6,
            fogNear = 15f,
            fogFar = 50f
        ),
        resourceWeights = mapOf(
            ResourceType.MEAT to 1.30f,
            ResourceType.HIDE to 1.25f,
            ResourceType.WOOD to 0.90f,
            ResourceType.CLOTH to 0.65f
        )
    );

    fun neighborNext(): LocationId {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }

    companion object {
        const val WOOD_REQUIRED_FOR_SPRUCE = 20
        const val LEVEL_REQUIRED_FOR_LAKE = 3
        const val STONE_REQUIRED_FOR_MINE = 10
        const val DAYS_REQUIRED_FOR_CABIN = 3
        const val CLOTH_REQUIRED_FOR_CABIN = 5
    }
}
