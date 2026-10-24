package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.WorldObjectKind
import com.ayaks.winterforest.domain.world.WorldPopulator
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.world.WorldCollision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Пак Trees & Bushes (модель пользователя): деревья и кусты добылимы,
 * непроходимы, стоят на земле (minY≈0 по используемым вершинам) и
 * расставлены без «полётов» (глобальные координаты узлов в границах мира).
 */
class TreesBushesPackTest {

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "settings.gradle.kts").exists()) cwd else cwd.parentFile
    }

    @Test
    fun `world spawns harvestable oak and scrub nodes`() {
        var oaks = 0
        var scrubs = 0
        for (loc in LocationId.entries) {
            val nodes = WorldPopulator.generateLocationNodes(loc)
            for (n in nodes) {
                when (n.kind) {
                    WorldObjectKind.OAK_TREE -> {
                        oaks++
                        assertEquals("OAK_TREE must yield WOOD at $loc", ResourceType.WOOD, n.resourceType)
                    }
                    WorldObjectKind.SCRUB_BUSH -> {
                        scrubs++
                        assertEquals("SCRUB_BUSH must yield BERRIES at $loc", ResourceType.BERRIES, n.resourceType)
                    }
                    else -> Unit
                }
                assertTrue(
                    "node ${n.id} out of world bounds",
                    Math.abs(n.x) < 166f && Math.abs(n.z) < 166f
                )
            }
            assertTrue(
                "ABANDONED_MINE must stay conifer-only",
                LocationId.ABANDONED_MINE != loc || nodes.none { it.kind == WorldObjectKind.OAK_TREE }
            )
        }
        assertTrue("world has no OAK_TREE nodes", oaks >= 3)
        assertTrue("world has no SCRUB_BUSH nodes", scrubs >= 3)
    }

    @Test
    fun `oak and scrub are impassable obstacles`() {
        val obstacles = WorldCollision.obstacles(
            LocationId.BASE_CLEARING,
            listOf(
                com.ayaks.winterforest.domain.model.ResourceNode(
                    id = "oak_t", location = LocationId.BASE_CLEARING,
                    kind = WorldObjectKind.OAK_TREE, x = 5f, z = 5f
                ),
                com.ayaks.winterforest.domain.model.ResourceNode(
                    id = "scrub_t", location = LocationId.BASE_CLEARING,
                    kind = WorldObjectKind.SCRUB_BUSH, x = -5f, z = -5f
                )
            )
        )
        val circles = obstacles.first
        assertTrue(circles.any { it.x == 5f && it.z == 5f && it.radius >= 0.55f })
        assertTrue(circles.any { it.x == -5f && it.z == -5f && it.radius >= 0.5f })
    }

    @Test
    fun `packed trees and bushes sit exactly on the ground`() {
        for (id in listOf("oak_a", "oak_b", "oak_c", "bush_green", "bush_dry", "bush_thicket")) {
            val obj = File(repoRoot(), "app-winterforest/src/main/assets/models/$id/mesh.obj")
            assertTrue("missing $id mesh", obj.isFile)
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var used = false
            for (line in obj.readLines()) {
                if (line.startsWith("v ")) {
                    val y = line.trim().split(Regex("\\s+"))[2].toFloat()
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    used = true
                }
            }
            assertTrue("empty $id", used)
            assertTrue("$id floats: minY=$minY", minY > -0.02f && minY < 0.02f)
            assertTrue("$id degenerate height", maxY - minY > 0.3f)
            assertTrue("$id missing diff.png", File(obj.parentFile, "diff.png").isFile)
        }
    }
}
