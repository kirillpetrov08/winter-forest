package com.ayaks.winterforest

import android.app.Application
import androidx.room.Room
import com.ayaks.winterforest.data.ads.AdConfig
import com.ayaks.winterforest.data.ads.RewardedAdController
import com.ayaks.winterforest.data.ads.YandexAdsPrivacyBootstrapper
import com.ayaks.winterforest.data.ads.YandexRewardedAdSdkAdapter
import com.ayaks.winterforest.data.db.WinterForestDatabase
import com.ayaks.winterforest.data.preferences.SettingsDataStore
import com.ayaks.winterforest.data.repository.RoomGameRepository
import com.ayaks.winterforest.domain.repository.GameRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Ручной контейнер зависимостей приложения (без Hilt/Dagger).
 */
class AppContainer(application: Application) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val adConfig: AdConfig = AdConfig.fromContext(application)

    val database: WinterForestDatabase = Room.databaseBuilder(
        application,
        WinterForestDatabase::class.java,
        WinterForestDatabase.DATABASE_NAME
    )
        .fallbackToDestructiveMigration()
        .build()

    val settingsDataStore: SettingsDataStore = SettingsDataStore.create(application)

    val repository: GameRepository = RoomGameRepository(
        dao = database.gameProgressDao(),
        settingsDataStore = settingsDataStore
    )

    val rewardedAdController: RewardedAdController = RewardedAdController(
        adConfig = adConfig,
        sdkPort = YandexRewardedAdSdkAdapter(application)
    )

    init {
        appScope.launch {
            val savedSettings = settingsDataStore.settingsFlow.first()
            YandexAdsPrivacyBootstrapper.applyPrivacyAndInitialize(
                context = application,
                adConfig = adConfig,
                hasUserConsent = savedSettings.gdprUserConsent,
                isAgeRestricted = false,
                onComplete = {
                    rewardedAdController.ensurePreloaded()
                }
            )
        }
    }
}

class WinterForestApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
