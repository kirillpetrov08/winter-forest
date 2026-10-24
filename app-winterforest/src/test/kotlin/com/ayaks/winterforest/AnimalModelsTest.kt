package com.ayaks.winterforest

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

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

    @Test
    fun `fur textures carry real detail not flat gradients`() {
        val root = repoRoot()
        for (id in listOf("deer_body", "wolf_body")) {
            val png = File(root, "app-winterforest/src/main/assets/models/$id/diff.png")
            assertTrue("missing $id texture", png.isFile)
            val img = ImageIO.read(png)
            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            for (y in 0 until img.height step 3) {
                for (x in 0 until img.width step 3) {
                    val c = img.getRGB(x, y)
                    val l = (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF))
                    sum += l; sumSq += l * l; n++
                }
            }
            val mean = sum / n
            val std = Math.sqrt(sumSq / n - mean * mean)
            assertTrue("$id texture is flat (std=$std)", std > 8.0)
        }
    }
}
