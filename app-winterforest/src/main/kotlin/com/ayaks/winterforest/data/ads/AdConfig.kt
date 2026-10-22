package com.ayaks.winterforest.data.ads

import android.content.Context
import com.ayaks.winterforest.BuildConfig
import com.ayaks.winterforest.R

/**
 * Конфигурация рекламных блоков РСЯ (Yandex Mobile Ads SDK).
 *
 * Правила безопасности:
 * 1. Пустая строка = соответствующий формат рекламы полностью отключён, игра работает без рекламы.
 * 2. В release-сборке любые demo/тестовые идентификаторы (demo-*) принудительно отфильтровываются в "".
 * 3. В debug-сборке официальные демо-ID Яндекса подставляются ТОЛЬКО если явно включён флаг debugDemoAds.
 */
data class AdConfig(
    val bannerAdUnitId: String,
    val rewardedAdUnitId: String,
    val isDebugBuild: Boolean
) {
    val isBannerEnabled: Boolean
        get() = bannerAdUnitId.isNotBlank()

    val isRewardedEnabled: Boolean
        get() = rewardedAdUnitId.isNotBlank()

    companion object {
        // debug-only: официальные демо-идентификаторы из документации Yandex Mobile Ads SDK 8.x
        const val DEBUG_DEMO_BANNER_ID = "demo-banner-yandex" // debug-only
        const val DEBUG_DEMO_REWARDED_ID = "demo-rewarded-yandex" // debug-only

        fun fromContext(context: Context): AdConfig {
            val xmlBanner = runCatching { context.getString(R.string.yandex_banner_ad_unit_id) }
                .getOrDefault("")
            val xmlRewarded = runCatching { context.getString(R.string.yandex_rewarded_ad_unit_id) }
                .getOrDefault("")
            return resolve(
                buildConfigBannerId = BuildConfig.YANDEX_BANNER_AD_UNIT_ID,
                buildConfigRewardedId = BuildConfig.YANDEX_REWARDED_AD_UNIT_ID,
                fallbackXmlBannerId = xmlBanner,
                fallbackXmlRewardedId = xmlRewarded,
                isDebugBuild = BuildConfig.DEBUG,
                enableDebugDemoAds = BuildConfig.YANDEX_DEBUG_DEMO_ADS
            )
        }

        fun resolve(
            buildConfigBannerId: String,
            buildConfigRewardedId: String,
            fallbackXmlBannerId: String = "",
            fallbackXmlRewardedId: String = "",
            isDebugBuild: Boolean,
            enableDebugDemoAds: Boolean = false
        ): AdConfig {
            val rawBanner = buildConfigBannerId.trim().ifEmpty { fallbackXmlBannerId.trim() }
            val rawRewarded = buildConfigRewardedId.trim().ifEmpty { fallbackXmlRewardedId.trim() }

            val finalBanner = sanitizeForBuildType(
                rawId = rawBanner,
                isDebugBuild = isDebugBuild,
                debugDemoFallback = if (isDebugBuild && enableDebugDemoAds) DEBUG_DEMO_BANNER_ID else "" // debug-only
            )
            val finalRewarded = sanitizeForBuildType(
                rawId = rawRewarded,
                isDebugBuild = isDebugBuild,
                debugDemoFallback = if (isDebugBuild && enableDebugDemoAds) DEBUG_DEMO_REWARDED_ID else "" // debug-only
            )

            return AdConfig(
                bannerAdUnitId = finalBanner,
                rewardedAdUnitId = finalRewarded,
                isDebugBuild = isDebugBuild
            )
        }

        private fun sanitizeForBuildType(
            rawId: String,
            isDebugBuild: Boolean,
            debugDemoFallback: String
        ): String {
            val candidate = rawId.trim().ifEmpty { debugDemoFallback }
            if (candidate.isEmpty()) return ""
            // Жёсткая защита release-сборки: demo/test ID никогда не попадают в продакшен
            if (!isDebugBuild && candidate.startsWith("demo-", ignoreCase = true)) {
                return ""
            }
            return candidate
        }
    }
}
