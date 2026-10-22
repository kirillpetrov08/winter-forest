package com.ayaks.winterforest.presentation.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ayaks.winterforest.data.ads.AdConfig
import com.ayaks.winterforest.presentation.navigation.AppDestination
import com.ayaks.winterforest.presentation.navigation.BannerPlacementPolicy
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.compose.Banner
import com.yandex.mobile.ads.compose.BannerEvents
import com.yandex.mobile.ads.compose.BannerSize
import com.yandex.mobile.ads.compose.rememberBannerAdState
import kotlinx.coroutines.delay

/**
 * Официальный адаптивный sticky-баннер Yandex Mobile Ads Compose SDK 8.6.0.
 * Отображается СТРОГО на экране главного меню и никогда не рендерится на других экранах.
 * При пустом ID блока не создаёт запрос и не занимает место.
 */
@Composable
fun MainMenuAdaptiveBanner(
    currentDestination: AppDestination,
    adConfig: AdConfig,
    modifier: Modifier = Modifier
) {
    if (!BannerPlacementPolicy.shouldShowBanner(currentDestination, adConfig)) {
        return
    }

    var retryAttempt by remember(adConfig.bannerAdUnitId) { mutableIntStateOf(0) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .testTag("main_menu_adaptive_banner")
    ) {
        val safeWidth = maxWidth.coerceAtLeast(320.dp)
        val bannerState = rememberBannerAdState(
            adSize = BannerSize.Sticky(width = safeWidth),
            events = BannerEvents(
                onAdLoaded = { _ -> retryAttempt = 0 },
                onAdFailedToLoad = { _ ->
                    // Не загружаем заново синхронно внутри колбэка; не более 2 отложенных попыток
                    if (retryAttempt < MAX_BANNER_RETRIES) {
                        retryAttempt += 1
                    }
                },
                onAdClicked = {},
                onImpression = {}
            )
        )

        LaunchedEffect(adConfig.bannerAdUnitId, retryAttempt) {
            if (retryAttempt > 0) {
                delay(3_000L * retryAttempt)
            }
            bannerState.loadAd(AdRequest.Builder(adConfig.bannerAdUnitId).build())
        }

        Banner(
            state = bannerState,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private const val MAX_BANNER_RETRIES = 2
