package com.ayaks.winterforest.presentation.navigation

import com.ayaks.winterforest.data.ads.AdConfig

enum class AppDestination(val route: String, val titleRu: String) {
    MAIN_MENU("main_menu", "Главное меню"),
    // §7.4: класс выбирается ПЕРЕД каждым новым забегом, а не в настройках базы
    LOADOUT_SELECT("loadout_select", "Выбор класса"),
    GAME("game", "Зимний лес"),
    BASE_INVENTORY("base_inventory", "База и инвентарь"),
    RESULTS("results", "Итоги выживания"),
    SETTINGS("settings", "Настройки"),
    ABOUT("about", "Об игре")
}

object BannerPlacementPolicy {
    /**
     * Единственный экран во всём приложении, где разрешён показ адаптивного баннера РСЯ —
     * строго экран главного меню и только при непустом идентификаторе блока.
     */
    fun shouldShowBanner(destination: AppDestination, adConfig: AdConfig): Boolean =
        destination == AppDestination.MAIN_MENU && adConfig.isBannerEnabled
}
