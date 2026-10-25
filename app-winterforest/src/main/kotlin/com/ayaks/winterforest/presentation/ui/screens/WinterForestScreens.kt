package com.ayaks.winterforest.presentation.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ayaks.winterforest.presentation.ui.HudZones
import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.BaseUpgradeType
import com.ayaks.winterforest.domain.model.GraphicsQuality
import com.ayaks.winterforest.domain.model.Inventory
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.SpecialLootId
import com.ayaks.winterforest.domain.model.StarterLoadout
import com.ayaks.winterforest.domain.model.WorkbenchRecipe
import com.ayaks.winterforest.domain.rules.SurvivalEngine
import com.ayaks.winterforest.presentation.gl.ForestGlSurfaceView
import com.ayaks.winterforest.presentation.gl.RenderSceneSnapshot
import com.ayaks.winterforest.presentation.navigation.AppDestination
import com.ayaks.winterforest.presentation.ui.components.CrosshairOverlay
import com.ayaks.winterforest.presentation.ui.components.GlassChip
import com.ayaks.winterforest.presentation.ui.components.GlassMenuButton
import com.ayaks.winterforest.presentation.ui.components.GlassStatRow
import com.ayaks.winterforest.presentation.ui.components.HealthBarColor
import com.ayaks.winterforest.presentation.ui.components.HungerBarColor
import com.ayaks.winterforest.presentation.ui.components.MainMenuAdaptiveBanner
import com.ayaks.winterforest.presentation.ui.components.MiniRadarHud
import com.ayaks.winterforest.presentation.ui.components.PrimaryGameButton
import com.ayaks.winterforest.presentation.ui.components.QuickEquipmentHotbar
import com.ayaks.winterforest.presentation.ui.components.StaminaBarColor
import com.ayaks.winterforest.presentation.ui.components.SurvivalStatBar
import com.ayaks.winterforest.presentation.ui.components.VirtualJoystick
import com.ayaks.winterforest.presentation.ui.components.WarmthBarColor
import com.ayaks.winterforest.presentation.ui.components.XpBarColor
import com.ayaks.winterforest.presentation.ui.components.rememberAssetIcon
import com.ayaks.winterforest.presentation.ui.theme.CrimsonDanger
import com.ayaks.winterforest.presentation.ui.theme.DeepPineNight
import com.ayaks.winterforest.presentation.ui.theme.FrostedCardSurface
import com.ayaks.winterforest.presentation.ui.theme.HearthAmberAccent
import com.ayaks.winterforest.presentation.ui.theme.IceCyanPrimary
import com.ayaks.winterforest.presentation.ui.theme.MistMutedText
import com.ayaks.winterforest.presentation.ui.theme.SnowCrispText
import com.ayaks.winterforest.presentation.ui.theme.SpruceEmerald
import com.ayaks.winterforest.presentation.viewmodel.WinterForestUiState
import com.ayaks.winterforest.presentation.viewmodel.WinterForestViewModel
import kotlin.math.roundToInt

/**
 * Горизонтальное Главное меню с живой 3D-панорамой зимнего лагеря на фоне
 * и адаптивным баннером РСЯ строго в нижней кромке экрана меню.
 */
@Composable
fun MainMenuScreen(
    state: WinterForestUiState,
    viewModel: WinterForestViewModel
) {
    if (state.corruptedSaveMessage != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Сохранение повреждено") },
            text = {
                Text(
                    "Не удалось восстановить данные предыдущей сессии (${state.corruptedSaveMessage}). " +
                        "Начните новую игру с чистым профилем."
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.dismissCorruptedSaveAndStartFresh() }) {
                    Text("Начать новую игру")
                }
            }
        )
    }

    if (!state.settings.consentDialogShown &&
        (state.adConfig.isBannerEnabled || state.adConfig.isRewardedEnabled)
    ) {
        AlertDialog(
            onDismissRequest = { viewModel.onGdprConsentSelected(false) },
            title = { Text("Конфиденциальность и реклама") },
            text = {
                Text(
                    "Приложение использует рекламную сеть Яндекса (РСЯ) для показа баннера в меню " +
                        "и добровольных наградных видео. Геолокация полностью отключена. " +
                        "Разрешить персонализацию объявлений?"
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.onGdprConsentSelected(true) }) {
                    Text("Разрешить")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onGdprConsentSelected(false) }) {
                    Text("Без персонализации")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Живая 3D-панорама лагеря отрисовывается корневым PersistentForestScene
        // (режим орбиты меню) — сцена остаётся тёплой и не гаснет при переходах.

        // Лёгкая кинематографическая виньетка для читаемости: фон ПОЛУПРОЗРАЧНЫЙ,
        // живая 3D-сцена лагеря просвечивает сквозь стекло меню
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xA606111B),
                            Color(0x3D081521),
                            Color(0x9407131E)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .displayCutoutPadding()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Левая колонка: логотип, атмосфера и сводка мета-прогресса выжившего
                Column(
                    modifier = Modifier.weight(1.15f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "WINTER FOREST",
                        fontSize = 38.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = SnowCrispText,
                        letterSpacing = 3.sp
                    )
                    Text(
                        text = "Выживание от первого лица в суровой арктической тайге",
                        fontSize = 14.sp,
                        color = IceCyanPrimary,
                        fontWeight = FontWeight.Medium
                    )

                    GlassStatRow(
                        items = listOf(
                            Triple(
                                rememberAssetIcon("glyphs/glyph_timer.png"),
                                "${state.baseProgress.bestDaysSurvived}",
                                "Рекорд, дн."
                            ),
                            Triple(
                                rememberAssetIcon("glyphs/glyph_home.png"),
                                "${state.baseProgress.totalBaseLevel}/20",
                                "Уровень базы"
                            ),
                            Triple(
                                rememberAssetIcon("glyphs/glyph_backpack.png"),
                                "${state.baseProgress.persistentSpecialLoot.size}/${SpecialLootId.entries.size}",
                                "Реликвии"
                            ),
                            Triple(
                                rememberAssetIcon("glyphs/glyph_xp.png"),
                                "${state.baseProgress.totalWildlifeHunted}",
                                "Охота"
                            )
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    GlassChip(
                        text = "Снаряжение: «${state.baseProgress.selectedLoadout.titleRu}»  •  Графика: ${state.settings.graphicsQuality.titleRu}",
                        fill = Color(0x7AFFFFFF),
                        textColor = MistMutedText,
                        fontSize = 11
                    )
                }

                // Правая колонка: один крупный CTA + стеклянные кнопки навигации с иконками
                Column(
                    modifier = Modifier.weight(0.95f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.hasActiveSave) {
                        PrimaryGameButton(
                            text = "Продолжить • День ${state.activeRun?.dayNumberDisplay ?: 1}",
                            icon = rememberAssetIcon("glyphs/glyph_map.png"),
                            onClick = { viewModel.continueSavedGame() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_continue")
                        )
                        GlassMenuButton(
                            text = "Новая экспедиция",
                            icon = rememberAssetIcon("glyphs/glyph_snowfall.png"),
                            onClick = { viewModel.requestNewExpedition() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_new_game")
                        )
                    } else {
                        PrimaryGameButton(
                            text = "Новая экспедиция",
                            icon = rememberAssetIcon("glyphs/glyph_snowfall.png"),
                            onClick = { viewModel.requestNewExpedition() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_new_game")
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        GlassMenuButton(
                            text = "База и крафт",
                            icon = rememberAssetIcon("glyphs/glyph_home.png"),
                            onClick = { viewModel.navigateTo(AppDestination.BASE_INVENTORY) },
                            modifier = Modifier.weight(1.15f)
                        )
                        GlassMenuButton(
                            text = "Настройки",
                            icon = rememberAssetIcon("glyphs/glyph_settings.png"),
                            onClick = { viewModel.navigateTo(AppDestination.SETTINGS) },
                            modifier = Modifier.weight(0.85f)
                        )
                    }

                    GlassMenuButton(
                        text = "Об игре и управлении",
                        onClick = { viewModel.navigateTo(AppDestination.ABOUT) },
                        modifier = Modifier.fillMaxWidth(),
                        height = 40.dp,
                        fontSize = 12
                    )
                }
            }

            // ЕДИНСТВЕННОЕ место показа баннера РСЯ во всём приложении — низ главного меню
            MainMenuAdaptiveBanner(
                currentDestination = state.destination,
                adConfig = state.adConfig
            )
        }
    }
}

@Composable
private fun StatPill(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = SnowCrispText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(text = title, color = MistMutedText, fontSize = 10.sp)
    }
}

/**
 * Полноэкранный горизонтальный 3D-экран выживания:
 * - Левый плавающий джойстик с авто-спринтом;
 * - Правая тач-область плавного 360°-обзора;
 * - Компактная панель жизненных показателей слева вверху + круговой мини-радар справа вверху;
 * - Прицел с авто-определением цели в центре + 5-слотовый хотбар внизу + дуга кнопок действий справа.
 */
@Composable
fun GameScreen(
    state: WinterForestUiState,
    viewModel: WinterForestViewModel
) {
    val run = state.activeRun ?: return
    val context = LocalContext.current
    val activity = context as? Activity

    // Единая сетка HUD (§7.14): ВЕРХНЯЯ и НИЖНЯЯ строки собраны структурными Row,
    // поэтому зоны физически не могут наехать друг на друга — ширина каждой
    // колонки известна заранее (HudZones), остатки распределяют Spacer(weight).
    // Компактный профиль включается на узких ландшафтных экранах (< 760 dp):
    // радар прячется, слоты и джойстик уменьшаются, надписи короче.
    BoxWithConstraints(modifier = Modifier.fillMaxSize().testTag("screen_game_3d")) {
        val compact = maxWidth < HudZones.COMPACT_WIDTH_DP.dp
        val statsWidth = if (compact) HudZones.COMPACT_STATS_WIDTH_DP.dp else HudZones.STATS_WIDTH_DP.dp
        val slotSize = if (compact) HudZones.COMPACT_HOTBAR_SLOT_DP else HudZones.HOTBAR_SLOT_DP
        val joystickSize = if (compact) HudZones.COMPACT_JOYSTICK_DP else HudZones.JOYSTICK_DP
        val actionsWidth =
            if (compact) HudZones.COMPACT_ACTIONS_WIDTH_DP.dp else HudZones.ACTIONS_WIDTH_DP.dp
        val widthDp = maxWidth.value

        // 1. Живая 3D-сцена уже отрисовывается корневым PersistentForestScene —
        // здесь только прозрачный слой свайп-обзора камеры поверх неё.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        viewModel.onSwipeLook(
                            deltaYawDeg = drag.x * 0.20f,
                            deltaPitchDeg = -drag.y * 0.16f
                        )
                    }
                }
        )

        // 2. Центральный прицел + контекстная подсказка объекта
        CrosshairOverlay(
            run = run,
            modifier = Modifier.align(Alignment.Center)
        )

        // 3. ВЕРХНЯЯ строка: статы | таймеры | меню+радар
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .displayCutoutPadding()
                .padding(horizontal = 10.dp)
                .padding(top = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 3a. Панель показателей и текущей цели дня (не закрывает обзор)
            Column(
                modifier = Modifier
                    .width(statsWidth)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xBA091520))
                    .border(1.dp, Color(0x327CC6FE), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = run.currentLocation.titleRu,
                        color = SnowCrispText,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (compact) 12.sp else 13.sp
                    )
                    val phaseLabel = if (run.isNight) "Полярная ночь" else "День"
                    val weatherLabel = if (run.isSnowing) "Метель" else "Мороз"
                    Text(
                        text = "Д.${run.dayNumberDisplay} • $phaseLabel • $weatherLabel",
                        color = IceCyanPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SurvivalStatBar("Здоровье", run.health, run.maxHealth, HealthBarColor, Modifier.weight(1f))
                    SurvivalStatBar("Сытость", run.hunger, 100f, HungerBarColor, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SurvivalStatBar("Тепло", run.warmth, 100f, WarmthBarColor, Modifier.weight(1f))
                    SurvivalStatBar("Силы", run.stamina, 100f, StaminaBarColor, Modifier.weight(1f))
                }
                SurvivalStatBar(
                    "Опыт (Ур. ${run.level})",
                    run.xp.toFloat(),
                    run.xpForNextLevel.toFloat(),
                    XpBarColor,
                    Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.weight(1f))

            // 3b. Строка активного таймера (статус, НЕ действие): «Ресурс
            // восстановится через N с» + реклама-пропуск. Кнопки восстановления
            // здесь больше нет — она одна, внизу, в панели действий (§7.2).
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val activeTimer = run.activeSkippableTimer()
                if (activeTimer != null) {
                    // Чип статуса и кнопка рекламы — СТОЛБИКОМ: в ландшафте каждая
                    // лишняя сотня dp ширины сверху съедает место статов и меню
                    // (бюджеты зон посчитаны в HudZones).
                    Text(
                        text = "${activeTimer.first.titleRu}: ${activeTimer.second.roundToInt()} с",
                        color = HearthAmberAccent,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color(0xCC162D40))
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                    if (state.isSkipTimerAdButtonVisible) {
                        Button(
                            onClick = { viewModel.onRequestRewardedSkipTimer(activity) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp).testTag("btn_rewarded_skip")
                        ) {
                            Text("Пропустить (реклама)", fontSize = 10.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // 3c. Меню-кластер + круговой радар
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { viewModel.navigateTo(AppDestination.BASE_INVENTORY) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                text = if (compact) {
                                    "База (${run.inventory.occupiedSlots}/${Inventory.MAX_SLOTS})"
                                } else {
                                    "База / Рюкзак (${run.inventory.occupiedSlots}/${Inventory.MAX_SLOTS})"
                                },
                                fontSize = 11.sp
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.navigateTo(AppDestination.MAIN_MENU) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Меню", fontSize = 11.sp)
                        }
                    }
                    if (state.settings.showFpsCounter) {
                        Text(
                            text = "${state.measuredFps} FPS • VBO",
                            color = SpruceEmerald,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xBA091520))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                    if (run.currentLocation != LocationId.BASE_CLEARING) {
                        OutlinedButton(
                            onClick = { viewModel.onReturnToBase() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("← В лагерь", fontSize = 10.sp)
                        }
                    }
                }

                if (HudZones.radarVisible(widthDp)) {
                    MiniRadarHud(run = run)
                }
            }
        }

        // 4. НИЖНЯЯ строка: джойстик | хотбар | действия
        val targetPrompt = run.activeTargetPrompt()
        val primaryLabel = when {
            targetPrompt?.isDroppedItem == true -> "Подобрать"
            targetPrompt?.isSpecialRelic == true -> "Забрать находку"
            targetPrompt != null -> targetPrompt.actionVerbRu
            else -> "Удар (${run.equippedTool.shortLabelRu})"
        }
        // §7.12: кнопка действия подбирает предмет с земли, когда цель — дроп
        val primaryCooldownId = if (targetPrompt?.isDroppedItem == true) "pickup" else "strike"
        val onPrimaryAction: () -> Unit =
            if (targetPrompt?.isDroppedItem == true) {
                { viewModel.onPickupNearestDropped() }
            } else {
                { viewModel.onActionStrike() }
            }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .displayCutoutPadding()
                .padding(horizontal = 10.dp)
                .padding(bottom = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            VirtualJoystick(
                sizeDp = joystickSize,
                onJoystickChanged = { fwd, str -> viewModel.updateJoystick(fwd, str) }
            )

            Spacer(Modifier.weight(1f))

            QuickEquipmentHotbar(
                run = run,
                slotSizeDp = slotSize,
                onUseSlot = { viewModel.onUseHotbar(it) }
            )

            Spacer(Modifier.weight(1f))

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.width(actionsWidth)
            ) {
                OutlinedButton(
                    onClick = { viewModel.onToggleSprint() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(
                        text = if (run.isSprinting) "Бег: ВКЛ" else "Бег",
                        color = if (run.isSprinting) HearthAmberAccent else SnowCrispText,
                        fontSize = 11.sp
                    )
                }

                // Действия с перезарядкой: внутри кнопки прогресс слева направо
                com.ayaks.winterforest.presentation.ui.components.CooldownButton(
                    label = "Добыть",
                    cooldownRemainingSec = state.cooldowns["gather"] ?: 0f,
                    cooldownTotalSec = 1.2f,
                    onClick = { viewModel.onActionGather() },
                    modifier = Modifier.width(actionsWidth)
                )
                com.ayaks.winterforest.presentation.ui.components.CooldownButton(
                    label = "Рыбалка",
                    cooldownRemainingSec = state.cooldowns["fish"] ?: 0f,
                    cooldownTotalSec = 6.0f,
                    onClick = { viewModel.onFishAtHole() },
                    modifier = Modifier.width(actionsWidth)
                )
                com.ayaks.winterforest.presentation.ui.components.CooldownButton(
                    label = "Укрытие",
                    cooldownRemainingSec = state.cooldowns["shelter"] ?: 0f,
                    cooldownTotalSec = 6.0f,
                    onClick = { viewModel.onPlaceSnowShelter() },
                    modifier = Modifier.width(actionsWidth)
                )

                com.ayaks.winterforest.presentation.ui.components.CooldownButton(
                    label = primaryLabel,
                    cooldownRemainingSec = state.cooldowns[primaryCooldownId] ?: 0f,
                    cooldownTotalSec = 1.0f,
                    onClick = onPrimaryAction,
                    modifier = Modifier.width(actionsWidth).height(48.dp)
                )

                // ЕДИНСТВЕННАЯ кнопка восстановления ресурсов (§7.2): внизу,
                // в панели действий под большим пальцем; сверху её больше нет.
                OutlinedButton(
                    onClick = { viewModel.onRestoreResources() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp).testTag("btn_restore_resources")
                ) {
                    Text("Восстановить ресурсы", fontSize = 10.sp)
                }
            }
        }
    }
}

/**
 * §7.4: выбор класса ПЕРЕД каждым новым забегом. Три стеклянные карточки со
 * стартовым набором; выбор применяется к забегу сразу при старте.
 */
@Composable
fun LoadoutSelectScreen(
    state: WinterForestUiState,
    viewModel: WinterForestViewModel
) {
    var chosen by remember { mutableStateOf(state.baseProgress.selectedLoadout) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xC4060C14))
            .displayCutoutPadding()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Класс экспедиции", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SnowCrispText)
            OutlinedButton(onClick = { viewModel.navigateTo(AppDestination.MAIN_MENU) }) {
                Text("← Меню")
            }
        }
        Text(
            text = "Класс определяет стартовый набор каждого нового забега. Выбор делается перед стартом, а не в базе.",
            color = MistMutedText,
            fontSize = 12.sp
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            for (loadout in StarterLoadout.entries) {
                val selected = chosen == loadout
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) Color(0x337CC6FE) else FrostedCardSurface
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { chosen = loadout }
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = (if (selected) "✓ " else "") + loadout.titleRu,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = SnowCrispText
                        )
                        Text(text = loadout.descriptionRu, color = MistMutedText, fontSize = 12.sp)
                        Text(
                            text = "Стартовый набор: " + loadout.items.entries
                                .joinToString(", ") { "${it.key.titleRu} ×${it.value}" },
                            color = IceCyanPrimary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        PrimaryGameButton(
            text = "Начать забег",
            icon = rememberAssetIcon("glyphs/glyph_snowfall.png"),
            onClick = { viewModel.confirmLoadoutAndStart(chosen) },
            modifier = Modifier.fillMaxWidth().testTag("btn_start_run")
        )
    }
}

/**
 * Горизонтальный двухпанельный экран Базы, Верстака, Рюкзака, Карты экспедиции и Коллекции находок.
 */
@Composable
fun BaseInventoryScreen(
    state: WinterForestUiState,
    viewModel: WinterForestViewModel
) {
    val base = state.baseProgress
    val run = state.activeRun
    val context = LocalContext.current
    val activity = context as? Activity
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        "Лагерь и постройки",
        "Верстак (7 рецептов)",
        "Рюкзак и склад",
        "Карта 5 локаций",
        "Реликвии и отряд",
        "Навыки и задания"
    )

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB3060C14))
            .displayCutoutPadding()
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Левая навигационная колонка разделов базы
        Column(
            modifier = Modifier
                .width(215.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(FrostedCardSurface)
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Лагерь выжившего", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = SnowCrispText)
                tabs.forEachIndexed { idx, title ->
                    val active = selectedTab == idx
                    Button(
                        onClick = { selectedTab = idx },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (active) IceCyanPrimary else Color(0x24FFFFFF)
                        ),
                        modifier = Modifier.fillMaxWidth().height(38.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = title,
                            color = if (active) Color(0xFF071521) else SnowCrispText,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 11.5.sp
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { viewModel.navigateBack() },
                modifier = Modifier.fillMaxWidth().height(40.dp)
            ) {
                Text("← Вернуться")
            }
        }

        // Правая рабочая область выбранного раздела
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (run != null) {
                Text(
                    text = run.lastStatusMessage,
                    color = HearthAmberAccent,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(FrostedCardSurface)
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }

            when (selectedTab) {
                0 -> BaseStructuresTab(run, base, state, activity, viewModel)
                1 -> WorkbenchTab(run, base, viewModel)
                2 -> InventoryStorageTab(run, base, viewModel)
                3 -> ExpeditionMapTab(run, base, viewModel)
                4 -> RelicsAndLoadoutTab(run, base, viewModel)
                else -> SkillsAndDailyTab(base, viewModel)
            }
        }
    }
}

@Composable
private fun BaseStructuresTab(
    run: com.ayaks.winterforest.domain.model.GameRunState?,
    base: BaseProgress,
    state: WinterForestUiState,
    activity: Activity?,
    viewModel: WinterForestViewModel
) {
    if (run != null) {
        Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Очаг, отдых и склад лагеря", fontWeight = FontWeight.Bold, color = SnowCrispText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.onStartCampfireCooking() }, modifier = Modifier.weight(1f)) {
                        Text("Готовить паёк", fontSize = 12.sp)
                    }
                    Button(onClick = { viewModel.onRestInShelter() }, modifier = Modifier.weight(1f)) {
                        Text("Отдохнуть у очага", fontSize = 12.sp)
                    }
                    Button(onClick = { viewModel.onDepositAllToBase() }, modifier = Modifier.weight(1.2f)) {
                        Text("Сложить всё на склад", fontSize = 12.sp)
                    }
                }
                val activeTimer = run.activeSkippableTimer()
                if (activeTimer != null && state.isSkipTimerAdButtonVisible) {
                    Button(
                        onClick = { viewModel.onRequestRewardedSkipTimer(activity) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Пропустить ожидание (реклама) • осталось ${run.remainingRewardedSkipsToday}/3")
                    }
                }
            }
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Капитальные постройки (сохраняются навсегда)", fontWeight = FontWeight.Bold, color = SnowCrispText)
            for (upgrade in BaseUpgradeType.entries) {
                val lvl = base.levelOf(upgrade)
                val cost = base.upgradeCost(upgrade)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${upgrade.titleRu} — Уровень $lvl/${BaseProgress.MAX_UPGRADE_LEVEL}",
                            color = IceCyanPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(upgrade.effectDescriptionRu, color = MistMutedText, fontSize = 11.5.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    if (cost != null) {
                        val costStr = cost.entries.joinToString(", ") { "${it.key.titleRu}: ${it.value}" }
                        Button(onClick = { viewModel.onUpgradeBase(upgrade) }) {
                            Text(
                                "${if (lvl == 0) "Построить" else "Улучшить"} ($costStr)",
                                fontSize = 11.sp
                            )
                        }
                    } else {
                        Text("Максимум", color = SpruceEmerald, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                HorizontalDivider(color = Color(0x22FFFFFF))
            }

            if (SpecialLootId.THERMOS in base.persistentSpecialLoot && !base.thermosInstalledOnBase) {
                Button(onClick = { viewModel.onInstallThermos() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Установить «Термос» на верстаке (-15% потери тепла навсегда)")
                }
            }
            if (SpecialLootId.WARM_COAT in base.persistentSpecialLoot) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Экипирована «Тёплая шуба» (-25% потери тепла)", color = SnowCrispText, fontSize = 13.sp)
                    Switch(
                        checked = base.warmCoatEquipped,
                        onCheckedChange = { viewModel.onToggleWarmCoat(it) }
                    )
                }
            }
        }
    }

    // Плавка железа в Каменной печи: руда + уголь → слитки (потом крафт железных инструментов)
    Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Каменная печь — плавка металла", fontWeight = FontWeight.Bold, color = SnowCrispText)
            if (base.furnaceLevel < 1) {
                Text(
                    "Постройте «Каменную печь» выше, чтобы плавить железную руду в слитки для железных крафтов.",
                    color = MistMutedText,
                    fontSize = 11.5.sp
                )
            } else {
                val smeltCost = BaseProgress.smeltCost(base.furnaceLevel)
                val smeltOut = BaseProgress.smeltYield(base.furnaceLevel)
                val costStr = smeltCost.entries.joinToString(" + ") { "${it.key.titleRu} ×${it.value}" }
                Text(
                    "Печь ур. ${base.furnaceLevel}: $costStr → Железо ×$smeltOut за плавку" +
                        if (base.furnaceLevel >= 4) " (двойной выход!)" else "",
                    color = IceCyanPrimary,
                    fontSize = 11.5.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.onSmeltIron(1) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Выплавить слиток", fontSize = 11.sp)
                    }
                    Button(
                        onClick = { viewModel.onSmeltIron(5) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Выплавить ×5", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/** Иконка рецепта в assets/icons (как в мобильных крафт-играх — сразу видно результат). */
private fun recipeIconPath(recipe: WorkbenchRecipe): String = when (recipe) {
    WorkbenchRecipe.CRAFT_RESIN_TORCH -> "icons/icon_recipe_torch.png"
    WorkbenchRecipe.CRAFT_BERRY_TEA -> "icons/icon_recipe_berry_tea.png"
    WorkbenchRecipe.CRAFT_ROAST_FEAST -> "icons/icon_recipe_roast_feast.png"
    WorkbenchRecipe.CRAFT_HUNTING_SPEAR -> "icons/icon_recipe_hunting_spear.png"
    WorkbenchRecipe.CRAFT_WARM_COMPRESS -> "icons/icon_recipe_warm_compress.png"
    WorkbenchRecipe.CRAFT_FUR_BOOTS -> "icons/icon_recipe_fur_boots.png"
    WorkbenchRecipe.CRAFT_MINERS_HEATER -> "icons/icon_recipe_miners_heater.png"
    WorkbenchRecipe.CRAFT_IRON_AXE -> "icons/icon_axe.png"
    WorkbenchRecipe.CRAFT_IRON_PICKAXE -> "icons/icon_pickaxe.png"
    WorkbenchRecipe.CRAFT_IRON_SPEARHEAD -> "icons/icon_spear.png"
    WorkbenchRecipe.CRAFT_PRIMITIVE_AXE -> "icons/icon_primitive_axe.png"
    WorkbenchRecipe.CRAFT_PRIMITIVE_PICKAXE -> "icons/icon_primitive_pickaxe.png"
}

@Composable
private fun WorkbenchTab(
    run: com.ayaks.winterforest.domain.model.GameRunState?,
    base: BaseProgress,
    viewModel: WinterForestViewModel
) {
    var activeCategory by remember { mutableStateOf<com.ayaks.winterforest.domain.model.RecipeCategory?>(null) }
    Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Верстак и кухня у костра • уровень ${base.workbenchLevel}/5",
                fontWeight = FontWeight.Bold,
                color = SnowCrispText
            )

            // Фильтр категорий — быстрый доступ к нужному типу рецептов
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val categories = listOf<com.ayaks.winterforest.domain.model.RecipeCategory?>(null) +
                    com.ayaks.winterforest.domain.model.RecipeCategory.entries
                for (cat in categories) {
                    val active = activeCategory == cat
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active) IceCyanPrimary else Color(0x22FFFFFF))
                            .clickable { activeCategory = cat }
                            .padding(horizontal = 11.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = cat?.titleRu ?: "Все",
                            color = if (active) Color(0xFF071521) else SnowCrispText,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            if (run == null) {
                Text("Начните экспедицию, чтобы создавать снаряжение и горячие блюда.", color = MistMutedText)
            } else {
                val visible = com.ayaks.winterforest.domain.model.WorkbenchRecipe.entries
                    .filter { activeCategory == null || it.category == activeCategory }
                for (recipe in visible) {
                    val missing = recipe.cost.filter { (res, need) -> run.inventory.countOf(res) < need }
                    val furnaceReady = !recipe.requiresFurnace || base.furnaceLevel >= 1
                    val canCraft = missing.isEmpty() && base.workbenchLevel >= recipe.minWorkbenchLevel && furnaceReady
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x14FFFFFF))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val recipeIcon = rememberAssetIcon(recipeIconPath(recipe))
                            if (recipeIcon != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = recipeIcon,
                                    contentDescription = recipe.titleRu,
                                    modifier = Modifier.size(32.dp),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(recipe.titleRu, color = HearthAmberAccent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(recipe.descriptionRu, color = MistMutedText, fontSize = 11.sp)
                            }
                            Button(
                                onClick = { viewModel.onCraftRecipe(recipe) },
                                enabled = canCraft,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = HearthAmberAccent,
                                    disabledContainerColor = Color(0x2AFFFFFF)
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(
                                    text = if (canCraft) "Создать" else "Недоступно",
                                    color = if (canCraft) Color(0xFF1A1006) else MistMutedText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        // Понятная строка ингредиентов: сколько есть и сколько нужно
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((res, need) in recipe.cost) {
                                val have = run.inventory.countOf(res)
                                val enough = have >= need
                                Text(
                                    text = "${res.titleRu} $have/$need",
                                    color = if (enough) SpruceEmerald else CrimsonDanger,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        if (base.workbenchLevel < recipe.minWorkbenchLevel) {
                            Text(
                                "Требуется верстак ${recipe.minWorkbenchLevel} ур.",
                                color = CrimsonDanger,
                                fontSize = 10.sp
                            )
                        } else if (!furnaceReady) {
                            Text(
                                "Требуется Каменная печь и слитки железа",
                                color = CrimsonDanger,
                                fontSize = 10.sp
                            )
                        } else if (missing.isNotEmpty()) {
                            Text(
                                "Не хватает: " + missing.entries.joinToString(", ") { "${it.key.titleRu} ×${it.value - run.inventory.countOf(it.key)}" },
                                color = CrimsonDanger,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InventoryStorageTab(
    run: com.ayaks.winterforest.domain.model.GameRunState?,
    base: BaseProgress,
    viewModel: WinterForestViewModel
) {
    val inv = run?.inventory ?: Inventory(specialItems = base.persistentSpecialLoot.associateWith { 1 })
    var selectedType by remember { mutableStateOf<ResourceType?>(null) }

    // §7.3: настоящий drag&drop. Состояние перетаскивания живёт здесь: что
    // тащим (ячейка сетки или слот хотбара), где палец, и прямоугольники целей
    // в координатах корня вкладки (снимаются onGloballyPositioned).
    val view = androidx.compose.ui.platform.LocalView.current
    var dragGrid by remember { mutableStateOf<Int?>(null) }
    var dragHotbar by remember { mutableStateOf<Int?>(null) }
    var dragType by remember { mutableStateOf<ResourceType?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    val gridRects = remember { mutableStateMapOf<Int, Rect>() }
    val hotbarRects = remember { mutableStateMapOf<Int, Rect>() }
    val dragging = dragGrid != null || dragHotbar != null

    fun dropTargetGrid(): Int? =
        gridRects.entries.firstOrNull { it.value.contains(dragPos) }?.key

    fun dropTargetHotbar(): Int? =
        hotbarRects.entries.firstOrNull { it.value.contains(dragPos) }?.key

    fun endDrag() {
        dragGrid = null
        dragHotbar = null
        dragType = null
    }

    fun finishDrop() {
        val g = dragGrid
        val h = dragHotbar
        val targetHot = dropTargetHotbar()
        val targetGrid = dropTargetGrid()
        when {
            g != null && targetHot != null -> viewModel.onDropInventoryOnHotbar(g, targetHot)
            g != null && targetGrid != null -> viewModel.onSwapInventoryCells(g, targetGrid)
            h != null && targetGrid != null -> viewModel.onDropHotbarToInventory(h)
            h != null && targetHot != null && targetHot != h -> viewModel.onSwapHotbarSlots(h, targetHot)
        }
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        endDrag()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Рюкзак • ${inv.occupiedSlots}/${Inventory.MAX_SLOTS}",
                        fontWeight = FontWeight.Bold,
                        color = SnowCrispText
                    )

                    // Сетка 5×4 БЕЗ подписей под ячейками: иконка + счётчик.
                    // Ячейки сортируются перетаскиванием, пустые «дырки» держатся.
                    for (row in 0 until 4) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (col in 0 until 5) {
                                val idx = row * 5 + col
                                val slot = inv.gridSlot(idx)
                                val isTarget = dragging && dropTargetGrid() == idx &&
                                    (dragGrid != idx)
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .onGloballyPositioned { gridRects[idx] = it.boundsInRoot() }
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (slot != null) Color(0x22FFFFFF) else Color(0x0DFFFFFF))
                                        .border(
                                            1.4.dp,
                                            when {
                                                isTarget -> HearthAmberAccent
                                                slot != null && slot.type == selectedType -> IceCyanPrimary
                                                slot != null -> Color(0x33FFFFFF)
                                                else -> Color(0x1AFFFFFF)
                                            },
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable { selectedType = slot?.type }
                                        .pointerInput(idx, slot?.type, slot?.count) {
                                            if (slot == null) return@pointerInput
                                            detectDragGestures(
                                                onDragStart = {
                                                    dragGrid = idx
                                                    dragHotbar = null
                                                    dragType = slot.type
                                                    dragPos = gridRects[idx]?.topLeft ?: Offset.Zero
                                                    view.performHapticFeedback(
                                                        android.view.HapticFeedbackConstants.LONG_PRESS
                                                    )
                                                },
                                                onDragEnd = { finishDrop() },
                                                onDragCancel = { endDrag() }
                                            ) { change, _ ->
                                                change.consume()
                                                val origin = gridRects[idx]?.topLeft ?: Offset.Zero
                                                dragPos = origin + change.position
                                            }
                                        }
                                ) {
                                    if (slot != null) {
                                        com.ayaks.winterforest.presentation.ui.components.ResourceTypeGlyph(
                                            type = slot.type,
                                            modifier = Modifier.size(34.dp).align(Alignment.Center)
                                        )
                                        if (slot.count > 1) {
                                            Text(
                                                text = "${slot.count}",
                                                color = SnowCrispText,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .padding(3.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Хотбар стоит ВПЛОТНУЮ к сетке: без заголовков, пояснений и
                    // разделителей между ними (§7.3).
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (slotIdx in 0..5) {
                            val current = run?.hotbarAt(slotIdx)
                            val isTarget = dragging && dropTargetHotbar() == slotIdx
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .onGloballyPositioned { hotbarRects[slotIdx] = it.boundsInRoot() }
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0x18FFFFFF))
                                    .border(
                                        1.4.dp,
                                        when {
                                            isTarget -> HearthAmberAccent
                                            current != null -> Color(0xFF7CC6FE)
                                            else -> Color(0x33FFFFFF)
                                        },
                                        RoundedCornerShape(10.dp)
                                    )
                                    .pointerInput(slotIdx, current?.titleRu) {
                                        if (current == null) return@pointerInput
                                        detectDragGestures(
                                            onDragStart = {
                                                dragHotbar = slotIdx
                                                dragGrid = null
                                                dragType = current.resourceType
                                                dragPos = hotbarRects[slotIdx]?.topLeft ?: Offset.Zero
                                                view.performHapticFeedback(
                                                    android.view.HapticFeedbackConstants.LONG_PRESS
                                                )
                                            },
                                            onDragEnd = { finishDrop() },
                                            onDragCancel = { endDrag() }
                                        ) { change, _ ->
                                            change.consume()
                                            val origin = hotbarRects[slotIdx]?.topLeft ?: Offset.Zero
                                            dragPos = origin + change.position
                                        }
                                    }
                            ) {
                                current?.let { entry ->
                                    if (entry.resourceType != null) {
                                        com.ayaks.winterforest.presentation.ui.components.ResourceTypeGlyph(
                                            type = entry.resourceType!!,
                                            modifier = Modifier.size(30.dp).align(Alignment.Center)
                                        )
                                    } else {
                                        Text(
                                            text = "🔧",
                                            fontSize = 20.sp,
                                            modifier = Modifier.align(Alignment.Center)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Панель выбранного предмета — ПОД хотбаром, а не между ним и сеткой
                    val selectedSlot = inv.slots.firstOrNull { it.type == selectedType }
                    if (selectedSlot != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x18FFFFFF))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            com.ayaks.winterforest.presentation.ui.components.ResourceTypeGlyph(type = selectedSlot.type)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${selectedSlot.type.titleRu} • ${selectedSlot.count}/${Inventory.MAX_STACK_SIZE}",
                                    color = SnowCrispText,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                            }
                            if (run != null && (selectedSlot.type.isEdible || selectedSlot.type.isFuel)) {
                                Button(
                                    onClick = { viewModel.onUseSpecificResource(selectedSlot.type) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("Использовать", fontSize = 11.sp)
                                }
                            }
                            if (run != null) {
                                OutlinedButton(
                                    onClick = { viewModel.onDropResourceToGround(selectedSlot.type) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("Выбросить", fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    if (base.storageResources.isNotEmpty()) {
                        val storageSummary = base.storageResources.entries
                            .joinToString(" • ") { "${it.key.titleRu}: ${it.value}" }
                        Text("Склад лагеря: $storageSummary", color = IceCyanPrimary, fontSize = 11.5.sp)
                    }

                    Text("Спецпредметы", fontWeight = FontWeight.Bold, color = HearthAmberAccent)
                    if (inv.specialItems.isEmpty()) {
                        Text("Специальные предметы ещё не найдены.", color = MistMutedText, fontSize = 12.sp)
                    } else {
                        for ((loot, qty) in inv.specialItems) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("${loot.titleRu} ×$qty (${loot.sourceLocationNameRu})", color = SnowCrispText, fontWeight = FontWeight.SemiBold)
                                    Text(loot.descriptionRu, color = MistMutedText, fontSize = 11.5.sp)
                                }
                                if (run != null && loot == SpecialLootId.RESIN_TORCH) {
                                    Button(onClick = { viewModel.onIgniteResinTorch() }) {
                                        Text("Зажечь", fontSize = 11.sp)
                                    }
                                }
                                if (run != null && !loot.persistsAcrossRuns) {
                                    OutlinedButton(onClick = { viewModel.onDropSpecialToGround(loot) }) {
                                        Text("Выбросить", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // «Призрак» перетаскиваемого предмета под пальцем + подсветка цели
        if (dragging) {
            val ghostType = dragType
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // перехватываем жест выше целей, пока идёт перетаскивание
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (dragPos.x - 28.dp.toPx()).toInt(),
                                (dragPos.y - 28.dp.toPx()).toInt()
                            )
                        }
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xAA091520))
                        .border(1.6.dp, HearthAmberAccent, RoundedCornerShape(10.dp))
                ) {
                    if (ghostType != null) {
                        com.ayaks.winterforest.presentation.ui.components.ResourceTypeGlyph(
                            type = ghostType,
                            modifier = Modifier.size(32.dp).align(Alignment.Center)
                        )
                    } else {
                        Text("🔧", fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}
@Composable
private fun ExpeditionMapTab(
    run: com.ayaks.winterforest.domain.model.GameRunState?,
    base: BaseProgress,
    viewModel: WinterForestViewModel
) {
    val hasMap = SpecialLootId.OLD_MAP in base.persistentSpecialLoot ||
        (run?.inventory?.hasSpecial(SpecialLootId.OLD_MAP) == true)
    val evalRun = run ?: SurvivalEngine.createNewRun(base)

    Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (hasMap) "Карта северных земель (открыта «Старой картой»)" else "Разведанные тропы из лагеря",
                fontWeight = FontWeight.Bold,
                color = SnowCrispText
            )
            Text(
                "Все районы соединены в один большой мир — между ними можно дойти пешком. " +
                    "Кнопки ниже — быстрый переход к изученному району.",
                color = MistMutedText,
                fontSize = 11.5.sp
            )
            for (loc in LocationId.entries) {
                val status = SurvivalEngine.evaluateLocationUnlock(loc, evalRun, base)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${loc.titleRu} • реликвия: «${loc.uniqueLoot.titleRu}»",
                            color = if (status.isUnlocked) SpruceEmerald else MistMutedText,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (hasMap || status.isUnlocked || loc == LocationId.BASE_CLEARING) {
                            Text(loc.subtitleRu, color = MistMutedText, fontSize = 11.5.sp)
                            Text("Условие: ${status.conditionSummaryRu}", color = IceCyanPrimary, fontSize = 11.sp)
                        } else {
                            Text(
                                "Найдите «Старую карту» на Базовой поляне для полной разведки маршрута.",
                                color = MistMutedText,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = { viewModel.onTravelToLocation(loc) }) {
                        Text(if (status.isUnlocked) "Быстрый переход" else "Проверить")
                    }
                }
                HorizontalDivider(color = Color(0x22FFFFFF))
            }
        }
    }
}

@Composable
private fun RelicsAndLoadoutTab(
    run: com.ayaks.winterforest.domain.model.GameRunState?,
    base: BaseProgress,
    viewModel: WinterForestViewModel
) {
    // §7.4: выбор класса убран из базы — он происходит перед каждым забегом
    // на экране LOADOUT_SELECT («Новая экспедиция» в главном меню).
    Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Класс экспедиции", fontWeight = FontWeight.Bold, color = SnowCrispText)
            Text(
                text = "Текущий класс: «${base.selectedLoadout.titleRu}». Класс выбирается перед стартом " +
                    "нового забега в главном меню («Новая экспедиция»).",
                color = MistMutedText,
                fontSize = 12.sp
            )
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
        val foundSet = base.persistentSpecialLoot + (run?.inventory?.specialItems?.keys.orEmpty())
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Коллекция реликвий севера: ${foundSet.size} из ${SpecialLootId.entries.size}",
                fontWeight = FontWeight.Bold,
                color = SnowCrispText
            )
            for (item in SpecialLootId.entries) {
                val found = item in foundSet
                Text(
                    text = (if (found) "★ " else "☆ ") +
                        "${item.titleRu} (${item.sourceLocationNameRu}) — ${item.descriptionRu}",
                    color = if (found) SnowCrispText else MistMutedText,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun ResultsScreen(
    state: WinterForestUiState,
    viewModel: WinterForestViewModel
) {
    val run = state.activeRun
    val context = LocalContext.current
    val activity = context as? Activity

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xC4060C14))
            .displayCutoutPadding()
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = FrostedCardSurface),
            modifier = Modifier.weight(1.15f)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Экспедиция завершена", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CrimsonDanger)
                Text(
                    "Мороз остановил путника, но постройки лагеря и найденные реликвии сохранены.",
                    color = MistMutedText,
                    fontSize = 12.5.sp
                )
                HorizontalDivider(color = Color(0x28FFFFFF))
                Text("Дней выжито: ${run?.daysSurvived ?: 0} (рекорд: ${state.baseProgress.bestDaysSurvived} дн.)", color = SnowCrispText)
                Text("Добыто ресурсов: ${run?.totalResourcesGathered ?: 0} • Охотничьих трофеев: ${state.baseProgress.totalWildlifeHunted}", color = SnowCrispText)
                Text("Уровень выжившего: ${run?.level ?: 1} • Суммарный уровень базы: ${state.baseProgress.totalBaseLevel}", color = IceCyanPrimary)
                val visitedNames = run?.visitedLocations?.joinToString(", ") { it.titleRu }.orEmpty()
                Text("Открытые земли: $visitedNames", color = SnowCrispText, fontSize = 12.sp)
            }
        }

        Column(
            modifier = Modifier.weight(0.85f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.isReviveAdButtonVisible) {
                Button(
                    onClick = { viewModel.onRequestRewardedRevive(activity) },
                    colors = ButtonDefaults.buttonColors(containerColor = SpruceEmerald),
                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("btn_rewarded_revive")
                ) {
                    Text(
                        text = "Возродиться у костра (реклама) — 50% сил",
                        color = Color(0xFF061B10),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            Button(
                onClick = { viewModel.requestNewExpedition() },
                colors = ButtonDefaults.buttonColors(containerColor = HearthAmberAccent),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Начать новую экспедицию", color = Color(0xFF1A1006), fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = { viewModel.navigateTo(AppDestination.MAIN_MENU) },
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) {
                Text("Главное меню")
            }
        }
    }
}

@Composable
fun SettingsScreen(
    state: WinterForestUiState,
    viewModel: WinterForestViewModel
) {
    if (state.showResetConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelResetProgressDialog() },
            title = { Text("Сбросить весь прогресс?") },
            text = {
                Text("Все улучшения базы, рекорды, активное сохранение и найденные предметы будут удалены.")
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmResetAllProgress() },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonDanger)
                ) {
                    Text("Да, сбросить")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelResetProgressDialog() }) {
                    Text("Отмена")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xC4060C14))
            .displayCutoutPadding()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Настройки графики и управления", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SnowCrispText)
            OutlinedButton(onClick = { viewModel.navigateBack() }) {
                Text("← Назад")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Левая карточка: Графика и производительность VBO
            Card(
                colors = CardDefaults.cardColors(containerColor = FrostedCardSurface),
                modifier = Modifier.weight(1f)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Качество 3D-графики", fontWeight = FontWeight.Bold, color = SnowCrispText)
                    Text(state.settings.graphicsQuality.subtitleRu, color = IceCyanPrimary, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        for (q in GraphicsQuality.entries) {
                            val active = state.settings.graphicsQuality == q
                            Button(
                                onClick = { viewModel.onGraphicsQualityChanged(q) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (active) IceCyanPrimary else Color(0x28FFFFFF)
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1f).height(36.dp)
                            ) {
                                Text(
                                    text = q.titleRu,
                                    color = if (active) Color(0xFF071521) else SnowCrispText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Показывать счётчик FPS на экране", color = SnowCrispText, fontSize = 13.sp)
                        Switch(
                            checked = state.settings.showFpsCounter,
                            onCheckedChange = { viewModel.onShowFpsChanged(it) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Авто-бег при полном наклоне джойстика", color = SnowCrispText, fontSize = 13.sp)
                        Switch(
                            checked = state.settings.autoSprintEnabled,
                            onCheckedChange = { viewModel.onAutoSprintChanged(it) }
                        )
                    }
                }
            }

            // Правая карточка: Звук, чувствительность, РСЯ и сброс
            Card(
                colors = CardDefaults.cardColors(containerColor = FrostedCardSurface),
                modifier = Modifier.weight(1f)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Громкость звука: ${(state.settings.soundVolume * 100).roundToInt()}%", color = SnowCrispText, fontSize = 13.sp)
                    Slider(
                        value = state.settings.soundVolume,
                        onValueChange = { viewModel.onSoundVolumeChanged(it) },
                        valueRange = 0f..1f
                    )

                    Text("Чувствительность камеры: ${"%.2f".format(state.settings.lookSensitivity)}x", color = SnowCrispText, fontSize = 13.sp)
                    Slider(
                        value = state.settings.lookSensitivity,
                        onValueChange = { viewModel.onLookSensitivityChanged(it) },
                        valueRange = 0.3f..2.5f
                    )

                    // Настройки персонализации рекламы РСЯ в меню НЕТ: согласие
                    // запрашивается один раз юридически обязательным диалогом
                    // первого запуска (MainMenuScreen) и меняется только там.

                    Button(
                        onClick = { viewModel.requestResetProgressDialog() },
                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonDanger),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Сбросить весь прогресс")
                    }
                }
            }
        }
    }
}

@Composable
fun AboutScreen(viewModel: WinterForestViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xC4060C14))
            .displayCutoutPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Об игре Winter Forest", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SnowCrispText)
            OutlinedButton(onClick = { viewModel.navigateBack() }) {
                Text("← Назад")
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Winter Forest — Горизонтальное 3D-издание", style = MaterialTheme.typography.titleLarge, color = SnowCrispText)
                Text(
                    "Атмосферное 3D-приключение на выживание от первого лица с собственным аппаратным OpenGL ES VBO-движком. " +
                        "Исследуйте 5 биомов севера, разводите походные костры, охотьтесь на зверя, отпугивайте ночных волков пламенем смоляного факела, " +
                        "развивайте сруб, очаг и верстак и соберите все 5 реликвий арктической тайги.",
                    color = MistMutedText
                )
                Text("Разработчик: Ayaks • Контакт: kirillpetrov08@gmail.com • Версия: 2.0.0", color = IceCyanPrimary)
            }
        }
    }
}


/** Вкладка «Навыки и задания»: дерево перков и ежедневные испытания. */
@Composable
private fun SkillsAndDailyTab(
    base: BaseProgress,
    viewModel: WinterForestViewModel
) {
    val am = com.ayaks.winterforest.domain.rules.AdvancedMechanics
    // ВАЖНО: скролл здесь запрещён. Правая область BaseInventoryScreen уже
    // прокручивается, а вложенный verticalScroll получает бесконечную высоту
    // и Compose падает с «infinity maximum height constraints» при открытии вкладки.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    "Навыки выживальщика • очков: ${am.availableSkillPoints(base)}",
                    fontWeight = FontWeight.Bold,
                    color = SnowCrispText
                )
                for (perk in com.ayaks.winterforest.domain.rules.PerkId.entries) {
                    val owned = perk in base.perks
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (owned) Color(0x1E4CC67E) else Color(0x14FFFFFF))
                            .padding(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        com.ayaks.winterforest.presentation.ui.components.rememberAssetIcon(
                            when (perk) {
                                com.ayaks.winterforest.domain.rules.PerkId.WARM_BLOODED -> "glyphs/glyph_warmth.png"
                                com.ayaks.winterforest.domain.rules.PerkId.GATHERER -> "glyphs/glyph_backpack.png"
                                com.ayaks.winterforest.domain.rules.PerkId.MARATHONER -> "glyphs/glyph_sprint.png"
                                com.ayaks.winterforest.domain.rules.PerkId.HUNTER -> "glyphs/glyph_warning.png"
                                com.ayaks.winterforest.domain.rules.PerkId.SURVIVOR -> "glyphs/glyph_health.png"
                                com.ayaks.winterforest.domain.rules.PerkId.FISHERMAN -> "glyphs/glyph_hunger.png"
                            }
                        )?.let { bmp ->
                            androidx.compose.foundation.Image(
                                bitmap = bmp,
                                contentDescription = perk.titleRu,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(perk.titleRu, color = HearthAmberAccent, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text(perk.descriptionRu, color = MistMutedText, fontSize = 10.5.sp)
                        }
                        if (!owned) {
                            Button(
                                onClick = { viewModel.onUnlockPerk(perk) },
                                enabled = am.canUnlockPerk(base, perk),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("Открыть", fontSize = 10.5.sp)
                            }
                        } else {
                            Text("✓", color = SpruceEmerald, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Ежедневные испытания", fontWeight = FontWeight.Bold, color = SnowCrispText)
                if (base.dailyDayIndex < 0) {
                    Text("Испытания появятся в первые сутки экспедиции.", color = MistMutedText, fontSize = 11.sp)
                } else {
                    for (ch in am.currentDailySet(base.dailyDayIndex)) {
                        val progress = base.dailyProgress[ch.name] ?: 0
                        val done = am.isChallengeComplete(base, ch)
                        val claimed = ch.name in base.dailyClaimed
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color(0x14FFFFFF))
                                .padding(9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(ch.titleRu, color = SnowCrispText, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp)
                                Text(
                                    "${progress.coerceAtMost(ch.target)}/${ch.target} • награда: ${ch.rewardXp} опыта",
                                    color = if (done) SpruceEmerald else MistMutedText,
                                    fontSize = 10.5.sp
                                )
                            }
                            when {
                                claimed -> Text("✓", color = SpruceEmerald, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                done -> Button(
                                    onClick = { viewModel.onClaimDaily(ch) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                                    modifier = Modifier.height(30.dp)
                                ) { Text("Забрать", fontSize = 10.5.sp) }
                                else -> Text("в процессе", color = MistMutedText, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
