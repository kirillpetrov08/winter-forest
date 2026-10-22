package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.EquippedTool
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.WildlifeEntity
import com.ayaks.winterforest.domain.model.WildlifeKind
import com.ayaks.winterforest.domain.model.WorkbenchRecipe
import com.ayaks.winterforest.domain.rules.SurvivalEngine
import com.ayaks.winterforest.presentation.gl.GlMesh
import com.ayaks.winterforest.presentation.gl.MeshBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sqrt

class LandscapeAndGameplayUpgradeTest {

    @Test
    fun `sprint increases movement speed drains stamina and auto disables when exhausted`() {
        val base = BaseProgress()
        val initial = SurvivalEngine.createNewRun(base)

        val walkMoved = SurvivalEngine.movePlayer(initial, forwardInput = 1f, strafeInput = 0f, deltaSeconds = 1f, base = base)
        val sprintMoved = SurvivalEngine.movePlayer(
            initial.copy(isSprinting = true, stamina = 100f),
            forwardInput = 1f,
            strafeInput = 0f,
            deltaSeconds = 1f,
            base = base
        )
        val walkDist = hypot(walkMoved.playerX - initial.playerX, walkMoved.playerZ - initial.playerZ)
        val sprintDist = hypot(sprintMoved.playerX - initial.playerX, sprintMoved.playerZ - initial.playerZ)
        assertTrue("Спринт должен заметно ускорять перемещение", sprintDist > walkDist * 1.45f)

        val exhausted = SurvivalEngine.tick(
            initial.copy(isSprinting = true, stamina = 4f),
            base,
            deltaSeconds = 2f,
            isMoving = true
        ).run
        assertFalse("При полном расходе сил авто-спринт обязан отключаться", exhausted.isSprinting)
    }

    @Test
    fun `field campfire consumes wood warms freezing survivor and repels night wolves`() {
        val base = BaseProgress()
        val freezing = SurvivalEngine.createNewRun(base).copy(
            currentLocation = LocationId.DENSE_SPRUCE,
            playerX = 14f,
            playerZ = 14f,
            warmth = 35f,
            inventory = SurvivalEngine.createNewRun(base).inventory.tryAddResource(ResourceType.WOOD, 4).inventory
        )
        val lit = SurvivalEngine.placeFieldCampfire(freezing, base)
        assertTrue(lit.actionSucceeded)
        assertEquals(1, lit.run.fieldCampfires.size)
        assertTrue(lit.run.warmth > 50f)

        // Волк рядом с горящим факелом/костром отступает вместо нанесения урона
        val wolfClose = WildlifeEntity(
            id = "test_wolf",
            kind = WildlifeKind.TIMBER_WOLF,
            x = lit.run.playerX + 1.8f,
            z = lit.run.playerZ,
            hp = WildlifeKind.TIMBER_WOLF.maxHp
        )
        val withWolf = lit.run.copy(
            equippedTool = EquippedTool.TORCH,
            wildlifeByLocation = mapOf(LocationId.DENSE_SPRUCE to listOf(wolfClose))
        )
        val stepped = SurvivalEngine.tick(withWolf, base, deltaSeconds = 0.5f, isMoving = false).run
        assertEquals("При зажжённом факеле и костре волк не наносит урон", withWolf.health, stepped.health, 0.01f)
    }

    @Test
    fun `hunting spear defeats wildlife grants meat and hide trophies and new workbench recipes work`() {
        val base = BaseProgress(workbenchLevel = 2)
        val baseRun = SurvivalEngine.createNewRun(base)
        val deer = WildlifeEntity(
            id = "stag_1",
            kind = WildlifeKind.NORTHERN_DEER,
            x = 0f,
            z = 3.0f,
            hp = WildlifeKind.NORTHERN_DEER.maxHp
        )
        val armed = baseRun.copy(
            equippedTool = EquippedTool.SPEAR,
            wildlifeByLocation = mapOf(LocationId.BASE_CLEARING to listOf(deer)),
            inventory = baseRun.inventory
                .tryAddResource(ResourceType.WOOD, 5).inventory
                .tryAddResource(ResourceType.STONE, 4).inventory
        )

        val spearCraft = SurvivalEngine.craftAtWorkbench(armed, base, WorkbenchRecipe.CRAFT_HUNTING_SPEAR)
        assertTrue(spearCraft.actionSucceeded)
        assertTrue(spearCraft.base.masterSpearCrafted)

        val hunt = SurvivalEngine.performSmartAction(spearCraft.run, spearCraft.base, withToolStrikeBonus = true)
        assertTrue(hunt.actionSucceeded)
        assertEquals(1, hunt.base.totalWildlifeHunted)
        assertTrue(hunt.run.inventory.countOf(ResourceType.MEAT) >= 2)
        assertTrue(hunt.run.inventory.countOf(ResourceType.HIDE) >= 1)
    }

    @Test
    fun `sculpted boulders spruce tiers wildlife and first person tools produce valid unit normals`() {
        val fur = MeshBuilder()
        val bark = MeshBuilder()
        val timber = MeshBuilder()
        val metal = MeshBuilder()
        val cloth = MeshBuilder()
        val fire = MeshBuilder()

        bark.addSculptedBoulder(0f, 0f, 0f, 1.0f, 0.9f, 0.9f, seed = 42)
        fur.addSpruceBoughTier(0f, 1f, 0f, 2.0f, 1.5f, tips = 9, twistRad = 0.4f)
        for (kind in WildlifeKind.entries) {
            MeshBuilder.addWildlifeCreature(fur, bark, fire, kind, 2f, 0f, -3f, 0.7f, 1.2f)
        }
        for (tool in EquippedTool.entries) {
            MeshBuilder.addFirstPersonEquippedItem(
                timber, metal, cloth, fire, tool, true,
                0f, 1.72f, 0f, 0.5f, -0.1f, 0.4f, 1.1f, 2.5f
            )
        }

        for (builder in listOf(fur, bark, timber, metal, cloth, fire)) {
            val mesh = builder.build()
            assertTrue(mesh.vertexCount > 0)
            for (v in 0 until mesh.vertexCount) {
                val o = v * GlMesh.FLOATS_PER_VERTEX
                val nx = mesh.vertexBuffer.get(o + 3)
                val ny = mesh.vertexBuffer.get(o + 4)
                val nz = mesh.vertexBuffer.get(o + 5)
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                assertEquals(1f, len, 0.01f)
            }
        }
    }

    @Test
    fun `rounded chamfered boxes smooth ellipsoids and lathes remove the blocky look everywhere`() {
        val mb = MeshBuilder()
        mb.addBox(0f, 0f, 0f, 1.2f, 0.8f, 0.6f) // шамференный брус
        mb.addEllipsoid(2f, 1f, 0f, 0.5f, 0.7f, 0.4f, yawRad = 0.3f)
        mb.addLathe(
            -2f, 0f, 0f,
            floatArrayOf(0.22f, 0f, 0.30f, 0.16f, 0.24f, 0.34f, 0.12f, 0.42f)
        )
        val mesh = mb.build()
        assertTrue(
            "Шамфер + эллипсоид + тело вращения должны давать заметно больше вершин, чем коробки (сейчас ${mesh.vertexCount})",
            mesh.vertexCount >= 800
        )
        for (v in 0 until mesh.vertexCount) {
            val o = v * GlMesh.FLOATS_PER_VERTEX
            for (k in 0 until GlMesh.FLOATS_PER_VERTEX) {
                val value = mesh.vertexBuffer.get(o + k)
                assertTrue("Координаты/нормали конечны", value == value && value > -1e6f && value < 1e6f)
            }
            val nx = mesh.vertexBuffer.get(o + 3)
            val ny = mesh.vertexBuffer.get(o + 4)
            val nz = mesh.vertexBuffer.get(o + 5)
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 0.2f) {
                assertEquals("Нормаль вершины должна быть единичной", 1f, len, 0.03f)
            }
        }

        // Даже 4 сегмента поднимаются до гладких 12 — никаких «квадратных» стволов
        val cyl = MeshBuilder()
        cyl.addTaperedCylinder(0f, 0f, 0f, 0f, 1f, 0f, 0.2f, 0.15f, segments = 4)
        val cone = MeshBuilder()
        cone.addCone(0f, 0f, 0f, 0.5f, 1f, segments = 3)
        assertTrue("Цилиндр должен иметь минимум 12 граней", cyl.build().vertexCount >= 12 * 6)
        assertTrue("Конус должен иметь минимум 12 граней", cone.build().vertexCount >= 12 * 3)
    }
}
