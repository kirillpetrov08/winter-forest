package com.ayaks.winterforest.presentation.gl

import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.world.WorldPopulator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Вершинный буфер с чередующимися атрибутами:
 * position(3) + normal(3) + uv(2) + color(4) = 12 float на вершину.
 */
class GlMesh(
    val vertexBuffer: FloatBuffer,
    val vertexCount: Int
) {
    companion object {
        const val FLOATS_PER_VERTEX = 12
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4
    }
}

class MeshBuilder(initialFloatsCapacity: Int = 8192) {
    private var data = FloatArray(initialFloatsCapacity)
    var floatSize: Int = 0
        private set

    val vertexCount: Int get() = floatSize / GlMesh.FLOATS_PER_VERTEX

    fun clear() {
        floatSize = 0
    }

    fun addVertex(
        px: Float, py: Float, pz: Float,
        nx: Float, ny: Float, nz: Float,
        u: Float, v: Float,
        r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f
    ) {
        if (floatSize + GlMesh.FLOATS_PER_VERTEX > data.size) {
            data = data.copyOf(data.size * 2)
        }
        val o = floatSize
        data[o] = px; data[o + 1] = py; data[o + 2] = pz
        data[o + 3] = nx; data[o + 4] = ny; data[o + 5] = nz
        data[o + 6] = u; data[o + 7] = v
        data[o + 8] = r; data[o + 9] = g; data[o + 10] = b; data[o + 11] = a
        floatSize += GlMesh.FLOATS_PER_VERTEX
    }

    fun addQuad(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        nx: Float, ny: Float, nz: Float,
        uScale: Float = 1f,
        vScale: Float = 1f,
        r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f
    ) {
        addVertex(x0, y0, z0, nx, ny, nz, 0f, vScale, r, g, b, a)
        addVertex(x1, y1, z1, nx, ny, nz, uScale, vScale, r, g, b, a)
        addVertex(x2, y2, z2, nx, ny, nz, uScale, 0f, r, g, b, a)

        addVertex(x0, y0, z0, nx, ny, nz, 0f, vScale, r, g, b, a)
        addVertex(x2, y2, z2, nx, ny, nz, uScale, 0f, r, g, b, a)
        addVertex(x3, y3, z3, nx, ny, nz, 0f, 0f, r, g, b, a)
    }

    fun addBox(
        cx: Float, baseY: Float, cz: Float,
        sx: Float, sy: Float, sz: Float,
        r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f
    ) {
        val hx = sx * 0.5f
        val hz = sz * 0.5f
        val y0 = baseY
        val y1 = baseY + sy
        // Front (+Z)
        addQuad(cx - hx, y0, cz + hz, cx + hx, y0, cz + hz, cx + hx, y1, cz + hz, cx - hx, y1, cz + hz, 0f, 0f, 1f, 1f, 1f, r, g, b, a)
        // Back (-Z)
        addQuad(cx + hx, y0, cz - hz, cx - hx, y0, cz - hz, cx - hx, y1, cz - hz, cx + hx, y1, cz - hz, 0f, 0f, -1f, 1f, 1f, r, g, b, a)
        // Right (+X)
        addQuad(cx + hx, y0, cz + hz, cx + hx, y0, cz - hz, cx + hx, y1, cz - hz, cx + hx, y1, cz + hz, 1f, 0f, 0f, 1f, 1f, r, g, b, a)
        // Left (-X)
        addQuad(cx - hx, y0, cz - hz, cx - hx, y0, cz + hz, cx - hx, y1, cz + hz, cx - hx, y1, cz - hz, -1f, 0f, 0f, 1f, 1f, r, g, b, a)
        // Top (+Y)
        addQuad(cx - hx, y1, cz + hz, cx + hx, y1, cz + hz, cx + hx, y1, cz - hz, cx - hx, y1, cz - hz, 0f, 1f, 0f, 1f, 1f, r, g, b, a)
    }

    fun addCone(
        cx: Float,
        baseY: Float,
        cz: Float,
        radius: Float,
        height: Float,
        segments: Int = 7,
        r: Float = 1f,
        g: Float = 1f,
        b: Float = 1f,
        a: Float = 1f
    ) {
        val step = (Math.PI * 2.0 / segments).toFloat()
        val slopeY = radius / hypot(radius, height)
        val slopeXZ = height / hypot(radius, height)
        for (i in 0 until segments) {
            val a0 = i * step
            val a1 = (i + 1) * step
            val x0 = cx + cos(a0) * radius
            val z0 = cz + sin(a0) * radius
            val x1 = cx + cos(a1) * radius
            val z1 = cz + sin(a1) * radius
            val mid = (a0 + a1) * 0.5f
            val nx = cos(mid) * slopeXZ
            val nz = sin(mid) * slopeXZ

            addVertex(x0, baseY, z0, nx, slopeY, nz, 0f, 1f, r * 0.86f, g * 0.86f, b * 0.90f, a)
            addVertex(x1, baseY, z1, nx, slopeY, nz, 1f, 1f, r * 0.86f, g * 0.86f, b * 0.90f, a)
            addVertex(cx, baseY + height, cz, nx, slopeY, nz, 0.5f, 0f, r, g, b, a)
        }
    }

    fun addShadowDisc(cx: Float, y: Float, cz: Float, radius: Float, alpha: Float = 0.34f) {
        val segs = 8
        val step = (Math.PI * 2.0 / segs).toFloat()
        for (i in 0 until segs) {
            val a0 = i * step
            val a1 = (i + 1) * step
            addVertex(cx, y + 0.03f, cz, 0f, 1f, 0f, 0.5f, 0.5f, 0.05f, 0.08f, 0.14f, alpha)
            addVertex(
                cx + cos(a0) * radius, y + 0.03f, cz + sin(a0) * radius,
                0f, 1f, 0f, 0f, 0f, 0.05f, 0.08f, 0.14f, 0.0f
            )
            addVertex(
                cx + cos(a1) * radius, y + 0.03f, cz + sin(a1) * radius,
                0f, 1f, 0f, 1f, 0f, 0.05f, 0.08f, 0.14f, 0.0f
            )
        }
    }

    fun build(): GlMesh {
        val buf = ByteBuffer.allocateDirect(floatSize * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(data, 0, floatSize)
        buf.position(0)
        return GlMesh(buf, vertexCount)
    }

    companion object {
        fun buildTerrainMesh(location: LocationId, halfExtent: Float = 46f, steps: Int = 36): GlMesh {
            val mb = MeshBuilder(steps * steps * 6 * GlMesh.FLOATS_PER_VERTEX)
            val cell = (halfExtent * 2f) / steps
            for (gz in 0 until steps) {
                val z0 = -halfExtent + gz * cell
                val z1 = z0 + cell
                for (gx in 0 until steps) {
                    val x0 = -halfExtent + gx * cell
                    val x1 = x0 + cell

                    val h00 = terrainWithBorderCliff(location, x0, z0)
                    val h10 = terrainWithBorderCliff(location, x1, z0)
                    val h11 = terrainWithBorderCliff(location, x1, z1)
                    val h01 = terrainWithBorderCliff(location, x0, z1)

                    // Вычисляем нормаль рельефа по перепаду высот
                    val dx = (h00 - h10) / cell
                    val dz = (h00 - h01) / cell
                    val invLen = 1f / sqrt(dx * dx + 1f + dz * dz)
                    val nx = dx * invLen
                    val ny = invLen
                    val nz = dz * invLen

                    val u0 = gx * 0.45f
                    val v0 = gz * 0.45f
                    val u1 = (gx + 1) * 0.45f
                    val v1 = (gz + 1) * 0.45f

                    val ao = (0.90f + 0.10f * (h00 * 0.3f).coerceIn(-0.5f, 0.5f)).coerceIn(0.78f, 1.0f)

                    mb.addVertex(x0, h00, z0, nx, ny, nz, u0, v0, ao, ao, ao * 1.02f, 1f)
                    mb.addVertex(x0, h01, z1, nx, ny, nz, u0, v1, ao, ao, ao * 1.02f, 1f)
                    mb.addVertex(x1, h11, z1, nx, ny, nz, u1, v1, ao, ao, ao * 1.02f, 1f)

                    mb.addVertex(x0, h00, z0, nx, ny, nz, u0, v0, ao, ao, ao * 1.02f, 1f)
                    mb.addVertex(x1, h11, z1, nx, ny, nz, u1, v1, ao, ao, ao * 1.02f, 1f)
                    mb.addVertex(x1, h10, z0, nx, ny, nz, u1, v0, ao, ao, ao * 1.02f, 1f)
                }
            }
            return mb.build()
        }

        private fun terrainWithBorderCliff(location: LocationId, x: Float, z: Float): Float {
            val base = WorldPopulator.terrainHeight(location, x, z)
            val edge = maxOf(kotlin.math.abs(x), kotlin.math.abs(z))
            // Визуальная заснеженная скальная гряда по периметру мира (> 38.5 м)
            val wall = ((edge - 38.5f) * 1.45f).coerceAtLeast(0f)
            return base + wall
        }
    }
}
