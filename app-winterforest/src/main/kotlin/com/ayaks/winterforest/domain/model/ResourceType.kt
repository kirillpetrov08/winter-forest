package com.ayaks.winterforest.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ResourceCategory {
    FUEL,
    FOOD,
    MATERIAL
}

@Serializable
enum class ResourceType(
    val titleRu: String,
    val titleEn: String,
    val category: ResourceCategory,
    val hungerRestore: Float = 0f,
    val warmthRestore: Float = 0f,
    val healthRestore: Float = 0f,
    val xpReward: Int = 5
) {
    WOOD(
        titleRu = "Дерево",
        titleEn = "Firewood",
        category = ResourceCategory.FUEL,
        warmthRestore = 22f,
        xpReward = 5
    ),
    BERRIES(
        titleRu = "Ягоды",
        titleEn = "Forest Berries",
        category = ResourceCategory.FOOD,
        hungerRestore = 18f,
        healthRestore = 2f,
        xpReward = 6
    ),
    STONE(
        titleRu = "Камень",
        titleEn = "Stone",
        category = ResourceCategory.MATERIAL,
        xpReward = 7
    ),
    CLOTH(
        titleRu = "Ткань",
        titleEn = "Cloth",
        category = ResourceCategory.MATERIAL,
        xpReward = 8
    ),
    RESIN(
        titleRu = "Смола",
        titleEn = "Pine Resin",
        category = ResourceCategory.FUEL,
        warmthRestore = 15f,
        xpReward = 9
    ),
    FISH(
        titleRu = "Рыба",
        titleEn = "Lake Fish",
        category = ResourceCategory.FOOD,
        hungerRestore = 36f,
        healthRestore = 5f,
        xpReward = 12
    ),
    ICE(
        titleRu = "Лёд",
        titleEn = "Glacial Ice",
        category = ResourceCategory.MATERIAL,
        xpReward = 8
    ),
    COAL(
        titleRu = "Уголь",
        titleEn = "Coal",
        category = ResourceCategory.FUEL,
        warmthRestore = 46f,
        xpReward = 14
    ),
    IRON(
        titleRu = "Железо",
        titleEn = "Iron Ore",
        category = ResourceCategory.MATERIAL,
        xpReward = 16
    ),
    MEAT(
        titleRu = "Мясо",
        titleEn = "Venison Meat",
        category = ResourceCategory.FOOD,
        hungerRestore = 45f,
        healthRestore = 8f,
        xpReward = 16
    ),
    HIDE(
        titleRu = "Шкуры",
        titleEn = "Animal Hide",
        category = ResourceCategory.MATERIAL,
        xpReward = 14
    );

    val isEdible: Boolean get() = hungerRestore > 0f
    val isFuel: Boolean get() = warmthRestore > 0f
}
