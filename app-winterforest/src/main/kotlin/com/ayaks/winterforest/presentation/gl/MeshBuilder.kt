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

        // Шамференное скругление рёбер: любой «квадратный» брус/ящик/обух
        // получает срезанные рёбра и сглаженные углы — меньше «квадратности».
        val ch = (minOf(sx, sy, sz) * 0.17f).coerceIn(0.006f, 0.075f)
        val uFront = max(sx * uvDensity, 0.35f)
        val vSide = max(sy * uvDensity, 0.35f)
        val uRight = max(sz * uvDensity, 0.35f)

        fun emitQuad(
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float,
            cx2: Float, cy2: Float, cz2: Float,
            dx2: Float, dy2: Float, dz2: Float,
            nx: Float, ny: Float, nz: Float,
            uS: Float, vS: Float,
            ao: Float
        ) {
            addQuad(
                px(ax, ay, az), py(ax, ay, az), pz(ax, ay, az),
                px(bx, by, bz), py(bx, by, bz), pz(bx, by, bz),
                px(cx2, cy2, cz2), py(cx2, cy2, cz2), pz(cx2, cy2, cz2),
                px(dx2, dy2, dz2), py(dx2, dy2, dz2), pz(dx2, dy2, dz2),
                nx, ny, nz, uS, vS, r * ao, g * ao, (b * ao).coerceAtMost(1.08f), a
            )
        }

        fun emitTri(
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float,
            cx2: Float, cy2: Float, cz2: Float,
            nx: Float, ny: Float, nz: Float,
            ao: Float
        ) {
            addVertex(px(ax, ay, az), py(ax, ay, az), pz(ax, ay, az), nx, ny, nz, 0f, 0f, r * ao, g * ao, b * ao, a)
            addVertex(px(bx, by, bz), py(bx, by, bz), pz(bx, by, bz), nx, ny, nz, 0.5f, 0.5f, r * ao, g * ao, b * ao, a)
            addVertex(px(cx2, cy2, cz2), py(cx2, cy2, cz2), pz(cx2, cy2, cz2), nx, ny, nz, 1f, 0f, r * ao, g * ao, b * ao, a)
        }

        val hxc = hx - ch; val hyc = hy - ch; val hzc = hz - ch

        // 6 плоских граней (утоплены на величину шамфера)
        emitQuad(-hxc, -hyc, hz, hxc, -hyc, hz, hxc, hyc, hz, -hxc, hyc, hz, azX, azY, azZ, uFront, vSide, 0.86f)
        emitQuad(hxc, -hyc, -hz, -hxc, -hyc, -hz, -hxc, hyc, -hz, hxc, hyc, -hz, -azX, -azY, -azZ, uFront, vSide, 0.86f)
        emitQuad(hx, -hyc, -hzc, hx, -hyc, hzc, hx, hyc, hzc, hx, hyc, -hzc, axX, axY, axZ, uRight, vSide, 0.86f)
        emitQuad(-hx, -hyc, hzc, -hx, -hyc, -hzc, -hx, hyc, -hzc, -hx, hyc, hzc, -axX, -axY, -axZ, uRight, vSide, 0.86f)
        emitQuad(-hxc, hy, hzc, hxc, hy, hzc, hxc, hy, -hzc, -hxc, hy, -hzc, ayX, ayY, ayZ, uFront, uRight, 1.02f)
        emitQuad(-hxc, -hy, -hzc, hxc, -hy, -hzc, hxc, -hy, hzc, -hxc, -hy, hzc, -ayX, -ayY, -ayZ, uFront, uRight, 0.62f)

        // 12 срезанных рёбер (нормали — усреднённые диагонали граней)
        fun invLen(x: Float, y: Float, z: Float): Float {
            val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-5f)
            return 1f / l
        }
        // рёбра Z± с X±
        for (sz2 in floatArrayOf(1f, -1f)) {
            for (sx2 in floatArrayOf(1f, -1f)) {
                val k = invLen(azX * sz2 + axX * sx2, azY * sz2 + axY * sx2, azZ * sz2 + axZ * sx2)
                val nX = (azX * sz2 + axX * sx2) * k
                val nY = (azY * sz2 + axY * sx2) * k
                val nZ = (azZ * sz2 + axZ * sx2) * k
                emitQuad(
                    hxc * sx2, -hyc, hz * sz2,
                    hx * sx2, -hyc, hzc * sz2,
                    hx * sx2, hyc, hzc * sz2,
                    hxc * sx2, hyc, hz * sz2,
                    nX, nY, nZ, uFront, vSide, 0.92f
                )
            }
        }
        // рёбра Y± с X±
        for (sy2 in floatArrayOf(1f, -1f)) {
            for (sx2 in floatArrayOf(1f, -1f)) {
                val k = invLen(ayX * sy2 + axX * sx2, ayY * sy2 + axY * sx2, ayZ * sy2 + axZ * sx2)
                val nX = (ayX * sy2 + axX * sx2) * k
                val nY = (ayY * sy2 + axY * sx2) * k
                val nZ = (ayZ * sy2 + axZ * sx2) * k
                emitQuad(
                    hx * sx2, hy * sy2, -hzc,
                    hx * sx2, hy * sy2, hzc,
                    hxc * sx2, hy * sy2, hz,
                    hxc * sx2, hy * sy2, -hz,
                    nX, nY, nZ, uRight, uFront, 0.92f
                )
            }
        }
        // рёбра Y± с Z±
        for (sy2 in floatArrayOf(1f, -1f)) {
            for (sz2 in floatArrayOf(1f, -1f)) {
                val k = invLen(ayX * sy2 + azX * sz2, ayY * sy2 + azY * sz2, ayZ * sy2 + azZ * sz2)
                val nX = (ayX * sy2 + azX * sz2) * k
                val nY = (ayY * sy2 + azY * sz2) * k
                val nZ = (ayZ * sy2 + azZ * sz2) * k
                emitQuad(
                    -hxc, hy * sy2, hz * sz2,
                    hxc, hy * sy2, hz * sz2,
                    hx, hy * sy2, hzc * sz2,
                    -hx, hy * sy2, hzc * sz2,
                    nX, nY, nZ, uFront, uRight, 0.92f
                )
            }
        }

        // 8 сглаженных угловых треугольников
        for (sx2 in floatArrayOf(1f, -1f)) {
            for (sy2 in floatArrayOf(1f, -1f)) {
                for (sz2 in floatArrayOf(1f, -1f)) {
                    val k = invLen(axX * sx2 + ayX * sy2 + azX * sz2, axY * sx2 + ayY * sy2 + azY * sz2, axZ * sx2 + ayZ * sy2 + azZ * sz2)
                    val nX = (axX * sx2 + ayX * sy2 + azX * sz2) * k
                    val nY = (axY * sx2 + ayY * sy2 + azY * sz2) * k
                    val nZ = (axZ * sx2 + ayZ * sy2 + azZ * sz2) * k
                    emitTri(
                        hxc * sx2, hy * sy2, hzc * sz2,
                        hx * sx2, hyc * sy2, hzc * sz2,
                        hxc * sx2, hyc * sy2, hz * sz2,
                        nX, nY, nZ, 0.95f
                    )
                }
            }
        }
    }

    /**
     * Конический/цилиндрический ствол или бревно между произвольными точками P0 и P1.
     */
    fun addTaperedCylinder(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        r0: Float,
        r1: Float,
        segments: Int = 14,
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

        val step = (Math.PI * 2.0 / max(segments, 12)).toFloat()
        val segs = max(segments, 12)
        val vTile = max(len * 0.45f, 0.5f)
        for (i in 0 until segs) {
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

            val u0 = i.toFloat() / segs * 2f
            val u1 = (i + 1).toFloat() / segs * 2f

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
        val totalSegs = max(tips, 12) * 2
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
        val latBands = 14
        val lonSegs = 20
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

    /**
     * Сглаженный эллипсоид (тело животного, сугроб, котёл, мешок) с плавными нормалями.
     */
    fun addEllipsoid(
        cx: Float,
        cy: Float,
        cz: Float,
        rx: Float,
        ry: Float,
        rz: Float,
        latBands: Int = 12,
        lonSegs: Int = 18,
        yawRad: Float = 0f,
        r: Float = 1f,
        g: Float = 1f,
        b: Float = 1f,
        a: Float = 1f,
        uvScale: Float = 1f
    ) {
        val bands = max(latBands, 6)
        val lons = max(lonSegs, 10)
        val cyaw = cos(yawRad)
        val syaw = sin(yawRad)

        fun dirAt(li: Int, lo: Int): FloatArray {
            val v = li.toFloat() / bands
            val phi = (v - 0.5f) * Math.PI.toFloat()
            val theta = (lo % lons).toFloat() / lons * (Math.PI.toFloat() * 2f)
            val cPhi = cos(phi)
            val dx = cPhi * cos(theta)
            val dy = sin(phi)
            val dz = cPhi * sin(theta)
            return floatArrayOf(dx * cyaw + dz * syaw, dy, -dx * syaw + dz * cyaw)
        }

        for (i in 0 until bands) {
            for (j in 0 until lons) {
                val d00 = dirAt(i, j)
                val d10 = dirAt(i, j + 1)
                val d11 = dirAt(i + 1, j + 1)
                val d01 = dirAt(i + 1, j)
                val u0 = j.toFloat() / lons * uvScale
                val u1 = (j + 1).toFloat() / lons * uvScale
                val v0 = i.toFloat() / bands * uvScale
                val v1 = (i + 1).toFloat() / bands * uvScale

                addVertex(cx + d00[0] * rx, cy + d00[1] * ry, cz + d00[2] * rz, d00[0], d00[1], d00[2], u0, v0, r, g, b, a)
                addVertex(cx + d10[0] * rx, cy + d10[1] * ry, cz + d10[2] * rz, d10[0], d10[1], d10[2], u1, v0, r, g, b, a)
                addVertex(cx + d11[0] * rx, cy + d11[1] * ry, cz + d11[2] * rz, d11[0], d11[1], d11[2], u1, v1, r, g, b, a)

                addVertex(cx + d00[0] * rx, cy + d00[1] * ry, cz + d00[2] * rz, d00[0], d00[1], d00[2], u0, v0, r, g, b, a)
                addVertex(cx + d11[0] * rx, cy + d11[1] * ry, cz + d11[2] * rz, d11[0], d11[1], d11[2], u1, v1, r, g, b, a)
                addVertex(cx + d01[0] * rx, cy + d01[1] * ry, cz + d01[2] * rz, d01[0], d01[1], d01[2], u0, v1, r, g, b, a)
            }
        }
    }

    /**
     * Тело вращения по профилю (радиус, высота): котёл, ведро, фонарь, бутылка, гриб.
     * profile — пары (radius, yRelative) снизу вверх, минимум 2 пары.
     */
    fun addLathe(
        cx: Float,
        baseY: Float,
        cz: Float,
        profile: FloatArray,
        segments: Int = 18,
        r: Float = 1f,
        g: Float = 1f,
        b: Float = 1f,
        a: Float = 1f
    ) {
        val rings = profile.size / 2
        if (rings < 2) return
        val segs = max(segments, 12)
        val step = (Math.PI * 2.0 / segs).toFloat()

        for (k in 0 until rings - 1) {
            val r0 = profile[k * 2]
            val y0 = profile[k * 2 + 1]
            val r1 = profile[k * 2 + 2]
            val y1 = profile[k * 2 + 3]
            // Нормаль по наклону образующей
            val dr = r1 - r0
            val dy = y1 - y0
            val slopeLen = hypot(dr, dy).coerceAtLeast(1e-5f)
            val nr = dy / slopeLen
            val ny = dr / slopeLen
            for (i in 0 until segs) {
                val a0 = i * step
                val a1 = (i + 1) * step
                val c0 = cos(a0); val s0 = sin(a0)
                val c1 = cos(a1); val s1 = sin(a1)
                val n0x = nr * c0; val n0z = nr * s0
                val n1x = nr * c1; val n1z = nr * s1
                val vTop = 1f - k.toFloat() / (rings - 1)
                val vBot = 1f - (k + 1).toFloat() / (rings - 1)

                addVertex(cx + r0 * c0, baseY + y0, cz + r0 * s0, n0x, ny, n0z, i.toFloat() / segs * 2f, vTop, r * 0.88f, g * 0.88f, b * 0.92f, a)
                addVertex(cx + r0 * c1, baseY + y0, cz + r0 * s1, n1x, ny, n1z, (i + 1).toFloat() / segs * 2f, vTop, r * 0.88f, g * 0.88f, b * 0.92f, a)
                addVertex(cx + r1 * c1, baseY + y1, cz + r1 * s1, n1x, ny, n1z, (i + 1).toFloat() / segs * 2f, vBot, r, g, b, a)

                addVertex(cx + r0 * c0, baseY + y0, cz + r0 * s0, n0x, ny, n0z, i.toFloat() / segs * 2f, vTop, r * 0.88f, g * 0.88f, b * 0.92f, a)
                addVertex(cx + r1 * c1, baseY + y1, cz + r1 * s1, n1x, ny, n1z, (i + 1).toFloat() / segs * 2f, vBot, r, g, b, a)
                addVertex(cx + r1 * c0, baseY + y1, cz + r1 * s0, n0x, ny, n0z, i.toFloat() / segs * 2f, vBot, r, g, b, a)
            }
        }
        // Нижнее дно
        val rB = profile[0]
        val yB = profile[1]
        if (rB > 0.01f) {
            for (i in 0 until segs) {
                val a0 = i * step
                val a1 = (i + 1) * step
                addVertex(cx, baseY + yB, cz, 0f, -1f, 0f, 0.5f, 0.5f, r * 0.55f, g * 0.55f, b * 0.58f, a)
                addVertex(cx + rB * cos(a1), baseY + yB, cz + rB * sin(a1), 0f, -1f, 0f, 1f, 0f, r * 0.55f, g * 0.55f, b * 0.58f, a)
                addVertex(cx + rB * cos(a0), baseY + yB, cz + rB * sin(a0), 0f, -1f, 0f, 0f, 0f, r * 0.55f, g * 0.55f, b * 0.58f, a)
            }
        }
    }

    fun addCone(
        cx: Float,
        baseY: Float,
        cz: Float,
        radius: Float,
        height: Float,
        segments: Int = 14,
        r: Float = 1f,
        g: Float = 1f,
        b: Float = 1f,
        a: Float = 1f
    ) {
        val segs = max(segments, 12)
        val step = (Math.PI * 2.0 / segs).toFloat()
        val slopeY = radius / hypot(radius, height)
        val slopeXZ = height / hypot(radius, height)
        for (i in 0 until segs) {
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
        // Внешние языки с полупрозрачным выдохом + светлое ядро: живое пламя под любым углом
        val layers = arrayOf(
            floatArrayOf(1.00f, 1.00f, 0.72f, 0.32f),
            floatArrayOf(0.52f, 0.82f, 1.06f, 0.22f)
        )
        for ((layer, params) in layers.withIndex()) {
            val wf = params[0]; val hf = params[1]; val bright = params[2]; val alphaMul = params[3]
            for (i in 0 until 3) {
                val ang = (i / 3f) * Math.PI.toFloat() + layer * 0.35f
                val dx = cos(ang) * width * 0.5f * wf
                val dz = sin(ang) * width * 0.5f * wf
                val nx = -sin(ang)
                val nz = cos(ang)
                val h = height * hf
                addVertex(cx - dx, baseY, cz - dz, nx, 0.25f, nz, 0f, 1f, r, g * bright, b * 0.45f, a)
                addVertex(cx + dx, baseY, cz + dz, nx, 0.25f, nz, 1f, 1f, r, g * bright, b * 0.45f, a)
                addVertex(cx + swayX * wf, baseY + h, cz, nx, 0.25f, nz, 0.5f, 0.04f, 1f, 0.96f, 0.72f, a * alphaMul)

                addVertex(cx + dx, baseY, cz + dz, -nx, 0.25f, -nz, 0f, 1f, r, g * bright, b * 0.45f, a)
                addVertex(cx - dx, baseY, cz - dz, -nx, 0.25f, -nz, 1f, 1f, r, g * bright, b * 0.45f, a)
                addVertex(cx + swayX * wf, baseY + h, cz, -nx, 0.25f, -nz, 0.5f, 0.04f, 1f, 0.96f, 0.72f, a * alphaMul)
            }
        }
    }

    fun addShadowDisc(cx: Float, y: Float, cz: Float, radius: Float, alpha: Float = 0.38f) {
        val segs = 18
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
         * Процедурная 3D-модель северного животного (таёжная крыса, северный олень
         * с рогами, таёжный волк). Резервный путь рендерера: вблизи звери рисуются
         * импортированными моделями (wolf_body / deer_body / rat_body), поэтому
         * функция используется для дальней фауны и в юнит-тестах геометрии.
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
            WildlifeKind.TAIGA_RAT -> {
                // Таёжная крыса: низкое серо-бурое тело, заострённая морда, круглые
                // уши и длинный голый хвост. Раньше в этой ветке рисовался белый
                // заяц с длинными ушами (наследие удалённого вида «полярный беляк»,
                // который делил с крысой одну ветку), поэтому «крыса» в процедурном
                // резерве выглядела как заяц.
                val by = groundY + 0.17f
                furBuilder.addShadowDisc(x, groundY, z, 0.40f, 0.28f)
                furBuilder.addEllipsoid(
                    x, by, z, 0.13f, 0.13f, 0.23f, yawRad = yaw,
                    r = 0.62f, g = 0.58f, b = 0.54f, uvScale = 1.5f
                )
                // Заострённая морда
                val hx = x + fwdX * 0.22f
                val hz = z + fwdZ * 0.22f
                furBuilder.addTaperedCylinder(
                    x + fwdX * 0.12f, by + 0.02f, z + fwdZ * 0.12f,
                    hx + fwdX * 0.07f, by - 0.01f, hz + fwdZ * 0.07f,
                    0.085f, 0.035f, 10, 0.66f, 0.62f, 0.58f
                )
                // Круглые уши (короткие конусы, а не заячьи «лопухи»)
                for (side in floatArrayOf(1f, -1f)) {
                    furBuilder.addTaperedCylinder(
                        hx - fwdX * 0.05f + rightX * 0.055f * side, by + 0.05f,
                        hz - fwdZ * 0.05f + rightZ * 0.055f * side,
                        hx - fwdX * 0.07f + rightX * 0.075f * side, by + 0.13f,
                        hz - fwdZ * 0.07f + rightZ * 0.075f * side,
                        0.042f, 0.014f, 8, 0.58f, 0.50f, 0.48f
                    )
                }
                // Длинный голый хвост из двух сегментов
                val tx = x - fwdX * 0.22f
                val tz = z - fwdZ * 0.22f
                furBuilder.addTaperedCylinder(
                    x - fwdX * 0.12f, by - 0.01f, z - fwdZ * 0.12f,
                    tx, by - 0.03f, tz,
                    0.028f, 0.018f, 6, 0.72f, 0.60f, 0.58f
                )
                furBuilder.addTaperedCylinder(
                    tx, by - 0.03f, tz,
                    tx - fwdX * 0.16f + rightX * sin(animPhase) * 0.05f, groundY + 0.05f,
                    tz - fwdZ * 0.16f + rightZ * sin(animPhase) * 0.05f,
                    0.018f, 0.006f, 6, 0.72f, 0.60f, 0.58f
                )
                // 4 короткие лапы с противофазным шагом
                val ratLegs = listOf(
                    Triple(0.07f, 0.13f, stride),
                    Triple(-0.07f, 0.13f, -stride),
                    Triple(0.08f, -0.12f, -stride),
                    Triple(-0.08f, -0.12f, stride)
                )
                for ((lx, lz, swing) in ratLegs) {
                    val px2 = x + rightX * lx + fwdX * lz
                    val pz2 = z + rightZ * lx + fwdZ * lz
                    furBuilder.addTaperedCylinder(
                        px2, by - 0.08f, pz2,
                        px2 + sin(swing) * 0.04f, groundY + 0.015f, pz2 + cos(swing) * 0.02f,
                        0.026f, 0.016f, 6, 0.56f, 0.52f, 0.48f
                    )
                }
            }

            WildlifeKind.NORTHERN_DEER -> {
                val torsoY = groundY + 1.02f
                furBuilder.addShadowDisc(x, groundY, z, 1.05f, 0.36f)
                // Плавное туловище северного оленя (эллипсоиды вместо коробок)
                furBuilder.addEllipsoid(x, torsoY, z, 0.32f, 0.33f, 0.72f, yawRad = yaw, r = 0.68f, g = 0.53f, b = 0.40f, uvScale = 2.2f)
                val nx = x + fwdX * 0.56f; val nz = z + fwdZ * 0.56f
                furBuilder.addTaperedCylinder(
                    x + fwdX * 0.34f, torsoY + 0.10f, z + fwdZ * 0.34f,
                    nx, torsoY + 0.52f, nz,
                    0.22f, 0.15f, 12, 0.84f, 0.78f, 0.72f
                )
                val hx = x + fwdX * 0.78f; val hz = z + fwdZ * 0.78f; val hy = torsoY + 0.66f
                furBuilder.addEllipsoid(hx, hy, hz, 0.14f, 0.14f, 0.26f, yawRad = yaw, r = 0.66f, g = 0.52f, b = 0.40f, uvScale = 1.4f)
                // Ветвистые рога оленя
                for (side in listOf(-1f, 1f)) {
                    val rx = hx + rightX * 0.11f * side
                    val rz = hz + rightZ * 0.11f * side
                    barkHornBuilder.addTaperedCylinder(
                        rx, hy + 0.10f, rz,
                        rx + rightX * 0.26f * side - fwdX * 0.12f, hy + 0.58f, rz + rightZ * 0.26f * side - fwdZ * 0.12f,
                        0.032f, 0.016f, 12, 0.90f, 0.84f, 0.74f
                    )
                    barkHornBuilder.addTaperedCylinder(
                        rx + rightX * 0.14f * side, hy + 0.34f, rz + rightZ * 0.14f * side,
                        rx + rightX * 0.24f * side + fwdX * 0.22f, hy + 0.54f, rz + rightZ * 0.24f * side + fwdZ * 0.22f,
                        0.022f, 0.012f, 12, 0.92f, 0.86f, 0.76f
                    )
                    barkHornBuilder.addTaperedCylinder(
                        rx + rightX * 0.06f * side, hy + 0.22f, rz + rightZ * 0.06f * side,
                        rx + rightX * 0.30f * side + fwdX * 0.05f, hy + 0.38f, rz + rightZ * 0.30f * side + fwdZ * 0.05f,
                        0.020f, 0.010f, 12, 0.90f, 0.84f, 0.74f
                    )
                }
                // 4 стройные скруглённые ноги с противофазным шагом
                val legs = listOf(
                    Triple(0.20f, 0.46f, stride),
                    Triple(-0.20f, 0.46f, -stride),
                    Triple(0.20f, -0.46f, -stride),
                    Triple(-0.20f, -0.46f, stride)
                )
                for ((lx, lz, swing) in legs) {
                    val px2 = x + rightX * lx + fwdX * lz
                    val pz2 = z + rightZ * lx + fwdZ * lz
                    val kneeX = px2 + sin(swing) * 0.06f
                    val kneeZ = pz2 + cos(swing) * 0.02f
                    furBuilder.addTaperedCylinder(px2, groundY + 0.86f, pz2, kneeX, groundY + 0.46f, kneeZ, 0.075f, 0.052f, 12, 0.56f, 0.43f, 0.33f)
                    furBuilder.addTaperedCylinder(kneeX, groundY + 0.46f, kneeZ, kneeX + sin(swing) * 0.10f, groundY + 0.02f, kneeZ + cos(swing) * 0.04f, 0.050f, 0.030f, 12, 0.50f, 0.38f, 0.29f)
                }
            }

            WildlifeKind.TIMBER_WOLF -> {
                val torsoY = groundY + 0.60f
                furBuilder.addShadowDisc(x, groundY, z, 0.88f, 0.38f)
                furBuilder.addEllipsoid(x, torsoY, z, 0.24f, 0.24f, 0.58f, yawRad = yaw, r = 0.46f, g = 0.48f, b = 0.53f, uvScale = 2.0f)
                furBuilder.addEllipsoid(x + fwdX * 0.24f, torsoY + 0.06f, z + fwdZ * 0.24f, 0.25f, 0.25f, 0.24f, yawRad = yaw, r = 0.54f, g = 0.56f, b = 0.60f, uvScale = 1.3f)
                val hx = x + fwdX * 0.64f; val hz = z + fwdZ * 0.64f; val hy = torsoY + 0.14f
                furBuilder.addEllipsoid(hx, hy, hz, 0.15f, 0.14f, 0.21f, yawRad = yaw, r = 0.50f, g = 0.52f, b = 0.57f, uvScale = 1.2f)
                furBuilder.addEllipsoid(hx + fwdX * 0.17f, hy - 0.03f, hz + fwdZ * 0.17f, 0.08f, 0.065f, 0.11f, yawRad = yaw, r = 0.42f, g = 0.44f, b = 0.49f)
                // Острые скруглённые уши
                for (side in floatArrayOf(1f, -1f)) {
                    furBuilder.addTaperedCylinder(
                        hx + rightX * 0.08f * side, hy + 0.10f, hz + rightZ * 0.08f * side,
                        hx + rightX * 0.115f * side - fwdX * 0.015f, hy + 0.27f, hz + rightZ * 0.115f * side - fwdZ * 0.015f,
                        0.048f, 0.010f, 12, 0.36f, 0.38f, 0.42f
                    )
                }
                // Пушистый изогнутый хвост волка
                furBuilder.addTaperedCylinder(x - fwdX * 0.52f, torsoY + 0.02f, z - fwdZ * 0.52f, x - fwdX * 0.92f, torsoY - 0.14f, z - fwdZ * 0.92f, 0.085f, 0.042f, 12, 0.40f, 0.42f, 0.47f)
                furBuilder.addEllipsoid(x - fwdX * 0.95f, torsoY - 0.16f, z - fwdZ * 0.95f, 0.05f, 0.05f, 0.08f, yawRad = yaw, r = 0.36f, g = 0.38f, b = 0.43f)
                // Светящиеся янтарные глаза хищника в темноте
                val ex = hx + fwdX * 0.19f; val ez = hz + fwdZ * 0.19f
                glowBuilder.addEllipsoid(ex + rightX * 0.065f, hy + 0.03f, ez + rightZ * 0.065f, 0.022f, 0.022f, 0.016f, latBands = 6, lonSegs = 10, r = 1f, g = 0.72f, b = 0.15f)
                glowBuilder.addEllipsoid(ex - rightX * 0.065f, hy + 0.03f, ez - rightZ * 0.065f, 0.022f, 0.022f, 0.016f, latBands = 6, lonSegs = 10, r = 1f, g = 0.72f, b = 0.15f)
                val legs = listOf(
                    Triple(0.15f, 0.36f, stride * 1.2f),
                    Triple(-0.15f, 0.36f, -stride * 1.2f),
                    Triple(0.15f, -0.36f, -stride * 1.2f),
                    Triple(-0.15f, -0.36f, stride * 1.2f)
                )
                for ((lx, lz, swing) in legs) {
                    val px2 = x + rightX * lx + fwdX * lz
                    val pz2 = z + rightZ * lx + fwdZ * lz
                    furBuilder.addTaperedCylinder(px2, groundY + 0.50f, pz2, px2 + sin(swing) * 0.07f, groundY + 0.02f, pz2 + cos(swing) * 0.03f, 0.062f, 0.032f, 12, 0.42f, 0.44f, 0.49f)
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
            // §7.11: пустые руки — геометрии нет (герой начинает забег без инструмента)
            EquippedTool.NONE -> Unit
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

        /** Полуразмер мира и сетка мирового рельефа — одна правда для меша и пропов. */
        const val WORLD_HALF_EXTENT = 168f

        private fun worldGridSteps(qualitySteps: Int): Int = qualitySteps.coerceIn(120, 300)

        /**
         * Сеточная высота мирового рельефа: билинейная интерполяция по ТОЙ ЖЕ сетке,
         * что и меш рельефа. Пропы и звери, поставленные по ней, НЕ висят в воздухе
         * и не тонут в земле.
         */
        fun sampleWorldHeight(x: Float, z: Float, steps: Int): Float {
            val s = worldGridSteps(steps)
            val cell = (WORLD_HALF_EXTENT * 2f) / s
            val fx = ((x + WORLD_HALF_EXTENT) / cell).coerceIn(0f, s - 0.001f)
            val fz = ((z + WORLD_HALF_EXTENT) / cell).coerceIn(0f, s - 0.001f)
            val gx = fx.toInt()
            val gz = fz.toInt()
            val tx = fx - gx
            val tz = fz - gz
            val x0 = -WORLD_HALF_EXTENT + gx * cell
            val z0 = -WORLD_HALF_EXTENT + gz * cell
            val h00 = WorldPopulator.worldHeight(x0, z0)
            val h10 = WorldPopulator.worldHeight(x0 + cell, z0)
            val h01 = WorldPopulator.worldHeight(x0, z0 + cell)
            val h11 = WorldPopulator.worldHeight(x0 + cell, z0 + cell)
            val h0 = h00 + (h10 - h00) * tx
            val h1 = h01 + (h11 - h01) * tx
            return h0 + (h1 - h0) * tz
        }

        /** Меш единого большого мира: все районы, смешение рельефа, кряж по краю. */
        fun buildWorldTerrainIntoBuilder(steps: Int): MeshBuilder {
            val s = worldGridSteps(steps)
            val half = WORLD_HALF_EXTENT
            val gCount = s + 1
            val cell = (half * 2f) / s
            val heights = FloatArray(gCount * gCount)
            val normX = FloatArray(gCount * gCount)
            val normY = FloatArray(gCount * gCount)
            val normZ = FloatArray(gCount * gCount)
            val vertAo = FloatArray(gCount * gCount)

            for (gz in 0..s) {
                val z = -half + gz * cell
                for (gx in 0..s) {
                    val x = -half + gx * cell
                    heights[gz * gCount + gx] = WorldPopulator.worldHeight(x, z)
                }
            }

            for (gz in 0..s) {
                for (gx in 0..s) {
                    val idx = gz * gCount + gx
                    val hL = heights[gz * gCount + max(gx - 1, 0)]
                    val hR = heights[gz * gCount + minOf(gx + 1, s)]
                    val hD = heights[max(gz - 1, 0) * gCount + gx]
                    val hU = heights[minOf(gz + 1, s) * gCount + gx]
                    val hC = heights[idx]
                    val dx = (hL - hR) / (2f * cell)
                    val dz = (hD - hU) / (2f * cell)
                    val inv = 1f / sqrt(dx * dx + 1f + dz * dz)
                    normX[idx] = dx * inv
                    normY[idx] = inv
                    normZ[idx] = dz * inv
                    val avgNeighbors = (hL + hR + hD + hU) * 0.25f
                    val cavity = ((hC - avgNeighbors) * 0.55f).coerceIn(-0.18f, 0.10f)
                    // Утоптанная тропа между районами (лучи от центра к каждому району)
                    val x = -half + gx * cell
                    val z = -half + gz * cell
                    val onPath = if (pathFactor(x, z) > 0f) 0.92f else 1f
                    vertAo[idx] = ((0.94f + cavity) * onPath).coerceIn(0.74f, 1.03f)
                }
            }

            val mb = MeshBuilder(s * s * 6 * GlMesh.FLOATS_PER_VERTEX)
            val uvScale = 0.52f
            for (gz in 0 until s) {
                val z0 = -half + gz * cell
                val z1 = z0 + cell
                for (gx in 0 until s) {
                    val x0 = -half + gx * cell
                    val x1 = x0 + cell
                    val i00 = gz * gCount + gx
                    val i10 = gz * gCount + (gx + 1)
                    val i01 = (gz + 1) * gCount + gx
                    val i11 = (gz + 1) * gCount + (gx + 1)
                    val u0 = x0 * uvScale
                    val u1 = x1 * uvScale
                    val v0 = z0 * uvScale
                    val v1 = z1 * uvScale
                    fun emit(i: Int, x: Float, z: Float, u: Float, v: Float) {
                        val ao = vertAo[i]
                        val t = terrainTintAt(x, z)
                        mb.addVertex(
                            x, heights[i], z,
                            normX[i], normY[i], normZ[i],
                            u, v,
                            ao * 0.99f * t[0], ao * t[1], (ao * 1.02f).coerceAtMost(1.04f) * t[2], 1f
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

        /** Утоптанные тропы-лучи от центра мира к каждому району (для АО рельефа). */
        private fun pathFactor(x: Float, z: Float): Float {
            val targets = arrayOf(
                floatArrayOf(0f, -120f), floatArrayOf(120f, 0f),
                floatArrayOf(-120f, 0f), floatArrayOf(0f, 120f)
            )
            for (t in targets) {
                val dx = t[0] - x
                val dz = t[1] - z
                val len = sqrt(dx * dx + dz * dz)
                if (len < 1f) return 1f
                val tPar = ((x * dx + z * dz) / (len * len)).coerceIn(0f, 1f)
                val cx = x + dx * tPar
                val cz = z + dz * tPar
                if (cx * cx + cz * cz < 1.7f * 1.7f) return 1f
            }
            return 0f
        }

        /** Лёгкая подкраска рельефа района: шахта холоднее, озеро синее, тайга ярче. */
        private fun terrainTintAt(x: Float, z: Float): FloatArray = when (com.ayaks.winterforest.domain.world.WorldRegions.regionAt(x, z)) {
            com.ayaks.winterforest.domain.model.LocationId.ABANDONED_MINE -> floatArrayOf(0.86f, 0.88f, 0.97f)
            com.ayaks.winterforest.domain.model.LocationId.FROZEN_LAKE -> floatArrayOf(0.97f, 1.0f, 1.05f)
            com.ayaks.winterforest.domain.model.LocationId.DENSE_SPRUCE -> floatArrayOf(0.97f, 1.0f, 0.98f)
            else -> floatArrayOf(1f, 1f, 1f)
        }
    }

    /** Копирует вершины другого билдера со сдвигом X/Z (раскладка районов большого мира). */
    fun appendTranslatedFrom(other: MeshBuilder, dx: Float, dz: Float) {
        val src = other.rawArray()
        val n = other.floatSize
        if (floatSize + n > data.size) {
            var cap = data.size
            while (cap < floatSize + n) cap *= 2
            data = data.copyOf(cap)
        }
        System.arraycopy(src, 0, data, floatSize, n)
        var o = floatSize
        while (o < floatSize + n) {
            data[o] += dx
            data[o + 2] += dz
            o += GlMesh.FLOATS_PER_VERTEX
        }
        floatSize += n
    }
}
