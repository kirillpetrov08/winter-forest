package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.Inventory
import com.ayaks.winterforest.domain.model.ResourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7.3: позиционная сетка рюкзака — перестановка ячеек перетаскиванием,
 * дырки сохраняются, битая раскладка из сейва нормализуется, а не роняет игру.
 */
class InventoryOrderTest {

    private fun stocked(): Inventory {
        var inv = Inventory()
        inv = inv.tryAddResource(ResourceType.WOOD, 3).inventory
        inv = inv.tryAddResource(ResourceType.BERRIES, 5).inventory
        inv = inv.tryAddResource(ResourceType.STONE, 2).inventory
        return inv
    }

    @Test
    fun `empty order means identity layout`() {
        val inv = stocked()
        val grid = inv.normalizedOrder()
        assertEquals(Inventory.MAX_SLOTS, grid.size)
        assertEquals(0, grid[0])
        assertEquals(1, grid[1])
        assertEquals(2, grid[2])
        assertNull(grid[3])
        assertEquals(ResourceType.WOOD, inv.gridSlot(0)?.type)
    }

    @Test
    fun `swapping cells moves items and keeps holes`() {
        val inv = stocked()
        val moved = inv.withSwappedCells(0, 7)
        assertEquals(ResourceType.WOOD, moved.gridSlot(7)?.type)
        assertNull(moved.gridSlot(0))
        assertEquals(ResourceType.BERRIES, moved.gridSlot(1)?.type)
        // обратная перестановка возвращает всё назад
        val back = moved.withSwappedCells(7, 0)
        assertEquals(inv.normalizedOrder(), back.normalizedOrder())
    }

    @Test
    fun `corrupted order is sanitized not fatal`() {
        val inv = stocked()
        // дубли, выход за границы, null-дырки
        val broken = inv.copy(order = listOf(2, 2, 99, null, 0, -1))
        val norm = broken.normalizedOrder()
        assertEquals(Inventory.MAX_SLOTS, norm.size)
        assertEquals(2, norm[0])
        assertEquals(0, norm[4])
        // каждый слот назначен ровно один раз
        val assigned = norm.filterNotNull()
        assertEquals(inv.slots.size, assigned.size)
        assertEquals(inv.slots.size, assigned.toSet().size)
        // идемпотентность
        assertEquals(norm, broken.copy(order = norm).normalizedOrder())
    }

    @Test
    fun `every slot stays reachable after any swap sequence`() {
        var inv = stocked()
        val pairs = listOf(0 to 19, 3 to 1, 19 to 2, 5 to 5, 2 to 18)
        for ((a, b) in pairs) {
            inv = inv.withSwappedCells(a, b)
        }
        val seen = inv.normalizedOrder().filterNotNull().toSet()
        assertEquals(inv.slots.size, seen.size)
        assertTrue(inv.countOf(ResourceType.WOOD) == 3)
    }
}
