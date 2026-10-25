package com.ayaks.winterforest.data.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.common.YandexAds
import com.yandex.mobile.ads.rewarded.Reward
import com.yandex.mobile.ads.rewarded.RewardedAd
import com.yandex.mobile.ads.rewarded.RewardedAdEventListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoadListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Безопасная инициализация Yandex Mobile Ads SDK 8.6.0:
 * строго ДО вызова YandexAds.initialize(...) применяются настройки согласия
 * и безусловно отключается передача геолокации.
 */
object YandexAdsPrivacyBootstrapper {
    @Volatile
    var isInitialized: Boolean = false
        private set

    fun applyPrivacyAndInitialize(
        context: Context,
        adConfig: AdConfig,
        hasUserConsent: Boolean,
        isAgeRestricted: Boolean = false,
        onComplete: () -> Unit = {}
    ) {
        if (!adConfig.isBannerEnabled && !adConfig.isRewardedEnabled) {
            // Если оба ID пусты — рекламный SDK не инициализируется и не делает сетевых запросов
            onComplete()
            return
        }
        runCatching {
            val appContext = context.applicationContext
            // 1. Строго до initialize(): согласие пользователя, возраст и запрет геолокации
            YandexAds.setUserConsent(hasUserConsent)
            YandexAds.setLocationTracking(false)
            YandexAds.setAgeRestricted(isAgeRestricted)

            if (!isInitialized) {
                YandexAds.initialize(appContext) {
                    isInitialized = true
                    onComplete()
                }
            } else {
                onComplete()
            }
        }.onFailure {
            // Отсутствие сети или сбой SDK никогда не роняет игру
            isInitialized = false
            onComplete()
        }
    }

    fun updateConsentAtRuntime(hasUserConsent: Boolean) {
        runCatching {
            YandexAds.setUserConsent(hasUserConsent)
            YandexAds.setLocationTracking(false)
        }
    }
}

interface LoadedRewardedHandle {
    fun show(
        activity: Activity?,
        onShown: () -> Unit,
        onFailedToShow: (String) -> Unit,
        onRewarded: () -> Unit,
        onDismissed: () -> Unit
    )

    fun release()
}

interface RewardedAdSdkPort {
    fun loadAd(
        adUnitId: String,
        onLoaded: (LoadedRewardedHandle) -> Unit,
        onFailed: (String) -> Unit
    )

    fun cancelAll()
}

fun interface RetryScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit)
}

/**
 * Реальный адаптер Yandex Mobile Ads SDK 8.6.0.
 * Хранит сильную ссылку на ЕДИНСТВЕННЫЙ экземпляр RewardedAdLoader на протяжении всей сессии.
 */
class YandexRewardedAdSdkAdapter(appContext: Context) : RewardedAdSdkPort {
    private val loader: RewardedAdLoader = RewardedAdLoader(appContext.applicationContext)

    override fun loadAd(
        adUnitId: String,
        onLoaded: (LoadedRewardedHandle) -> Unit,
        onFailed: (String) -> Unit
    ) {
        val request = AdRequest.Builder(adUnitId).build()
        loader.loadAd(
            request,
            object : RewardedAdLoadListener {
                override fun onAdLoaded(rewarded: RewardedAd) {
                    onLoaded(YandexLoadedAdHandle(rewarded))
                }

                override fun onAdFailedToLoad(error: AdRequestError) {
                    onFailed("${error.code}: ${error.description}")
                }
            }
        )
    }

    override fun cancelAll() {
        loader.cancelLoading()
    }

    private class YandexLoadedAdHandle(private var rewardedAd: RewardedAd?) : LoadedRewardedHandle {
        override fun show(
            activity: Activity?,
            onShown: () -> Unit,
            onFailedToShow: (String) -> Unit,
            onRewarded: () -> Unit,
            onDismissed: () -> Unit
        ) {
            val hostActivity = activity
            val ad = rewardedAd
            if (hostActivity == null || ad == null) {
                onFailedToShow("Activity недоступна для показа объявления.")
                return
            }
            ad.setAdEventListener(object : RewardedAdEventListener {
                override fun onAdShown() {
                    onShown()
                }

                override fun onAdFailedToShow(adError: AdError) {
                    release()
                    onFailedToShow(adError.description)
                }

                override fun onAdDismissed() {
                    release()
                    onDismissed()
                }

                override fun onAdClicked() = Unit

                override fun onAdImpression(impressionData: ImpressionData?) = Unit

                override fun onRewarded(reward: Reward) {
                    onRewarded()
                }
            })
            ad.show(hostActivity)
        }

        override fun release() {
            rewardedAd?.setAdEventListener(null)
            rewardedAd = null
        }
    }
}

data class RewardedAdUiState(
    val isConfigured: Boolean = false,
    val isLoading: Boolean = false,
    val isReady: Boolean = false,
    val consecutiveFailures: Int = 0,
    val retryExhausted: Boolean = false,
    val scheduledBackoffDelaysMs: List<Long> = emptyList()
)

/**
 * Контроллер наградных видео РСЯ со строгим соблюдением всех правил раздела 3.3:
 * - один экземпляр загрузчика (RewardedAdSdkPort) и сильная ссылка на загруженное объявление;
 * - все вызовы SDK выполняются на главном потоке;
 * - при onAdFailedToLoad повторная загрузка НЕ вызывается синхронно в колбэке, а планируется
 *   с экспоненциальной задержкой (2с -> 4с -> 8с) и жёстким лимитом НЕ БОЛЕЕ 3 попыток подряд;
 * - награда начисляется ИСКЛЮЧИТЕЛЬНО при срабатывании колбэка onRewarded.
 */
class RewardedAdController(
    private val adConfig: AdConfig,
    private val sdkPort: RewardedAdSdkPort,
    private val isOnMainThread: () -> Boolean = {
        Looper.getMainLooper()?.thread == Thread.currentThread()
    },
    private val dispatchToMain: (() -> Unit) -> Unit = { block ->
        val mainLooper = Looper.getMainLooper()
        if (mainLooper != null && mainLooper.thread != Thread.currentThread()) {
            Handler(mainLooper).post(block)
        } else {
            block()
        }
    },
    private val retryScheduler: RetryScheduler = RetryScheduler { delayMs, task ->
        val mainLooper = Looper.getMainLooper()
        if (mainLooper != null) {
            Handler(mainLooper).postDelayed(task, delayMs)
        }
    }
) {
    private var loadedHandle: LoadedRewardedHandle? = null
    private val _state = MutableStateFlow(
        RewardedAdUiState(isConfigured = adConfig.isRewardedEnabled)
    )
    val state: StateFlow<RewardedAdUiState> = _state.asStateFlow()

    fun ensurePreloaded() {
        runOnMain {
            if (!adConfig.isRewardedEnabled) return@runOnMain
            val cur = _state.value
            if (cur.isLoading || cur.isReady || cur.retryExhausted) return@runOnMain
            performLoadAttempt()
        }
    }

    /**
     * Ручная попытка пользователя при нажатии кнопки, если объявление ещё не загружено
     * (строго в пределах MAX_CONSECUTIVE_ATTEMPTS = 3).
     */
    fun showOrTriggerLoad(
        activity: Activity?,
        onEarnedReward: () -> Unit,
        onUnavailableMessage: (String) -> Unit
    ) {
        runOnMain {
            if (!adConfig.isRewardedEnabled) {
                onUnavailableMessage("Наградная реклама отключена.")
                return@runOnMain
            }
            val currentAd = loadedHandle
            if (currentAd != null) {
                var rewardGrantedForImpression = false
                currentAd.show(
                    activity = activity,
                    onShown = {},
                    onFailedToShow = { err ->
                        loadedHandle = null
                        _state.value = _state.value.copy(isReady = false)
                        onUnavailableMessage("Не удалось показать видео: $err")
                    },
                    onRewarded = {
                        // ЕДИНСТВЕННАЯ точка выдачи игровой награды — строго колбэк onRewarded!
                        if (!rewardGrantedForImpression) {
                            rewardGrantedForImpression = true
                            onEarnedReward()
                        }
                    },
                    onDismissed = {
                        loadedHandle = null
                        _state.value = _state.value.copy(isReady = false)
                        ensurePreloaded()
                    }
                )
            } else {
                val cur = _state.value
                if (cur.retryExhausted || cur.consecutiveFailures >= MAX_CONSECUTIVE_ATTEMPTS) {
                    onUnavailableMessage("Реклама временно недоступна (превышен лимит 3 попыток загрузки).")
                } else {
                    if (!cur.isLoading) {
                        performLoadAttempt()
                    }
                    onUnavailableMessage("Реклама ещё не готова, попробуйте чуть позже.")
                }
            }
        }
    }

    fun release() {
        runOnMain {
            sdkPort.cancelAll()
            loadedHandle?.release()
            loadedHandle = null
            _state.value = RewardedAdUiState(isConfigured = adConfig.isRewardedEnabled)
        }
    }

    private fun performLoadAttempt() {
        val currentFailures = _state.value.consecutiveFailures
        if (currentFailures >= MAX_CONSECUTIVE_ATTEMPTS) {
            _state.value = _state.value.copy(
                isLoading = false,
                retryExhausted = true
            )
            return
        }

        _state.value = _state.value.copy(isLoading = true)
        sdkPort.loadAd(
            adUnitId = adConfig.rewardedAdUnitId,
            onLoaded = { handle ->
                runOnMain {
                    loadedHandle = handle
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isReady = true,
                        consecutiveFailures = 0,
                        retryExhausted = false
                    )
                }
            },
            onFailed = { _ ->
                runOnMain {
                    val failuresNow = _state.value.consecutiveFailures + 1
                    val exhausted = failuresNow >= MAX_CONSECUTIVE_ATTEMPTS
                    val delays = _state.value.scheduledBackoffDelaysMs.toMutableList()
                    if (!exhausted) {
                        // Экспоненциальная задержка: 2000 мс, 4000 мс (без синхронного вызова loadAd в onAdFailedToLoad!)
                        val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl (failuresNow - 1))
                        delays += delayMs
                        _state.value = _state.value.copy(
                            isLoading = false,
                            isReady = false,
                            consecutiveFailures = failuresNow,
                            retryExhausted = false,
                            scheduledBackoffDelaysMs = delays
                        )
                        retryScheduler.schedule(delayMs) {
                            runOnMain {
                                if (!_state.value.isReady && !_state.value.isLoading && !_state.value.retryExhausted) {
                                    performLoadAttempt()
                                }
                            }
                        }
                    } else {
                        // После 3 неудачных попыток подряд автоповторы прекращаются
                        _state.value = _state.value.copy(
                            isLoading = false,
                            isReady = false,
                            consecutiveFailures = failuresNow,
                            retryExhausted = true,
                            scheduledBackoffDelaysMs = delays
                        )
                    }
                }
            }
        )
    }

    private fun runOnMain(block: () -> Unit) {
        if (isOnMainThread()) {
            block()
        } else {
            dispatchToMain(block)
        }
    }

    companion object {
        const val MAX_CONSECUTIVE_ATTEMPTS = 3
        const val INITIAL_RETRY_DELAY_MS = 2_000L
    }
}
