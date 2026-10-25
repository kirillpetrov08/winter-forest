package com.ayaks.winterforest

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

/**
 * Отделка моделей зверей: правильные зоологические размеры (олень крупнее волка),
 * шерстяные текстуры с настоящей детализацией (не «аэрограф»).
 */
class AnimalModelsTest {

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "settings.gradle.kts").exists()) cwd else cwd.parentFile
    }

    private fun bbox(obj: File): FloatArray {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (line in obj.readLines()) {
            if (line.startsWith("v ")) {
                val p = line.trim().split(Regex("\\s+"))
                val x = p[1].toFloat(); val y = p[2].toFloat(); val z = p[3].toFloat()
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            }
        }
        return floatArrayOf(minX, maxX, minY, maxY, minZ, maxZ)
    }

    /**
     * Минимальный PNG-декодер (8 бит, RGB/RGBA, без interlace) на java.util.zip:
     * javax.imageio недоступен в юнит-тестах Android (компиляция против android.jar).
     * Возвращает среднеквадратичное отклонение яркости по пикселям.
     */
    private fun pngLumaStd(file: File): Double {
        val bytes = file.readBytes()
        require(bytes.size > 8 && bytes[0] == 0x89.toByte()) { "not a png: $file" }
        var pos = 8
        var width = 0
        var height = 0
        var channels = 0
        val idat = ByteArrayOutputStream()
        while (pos + 8 <= bytes.size) {
            val len = ((bytes[pos].toInt() and 0xFF) shl 24) or ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            val type = String(bytes, pos + 4, 4)
            val dataPos = pos + 8
            when (type) {
                "IHDR" -> {
                    width = ((bytes[dataPos].toInt() and 0xFF) shl 24) or ((bytes[dataPos + 1].toInt() and 0xFF) shl 16) or
                        ((bytes[dataPos + 2].toInt() and 0xFF) shl 8) or (bytes[dataPos + 3].toInt() and 0xFF)
                    height = ((bytes[dataPos + 4].toInt() and 0xFF) shl 24) or ((bytes[dataPos + 5].toInt() and 0xFF) shl 16) or
                        ((bytes[dataPos + 6].toInt() and 0xFF) shl 8) or (bytes[dataPos + 7].toInt() and 0xFF)
                    val colorType = bytes[dataPos + 9].toInt() and 0xFF
                    channels = when (colorType) {
                        2 -> 3
                        6 -> 4
                        else -> throw IllegalArgumentException("unsupported colorType $colorType")
                    }
                }
                "IDAT" -> idat.write(bytes, dataPos, len)
                "IEND" -> break
            }
            pos = dataPos + len + 4
        }
        val raw = inflate(idat.toByteArray())
        val stride = width * channels
        val out = ByteArray(stride * height)
        var prev = ByteArray(stride)
        var o = 0
        for (y in 0 until height) {
            val filter = raw[y * (stride + 1)].toInt() and 0xFF
            val row = raw.copyOfRange(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride)
            for (i in 0 until stride) {
                val a = if (i >= channels) row[i - channels].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= channels) prev[i - channels].toInt() and 0xFF else 0
                val x = row[i].toInt() and 0xFF
                row[i] = (when (filter) {
                    0 -> x
                    1 -> x + a
                    2 -> x + b
                    3 -> x + (a + b) / 2
                    4 -> {
                        val p = a + b - c
                        val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
                        x + (if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c)
                    }
                    else -> x
                } and 0xFF).toByte()
            }
            System.arraycopy(row, 0, out, o, stride)
            o += stride
            prev = row
        }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * stride + x * channels
                val l = 0.299 * (out[i].toInt() and 0xFF) + 0.587 * (out[i + 1].toInt() and 0xFF) +
                    0.114 * (out[i + 2].toInt() and 0xFF)
                sum += l; sumSq += l * l; n++
            }
        }
        val mean = sum / n
        return Math.sqrt(sumSq / n - mean * mean)
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(data)
        val buf = ByteArray(1 shl 16)
        val out = ByteArrayOutputStream()
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && inf.needsInput()) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }

    @Test
    fun `deer is a big stag and wolf stays wolf-sized`() {
        val root = repoRoot()
        val deer = bbox(File(root, "app-winterforest/src/main/assets/models/deer_body/mesh.obj"))
        val wolf = bbox(File(root, "app-winterforest/src/main/assets/models/wolf_body/mesh.obj"))
        val deerLen = deer[5] - deer[4]
        val deerHigh = deer[3] - deer[2]
        val wolfLen = wolf[5] - wolf[4]
        val wolfHigh = wolf[3] - wolf[2]
        // северный олень: длина ~1.5 м, в холке с рогами ~1.7 м
        assertTrue("deer too short: $deerLen", deerLen >= 1.45f && deerLen <= 1.85f)
        assertTrue("deer too low: $deerHigh", deerHigh >= 1.60f && deerHigh <= 1.95f)
        // волк: ~1.5–1.7 м с хвостом, в холке ~0.85–0.95 м
        assertTrue("wolf wrong length: $wolfLen", wolfLen >= 1.45f && wolfLen <= 1.75f)
        assertTrue("wolf too tall: $wolfHigh", wolfHigh >= 0.78f && wolfHigh <= 1.00f)
        // олень заметно выше волка
        assertTrue("deer must tower over wolf ($deerHigh vs $wolfHigh)", deerHigh > wolfHigh * 1.5f)
    }

    /** Вершины OBJ: [x, y, z] по порядку строк. */
    private fun vertices(obj: File): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        for (line in obj.readLines()) {
            if (line.startsWith("v ")) {
                val p = line.trim().split(Regex("\\s+"))
                out.add(floatArrayOf(p[1].toFloat(), p[2].toFloat(), p[3].toFloat()))
            }
        }
        return out
    }

    /**
     * Олень должен быть ОБЪЁМНЫМ и симметричным. Исходный RedDeer из
     * «The Deer Rigger» поставлялся половиной туши (зеркальный модификатор не
     * запечён): в игре это выглядело как плоский бумажный олень, а разрез по
     * связности давал только две ноги — mesh_leg2/mesh_leg3 были пустыми и
     * никогда не анимировались. Упаковщик теперь отражает геометрию через
     * плоскость X=0 и сшивает шов, тест это фиксирует.
     */
    @Test
    fun `deer is a solid mirrored animal with four real legs`() {
        val root = repoRoot()
        val dir = File(root, "app-winterforest/src/main/assets/models/deer_body")
        val verts = vertices(File(dir, "mesh.obj"))
        val box = bbox(File(dir, "mesh.obj"))
        val width = box[1] - box[0]
        val length = box[5] - box[4]

        // «бумажный» олень: вся геометрия по одну сторону от плоскости зеркала
        val left = verts.count { it[0] < -0.01f }
        val right = verts.count { it[0] > 0.01f }
        assertTrue("deer is one-sided: left=$left right=$right", left > verts.size / 4 && right > verts.size / 4)

        // ширина туши взрослого марала: не плоская (0.06 м) и не бочка (0.80 м)
        val ratio = width / length
        assertTrue("deer silhouette wrong: width=$width length=$length", ratio > 0.18f && ratio < 0.42f)

        // bounding box симметричен относительно продольной оси
        assertTrue("deer bbox off-center: ${box[0]}..${box[1]}", Math.abs(box[0] + box[1]) < 0.01f)

        // четыре ноги существуют, достают до земли и не являются огрызками
        for (k in 0..3) {
            val leg = File(dir, "mesh_leg$k.obj")
            assertTrue("missing mesh_leg$k", leg.isFile)
            val legVerts = vertices(leg)
            assertTrue("mesh_leg$k is empty", legVerts.size >= 60)
            val legBox = bbox(leg)
            assertTrue("mesh_leg$k does not reach the ground: minY=${legBox[2]}", legBox[2] < 0.05f)
            assertTrue("mesh_leg$k is too short: ${legBox[3] - legBox[2]}", legBox[3] - legBox[2] > 0.30f)
        }
        // передние и задние пары разнесены по длине туши
        val frontZ = bbox(File(dir, "mesh_leg0.obj")).let { (it[4] + it[5]) / 2f }
        val backZ = bbox(File(dir, "mesh_leg2.obj")).let { (it[4] + it[5]) / 2f }
        assertTrue("legs are not spread along the body: front=$frontZ back=$backZ", frontZ - backZ > 0.3f)
    }

    @Test
    fun `fur textures carry real detail not flat gradients`() {
        val root = repoRoot()
        for (id in listOf("deer_body", "wolf_body")) {
            val png = File(root, "app-winterforest/src/main/assets/models/$id/diff.png")
            assertTrue("missing $id texture", png.isFile)
            val std = pngLumaStd(png)
            assertTrue("$id texture is flat (std=$std)", std > 8.0)
        }
    }
}
