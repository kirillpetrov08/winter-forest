package com.ayaks.winterforest.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class GraphicsQuality(
    val titleRu: String,
    val subtitleRu: String,
    val textureSize: Int,
    val terrainSteps: Int,
    val perimeterTrees: Int,
    val snowParticles: Int,
    val fogDistanceScale: Float
) {
    LOW(
        titleRu = "Низкое",
        subtitleRu = "Максимум FPS для бюджетных устройств",
        textureSize = 256,
        terrainSteps = 56,
        perimeterTrees = 20,
        snowParticles = 45,
        fogDistanceScale = 0.85f
    ),
    MEDIUM(
        titleRu = "Среднее",
        subtitleRu = "Плавный рендеринг без лимита FPS и сбалансированная дальность",
        textureSize = 512,
        terrainSteps = 84,
        perimeterTrees = 30,
        snowParticles = 100,
        fogDistanceScale = 0.96f
    ),
    HIGH(
        titleRu = "Высокое",
        subtitleRu = "Детальные текстуры 1024px, тени АО, сияние и густой лес",
        textureSize = 1024,
        terrainSteps = 120,
        perimeterTrees = 42,
        snowParticles = 185,
        fogDistanceScale = 1.05f
    ),
    ULTRA(
        titleRu = "Ультра",
        subtitleRu = "Полные 2048px-текстуры каждого материала, плотный рельеф и буря",
        textureSize = 2048,
        terrainSteps = 160,
        perimeterTrees = 54,
        snowParticles = 290,
        fogDistanceScale = 1.18f
    );

    companion object {
        fun fromOrdinal(index: Int): GraphicsQuality =
            entries.getOrElse(index.coerceIn(0, entries.lastIndex)) { HIGH }
    }
}

@Serializable
enum class EquippedTool(
    val titleRu: String,
    val shortLabelRu: String,
    val reachMeters: Float,
    val swingDurationSec: Float
) {
    AXE(
        titleRu = "Топор лесоруба",
        shortLabelRu = "Топор",
        reachMeters = 4.1f,
        swingDurationSec = 0.34f
    ),
    PICKAXE(
        titleRu = "Горняцкое кайло",
        shortLabelRu = "Кирка",
        reachMeters = 4.0f,
        swingDurationSec = 0.36f
    ),
    SPEAR(
        titleRu = "Охотничье копьё",
        shortLabelRu = "Копьё",
        reachMeters = 5.3f,
        swingDurationSec = 0.28f
    ),
    TORCH(
        titleRu = "Смоляной факел",
        shortLabelRu = "Факел",
        reachMeters = 3.8f,
        swingDurationSec = 0.32f
    )
}

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
enum class RecipeCategory(val titleRu: String) {
    TOOLS("Инструменты"),
    FOOD("Еда"),
    FIRE("Огонь"),
    GEAR("Снаряжение")
}

@Serializable
enum class WorkbenchRecipe(
    val titleRu: String,
    val descriptionRu: String,
    val minWorkbenchLevel: Int,
    val requiresPickaxe: Boolean,
    val cost: Map<ResourceType, Int>,
    val category: RecipeCategory
) {
    CRAFT_RESIN_TORCH(
        titleRu = "Смоляной факел",
        descriptionRu = "+50% тепла, яркий свет на 3 минуты и защита от волков",
        minWorkbenchLevel = 1,
        requiresPickaxe = false,
        cost = mapOf(ResourceType.WOOD to 2, ResourceType.RESIN to 2),
        category = RecipeCategory.FIRE
    ),
    CRAFT_BERRY_TEA(
        titleRu = "Таёжный ягодный взвар",
        descriptionRu = "Горячий настой: +36 тепла, +26 сытости и +100% выносливости",
        minWorkbenchLevel = 1,
        requiresPickaxe = false,
        cost = mapOf(ResourceType.BERRIES to 3, ResourceType.WOOD to 1),
        category = RecipeCategory.FOOD
    ),
    CRAFT_ROAST_FEAST(
        titleRu = "Жаркое охотника",
        descriptionRu = "Сытный ужин у огня: +55 сытости, +24 тепла и +20 здоровья",
        minWorkbenchLevel = 1,
        requiresPickaxe = false,
        cost = mapOf(ResourceType.MEAT to 1, ResourceType.BERRIES to 2),
        category = RecipeCategory.FOOD
    ),
    CRAFT_HUNTING_SPEAR(
        titleRu = "Кованое копьё следопыта",
        descriptionRu = "Навсегда усиливает охоту копьём (+1 добычи и двойной урон зверю)",
        minWorkbenchLevel = 1,
        requiresPickaxe = false,
        cost = mapOf(ResourceType.WOOD to 3, ResourceType.STONE to 2),
        category = RecipeCategory.TOOLS
    ),
    CRAFT_WARM_COMPRESS(
        titleRu = "Тёплый перевязочный пакет",
        descriptionRu = "Мгновенно восстанавливает +28 здоровья и +15 тепла",
        minWorkbenchLevel = 2,
        requiresPickaxe = false,
        cost = mapOf(ResourceType.CLOTH to 2, ResourceType.BERRIES to 2),
        category = RecipeCategory.GEAR
    ),
    CRAFT_FUR_BOOTS(
        titleRu = "Меховые унты севера",
        descriptionRu = "Постоянный бонус: +14% к скорости шага по снегу и меньше расход сил",
        minWorkbenchLevel = 2,
        requiresPickaxe = false,
        cost = mapOf(ResourceType.HIDE to 2, ResourceType.CLOTH to 2),
        category = RecipeCategory.GEAR
    ),
    CRAFT_MINERS_HEATER(
        titleRu = "Угольная грелка (рецепт Кирки)",
        descriptionRu = "Открывается Киркой: +65 тепла и +20 здоровья в походе",
        minWorkbenchLevel = 2,
        requiresPickaxe = true,
        cost = mapOf(ResourceType.COAL to 2, ResourceType.IRON to 1),
        category = RecipeCategory.GEAR
    )
}

@Serializable
data class BaseProgress(
    val shelterLevel: Int = 1,
    val perks: Set<com.ayaks.winterforest.domain.rules.PerkId> = emptySet(),
    val perkPoints: Int = 0,
    val dailyDayIndex: Int = -1,
    val dailyProgress: Map<String, Int> = emptyMap(),
    val dailyClaimed: Set<String> = emptySet(),
    val campfireLevel: Int = 1,
    val workbenchLevel: Int = 1,
    val storageResources: Map<ResourceType, Int> = emptyMap(),
    val persistentSpecialLoot: Set<SpecialLootId> = emptySet(),
    val thermosInstalledOnBase: Boolean = false,
    val warmCoatEquipped: Boolean = false,
    val furBootsCrafted: Boolean = false,
    val masterSpearCrafted: Boolean = false,
    val totalWildlifeHunted: Int = 0,
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

    fun movementSpeedMultiplier(): Float = if (furBootsCrafted) 1.14f else 1.0f

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
