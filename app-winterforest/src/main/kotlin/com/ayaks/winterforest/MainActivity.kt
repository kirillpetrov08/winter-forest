package com.ayaks.winterforest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ayaks.winterforest.data.ads.YandexAdsPrivacyBootstrapper
import com.ayaks.winterforest.presentation.navigation.AppDestination
import com.ayaks.winterforest.presentation.ui.screens.AboutScreen
import com.ayaks.winterforest.presentation.ui.screens.BaseInventoryScreen
import com.ayaks.winterforest.presentation.ui.screens.GameScreen
import com.ayaks.winterforest.presentation.ui.screens.MainMenuScreen
import com.ayaks.winterforest.presentation.ui.screens.ResultsScreen
import com.ayaks.winterforest.presentation.ui.screens.SettingsScreen
import com.ayaks.winterforest.presentation.ui.theme.WinterForestTheme
import com.ayaks.winterforest.presentation.viewmodel.WinterForestViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: WinterForestViewModel by viewModels {
        val appContainer = (application as WinterForestApplication).container
        WinterForestViewModel.Factory(
            repository = appContainer.repository,
            adConfig = appContainer.adConfig,
            rewardedController = appContainer.rewardedAdController,
            onConsentUpdatedCallback = { granted ->
                YandexAdsPrivacyBootstrapper.updateConsentAtRuntime(granted)
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WinterForestTheme {
                WinterForestAppRoot(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun WinterForestAppRoot(viewModel: WinterForestViewModel) {
    val state by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                Lifecycle.Event.ON_RESUME -> viewModel.onAppForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = state.destination != AppDestination.MAIN_MENU) {
        viewModel.navigateBack()
    }

    when (state.destination) {
        AppDestination.MAIN_MENU -> MainMenuScreen(state = state, viewModel = viewModel)
        AppDestination.GAME -> GameScreen(state = state, viewModel = viewModel)
        AppDestination.BASE_INVENTORY -> BaseInventoryScreen(state = state, viewModel = viewModel)
        AppDestination.RESULTS -> ResultsScreen(state = state, viewModel = viewModel)
        AppDestination.SETTINGS -> SettingsScreen(state = state, viewModel = viewModel)
        AppDestination.ABOUT -> AboutScreen(viewModel = viewModel)
    }
}
