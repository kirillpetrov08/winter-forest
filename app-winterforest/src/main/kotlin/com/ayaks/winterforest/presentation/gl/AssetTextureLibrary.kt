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
    ): ByteBuffer? = loadRgbaFromAsset(context, "textures/$name.png", outSize, tintR, tintG, tintB)

    /** Загрузка произвольного PNG из assets по полному пути (модели: models/<id>/diff.png). */
    fun loadRgbaFromAsset(
        context: Context,
        assetPath: String,
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
            val raw = context.assets.open(assetPath).use { stream ->
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

    /** Текстура модели: реальные пиксельные размеры, без апскейла и растягивания. */
    class ModelTexture(val width: Int, val height: Int, val buffer: ByteBuffer)

    /**
     * Загрузка текстуры модели (`models/<id>/diff.png`) в её НАТИВНОМ масштабе.
     *
     * Прежний путь масштабировал картинку до `quality.textureSize` квадратом:
     * источники 512×512 (а barrel и oak_* — 512×256), то есть на HIGH/ULTRA
     * текстура впустую раздувалась до 1024²/2048² — 4–16× видеопамяти на каждый
     * из 34 пропов (до 544 МБ на ULTRA) — и квадратично растягивала
     * не-квадратные атласы. Здесь размер берётся из самой картинки и только
     * уменьшается (inSampleSize), если она больше [maxSize].
     */
    fun loadModelTexture(context: Context, assetPath: String, maxSize: Int): ModelTexture? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val target = if (maxSize > 0) maxSize else 2048
            while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inScaled = false
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null
            val w = raw.width
            val h = raw.height
            val buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            raw.copyPixelsToBuffer(buffer)
            raw.recycle()
            buffer.position(0)
            ModelTexture(w, h, buffer)
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
