package com.ayaks.winterforest.presentation.gl

import com.ayaks.winterforest.domain.model.LocationPalette
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

enum class ProceduralTextureId {
    SNOW,
    WOOD_BARK,
    PINE_FOLIAGE,
    STONE,
    CLOTH,
    ICE,
    TIMBER,
    FIRE_EMBER
}

/**
 * Процедурный генератор чётких 2D-текстур (шум Перлина / FBM + ячеистый шум Вороного)
 * для OpenGL ES 2.0/3.0. Не использует внешние файлы и никогда не создаёт однотонные заливки.
 */
object ProceduralTextures {

    const val DEFAULT_TEXTURE_SIZE = 128

    fun generateRgbaBuffer(
        id: ProceduralTextureId,
        palette: LocationPalette,
        size: Int = DEFAULT_TEXTURE_SIZE,
        seed: Int = 1337
    ): ByteBuffer {
        val argb = generateArgbPixels(id, palette, size, seed)
        val buffer = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        for (px in argb) {
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            val a = (px ushr 24) and 0xFF
            buffer.put(r.toByte())
            buffer.put(g.toByte())
            buffer.put(b.toByte())
            buffer.put(a.toByte())
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

        for (y in 0 until size) {
            val v = y.toFloat() / size.toFloat()
            for (x in 0 until size) {
                val u = x.toFloat() / size.toFloat()
                val idx = y * size + x

                var r: Float
                var g: Float
                var b: Float
                var a = 1f

                when (id) {
                    ProceduralTextureId.SNOW -> {
                        // Ветровые заструги FBM + микрокристаллы Вороного
                        val drift = fbm(u * 5f, v * 9f, octaves = 5, seed = seed)
                        val ripple = 0.5f + 0.5f * sin((u * 3.0f + v * 15.0f + drift * 2.2f) * Math.PI.toFloat() * 2f)
                        val crystal = voronoiCell(u * 26f, v * 26f, seed + 41)
                        val glint = if (crystal.first < 0.14f) 0.14f else 0f
                        val shade = (0.76f + 0.16f * drift + 0.08f * ripple + glint).coerceIn(0f, 1.08f)
                        r = (snowTintR * shade).coerceIn(0f, 1f)
                        g = (snowTintG * shade).coerceIn(0f, 1f)
                        b = ((snowTintB * 1.02f) * shade).coerceIn(0f, 1f)
                    }

                    ProceduralTextureId.WOOD_BARK -> {
                        // Вытянутые по вертикали трещины коры + иней в расщелинах
                        val ridge = fbm(u * 14f, v * 3.2f, octaves = 5, seed = seed + 101)
                        val fissures = voronoiCell(u * 12f, v * 3.5f, seed + 211).second
                        val barkTone = (0.30f + 0.42f * ridge + 0.24f * fissures).coerceIn(0.10f, 1f)
                        val frost = ((ridge - 0.66f) * 2.2f).coerceIn(0f, 0.45f)
                        r = (0.36f * barkTone + frost * 0.75f).coerceIn(0f, 1f)
                        g = (0.25f * barkTone + frost * 0.80f).coerceIn(0f, 1f)
                        b = (0.18f * barkTone + frost * 0.88f).coerceIn(0f, 1f)
                    }

                    ProceduralTextureId.PINE_FOLIAGE -> {
                        // Хвойные лапы + плотная снежная шапка на верхних ветвях
                        val needles = fbm(u * 18f, v * 18f, octaves = 4, seed = seed + 307)
                        val tuft = voronoiCell(u * 15f, v * 15f, seed + 313).second
                        val snowCap = ((0.46f - v + (needles - 0.5f) * 0.36f) * 3.1f).coerceIn(0f, 1f)
                        val greenBase = (0.34f + 0.48f * needles + 0.22f * tuft).coerceIn(0.12f, 1f)
                        r = lerp(0.09f * greenBase, 0.90f * snowTintR, snowCap)
                        g = lerp(0.29f * greenBase, 0.94f * snowTintG, snowCap)
                        b = lerp(0.20f * greenBase, 0.98f * snowTintB, snowCap)
                    }

                    ProceduralTextureId.STONE -> {
                        // Каменные грани Вороного с прожилками руды/кварца
                        val cell = voronoiCell(u * 9f, v * 9f, seed + 409)
                        val grain = fbm(u * 22f, v * 22f, octaves = 4, seed = seed + 419)
                        val crackEdge = (cell.second * 1.35f).coerceIn(0.18f, 1f)
                        val mineral = if (grain > 0.72f) 0.18f else 0f
                        val luma = (0.30f + 0.36f * crackEdge + 0.20f * grain + mineral).coerceIn(0.10f, 1f)
                        r = (luma * 0.88f).coerceIn(0f, 1f)
                        g = (luma * 0.91f).coerceIn(0f, 1f)
                        b = (luma * 0.97f).coerceIn(0f, 1f)
                    }

                    ProceduralTextureId.CLOTH -> {
                        // Переплетение грубой шерстяной ткани и мешковины
                        val warp = 0.5f + 0.5f * sin(u * size.toFloat() * 0.55f)
                        val weft = 0.5f + 0.5f * sin(v * size.toFloat() * 0.55f)
                        val weave = if (((x / 3 + y / 3) and 1) == 0) warp else weft
                        val wear = fbm(u * 8f, v * 8f, octaves = 3, seed = seed + 503)
                        val tone = (0.42f + 0.34f * weave + 0.24f * wear).coerceIn(0.15f, 1f)
                        r = (0.68f * tone).coerceIn(0f, 1f)
                        g = (0.43f * tone).coerceIn(0f, 1f)
                        b = (0.30f * tone).coerceIn(0f, 1f)
                    }

                    ProceduralTextureId.ICE -> {
                        // Озёрный лёд с трещинами Вороного и бирюзовым рассеиванием
                        val fracture = voronoiCell(u * 8f, v * 8f, seed + 607)
                        val crackLine = (1f - (fracture.second * 6.5f).coerceIn(0f, 1f))
                        val depthNoise = fbm(u * 10f, v * 10f, octaves = 4, seed = seed + 613)
                        r = (0.52f + 0.20f * depthNoise + 0.42f * crackLine).coerceIn(0f, 1f)
                        g = (0.76f + 0.14f * depthNoise + 0.24f * crackLine).coerceIn(0f, 1f)
                        b = (0.90f + 0.08f * depthNoise + 0.12f * crackLine).coerceIn(0f, 1f)
                    }

                    ProceduralTextureId.TIMBER -> {
                        // Брёвна зимовья и ящиков с годовыми кольцами и пазами
                        val plankRow = (v * 5f)
                        val seam = kotlin.math.abs(plankRow - floor(plankRow) - 0.5f) * 2f
                        val seamShadow = if (seam > 0.88f) 0.42f else 1.0f
                        val grain = fbm(u * 5f, v * 26f, octaves = 4, seed = seed + 701)
                        val tone = ((0.44f + 0.40f * grain) * seamShadow).coerceIn(0.12f, 1f)
                        r = (0.58f * tone).coerceIn(0f, 1f)
                        g = (0.39f * tone).coerceIn(0f, 1f)
                        b = (0.24f * tone).coerceIn(0f, 1f)
                    }

                    ProceduralTextureId.FIRE_EMBER -> {
                        // Язык пламени и тлеющие угли костра / смоляного факела
                        val dx = (u - 0.5f) * 2f
                        val dy = (1f - v)
                        val flameTurb = fbm(u * 7f, v * 11f, octaves = 4, seed = seed + 809)
                        val mask = (1.15f - kotlin.math.abs(dx) * (1.25f + (1f - dy)) - (1f - dy) * 0.35f + flameTurb * 0.35f)
                            .coerceIn(0f, 1f)
                        r = (1.0f * mask).coerceIn(0f, 1f)
                        g = (0.72f * mask * mask + 0.18f * mask).coerceIn(0f, 1f)
                        b = (0.22f * mask * mask * mask).coerceIn(0f, 1f)
                        a = (mask * 1.25f).coerceIn(0.05f, 1f)
                    }
                }

                pixels[idx] = packArgb(a, r, g, b)
            }
        }
        return pixels
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

    /**
     * Ячеистый 2D-шум Вороного: возвращает (расстояние до центра F1, расстояние до ребра F2 - F1).
     */
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
        val angle = hashFloat(ix, iy, seed) * (Math.PI.toFloat() * 2f)
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
}
