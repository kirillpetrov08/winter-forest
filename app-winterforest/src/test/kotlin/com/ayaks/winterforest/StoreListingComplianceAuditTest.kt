package com.ayaks.winterforest

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreListingComplianceAuditTest {

    private fun resolveRepoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "settings.gradle.kts").exists()) cwd else cwd.parentFile
    }

    private fun readPngDimensionsAndColorType(file: File): Triple<Int, Int, Int> {
        assertTrue("PNG-файл должен существовать: ${file.path}", file.isFile)
        val header = file.inputStream().use { stream ->
            val bytes = ByteArray(26)
            assertEquals(26, stream.read(bytes))
            bytes
        }
        assertEquals(0x89.toByte(), header[0])
        assertEquals(0x50.toByte(), header[1])
        assertEquals(0x4E.toByte(), header[2])
        assertEquals(0x47.toByte(), header[3])
        val width = ByteBuffer.wrap(header, 16, 4).int
        val height = ByteBuffer.wrap(header, 20, 4).int
        val colorType = header[25].toInt() and 0xFF
        return Triple(width, height, colorType)
    }

    @Test
    fun `manifest app ads privacy policy and landscape 1920x1080 store png graphics comply with store and ad rules`() {
        val root = resolveRepoRoot()

        // 1. Проверка docs/app-ads.txt
        val appAds = File(root, "docs/app-ads.txt").readText(Charsets.UTF_8).trim()
        assertEquals("yandex.com, 110347355, DIRECT", appAds)

        // 2. Проверка AndroidManifest.xml (автоматический горизонтальный sensorLandscape + запрет автоинициализации РСЯ)
        val manifest = File(root, "app-winterforest/src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertTrue(manifest.contains("com.google.android.gms.permission.AD_ID"))
        assertTrue(
            "Приложение обязано запускаться и переворачиваться в горизонтальном режиме sensorLandscape",
            manifest.contains("android:screenOrientation=\"sensorLandscape\"")
        )
        assertTrue(manifest.contains("com.yandex.mobile.ads.AUTOMATIC_SDK_INITIALIZATION"))
        assertTrue(
            "Геолокационные разрешения должны быть явно удалены через tools:node=\"remove\"",
            manifest.contains("ACCESS_FINE_LOCATION") && manifest.contains("tools:node=\"remove\"")
        )
        assertFalse("Разрешение READ_MEDIA_IMAGES не используется и не должно запрашиваться", manifest.contains("READ_MEDIA_IMAGES"))
        assertFalse("Разрешение CAMERA не используется и не должно запрашиваться", manifest.contains("android.permission.CAMERA"))

        // 3. Проверка store/privacy-policy.md и метаданных разработчика Ayaks
        val privacy = File(root, "store/privacy-policy.md").readText(Charsets.UTF_8)
        assertTrue(privacy.contains("Ayaks"))
        assertTrue(privacy.contains("kirillpetrov08@gmail.com"))
        assertTrue(privacy.contains("Yandex Mobile Ads"))

        // 4. Проверка отсутствия зашитых реальных РСЯ ID и паролей в исходном коде src/main
        val mainSourceRoot = File(root, "app-winterforest/src/main")
        val realYandexIdRegex = Regex("""R-M-\d{4,}-\d+""")
        mainSourceRoot.walkTopDown().filter { it.isFile }.forEach { srcFile ->
            val content = srcFile.readText(Charsets.UTF_8)
            assertFalse(
                "В исходниках не должно быть зашитых реальных РСЯ ID (${srcFile.path})",
                realYandexIdRegex.containsMatchIn(content)
            )
        }

        // 4b. Боевые блоки РСЯ (кабинет: приложение winter forest, ID 20264124) зашиты
        // СТРОГО в build config (build.gradle.kts), а не в runtime-исходниках: баннер + вознаграждение,
        // без демо-заглушек. local.properties/env могут переопределить при необходимости.
        val buildGradle = File(root, "app-winterforest/build.gradle.kts").readText(Charsets.UTF_8)
        assertTrue(buildGradle.contains("\"R-M-20264124-1\""))
        assertTrue(buildGradle.contains("\"R-M-20264124-2\""))
        val wiredIds = realYandexIdRegex.findAll(buildGradle).map { it.value }.toSet()
        assertEquals("В build config должны быть ровно два боевых блока РСЯ", setOf("R-M-20264124-1", "R-M-20264124-2"), wiredIds)
        assertFalse("Демо-блоки РСЯ не должны подмешиваться в сборку", buildGradle.contains("demo-banner") || buildGradle.contains("demo-rewarded"))

        // 5. Проверка размеров и цветовых форматов графики магазина (icon 512x512 RGB, feature 1024x500 RGB, 7 горизонтальных скриншотов 1920x1080 RGB)
        val (iconW, iconH, iconColor) = readPngDimensionsAndColorType(File(root, "store/icon-512x512.png"))
        assertEquals(512, iconW)
        assertEquals(512, iconH)
        assertEquals("Иконка Google Play / RuStore должна быть 24-bit RGB без альфа-канала (colorType = 2)", 2, iconColor)

        val (featW, featH, featColor) = readPngDimensionsAndColorType(File(root, "store/feature-graphic-1024x500.png"))
        assertEquals(1024, featW)
        assertEquals(500, featH)
        assertEquals(2, featColor)

        val screenshotsDir = File(root, "store/screenshots")
        val pngScreenshots = screenshotsDir.listFiles { f -> f.extension == "png" }?.sortedBy { it.name }.orEmpty()
        assertTrue("Должно быть не менее 6 горизонтальных скриншотов (фактически ${pngScreenshots.size})", pngScreenshots.size >= 6)
        for (shot in pngScreenshots) {
            val (w, h, color) = readPngDimensionsAndColorType(shot)
            assertEquals("Ширина горизонтального скриншота ${shot.name} должна быть 1920 px", 1920, w)
            assertEquals("Высота горизонтального скриншота ${shot.name} должна быть 1080 px", 1080, h)
            assertEquals(2, color)
            assertTrue("Размер скриншота ${shot.name} должен быть непустым и <= 5 МБ", shot.length() in 20_000L..5_000_000L)
        }
    }

    @Test
    fun `baked 2048px asset textures deliver 200+ mb of fully detailed item and location graphics`() {
        val root = resolveRepoRoot()
        val texDir = File(root, "app-winterforest/src/main/assets/textures")
        assertTrue("Каталог assets/textures должен существовать", texDir.isDirectory)
        val pngs = texDir.listFiles { f -> f.isFile && f.extension.equals("png", ignoreCase = true) } ?: emptyArray()
        assertTrue(
            "Должно быть не меньше 30 текстур (каждый материал, предмет и локация), сейчас ${pngs.size}",
            pngs.size >= 30
        )
        val totalBytes = pngs.sumOf { it.length() }
        val totalMb = totalBytes / (1024L * 1024L)
        assertTrue("Графика в приложении должна весить не менее 200 МБ, сейчас $totalMb МБ", totalBytes >= 200L * 1024L * 1024L)

        // Каждая текстура — полноценные 2048×2048 RGB (деталь каждого материала/локации)
        for (png in pngs) {
            val (w, h, color) = readPngDimensionsAndColorType(png)
            assertTrue("Текстура ${png.name} должна быть не меньше 2048px, сейчас ${w}x$h", w >= 2048 && h >= 2048)
            assertEquals("Текстура ${png.name} должна быть RGB/RGBA", true, color == 2 || color == 6)
        }

        // Обязательный набор материалов и локаций
        for (required in listOf(
            "snow_soft", "snow_crust", "ice_cracked", "bark_pine", "bark_birch", "pine_needles",
            "stone_granite", "stone_mossy", "wood_plank", "wood_log_end", "wood_carved",
            "metal_worn", "metal_rust", "fur_wolf", "fur_deer", "cloth_canvas", "cloth_wool",
            "ember_coal", "ember_glow", "flame_streak", "leather_tanned", "meat_raw", "resin_amber", "crystal_ice", "herb_leaf",
            "dirt_frozen", "grass_dry", "bone_old",
            "item_ore_raw", "item_meal_stew", "item_rope_coil", "item_torch_wrap", "item_map_paper", "item_herbs_bundle",
            "loc_sos_clearing", "loc_frozen_lake", "loc_pine_forest", "loc_abandoned_mine", "loc_cliff_path", "loc_winter_valley"
        )) {
            assertTrue("Текстура $required.png обязательна", File(texDir, "$required.png").isFile)
        }
    }
}
