package com.ayaks.winterforest.presentation.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image

/**
 * «Стеклянные» панели и кнопки мобильного меню выживания (как в Last Day on Earth /
 * Frostborn): полупрозрачный тёмный фон, тонкая световая кайма, крупный главный CTA
 * тёплым янтарём и вторичные кнопки с иконками-глифами.
 */

val GlassDeep = Color(0xD40B1926)
val GlassSoft = Color(0x59FFFFFF)
val GlassBorder = Color(0x33FFFFFF)
val GlassChipText = Color(0xCFE7F2FB)

/** Модификатор «стеклянной» панели: полупрозрачная заливка + кайма + скругление. */
fun Modifier.glassPanel(corner: Dp = 18.dp, fill: Color = GlassDeep): Modifier = this
    .clip(RoundedCornerShape(corner))
    .background(fill)
    .border(1.dp, GlassBorder, RoundedCornerShape(corner))

/** Крупная главная кнопка меню (единственный главный CTA экрана). */
@Composable
fun PrimaryGameButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageBitmap? = null,
    height: Dp = 54.dp
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFFF7B267), Color(0xFFEE8B3D))
                )
            )
            .border(1.dp, Color(0x55FFFFFF), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = text,
                color = Color(0xFF1A1006),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp
            )
        }
    }
}

/** Вторичная кнопка меню: тёмное стекло с каймой и опциональной иконкой-глифом. */
@Composable
fun GlassMenuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageBitmap? = null,
    height: Dp = 46.dp,
    fontSize: Int = 13
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(Color(0xB30E1F2E))
            .border(1.dp, GlassBorder, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(
                text = text,
                color = GlassChipText,
                fontWeight = FontWeight.SemiBold,
                fontSize = fontSize.sp
            )
        }
    }
}

/** Компактная стеклянная плашка (статистика, подписи, стоимости). */
@Composable
fun GlassChip(
    text: String,
    modifier: Modifier = Modifier,
    corner: Dp = 10.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
    textColor: Color = GlassChipText,
    fill: Color = Color(0x8CFFFFFF),
    fontSize: Int = 11
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(fill)
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = textColor, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Строка значений: «Иконка + Значение + Подпись» — как полосы статов в мобильных играх. */
@Composable
fun GlassStatRow(
    items: List<Triple<ImageBitmap?, String, String>>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glassPanel(16.dp, Color(0xB30B1926))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for ((icon, value, label) in items) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.width(6.dp))
                }
                androidx.compose.foundation.layout.Column {
                    Text(value, color = Color(0xFFF2F8FD), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(label, color = Color(0x99E7F2FB), fontSize = 9.5.sp)
                }
            }
        }
    }
}
