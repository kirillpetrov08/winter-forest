package com.ayaks.winterforest.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ayaks.winterforest.domain.repository.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsDataStore(private val dataStore: DataStore<Preferences>) {

    val settingsFlow: Flow<UserSettings> = dataStore.data.map { prefs ->
        UserSettings(
            soundVolume = (prefs[KEY_SOUND_VOLUME] ?: 0.80f).coerceIn(0f, 1f),
            lookSensitivity = (prefs[KEY_LOOK_SENSITIVITY] ?: 1.0f).coerceIn(0.3f, 2.5f),
            gdprUserConsent = prefs[KEY_GDPR_CONSENT] ?: false,
            consentDialogShown = prefs[KEY_CONSENT_SHOWN] ?: false,
            // Геолокация жёстко отключена всегда
            locationTrackingEnabled = false
        )
    }

    suspend fun setSoundVolume(volume: Float) {
        dataStore.edit { it[KEY_SOUND_VOLUME] = volume.coerceIn(0f, 1f) }
    }

    suspend fun setLookSensitivity(sensitivity: Float) {
        dataStore.edit { it[KEY_LOOK_SENSITIVITY] = sensitivity.coerceIn(0.3f, 2.5f) }
    }

    suspend fun setGdprConsent(granted: Boolean) {
        dataStore.edit {
            it[KEY_GDPR_CONSENT] = granted
            it[KEY_CONSENT_SHOWN] = true
        }
    }

    suspend fun resetSettings() {
        dataStore.edit { it.clear() }
    }

    companion object {
        private val KEY_SOUND_VOLUME = floatPreferencesKey("sound_volume")
        private val KEY_LOOK_SENSITIVITY = floatPreferencesKey("look_sensitivity")
        private val KEY_GDPR_CONSENT = booleanPreferencesKey("gdpr_user_consent")
        private val KEY_CONSENT_SHOWN = booleanPreferencesKey("consent_dialog_shown")

        fun inMemoryForTesting(): SettingsDataStore {
            val map = LinkedHashMap<Preferences.Key<*>, Any?>()
            val store = object : DataStore<Preferences> {
                private val flow = kotlinx.coroutines.flow.MutableStateFlow<Preferences>(emptyPreferences())
                override val data: Flow<Preferences> = flow
                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                    val next = transform(flow.value)
                    flow.value = next
                    return next
                }
            }
            return SettingsDataStore(store)
        }

        fun create(context: Context): SettingsDataStore {
            val appContext = context.applicationContext
            val store = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { appContext.preferencesDataStoreFile("winter_forest_settings") }
            )
            return SettingsDataStore(store)
        }
    }
}
