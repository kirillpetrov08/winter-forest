package com.ayaks.winterforest.presentation.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ayaks.winterforest.data.ads.AdConfig
import com.ayaks.winterforest.data.ads.RewardedAdController
import com.ayaks.winterforest.data.ads.RewardedAdUiState
import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.BaseUpgradeType
import com.ayaks.winterforest.domain.model.GameRunState
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.RunPhase
import com.ayaks.winterforest.domain.model.StarterLoadout
import com.ayaks.winterforest.domain.model.WorkbenchRecipe
import com.ayaks.winterforest.domain.repository.GameRepository
import com.ayaks.winterforest.domain.repository.LoadSaveResult
import com.ayaks.winterforest.domain.repository.UserSettings
import com.ayaks.winterforest.domain.rules.SurvivalEngine
import com.ayaks.winterforest.presentation.navigation.AppDestination
import com.ayaks.winterforest.presentation.navigation.BannerPlacementPolicy
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class WinterForestUiState(
    val isInitialized: Boolean = false,
    val destination: AppDestination = AppDestination.MAIN_MENU,
    val previousDestination: AppDestination = AppDestination.MAIN_MENU,
    val baseProgress: BaseProgress = BaseProgress(),
    val activeRun: GameRunState? = null,
    val settings: UserSettings = UserSettings(),
    val adConfig: AdConfig = AdConfig("", "", isDebugBuild = false),
    val rewardedState: RewardedAdUiState = RewardedAdUiState(),
    val corruptedSaveMessage: String? = null,
    val showResetConfirmationDialog: Boolean = false,
    val joystickForward: Float = 0f,
    val joystickStrafe: Float = 0f
) {
    val hasActiveSave: Boolean
        get() = activeRun != null && activeRun.phase == RunPhase.PLAYING

    val isBannerVisibleOnCurrentScreen: Boolean
        get() = BannerPlacementPolicy.shouldShowBanner(destination, adConfig)

    val isReviveAdButtonVisible: Boolean
        get() = adConfig.isRewardedEnabled && (activeRun?.canReviveWithAd == true)

    val isSkipTimerAdButtonVisible: Boolean
        get() = adConfig.isRewardedEnabled &&
            (activeRun?.activeSkippableTimer() != null) &&
            ((activeRun.remainingRewardedSkipsToday) > 0)
}

class WinterForestViewModel(
    private val repository: GameRepository,
    private val adConfig: AdConfig,
    private val rewardedController: RewardedAdController,
    private val onConsentUpdatedCallback: (Boolean) -> Unit = {},
    externalScope: CoroutineScope? = null
) : ViewModel() {

    private val vmScope: CoroutineScope =
        externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ownsScope = externalScope == null

    private val _uiState = MutableStateFlow(WinterForestUiState(adConfig = adConfig))
    val uiState: StateFlow<WinterForestUiState> = _uiState.asStateFlow()

    private var gameLoopJob: Job? = null
    private var autoSaveAccumulatorSec: Float = 0f

    init {
        vmScope.launch {
            repository.settingsFlow.collect { newSettings ->
                _uiState.update { it.copy(settings = newSettings) }
            }
        }
        vmScope.launch {
            rewardedController.state.collect { adState ->
                _uiState.update { it.copy(rewardedState = adState) }
            }
        }
        vmScope.launch {
            loadInitialData()
        }
    }

    suspend fun loadInitialData() {
        when (val result = repository.loadGameSnapshot()) {
            is LoadSaveResult.Loaded -> {
                _uiState.update {
                    it.copy(
                        isInitialized = true,
                        baseProgress = result.baseProgress,
                        activeRun = result.activeRun,
                        corruptedSaveMessage = null
                    )
                }
            }
            is LoadSaveResult.Corrupted -> {
                _uiState.update {
                    it.copy(
                        isInitialized = true,
                        baseProgress = BaseProgress(),
                        activeRun = null,
                        corruptedSaveMessage = result.reason
                    )
                }
            }
        }
        if (adConfig.isRewardedEnabled) {
            rewardedController.ensurePreloaded()
        }
    }

    fun startNewGame(seed: Long = System.currentTimeMillis()) {
        val currentBase = _uiState.value.baseProgress
        val freshRun = SurvivalEngine.createNewRun(currentBase, seed = seed)
        _uiState.update {
            it.copy(
                activeRun = freshRun,
                destination = AppDestination.GAME,
                previousDestination = AppDestination.MAIN_MENU,
                corruptedSaveMessage = null,
                joystickForward = 0f,
                joystickStrafe = 0f
            )
        }
        persistCheckpointAsync()
        startGameLoopIfNeeded()
    }

    fun continueSavedGame() {
        val saved = _uiState.value.activeRun ?: return
        if (saved.phase == RunPhase.DEAD) {
            startNewGame()
            return
        }
        _uiState.update {
            it.copy(
                destination = AppDestination.GAME,
                previousDestination = AppDestination.MAIN_MENU,
                joystickForward = 0f,
                joystickStrafe = 0f
            )
        }
        startGameLoopIfNeeded()
    }

    fun dismissCorruptedSaveAndStartFresh() {
        vmScope.launch {
            repository.resetAllProgress()
            val cleanBase = BaseProgress()
            _uiState.update {
                it.copy(
                    baseProgress = cleanBase,
                    activeRun = null,
                    corruptedSaveMessage = null,
                    destination = AppDestination.MAIN_MENU
                )
            }
        }
    }

    fun navigateTo(target: AppDestination) {
        if (target != AppDestination.GAME) {
            stopGameLoop()
            persistCheckpointAsync()
        }
        _uiState.update { state ->
            state.copy(
                previousDestination = state.destination,
                destination = target,
                joystickForward = 0f,
                joystickStrafe = 0f
            )
        }
        if (target == AppDestination.GAME && _uiState.value.activeRun?.phase == RunPhase.PLAYING) {
            startGameLoopIfNeeded()
        }
    }

    fun navigateBack() {
        val cur = _uiState.value
        val target = when (cur.destination) {
            AppDestination.MAIN_MENU -> AppDestination.MAIN_MENU
            AppDestination.GAME -> AppDestination.MAIN_MENU
            AppDestination.RESULTS -> AppDestination.MAIN_MENU
            AppDestination.BASE_INVENTORY ->
                if (cur.previousDestination == AppDestination.GAME && cur.hasActiveSave) {
                    AppDestination.GAME
                } else {
                    AppDestination.MAIN_MENU
                }
            AppDestination.SETTINGS, AppDestination.ABOUT -> AppDestination.MAIN_MENU
        }
        navigateTo(target)
    }

    /**
     * Вызывается при сворачивании приложения (onPause / onStop):
     * гарантированно сохраняет текущее состояние игрока без потери прогресса.
     */
    fun onAppBackgrounded() {
        stopGameLoop()
        _uiState.update { it.copy(joystickForward = 0f, joystickStrafe = 0f) }
        persistCheckpointAsync()
    }

    fun onAppForegrounded() {
        if (_uiState.value.destination == AppDestination.GAME &&
            _uiState.value.activeRun?.phase == RunPhase.PLAYING
        ) {
            startGameLoopIfNeeded()
        }
    }

    fun updateJoystick(forward: Float, strafe: Float) {
        _uiState.update {
            it.copy(
                joystickForward = forward.coerceIn(-1f, 1f),
                joystickStrafe = strafe.coerceIn(-1f, 1f)
            )
        }
    }

    fun onSwipeLook(deltaYawDeg: Float, deltaPitchDeg: Float) {
        val state = _uiState.value
        val run = state.activeRun ?: return
        val rotated = SurvivalEngine.rotateCamera(
            run = run,
            deltaYawDeg = deltaYawDeg,
            deltaPitchDeg = deltaPitchDeg,
            sensitivity = state.settings.lookSensitivity
        )
        _uiState.update { it.copy(activeRun = rotated) }
    }

    /**
     * Один шаг игрового цикла (доступен и напрямую для детерминированных тестов).
     */
    fun stepSimulation(deltaSeconds: Float) {
        val current = _uiState.value
        val run = current.activeRun ?: return
        if (run.phase == RunPhase.DEAD) return

        val isMoving = abs(current.joystickForward) > 0.05f || abs(current.joystickStrafe) > 0.05f
        val movedRun = if (isMoving) {
            SurvivalEngine.movePlayer(
                run = run,
                forwardInput = current.joystickForward,
                strafeInput = current.joystickStrafe,
                deltaSeconds = deltaSeconds
            )
        } else {
            run
        }

        val outcome = SurvivalEngine.tick(
            run = movedRun,
            base = current.baseProgress,
            deltaSeconds = deltaSeconds,
            isMoving = isMoving
        )

        val diedJustNow = outcome.run.phase == RunPhase.DEAD
        _uiState.update {
            it.copy(
                activeRun = outcome.run,
                baseProgress = outcome.base,
                destination = if (diedJustNow) AppDestination.RESULTS else it.destination,
                joystickForward = if (diedJustNow) 0f else it.joystickForward,
                joystickStrafe = if (diedJustNow) 0f else it.joystickStrafe
            )
        }

        if (diedJustNow) {
            stopGameLoop()
            persistCheckpointAsync()
            if (adConfig.isRewardedEnabled) {
                rewardedController.ensurePreloaded()
            }
        } else {
            autoSaveAccumulatorSec += deltaSeconds
            if (autoSaveAccumulatorSec >= AUTO_SAVE_INTERVAL_SEC) {
                autoSaveAccumulatorSec = 0f
                persistCheckpointAsync()
            }
        }
    }

    fun onActionGather() = applyEngineAction { run, base ->
        SurvivalEngine.gatherNearest(run, base, withToolStrikeBonus = false)
    }

    fun onActionStrike() = applyEngineAction { run, base ->
        SurvivalEngine.gatherNearest(run, base, withToolStrikeBonus = true)
    }

    fun onActionQuickUse() = applyEngineAction { run, base ->
        SurvivalEngine.quickUseBestSurvivalItem(run, base)
    }

    fun onUseSpecificResource(type: ResourceType) = applyEngineAction { run, base ->
        SurvivalEngine.useResource(run, base, type)
    }

    fun onIgniteResinTorch() = applyEngineAction { run, base ->
        SurvivalEngine.igniteResinTorch(run, base)
    }

    fun onReturnToBase() = applyEngineAction { run, base ->
        SurvivalEngine.returnToBase(run, base)
    }

    fun onTravelToLocation(destination: LocationId) {
        val state = _uiState.value
        val existingRun = state.activeRun ?: SurvivalEngine.createNewRun(state.baseProgress)
        val outcome = SurvivalEngine.travelToLocation(existingRun, state.baseProgress, destination)
        _uiState.update {
            it.copy(
                activeRun = outcome.run,
                baseProgress = outcome.base,
                destination = if (outcome.actionSucceeded) AppDestination.GAME else it.destination
            )
        }
        persistCheckpointAsync()
        if (outcome.actionSucceeded) {
            startGameLoopIfNeeded()
        }
    }

    fun onDepositAllToBase() = applyEngineAction { run, base ->
        SurvivalEngine.depositAllToBaseStorage(run, base)
    }

    fun onUpgradeBase(type: BaseUpgradeType) {
        val state = _uiState.value
        val run = state.activeRun ?: SurvivalEngine.createNewRun(state.baseProgress)
        val outcome = SurvivalEngine.upgradeBaseStructure(run, state.baseProgress, type)
        _uiState.update {
            it.copy(
                activeRun = if (state.activeRun != null) outcome.run else null,
                baseProgress = outcome.base
            )
        }
        persistCheckpointAsync()
    }

    fun onInstallThermos() = applyEngineAction { run, base ->
        SurvivalEngine.installThermosOnBase(run, base)
    }

    fun onCraftRecipe(recipe: WorkbenchRecipe) = applyEngineAction { run, base ->
        SurvivalEngine.craftAtWorkbench(run, base, recipe)
    }

    fun onStartCampfireCooking() = applyEngineAction { run, base ->
        SurvivalEngine.startCookingOnCampfire(run, base)
    }

    fun onRestInShelter() = applyEngineAction { run, base ->
        SurvivalEngine.restInBaseShelter(run, base)
    }

    fun onSelectLoadout(loadout: StarterLoadout) {
        val updated = SurvivalEngine.selectStarterLoadout(_uiState.value.baseProgress, loadout)
        _uiState.update { it.copy(baseProgress = updated) }
        persistCheckpointAsync()
    }

    fun onToggleWarmCoat(enabled: Boolean) {
        val updated = SurvivalEngine.toggleWarmCoat(_uiState.value.baseProgress, enabled)
        _uiState.update { it.copy(baseProgress = updated) }
        persistCheckpointAsync()
    }

    /**
     * Кнопка «Возродиться (реклама)» на экране результатов.
     * Игровая награда выдаётся ТОЛЬКО при вызове колбэка onEarnedReward (т.е. onRewarded в SDK).
     */
    fun onRequestRewardedRevive(activity: Activity?) {
        val snapshotBefore = _uiState.value.activeRun ?: return
        if (!snapshotBefore.canReviveWithAd) return

        rewardedController.showOrTriggerLoad(
            activity = activity,
            onEarnedReward = {
                val cur = _uiState.value
                val runNow = cur.activeRun ?: return@showOrTriggerLoad
                val outcome = SurvivalEngine.applyRewardedRevive(runNow, cur.baseProgress)
                if (outcome.actionSucceeded) {
                    _uiState.update {
                        it.copy(
                            activeRun = outcome.run,
                            baseProgress = outcome.base,
                            destination = AppDestination.GAME
                        )
                    }
                    persistCheckpointAsync()
                    startGameLoopIfNeeded()
                }
            },
            onUnavailableMessage = { msg ->
                _uiState.update { st ->
                    st.copy(activeRun = st.activeRun?.copy(lastStatusMessage = msg))
                }
            }
        )
    }

    /**
     * Кнопка «Пропустить ожидание (реклама)» для активного таймера/кулдауна (не более 3 в игровой день).
     * Награда выдаётся ТОЛЬКО в колбэке onRewarded!
     */
    fun onRequestRewardedSkipTimer(activity: Activity?) {
        val snapshotBefore = _uiState.value.activeRun ?: return
        if (snapshotBefore.remainingRewardedSkipsToday <= 0) return
        if (snapshotBefore.activeSkippableTimer() == null) return

        rewardedController.showOrTriggerLoad(
            activity = activity,
            onEarnedReward = {
                val cur = _uiState.value
                val runNow = cur.activeRun ?: return@showOrTriggerLoad
                val outcome = SurvivalEngine.applyRewardedSkipTimer(runNow, cur.baseProgress)
                _uiState.update {
                    it.copy(
                        activeRun = outcome.run,
                        baseProgress = outcome.base
                    )
                }
                persistCheckpointAsync()
            },
            onUnavailableMessage = { msg ->
                _uiState.update { st ->
                    st.copy(activeRun = st.activeRun?.copy(lastStatusMessage = msg))
                }
            }
        )
    }

    fun onSoundVolumeChanged(volume: Float) {
        vmScope.launch { repository.updateSoundVolume(volume) }
    }

    fun onLookSensitivityChanged(sensitivity: Float) {
        vmScope.launch { repository.updateLookSensitivity(sensitivity) }
    }

    fun onGdprConsentSelected(granted: Boolean) {
        onConsentUpdatedCallback(granted)
        vmScope.launch { repository.updateGdprConsent(granted) }
    }

    fun requestResetProgressDialog() {
        _uiState.update { it.copy(showResetConfirmationDialog = true) }
    }

    fun cancelResetProgressDialog() {
        _uiState.update { it.copy(showResetConfirmationDialog = false) }
    }

    fun confirmResetAllProgress() {
        stopGameLoop()
        vmScope.launch {
            repository.resetAllProgress()
            _uiState.update {
                it.copy(
                    baseProgress = BaseProgress(),
                    activeRun = null,
                    showResetConfirmationDialog = false,
                    destination = AppDestination.MAIN_MENU
                )
            }
        }
    }

    private fun applyEngineAction(
        block: (GameRunState, BaseProgress) -> com.ayaks.winterforest.domain.rules.EngineStepOutcome
    ) {
        val state = _uiState.value
        val run = state.activeRun ?: return
        val outcome = block(run, state.baseProgress)
        _uiState.update {
            it.copy(
                activeRun = outcome.run,
                baseProgress = outcome.base
            )
        }
        persistCheckpointAsync()
    }

    private fun startGameLoopIfNeeded() {
        if (gameLoopJob?.isActive == true) return
        gameLoopJob = vmScope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                stepSimulation(TICK_INTERVAL_MS / 1000f)
            }
        }
    }

    private fun stopGameLoop() {
        gameLoopJob?.cancel()
        gameLoopJob = null
    }

    private fun persistCheckpointAsync() {
        val snapshot = _uiState.value
        vmScope.launch {
            repository.saveCheckpoint(snapshot.baseProgress, snapshot.activeRun)
        }
    }

    override fun onCleared() {
        stopGameLoop()
        rewardedController.release()
        if (ownsScope) {
            vmScope.cancel()
        }
        super.onCleared()
    }

    class Factory(
        private val repository: GameRepository,
        private val adConfig: AdConfig,
        private val rewardedController: RewardedAdController,
        private val onConsentUpdatedCallback: (Boolean) -> Unit
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return WinterForestViewModel(
                repository = repository,
                adConfig = adConfig,
                rewardedController = rewardedController,
                onConsentUpdatedCallback = onConsentUpdatedCallback
            ) as T
        }
    }

    companion object {
        private const val TICK_INTERVAL_MS = 100L // 10 Гц логики (рендерер работает отдельно на 60 Гц)
        private const val AUTO_SAVE_INTERVAL_SEC = 10f
    }
}
