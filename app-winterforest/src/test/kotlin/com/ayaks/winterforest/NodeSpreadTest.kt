package com.ayaks.winterforest

import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.WorldObjectKind
import com.ayaks.winterforest.domain.world.WorldPopulator
import com.ayaks.winterforest.presentation.gl.AssetProps
import kotlin.math.hypot
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7.10 «добывать можно каждый камень и дерево, без куч у построек»:
 * раскладка узлов — пуассон-диск по всей площади района, а декоративных
 * (несобираемых) камней/деревьев/кустов в мире больше нет.
 */
class NodeSpreadTest {

    private val decorModels = setOf(
        "rock_09", "rock_moss_a", "rock_moss_b",
        "oak_a", "oak_b", "oak_c",
        "bush_green", "bush_dry", "bush_thicket",
        "tree_bark", "dry_branches_a", "dry_branches_b"
    )

    @Test
    fun `no ungatherable decorative rocks trees or bushes remain in placements`() {
        for (loc in LocationId.entries) {
            for (p in AssetProps.forLocation(loc)) {
                assertTrue(
                    "${loc.name}: декоративный несобираемый ${p.modelId} — должен быть узлом",
                    p.modelId !in decorModels
                )
            }
        }
    }

    @Test
    fun `nodes spread evenly and keep clear of structures`() {
        val forbidden = mapOf(
            LocationId.BASE_CLEARING to listOf(-6.8f to -5.8f, 4.8f to -4.5f),
            LocationId.HUNTERS_CABIN to listOf(7.2f to 8.8f)
        )
        for (loc in LocationId.entries) {
            val nodes = WorldPopulator.generateLocationNodes(loc)
            assertTrue("${loc.name}: узлов меньше прежнего (${nodes.size})", nodes.size >= 42)

            // локальные координаты: узлы приходят в глобальных.
            // Ящики и тайники — постановочные точки лута, их близость к постройкам
            // намеренная; проверяем дистанцию только для природных ресурсов.
            val (cx, cz) = com.ayaks.winterforest.domain.world.WorldRegions.centerOf(loc)
            val local = nodes
                .filter {
                    it.kind != WorldObjectKind.SUPPLY_CRATE &&
                        it.kind != WorldObjectKind.MILITARY_CRATE &&
                        it.kind != WorldObjectKind.SPECIAL_CACHE
                }
                .map { (it.x - cx) to (it.z - cz) }

            // нет «кучи» у ключевых построек: ближайший узел не ближе 6 м
            for ((fx, fz) in forbidden[loc].orEmpty()) {
                val nearest = local.minOfOrNull { hypot(it.first - fx, it.second - fz) } ?: 99f
                assertTrue(
                    "${loc.name}: узлы лепятся к постройке ($fx,$fz): $nearest м",
                    nearest >= 6f
                )
            }

            // равномерность: район разбит на 4 квадранта — в каждом есть узлы
            val quads = local.groupBy { (it.first >= 0f) to (it.second >= 0f) }
            assertTrue("${loc.name}: узлы только в части квадрантов ${quads.keys}", quads.size == 4)
            for ((q, list) in quads) {
                assertTrue("${loc.name}: квадрант $q пуст", list.size >= 4)
            }
        }
    }

    @Test
    fun `mine gallery walls are gatherable coal and iron nodes`() {
        val mine = WorldPopulator.generateLocationNodes(LocationId.ABANDONED_MINE)
        val gallery = mine.filter { it.id.contains("_gallery_") }
        assertTrue("стенки штольни исчезли", gallery.size == 12)
        assertTrue(
            gallery.all { it.kind == WorldObjectKind.COAL_SEAM || it.kind == WorldObjectKind.IRON_VEIN }
        )
    }
}
