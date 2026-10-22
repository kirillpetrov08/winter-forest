# Модуль `:app-winterforest` — Android 3D Survival Game

Основной Android-модуль игры **Winter Forest** (`com.ayaks.winterforest`), написанный на **Kotlin + Jetpack Compose + OpenGL ES 2.0/3.0** с архитектурой **MVVM** (`domain` / `data` / `presentation`) и ручным DI (`AppContainer`).

## Структура пакетов

- `domain/`
  - `model/` — чистые модели ресурсов (`ResourceType`), инвентаря на 20 слотов (`Inventory`), прогресса базы (`BaseProgress`), 5 локаций (`LocationId`), уникального спецлута (`SpecialLootId`) и состояния забега (`GameRunState`).
  - `world/WorldPopulator.kt` — детерминированная генерация высот рельефа и ресурсных узлов каждой локации.
  - `rules/SurvivalEngine.kt` — все правила выживания (голод, холод, здоровье, 10-минутный цикл дня/ночи, сбор, прокачка базы, верстак, условия открытия локаций, возрождение и пропуск ожидания).
- `data/`
  - `db/` — база данных **AndroidX Room 2.8.5** (`WinterForestDatabase`) + кодек сохранений `SavePayloadCodec` с контрольной суммой **CRC32** и безопасной обработкой повреждённых файлов.
  - `preferences/` — **Jetpack DataStore Preferences 1.2.1** (`SettingsDataStore`) для громкости, чувствительности обзора и согласия пользователя (геолокация принудительно `false`).
  - `ads/` — интеграция **Yandex Mobile Ads SDK 8.6.0**: `AdConfig`, `YandexAdsPrivacyBootstrapper`, `RewardedAdController`.
- `presentation/`
  - `gl/` — собственный 3D-движок первого лица на `GLSurfaceView` + GLSL шейдерах освещения и тумана (`Shaders.kt`, `MeshBuilder.kt`, `ProceduralTextures.kt` с FBM/Перлином и Вороным, `ForestGlRenderer.kt`).
  - `navigation/` — маршруты экранов и политика показа баннера (`BannerPlacementPolicy`).
  - `viewmodel/` — `WinterForestViewModel` со `StateFlow` и игровым циклом.
  - `ui/` — тема `WinterForestTheme`, адаптивный баннер `MainMenuAdaptiveBanner` (только в главном меню!), джойстик, компас и все экраны Jetpack Compose.

## Локации и специальный лут

| Локация | Условие открытия | Специальный лут | Сохранение при смерти |
|---|---|---|---|
| Базовая поляна | С начала игры | Старая карта | Сохраняется навсегда |
| Густой ельник | 20 ед. дерева суммарно | Смоляной факел (+50% тепла на 3 мин, крафтится повторно) | Сбрасывается (расходуемый) |
| Замёрзшее озеро | Уровень персонажа ≥ 3 | Термос (-15% потери тепла после постройки на базе) | Сохраняется навсегда |
| Заброшенная шахта | «Старая карта» + 10 камня | Кирка (новые ступени и рецепты верстака) | Сохраняется навсегда |
| Охотничий домик | Выжить 3 дня + 5 ткани | Тёплая шуба (-25% потери тепла при экипировке) | Сохраняется навсегда |

## Сборка и запуск

```bash
./gradlew test
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew bundleRelease
```

## Настройка ID рекламы РСЯ (без правки кода)

Скопируйте `local.properties.example` → `local.properties` в корне репозитория и заполните:

```properties
yandex.banner.id=ВАШ_ID_БАННЕРА_ИЗ_РСЯ
yandex.rewarded.id=ВАШ_ID_REWARDED_ИЗ_РСЯ
yandex.debug.demo=false
```

Поля автоматически пробрасываются в `BuildConfig.YANDEX_BANNER_AD_UNIT_ID` и `BuildConfig.YANDEX_REWARDED_AD_UNIT_ID` (резервная XML-точка: `app-winterforest/src/main/res/values/ads.xml`). Пустое значение отключает рекламу без влияния на геймплей. Подробности: [`../docs/ads-setup.md`](../docs/ads-setup.md).
