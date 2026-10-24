package com.ayaks.winterforest.domain.world

import com.ayaks.winterforest.domain.model.LocationId
import kotlin.math.hypot

/**
 * Единый большой мир: пять районов (бывших «локаций») лежат в одной системе
 * координат, между ними можно ПРОСТО ИДТИ пешком. Каждый район работает в
 * своих локальных координатах (как раньше), а [centerOf] даёт сдвиг.
 *
 *   DENSE_SPRUCE (0, −120)
 *        |
 * ABANDONED_MINE (−120, 0) — BASE_CLEARING (0, 0) — FROZEN_LAKE (120, 0)
 *        |
 *   HUNTERS_CABIN (0, 120)
 */
object WorldRegions {

    data class Region(val id: LocationId, val cx: Float, val cz: Float)

    val REGIONS: List<Region> = listOf(
        Region(LocationId.BASE_CLEARING, 0f, 0f),
        Region(LocationId.DENSE_SPRUCE, 0f, -120f),
        Region(LocationId.FROZEN_LAKE, 120f, 0f),
        Region(LocationId.ABANDONED_MINE, -120f, 0f),
        Region(LocationId.HUNTERS_CABIN, 0f, 120f)
    )

    /** Полуразмер видимой части района: |x|, |z| < 55 вокруг центра. */
    const val REGION_HALF_EXTENT = 55f

    /** Где начинается смешение рельефа двух соседних районов. */
    private const val BLEND_START = 52f
    private const val BLEND_END = 76f

    fun centerOf(loc: LocationId): Pair<Float, Float> {
        val r = REGIONS.first { it.id == loc }
        return r.cx to r.cz
    }

    fun regionAt(x: Float, z: Float): LocationId {
        var best = REGIONS[0]
        var bestD = Float.MAX_VALUE
        for (r in REGIONS) {
            val d = (x - r.cx) * (x - r.cx) + (z - r.cz) * (z - r.cz)
            if (d < bestD) {
                bestD = d
                best = r
            }
        }
        return best.id
    }

    /** Вес района в точке: 1 в глубине района, плавно 0 на границе смешения. */
    fun regionWeight(x: Float, z: Float, r: Region): Float {
        val d = hypot(x - r.cx, z - r.cz)
        val t = ((BLEND_END - d) / (BLEND_END - BLEND_START)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
