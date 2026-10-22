package com.ayaks.winterforest

import android.app.Activity
import com.ayaks.winterforest.data.ads.AdConfig
import com.ayaks.winterforest.data.ads.LoadedRewardedHandle
import com.ayaks.winterforest.data.ads.RewardedAdController
import com.ayaks.winterforest.data.ads.RewardedAdSdkPort
import com.ayaks.winterforest.data.repository.FileBackedGameRepository
import com.ayaks.winterforest.domain.model.RunPhase
import com.ayaks.winterforest.domain.model.SpecialLootId
import com.ayaks.winterforest.presentation.navigation.AppDestination
import com.ayaks.winterforest.presentation.navigation.BannerPlacementPolicy
import com.ayaks.winterforest.presentation.viewmodel.WinterForestViewModel
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdsConfigurationAndPolicyTest {

    @Test
    fun `empty ad unit ids disable banner and rewarded buttons everywhere without crash`() {
        val emptyConfig = AdConfig.resolve(
            buildConfigBannerId = "",
            buildConfigRewardedId = "",
            isDebugBuild = false,
            enableDebugDemoAds = false
        )
        assertFalse(emptyConfig.isBannerEnabled)
        assertFalse(emptyConfig.isRewardedEnabled)

        for (screen in AppDestination.entries) {
            assertFalse(
                "При пустом ID баннер не должен показываться ни на одном экране ($screen)",
                BannerPlacementPolicy.shouldShowBanner(screen, emptyConfig)
            )
        }
    }

    @Test
    fun `banner shows strictly on main menu and never on game results base settings or about`() {
        val prodConfig = AdConfig.resolve(
            buildConfigBannerId = "R-M-987654-1",
            buildConfigRewardedId = "R-M-987654-2",
            isDebugBuild = false
        )
        assertTrue(BannerPlacementPolicy.shouldShowBanner(AppDestination.MAIN_MENU, prodConfig))
        assertFalse(BannerPlacementPolicy.shouldShowBanner(AppDestination.GAME, prodConfig))
        assertFalse(BannerPlacementPolicy.shouldShowBanner(AppDestination.RESULTS, prodConfig))
        assertFalse(BannerPlacementPolicy.shouldShowBanner(AppDestination.BASE_INVENTORY, prodConfig))
        assertFalse(BannerPlacementPolicy.shouldShowBanner(AppDestination.SETTINGS, prodConfig))
        assertFalse(BannerPlacementPolicy.shouldShowBanner(AppDestination.ABOUT, prodConfig))
    }

    @Test
    fun `release build strips demo ad ids automatically while debug allows explicit demo flag`() {
        val accidentalDemoInRelease = AdConfig.resolve(
            buildConfigBannerId = "demo-banner-yandex",
            buildConfigRewardedId = "demo-rewarded-yandex",
            isDebugBuild = false,
            enableDebugDemoAds = true
        )
        assertEquals("", accidentalDemoInRelease.bannerAdUnitId)
        assertEquals("", accidentalDemoInRelease.rewardedAdUnitId)
        assertFalse(accidentalDemoInRelease.isBannerEnabled)
        assertFalse(accidentalDemoInRelease.isRewardedEnabled)

        val explicitDebugDemo = AdConfig.resolve(
            buildConfigBannerId = "",
            buildConfigRewardedId = "",
            isDebugBuild = true,
            enableDebugDemoAds = true
        )
        assertEquals(AdConfig.DEBUG_DEMO_BANNER_ID, explicitDebugDemo.bannerAdUnitId)
        assertEquals(AdConfig.DEBUG_DEMO_REWARDED_ID, explicitDebugDemo.rewardedAdUnitId)
    }

    @Test
    fun `closing rewarded ad without onRewarded grants zero reward and ads never grant special loot`() = runTest {
        val tempDir = Files.createTempDirectory("wf_ads_test").toFile()
        val testScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            var shouldInvokeOnRewarded = false
            val fakeSdkPort = object : RewardedAdSdkPort {
                override fun loadAd(
                    adUnitId: String,
                    onLoaded: (LoadedRewardedHandle) -> Unit,
                    onFailed: (String) -> Unit
                ) {
                    onLoaded(object : LoadedRewardedHandle {
                        override fun show(
                            activity: Activity?,
                            onShown: () -> Unit,
                            onFailedToShow: (String) -> Unit,
                            onRewarded: () -> Unit,
                            onDismissed: () -> Unit
                        ) {
                            onShown()
                            if (shouldInvokeOnRewarded) {
                                onRewarded()
                            }
                            onDismissed()
                        }

                        override fun release() = Unit
                    })
                }

                override fun cancelAll() = Unit
            }

            val config = AdConfig("R-M-111111-1", "R-M-111111-2", isDebugBuild = false)
            val controller = RewardedAdController(
                adConfig = config,
                sdkPort = fakeSdkPort,
                isOnMainThread = { true },
                dispatchToMain = { it() }
            )
            val vm = WinterForestViewModel(
                repository = FileBackedGameRepository(tempDir),
                adConfig = config,
                rewardedController = controller,
                externalScope = testScope
            )
            vm.loadInitialData()
            vm.startNewGame(seed = 99L)

            // Доводим персонажа до смерти
            repeat(60) {
                vm.stepSimulation(60f)
                if (vm.uiState.value.activeRun?.phase == RunPhase.DEAD) return@repeat
            }
            assertEquals(RunPhase.DEAD, vm.uiState.value.activeRun?.phase)

            // Сценарий 1: видео открыто и закрыто БЕЗ onRewarded -> возрождения НЕ происходит!
            shouldInvokeOnRewarded = false
            vm.onRequestRewardedRevive(activity = null)
            assertEquals(RunPhase.DEAD, vm.uiState.value.activeRun?.phase)
            assertEquals(AppDestination.RESULTS, vm.uiState.value.destination)

            // Сценарий 2: срабатывает onRewarded -> персонаж возрождается с 50% сил на Базовой поляне, но спецлут пуст
            shouldInvokeOnRewarded = true
            vm.onRequestRewardedRevive(activity = null)
            val revived = vm.uiState.value.activeRun!!
            assertEquals(RunPhase.PLAYING, revived.phase)
            assertEquals(revived.maxHealth * 0.5f, revived.health, 0.01f)
            assertEquals(50f, revived.hunger, 0.01f)
            assertEquals(50f, revived.warmth, 0.01f)
            for (special in SpecialLootId.entries) {
                assertFalse(
                    "Ни один специальный предмет не должен выдаваться за просмотр рекламы",
                    revived.inventory.hasSpecial(special)
                )
            }
        } finally {
            testScope.cancel()
            tempDir.deleteRecursively()
        }
    }
}
