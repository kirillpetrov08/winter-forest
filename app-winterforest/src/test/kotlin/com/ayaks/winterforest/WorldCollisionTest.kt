package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.ResourceNode
import com.ayaks.winterforest.domain.model.WorldObjectKind
import com.ayaks.winterforest.domain.world.WorldCollision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Непроходимость: деревья/валуны выталкивают, стены срубов держат,
 * дверной проём проходим, внутри дома тепло.
 */
class WorldCollisionTest {

    private fun node(kind: WorldObjectKind, x: Float, z: Float) = ResourceNode(
        id = "n_${kind}_$x",
        location = LocationId.BASE_CLEARING,
        kind = kind,
        x = x,
        z = z
    )

    @Test
    fun `circle obstacle pushes player out`() {
        val circles = listOf(WorldCollision.Circle(0f, 0f, 0.55f))
        val resolved = WorldCollision.resolve(0.2f, 0f, 0.42f, circles, emptyList())
        val d = kotlin.math.hypot(resolved[0] - 0f, resolved[1] - 0f)
        assertTrue("d=$d", d >= 0.96f)
    }

    @Test
    fun `sliding along wall is possible`() {
        val rects = listOf(WorldCollision.Rect(-2f, 2f, -0.26f, 0.26f))
        // подошли к стене чуть сбоку — выталкивание вбок, а не стоп
        val resolved = WorldCollision.resolve(0.4f, 0f, 0.42f, emptyList(), rects)
        assertTrue(kotlin.math.abs(resolved[0] - 0.4f) < 1.5f)
        assertTrue(kotlin.math.abs(resolved[1]) >= 0.66f)
    }

    @Test
    fun `tree node blocks but ice hole does not`() {
        val (circles, rects) = WorldCollision.obstacles(
            LocationId.BASE_CLEARING,
            listOf(node(WorldObjectKind.PINE_TREE, 5f, 5f), node(WorldObjectKind.ICE_HOLE, -5f, -5f))
        )
        assertTrue(circles.any { it.x == 5f && it.z == 5f })
        assertFalse(circles.any { it.x == -5f && it.z == -5f })
        assertTrue(rects.isNotEmpty()) // сруб базы присутствует
    }

    @Test
    fun `shelter door gap is passable and walls are not`() {
        val (circles, rects) = WorldCollision.obstacles(LocationId.BASE_CLEARING, emptyList())
        // дверной проём сруба: x ≈ -7.65, z ≈ -3.7 (фасад)
        val door = WorldCollision.resolve(-7.65f, -3.7f, WorldCollision.PLAYER_RADIUS, circles, rects)
        assertTrue("door moved to ${door[0]},${door[1]}", kotlin.math.hypot(door[0] + 7.65f, door[1] + 3.7f) < 0.2f)
        // в стене рядом с дверью не остаёмся
        val wall = WorldCollision.resolve(-6.2f, -3.7f, WorldCollision.PLAYER_RADIUS, circles, rects)
        assertTrue(kotlin.math.abs(wall[1] + 3.7f) > 0.4f)
    }

    @Test
    fun `inside shelter detection`() {
        assertTrue(WorldCollision.isInsideShelter(LocationId.BASE_CLEARING, -6.8f, -5.8f))
        assertFalse(WorldCollision.isInsideShelter(LocationId.BASE_CLEARING, 2.5f, 1.5f))
        assertTrue(WorldCollision.isInsideShelter(LocationId.HUNTERS_CABIN, 7.2f, 8.8f))
        assertFalse(WorldCollision.isInsideShelter(LocationId.DENSE_SPRUCE, 0f, 0f))
    }

    @Test
    fun `resolve is stable when already clear`() {
        val (circles, rects) = WorldCollision.obstacles(LocationId.FROZEN_LAKE, emptyList())
        val resolved = WorldCollision.resolve(0f, 0f, WorldCollision.PLAYER_RADIUS, circles, rects)
        assertEquals(0f, resolved[0], 1e-3f)
        assertEquals(0f, resolved[1], 1e-3f)
    }
}
