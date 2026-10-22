package com.ayaks.winterforest.presentation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayaks.winterforest.presentation.ui.theme.CrimsonDanger
import com.ayaks.winterforest.presentation.ui.theme.HearthAmberAccent
import com.ayaks.winterforest.presentation.ui.theme.IceCyanPrimary
import com.ayaks.winterforest.presentation.ui.theme.SnowCrispText
import com.ayaks.winterforest.presentation.ui.theme.SpruceEmerald
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

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
            Text(text = label, color = SnowCrispText, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(
                text = "${value.roundToInt()}/${maxValue.roundToInt()}",
                color = SnowCrispText,
                fontSize = 11.sp
            )
        }
        Spacer(Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = barColor,
            trackColor = Color(0x44FFFFFF)
        )
    }
}

@Composable
fun VirtualJoystick(
    sizeDp: Int = 132,
    onJoystickChanged: (forward: Float, strafe: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var knobOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(Color(0x5510202E))
            .pointerInput(Unit) {
                val maxRadiusPx = size.width * 0.36f
                detectDragGestures(
                    onDragEnd = {
                        knobOffset = Offset.Zero
                        onJoystickChanged(0f, 0f)
                    },
                    onDragCancel = {
                        knobOffset = Offset.Zero
                        onJoystickChanged(0f, 0f)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val raw = knobOffset + dragAmount
                        val dist = hypot(raw.x, raw.y)
                        val clamped = if (dist > maxRadiusPx && dist > 0.001f) {
                            Offset(raw.x / dist * maxRadiusPx, raw.y / dist * maxRadiusPx)
                        } else {
                            raw
                        }
                        knobOffset = clamped
                        val normStrafe = (clamped.x / maxRadiusPx).coerceIn(-1f, 1f)
                        val normForward = (-clamped.y / maxRadiusPx).coerceIn(-1f, 1f)
                        onJoystickChanged(normForward, normStrafe)
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.size(sizeDp.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = Color(0x667CC6FE),
                radius = size.minDimension * 0.46f,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
            )
            drawCircle(
                color = Color(0xCC7CC6FE),
                radius = size.minDimension * 0.20f,
                center = center + knobOffset
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
    val angleToOriginDeg = Math.toDegrees(atan2(-playerX.toDouble(), playerZ.toDouble())).toFloat()
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
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = "Компас: $cardinal",
            color = IceCyanPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (isAtBaseZone) {
                "Костёр базы: ${distToCamp} м (${angleToOriginDeg.roundToInt()}°)"
            } else {
                "Экспедиция (${distToCamp} м от тропы)"
            },
            color = if (distToCamp <= 8 && isAtBaseZone) SpruceEmerald else HearthAmberAccent,
            fontSize = 11.sp
        )
    }
}

val HealthBarColor = CrimsonDanger
val HungerBarColor = HearthAmberAccent
val WarmthBarColor = IceCyanPrimary
val XpBarColor = SpruceEmerald
