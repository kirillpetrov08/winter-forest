package com.ayaks.winterforest.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class StarterLoadout(
    val titleRu: String,
    val descriptionRu: String,
    val items: Map<ResourceType, Int>
) {
    SURVIVOR(
        titleRu = "Путник",
        descriptionRu = "Сбалансированный запас: 4 дерева, 4 ягоды, 2 камня, 1 ткань",
        items = mapOf(
            ResourceType.WOOD to 4,
            ResourceType.BERRIES to 4,
            ResourceType.STONE to 2,
            ResourceType.CLOTH to 1
        )
    ),
    WOODCUTTER(
        titleRu = "Лесоруб",
        descriptionRu = "Упор на тепло и костёр: 8 дерева, 2 ягоды, 2 смолы",
        items = mapOf(
            ResourceType.WOOD to 8,
            ResourceType.BERRIES to 2,
            ResourceType.RESIN to 2
        )
    ),
    EXPLORER(
        titleRu = "Следопыт",
        descriptionRu = "Подготовка к дальним тропам: 3 дерева, 4 ягоды, 4 камня, 2 ткани",
        items = mapOf(
            ResourceType.WOOD to 3,
            ResourceType.BERRIES to 4,
            ResourceType.STONE to 4,
            ResourceType.CLOTH to 2
        )
    )
}

@Serializable
enum class BaseUpgradeType(
    val titleRu: String,
    val effectDescriptionRu: String
) {
    SHELTER(
        titleRu = "Укрытие",
        effectDescriptionRu = "Снижает скорость потери тепла на 8% за каждый уровень"
    ),
    CAMPFIRE(
        titleRu = "Костёр",
        effectDescriptionRu = "Ускоряет согревание у огня и сокращает время готовки на 15% за уровень"
    ),
    WORKBENCH(
        titleRu = "Верстак",
        effectDescriptionRu = "Открывает крафт Смоляного факела, рационов и продвинутых наборов (с Киркой — ур. 4–5)"
    )
}

@Serializable
enum class WorkbenchRecipe(
    val titleRu: String,
    val descriptionRu: String,
    val minWorkbenchLevel: Int,
    val requiresPickaxe: Boolean,
    val cost: Map<ResourceType, Int>
) {
    CRAFT_RESIN_TORCH(
        titleRu = "Смоляной факел",
        descriptionRu = "+50% тепла и яркий свет на 3 минуты (расходуемый)",
        minWorkbenchLevel = 1,
        requiresPickaxe = false,
        cost = mapOf(ResourceType.WOOD to 2, ResourceType.RESIN to 2)
    ),
    CRAFT_WARM_COMPRESS(
        titleRu = "Тёплый перевязочный пакет",
        descriptionRu = "Мгновенно восстанавливает +28 здоровья и +15 тепла",
        minWorkbenchLevel = 2,
        requiresPickaxe = false,
        cost = mapOf(ResourceType.CLOTH to 2, ResourceType.BERRIES to 2)
    ),
    CRAFT_MINERS_HEATER(
        titleRu = "Угольная грелка (рецепт Кирки)",
        descriptionRu = "Открывается Киркой: +65 тепла и +20 здоровья в походе",
        minWorkbenchLevel = 2,
        requiresPickaxe = true,
        cost = mapOf(ResourceType.COAL to 2, ResourceType.IRON to 1)
    )
}

@Serializable
data class BaseProgress(
    val shelterLevel: Int = 1,
    val campfireLevel: Int = 1,
    val workbenchLevel: Int = 1,
    val storageResources: Map<ResourceType, Int> = emptyMap(),
    val persistentSpecialLoot: Set<SpecialLootId> = emptySet(),
    val thermosInstalledOnBase: Boolean = false,
    val warmCoatEquipped: Boolean = false,
    val selectedLoadout: StarterLoadout = StarterLoadout.SURVIVOR,
    val bestDaysSurvived: Int = 0,
    val lifetimeWoodGathered: Int = 0,
    val completedRunsCount: Int = 0
) {
    val totalBaseLevel: Int get() = shelterLevel + campfireLevel + workbenchLevel

    fun levelOf(type: BaseUpgradeType): Int = when (type) {
        BaseUpgradeType.SHELTER -> shelterLevel
        BaseUpgradeType.CAMPFIRE -> campfireLevel
        BaseUpgradeType.WORKBENCH -> workbenchLevel
    }

    fun upgradeCost(type: BaseUpgradeType): Map<ResourceType, Int>? {
        val current = levelOf(type)
        if (current >= MAX_UPGRADE_LEVEL) return null
        return when (type) {
            BaseUpgradeType.SHELTER -> mapOf(
                ResourceType.WOOD to (4 * current),
                ResourceType.CLOTH to (2 * current),
                ResourceType.STONE to (2 * current)
            )
            BaseUpgradeType.CAMPFIRE -> mapOf(
                ResourceType.STONE to (4 * current),
                ResourceType.WOOD to (3 * current)
            )
            BaseUpgradeType.WORKBENCH -> {
                val base = mutableMapOf(
                    ResourceType.WOOD to (5 * current),
                    ResourceType.STONE to (3 * current)
                )
                if (current >= 3) {
                    base[ResourceType.IRON] = (current - 1)
                }
                base
            }
        }
    }

    fun warmthLossMultiplier(): Float {
        // Укрытие: -8% потери тепла за каждый уровень выше 1 (или -6% на ур. 1 .. -38% на ур. 5)
        val shelterFactor = (1f - (shelterLevel - 1) * 0.08f).coerceAtLeast(0.60f)
        val thermosFactor = if (thermosInstalledOnBase) {
            1f - SpecialLootId.THERMOS_WARMTH_LOSS_REDUCTION
        } else {
            1f
        }
        val coatFactor = if (warmCoatEquipped && SpecialLootId.WARM_COAT in persistentSpecialLoot) {
            1f - SpecialLootId.WARM_COAT_WARMTH_LOSS_REDUCTION
        } else {
            1f
        }
        return shelterFactor * thermosFactor * coatFactor
    }

    fun campfireRecoveryPerSec(): Float = 4.5f + (campfireLevel - 1) * 2.0f

    fun cookingDurationSeconds(): Float =
        (45f * (1f - (campfireLevel - 1) * 0.12f)).coerceAtLeast(15f)

    companion object {
        const val MAX_UPGRADE_LEVEL = 5
        val THERMOS_INSTALL_COST = mapOf(
            ResourceType.WOOD to 2,
            ResourceType.STONE to 2
        )
    }
}
