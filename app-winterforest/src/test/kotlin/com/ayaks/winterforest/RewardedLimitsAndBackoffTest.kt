package com.ayaks.winterforest

import com.ayaks.winterforest.data.ads.AdConfig
import com.ayaks.winterforest.data.ads.LoadedRewardedHandle
import com.ayaks.winterforest.data.ads.RetryScheduler
import com.ayaks.winterforest.data.ads.RewardedAdController
import com.ayaks.winterforest.data.ads.RewardedAdSdkPort
import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.GameRunState
import com.ayaks.winterforest.domain.model.RunPhase
import com.ayaks.winterforest.domain.rules.SurvivalEngine
import java.util.PriorityQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardedLimitsAndBackoffTest {

    @Test
    fun `rewarded timer skip enforces maximum 3 uses per 6 minute game day and resets next day`() {
        val base = BaseProgress()
        var run = SurvivalEngine.createNewRun(base)

        for (attempt in 1..3) {
            run = run.copy(restCooldownRemainingSec = 25f)
            val skipped = SurvivalEngine.applyRewardedSkipTimer(run, base)
            assertTrue("Пропуск #$attempt должен быть разрешён", skipped.actionSucceeded)
            run = skipped.run
            assertEquals(attempt, run.skipsUsedInCurrentWindow)
            assertEquals(0f, run.restCooldownRemainingSec, 0.001f)
        }

        // 4-я попытка в течение тех же 6 минут обязана быть отклонена
        run = run.copy(restCooldownRemainingSec = 25f)
        val fourthAttempt = SurvivalEngine.applyRewardedSkipTimer(run, base)
        assertFalse("4-й пропуск за игровой день обязан блокироваться", fourthAttempt.actionSucceeded)
        assertEquals(25f, fourthAttempt.run.restCooldownRemainingSec, 0.001f)

        // После 6 минут (360 секунд) квота сбрасывается и пропуск снова доступен
        val nextDayRun = SurvivalEngine.tick(run, base, deltaSeconds = GameRunState.GAME_DAY_SKIP_WINDOW_SECONDS).run
        assertEquals(0, nextDayRun.skipsUsedInCurrentWindow)
    }

    @Test
    fun `rewarded revive works strictly once per run even if player dies a second time`() {
        val base = BaseProgress()
        val deadOnce = SurvivalEngine.createNewRun(base).copy(
            phase = RunPhase.DEAD,
            health = 0f,
            deathsInRun = 1
        )

        val firstRevive = SurvivalEngine.applyRewardedRevive(deadOnce, base)
        assertTrue(firstRevive.actionSucceeded)
        assertEquals(1, firstRevive.run.revivesUsedInRun)
        assertEquals(RunPhase.PLAYING, firstRevive.run.phase)

        val deadTwice = firstRevive.run.copy(
            phase = RunPhase.DEAD,
            health = 0f,
            deathsInRun = 2
        )
        val secondRevive = SurvivalEngine.applyRewardedRevive(deadTwice, base)
        assertFalse("Повторное возрождение в том же забеге обязано быть запрещено", secondRevive.actionSucceeded)
        assertEquals(RunPhase.DEAD, secondRevive.run.phase)
    }

    @Test
    fun `rewarded controller never retries synchronously inside onAdFailedToLoad and caps at 3 attempts`() {
        var virtualClockMs = 0L
        val pendingTimers = PriorityQueue<Pair<Long, () -> Unit>>(compareBy { it.first })
        fun advanceClockBy(deltaMs: Long) {
            val target = virtualClockMs + deltaMs
            while (true) {
                val head = pendingTimers.peek() ?: break
                if (head.first <= target) {
                    val (dueMs, task) = pendingTimers.poll()!!
                    virtualClockMs = dueMs
                    task()
                } else {
                    break
                }
            }
            virtualClockMs = target
        }

        var loadCallCount = 0
        var insideFailureCallback = false
        var synchronousReentryDetected = false
        var mainThreadEnforced = true
        var currentlyOnMain = false

        val failingPort = object : RewardedAdSdkPort {
            override fun loadAd(
                adUnitId: String,
                onLoaded: (LoadedRewardedHandle) -> Unit,
                onFailed: (String) -> Unit
            ) {
                if (!currentlyOnMain) {
                    mainThreadEnforced = false
                }
                if (insideFailureCallback) {
                    synchronousReentryDetected = true
                }
                loadCallCount++
                insideFailureCallback = true
                try {
                    onFailed("No fill (simulated network/inventory error)")
                } finally {
                    insideFailureCallback = false
                }
            }

            override fun cancelAll() = Unit
        }

        val controller = RewardedAdController(
            adConfig = AdConfig("R-M-1", "R-M-2", isDebugBuild = false),
            sdkPort = failingPort,
            isOnMainThread = { currentlyOnMain },
            dispatchToMain = { block ->
                val prev = currentlyOnMain
                currentlyOnMain = true
                try {
                    block()
                } finally {
                    currentlyOnMain = prev
                }
            },
            retryScheduler = RetryScheduler { delayMs, task ->
                pendingTimers.add((virtualClockMs + delayMs) to task)
            }
        )

        // Вызываем с «фонового» потока (currentlyOnMain = false), проверяем авто-переброс на Main
        controller.ensurePreloaded()
        assertEquals("Сразу выполняется только 1-я попытка без синхронного повтора", 1, loadCallCount)
        assertFalse("Запрещён синхронный повтор внутри onAdFailedToLoad", synchronousReentryDetected)

        // Через 1999 мс повтор ещё не запущен; на 2000 мс идёт 2-я попытка
        advanceClockBy(1_999L)
        assertEquals(1, loadCallCount)

        advanceClockBy(1L)
        assertEquals(2, loadCallCount)

        // Следующий backoff = 4000 мс -> 3-я попытка
        advanceClockBy(4_000L)
        assertEquals(3, loadCallCount)

        // Дальше автоповторы строго остановлены (лимит MAX_CONSECUTIVE_ATTEMPTS = 3)
        advanceClockBy(60_000L)
        assertEquals("После 3 неудачных попыток фоновые повторы прекращаются", 3, loadCallCount)
        assertTrue(controller.state.value.retryExhausted)
        assertEquals(listOf(2_000L, 4_000L), controller.state.value.scheduledBackoffDelaysMs)
        assertTrue("Все обращения к SDK должны выполняться на главном потоке", mainThreadEnforced)
    }
}
