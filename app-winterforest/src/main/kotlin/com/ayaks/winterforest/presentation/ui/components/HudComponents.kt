package com.ayaks.winterforest.presentation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayaks.winterforest.domain.model.EquippedTool
import com.ayaks.winterforest.domain.model.GameRunState
import com.ayaks.winterforest.domain.model.Inventory
import com.ayaks.winterforest.domain.model.InventorySlot
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.ResourceCategory
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.SpecialLootId
import com.ayaks.winterforest.presentation.ui.theme.CrimsonDanger
import com.ayaks.winterforest.presentation.ui.theme.FrostedCardSurface
import com.ayaks.winterforest.presentation.ui.theme.HearthAmberAccent
import com.ayaks.winterforest.presentation.ui.theme.IceCyanPrimary
import com.ayaks.winterforest.presentation.ui.theme.MistMutedText
import com.ayaks.winterforest.presentation.ui.theme.SnowCrispText
import com.ayaks.winterforest.presentation.ui.theme.SpruceEmerald
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

val HealthBarColor = CrimsonDanger
val HungerBarColor = HearthAmberAccent
val WarmthBarColor = IceCyanPrimary
val StaminaBarColor = Color(0xFFF6D365)
val XpBarColor = SpruceEmerald

@Composable
fun SurvivalStatBar(
    label: String,
    value: Float,
    maxValue: Float,
    barColor: Color,
    modifier: Modifier = Modifier
) {
    val progress = (value / maxValue.coerceAtLeast(1f)).coerceIn(0f, 1f)
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, color = SnowCrispText, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Text(
                text = "${value.roundToInt()}/${maxValue.roundToInt()}",
                color = MistMutedText,
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.height(1.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = barColor,
            trackColor = Color(0x36FFFFFF)
        )
    }
}

/**
 * Плавный плавающий 2-зонный виртуальный джойстик под большой палец левой руки:
 * центр автоматически подстраивается под точку касания + внешнее кольцо авто-спринта.
 */
@Composable
fun VirtualJoystick(
    sizeDp: Int = 148,
    onJoystickChanged: (forward: Float, strafe: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var anchorOffset by remember { mutableStateOf<Offset?>(null) }
    var knobDelta by remember { mutableStateOf(Offset.Zero) }
    var sprintActive by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(24.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val origin = Offset(
                        x = down.position.x.coerceIn(size.width * 0.25f, size.width * 0.75f),
                        y = down.position.y.coerceIn(size.height * 0.25f, size.height * 0.75f)
                    )
                    anchorOffset = origin
                    val maxRadiusPx = minOf(size.width, size.height) * 0.34f

                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) break
                        pointer.consume()
                        val raw = pointer.position - origin
                        val dist = hypot(raw.x, raw.y)
                        val clamped = if (dist > maxRadiusPx && dist > 0.001f) {
                            Offset(raw.x / dist * maxRadiusPx, raw.y / dist * maxRadiusPx)
                        } else {
                            raw
                        }
                        knobDelta = clamped
                        val normStrafe = (clamped.x / maxRadiusPx).coerceIn(-1f, 1f)
                        val normForward = (-clamped.y / maxRadiusPx).coerceIn(-1f, 1f)
                        sprintActive = hypot(normForward, normStrafe) >= 0.84f
                        onJoystickChanged(normForward, normStrafe)
                    }

                    anchorOffset = null
                    knobDelta = Offset.Zero
                    sprintActive = false
                    onJoystickChanged(0f, 0f)
                }
            }
    ) {
        Canvas(modifier = Modifier.size(sizeDp.dp)) {
            val baseCenter = anchorOffset ?: Offset(size.width * 0.46f, size.height * 0.54f)
            val outerR = size.minDimension * 0.36f
            val ringCol = if (sprintActive) HearthAmberAccent.copy(alpha = 0.85f) else IceCyanPrimary.copy(alpha = 0.48f)

            drawCircle(
                color = Color(0x52091521),
                radius = outerR,
                center = baseCenter
            )
            drawCircle(
                color = ringCol,
                radius = outerR,
                center = baseCenter,
                style = Stroke(width = if (sprintActive) 4.5f else 2.6f)
            )
            drawCircle(
                color = Color(0x28FFFFFF),
                radius = outerR * 0.84f,
                center = baseCenter,
                style = Stroke(width = 1.2f)
            )
            drawCircle(
                color = if (sprintActive) HearthAmberAccent else IceCyanPrimary.copy(alpha = 0.90f),
                radius = size.minDimension * 0.145f,
                center = baseCenter + knobDelta
            )
        }
    }
}

/**
 * Круговой вращающийся мини-радар локации: костёр базы, полевые костры, северные ворота,
 * ближайшие ресурсы, уникальный тайник реликвии и живые звери/волки.
 */
@Composable
fun MiniRadarHud(
    run: GameRunState,
    modifier: Modifier = Modifier,
    onMapClick: (() -> Unit)? = null
) {
    val yawRad = Math.toRadians(run.yawDeg.toDouble()).toFloat()
    val cardinal = when (((run.yawDeg % 360f + 360f) % 360f).roundToInt()) {
        in 45..134 -> "В"
        in 135..224 -> "Ю"
        in 225..314 -> "З"
        else -> "С"
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xBA091520))
            .border(1.dp, Color(0x387CC6FE), RoundedCornerShape(14.dp))
            .then(if (onMapClick != null) Modifier.clickable(onClick = onMapClick) else Modifier)
            .padding(6.dp)
    ) {
        Box(contentAlignment = Alignment.TopCenter, modifier = Modifier.size(76.dp)) {
            Canvas(modifier = Modifier.size(76.dp)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val rMax = size.minDimension * 0.45f
                val radarRangeMeters = 32f
                val cy = cos(yawRad)
                val sy = sin(yawRad)

                fun projectWorld(wx: Float, wz: Float): Offset {
                    val dx = wx - run.playerX
                    val dz = wz - run.playerZ
                    // Поворот в систему координат взгляда игрока (вперёд = вверх на радаре)
                    val rx = dx * cy + dz * sy
                    val rz = -dx * sy + dz * cy
                    val scale = rMax / radarRangeMeters
                    val px = rx * scale
                    val py = rz * scale
                    val d = hypot(px, py)
                    val clampedFactor = if (d > rMax - 3f && d > 0.01f) (rMax - 3f) / d else 1f
                    return Offset(c.x + px * clampedFactor, c.y + py * clampedFactor)
                }

                drawCircle(color = Color(0x99061019), radius = rMax, center = c)
                drawCircle(color = Color(0x337CC6FE), radius = rMax * 0.55f, center = c, style = Stroke(1f))
                drawCircle(color = Color(0x667CC6FE), radius = rMax, center = c, style = Stroke(2f))

                // Северные ворота перехода в следующую локацию
                drawCircle(color = IceCyanPrimary, radius = 3.8f, center = projectWorld(0f, -35.5f))

                // Костёр Базовой поляны и полевые костры
                if (run.currentLocation == LocationId.BASE_CLEARING) {
                    drawCircle(color = HearthAmberAccent, radius = 4.6f, center = projectWorld(0f, 0f))
                }
                for (fc in run.fieldCampfires) {
                    drawCircle(color = HearthAmberAccent, radius = 3.8f, center = projectWorld(fc.x, fc.z))
                }

                // Ресурсы и уникальный тайник
                for (node in run.currentLocationNodes()) {
                    if (!node.isAvailable) continue
                    val pt = projectWorld(node.x, node.z)
                    if (node.specialLootId != null) {
                        drawCircle(color = Color(0xFFFFE066), radius = 4.6f, center = pt)
                    } else {
                        drawCircle(color = SpruceEmerald.copy(alpha = 0.85f), radius = 2.4f, center = pt)
                    }
                }

                // Животные: белые точки — дичь, алые — волки
                for (beast in run.currentWildlife()) {
                    if (!beast.isAlive) continue
                    drawCircle(
                        color = if (beast.kind.isPredator) CrimsonDanger else SnowCrispText,
                        radius = 3.4f,
                        center = projectWorld(beast.x, beast.z)
                    )
                }

                // Метка самого игрока в центре
                drawCircle(color = Color.White, radius = 3.5f, center = c)
            }
            Text(
                text = cardinal,
                color = IceCyanPrimary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Центральный прицел и контекстная подсказка объекта прямо по курсу взгляда.
 */
@Composable
fun CrosshairOverlay(
    run: GameRunState,
    modifier: Modifier = Modifier
) {
    val prompt = run.activeTargetPrompt()
    val reticleColor = when {
        run.hitFeedbackTimerSec > 0f -> HearthAmberAccent
        prompt?.isSpecialRelic == true -> Color(0xFFFFE066)
        prompt?.isHostile == true -> CrimsonDanger
        prompt != null -> SpruceEmerald
        else -> Color(0xAAFFFFFF)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val gap = if (run.hitFeedbackTimerSec > 0f) 5.5f else 3.2f
            val arm = 5.5f
            drawLine(reticleColor, Offset(c.x - gap - arm, c.y), Offset(c.x - gap, c.y), strokeWidth = 2f)
            drawLine(reticleColor, Offset(c.x + gap, c.y), Offset(c.x + gap + arm, c.y), strokeWidth = 2f)
            drawLine(reticleColor, Offset(c.x, c.y - gap - arm), Offset(c.x, c.y - gap), strokeWidth = 2f)
            drawLine(reticleColor, Offset(c.x, c.y + gap), Offset(c.x, c.y + gap + arm), strokeWidth = 2f)
            drawCircle(reticleColor, radius = 1.6f, center = c)
        }
        if (prompt != null) {
            Spacer(Modifier.height(4.dp))
            val promptIcon = prompt.iconPath?.let { rememberAssetIcon(it) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xC4091520))
                    .border(1.dp, reticleColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                if (promptIcon != null) {
                    androidx.compose.foundation.Image(
                        bitmap = promptIcon,
                        contentDescription = prompt.labelRu,
                        modifier = Modifier.size(16.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = "${prompt.actionVerbRu} • ${"%.1f".format(prompt.distanceMeters)} м",
                    color = SnowCrispText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Нижний хотбар быстрого доступа: 6 слотов, изначально пустые.
 * Игрок сам наполняет их едой, инструментами и расходниками из рюкзака
 * (в меню Базы/Рюкзака). В игре слот можно использовать — экипировать
 * инструмент или съесть/применить расходник.
 */
@Composable
fun QuickEquipmentHotbar(
    run: GameRunState,
    onUseSlot: (Int) -> Unit,
    modifier: Modifier = Modifier,
    slotSizeDp: Int = 56
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xC2091520))
            .border(1.dp, Color(0x367CC6FE), RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        for (slotIdx in 0..5) {
            val entry = run.hotbarAt(slotIdx)
            // Количество еды/ресурсов/инструментов прямо на ячейке хотбара
            val count = entry?.let { e ->
                when {
                    e.resourceType != null -> run.inventory.countOf(e.resourceType!!)
                    e.specialLootId != null -> run.inventory.specialCount(e.specialLootId!!)
                    e.tool != null -> run.inventory.toolCount(e.tool!!)
                    else -> 0
                }
            } ?: 0
            HotbarSlotChip(
                indexLabel = (slotIdx + 1).toString(),
                entry = entry,
                countBadge = if (count > 0) "×$count" else null,
                slotSizeDp = slotSizeDp,
                onClick = { onUseSlot(slotIdx) }
            )
        }
    }
}

/** Иконка инструмента (Unicode-символ, пока не привезены иконки-картинки). */
fun toolIcon(tool: EquippedTool): String = when (tool) {
    EquippedTool.NONE -> "✋"
    EquippedTool.AXE -> "🪓"
    EquippedTool.PICKAXE -> "⛏️"
    EquippedTool.SPEAR -> "🎯"
    EquippedTool.TORCH -> "🔥"
}

/** Ячейка слота хотбара: иконка + подпись + счётчик количества, либо пустая ячейка. */
@Composable
private fun HotbarSlotChip(
    indexLabel: String,
    entry: com.ayaks.winterforest.domain.model.HotbarEntry?,
    countBadge: String? = null,
    slotSizeDp: Int = 56,
    onClick: () -> Unit
) {
    val bg = FrostedCardSurface.copy(alpha = 0.72f)
    val borderCol = Color(0x28FFFFFF)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.2.dp, borderCol, RoundedCornerShape(10.dp))
            .clickable(enabled = true, onClick = onClick)
            .size(slotSizeDp.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (entry != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    val customIcon = entry.specialLootId?.let { rememberAssetIcon(specialLootIconPath(it)) }
                    when {
                        entry.isTool -> Text(
                            text = toolIcon(entry.tool!!),
                            fontSize = 20.sp
                        )
                        entry.resourceType != null && customIcon == null -> {
                            ResourceTypeGlyph(
                                type = entry.resourceType!!,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        customIcon != null -> androidx.compose.foundation.Image(
                            bitmap = customIcon,
                            contentDescription = entry.titleRu,
                            modifier = Modifier.size(30.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                        )
                        else -> Text(text = "📦", fontSize = 20.sp)
                    }
                }
                Text(
                    text = entry.titleRu,
                    color = SnowCrispText,
                    fontSize = 9.sp,
                    maxLines = 1
                )
            } else {
                Text(text = indexLabel, color = MistMutedText, fontSize = 10.sp)
                Text(text = "—", color = MistMutedText.copy(alpha = 0.5f), fontSize = 16.sp)
            }
        }
        if (countBadge != null) {
            Text(
                text = countBadge,
                color = SnowCrispText,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 3.dp, bottom = 1.dp)
            )
        }
    }
}


@Composable
fun MiniCompassBadge(
    playerX: Float,
    playerZ: Float,
    yawDeg: Float,
    isAtBaseZone: Boolean,
    modifier: Modifier = Modifier
) {
    val distToCamp = hypot(playerX, playerZ).roundToInt()
    val cardinal = when (((yawDeg % 360f + 360f) % 360f).roundToInt()) {
        in 45..134 -> "В"
        in 135..224 -> "Ю"
        in 225..314 -> "З"
        else -> "С"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xAA0C1824))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text = "Курс: $cardinal • ${if (isAtBaseZone) "Очаг ${distToCamp} м" else "Тропа ${distToCamp} м"}",
            color = if (distToCamp <= 8 && isAtBaseZone) SpruceEmerald else IceCyanPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Компактная векторная иконка ресурса для ячеек рюкзака (Canvas, без растровых ассетов).
 */
@Composable
fun ResourceTypeGlyph(type: ResourceType, modifier: Modifier = Modifier) {
    val bmp = rememberAssetIcon("icons/${assetIconNameFor(type)}.png")
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp,
            contentDescription = type.titleRu,
            modifier = modifier,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit
        )
        return
    }
    val tint = when (type.category) {
        ResourceCategory.FUEL -> HearthAmberAccent
        ResourceCategory.FOOD -> SpruceEmerald
        ResourceCategory.MATERIAL -> IceCyanPrimary
    }
    Canvas(modifier = modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        drawCircle(color = tint.copy(alpha = 0.9f), radius = w * 0.32f, center = Offset(w * 0.5f, h * 0.5f))
        drawCircle(color = tint.copy(alpha = 0.5f), radius = w * 0.16f, center = Offset(w * 0.5f, h * 0.5f))
    }
}

/** Имя предметной иконки в assets/icons для ресурса. */
fun assetIconNameFor(type: ResourceType): String = when (type) {
    ResourceType.WOOD -> "icon_wood"
    ResourceType.STONE -> "icon_stone"
    ResourceType.IRON -> "icon_iron_ore"
    ResourceType.IRON_INGOT -> "icon_iron_ore"
    ResourceType.COAL -> "icon_coal"
    ResourceType.CLOTH -> "icon_cloth"
    ResourceType.HIDE -> "icon_hide"
    ResourceType.MEAT -> "icon_meat"
    ResourceType.FISH -> "icon_fish"
    ResourceType.BERRIES -> "icon_berries"
    ResourceType.RESIN -> "icon_resin"
    ResourceType.ICE -> "icon_ice"
}

/** Иконка редкого предмета в assets/icons (пустая строка, если файла ещё нет). */
fun specialLootIconPath(id: SpecialLootId): String = id.iconPath

/** Загрузка PNG из assets как ImageBitmap (с кэшем через remember). */
@Composable
fun rememberAssetIcon(path: String): androidx.compose.ui.graphics.ImageBitmap? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(path) {
        runCatching {
            // Иконки в ассетах — 1024² (глифы 512²), а в HUD рисуются 40–64 dp.
            // Полный декод стоил 4 МБ на иконку: десяток иконок на экране — 40+ МБ
            // heap и постоянные GC-паузы на слабых устройствах. Декодируем с
            // inSampleSize под целевые ~192 px (≈256 КБ на иконку).
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(path).use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
            }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= ICON_TARGET_PX &&
                bounds.outHeight / (sample * 2) >= ICON_TARGET_PX
            ) {
                sample *= 2
            }
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            context.assets.open(path).use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, opts)?.asImageBitmap()
            }
        }.getOrNull()
    }
}

/** Целевой размер декодирования иконок HUD, px (≈64 dp на xhdpi-xxhdpi). */
private const val ICON_TARGET_PX = 192

/**
 * Кнопка с внутренним кулдауном: фон заполняется слева направо по прогрессу
 * перезарядки, на время кулдауна кнопка некликабельна.
 */
@Composable
fun CooldownButton(
    label: String,
    cooldownRemainingSec: Float,
    cooldownTotalSec: Float,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cooling = cooldownRemainingSec > 0.05f
    val readyFraction = if (cooling && cooldownTotalSec > 0f) {
        (1f - cooldownRemainingSec / cooldownTotalSec).coerceIn(0f, 1f)
    } else {
        1f
    }
    val bg = if (cooling) Color(0x33FFFFFF) else Color(0xE6243444)
    val fg = if (!enabled || cooling) MistMutedText else SnowCrispText
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .border(1.dp, if (cooling) Color(0x2EFFFFFF) else Color(0x557CC6FE), RoundedCornerShape(9.dp))
            .clickable(enabled = enabled && !cooling, onClick = onClick)
    ) {
        // заливка прогресса слева направо
        if (cooling) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(IceCyanPrimary.copy(alpha = 0.28f * readyFraction))
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(readyFraction)
                    .background(IceCyanPrimary.copy(alpha = 0.34f))
            )
        }
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
    }
}
