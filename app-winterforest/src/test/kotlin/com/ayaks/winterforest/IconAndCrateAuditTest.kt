package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.SpecialLootId
import com.ayaks.winterforest.domain.model.WorldObjectKind
import com.ayaks.winterforest.domain.world.WorldPopulator
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Аудит ссылок на иконки и военных ящиков (§7.5).
 *
 * Раньше `specialLootIconPath` ссылался на `icon_soldier_ration.png`,
 * `icon_hunting_knife.png` и `icon_first_aid_kit.png`, которых НЕ было на диске:
 * иконки молча не рисовались. Тест сканирует все строковые литералы
 * «icons/….png» в исходниках и требует файл на диске, а также проверяет,
 * что военных ящиков на карте осталось три (по одному на осмысленную точку
 * маршрута) и у каждого гарантированный уникальный лут.
 */
class IconAndCrateAuditTest {

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "settings.gradle.kts").exists()) cwd else cwd.parentFile
    }

    private fun mainKotlinSources(): List<File> {
        val root = File(repoRoot(), "app-winterforest/src/main/kotlin")
        val out = ArrayList<File>()
        root.walkTopDown().forEach { if (it.isFile && it.name.endsWith(".kt")) out += it }
        return out
    }

    @Test
    fun `every icon reference in kotlin sources points to an existing asset`() {
        val pattern = Regex("\"(icons/[A-Za-z0-9_]+\\.png)\"")
        val refs = LinkedHashSet<String>()
        for (src in mainKotlinSources()) {
            for (match in pattern.findAll(src.readText())) {
                refs += match.groupValues[1]
            }
        }
        assertTrue("в исходниках не осталось ссылок на иконки", refs.isNotEmpty())
        for (ref in refs) {
            val file = File(repoRoot(), "app-winterforest/src/main/assets/$ref")
            assertTrue("MISSING icon: $ref (ссылка из кода)", file.isFile)
            assertTrue("icon too small: $ref", file.length() > 512L)
        }
    }

    @Test
    fun `every special loot has its own icon file on disk`() {
        for (id in SpecialLootId.entries) {
            val file = File(repoRoot(), "app-winterforest/src/main/assets/${id.iconPath}")
            assertTrue("${id.name}: нет иконки ${id.iconPath}", file.isFile)
        }
        // у предметов разные иконки: иначе в рюкзаке их не различить
        val paths = SpecialLootId.entries.map { it.iconPath }
        assertEquals("иконки спецлута дублируются", paths.size, paths.toSet().size)
    }

    @Test
    fun `military crates are rare and carry guaranteed unique loot`() {
        val crates = LocationId.entries.flatMap { loc ->
            WorldPopulator.generateLocationNodes(loc).filter { it.kind == WorldObjectKind.MILITARY_CRATE }
        }
        // было 10 ящиков на карту (2 на район) — лут обесценивался
        assertTrue("военных ящиков слишком много: ${crates.size}", crates.size in 1..3)
        for (crate in crates) {
            assertTrue(
                "ящик ${crate.id} без гарантированного лута",
                crate.specialLootId != null
            )
        }
        val loots = crates.mapNotNull { it.specialLootId }
        assertEquals("лут ящиков дублируется", loots.size, loots.toSet().size)
        // лут ящиков — расходники/оружие, а не постоянные реликвии исследования
        for (loot in loots) {
            assertTrue("${loot.name} не должен быть постоянной реликвией", !loot.persistsAcrossRuns)
        }
    }
}
