package com.ayaks.winterforest

import com.ayaks.winterforest.data.db.ActiveRunEntity
import com.ayaks.winterforest.data.db.BaseProgressEntity
import com.ayaks.winterforest.data.db.GameProgressDao
import com.ayaks.winterforest.data.repository.FileBackedGameRepository
import com.ayaks.winterforest.data.repository.RoomGameRepository
import com.ayaks.winterforest.data.preferences.SettingsDataStore
import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.SpecialLootId
import com.ayaks.winterforest.domain.repository.LoadSaveResult
import com.ayaks.winterforest.domain.rules.SurvivalEngine
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
