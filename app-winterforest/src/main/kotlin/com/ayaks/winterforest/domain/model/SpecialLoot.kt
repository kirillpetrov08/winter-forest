package com.ayaks.winterforest.domain.model

import kotlinx.serialization.Serializable

/**
 * Уникальный специальный лут пяти изучаемых локаций.
 * Выдаётся строго за исследование и выполнение условий локаций (либо крафт Смоляного факела).
 * Никогда не продаётся и никогда не выдаётся за просмотр рекламы.
 */
@Serializable
enum class SpecialLootId(
    val titleRu: String,
    val titleEn: String,
    val descriptionRu: String,
    val sourceLocationNameRu: String,
    val persistsAcrossRuns: Boolean,
    val isConsumable: Boolean,
    val isCraftable: Boolean,
    /** Путь PNG-иконки предмета в assets/icons (единый источник для HUD и меню). */
    val iconPath: String
) {
    OLD_MAP(
        titleRu = "Старая карта",
        titleEn = "Old Map",
        descriptionRu = "Мета-предмет: открывает отображение всех локаций и троп на карте экспедиции. Сохраняется между забегами.",
        sourceLocationNameRu = "Базовая поляна",
        persistsAcrossRuns = true,
        isConsumable = false,
        isCraftable = false,
        iconPath = "icons/icon_old_map.png"
    ),
    RESIN_TORCH(
        titleRu = "Смоляной факел",
        titleEn = "Resin Torch",
        descriptionRu = "Расходуемый предмет: даёт +50% эффективности тепла и освещает тёмные штольни на 3 игровые минуты. Сбрасывается при смерти, доступен повторный крафт из смолы.",
        sourceLocationNameRu = "Густой ельник / Верстак",
        persistsAcrossRuns = false,
        isConsumable = true,
        isCraftable = true,
        iconPath = "icons/icon_special_resin_torch.png"
    ),
    THERMOS(
        titleRu = "Термос",
        titleEn = "Insulated Thermos",
        descriptionRu = "Постоянное улучшение базы: после установки на базе снижает скорость потери тепла на 15%. Сохраняется между забегами.",
        sourceLocationNameRu = "Замёрзшее озеро",
        persistsAcrossRuns = true,
        isConsumable = false,
        isCraftable = false,
        iconPath = "icons/icon_thermos.png"
    ),
    PICKAXE(
        titleRu = "Кирка",
        titleEn = "Miner's Pickaxe",
        descriptionRu = "Постоянный инструмент: открывает новые рецепты и ступени улучшения верстака, повышает добычу камня и руды. Сохраняется между забегами.",
        sourceLocationNameRu = "Заброшенная шахта",
        persistsAcrossRuns = true,
        isConsumable = false,
        isCraftable = false,
        iconPath = "icons/icon_special_pickaxe.png"
    ),
    WARM_COAT(
        titleRu = "Тёплая шуба",
        titleEn = "Warm Fur Coat",
        descriptionRu = "Постоянная экипировка: заменяет обычную куртку и снижает потерю тепла на 25%. Сохраняется между забегами.",
        sourceLocationNameRu = "Охотничий домик",
        persistsAcrossRuns = true,
        isConsumable = false,
        isCraftable = false,
        iconPath = "icons/icon_warm_coat.png"
    ),
    SOLDIER_RATION(
        titleRu = "Сухпай",
        titleEn = "Soldier Ration",
        descriptionRu = "Редкий припас из ящиков: восстанавливает много сытости и немного тепла. Расходуемый предмет.",
        sourceLocationNameRu = "Военные ящики",
        persistsAcrossRuns = false,
        isConsumable = true,
        isCraftable = false,
        iconPath = "icons/icon_soldier_ration.png"
    ),
    HUNTING_KNIFE(
        titleRu = "Охотничий нож",
        titleEn = "Hunting Knife",
        descriptionRu = "Редкое оружие из ящиков: +3 урона по зверям в ближнем бою до конца забега.",
        sourceLocationNameRu = "Военные ящики",
        persistsAcrossRuns = false,
        isConsumable = false,
        isCraftable = false,
        iconPath = "icons/icon_hunting_knife.png"
    ),
    FIRST_AID_KIT(
        titleRu = "Полевая аптечка",
        titleEn = "First Aid Kit",
        descriptionRu = "Редкая вещь из военных ящиков: восстанавливает 45 здоровья. Расходуемый предмет.",
        sourceLocationNameRu = "Военные ящики",
        persistsAcrossRuns = false,
        isConsumable = true,
        isCraftable = false,
        iconPath = "icons/icon_first_aid_kit.png"
    );

    companion object {
        const val RESIN_TORCH_DURATION_SEC: Float = 180f // 3 игровые минуты
        const val THERMOS_WARMTH_LOSS_REDUCTION: Float = 0.15f // -15% потери тепла
        const val WARM_COAT_WARMTH_LOSS_REDUCTION: Float = 0.25f // -25% потери тепла
    }
}
