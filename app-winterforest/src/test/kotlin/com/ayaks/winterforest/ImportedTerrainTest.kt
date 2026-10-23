package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.world.ImportedTerrain
import com.ayaks.winterforest.domain.world.WorldPopulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedTerrainTest {

    @Test
    fun `grid size matches and sampler stays in game range`() {
        assertEquals(ImportedTerrain.GRID * ImportedTerrain.GRID, ImportedTerrain.HEIGHTS.size)
        for (i in ImportedTerrain.HEIGHTS.indices step 37) {
            val h = ImportedTerrain.HEIGHTS[i]
            assertTrue("height out of range: $h", h in 0f..6.5f)
        }
        val h0 = ImportedTerrain.heightAt(0f, 0f)
        val h1 = ImportedTerrain.heightAt(37.5f, -21.25f)
        val h2 = ImportedTerrain.heightAt(47.9f, -47.9f)
        assertTrue("h0=$h0", h0 in 0f..6.5f)
        assertTrue("h1=$h1", h1 in 0f..6.5f)
        assertTrue("h2=$h2", h2 in 0f..6.5f)
    }

    @Test
    fun `base clearing terrain height matches imported map after camp flatten`() {
        // Центр лагеря принудительно выровнен (raw * flatten)
        assertEquals(0f, WorldPopulator.terrainHeight(LocationId.BASE_CLEARING, 0f, 0f), 1e-4f)
        // Дальше от центра высоты положительны и согласованы с картой
        val x = 33f; val z = -19f
        val flat = (((x * x + z * z).toDouble().let { kotlin.math.sqrt(it) }.toFloat() - 6.2f) / 8.0f).coerceIn(0f, 1f)
        val expected = ImportedTerrain.heightAt(x, z) * flat
        assertEquals(expected, WorldPopulator.terrainHeight(LocationId.BASE_CLEARING, x, z), 1e-3f)
    }
}
