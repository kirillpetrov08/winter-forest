package com.ayaks.winterforest.presentation.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
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
import com.ayaks.winterforest.domain.model.BaseUpgradeType
import com.ayaks.winterforest.domain.model.Inventory
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.SpecialLootId
import com.ayaks.winterforest.domain.model.StarterLoadout
import com.ayaks.winterforest.domain.model.WorkbenchRecipe
import com.ayaks.winterforest.domain.rules.SurvivalEngine
import com.ayaks.winterforest.presentation.gl.ForestGlSurfaceView
import com.ayaks.winterforest.presentation.gl.RenderSceneSnapshot
import com.ayaks.winterforest.presentation.navigation.AppDestination
import com.ayaks.winterforest.presentation.ui.components.HealthBarColor
import com.ayaks.winterforest.presentation.ui.components.HungerBarColor
import com.ayaks.winterforest.presentation.ui.components.MainMenuAdaptiveBanner
import com.ayaks.winterforest.presentation.ui.components.MiniCompassBadge
import com.ayaks.winterforest.presentation.ui.components.SurvivalStatBar
import com.ayaks.winterforest.presentation.ui.components.VirtualJoystick
import com.ayaks.winterforest.presentation.ui.components.WarmthBarColor
import com.ayaks.winterforest.presentation.ui.components.XpBarColor
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF102436), Color(0xFF0A1622), DeepPineNight)
                )
            )
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "WINTER FOREST",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = SnowCrispText,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Выживание в заснеженном северном лесу",
                    fontSize = 14.sp,
                    color = IceCyanPrimary
                )
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = FrostedCardSurface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatPill("Рекорд", "${state.baseProgress.bestDaysSurvived} дн.")
                        StatPill("Уровень базы", "${state.baseProgress.totalBaseLevel}")
                        StatPill(
                            "Коллекция",
                            "${state.baseProgress.persistentSpecialLoot.size}/${SpecialLootId.entries.size}"
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.hasActiveSave) {
                    Button(
                        onClick = { viewModel.continueSavedGame() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("btn_continue")
                    ) {
                        val day = state.activeRun?.dayNumberDisplay ?: 1
                        val loc = state.activeRun?.currentLocation?.titleRu.orEmpty()
                        Text("Продолжить (День $day • $loc)", fontSize = 15.sp)
                    }
                }

                Button(
                    onClick = { viewModel.startNewGame() },
                    colors = ButtonDefaults.buttonColors(containerColor = HearthAmberAccent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("btn_new_game")
                ) {
                    Text("Новая игра", color = Color(0xFF1A1006), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                OutlinedButton(
                    onClick = { viewModel.navigateTo(AppDestination.BASE_INVENTORY) },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("База, экипировка и коллекция")
                }

                OutlinedButton(
                    onClick = { viewModel.navigateTo(AppDestination.SETTINGS) },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Настройки")
                }

                OutlinedButton(
                    onClick = { viewModel.navigateTo(AppDestination.ABOUT) },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Об игре")
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
        Text(text = value, color = SnowCrispText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(text = title, color = MistMutedText, fontSize = 11.sp)
    }
}

@Composable
fun GameScreen(
    state: WinterForestUiState,
    viewModel: WinterForestViewModel
) {
    val run = state.activeRun ?: return
    val context = LocalContext.current
    val activity = context as? Activity

    Box(modifier = Modifier.fillMaxSize().testTag("screen_game_3d")) {
        // 1. Полноэкранная 3D-сцена OpenGL ES 2.0/3.0
        AndroidView(
            factory = { ctx ->
                ForestGlSurfaceView(ctx).apply {
                    forestRenderer.snapshot = RenderSceneSnapshot(run, state.baseProgress)
                }
            },
            update = { glView ->
                glView.forestRenderer.snapshot = RenderSceneSnapshot(run, state.baseProgress)
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        viewModel.onSwipeLook(
                            deltaYawDeg = drag.x * 0.22f,
                            deltaPitchDeg = -drag.y * 0.18f
                        )
                    }
                }
        )

        // 2. Верхний HUD (локация, сутки, компас, здоровье, голод, тепло, опыт) — БЕЗ баннеров!
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xBA0B1722))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = run.currentLocation.titleRu,
                        color = SnowCrispText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    val phaseLabel = if (run.isNight) "Ночь" else "День"
                    val weatherLabel = if (run.isSnowing) "Снегопад" else "Ясно"
                    Text(
                        text = "День ${run.dayNumberDisplay} • $phaseLabel • $weatherLabel • Ур. ${run.level}",
                        color = IceCyanPrimary,
                        fontSize = 11.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { viewModel.navigateTo(AppDestination.BASE_INVENTORY) },
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text("База / Рюкзак (${run.inventory.occupiedSlots}/${Inventory.MAX_SLOTS})", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.navigateTo(AppDestination.MAIN_MENU) },
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text("Меню", fontSize = 11.sp)
                    }
                }
            }

            MiniCompassBadge(
                playerX = run.playerX,
                playerZ = run.playerZ,
                yawDeg = run.yawDeg,
                isAtBaseZone = run.currentLocation == LocationId.BASE_CLEARING
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xA60B1620))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SurvivalStatBar("Здоровье", run.health, run.maxHealth, HealthBarColor, Modifier.weight(1f))
                    SurvivalStatBar("Голод", run.hunger, 100f, HungerBarColor, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SurvivalStatBar("Тепло", run.warmth, 100f, WarmthBarColor, Modifier.weight(1f))
                    SurvivalStatBar(
                        "Опыт (Ур. ${run.level})",
                        run.xp.toFloat(),
                        run.xpForNextLevel.toFloat(),
                        XpBarColor,
                        Modifier.weight(1f)
                    )
                }
            }

            // Строка статуса и опциональный компактный блок пропуска активного таймера
            Text(
                text = run.lastStatusMessage,
                color = SnowCrispText,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xB8112231))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )

            val activeTimer = run.activeSkippableTimer()
            if (activeTimer != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xCC182F42))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${activeTimer.first.titleRu}: ${activeTimer.second.roundToInt()} с",
                        color = HearthAmberAccent,
                        fontSize = 12.sp
                    )
                    if (state.isSkipTimerAdButtonVisible) {
                        Button(
                            onClick = { viewModel.onRequestRewardedSkipTimer(activity) },
                            modifier = Modifier.height(34.dp).testTag("btn_rewarded_skip")
                        ) {
                            Text(
                                text = "Пропустить ожидание (реклама)",
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        // 3. Нижние органы управления: виртуальный джойстик + кнопки действий
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            VirtualJoystick(
                onJoystickChanged = { fwd, str -> viewModel.updateJoystick(fwd, str) }
            )

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val nearest = run.nearestInteractableNode()
                val gatherLabel = when {
                    nearest?.specialLootId != null -> "Собрать: ${nearest.specialLootId.titleRu}"
                    nearest?.resourceType != null -> "Собрать (${nearest.resourceType.titleRu})"
                    else -> "Собрать"
                }

                if (run.currentLocation != LocationId.BASE_CLEARING) {
                    OutlinedButton(onClick = { viewModel.onReturnToBase() }) {
                        Text("На базу", fontSize = 12.sp)
                    }
                }

                Button(
                    onClick = { viewModel.onActionGather() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (nearest != null) SpruceEmerald else FrostedCardSurface
                    )
                ) {
                    Text(gatherLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.onActionQuickUse() },
                        colors = ButtonDefaults.buttonColors(containerColor = HearthAmberAccent)
                    ) {
                        Text("Использовать", color = Color(0xFF1A1006), fontSize = 12.sp)
                    }
                    Button(onClick = { viewModel.onActionStrike() }) {
                        Text("Ударить", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun BaseInventoryScreen(
    state: WinterForestUiState,
    viewModel: WinterForestViewModel
) {
    val base = state.baseProgress
    val run = state.activeRun
    val context = LocalContext.current
    val activity = context as? Activity

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepPineNight)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("База и инвентарь", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SnowCrispText)
            OutlinedButton(onClick = { viewModel.navigateBack() }) {
                Text("Назад")
            }
        }

        if (run != null) {
            Text(run.lastStatusMessage, color = HearthAmberAccent, fontSize = 13.sp)
        }

        // Лагерные действия на базе (отдых, готовка и rewarded-пропуск ожидания)
        if (run != null) {
            Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Очаг и укрытие", fontWeight = FontWeight.Bold, color = SnowCrispText)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.onStartCampfireCooking() }, modifier = Modifier.weight(1f)) {
                            Text("Готовить паёк", fontSize = 12.sp)
                        }
                        Button(onClick = { viewModel.onRestInShelter() }, modifier = Modifier.weight(1f)) {
                            Text("Отдохнуть", fontSize = 12.sp)
                        }
                    }
                    Button(onClick = { viewModel.onDepositAllToBase() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Перенести ресурсы на склад базы")
                    }
                    val activeTimer = run.activeSkippableTimer()
                    if (activeTimer != null && state.isSkipTimerAdButtonVisible) {
                        Button(
                            onClick = { viewModel.onRequestRewardedSkipTimer(activity) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Пропустить ожидание (реклама) • осталось ${run.remainingRewardedSkipsToday}/3"
                            )
                        }
                    }
                }
            }
        }

        // Раздел 1: Улучшения базы
        Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Прокачка базы (сохраняется между забегами)", fontWeight = FontWeight.Bold, color = SnowCrispText)
                for (upgrade in BaseUpgradeType.entries) {
                    val lvl = base.levelOf(upgrade)
                    val cost = base.upgradeCost(upgrade)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${upgrade.titleRu} — Уровень $lvl/${com.ayaks.winterforest.domain.model.BaseProgress.MAX_UPGRADE_LEVEL}",
                            color = IceCyanPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(upgrade.effectDescriptionRu, color = MistMutedText, fontSize = 12.sp)
                        if (cost != null) {
                            val costStr = cost.entries.joinToString(", ") { "${it.key.titleRu}: ${it.value}" }
                            Button(onClick = { viewModel.onUpgradeBase(upgrade) }) {
                                Text("Улучшить ($costStr)", fontSize = 12.sp)
                            }
                        } else {
                            Text("Максимальный уровень достигнут", color = SpruceEmerald, fontSize = 12.sp)
                        }
                    }
                    HorizontalDivider(color = Color(0x22FFFFFF))
                }

                // Постройка Термоса и экипировка Тёплой шубы
                if (SpecialLootId.THERMOS in base.persistentSpecialLoot && !base.thermosInstalledOnBase) {
                    Button(onClick = { viewModel.onInstallThermos() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Установить «Термос» на базе (-15% потери тепла навсегда)")
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

        // Раздел 2: Верстак (рецепты крафта, включая Смоляной факел и рецепты Кирки)
        if (run != null) {
            Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Рецепты верстака", fontWeight = FontWeight.Bold, color = SnowCrispText)
                    for (recipe in WorkbenchRecipe.entries) {
                        val costStr = recipe.cost.entries.joinToString(", ") { "${it.key.titleRu}: ${it.value}" }
                        Column {
                            Text(recipe.titleRu, color = HearthAmberAccent, fontWeight = FontWeight.Medium)
                            Text(recipe.descriptionRu, color = MistMutedText, fontSize = 12.sp)
                            Button(onClick = { viewModel.onCraftRecipe(recipe) }) {
                                Text("Создать ($costStr)", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Раздел 3: Стартовая экипировка
        Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Выбор стартовой экипировки", fontWeight = FontWeight.Bold, color = SnowCrispText)
                for (loadout in StarterLoadout.entries) {
                    val selected = base.selectedLoadout == loadout
                    OutlinedButton(
                        onClick = { viewModel.onSelectLoadout(loadout) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            (if (selected) "✓ " else "") + "${loadout.titleRu} — ${loadout.descriptionRu}",
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // Раздел 4: Инвентарь и отдельная секция специальных предметов
        Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val inv = run?.inventory ?: Inventory(specialItems = base.persistentSpecialLoot.associateWith { 1 })
                Text(
                    "Рюкзак (${inv.occupiedSlots}/${Inventory.MAX_SLOTS} слотов) и Склад базы",
                    fontWeight = FontWeight.Bold,
                    color = SnowCrispText
                )
                if (inv.slots.isEmpty() && base.storageResources.isEmpty()) {
                    Text("Обычные ресурсы пока не собраны.", color = MistMutedText, fontSize = 13.sp)
                } else {
                    for (slot in inv.slots) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${slot.type.titleRu}: ${slot.count}/${Inventory.MAX_STACK_SIZE}", color = SnowCrispText)
                            if (run != null && (slot.type.isEdible || slot.type.isFuel)) {
                                TextButton(onClick = { viewModel.onUseSpecificResource(slot.type) }) {
                                    Text("Использовать")
                                }
                            }
                        }
                    }
                    if (base.storageResources.isNotEmpty()) {
                        HorizontalDivider(color = Color(0x22FFFFFF))
                        val storageSummary = base.storageResources.entries
                            .joinToString(" • ") { "${it.key.titleRu}: ${it.value}" }
                        Text("На складе базы: $storageSummary", color = IceCyanPrimary, fontSize = 12.sp)
                    }
                }

                HorizontalDivider(color = Color(0x33FFFFFF))
                Text("Специальные предметы в инвентаре", fontWeight = FontWeight.Bold, color = HearthAmberAccent)
                if (inv.specialItems.isEmpty()) {
                    Text("Специальные предметы ещё не найдены.", color = MistMutedText, fontSize = 12.sp)
                } else {
                    for ((loot, qty) in inv.specialItems) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${loot.titleRu} ×$qty (источник: ${loot.sourceLocationNameRu})", color = SnowCrispText, fontWeight = FontWeight.SemiBold)
                            Text(loot.descriptionRu, color = MistMutedText, fontSize = 12.sp)
                            if (run != null && loot == SpecialLootId.RESIN_TORCH) {
                                Button(onClick = { viewModel.onIgniteResinTorch() }) {
                                    Text("Зажечь Смоляной факел")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Раздел 5: Карта локаций («Отправиться в локацию»)
        Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val hasMap = SpecialLootId.OLD_MAP in base.persistentSpecialLoot ||
                    (run?.inventory?.hasSpecial(SpecialLootId.OLD_MAP) == true)
                Text(
                    if (hasMap) "Карта экспедиции (открыта «Старой картой»)" else "Тропы из лагеря",
                    fontWeight = FontWeight.Bold,
                    color = SnowCrispText
                )
                val evalRun = run ?: SurvivalEngine.createNewRun(base)
                for (loc in LocationId.entries) {
                    val status = SurvivalEngine.evaluateLocationUnlock(loc, evalRun, base)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "${loc.titleRu} — спецлут: «${loc.uniqueLoot.titleRu}»",
                            color = if (status.isUnlocked) SpruceEmerald else MistMutedText,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (hasMap || status.isUnlocked || loc == LocationId.BASE_CLEARING) {
                            Text(loc.subtitleRu, color = MistMutedText, fontSize = 12.sp)
                            Text("Условие: ${status.conditionSummaryRu}", color = IceCyanPrimary, fontSize = 11.sp)
                        } else {
                            Text(
                                "Найдите «Старую карту» на Базовой поляне, чтобы увидеть подробности маршрута.",
                                color = MistMutedText,
                                fontSize = 11.sp
                            )
                        }
                        Button(onClick = { viewModel.onTravelToLocation(loc) }) {
                            Text(if (status.isUnlocked) "Отправиться" else "Проверить проход")
                        }
                    }
                    HorizontalDivider(color = Color(0x22FFFFFF))
                }
            }
        }

        // Раздел 6: Коллекция находок (N из 5)
        Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
            val foundSet = base.persistentSpecialLoot +
                (run?.inventory?.specialItems?.keys.orEmpty())
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Коллекция находок: найдено ${foundSet.size} из ${SpecialLootId.entries.size}",
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
}

@Composable
fun ResultsScreen(
    state: WinterForestUiState,
    viewModel: WinterForestViewModel
) {
    val run = state.activeRun
    val context = LocalContext.current
    val activity = context as? Activity

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepPineNight)
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Экспедиция завершена",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = CrimsonDanger
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Холод оказался сильнее, но постройки базы и постоянные находки сохранены.",
                color = MistMutedText,
                fontSize = 13.sp
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = FrostedCardSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Статистика забега", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = SnowCrispText)
                Text("Дней выжито: ${run?.daysSurvived ?: 0}", color = SnowCrispText)
                Text("Лучший рекорд: ${state.baseProgress.bestDaysSurvived} дн.", color = HearthAmberAccent)
                Text("Собрано ресурсов: ${run?.totalResourcesGathered ?: 0}", color = SnowCrispText)
                Text("Уровень персонажа: ${run?.level ?: 1}", color = SnowCrispText)
                Text("Суммарный уровень базы: ${state.baseProgress.totalBaseLevel}", color = IceCyanPrimary)
                val visitedNames = run?.visitedLocations?.joinToString(", ") { it.titleRu }.orEmpty()
                Text("Посещённые локации: $visitedNames", color = SnowCrispText)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.isReviveAdButtonVisible) {
                Button(
                    onClick = { viewModel.onRequestRewardedRevive(activity) },
                    colors = ButtonDefaults.buttonColors(containerColor = SpruceEmerald),
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("btn_rewarded_revive")
                ) {
                    Text(
                        text = "Возродиться (реклама) — 50% сил на базе",
                        color = Color(0xFF061B10),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Button(
                onClick = { viewModel.startNewGame() },
                colors = ButtonDefaults.buttonColors(containerColor = HearthAmberAccent),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Начать заново", color = Color(0xFF1A1006), fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = { viewModel.navigateTo(AppDestination.MAIN_MENU) },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("В меню")
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
            .background(DeepPineNight)
            .statusBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Настройки", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = SnowCrispText)
            OutlinedButton(onClick = { viewModel.navigateBack() }) {
                Text("Назад")
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Громкость звука: ${(state.settings.soundVolume * 100).roundToInt()}%", color = SnowCrispText)
                Slider(
                    value = state.settings.soundVolume,
                    onValueChange = { viewModel.onSoundVolumeChanged(it) },
                    valueRange = 0f..1f
                )

                Text(
                    "Чувствительность обзора: ${"%.2f".format(state.settings.lookSensitivity)}x",
                    color = SnowCrispText
                )
                Slider(
                    value = state.settings.lookSensitivity,
                    onValueChange = { viewModel.onLookSensitivityChanged(it) },
                    valueRange = 0.3f..2.5f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Согласие на персонализацию рекламы (РСЯ)", color = SnowCrispText)
                        Text("Геолокация отключена постоянно.", color = MistMutedText, fontSize = 12.sp)
                    }
                    Switch(
                        checked = state.settings.gdprUserConsent,
                        onCheckedChange = { viewModel.onGdprConsentSelected(it) }
                    )
                }
            }
        }

        Button(
            onClick = { viewModel.requestResetProgressDialog() },
            colors = ButtonDefaults.buttonColors(containerColor = CrimsonDanger),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Сбросить прогресс")
        }
    }
}

@Composable
fun AboutScreen(viewModel: WinterForestViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepPineNight)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Об игре", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = SnowCrispText)
            OutlinedButton(onClick = { viewModel.navigateBack() }) {
                Text("Назад")
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = FrostedCardSurface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Winter Forest", style = MaterialTheme.typography.titleLarge, color = SnowCrispText)
                Text(
                    "Атмосферное 3D-приключение на выживание от первого лица в северном лесу. " +
                        "Собирайте дрова, пищу и редкие материалы, поддерживайте огонь на Базовой поляне, " +
                        "улучшайте укрытие, костёр и верстак и открывайте пять суровых локаций с уникальными находками.",
                    color = MistMutedText
                )
                Text("Разработчик: Ayaks", color = IceCyanPrimary)
                Text("Контакт для обратной связи: kirillpetrov08@gmail.com", color = SnowCrispText)
                Text("Версия: 1.0.0", color = MistMutedText)
            }
        }
    }
}
