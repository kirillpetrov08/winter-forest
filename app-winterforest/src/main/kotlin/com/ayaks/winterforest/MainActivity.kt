package com.ayaks.winterforest

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
        // Автоматический горизонтальный режим с переворотом на 180° по датчику наклона
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enableEdgeToEdge()
        configureImmersiveLandscapeWindow()

        setContent {
            WinterForestTheme {
                WinterForestAppRoot(viewModel = viewModel)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            configureImmersiveLandscapeWindow()
        }
    }

    private fun configureImmersiveLandscapeWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
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
