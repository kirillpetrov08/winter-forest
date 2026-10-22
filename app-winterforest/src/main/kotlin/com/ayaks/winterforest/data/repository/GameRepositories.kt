package com.ayaks.winterforest.data.repository

import com.ayaks.winterforest.data.db.ActiveRunEntity
import com.ayaks.winterforest.data.db.BaseProgressEntity
import com.ayaks.winterforest.data.db.CorruptedSaveException
import com.ayaks.winterforest.data.db.GameProgressDao
import com.ayaks.winterforest.data.db.SavePayloadCodec
import com.ayaks.winterforest.data.preferences.SettingsDataStore
import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.GameRunState
import com.ayaks.winterforest.domain.repository.GameRepository
import com.ayaks.winterforest.domain.repository.LoadSaveResult
import com.ayaks.winterforest.domain.repository.UserSettings
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Основная production-реализация репозитория на связке Room (структурированные сохранения
 * с контролем целостности CRC32) + Jetpack DataStore Preferences (настройки и флаг согласия).
 */
class RoomGameRepository(
    private val dao: GameProgressDao,
    private val settingsDataStore: SettingsDataStore
) : GameRepository {

    override val settingsFlow: Flow<UserSettings> = settingsDataStore.settingsFlow

    override suspend fun loadGameSnapshot(): LoadSaveResult {
        return try {
            val baseEntity = dao.getBaseProgress()
            val base = if (baseEntity != null) {
                SavePayloadCodec.decodeBaseProgress(baseEntity.payloadJson, baseEntity.crc32)
            } else {
                BaseProgress()
            }

            val runEntity = dao.getActiveRun()
            val activeRun = if (runEntity != null) {
                SavePayloadCodec.decodeRunState(runEntity.payloadJson, runEntity.crc32)
            } else {
                null
            }

            LoadSaveResult.Loaded(baseProgress = base, activeRun = activeRun)
        } catch (corrupted: CorruptedSaveException) {
            LoadSaveResult.Corrupted(corrupted.message ?: "Файл сохранения повреждён.")
        } catch (unexpected: Throwable) {
            LoadSaveResult.Corrupted("Не удалось прочитать сохранение: ${unexpected.message}")
        }
    }

    override suspend fun saveBaseProgress(progress: BaseProgress) {
        val (json, crc) = SavePayloadCodec.encodeBaseProgress(progress)
        dao.upsertBaseProgress(
            BaseProgressEntity(
                payloadJson = json,
                crc32 = crc,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    override suspend fun saveActiveRun(run: GameRunState?) {
        if (run == null) {
            dao.clearActiveRun()
        } else {
            val (json, crc) = SavePayloadCodec.encodeRunState(run)
            dao.upsertActiveRun(
                ActiveRunEntity(
                    payloadJson = json,
                    crc32 = crc,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    override suspend fun saveCheckpoint(baseProgress: BaseProgress, activeRun: GameRunState?) {
        saveBaseProgress(baseProgress)
        saveActiveRun(activeRun)
    }

    override suspend fun resetAllProgress() {
        dao.clearActiveRun()
        dao.clearBaseProgress()
    }

    override suspend fun updateSoundVolume(volume: Float) {
        settingsDataStore.setSoundVolume(volume)
    }

    override suspend fun updateLookSensitivity(sensitivity: Float) {
        settingsDataStore.setLookSensitivity(sensitivity)
    }

    override suspend fun updateGdprConsent(granted: Boolean) {
        settingsDataStore.setGdprConsent(granted)
    }
}

/**
 * Дисковая JVM-совместимая реализация GameRepository на том же формате конвертов SavePayloadCodec (CRC32 + JSON).
 * Используется для детерминированных интеграционных JUnit-тестов сохранения, перезапуска и битых файлов.
 */
class FileBackedGameRepository(private val rootDir: File) : GameRepository {

    private val baseFile = File(rootDir, "base_progress.json")
    private val runFile = File(rootDir, "active_run.json")
    private val settingsState = MutableStateFlow(UserSettings())

    override val settingsFlow: Flow<UserSettings> = settingsState.asStateFlow()

    init {
        rootDir.mkdirs()
    }

    override suspend fun loadGameSnapshot(): LoadSaveResult {
        return try {
            val base = if (baseFile.exists()) {
                val env = SavePayloadCodec.decodeEnvelope(baseFile.readText(Charsets.UTF_8))
                SavePayloadCodec.decodeBaseProgress(env.bodyJson, env.crc32)
            } else {
                BaseProgress()
            }

            val run = if (runFile.exists()) {
                val env = SavePayloadCodec.decodeEnvelope(runFile.readText(Charsets.UTF_8))
                SavePayloadCodec.decodeRunState(env.bodyJson, env.crc32)
            } else {
                null
            }
            LoadSaveResult.Loaded(baseProgress = base, activeRun = run)
        } catch (corrupted: CorruptedSaveException) {
            LoadSaveResult.Corrupted(corrupted.message ?: "Повреждённое сохранение.")
        } catch (t: Throwable) {
            LoadSaveResult.Corrupted("Ошибка чтения сохранения: ${t.message}")
        }
    }

    override suspend fun saveBaseProgress(progress: BaseProgress) {
        rootDir.mkdirs()
        val (json, crc) = SavePayloadCodec.encodeBaseProgress(progress)
        baseFile.writeText(SavePayloadCodec.encodeEnvelope(json, crc), Charsets.UTF_8)
    }

    override suspend fun saveActiveRun(run: GameRunState?) {
        rootDir.mkdirs()
        if (run == null) {
            if (runFile.exists()) runFile.delete()
        } else {
            val (json, crc) = SavePayloadCodec.encodeRunState(run)
            runFile.writeText(SavePayloadCodec.encodeEnvelope(json, crc), Charsets.UTF_8)
        }
    }

    override suspend fun saveCheckpoint(baseProgress: BaseProgress, activeRun: GameRunState?) {
        saveBaseProgress(baseProgress)
        saveActiveRun(activeRun)
    }

    override suspend fun resetAllProgress() {
        if (runFile.exists()) runFile.delete()
        if (baseFile.exists()) baseFile.delete()
    }

    override suspend fun updateSoundVolume(volume: Float) {
        settingsState.value = settingsState.value.copy(soundVolume = volume.coerceIn(0f, 1f))
    }

    override suspend fun updateLookSensitivity(sensitivity: Float) {
        settingsState.value = settingsState.value.copy(lookSensitivity = sensitivity.coerceIn(0.3f, 2.5f))
    }

    override suspend fun updateGdprConsent(granted: Boolean) {
        settingsState.value = settingsState.value.copy(
            gdprUserConsent = granted,
            consentDialogShown = true,
            locationTrackingEnabled = false
        )
    }
}
