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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayaks.winterforest.domain.model.EquippedTool
import com.ayaks.winterforest.domain.model.GameRunState
import com.ayaks.winterforest.domain.model.LocationId
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
    modifier: Modifier = Modifier
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
            Text(
                text = "${prompt.actionVerbRu} • ${"%.1f".format(prompt.distanceMeters)} м",
                color = SnowCrispText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xC4091520))
                    .border(1.dp, reticleColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            )
        }
    }
}

/**
 * Нижний хотбар быстрого переключения снаряжения и еды в 1 касание (5 слотов).
 */
@Composable
fun QuickEquipmentHotbar(
    run: GameRunState,
    onSelectTool: (EquippedTool) -> Unit,
    onIgniteOrEquipTorch: () -> Unit,
    onQuickEat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val torchCount = run.inventory.specialCount(SpecialLootId.RESIN_TORCH)
    val foodCount = run.inventory.countOf(ResourceType.MEAT) +
        run.inventory.countOf(ResourceType.FISH) +
        run.inventory.countOf(ResourceType.BERRIES)

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xC2091520))
            .border(1.dp, Color(0x367CC6FE), RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        HotbarSlotChip(
            indexLabel = "1",
            title = "Топор",
            selected = run.equippedTool == EquippedTool.AXE,
            badge = null,
            onClick = { onSelectTool(EquippedTool.AXE) }
        )
        HotbarSlotChip(
            indexLabel = "2",
            title = "Кирка",
            selected = run.equippedTool == EquippedTool.PICKAXE,
            badge = null,
            onClick = { onSelectTool(EquippedTool.PICKAXE) }
        )
        HotbarSlotChip(
            indexLabel = "3",
            title = "Копьё",
            selected = run.equippedTool == EquippedTool.SPEAR,
            badge = null,
            onClick = { onSelectTool(EquippedTool.SPEAR) }
        )
        HotbarSlotChip(
            indexLabel = "4",
            title = "Факел",
            selected = run.equippedTool == EquippedTool.TORCH,
            badge = if (torchCount > 0) "×$torchCount" else null,
            onClick = onIgniteOrEquipTorch
        )
        HotbarSlotChip(
            indexLabel = "5",
            title = "Еда",
            selected = false,
            badge = "×$foodCount",
            onClick = onQuickEat
        )
    }
}

@Composable
private fun HotbarSlotChip(
    indexLabel: String,
    title: String,
    selected: Boolean,
    badge: String?,
    onClick: () -> Unit
) {
    val bg = if (selected) IceCyanPrimary.copy(alpha = 0.26f) else FrostedCardSurface.copy(alpha = 0.72f)
    val borderCol = if (selected) IceCyanPrimary else Color(0x28FFFFFF)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.2.dp, borderCol, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = indexLabel, color = MistMutedText, fontSize = 9.sp)
            Text(
                text = title,
                color = if (selected) SnowCrispText else SnowCrispText.copy(alpha = 0.88f),
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        }
        if (badge != null) {
            Text(text = badge, color = HearthAmberAccent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
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
