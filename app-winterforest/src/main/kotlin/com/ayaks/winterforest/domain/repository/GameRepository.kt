package com.ayaks.winterforest.domain.repository

import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.GameRunState
import kotlinx.coroutines.flow.Flow

data class UserSettings(
    val soundVolume: Float = 0.80f,
    val lookSensitivity: Float = 1.0f,
    val gdprUserConsent: Boolean = false,
    val consentDialogShown: Boolean = false,
    // Геолокация отключена безусловно по требованиям конфиденциальности игры
    val locationTrackingEnabled: Boolean = false
)

sealed interface LoadSaveResult {
    data class Loaded(
        val baseProgress: BaseProgress,
        val activeRun: GameRunState?
    ) : LoadSaveResult

    data class Corrupted(val reason: String) : LoadSaveResult
}

interface GameRepository {
    val settingsFlow: Flow<UserSettings>

    suspend fun loadGameSnapshot(): LoadSaveResult
    suspend fun saveBaseProgress(progress: BaseProgress)
    suspend fun saveActiveRun(run: GameRunState?)
    suspend fun saveCheckpoint(baseProgress: BaseProgress, activeRun: GameRunState?)
    suspend fun resetAllProgress()

    suspend fun updateSoundVolume(volume: Float)
    suspend fun updateLookSensitivity(sensitivity: Float)
    suspend fun updateGdprConsent(granted: Boolean)
}
