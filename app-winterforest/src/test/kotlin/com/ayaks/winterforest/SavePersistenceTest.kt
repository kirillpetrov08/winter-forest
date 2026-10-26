package com.ayaks.winterforest

import com.ayaks.winterforest.data.db.ActiveRunEntity
import com.ayaks.winterforest.data.db.SavePayloadCodec
import com.ayaks.winterforest.data.db.BaseProgressEntity
import com.ayaks.winterforest.data.db.GameProgressDao
import com.ayaks.winterforest.data.repository.FileBackedGameRepository
import com.ayaks.winterforest.data.repository.RoomGameRepository
import com.ayaks.winterforest.data.preferences.SettingsDataStore
import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.SpecialLootId
import com.ayaks.winterforest.domain.model.WildlifeKind
import com.ayaks.winterforest.domain.repository.LoadSaveResult
import com.ayaks.winterforest.domain.rules.SurvivalEngine
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavePersistenceTest {

    @Test
    fun `file backed repository saves and restores full base and active run state`() = runTest {
        val tempDir = Files.createTempDirectory("wf_save_test").toFile()
        try {
            val repo = FileBackedGameRepository(tempDir)
            val upgradedBase = BaseProgress(
                shelterLevel = 3,
                campfireLevel = 4,
                workbenchLevel = 2,
                storageResources = mapOf(ResourceType.WOOD to 18, ResourceType.IRON to 6),
                persistentSpecialLoot = setOf(SpecialLootId.OLD_MAP, SpecialLootId.THERMOS),
                thermosInstalledOnBase = true,
                bestDaysSurvived = 5,
                lifetimeWoodGathered = 42
            )
            val run = SurvivalEngine.createNewRun(upgradedBase).copy(
                currentLocation = LocationId.DENSE_SPRUCE,
                health = 84f,
                hunger = 71f,
                warmth = 66f,
                daysSurvived = 2,
                totalResourcesGathered = 27
            )

            repo.saveCheckpoint(upgradedBase, run)

            val freshRepoInstance = FileBackedGameRepository(tempDir)
            val loaded = freshRepoInstance.loadGameSnapshot()
            assertTrue(loaded is LoadSaveResult.Loaded)
            loaded as LoadSaveResult.Loaded

            assertEquals(3, loaded.baseProgress.shelterLevel)
            assertEquals(4, loaded.baseProgress.campfireLevel)
            assertEquals(5, loaded.baseProgress.bestDaysSurvived)
            assertTrue(loaded.baseProgress.thermosInstalledOnBase)
            assertTrue(SpecialLootId.OLD_MAP in loaded.baseProgress.persistentSpecialLoot)

            val restoredRun = loaded.activeRun
            assertNotNull(restoredRun)
            assertEquals(LocationId.DENSE_SPRUCE, restoredRun!!.currentLocation)
            assertEquals(84f, restoredRun.health, 0.01f)
            assertEquals(27, restoredRun.totalResourcesGathered)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `corrupted save file or bad crc checksum returns Corrupted safely without crashing`() = runTest {
        val tempDir = Files.createTempDirectory("wf_corrupt_test").toFile()
        try {
            val repo = FileBackedGameRepository(tempDir)
            val base = BaseProgress(shelterLevel = 2)
            val run = SurvivalEngine.createNewRun(base)
            repo.saveCheckpoint(base, run)

            // Повреждаем байты внутри файла сохранения забега (нарушение CRC32 / JSON)
            val runFile = File(tempDir, "active_run.json")
            runFile.writeText("{corrupted_json_payload!!!", Charsets.UTF_8)

            val resultAfterJsonDamage = repo.loadGameSnapshot()
            assertTrue(
                "Повреждённый JSON должен безопасно возвращать LoadSaveResult.Corrupted",
                resultAfterJsonDamage is LoadSaveResult.Corrupted
            )

            // Проверяем нарушение контрольной суммы в Room DAO слое
            val inMemoryDao = object : GameProgressDao {
                var baseRow: BaseProgressEntity? = BaseProgressEntity(
                    payloadJson = "{\"shelterLevel\":99}",
                    crc32 = 123456789L, // заведомо неверный CRC32
                    updatedAtEpochMs = 1L
                )
                var runRow: ActiveRunEntity? = null
                override suspend fun getBaseProgress() = baseRow
                override suspend fun upsertBaseProgress(entity: BaseProgressEntity) { baseRow = entity }
                override suspend fun clearBaseProgress() { baseRow = null }
                override suspend fun getActiveRun() = runRow
                override suspend fun upsertActiveRun(entity: ActiveRunEntity) { runRow = entity }
                override suspend fun clearActiveRun() { runRow = null }
            }
            val roomRepo = RoomGameRepository(inMemoryDao, SettingsDataStore.inMemoryForTesting())
            val daoLoadResult = roomRepo.loadGameSnapshot()
            assertTrue(
                "Несовпадение CRC32 в Room должно возвращать LoadSaveResult.Corrupted без падения",
                daoLoadResult is LoadSaveResult.Corrupted
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Удаление значения enum не должно «убивать» существующие сохранения:
     * CRC проверяется до десериализации, поэтому миграция выполняется после
     * подтверждения целостности и пересчитывает контрольную сумму.
     */
    @Test
    fun `legacy save with removed wildlife kind is migrated instead of corrupted`() = runTest {
        val tempDir = Files.createTempDirectory("wf_migrate_test").toFile()
        try {
            val repo = FileBackedGameRepository(tempDir)
            val base = BaseProgress(shelterLevel = 2)
            val run = SurvivalEngine.createNewRun(base)
            repo.saveCheckpoint(base, run)

            val runFile = File(tempDir, "active_run.json")
            val env = SavePayloadCodec.decodeEnvelope(runFile.readText(Charsets.UTF_8))
            assertTrue("ожидается мелкая дичь в стартовом забеге", env.bodyJson.contains("TAIGA_RAT"))

            // Имитируем сохранение, записанное версией с удалённым видом зайца
            val legacyBody = env.bodyJson.replace(
                "\"TAIGA_RAT\"",
                "\"" + SavePayloadCodec.LEGACY_HARE_KIND_NAME + "\""
            )
            assertFalse("подмена не сработала", legacyBody == env.bodyJson)
            val legacyCrc = SavePayloadCodec.computeCrc32(legacyBody)
            runFile.writeText(SavePayloadCodec.encodeEnvelope(legacyBody, legacyCrc), Charsets.UTF_8)

            val loaded = FileBackedGameRepository(tempDir).loadGameSnapshot()
            assertTrue("старое сохранение должно читаться, а не отбраковываться: $loaded",
                loaded is LoadSaveResult.Loaded)
            val migrated = (loaded as LoadSaveResult.Loaded).activeRun
            assertNotNull(migrated)
            val fauna = migrated!!.currentWildlife()
            assertTrue("фауна не должна потеряться при миграции", fauna.isNotEmpty())
            assertTrue(
                "удалённый вид должен переноситься в существующий: ${fauna.map { it.kind }.toSet()}",
                fauna.none { it.kind.name == SavePayloadCodec.LEGACY_HARE_KIND_NAME }
            )
            assertTrue(
                "мелкая дичь переносится в таёжную крысу",
                fauna.any { it.kind == WildlifeKind.TAIGA_RAT }
            )

            // Повреждённая контрольная сумма по-прежнему отбраковывается
            runFile.writeText(SavePayloadCodec.encodeEnvelope(legacyBody, legacyCrc + 7L), Charsets.UTF_8)
            val broken = FileBackedGameRepository(tempDir).loadGameSnapshot()
            assertTrue("с плохим CRC миграция не должна спасать файл", broken is LoadSaveResult.Corrupted)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * §7.6 «два одинаковых оленя»: у каждого вида фауны должна быть СВОЯ модель,
     * а в списке зверей локации не должно быть дублей одного вида сверх парного
     * количества стада.
     */
    @Test
    fun `every wildlife kind has its own model and no duplicated deer`() {
        // Удалённый вид зайца: отдельной модели у него не было, и рендерер
        // рисовал «зайца» моделью оленя — на карте стояли два одинаковых оленя.
        assertFalse(
            "удалённый вид фауны вернулся в enum",
            WildlifeKind.entries.any { it.name == SavePayloadCodec.LEGACY_HARE_KIND_NAME }
        )
        // у каждого вида — уникальный идентификатор модели ( wolf / deer / rat )
        val models = WildlifeKind.entries.map { kind ->
            when (kind) {
                WildlifeKind.TIMBER_WOLF -> "wolf_body"
                WildlifeKind.NORTHERN_DEER -> "deer_body"
                WildlifeKind.TAIGA_RAT -> "rat_body"
            }
        }
        assertEquals("видам фауны нужны разные модели", models.size, models.toSet().size)

        for (location in LocationId.entries) {
            val fauna = com.ayaks.winterforest.domain.world.WorldPopulator.generateLocationWildlife(location)
            assertTrue("$location без фауны", fauna.isNotEmpty())
            val deer = fauna.count { it.kind == WildlifeKind.NORTHERN_DEER }
            assertTrue("$location: слишком много оленей ($deer)", deer <= 2)
            val ids = fauna.map { it.id }
            assertEquals("$location: дубли id фауны", ids.size, ids.toSet().size)
        }
    }
}
