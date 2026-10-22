package com.ayaks.winterforest

import com.ayaks.winterforest.data.ads.AdConfig
import com.ayaks.winterforest.data.ads.LoadedRewardedHandle
import com.ayaks.winterforest.data.ads.RewardedAdController
import com.ayaks.winterforest.data.ads.RewardedAdSdkPort
import com.ayaks.winterforest.data.repository.FileBackedGameRepository
import com.ayaks.winterforest.presentation.navigation.AppDestination
import com.ayaks.winterforest.presentation.viewmodel.WinterForestViewModel
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationFlowTest {

    private val noOpAdPort = object : RewardedAdSdkPort {
        override fun loadAd(
            adUnitId: String,
            onLoaded: (LoadedRewardedHandle) -> Unit,
            onFailed: (String) -> Unit
        ) = Unit
        override fun cancelAll() = Unit
    }

    @Test
    fun `full screen transition cycle menu to game to base to results to menu works cleanly`() = runTest {
        val tempDir = Files.createTempDirectory("wf_nav_test").toFile()
        val testScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val repo = FileBackedGameRepository(tempDir)
            val adConfig = AdConfig("", "", isDebugBuild = false)
            val rewardedController = RewardedAdController(
                adConfig = adConfig,
                sdkPort = noOpAdPort,
                isOnMainThread = { true },
                dispatchToMain = { it() }
            )
            val vm = WinterForestViewModel(
                repository = repo,
                adConfig = adConfig,
                rewardedController = rewardedController,
                externalScope = testScope
            )
            vm.loadInitialData()

            // 1. Старт на главном меню
            assertEquals(AppDestination.MAIN_MENU, vm.uiState.value.destination)

            // 2. Переход в Настройки и Об игре и обратно
            vm.navigateTo(AppDestination.SETTINGS)
            assertEquals(AppDestination.SETTINGS, vm.uiState.value.destination)
            vm.navigateBack()
            assertEquals(AppDestination.MAIN_MENU, vm.uiState.value.destination)

            vm.navigateTo(AppDestination.ABOUT)
            assertEquals(AppDestination.ABOUT, vm.uiState.value.destination)
            vm.navigateBack()
            assertEquals(AppDestination.MAIN_MENU, vm.uiState.value.destination)

            // 3. Запуск новой игры -> GAME -> BASE_INVENTORY -> обратно в GAME
            vm.startNewGame(seed = 42L)
            assertEquals(AppDestination.GAME, vm.uiState.value.destination)
            assertTrue(vm.uiState.value.hasActiveSave)

            vm.navigateTo(AppDestination.BASE_INVENTORY)
            assertEquals(AppDestination.BASE_INVENTORY, vm.uiState.value.destination)
            vm.navigateBack()
            assertEquals(AppDestination.GAME, vm.uiState.value.destination)

            // 4. Истощение до смерти -> автоматический переход на экран RESULTS -> возврат в MAIN_MENU
            vm.updateJoystick(forward = 1f, strafe = 0f)
            repeat(60) {
                vm.stepSimulation(60f)
                if (vm.uiState.value.destination == AppDestination.RESULTS) return@repeat
            }
            assertEquals(AppDestination.RESULTS, vm.uiState.value.destination)

            vm.navigateTo(AppDestination.MAIN_MENU)
            assertEquals(AppDestination.MAIN_MENU, vm.uiState.value.destination)

            // 5. Подтверждение сброса прогресса в Настройках
            vm.requestResetProgressDialog()
            assertTrue(vm.uiState.value.showResetConfirmationDialog)
            vm.cancelResetProgressDialog()
            assertFalse(vm.uiState.value.showResetConfirmationDialog)

            vm.requestResetProgressDialog()
            vm.confirmResetAllProgress()
            kotlinx.coroutines.yield()
            assertNull(vm.uiState.value.activeRun)
            assertEquals(AppDestination.MAIN_MENU, vm.uiState.value.destination)
        } finally {
            testScope.cancel()
            tempDir.deleteRecursively()
        }
    }
}
