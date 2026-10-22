# Настройка рекламы РСЯ (Yandex Mobile Ads SDK) в Winter Forest

В проекте интегрирован официальный **Yandex Mobile Ads SDK (`com.yandex.android:mobileads:8.6.0` + `com.yandex.android:mobileads-compose:8.6.0`)**. Сторонние рекламные сети и медиация не подключены.

По умолчанию рекламные идентификаторы **пустые (`""`)** — при пустых значениях SDK не инициализируется, сетевые запросы не выполняются, кнопки вознаграждаемой рекламы и баннер скрыты, а **игра полностью работает офлайн без рекламы**.

---

## 1. Где получить реальные ID блоков в кабинете РСЯ

1. Откройте [Партнёрский интерфейс Рекламной сети Яндекса](https://partner.yandex.ru/).
2. Перейдите в раздел **Реклама в приложениях → Приложения** и добавьте приложение `Winter Forest` (`package name`: `com.ayaks.winterforest`), либо выберите уже созданное.
3. Создайте **два рекламных блока**:
   - **Формат 1 — Адаптивный баннер (Sticky Banner):** скопируйте его `ID блока` вида `R-M-XXXXXXX-1`.
   - **Формат 2 — Вознаграждаемая реклама (Rewarded):** скопируйте его `ID блока` вида `R-M-XXXXXXX-2`.
   *(Один и тот же Rewarded ID используется для обеих добровольных наград: возрождение на экране итогов и пропуск активного таймера).*

---

## 2. Куда именно вставить ID в готовый проект (без изменения Kotlin-кода)

Предусмотрены **три способа** передачи ID (в порядке приоритета). Переписывать логику приложения **не нужно**.

### Способ А (рекомендуемый для локальной сборки): файл `local.properties` в корне репозитория
Файл `local.properties` уже добавлен в `.gitignore` и не попадёт в Git.

Откройте (или создайте по образцу `local.properties.example`) файл `/local.properties` и укажите:

```properties
# вставить ID баннера сюда:
yandex.banner.id=R-M-XXXXXXX-1

# вставить ID rewarded сюда:
yandex.rewarded.id=R-M-XXXXXXX-2

# оставить false для боевой проверки (демо-режим работает только в debug):
yandex.debug.demo=false
```

При сборке `app-winterforest/build.gradle.kts` автоматически пробрасывает эти значения в:
- `BuildConfig.YANDEX_BANNER_AD_UNIT_ID`
- `BuildConfig.YANDEX_REWARDED_AD_UNIT_ID`

### Способ Б (рекомендуемый для GitHub Actions CI): секреты репозитория
В настройках GitHub (`Settings → Secrets and variables → Actions`) добавьте секреты:
- `YANDEX_BANNER_AD_UNIT_ID` — вставить ID баннера сюда (`R-M-XXXXXXX-1`)
- `YANDEX_REWARDED_AD_UNIT_ID` — вставить ID rewarded сюда (`R-M-XXXXXXX-2`)

Workflow `.github/workflows/android-ci.yml` автоматически передаёт их в `assembleDebug`, `assembleRelease` и `bundleRelease`.

### Способ В (альтернативный XML-ресурс): `app-winterforest/src/main/res/values/ads.xml`
Если вы не используете `local.properties` и переменные окружения, можно вставить ID напрямую в `app-winterforest/src/main/res/values/ads.xml`:

```xml
<resources>
    <!-- вставить ID баннера сюда -->
    <string name="yandex_banner_ad_unit_id" translatable="false">R-M-XXXXXXX-1</string>
    <!-- вставить ID rewarded сюда -->
    <string name="yandex_rewarded_ad_unit_id" translatable="false">R-M-XXXXXXX-2</string>
</resources>
```

Класс `com.ayaks.winterforest.data.ads.AdConfig` читает сначала `BuildConfig`, а если поле пустое — берёт значение из `res/values/ads.xml`.

---

## 3. Защита от тестовых ID в Release и тестирование

- В debug-сборке при необходимости протестировать верстку без реальных блоков можно временно выставить `yandex.debug.demo=true` в `local.properties` — тогда **только в `debug`** будут использованы официальные демо-идентификаторы Яндекса `demo-banner-yandex` и `demo-rewarded-yandex` (помечены в коде `// debug-only`).
- В `release`-сборке флаг `YANDEX_DEBUG_DEMO_ADS` жёстко равен `false`, а метод `AdConfig.resolve(...)` принудительно очищает любой ID, начинающийся с `demo-`. Таким образом, **тестовый блок физически не может попасть в релизный APK/AAB**.
- **Строго запрещено нажимать на собственные боевые объявления** на реальных устройствах — при проверке используйте только эмулятор с `debug`-сборкой и не кликайте по объявлениям с боевых ID.

---

## 4. Согласие пользователя (GDPR) и отключение геолокации

1. В `app-winterforest/src/main/AndroidManifest.xml`:
   - Установлен `<meta-data android:name="com.yandex.mobile.ads.AUTOMATIC_SDK_INITIALIZATION" android:value="false" />`, чтобы SDK никогда не инициализировался до применения настроек приватности.
   - Разрешения `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` и `ACCESS_BACKGROUND_LOCATION` принудительно вырезаются из итогового манифеста директивой `tools:node="remove"`.
2. В `YandexAdsPrivacyBootstrapper.applyPrivacyAndInitialize(...)` **строго до** `YandexAds.initialize(context)` вызываются:
   - `YandexAds.setUserConsent(hasUserConsent)` — передаёт выбор пользователя из диалога согласия / экрана «Настройки»;
   - `YandexAds.setLocationTracking(false)` — **безусловно отключает передачу геолокации**;
   - `YandexAds.setAgeRestricted(false)`.

---

## 5. Проверка и размещение `app-ads.txt`

В репозитории уже подготовлен файл `docs/app-ads.txt` со согласованной строкой кабинета РСЯ:

```text
yandex.com, 110347355, DIRECT
```

> **Важно:** перед отправкой приложения на модерацию обязательно сверьте эту строку с разделом **«Настройки → app-ads.txt»** в вашем кабинете РСЯ и не подменяйте её посторонними записями.

### Как разместить `app-ads.txt` в корне домена разработчика:
1. Если у вас есть собственный домен `https://example.com`, загрузите `docs/app-ads.txt` так, чтобы он открывался по прямому адресу `https://example.com/app-ads.txt` с HTTP-кодом `200` и типом `text/plain`.
2. Если отдельного хостинга нет, используйте **GitHub Pages** прямо из этого репозитория (или отдельного репозитория `<username>.github.io` — так как магазины проверяют корень домена `https://<username>.github.io/app-ads.txt`!):
   - Создайте публичный репозиторий `<ваш-логин>.github.io` и положите в его корень файлы `docs/index.html` и `docs/app-ads.txt`.
   - Убедитесь в браузере, что `https://<ваш-логин>.github.io/app-ads.txt` отдаёт ровно строку `yandex.com, 110347355, DIRECT`.
   - Укажите `https://<ваш-логин>.github.io` в поле **Веб-сайт разработчика (Developer Website)** в Google Play Console и RuStore Консоли.

---

## 6. Правила показа рекламы, закреплённые в коде

1. **Адаптивный баннер:** рендерится **только** на экране `MainMenuScreen` (`BannerPlacementPolicy.shouldShowBanner`). На экранах 3D-игры, базы/инвентаря, результатов, настроек и «Об игре» баннер полностью отсутствует.
2. **Наградные видео (`RewardedAdController`):**
   - вызываются только по нажатию явных кнопок «Возродиться (реклама)» и «Пропустить ожидание (реклама)»;
   - один экземпляр `RewardedAdLoader` на сессию, все вызовы — строго на главном потоке;
   - при `onAdFailedToLoad` повторная загрузка **не** запускается синхронно: действует экспоненциальная задержка (`2 с → 4 с`) и лимит **не более 3 неудачных попыток подряд**;
   - награда начисляется **только** в колбэке `onRewarded`;
   - возрождение доступно не более **1 раза за забег** (50% здоровья, голода и тепла на Базовой поляне);
   - пропуск ожидания доступен не более **3 раз за игровой день** (6 минут реального времени);
   - специальный лут за рекламу **никогда** не выдаётся.
