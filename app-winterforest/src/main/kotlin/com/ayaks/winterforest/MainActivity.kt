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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ayaks.winterforest.data.ads.YandexAdsPrivacyBootstrapper
import com.ayaks.winterforest.domain.model.GameRunState
import com.ayaks.winterforest.presentation.gl.ForestGlSurfaceView
import com.ayaks.winterforest.presentation.gl.RenderSceneSnapshot
import com.ayaks.winterforest.presentation.navigation.AppDestination
import com.ayaks.winterforest.presentation.ui.screens.AboutScreen
import com.ayaks.winterforest.presentation.ui.screens.BaseInventoryScreen
import com.ayaks.winterforest.presentation.ui.screens.GameScreen
import com.ayaks.winterforest.presentation.ui.screens.MainMenuScreen
import com.ayaks.winterforest.presentation.ui.screens.ResultsScreen
import com.ayaks.winterforest.presentation.ui.screens.SettingsScreen
import com.ayaks.winterforest.presentation.ui.theme.WinterForestTheme
import com.ayaks.winterforest.presentation.viewmodel.WinterForestUiState
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
        // Целевые 60 FPS задаёт сам рендерер: eglSwapInterval(1) в
        // ForestGlRenderer + Surface.setFrameRate(60, FIXED) в ForestGlSurfaceView
        // (см. FramePacing — прежняя разблокировка 120 Гц давала микрофризы).

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

    // Корневой Box: единый живой 3D-слой OpenGL под всеми экранами.
    // Рюкзак/настройки/меню открываются ПОВЕРХ работающей сцены — без «мёртвого»
    // чёрного экрана и повторной пересборки VBO/текстур при выходе из вкладок.
    Box(modifier = Modifier.fillMaxSize()) {
        PersistentForestScene(
            state = state,
            viewModel = viewModel,
            onFirstFrameReady = viewModel::onSceneReady
        )

        // Пока OpenGL не отрисовал первый кадр, НЕ КОМПОНУЕМ ни один экран:
        // ни хотбара, ни статов, ни кнопок меню в этот момент не существует,
        // а непрозрачный светлый оверлей закрывает чёрную поверхность GL.
        if (state.sceneReady) {
            when (state.destination) {
                AppDestination.MAIN_MENU -> MainMenuScreen(state = state, viewModel = viewModel)
                AppDestination.GAME -> GameScreen(state = state, viewModel = viewModel)
                AppDestination.BASE_INVENTORY -> BaseInventoryScreen(state = state, viewModel = viewModel)
                AppDestination.RESULTS -> ResultsScreen(state = state, viewModel = viewModel)
                AppDestination.SETTINGS -> SettingsScreen(state = state, viewModel = viewModel)
                AppDestination.ABOUT -> AboutScreen(viewModel = viewModel)
            }
        } else {
            TaigaLoadingOverlay()
        }
    }
}

/**
 * Экран загрузки тайги: единственный момент, когда пользователь видит надпись
 * «Загрузка тайги…». Фон непрозрачный и светлый, поэтому под ним не просвечивают
 * ни 3D-сцена, ни интерфейс. Как только рендерер сообщает о первом кадре
 * ([WinterForestViewModel.onSceneReady]), оверлей снимается навсегда — при
 * возврате из меню в игру он уже не появляется.
 */
@Composable
private fun TaigaLoadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFE9F2F8)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(260.dp)
                .height(60.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "WINTER FOREST",
                style = TextStyle(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF071521)
                )
            )
            Text(
                text = "Загрузка тайги…",
                style = TextStyle(
                    fontSize = 14.sp,
                    color = Color(0xFF5B7A8A)
                )
            )
        }
    }
}

/**
 * Постоянный GLSurfaceView приложения: обновляет снимок сцены каждый кадр,
 * держит GPU-кэши тёплыми и переключает режим «орбита меню / вид от игрока».
 */
@Composable
private fun PersistentForestScene(
    state: WinterForestUiState,
    viewModel: WinterForestViewModel,
    onFirstFrameReady: () -> Unit
) {
    val orbitMode = state.destination == AppDestination.MAIN_MENU ||
        state.destination == AppDestination.SETTINGS ||
        state.destination == AppDestination.ABOUT
    AndroidView(
        factory = { ctx ->
            ForestGlSurfaceView(
                ctx,
                onFpsSample = viewModel::onFpsMeasured,
                onFirstFrameReady = onFirstFrameReady
            ).apply {
                forestRenderer.snapshot = RenderSceneSnapshot(
                    run = state.activeRun ?: GameRunState(),
                    base = state.baseProgress,
                    quality = state.settings.graphicsQuality,
                    menuOrbitMode = orbitMode
                )
            }
        },
        update = { glView ->
            glView.forestRenderer.snapshot = RenderSceneSnapshot(
                run = state.activeRun ?: GameRunState(),
                base = state.baseProgress,
                quality = state.settings.graphicsQuality,
                menuOrbitMode = orbitMode
            )
        },
        modifier = Modifier.fillMaxSize()
    )
}
