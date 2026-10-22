package com.ayaks.winterforest.presentation.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Загрузчик высокодетализированных текстур материалов из `assets/textures/`
 * (2048×2048 PNG каждого материала и локации запечены офлайн и упакованы в APK).
 *
 * Текстуры даунсемплятся под [outSize] текущего [com.ayaks.winterforest.domain.model.GraphicsQuality],
 * тонируются палитрой биома и возвращаются в формате RGBA для glTexImage2D.
 * Если ассет отсутствует — вызывающий код использует процедурную генерацию.
 */
object AssetTextureLibrary {

    fun assetNameFor(id: ProceduralTextureId): String = when (id) {
        ProceduralTextureId.SNOW -> "snow_soft"
        ProceduralTextureId.WOOD_BARK -> "bark_pine"
        ProceduralTextureId.PINE_FOLIAGE -> "pine_needles"
        ProceduralTextureId.STONE -> "stone_granite"
        ProceduralTextureId.CLOTH -> "cloth_canvas"
        ProceduralTextureId.ICE -> "ice_cracked"
        ProceduralTextureId.TIMBER -> "wood_plank"
        ProceduralTextureId.FIRE_EMBER -> "flame_streak"
        ProceduralTextureId.METAL_STEEL -> "metal_worn"
        ProceduralTextureId.FUR_HIDE -> "fur_deer"
    }

    fun loadRgba(
        context: Context,
        name: String,
        outSize: Int,
        tintR: Float = 1f,
        tintG: Float = 1f,
        tintB: Float = 1f
    ): ByteBuffer? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = context.assets.open("textures/$name.png").use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            } ?: return null
            val bmp = if (raw.width != outSize || raw.height != outSize) {
                val scaled = Bitmap.createScaledBitmap(raw, outSize, outSize, true)
                if (scaled !== raw) raw.recycle()
                scaled
            } else {
                raw
            }
            val buffer = ByteBuffer.allocateDirect(outSize * outSize * 4).order(ByteOrder.nativeOrder())
            bmp.copyPixelsToBuffer(buffer)
            bmp.recycle()
            buffer.position(0)

            // Тонировка биома прямо в буфере (snOwTint и пр.) без повторной аллокации
            if (tintR != 1f || tintG != 1f || tintB != 1f) {
                val tmp = ByteArray(4 * 1024)
                var remaining = outSize * outSize * 4
                buffer.position(0)
                while (remaining > 0) {
                    val chunk = minOf(tmp.size, remaining)
                    buffer.get(tmp, 0, chunk)
                    var i = 0
                    while (i < chunk) {
                        tmp[i] = (tmp[i].toInt().and(0xFF) * tintR).toInt().coerceIn(0, 255).toByte()
                        tmp[i + 1] = (tmp[i + 1].toInt().and(0xFF) * tintG).toInt().coerceIn(0, 255).toByte()
                        tmp[i + 2] = (tmp[i + 2].toInt().and(0xFF) * tintB).toInt().coerceIn(0, 255).toByte()
                        i += 4
                    }
                    buffer.position(buffer.position() - chunk)
                    buffer.put(tmp, 0, chunk)
                    remaining -= chunk
                }
                buffer.position(0)
            }
            buffer
        } catch (e: Exception) {
            null
        }
    }

    fun anyAssetsAvailable(context: Context): Boolean = try {
        val list = context.assets.list("textures")
        list != null && list.isNotEmpty()
    } catch (e: Exception) {
        false
    }
}
