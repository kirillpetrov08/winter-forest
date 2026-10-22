package com.ayaks.winterforest.presentation.gl

import android.opengl.GLES20
import com.ayaks.winterforest.domain.model.EquippedTool
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.WildlifeKind
import com.ayaks.winterforest.domain.world.WorldPopulator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Вершинный буфер с чередующимися атрибутами:
 * position(3) + normal(3) + uv(2) + color(4) = 12 float (48 байт) на вершину.
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

/**
 * Аппаратный буфер вершин в видеопамяти OpenGL ES (VBO).
 * Устраняет повторные аллокации ByteBuffer и копирования на каждом кадре.
 */
class GlVbo {
    var bufferId: Int = 0
        private set
    var vertexCount: Int = 0
        private set
    private var gpuCapacityBytes: Int = 0

    fun uploadStatic(builder: MeshBuilder, scratchBuffer: FloatBuffer? = null) {
        ensureHandle()
        vertexCount = builder.vertexCount
        if (vertexCount <= 0) return
        val byteLen = builder.floatSize * 4
        val buf = obtainFilledBuffer(builder, scratchBuffer)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufferId)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, byteLen, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        gpuCapacityBytes = byteLen
    }

    fun uploadDynamic(builder: MeshBuilder, reusableBuffer: FloatBuffer): FloatBuffer {
        ensureHandle()
        vertexCount = builder.vertexCount
        if (vertexCount <= 0) return reusableBuffer
        val neededFloats = builder.floatSize
        val targetBuf = if (reusableBuffer.capacity() < neededFloats) {
            ByteBuffer.allocateDirect(max(neededFloats * 2, 16384) * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        } else {
            reusableBuffer
        }
        targetBuf.clear()
        targetBuf.put(builder.rawArray(), 0, neededFloats)
        targetBuf.position(0)

        val byteLen = neededFloats * 4
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufferId)
        if (byteLen > gpuCapacityBytes) {
            val allocBytes = byteLen * 2
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, allocBytes, null, GLES20.GL_DYNAMIC_DRAW)
            gpuCapacityBytes = allocBytes
        }
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, byteLen, targetBuf)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        return targetBuf
    }

    fun release() {
        if (bufferId != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(bufferId), 0)
            bufferId = 0
        }
        vertexCount = 0
        gpuCapacityBytes = 0
    }

    private fun ensureHandle() {
        if (bufferId == 0) {
            val ids = IntArray(1)
            GLES20.glGenBuffers(1, ids, 0)
            bufferId = ids[0]
        }
    }

    private fun obtainFilledBuffer(builder: MeshBuilder, scratch: FloatBuffer?): FloatBuffer {
        val needed = builder.floatSize
        val buf = if (scratch != null && scratch.capacity() >= needed) {
            scratch
        } else {
            ByteBuffer.allocateDirect(needed * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        }
        buf.clear()
        buf.put(builder.rawArray(), 0, needed)
        buf.position(0)
        return buf
    }
}

class MeshBuilder(initialFloatsCapacity: Int = 16384) {
    private var data = FloatArray(initialFloatsCapacity)
    var floatSize: Int = 0
        private set

    val vertexCount: Int get() = floatSize / GlMesh.FLOATS_PER_VERTEX

    internal fun rawArray(): FloatArray = data

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
        val nLen = sqrt(nx * nx + ny * ny + nz * nz)
        val inv = if (nLen > 1e-5f) 1f / nLen else 0f
        val ux = if (nLen > 1e-5f) nx * inv else 0f
        val uy = if (nLen > 1e-5f) ny * inv else 1f
        val uz = if (nLen > 1e-5f) nz * inv else 0f

        val o = floatSize
        data[o] = px; data[o + 1] = py; data[o + 2] = pz
        data[o + 3] = ux; data[o + 4] = uy; data[o + 5] = uz
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

    fun addQuadShaded(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        nx: Float, ny: Float, nz: Float,
        uScale: Float = 1f,
        vScale: Float = 1f,
        bottomAo: Float = 0.78f,
        r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f
    ) {
        val br = r * bottomAo; val bg = g * bottomAo; val bb = b * bottomAo
        addVertex(x0, y0, z0, nx, ny, nz, 0f, vScale, br, bg, bb, a)
        addVertex(x1, y1, z1, nx, ny, nz, uScale, vScale, br, bg, bb, a)
        addVertex(x2, y2, z2, nx, ny, nz, uScale, 0f, r, g, b, a)

        addVertex(x0, y0, z0, nx, ny, nz, 0f, vScale, br, bg, bb, a)
        addVertex(x2, y2, z2, nx, ny, nz, uScale, 0f, r, g, b, a)
        addVertex(x3, y3, z3, nx, ny, nz, 0f, 0f, r, g, b, a)
    }

    fun addBox(
        cx: Float, baseY: Float, cz: Float,
        sx: Float, sy: Float, sz: Float,
        r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f
    ) {
        addOrientedBox(cx, baseY + sy * 0.5f, cz, sx, sy, sz, 0f, 0f, 0f, r, g, b, a)
    }

    /**
     * Произвольно ориентированный 3D-брус (yaw / pitch / roll) с запечённым контактным АО снизу.
     */
    fun addOrientedBox(
        cx: Float, cy: Float, cz: Float,
        sx: Float, sy: Float, sz: Float,
        yawRad: Float = 0f,
        pitchRad: Float = 0f,
        rollRad: Float = 0f,
        r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f,
        uvDensity: Float = 0.65f
    ) {
        val cyaw = cos(yawRad); val syaw = sin(yawRad)
        val cp = cos(pitchRad); val sp = sin(pitchRad)
        val cr = cos(rollRad); val sr = sin(rollRad)

        // Локальные ортонормированные оси X, Y, Z
        val axX = cyaw * cr + syaw * sp * sr
        val axY = cp * sr
        val axZ = -syaw * cr + cyaw * sp * sr

        val ayX = -cyaw * sr + syaw * sp * cr
        val ayY = cp * cr
        val ayZ = syaw * sr + cyaw * sp * cr

        val azX = syaw * cp
        val azY = -sp
        val azZ = cyaw * cp

        val hx = sx * 0.5f; val hy = sy * 0.5f; val hz = sz * 0.5f

        fun px(lx: Float, ly: Float, lz: Float) = cx + axX * lx + ayX * ly + azX * lz
        fun py(lx: Float, ly: Float, lz: Float) = cy + axY * lx + ayY * ly + azY * lz
        fun pz(lx: Float, ly: Float, lz: Float) = cz + axZ * lx + ayZ * ly + azZ * lz

        val uFront = max(sx * uvDensity, 0.35f)
        val vSide = max(sy * uvDensity, 0.35f)
        val uRight = max(sz * uvDensity, 0.35f)

        // +Z
        addQuadShaded(
            px(-hx, -hy, hz), py(-hx, -hy, hz), pz(-hx, -hy, hz),
            px(hx, -hy, hz), py(hx, -hy, hz), pz(hx, -hy, hz),
            px(hx, hy, hz), py(hx, hy, hz), pz(hx, hy, hz),
            px(-hx, hy, hz), py(-hx, hy, hz), pz(-hx, hy, hz),
            azX, azY, azZ, uFront, vSide, 0.80f, r, g, b, a
        )
        // -Z
        addQuadShaded(
            px(hx, -hy, -hz), py(hx, -hy, -hz), pz(hx, -hy, -hz),
            px(-hx, -hy, -hz), py(-hx, -hy, -hz), pz(-hx, -hy, -hz),
            px(-hx, hy, -hz), py(-hx, hy, -hz), pz(-hx, hy, -hz),
            px(hx, hy, -hz), py(hx, hy, -hz), pz(hx, hy, -hz),
            -azX, -azY, -azZ, uFront, vSide, 0.80f, r, g, b, a
        )
        // +X
        addQuadShaded(
            px(hx, -hy, hz), py(hx, -hy, hz), pz(hx, -hy, hz),
            px(hx, -hy, -hz), py(hx, -hy, -hz), pz(hx, -hy, -hz),
            px(hx, hy, -hz), py(hx, hy, -hz), pz(hx, hy, -hz),
            px(hx, hy, hz), py(hx, hy, hz), pz(hx, hy, hz),
            axX, axY, axZ, uRight, vSide, 0.80f, r, g, b, a
        )
        // -X
        addQuadShaded(
            px(-hx, -hy, -hz), py(-hx, -hy, -hz), pz(-hx, -hy, -hz),
            px(-hx, -hy, hz), py(-hx, -hy, hz), pz(-hx, -hy, hz),
            px(-hx, hy, hz), py(-hx, hy, hz), pz(-hx, hy, hz),
            px(-hx, hy, -hz), py(-hx, hy, -hz), pz(-hx, hy, -hz),
            -axX, -axY, -axZ, uRight, vSide, 0.80f, r, g, b, a
        )
        // +Y (top)
        addQuad(
            px(-hx, hy, hz), py(-hx, hy, hz), pz(-hx, hy, hz),
            px(hx, hy, hz), py(hx, hy, hz), pz(hx, hy, hz),
            px(hx, hy, -hz), py(hx, hy, -hz), pz(hx, hy, -hz),
            px(-hx, hy, -hz), py(-hx, hy, -hz), pz(-hx, hy, -hz),
            ayX, ayY, ayZ, uFront, uRight, r * 1.04f, g * 1.04f, b * 1.05f, a
        )
        // -Y (bottom)
        addQuad(
            px(-hx, -hy, -hz), py(-hx, -hy, -hz), pz(-hx, -hy, -hz),
            px(hx, -hy, -hz), py(hx, -hy, -hz), pz(hx, -hy, -hz),
            px(hx, -hy, hz), py(hx, -hy, hz), pz(hx, -hy, hz),
            px(-hx, -hy, hz), py(-hx, -hy, hz), pz(-hx, -hy, hz),
            -ayX, -ayY, -ayZ, uFront, uRight, r * 0.62f, g * 0.62f, b * 0.66f, a
        )
    }

    /**
     * Конический/цилиндрический ствол или бревно между произвольными точками P0 и P1.
     */
    fun addTaperedCylinder(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        r0: Float,
        r1: Float,
        segments: Int = 8,
        r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f,
        capEnds: Boolean = true
    ) {
        val dx = x1 - x0; val dy = y1 - y0; val dz = z1 - z0
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-4f) return
        val ux = dx / len; val uy = dy / len; val uz = dz / len

        // Ортонормированный базис в сечении цилиндра
        val refX = if (abs(uy) < 0.92f) 0f else 1f
        val refY = if (abs(uy) < 0.92f) 1f else 0f
        var bx = refY * uz; var by = -refX * uz; var bz = refX * uy - refY * ux
        val bLen = sqrt(bx * bx + by * by + bz * bz).coerceAtLeast(1e-5f)
        bx /= bLen; by /= bLen; bz /= bLen
        val tx = uy * bz - uz * by
        val ty = uz * bx - ux * bz
        val tz = ux * by - uy * bx

        val step = (Math.PI * 2.0 / segments).toFloat()
        val vTile = max(len * 0.45f, 0.5f)
        for (i in 0 until segments) {
            val a0 = i * step
            val a1 = (i + 1) * step
            val c0 = cos(a0); val s0 = sin(a0)
            val c1 = cos(a1); val s1 = sin(a1)

            val n0x = bx * c0 + tx * s0; val n0y = by * c0 + ty * s0; val n0z = bz * c0 + tz * s0
            val n1x = bx * c1 + tx * s1; val n1y = by * c1 + ty * s1; val n1z = bz * c1 + tz * s1

            val p00x = x0 + n0x * r0; val p00y = y0 + n0y * r0; val p00z = z0 + n0z * r0
            val p10x = x0 + n1x * r0; val p10y = y0 + n1y * r0; val p10z = z0 + n1z * r0
            val p11x = x1 + n1x * r1; val p11y = y1 + n1y * r1; val p11z = z1 + n1z * r1
            val p01x = x1 + n0x * r1; val p01y = y1 + n0y * r1; val p01z = z1 + n0z * r1

            val u0 = i.toFloat() / segments * 2f
            val u1 = (i + 1).toFloat() / segments * 2f

            addVertex(p00x, p00y, p00z, n0x, n0y, n0z, u0, vTile, r * 0.78f, g * 0.78f, b * 0.80f, a)
            addVertex(p10x, p10y, p10z, n1x, n1y, n1z, u1, vTile, r * 0.78f, g * 0.78f, b * 0.80f, a)
            addVertex(p11x, p11y, p11z, n1x, n1y, n1z, u1, 0f, r, g, b, a)

            addVertex(p00x, p00y, p00z, n0x, n0y, n0z, u0, vTile, r * 0.78f, g * 0.78f, b * 0.80f, a)
            addVertex(p11x, p11y, p11z, n1x, n1y, n1z, u1, 0f, r, g, b, a)
            addVertex(p01x, p01y, p01z, n0x, n0y, n0z, u0, 0f, r, g, b, a)

            if (capEnds && r1 > 0.02f) {
                addVertex(x1, y1, z1, ux, uy, uz, 0.5f, 0.5f, r * 1.06f, g * 1.02f, b * 0.96f, a)
                addVertex(p01x, p01y, p01z, ux, uy, uz, 0.5f + c0 * 0.5f, 0.5f + s0 * 0.5f, r, g, b, a)
                addVertex(p11x, p11y, p11z, ux, uy, uz, 0.5f + c1 * 0.5f, 0.5f + s1 * 0.5f, r, g, b, a)
            }
        }
    }

    /**
     * Многолучевая хвойная лапа с волнистым краем ветвей и затенением под нижним ярусом.
     */
    fun addSpruceBoughTier(
        cx: Float,
        baseY: Float,
        cz: Float,
        radius: Float,
        height: Float,
        tips: Int = 9,
        twistRad: Float = 0f,
        swayX: Float = 0f,
        r: Float = 1f, g: Float = 1f, b: Float = 1f
    ) {
        val totalSegs = tips * 2
        val step = (Math.PI * 2.0 / totalSegs).toFloat()
        val slopeY = radius / hypot(radius, height)
        val slopeXZ = height / hypot(radius, height)

        for (i in 0 until totalSegs) {
            val a0 = twistRad + i * step
            val a1 = twistRad + (i + 1) * step
            val rad0 = radius * (if (i % 2 == 0) 1.06f else 0.74f)
            val rad1 = radius * (if ((i + 1) % 2 == 0) 1.06f else 0.74f)
            val drop0 = if (i % 2 == 0) -0.10f * height else 0.08f * height
            val drop1 = if ((i + 1) % 2 == 0) -0.10f * height else 0.08f * height

            val x0 = cx + swayX * 0.35f + cos(a0) * rad0
            val z0 = cz + sin(a0) * rad0
            val x1 = cx + swayX * 0.35f + cos(a1) * rad1
            val z1 = cz + sin(a1) * rad1
            val mid = (a0 + a1) * 0.5f
            val nx = cos(mid) * slopeXZ
            val nz = sin(mid) * slopeXZ

            // Верхний заснеженный склон лапы
            addVertex(x0, baseY + drop0, z0, nx, slopeY, nz, 0f, 1f, r * 0.76f, g * 0.80f, b * 0.86f, 1f)
            addVertex(x1, baseY + drop1, z1, nx, slopeY, nz, 1f, 1f, r * 0.76f, g * 0.80f, b * 0.86f, 1f)
            addVertex(cx + swayX, baseY + height, cz, nx, slopeY, nz, 0.5f, 0f, r * 1.05f, g * 1.05f, b * 1.06f, 1f)

            // Тёмное подбрюшье хвойного яруса (даёт глубокий реалистичный объём кроны!)
            addVertex(cx, baseY + height * 0.14f, cz, 0f, -1f, 0f, 0.5f, 0.85f, r * 0.28f, g * 0.32f, b * 0.34f, 1f)
            addVertex(x1, baseY + drop1, z1, 0f, -1f, 0f, 1f, 1f, r * 0.36f, g * 0.40f, b * 0.44f, 1f)
            addVertex(x0, baseY + drop0, z0, 0f, -1f, 0f, 0f, 1f, r * 0.36f, g * 0.40f, b * 0.44f, 1f)
        }
    }

    /**
     * Гранёный природный валун / рудная скала на базе подразделённой сферы с шумовым смещением граней.
     */
    fun addSculptedBoulder(
        cx: Float,
        baseY: Float,
        cz: Float,
        rx: Float,
        ry: Float,
        rz: Float,
        seed: Int,
        r: Float = 1f, g: Float = 1f, b: Float = 1f,
        snowCapTint: Boolean = true
    ) {
        val latBands = 5
        val lonSegs = 8
        fun displacedPoint(latIdx: Int, lonIdx: Int): FloatArray {
            val v = latIdx.toFloat() / latBands.toFloat()
            val phi = (v - 0.5f) * Math.PI.toFloat()
            val theta = (lonIdx % lonSegs).toFloat() / lonSegs.toFloat() * (Math.PI.toFloat() * 2f)
            val cPhi = cos(phi)
            val dx = cPhi * cos(theta)
            val dy = sin(phi)
            val dz = cPhi * sin(theta)
            val n = ProceduralTextures.fbm(dx * 2.1f + 3.1f, dz * 2.1f + dy * 1.7f, octaves = 3, seed = seed)
            val disp = 0.72f + 0.48f * n
            return floatArrayOf(
                cx + dx * rx * disp,
                baseY + ry * 0.42f + dy * ry * disp * 0.68f,
                cz + dz * rz * disp
            )
        }

        for (i in 0 until latBands) {
            for (j in 0 until lonSegs) {
                val p00 = displacedPoint(i, j)
                val p10 = displacedPoint(i, j + 1)
                val p11 = displacedPoint(i + 1, j + 1)
                val p01 = displacedPoint(i + 1, j)

                // Плоская геологическая нормаль скола грани
                val e1x = p10[0] - p00[0]; val e1y = p10[1] - p00[1]; val e1z = p10[2] - p00[2]
                val e2x = p01[0] - p00[0]; val e2y = p01[1] - p00[1]; val e2z = p01[2] - p00[2]
                var nx = e1y * e2z - e1z * e2y
                var ny = e1z * e2x - e1x * e2z
                var nz = e1x * e2y - e1y * e2x
                // Направляем нормаль строго наружу от центра валуна
                val midX = (p00[0] + p11[0]) * 0.5f - cx
                val midY = (p00[1] + p11[1]) * 0.5f - (baseY + ry * 0.42f)
                val midZ = (p00[2] + p11[2]) * 0.5f - cz
                if (nx * midX + ny * midY + nz * midZ < 0f) {
                    nx = -nx; ny = -ny; nz = -nz
                }
                val nLen = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
                val upDot = ny / nLen
                val snowMix = if (snowCapTint) ((upDot - 0.42f) * 2.2f).coerceIn(0f, 0.85f) else 0f
                val ao = (0.64f + 0.36f * (i.toFloat() / latBands.toFloat())).coerceIn(0.58f, 1f)
                val fr = (r * ao * (1f - snowMix) + 0.94f * snowMix)
                val fg = (g * ao * (1f - snowMix) + 0.97f * snowMix)
                val fb = (b * ao * (1f - snowMix) + 1.00f * snowMix)

                addQuad(
                    p00[0], p00[1], p00[2],
                    p10[0], p10[1], p10[2],
                    p11[0], p11[1], p11[2],
                    p01[0], p01[1], p01[2],
                    nx, ny, nz,
                    0.85f, 0.85f,
                    fr, fg, fb, 1f
                )
            }
        }
    }

    fun addCone(
        cx: Float,
        baseY: Float,
        cz: Float,
        radius: Float,
        height: Float,
        segments: Int = 8,
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

            addVertex(x0, baseY, z0, nx, slopeY, nz, 0f, 1f, r * 0.84f, g * 0.84f, b * 0.88f, a)
            addVertex(x1, baseY, z1, nx, slopeY, nz, 1f, 1f, r * 0.84f, g * 0.84f, b * 0.88f, a)
            addVertex(cx, baseY + height, cz, nx, slopeY, nz, 0.5f, 0f, r, g, b, a)
        }
    }

    /**
     * Скрещённые 3D-плоскости пламени костра / факела (видны объёмно под любым углом обзора!).
     */
    fun addCrossedFlame(
        cx: Float,
        baseY: Float,
        cz: Float,
        width: Float,
        height: Float,
        swayX: Float = 0f,
        r: Float = 1f, g: Float = 0.88f, b: Float = 0.48f, a: Float = 0.95f
    ) {
        for (i in 0 until 3) {
            val ang = (i / 3f) * Math.PI.toFloat()
            val dx = cos(ang) * width * 0.5f
            val dz = sin(ang) * width * 0.5f
            val nx = -sin(ang)
            val nz = cos(ang)
            addVertex(cx - dx, baseY, cz - dz, nx, 0.25f, nz, 0f, 1f, r, g * 0.72f, b * 0.45f, a)
            addVertex(cx + dx, baseY, cz + dz, nx, 0.25f, nz, 1f, 1f, r, g * 0.72f, b * 0.45f, a)
            addVertex(cx + swayX, baseY + height, cz, nx, 0.25f, nz, 0.5f, 0.04f, 1f, 0.96f, 0.72f, a * 0.92f)

            addVertex(cx + dx, baseY, cz + dz, -nx, 0.25f, -nz, 0f, 1f, r, g * 0.72f, b * 0.45f, a)
            addVertex(cx - dx, baseY, cz - dz, -nx, 0.25f, -nz, 1f, 1f, r, g * 0.72f, b * 0.45f, a)
            addVertex(cx + swayX, baseY + height, cz, -nx, 0.25f, -nz, 0.5f, 0.04f, 1f, 0.96f, 0.72f, a * 0.92f)
        }
    }

    fun addShadowDisc(cx: Float, y: Float, cz: Float, radius: Float, alpha: Float = 0.38f) {
        val segs = 12
        val step = (Math.PI * 2.0 / segs).toFloat()
        for (i in 0 until segs) {
            val a0 = i * step
            val a1 = (i + 1) * step
            addVertex(cx, y + 0.035f, cz, 0f, 1f, 0f, 0.5f, 0.5f, 0.04f, 0.07f, 0.13f, alpha)
            addVertex(
                cx + cos(a0) * radius, y + 0.035f, cz + sin(a0) * radius,
                0f, 1f, 0f, 0f, 0f, 0.04f, 0.07f, 0.13f, 0.0f
            )
            addVertex(
                cx + cos(a1) * radius, y + 0.035f, cz + sin(a1) * radius,
                0f, 1f, 0f, 0f, 1f, 0.04f, 0.07f, 0.13f, 0.0f
            )
        }
    }

    fun build(): GlMesh {
        val buf = ByteBuffer.allocateDirect(max(floatSize, 12) * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(data, 0, floatSize)
        buf.position(0)
        return GlMesh(buf, vertexCount)
    }

    companion object {
        /**
         * Анимированная 3D-модель северного животного (полярный заяц, северный олень с рогами, таёжный волк).
         */
        fun addWildlifeCreature(
        furBuilder: MeshBuilder,
        barkHornBuilder: MeshBuilder,
        glowBuilder: MeshBuilder,
        kind: WildlifeKind,
        x: Float,
        groundY: Float,
        z: Float,
        headingRad: Float,
        animPhase: Float
    ) {
        val yaw = -headingRad + (Math.PI.toFloat() * 0.5f)
        val fwdX = cos(headingRad)
        val fwdZ = sin(headingRad)
        val rightX = -fwdZ
        val rightZ = fwdX
        val stride = sin(animPhase) * 0.34f

        when (kind) {
            WildlifeKind.SNOW_HARE -> {
                val hop = abs(sin(animPhase * 0.85f)) * 0.14f
                val by = groundY + 0.24f + hop
                furBuilder.addShadowDisc(x, groundY, z, 0.42f, 0.30f)
                furBuilder.addOrientedBox(x, by, z, 0.26f, 0.24f, 0.42f, yaw, -0.10f, 0f, 0.98f, 0.98f, 1.0f)
                val hx = x + fwdX * 0.24f; val hz = z + fwdZ * 0.24f
                furBuilder.addOrientedBox(hx, by + 0.12f, hz, 0.19f, 0.18f, 0.20f, yaw, 0f, 0f, 0.99f, 0.99f, 1.0f)
                // Длинные заячьи уши с чёрными кончиками
                furBuilder.addOrientedBox(hx + rightX * 0.055f, by + 0.31f, hz + rightZ * 0.055f, 0.045f, 0.22f, 0.06f, yaw, -0.22f, 0.10f, 0.94f, 0.95f, 0.98f)
                furBuilder.addOrientedBox(hx - rightX * 0.055f, by + 0.31f, hz - rightZ * 0.055f, 0.045f, 0.22f, 0.06f, yaw, -0.22f, -0.10f, 0.94f, 0.95f, 0.98f)
                furBuilder.addOrientedBox(x - fwdX * 0.24f, by + 0.04f, z - fwdZ * 0.24f, 0.12f, 0.12f, 0.12f, yaw, 0f, 0f, 1f, 1f, 1f)
            }

            WildlifeKind.NORTHERN_DEER -> {
                val torsoY = groundY + 1.02f
                furBuilder.addShadowDisc(x, groundY, z, 1.05f, 0.36f)
                // Туловище северного оленя со светлой шеей и грудью
                furBuilder.addOrientedBox(x, torsoY, z, 0.56f, 0.58f, 1.38f, yaw, 0f, 0f, 0.68f, 0.53f, 0.40f)
                furBuilder.addOrientedBox(x + fwdX * 0.56f, torsoY + 0.36f, z + fwdZ * 0.56f, 0.30f, 0.66f, 0.32f, yaw, 0.26f, 0f, 0.84f, 0.78f, 0.72f)
                val hx = x + fwdX * 0.78f; val hz = z + fwdZ * 0.78f; val hy = torsoY + 0.66f
                furBuilder.addOrientedBox(hx, hy, hz, 0.26f, 0.26f, 0.48f, yaw, -0.12f, 0f, 0.66f, 0.52f, 0.40f)
                // Ветвистые рога оленя
                for (side in listOf(-1f, 1f)) {
                    val rx = hx + rightX * 0.11f * side
                    val rz = hz + rightZ * 0.11f * side
                    barkHornBuilder.addTaperedCylinder(
                        rx, hy + 0.10f, rz,
                        rx + rightX * 0.26f * side - fwdX * 0.12f, hy + 0.58f, rz + rightZ * 0.26f * side - fwdZ * 0.12f,
                        0.032f, 0.016f, 6, 0.90f, 0.84f, 0.74f
                    )
                    barkHornBuilder.addTaperedCylinder(
                        rx + rightX * 0.14f * side, hy + 0.34f, rz + rightZ * 0.14f * side,
                        rx + rightX * 0.24f * side + fwdX * 0.22f, hy + 0.54f, rz + rightZ * 0.24f * side + fwdZ * 0.22f,
                        0.022f, 0.012f, 5, 0.92f, 0.86f, 0.76f
                    )
                }
                // 4 стройные ноги с противофазным шагом
                val legs = listOf(
                    Triple(0.20f, 0.46f, stride),
                    Triple(-0.20f, 0.46f, -stride),
                    Triple(0.20f, -0.46f, -stride),
                    Triple(-0.20f, -0.46f, stride)
                )
                for ((lx, lz, swing) in legs) {
                    val px = x + rightX * lx + fwdX * lz
                    val pz = z + rightZ * lx + fwdZ * lz
                    furBuilder.addOrientedBox(px, groundY + 0.44f, pz, 0.11f, 0.88f, 0.12f, yaw, swing, 0f, 0.56f, 0.43f, 0.33f)
                }
            }

            WildlifeKind.TIMBER_WOLF -> {
                val torsoY = groundY + 0.60f
                furBuilder.addShadowDisc(x, groundY, z, 0.88f, 0.38f)
                furBuilder.addOrientedBox(x, torsoY, z, 0.42f, 0.42f, 1.08f, yaw, 0f, 0f, 0.46f, 0.48f, 0.53f)
                furBuilder.addOrientedBox(x + fwdX * 0.24f, torsoY + 0.06f, z + fwdZ * 0.24f, 0.46f, 0.46f, 0.44f, yaw, 0f, 0f, 0.54f, 0.56f, 0.60f)
                val hx = x + fwdX * 0.64f; val hz = z + fwdZ * 0.64f; val hy = torsoY + 0.14f
                furBuilder.addOrientedBox(hx, hy, hz, 0.28f, 0.26f, 0.40f, yaw, -0.08f, 0f, 0.50f, 0.52f, 0.57f)
                // Острые уши и пушистый хвост волка
                furBuilder.addOrientedBox(hx + rightX * 0.09f, hy + 0.19f, hz + rightZ * 0.09f, 0.07f, 0.13f, 0.07f, yaw, 0f, 0f, 0.36f, 0.38f, 0.42f)
                furBuilder.addOrientedBox(hx - rightX * 0.09f, hy + 0.19f, hz - rightZ * 0.09f, 0.07f, 0.13f, 0.07f, yaw, 0f, 0f, 0.36f, 0.38f, 0.42f)
                furBuilder.addOrientedBox(x - fwdX * 0.66f, torsoY - 0.06f, z - fwdZ * 0.66f, 0.14f, 0.15f, 0.46f, yaw, -0.45f, 0f, 0.40f, 0.42f, 0.47f)
                // Светящиеся янтарные глаза хищника в темноте
                val ex = hx + fwdX * 0.19f; val ez = hz + fwdZ * 0.19f
                glowBuilder.addBox(ex + rightX * 0.065f, hy + 0.03f, ez + rightZ * 0.065f, 0.04f, 0.04f, 0.04f, 1f, 0.72f, 0.15f, 1f)
                glowBuilder.addBox(ex - rightX * 0.065f, hy + 0.03f, ez - rightZ * 0.065f, 0.04f, 0.04f, 0.04f, 1f, 0.72f, 0.15f, 1f)
                val legs = listOf(
                    Triple(0.15f, 0.36f, stride * 1.2f),
                    Triple(-0.15f, 0.36f, -stride * 1.2f),
                    Triple(0.15f, -0.36f, -stride * 1.2f),
                    Triple(-0.15f, -0.36f, stride * 1.2f)
                )
                for ((lx, lz, swing) in legs) {
                    val px = x + rightX * lx + fwdX * lz
                    val pz = z + rightZ * lx + fwdZ * lz
                    furBuilder.addOrientedBox(px, groundY + 0.26f, pz, 0.095f, 0.52f, 0.10f, yaw, swing, 0f, 0.42f, 0.44f, 0.49f)
                }
            }
        }
    }

    /**
     * Детальная 3D-модель инструмента/оружия от первого лица (Топор, Кирка, Копьё, Смоляной факел).
     */
    fun addFirstPersonEquippedItem(
        timberBuilder: MeshBuilder,
        metalBuilder: MeshBuilder,
        clothBuilder: MeshBuilder,
        fireBuilder: MeshBuilder,
        tool: EquippedTool,
        torchLit: Boolean,
        camX: Float,
        camY: Float,
        camZ: Float,
        yawRad: Float,
        pitchRad: Float,
        swingProgress: Float,
        walkBobPhase: Float,
        timeSec: Float
    ) {
        val fwdX = sin(yawRad) * cos(pitchRad)
        val fwdY = sin(pitchRad)
        val fwdZ = -cos(yawRad) * cos(pitchRad)
        val rightX = cos(yawRad)
        val rightZ = sin(yawRad)

        val bobX = sin(walkBobPhase * 0.5f) * 0.022f
        val bobY = abs(cos(walkBobPhase * 0.5f)) * 0.020f + sin(timeSec * 2.1f) * 0.006f
        val chopArc = sin(swingProgress * Math.PI.toFloat())

        val baseX = camX + fwdX * (0.58f + chopArc * 0.24f) + rightX * (0.34f - chopArc * 0.10f + bobX)
        val baseY = camY - 0.34f + fwdY * 0.52f - chopArc * 0.16f - bobY
        val baseZ = camZ + fwdZ * (0.58f + chopArc * 0.24f) + rightZ * (0.34f - chopArc * 0.10f + bobX)

        val toolYaw = -yawRad
        val toolPitch = pitchRad + 0.22f - chopArc * 1.05f

        val tipDx = sin(yawRad) * sin(0.28f + chopArc * 0.85f)
        val tipDy = cos(0.28f + chopArc * 0.85f)
        val tipDz = -cos(yawRad) * sin(0.28f + chopArc * 0.85f)

        when (tool) {
            EquippedTool.AXE -> {
                val topX = baseX + tipDx * 0.50f
                val topY = baseY + tipDy * 0.50f
                val topZ = baseZ + tipDz * 0.50f
                timberBuilder.addTaperedCylinder(baseX, baseY, baseZ, topX, topY, topZ, 0.026f, 0.022f, 8, 0.88f, 0.72f, 0.52f)
                clothBuilder.addTaperedCylinder(
                    baseX + tipDx * 0.08f, baseY + tipDy * 0.08f, baseZ + tipDz * 0.08f,
                    baseX + tipDx * 0.22f, baseY + tipDy * 0.22f, baseZ + tipDz * 0.22f,
                    0.030f, 0.029f, 8
                )
                // Кованый бородатый обух и широкое стальное лезвие топора
                metalBuilder.addOrientedBox(topX, topY - 0.03f, topZ, 0.055f, 0.10f, 0.16f, toolYaw, toolPitch, 0f, 0.86f, 0.90f, 0.96f)
                metalBuilder.addOrientedBox(
                    topX + sin(yawRad) * 0.09f, topY - 0.05f, topZ - cos(yawRad) * 0.09f,
                    0.020f, 0.16f, 0.13f, toolYaw, toolPitch, 0f, 0.98f, 0.99f, 1.0f
                )
            }

            EquippedTool.PICKAXE -> {
                val topX = baseX + tipDx * 0.52f
                val topY = baseY + tipDy * 0.52f
                val topZ = baseZ + tipDz * 0.52f
                timberBuilder.addTaperedCylinder(baseX, baseY, baseZ, topX, topY, topZ, 0.027f, 0.024f, 8, 0.80f, 0.65f, 0.45f)
                metalBuilder.addOrientedBox(topX, topY - 0.02f, topZ, 0.055f, 0.065f, 0.34f, toolYaw, toolPitch, 0f, 0.88f, 0.92f, 0.98f)
                metalBuilder.addOrientedBox(
                    topX + sin(yawRad) * 0.15f, topY - 0.05f, topZ - cos(yawRad) * 0.15f,
                    0.032f, 0.045f, 0.14f, toolYaw, toolPitch - 0.35f, 0f, 0.96f, 0.98f, 1.0f
                )
            }

            EquippedTool.SPEAR -> {
                val topX = baseX + fwdX * 0.62f + tipDx * 0.26f
                val topY = baseY + fwdY * 0.62f + tipDy * 0.26f
                val topZ = baseZ + fwdZ * 0.62f + tipDz * 0.26f
                timberBuilder.addTaperedCylinder(
                    baseX - fwdX * 0.20f, baseY - 0.10f, baseZ - fwdZ * 0.20f,
                    topX, topY, topZ, 0.023f, 0.018f, 8, 0.84f, 0.68f, 0.48f
                )
                clothBuilder.addTaperedCylinder(baseX, baseY, baseZ, baseX + fwdX * 0.18f, baseY + 0.08f, baseZ + fwdZ * 0.18f, 0.027f, 0.026f, 8)
                // Листовидный стальной наконечник копья
                metalBuilder.addTaperedCylinder(
                    topX, topY, topZ,
                    topX + fwdX * 0.24f, topY + fwdY * 0.24f + 0.05f, topZ + fwdZ * 0.24f,
                    0.036f, 0.004f, 6, 0.98f, 0.99f, 1.0f
                )
            }

            EquippedTool.TORCH -> {
                val topX = baseX + tipDx * 0.48f
                val topY = baseY + tipDy * 0.48f
                val topZ = baseZ + tipDz * 0.48f
                timberBuilder.addTaperedCylinder(baseX, baseY, baseZ, topX, topY, topZ, 0.028f, 0.034f, 8, 0.62f, 0.44f, 0.28f)
                clothBuilder.addTaperedCylinder(
                    topX - tipDx * 0.10f, topY - tipDy * 0.10f, topZ - tipDz * 0.10f,
                    topX + tipDx * 0.03f, topY + tipDy * 0.03f, topZ + tipDz * 0.03f,
                    0.044f, 0.048f, 8, 0.45f, 0.30f, 0.20f
                )
                if (torchLit) {
                    val flick = 0.88f + 0.18f * sin(timeSec * 13.5f)
                    fireBuilder.addCrossedFlame(
                        topX, topY + 0.02f, topZ,
                        width = 0.20f,
                        height = 0.34f * flick,
                        swayX = sin(timeSec * 9.0f) * 0.025f
                    )
                }
            }
        }
    }

    fun buildTerrainMesh(location: LocationId, halfExtent: Float = 48f, steps: Int = 64): GlMesh {
            val builder = buildTerrainIntoBuilder(location, halfExtent, steps)
            return builder.build()
        }

        fun buildTerrainIntoBuilder(location: LocationId, halfExtent: Float = 48f, steps: Int = 64): MeshBuilder {
            val gCount = steps + 1
            val cell = (halfExtent * 2f) / steps
            val heights = FloatArray(gCount * gCount)
            val normX = FloatArray(gCount * gCount)
            val normY = FloatArray(gCount * gCount)
            val normZ = FloatArray(gCount * gCount)
            val vertAo = FloatArray(gCount * gCount)

            for (gz in 0..steps) {
                val z = -halfExtent + gz * cell
                for (gx in 0..steps) {
                    val x = -halfExtent + gx * cell
                    heights[gz * gCount + gx] = terrainWithBorderCliff(location, x, z)
                }
            }

            // Плавные по-вершинные нормали центральными разностями + АО впадин рельефа
            for (gz in 0..steps) {
                for (gx in 0..steps) {
                    val idx = gz * gCount + gx
                    val hL = heights[gz * gCount + max(gx - 1, 0)]
                    val hR = heights[gz * gCount + minOf(gx + 1, steps)]
                    val hD = heights[max(gz - 1, 0) * gCount + gx]
                    val hU = heights[minOf(gz + 1, steps) * gCount + gx]
                    val hC = heights[idx]

                    val dx = (hL - hR) / (2f * cell)
                    val dz = (hD - hU) / (2f * cell)
                    val inv = 1f / sqrt(dx * dx + 1f + dz * dz)
                    normX[idx] = dx * inv
                    normY[idx] = inv
                    normZ[idx] = dz * inv

                    val avgNeighbors = (hL + hR + hD + hU) * 0.25f
                    val cavity = ((hC - avgNeighbors) * 0.55f).coerceIn(-0.18f, 0.10f)
                    // Утоптанная снежная тропа от лагеря к северным воротам (x ≈ 0, z in -36..6)
                    val x = -halfExtent + gx * cell
                    val z = -halfExtent + gz * cell
                    val onPath = if (z in -36f..7f && abs(x - sin(z * 0.14f) * 1.4f) < 1.35f) 0.90f else 1.0f
                    vertAo[idx] = ((0.94f + cavity) * onPath).coerceIn(0.74f, 1.03f)
                }
            }

            val mb = MeshBuilder(steps * steps * 6 * GlMesh.FLOATS_PER_VERTEX)
            val uvScale = 0.52f
            for (gz in 0 until steps) {
                val z0 = -halfExtent + gz * cell
                val z1 = z0 + cell
                for (gx in 0 until steps) {
                    val x0 = -halfExtent + gx * cell
                    val x1 = x0 + cell

                    val i00 = gz * gCount + gx
                    val i10 = gz * gCount + (gx + 1)
                    val i01 = (gz + 1) * gCount + gx
                    val i11 = (gz + 1) * gCount + (gx + 1)

                    val u0 = gx * uvScale; val v0 = gz * uvScale
                    val u1 = (gx + 1) * uvScale; val v1 = (gz + 1) * uvScale

                    fun emit(idx: Int, px: Float, pz: Float, u: Float, v: Float) {
                        val ao = vertAo[idx]
                        mb.addVertex(
                            px, heights[idx], pz,
                            normX[idx], normY[idx], normZ[idx],
                            u, v,
                            ao * 0.99f, ao, (ao * 1.02f).coerceAtMost(1.04f), 1f
                        )
                    }

                    emit(i00, x0, z0, u0, v0)
                    emit(i01, x0, z1, u0, v1)
                    emit(i11, x1, z1, u1, v1)

                    emit(i00, x0, z0, u0, v0)
                    emit(i11, x1, z1, u1, v1)
                    emit(i10, x1, z0, u1, v0)
                }
            }
            return mb
        }

        fun terrainWithBorderCliff(location: LocationId, x: Float, z: Float): Float {
            val base = WorldPopulator.terrainHeight(location, x, z)
            val radial = hypot(x, z)
            val edge = maxOf(abs(x), abs(z))
            val ridge = ((edge - 38.2f) * 1.55f).coerceAtLeast(0f) +
                (0.55f * sin(radial * 0.45f) * ((edge - 38.2f).coerceIn(0f, 4f) * 0.25f))
            return base + ridge
        }
    }
}
