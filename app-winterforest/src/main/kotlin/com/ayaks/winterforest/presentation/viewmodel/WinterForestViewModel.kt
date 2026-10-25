package com.ayaks.winterforest.presentation.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ayaks.winterforest.data.ads.AdConfig
import com.ayaks.winterforest.data.ads.RewardedAdController
import com.ayaks.winterforest.data.ads.RewardedAdUiState
import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.BaseUpgradeType
import com.ayaks.winterforest.domain.model.EquippedTool
import com.ayaks.winterforest.domain.model.GameRunState
import com.ayaks.winterforest.domain.model.GraphicsQuality
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
import kotlin.math.hypot
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

/** Пеший переход между соседними локациями (идём, а не телепортируемся). */
data class TravelJourney(
    val destination: com.ayaks.winterforest.domain.model.LocationId,
    val totalSec: Float,
    val remainingSec: Float
)

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
    val joystickStrafe: Float = 0f,
    val measuredFps: Int = 60,
    val cooldowns: Map<String, Float> = emptyMap(),
    val travelJourney: TravelJourney? = null,
    /** Сцена OpenGL отрисовала первый кадр — экран загрузки можно убирать. */
    val sceneReady: Boolean = false
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

    /** §7.4: «Новая экспедиция» всегда ведёт через экран выбора класса. */
    fun requestNewExpedition() {
        _uiState.update {
            it.copy(
                destination = AppDestination.LOADOUT_SELECT,
                previousDestination = it.destination
            )
        }
    }

    /** Применение выбранного класса к базе и старт забега с ним. */
    fun confirmLoadoutAndStart(loadout: StarterLoadout, seed: Long = System.currentTimeMillis()) {
        val baseWithLoadout = SurvivalEngine.selectStarterLoadout(_uiState.value.baseProgress, loadout)
        _uiState.update { it.copy(baseProgress = baseWithLoadout) }
        persistCheckpointAsync()
        startNewGame(seed)
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
        val clampedFwd = forward.coerceIn(-1f, 1f)
        val clampedStr = strafe.coerceIn(-1f, 1f)
        val mag = hypot(clampedFwd, clampedStr)
        _uiState.update { st ->
            val updatedRun = if (st.settings.autoSprintEnabled && st.activeRun != null) {
                if (mag >= 0.84f && st.activeRun.stamina > 8f) {
                    st.activeRun.copy(isSprinting = true)
                } else if (mag < 0.35f) {
                    st.activeRun.copy(isSprinting = false)
                } else {
                    st.activeRun
                }
            } else {
                st.activeRun
            }
            st.copy(
                joystickForward = clampedFwd,
                joystickStrafe = clampedStr,
                activeRun = updatedRun
            )
        }
    }

    fun onToggleSprint() {
        _uiState.update { st ->
            val run = st.activeRun ?: return@update st
            st.copy(activeRun = SurvivalEngine.setSprinting(run, !run.isSprinting))
        }
    }

    fun onEquipTool(tool: EquippedTool) {
        _uiState.update { st ->
            val run = st.activeRun ?: return@update st
            st.copy(activeRun = SurvivalEngine.equipTool(run, tool))
        }
    }

    fun onPlaceFieldCampfire() = applyEngineAction(cooldownId = "place_campfire", cooldownSec = 3.0f) { run, base ->
        SurvivalEngine.placeFieldCampfire(run, base)
    }

    /** Можно ли положить тип ресурса в слот хотбара (еда, инструмент, спецпредмет). */
    fun canAssignToHotbar(type: ResourceType): Boolean {
        return type.isEdible || type.isFuel || type.category == com.ayaks.winterforest.domain.model.ResourceCategory.MATERIAL
    }

    /** Положить выбранный предмет из инвентаря в слот хотбара. */
    fun onAssignHotbarFromInventory(slotIndex: Int, type: ResourceType) {
        _uiState.update { st ->
            val run = st.activeRun ?: return@update st
            val entry = com.ayaks.winterforest.domain.model.HotbarEntry(
                titleRu = type.titleRu,
                iconPath = "",
                resourceType = type
            )
            st.copy(activeRun = run.withHotbarSlot(slotIndex, entry))
        }
        persistCheckpointAsync()
    }

    /** Кнопка «Восстановить ресурсы»: сбрасывает перезарядку всех узлов текущей локации. */
    fun onRestoreResources() {
        _uiState.update { st ->
            val run = st.activeRun ?: return@update st
            val updatedNodes = run.nodesByLocation.mapValues { (_, nodes) ->
                nodes.map { it.copy(respawnRemainingSec = 0f) }
            }
            st.copy(
                activeRun = run.copy(
                    nodesByLocation = updatedNodes,
                    lastStatusMessage = "Ресурсы на карте восстановлены."
                )
            )
        }
    }

    fun onFpsMeasured(fps: Int) {
        if (fps > 0 && fps != _uiState.value.measuredFps) {
            _uiState.update { it.copy(measuredFps = fps) }
        }
    }

    /** OpenGL-сцена отрисовала первый кадр — светлый экран загрузки заменяется игрой. */
    fun onSceneReady() {
        if (!_uiState.value.sceneReady) {
            _uiState.update { it.copy(sceneReady = true) }
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
     * Один шаг игрового цикла (20 Гц для отзывчивого движения без рывков).
     */
    fun stepSimulation(deltaSeconds: Float) {
        val current = _uiState.value
        val run = current.activeRun ?: return
        if (run.phase == RunPhase.DEAD) return

        // Кулдауны кнопок тикают всегда
        if (_uiState.value.cooldowns.isNotEmpty()) {
            _uiState.update { st ->
                st.copy(cooldowns = st.cooldowns.mapValues { (_, v) -> (v - deltaSeconds).coerceAtLeast(0f) }
                    .filterValues { it > 0.01f })
            }
        }

        // Пеший переход между локациями: идём, а не телепортируемся
        val journey = _uiState.value.travelJourney
        if (journey != null) {
            val movingNow = abs(current.joystickForward) > 0.05f || abs(current.joystickStrafe) > 0.05f
            val speed = if (movingNow) 1.65f else 1f
            val left = journey.remainingSec - deltaSeconds * speed
            if (left <= 0f) {
                val arrived = SurvivalEngine.travelToLocation(
                    run, current.baseProgress, journey.destination
                )
                _uiState.update {
                    it.copy(
                        activeRun = arrived.run,
                        baseProgress = arrived.base,
                        travelJourney = null
                    )
                }
                persistCheckpointAsync()
                return
            } else {
                _uiState.update { it.copy(travelJourney = journey.copy(remainingSec = left)) }
            }
        }

        val isMoving = abs(current.joystickForward) > 0.05f || abs(current.joystickStrafe) > 0.05f
        val movedRun = if (isMoving) {
            SurvivalEngine.movePlayer(
                run = run,
                forwardInput = current.joystickForward,
                strafeInput = current.joystickStrafe,
                deltaSeconds = deltaSeconds,
                base = current.baseProgress
            )
        } else {
            run
        }

        // Переход между районами — БЕЗ телепорта: мир непрерывный, игрок просто идёт
        // через проход в железных воротах тропы, а район переключается по позиции.

        // Рыбалка в процессе: персонаж рыбачит у лунки, затем поклёвка
        var runForTick = movedRun
        var baseForTick = current.baseProgress
        if (movedRun.isFishing) {
            val fishing = com.ayaks.winterforest.domain.rules.AdvancedMechanics.tickFishing(
                movedRun, current.baseProgress, deltaSeconds
            )
            runForTick = fishing.run
            baseForTick = fishing.base
        }

        val outcome = SurvivalEngine.tick(
            run = runForTick,
            base = baseForTick,
            deltaSeconds = deltaSeconds,
            isMoving = isMoving
        )

        // Очки навыков за уровни + синхронизация ежедневных испытаний
        val levelGain = (outcome.run.level - run.level).coerceAtLeast(0)
        val dayIndex = (outcome.run.elapsedRealSeconds / GameRunState.FULL_CYCLE_SECONDS).toInt()
        var nextBase = com.ayaks.winterforest.domain.rules.AdvancedMechanics.syncDaily(outcome.base, dayIndex)
        if (levelGain > 0) nextBase = nextBase.copy(perkPoints = nextBase.perkPoints + levelGain)
        val outcome2 = outcome.copy(base = nextBase)

        val diedJustNow = outcome2.run.phase == RunPhase.DEAD
        _uiState.update {
            it.copy(
                activeRun = outcome2.run,
                baseProgress = outcome2.base,
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

    fun onActionGather() = applyEngineAction(cooldownId = "gather", cooldownSec = 1.2f) { run, base ->
        SurvivalEngine.gatherNearest(run, base, withToolStrikeBonus = false)
    }

    fun onActionStrike() = applyEngineAction(cooldownId = "strike", cooldownSec = 1.0f) { run, base ->
        SurvivalEngine.performSmartAction(run, base, withToolStrikeBonus = true)
    }

    fun onActionQuickUse() = applyEngineAction(cooldownId = "quick_use", cooldownSec = 0.9f) { run, base ->
        SurvivalEngine.quickUseBestSurvivalItem(run, base)
    }

    fun onUseSpecificResource(type: ResourceType) = applyEngineAction(cooldownId = "use_item", cooldownSec = 0.9f) { run, base ->
        SurvivalEngine.useResource(run, base, type)
    }

    fun onIgniteResinTorch() = applyEngineAction(cooldownId = "ignite", cooldownSec = 2.0f) { run, base ->
        SurvivalEngine.igniteResinTorch(run, base)
    }

    fun onReturnToBase() = applyEngineAction { run, base ->
        SurvivalEngine.returnToBase(run, base)
    }

    fun onTravelToLocation(destination: LocationId) {
        val state = _uiState.value
        val existingRun = state.activeRun ?: return
        if (destination == existingRun.currentLocation) return
        // Пеший переход: идём до соседней локации, а не телепортируемся
        val total = com.ayaks.winterforest.domain.rules.AdvancedMechanics.travelSeconds(
            existingRun.currentLocation, destination
        )
        _uiState.update {
            it.copy(
                travelJourney = TravelJourney(destination, total, total),
                destination = AppDestination.GAME
            )
        }
    }

    fun onFishAtHole() = applyEngineAction(
        cooldownId = "fish",
        cooldownSec = com.ayaks.winterforest.domain.model.GameRunState.FISHING_PROCESS_SECONDS
    ) { run, base ->
        // Рыбалка — процесс: персонаж рыбачит несколько секунд, потом поклёвка
        // и перезарядка лунки (тик процесса идёт в stepSimulation).
        com.ayaks.winterforest.domain.rules.AdvancedMechanics.startFishing(run, base)
    }

    // ---------- Хотбар быстрого доступа ----------

    /** Положить запись (еда/инструмент/расходник) в слот хотбара. */
    fun onAssignHotbar(index: Int, entry: com.ayaks.winterforest.domain.model.HotbarEntry) {
        _uiState.update { st ->
            val run = st.activeRun ?: return@update st
            st.copy(activeRun = run.withHotbarSlot(index, entry))
        }
        persistCheckpointAsync()
    }

    /** Убрать запись из слота хотбара. */
    fun onClearHotbar(index: Int) {
        _uiState.update { st ->
            val run = st.activeRun ?: return@update st
            st.copy(activeRun = run.withHotbarSlot(index, null))
        }
        persistCheckpointAsync()
    }

    /** Использовать слот хотбара: экипировать инструмент либо съесть/применить расходник. */
    fun onUseHotbar(index: Int) = applyEngineAction(cooldownId = "hotbar_$index", cooldownSec = 0.8f) { run, base ->
        com.ayaks.winterforest.domain.rules.AdvancedMechanics.consumeHotbarEntry(run, base, index)
    }

    fun onPlaceSnowShelter() = applyEngineAction(cooldownId = "shelter", cooldownSec = 6.0f) { run, base ->
        com.ayaks.winterforest.domain.rules.AdvancedMechanics.placeSnowShelter(run, base)
    }

    fun onUnlockPerk(perk: com.ayaks.winterforest.domain.rules.PerkId) {
        _uiState.update { st ->
            st.copy(baseProgress = com.ayaks.winterforest.domain.rules.AdvancedMechanics.unlockPerk(st.baseProgress, perk))
        }
        persistCheckpointAsync()
    }

    fun onClaimDaily(challenge: com.ayaks.winterforest.domain.rules.DailyChallengeId) {
        _uiState.update { st ->
            val base = com.ayaks.winterforest.domain.rules.AdvancedMechanics.claimChallenge(st.baseProgress, challenge)
            val run = st.activeRun
            if (base != st.baseProgress && run != null) {
                val (xp, lvl) = SurvivalEngine.applyXpGain(run.xp, run.level, challenge.rewardXp)
                st.copy(baseProgress = base, activeRun = run.copy(xp = xp, level = lvl))
            } else {
                st.copy(baseProgress = base)
            }
        }
        persistCheckpointAsync()
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

    fun onSmeltIron(batches: Int) = applyEngineAction(cooldownId = "smelt", cooldownSec = 2.0f) { run, base ->
        val o = SurvivalEngine.smeltAtFurnace(run, base, batches)
        if (o.actionSucceeded) {
            o.copy(base = com.ayaks.winterforest.domain.rules.AdvancedMechanics.trackChallenge(
                o.base, com.ayaks.winterforest.domain.rules.DailyChallengeId.Kind.CRAFT, 1
            ))
        } else o
    }

    fun onCraftRecipe(recipe: WorkbenchRecipe) = applyEngineAction(cooldownId = "craft", cooldownSec = 2.5f) { run, base ->
        val o = SurvivalEngine.craftAtWorkbench(run, base, recipe)
        if (o.actionSucceeded) {
            o.copy(base = com.ayaks.winterforest.domain.rules.AdvancedMechanics.trackChallenge(
                o.base, com.ayaks.winterforest.domain.rules.DailyChallengeId.Kind.CRAFT, 1
            ))
        } else o
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

    fun onGraphicsQualityChanged(quality: GraphicsQuality) {
        vmScope.launch { repository.updateGraphicsQuality(quality) }
    }

    fun onShowFpsChanged(enabled: Boolean) {
        vmScope.launch { repository.updateShowFpsCounter(enabled) }
    }

    fun onAutoSprintChanged(enabled: Boolean) {
        vmScope.launch { repository.updateAutoSprint(enabled) }
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
        cooldownId: String? = null,
        cooldownSec: Float = 0f,
        block: (GameRunState, BaseProgress) -> com.ayaks.winterforest.domain.rules.EngineStepOutcome
    ) {
        val state = _uiState.value
        val run = state.activeRun ?: return
        val outcome = block(run, state.baseProgress)
        _uiState.update {
            it.copy(
                activeRun = outcome.run,
                baseProgress = outcome.base,
                cooldowns = if (cooldownId != null && cooldownSec > 0f) {
                    it.cooldowns + (cooldownId to cooldownSec)
                } else {
                    it.cooldowns
                }
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
        private const val TICK_INTERVAL_MS = 50L // 20 Гц плавной физической симуляции без рывков!
        private const val AUTO_SAVE_INTERVAL_SEC = 10f
    }
}
