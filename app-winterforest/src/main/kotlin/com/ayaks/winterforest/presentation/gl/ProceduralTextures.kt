package com.ayaks.winterforest.presentation.gl

import com.ayaks.winterforest.domain.model.LocationPalette
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

enum class ProceduralTextureId {
    SNOW,
    WOOD_BARK,
    PINE_FOLIAGE,
    STONE,
    CLOTH,
    ICE,
    TIMBER,
    FIRE_EMBER,
    METAL_STEEL,
    FUR_HIDE
}

/**
 * Высокодетализированный процедурный генератор 10 игровых материалов
 * (многооктавный FBM + гребневый мультифрактал + ячеистый Вороной + микрорельеф).
 * Все текстуры бесшовные (tileable) и генерируются один раз при загрузке локации в GPU mipmap-буфер.
 */
object ProceduralTextures {

    const val DEFAULT_TEXTURE_SIZE = 256

    fun generateRgbaBuffer(
        id: ProceduralTextureId,
        palette: LocationPalette,
        size: Int = DEFAULT_TEXTURE_SIZE,
        seed: Int = 1337
    ): ByteBuffer {
        val argb = generateArgbPixels(id, palette, size, seed)
        val buffer = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        val rowBytes = ByteArray(size * 4)
        var idx = 0
        for (y in 0 until size) {
            var bOff = 0
            for (x in 0 until size) {
                val px = argb[idx++]
                rowBytes[bOff++] = ((px ushr 16) and 0xFF).toByte()
                rowBytes[bOff++] = ((px ushr 8) and 0xFF).toByte()
                rowBytes[bOff++] = (px and 0xFF).toByte()
                rowBytes[bOff++] = ((px ushr 24) and 0xFF).toByte()
            }
            buffer.put(rowBytes)
        }
        buffer.position(0)
        return buffer
    }

    fun generateArgbPixels(
        id: ProceduralTextureId,
        palette: LocationPalette,
        size: Int = DEFAULT_TEXTURE_SIZE,
        seed: Int = 1337
    ): IntArray {
        val pixels = IntArray(size * size)
        val snowTintR = ((palette.snowTintHex ushr 16) and 0xFF) / 255f
        val snowTintG = ((palette.snowTintHex ushr 8) and 0xFF) / 255f
        val snowTintB = (palette.snowTintHex and 0xFF) / 255f
        val invSize = 1f / size.toFloat()

        for (y in 0 until size) {
            val v = y.toFloat() * invSize
            for (x in 0 until size) {
                val u = x.toFloat() * invSize
                val idx = y * size + x

                var r: Float
                var g: Float
                var b: Float
                var a = 1f

                when (id) {
                    ProceduralTextureId.SNOW -> {
                        // Ветровые заструги, голубые ложбины АО и искрящиеся ледяные кристаллы
                        val macroDrift = fbm(u * 4.5f, v * 8.0f, octaves = 5, seed = seed)
                        val windWave = 0.5f + 0.5f * sin((u * 2.5f + v * 13.5f + macroDrift * 2.6f) * TWO_PI)
                        val fineGrain = fbm(u * 32f, v * 32f, octaves = 3, seed = seed + 29)
                        val crystal = voronoiCell(u * 30f, v * 30f, seed + 41)
                        val sparkle = if (crystal.first < 0.11f) 0.18f else 0f
                        val hollowBlue = ((0.52f - macroDrift) * 0.55f).coerceIn(0f, 0.22f)
                        val luma = (0.74f + 0.15f * macroDrift + 0.08f * windWave + 0.06f * fineGrain + sparkle)
                            .coerceIn(0.45f, 1.08f)
                        r = (snowTintR * luma * (1f - hollowBlue * 0.45f)).coerceIn(0f, 1f)
                        g = (snowTintG * luma * (1f - hollowBlue * 0.16f)).coerceIn(0f, 1f)
                        b = (snowTintB * luma * (1.03f + hollowBlue * 0.18f)).coerceIn(0f, 1f)
                    }

                    ProceduralTextureId.WOOD_BARK -> {
                        // Глубокая фактурная кора вековой северной сосны и ели с лишайником и изморозью
                        val warp = fbm(u * 6f, v * 2.5f, octaves = 3, seed = seed + 83) * 0.12f
                        val ridge = ridgedFbm((u + warp) * 16f, v * 3.8f, octaves = 5, seed = seed + 101)
                        val plates = voronoiCell((u + warp) * 13f, v * 4.2f, seed + 211)
                        val crevice = (plates.second * 1.65f).coerceIn(0.08f, 1f)
                        val barkBase = (0.16f + 0.46f * ridge * crevice + 0.20f * plates.first).coerceIn(0.06f, 1f)
                        val rime = ((ridge - 0.68f) * 2.6f).coerceIn(0f, 0.52f)
                        r = (0.44f * barkBase + rime * 0.78f).coerceIn(0f, 1f)
                        g = (0.29f * barkBase + rime * 0.84f).coerceIn(0f, 1f)
                        b = (0.20f * barkBase + rime * 0.92f).coerceIn(0f, 1f)
                    }

                    ProceduralTextureId.PINE_FOLIAGE -> {
                        // Объёмные пучки хвои с глубоким затенением внутри ветвей и плотной шапкой снега сверху
                        val cluster = voronoiCell(u * 14f, v * 14f, seed + 313)
                        val needles = ridgedFbm(u * 28f, v * 22f, octaves = 4, seed = seed + 307)
                        val depthAo = (0.35f + 0.65f * cluster.second * 1.4f).coerceIn(0.22f, 1f)
                        val snowLine = 0.44f + (fbm(u * 9f, v * 9f, octaves = 3, seed = seed + 331) - 0.5f) * 0.34f
                        val snowMask = ((snowLine - v) * 4.6f).coerceIn(0f, 1f)
                        val needleShade = (0.26f + 0.52f * needles) * depthAo
                        r = lerp(0.06f + 0.12f * needleShade, 0.92f * snowTintR * (0.86f + 0.14f * needles), snowMask)
                        g = lerp(0.16f + 0.32f * needleShade, 0.95f * snowTintG * (0.88f + 0.12f * needles), snowMask)
                        b = lerp(0.11f + 0.20f * needleShade, 0.99f * snowTintB, snowMask)
                    }

                    ProceduralTextureId.STONE -> {
                        // Сколотый северный гранит со слоями породы, кварцевыми прожилками и снегом в нишах
                        val facets = voronoiCell(u * 7.5f, v * 7.5f, seed + 409)
                        val micro = fbm(u * 26f, v * 26f, octaves = 4, seed = seed + 419)
                        val strata = 0.5f + 0.5f * sin((v * 7f + micro * 1.4f) * TWO_PI)
                        val crackAo = (facets.second * 2.1f).coerceIn(0.12f, 1f)
                        val quartz = ((ridgedFbm(u * 15f, v * 15f, octaves = 3, seed = seed + 433) - 0.78f) * 3.5f)
                            .coerceIn(0f, 0.35f)
                        val luma = ((0.24f + 0.34f * micro + 0.16f * strata) * crackAo + quartz).coerceIn(0.08f, 1f)
                        r = (luma * 0.89f).coerceIn(0f, 1f)
                        g = (luma * 0.92f).coerceIn(0f, 1f)
                        b = (luma * 0.98f).coerceIn(0f, 1f)
                    }

                    ProceduralTextureId.CLOTH -> {
                        // Груботканое северное сукно и прошитая ремнями кожа
                        val herring = 0.5f + 0.5f * sin((u * 42f + (if (((y / 6) and 1) == 0) v * 42f else -v * 42f)))
                        val cross = 0.5f + 0.5f * cos(v * 64f)
                        val wear = fbm(u * 9f, v * 9f, octaves = 4, seed = seed + 503)
                        val strap = if (abs(v - 0.5f) < 0.08f) 1f else 0f
                        val tone = (0.36f + 0.28f * herring + 0.14f * cross + 0.24f * wear).coerceIn(0.14f, 1f)
                        r = lerp(0.66f * tone, 0.34f * tone, strap).coerceIn(0f, 1f)
                        g = lerp(0.42f * tone, 0.21f * tone, strap).coerceIn(0f, 1f)
                        b = lerp(0.28f * tone, 0.13f * tone, strap).coerceIn(0f, 1f)
                    }

                    ProceduralTextureId.ICE -> {
                        // Глубокий прозрачно-бирюзовый озёрный лёд с двойной сетью трещин и пузырьками воздуха
                        val deepFrac = voronoiCell(u * 6.5f, v * 6.5f, seed + 607)
                        val fineFrac = voronoiCell(u * 17f, v * 17f, seed + 619)
                        val primaryCrack = (1f - (deepFrac.second * 8.5f).coerceIn(0f, 1f))
                        val microCrack = (1f - (fineFrac.second * 11f).coerceIn(0f, 1f)) * 0.55f
                        val glacialDepth = fbm(u * 9f, v * 9f, octaves = 5, seed = seed + 613)
                        val bubbles = if (fineFrac.first < 0.09f) 0.22f else 0f
                        val crackSum = (primaryCrack + microCrack + bubbles).coerceIn(0f, 1f)
                        r = (0.34f + 0.24f * glacialDepth + 0.56f * crackSum).coerceIn(0f, 1f)
                        g = (0.64f + 0.18f * glacialDepth + 0.34f * crackSum).coerceIn(0f, 1f)
                        b = (0.84f + 0.10f * glacialDepth + 0.16f * crackSum).coerceIn(0f, 1f)
                    }

                    ProceduralTextureId.TIMBER -> {
                        // Оцилиндрованные тёсаные брёвна зимовья с мхом в пазах, сучками и волокнами древесины
                        val logCount = 5f
                        val rowPos = v * logCount
                        val rowFrac = rowPos - floor(rowPos)
                        val roundProfile = sin(rowFrac * Math.PI.toFloat()).coerceIn(0.18f, 1f)
                        val seamMask = if (rowFrac < 0.06f || rowFrac > 0.94f) 0.38f else 1f
                        val knot = voronoiCell(u * 4.5f, v * 5f, seed + 709)
                        val ring = if (knot.first < 0.16f) 0.72f + 0.28f * sin(knot.first * 90f) else 1f
                        val grain = fbm(u * 5.5f, v * 34f, octaves = 4, seed = seed + 701)
                        val tone = ((0.34f + 0.44f * grain) * roundProfile * seamMask * ring).coerceIn(0.08f, 1f)
                        r = (0.62f * tone).coerceIn(0f, 1f)
                        g = (0.41f * tone).coerceIn(0f, 1f)
                        b = (0.24f * tone).coerceIn(0f, 1f)
                    }

                    ProceduralTextureId.FIRE_EMBER -> {
                        // Раскалённые языки пламени от бело-золотого ядра к багровым искрам
                        val dx = (u - 0.5f) * 2f
                        val dy = (1f - v)
                        val turb = fbm(u * 8f, v * 13f, octaves = 4, seed = seed + 809)
                        val ember = voronoiCell(u * 12f, v * 12f, seed + 821)
                        val core = (1.20f - abs(dx) * (1.15f + (1f - dy) * 0.95f) - (1f - dy) * 0.28f + turb * 0.38f)
                            .coerceIn(0.10f, 1f)
                        val spark = if (ember.first < 0.10f) 0.30f else 0f
                        r = (1.0f * core + spark).coerceIn(0.12f, 1f)
                        g = (0.80f * core * core + 0.16f * core).coerceIn(0.05f, 1f)
                        b = (0.34f * core * core * core).coerceIn(0.02f, 1f)
                        a = (core * 1.25f).coerceIn(0.18f, 1f)
                    }

                    ProceduralTextureId.METAL_STEEL -> {
                        // Кованая закалённая сталь лезвий топора, кирки и копья со следами шлифовки
                        val brushed = fbm(u * 40f, v * 7f, octaves = 4, seed = seed + 907)
                        val hammer = voronoiCell(u * 10f, v * 10f, seed + 919)
                        val bevel = if (u > 0.78f || v < 0.16f) 0.24f else 0f
                        val sheen = (0.46f + 0.28f * brushed + 0.16f * hammer.second + bevel).coerceIn(0.18f, 1f)
                        r = (sheen * 0.86f).coerceIn(0f, 1f)
                        g = (sheen * 0.90f).coerceIn(0f, 1f)
                        b = (sheen * 0.98f).coerceIn(0f, 1f)
                    }

                    ProceduralTextureId.FUR_HIDE -> {
                        // Густой северный мех с направленным ворсом и тёплым подшёрстком
                        val strands = ridgedFbm(u * 36f, v * 11f, octaves = 4, seed = seed + 971)
                        val patch = fbm(u * 5.5f, v * 5.5f, octaves = 3, seed = seed + 983)
                        val shade = (0.48f + 0.34f * strands + 0.22f * patch).coerceIn(0.16f, 1f)
                        r = (0.86f * shade).coerceIn(0f, 1f)
                        g = (0.83f * shade).coerceIn(0f, 1f)
                        b = (0.79f * shade).coerceIn(0f, 1f)
                    }
                }

                pixels[idx] = packArgb(a, r, g, b)
            }
        }
        return pixels
    }

    fun ridgedFbm(x: Float, y: Float, octaves: Int = 4, seed: Int = 0): Float {
        var sum = 0f
        var amp = 0.5f
        var freq = 1f
        var norm = 0f
        repeat(octaves) { oct ->
            val n = gradientNoise(x * freq, y * freq, seed + oct * 977)
            val ridge = 1f - abs(n * 2f - 1f)
            sum += amp * ridge * ridge
            norm += amp
            amp *= 0.5f
            freq *= 2.0f
        }
        return (sum / norm.coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
    }

    fun fbm(x: Float, y: Float, octaves: Int = 4, seed: Int = 0): Float {
        var sum = 0f
        var amp = 0.5f
        var freq = 1f
        var norm = 0f
        repeat(octaves) { oct ->
            sum += amp * gradientNoise(x * freq, y * freq, seed + oct * 977)
            norm += amp
            amp *= 0.5f
            freq *= 2.0f
        }
        return (sum / norm.coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
    }

    fun voronoiCell(x: Float, y: Float, seed: Int): Pair<Float, Float> {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        var f1 = 99f
        var f2 = 99f
        for (oy in -1..1) {
            for (ox in -1..1) {
                val cx = ix + ox
                val cy = iy + oy
                val jx = cx + hashFloat(cx, cy, seed)
                val jy = cy + hashFloat(cx + 57, cy - 31, seed xor 0x55AA)
                val d = hypot(jx - x, jy - y)
                if (d < f1) {
                    f2 = f1
                    f1 = d
                } else if (d < f2) {
                    f2 = d
                }
            }
        }
        return f1.coerceIn(0f, 1f) to (f2 - f1).coerceIn(0f, 1f)
    }

    private fun gradientNoise(x: Float, y: Float, seed: Int): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val ux = fx * fx * (3f - 2f * fx)
        val uy = fy * fy * (3f - 2f * fy)

        val n00 = gradDot(x0, y0, fx, fy, seed)
        val n10 = gradDot(x0 + 1, y0, fx - 1f, fy, seed)
        val n01 = gradDot(x0, y0 + 1, fx, fy - 1f, seed)
        val n11 = gradDot(x0 + 1, y0 + 1, fx - 1f, fy - 1f, seed)

        val nx0 = lerp(n00, n10, ux)
        val nx1 = lerp(n01, n11, ux)
        return (0.5f + 0.5f * lerp(nx0, nx1, uy)).coerceIn(0f, 1f)
    }

    private fun gradDot(ix: Int, iy: Int, dx: Float, dy: Float, seed: Int): Float {
        val angle = hashFloat(ix, iy, seed) * TWO_PI
        return cos(angle) * dx + sin(angle) * dy
    }

    private fun hashFloat(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return ((h and 0x7FFFFFFF).toFloat() / Int.MAX_VALUE.toFloat())
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun packArgb(a: Float, r: Float, g: Float, b: Float): Int {
        val ia = (a.coerceIn(0f, 1f) * 255f).toInt() and 0xFF
        val ir = (r.coerceIn(0f, 1f) * 255f).toInt() and 0xFF
        val ig = (g.coerceIn(0f, 1f) * 255f).toInt() and 0xFF
        val ib = (b.coerceIn(0f, 1f) * 255f).toInt() and 0xFF
        return (ia shl 24) or (ir shl 16) or (ig shl 8) or ib
    }

    private const val TWO_PI = (Math.PI * 2.0).toFloat()
}
