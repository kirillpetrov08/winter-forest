package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.presentation.gl.AssetProps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Консистентность импортированного реквизита: каждая расстановка ссылается на
 * загруженную модель, все локации декорированы, дубликатов id нет.
 */
class AssetPropsTest {

    @Test
    fun `every placement references a known model id`() {
        val known = AssetProps.ALL_MODEL_IDS.toSet()
        for (loc in LocationId.entries) {
            for (p in AssetProps.forLocation(loc)) {
                assertTrue(
                    "placement ${p.modelId} at $loc not in ALL_MODEL_IDS",
                    p.modelId in known
                )
            }
        }
    }

    @Test
    fun `model ids are unique and entity models are registered`() {
        assertEquals(AssetProps.ALL_MODEL_IDS.size, AssetProps.ALL_MODEL_IDS.toSet().size)
        val known = AssetProps.ALL_MODEL_IDS.toSet()
        for (id in AssetProps.ENTITY_MODEL_IDS) {
            assertTrue("entity $id missing from ALL_MODEL_IDS", id in known)
        }
    }

    @Test
    fun `all locations carry decor and coordinates stay on the map`() {
        for (loc in LocationId.entries) {
            val placements = AssetProps.forLocation(loc)
            assertTrue("location $loc has no props", placements.isNotEmpty())
            for (p in placements) {
                assertTrue("x out of bounds at $loc", Math.abs(p.x) < 48f)
                assertTrue("z out of bounds at $loc", Math.abs(p.z) < 48f)
                assertTrue("scale invalid at $loc", p.scale > 0.05f && p.scale < 8f)
            }
        }
    }

    @Test
    fun `new imported models are placed in their landmark locations`() {
        val base = AssetProps.forLocation(LocationId.BASE_CLEARING).map { it.modelId }
        assertTrue(base.contains("workbench"))
        assertTrue(base.contains("cache_crate"))
        val cabin = AssetProps.forLocation(LocationId.HUNTERS_CABIN).map { it.modelId }
        assertTrue(cabin.contains("gate"))
        assertTrue(cabin.contains("lantern"))
        val lake = AssetProps.forLocation(LocationId.FROZEN_LAKE).map { it.modelId }
        assertTrue(lake.contains("cache_crate"))
        val mine = AssetProps.forLocation(LocationId.ABANDONED_MINE).map { it.modelId }
        assertTrue(mine.contains("gate"))
        assertTrue(mine.contains("cache_crate"))
        assertTrue(mine.contains("lantern"))
    }

    @Test
    fun `tool models live in player hands not on the floor`() {
        // Топор/кирка/копьё рисуются от первого лица в руках — на земле их быть не должно
        val floorTools = setOf("wooden_axe", "pickaxe", "spear")
        for (loc in LocationId.entries) {
            for (p in AssetProps.forLocation(loc)) {
                assertTrue(
                    "tool ${p.modelId} placed on the floor at $loc — must be held instead",
                    p.modelId !in floorTools
                )
            }
        }
        // Но сами модели и их текстуры должны остаться загруженными (руки игрока)
        val known = AssetProps.ALL_MODEL_IDS.toSet()
        for (id in floorTools) {
            assertTrue("tool model $id missing from ALL_MODEL_IDS", id in known)
        }
    }
}
