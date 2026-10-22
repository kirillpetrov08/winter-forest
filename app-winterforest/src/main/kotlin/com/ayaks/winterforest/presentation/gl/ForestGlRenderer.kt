package com.ayaks.winterforest.presentation.gl

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.GameRunState
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.ResourceNode
import com.ayaks.winterforest.domain.model.WorldObjectKind
import com.ayaks.winterforest.domain.world.WorldPopulator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

data class RenderSceneSnapshot(
    val run: GameRunState = GameRunState(),
    val base: BaseProgress = BaseProgress()
)

class ForestGlRenderer : GLSurfaceView.Renderer {

    @Volatile
    var snapshot: RenderSceneSnapshot = RenderSceneSnapshot()

    private var viewportWidth = 1080
    private var viewportHeight = 1920

    private var worldProgram = 0
    private var skyProgram = 0
    private var skyQuadBuffer: FloatBuffer? = null

    private val textures = mutableMapOf<ProceduralTextureId, Int>()
    private var uploadedPaletteLocation: LocationId? = null
    private var cachedTerrainLocation: LocationId? = null
    private var terrainMesh: GlMesh? = null
    private var cachedLakeIceMesh: GlMesh? = null

    private val dynamicFoliageBuilder = MeshBuilder(16384)
    private val dynamicBarkBuilder = MeshBuilder(8192)
    private val dynamicStoneBuilder = MeshBuilder(8192)
    private val dynamicClothBuilder = MeshBuilder(4096)
    private val dynamicTimberBuilder = MeshBuilder(8192)
    private val dynamicFireBuilder = MeshBuilder(4096)

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        worldProgram = createProgram(Shaders.WORLD_VERTEX_SHADER, Shaders.WORLD_FRAGMENT_SHADER)
        skyProgram = createProgram(Shaders.SKY_VERTEX_SHADER, Shaders.SKY_FRAGMENT_SHADER)

        val quadCoords = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            -1f, 1f,
            1f, -1f,
            1f, 1f
        )
        skyQuadBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(quadCoords)
                position(0)
            }

        Matrix.setIdentityM(modelMatrix, 0)
        ensureLocationAssets(snapshot.run.currentLocation)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        Matrix.perspectiveM(projMatrix, 0, 66f, aspect, 0.15f, 110f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val snap = snapshot
        val run = snap.run
        val base = snap.base
        val loc = run.currentLocation

        ensureLocationAssets(loc)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // 1. Суточное освещение и туман (плавный переход день 6 мин / ночь 4 мин)
        val cycleT = (run.elapsedRealSeconds % GameRunState.FULL_CYCLE_SECONDS) / GameRunState.FULL_CYCLE_SECONDS
        val daylight = computeDaylightFactor(cycleT)
        val mineDarkness = if (loc.requiresLightForVisibility && !run.hasActiveLightSource) 0.22f else 1.0f
        val effectiveLight = (daylight * mineDarkness).coerceIn(0.10f, 1.0f)

        val pal = loc.palette
        val zenith = scaleHexColor(pal.skyZenithHex, (0.22f + 0.78f * daylight))
        val horizon = scaleHexColor(pal.skyHorizonHex, (0.20f + 0.80f * daylight))
        val fogCol = scaleHexColor(pal.fogHex, (0.18f + 0.82f * effectiveLight))
        val sunCol = scaleHexColor(pal.sunLightHex, (0.12f + 0.92f * effectiveLight))

        // 2. Рендер купола неба
        drawSkyGradient(zenith, horizon, sunCol, cycleT)

        // 3. Камера от первого лица с лёгким покачиванием шага
        val eyeHeight = 1.72f
        val camX = run.playerX
        val camY = run.playerY + eyeHeight
        val camZ = run.playerZ
        val yawRad = Math.toRadians(run.yawDeg.toDouble()).toFloat()
        val pitchRad = Math.toRadians(run.pitchDeg.toDouble()).toFloat()
        val lookX = camX + sin(yawRad) * cos(pitchRad)
        val lookY = camY + sin(pitchRad)
        val lookZ = camZ - cos(yawRad) * cos(pitchRad)

        Matrix.setLookAtM(viewMatrix, 0, camX, camY, camZ, lookX, lookY, lookZ, 0f, 1f, 0f)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
        Matrix.setIdentityM(modelMatrix, 0)
        System.arraycopy(vpMatrix, 0, mvpMatrix, 0, 16)

        // Точечный тёплый источник света: костёр базы либо смоляной факел / походный огонь игрока
        val pointLightPos: FloatArray
        val pointLightRadius: Float
        if (loc == LocationId.BASE_CLEARING) {
            pointLightPos = floatArrayOf(0f, WorldPopulator.terrainHeight(loc, 0f, 0f) + 1.2f, 0f)
            pointLightRadius = 14f + base.campfireLevel * 2.5f
        } else if (run.resinTorchRemainingSec > 0f || run.portableFireRemainingSec > 0f) {
            pointLightPos = floatArrayOf(camX, camY - 0.2f, camZ)
            pointLightRadius = if (run.resinTorchRemainingSec > 0f) 18f else 11f
        } else {
            pointLightPos = floatArrayOf(0f, -999f, 0f)
            pointLightRadius = 0.01f
        }

        val fogNear = if (loc.requiresLightForVisibility && !run.hasActiveLightSource) {
            2.5f
        } else if (run.isSnowing) {
            pal.fogNear * 0.72f
        } else {
            pal.fogNear
        }
        val fogFar = if (loc.requiresLightForVisibility && !run.hasActiveLightSource) {
            10.5f
        } else if (run.isSnowing) {
            pal.fogFar * 0.75f
        } else {
            pal.fogFar
        }

        GLES20.glUseProgram(worldProgram)
        setWorldUniforms(
            camX = camX,
            camY = camY,
            camZ = camZ,
            sunAngle = cycleT * (Math.PI.toFloat() * 2f),
            sunColor = sunCol,
            ambientSky = floatArrayOf(horizon[0] * 0.55f, horizon[1] * 0.58f, horizon[2] * 0.65f),
            pointPos = pointLightPos,
            pointRadius = pointLightRadius,
            fogColor = fogCol,
            fogNear = fogNear,
            fogFar = fogFar
        )

        // 4. Рельеф снега (или ледяной щит на Замёрзшем озере)
        val groundTex = if (loc == LocationId.ABANDONED_MINE) {
            ProceduralTextureId.STONE
        } else {
            ProceduralTextureId.SNOW
        }
        terrainMesh?.let { drawMesh(it, textures[groundTex] ?: 0, unlit = false) }
        if (loc == LocationId.FROZEN_LAKE) {
            cachedLakeIceMesh?.let { drawMesh(it, textures[ProceduralTextureId.ICE] ?: 0, unlit = false) }
        }

        // 5. Наполнение локации: постройки базы, деревья, камни, тайники, частицы снега и пламя
        rebuildDynamicBatches(run, base)
        drawMesh(dynamicBarkBuilder.build(), textures[ProceduralTextureId.WOOD_BARK] ?: 0, unlit = false)
        drawMesh(dynamicFoliageBuilder.build(), textures[ProceduralTextureId.PINE_FOLIAGE] ?: 0, unlit = false)
        drawMesh(dynamicStoneBuilder.build(), textures[ProceduralTextureId.STONE] ?: 0, unlit = false)
        drawMesh(dynamicTimberBuilder.build(), textures[ProceduralTextureId.TIMBER] ?: 0, unlit = false)
        drawMesh(dynamicClothBuilder.build(), textures[ProceduralTextureId.CLOTH] ?: 0, unlit = false)
        drawMesh(dynamicFireBuilder.build(), textures[ProceduralTextureId.FIRE_EMBER] ?: 0, unlit = true)
    }

    private fun ensureLocationAssets(location: LocationId) {
        if (uploadedPaletteLocation != location || textures.isEmpty()) {
            textures.values.forEach { id ->
                if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
            }
            textures.clear()
            for (kind in ProceduralTextureId.entries) {
                val texHandle = IntArray(1)
                GLES20.glGenTextures(1, texHandle, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texHandle[0])
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR_MIPMAP_LINEAR
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR
                )
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)

                val buf = ProceduralTextures.generateRgbaBuffer(
                    id = kind,
                    palette = location.palette,
                    size = ProceduralTextures.DEFAULT_TEXTURE_SIZE,
                    seed = 2026 + location.ordinal * 37
                )
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_RGBA,
                    ProceduralTextures.DEFAULT_TEXTURE_SIZE,
                    ProceduralTextures.DEFAULT_TEXTURE_SIZE,
                    0,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    buf
                )
                GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
                textures[kind] = texHandle[0]
            }
            uploadedPaletteLocation = location
        }

        if (cachedTerrainLocation != location || terrainMesh == null) {
            terrainMesh = MeshBuilder.buildTerrainMesh(location)
            cachedLakeIceMesh = if (location == LocationId.FROZEN_LAKE) {
                MeshBuilder(256).apply {
                    addQuad(
                        -23f, -0.18f, 23f,
                        23f, -0.18f, 23f,
                        23f, -0.18f, -23f,
                        -23f, -0.18f, -23f,
                        0f, 1f, 0f,
                        10f, 10f,
                        0.92f, 0.97f, 1.0f, 0.94f
                    )
                }.build()
            } else {
                null
            }
            cachedTerrainLocation = location
        }
    }

    private fun rebuildDynamicBatches(run: GameRunState, base: BaseProgress) {
        dynamicBarkBuilder.clear()
        dynamicFoliageBuilder.clear()
        dynamicStoneBuilder.clear()
        dynamicTimberBuilder.clear()
        dynamicClothBuilder.clear()
        dynamicFireBuilder.clear()

        val loc = run.currentLocation
        val t = run.elapsedRealSeconds

        // Постройки на Базовой поляне и в Охотничьем домике
        if (loc == LocationId.BASE_CLEARING) {
            val baseY = WorldPopulator.terrainHeight(loc, 0f, 0f)
            // Каменное кольцо и пламя костра
            for (i in 0 until 8) {
                val a = (i / 8f) * Math.PI.toFloat() * 2f
                dynamicStoneBuilder.addBox(cos(a) * 0.95f, baseY, sin(a) * 0.95f, 0.42f, 0.28f, 0.42f)
            }
            dynamicBarkBuilder.addBox(0f, baseY + 0.08f, 0f, 1.15f, 0.22f, 0.26f)
            dynamicBarkBuilder.addBox(0f, baseY + 0.14f, 0f, 0.26f, 0.22f, 1.15f)
            val flicker = 0.85f + 0.22f * sin(t * 9.5f)
            dynamicFireBuilder.addCone(0f, baseY + 0.22f, 0f, 0.55f, (1.15f + base.campfireLevel * 0.16f) * flicker, 6, 1f, 0.85f, 0.45f, 0.95f)

            // Деревянное укрытие (масштабируется с уровнем укрытия)
            val shY = WorldPopulator.terrainHeight(loc, -6.5f, -5.5f)
            val shH = 2.5f + base.shelterLevel * 0.22f
            dynamicTimberBuilder.addBox(-6.5f, shY, -5.5f, 4.6f, shH, 4.0f)
            dynamicFoliageBuilder.addCone(-6.5f, shY + shH, -5.5f, 3.5f, 1.65f, 4, 0.95f, 0.97f, 1.0f, 1f)

            // Верстак
            val wbY = WorldPopulator.terrainHeight(loc, 4.8f, -4.5f)
            dynamicTimberBuilder.addBox(4.8f, wbY, -4.5f, 2.0f, 1.05f, 1.1f)
            if (base.thermosInstalledOnBase) {
                dynamicStoneBuilder.addBox(5.3f, wbY + 1.05f, -4.5f, 0.36f, 0.62f, 0.36f, 0.85f, 0.95f, 1f, 1f)
            }
        } else if (loc == LocationId.HUNTERS_CABIN) {
            val cabY = WorldPopulator.terrainHeight(loc, 7.2f, 8.8f)
            dynamicTimberBuilder.addBox(7.2f, cabY, 8.8f, 5.2f, 3.0f, 4.6f)
            dynamicFoliageBuilder.addCone(7.2f, cabY + 3.0f, 8.8f, 4.0f, 1.9f, 4, 0.95f, 0.94f, 0.96f, 1f)
            dynamicStoneBuilder.addBox(8.9f, cabY + 3.0f, 7.6f, 0.7f, 1.6f, 0.7f)
        } else if (loc == LocationId.ABANDONED_MINE) {
            // Деревянные крепи штольни
            for (zStep in listOf(-14f, -6f, 2f, 10f, 18f)) {
                val gy = WorldPopulator.terrainHeight(loc, 0f, zStep)
                dynamicTimberBuilder.addBox(-3.2f, gy, zStep, 0.45f, 3.4f, 0.45f)
                dynamicTimberBuilder.addBox(3.2f, gy, zStep, 0.45f, 3.4f, 0.45f)
                dynamicTimberBuilder.addBox(0f, gy + 3.3f, zStep, 7.0f, 0.42f, 0.50f)
            }
        }

        // Маркер пограничной тропы перехода в соседнюю зону
        val portalY = WorldPopulator.terrainHeight(loc, 0f, -35.5f)
        dynamicTimberBuilder.addBox(-1.6f, portalY, -35.5f, 0.35f, 3.0f, 0.35f)
        dynamicTimberBuilder.addBox(1.6f, portalY, -35.5f, 0.35f, 3.0f, 0.35f)
        dynamicTimberBuilder.addBox(0f, portalY + 2.75f, -35.5f, 3.8f, 0.38f, 0.38f)

        // Стена вековых елей вдоль границы мира, чтобы край мира выглядел естественно
        for (i in 0 until 28) {
            val ang = (i / 28f) * Math.PI.toFloat() * 2f
            val bx = cos(ang) * 40.5f
            val bz = sin(ang) * 40.5f
            val by = WorldPopulator.terrainHeight(loc, bx, bz)
            addSpruceTree(bx, by, bz, scale = 1.35f)
        }

        // Интерактивные ресурсные узлы и тайники спецлута
        for (node in run.currentLocationNodes()) {
            if (!node.isAvailable) continue
            addWorldNodeGeometry(loc, node, t)
        }

        // Инструмент / факел от первого лица + снежные частицы погоды
        addFirstPersonItemAndWeather(run)
    }

    private fun addWorldNodeGeometry(loc: LocationId, node: ResourceNode, timeSec: Float) {
        val y = WorldPopulator.terrainHeight(loc, node.x, node.z)
        when (node.kind) {
            WorldObjectKind.PINE_TREE -> addSpruceTree(node.x, y, node.z, scale = 1.0f)
            WorldObjectKind.BERRY_BUSH -> {
                dynamicFoliageBuilder.addShadowDisc(node.x, y, node.z, 1.1f)
                dynamicFoliageBuilder.addCone(node.x, y, node.z, 0.95f, 1.15f, 6, 0.85f, 0.95f, 0.88f, 1f)
                dynamicClothBuilder.addBox(node.x + 0.22f, y + 0.45f, node.z + 0.18f, 0.18f, 0.18f, 0.18f, 1f, 0.25f, 0.25f, 1f)
                dynamicClothBuilder.addBox(node.x - 0.20f, y + 0.58f, node.z - 0.15f, 0.16f, 0.16f, 0.16f, 1f, 0.22f, 0.28f, 1f)
            }
            WorldObjectKind.STONE_OUTCROP -> {
                dynamicStoneBuilder.addBox(node.x, y - 0.1f, node.z, 1.45f, 1.05f, 1.25f)
                dynamicStoneBuilder.addBox(node.x + 0.55f, y - 0.1f, node.z - 0.35f, 0.85f, 0.72f, 0.85f)
            }
            WorldObjectKind.SUPPLY_CRATE -> {
                dynamicTimberBuilder.addBox(node.x, y, node.z, 1.05f, 0.90f, 1.05f)
                dynamicClothBuilder.addBox(node.x, y + 0.90f, node.z, 0.85f, 0.16f, 0.85f)
            }
            WorldObjectKind.RESIN_STUMP -> {
                dynamicBarkBuilder.addBox(node.x, y, node.z, 0.85f, 0.95f, 0.85f)
                dynamicFireBuilder.addBox(node.x + 0.38f, y + 0.42f, node.z, 0.20f, 0.28f, 0.20f, 1f, 0.72f, 0.20f, 0.9f)
            }
            WorldObjectKind.ICE_HOLE -> {
                dynamicStoneBuilder.addBox(node.x, y - 0.05f, node.z, 1.5f, 0.18f, 1.5f, 0.55f, 0.80f, 0.98f, 1f)
                dynamicTimberBuilder.addBox(node.x + 0.9f, y, node.z, 0.16f, 1.35f, 0.16f)
            }
            WorldObjectKind.COAL_SEAM -> {
                dynamicStoneBuilder.addBox(node.x, y - 0.1f, node.z, 1.5f, 1.15f, 1.3f, 0.32f, 0.34f, 0.38f, 1f)
            }
            WorldObjectKind.IRON_VEIN -> {
                dynamicStoneBuilder.addBox(node.x, y - 0.1f, node.z, 1.4f, 1.2f, 1.25f, 0.82f, 0.56f, 0.42f, 1f)
            }
            WorldObjectKind.GAME_CARCASS -> {
                dynamicClothBuilder.addBox(node.x, y, node.z, 1.25f, 0.62f, 0.78f, 0.72f, 0.44f, 0.36f, 1f)
            }
            WorldObjectKind.SPECIAL_CACHE -> {
                dynamicTimberBuilder.addBox(node.x, y, node.z, 1.25f, 0.82f, 0.90f, 1f, 0.88f, 0.62f, 1f)
                val beaconY = y + 1.15f + 0.18f * sin(timeSec * 3.2f)
                dynamicFireBuilder.addCone(node.x, beaconY, node.z, 0.28f, 0.65f, 5, 1f, 0.90f, 0.38f, 0.92f)
            }
        }
    }

    private fun addSpruceTree(x: Float, y: Float, z: Float, scale: Float) {
        dynamicFoliageBuilder.addShadowDisc(x, y, z, 2.1f * scale, alpha = 0.32f)
        dynamicBarkBuilder.addBox(x, y, z, 0.52f * scale, 2.2f * scale, 0.52f * scale)
        dynamicFoliageBuilder.addCone(x, y + 1.4f * scale, z, 2.15f * scale, 2.4f * scale, 7)
        dynamicFoliageBuilder.addCone(x, y + 3.0f * scale, z, 1.65f * scale, 2.1f * scale, 7)
        dynamicFoliageBuilder.addCone(x, y + 4.4f * scale, z, 1.05f * scale, 1.8f * scale, 7)
    }

    private fun addFirstPersonItemAndWeather(run: GameRunState) {
        val yawRad = Math.toRadians(run.yawDeg.toDouble()).toFloat()
        val fwdX = sin(yawRad)
        val fwdZ = -cos(yawRad)
        val rightX = cos(yawRad)
        val rightZ = sin(yawRad)

        val swing = sin((run.swingAnimRemainingSec / 0.35f).coerceIn(0f, 1f) * Math.PI.toFloat())
        val handX = run.playerX + fwdX * (0.62f + swing * 0.22f) + rightX * 0.34f
        val handY = run.playerY + 1.34f - swing * 0.16f
        val handZ = run.playerZ + fwdZ * (0.62f + swing * 0.22f) + rightZ * 0.34f

        dynamicTimberBuilder.addBox(handX, handY, handZ, 0.08f, 0.42f, 0.08f)
        if (run.resinTorchRemainingSec > 0f) {
            dynamicFireBuilder.addCone(handX, handY + 0.42f, handZ, 0.14f, 0.32f, 5, 1f, 0.86f, 0.35f, 0.95f)
        } else {
            dynamicStoneBuilder.addBox(handX, handY + 0.36f, handZ, 0.24f, 0.10f, 0.10f)
        }

        // Частицы снегопада вокруг камеры (в соответствии с погодой локации)
        if (run.isSnowing || run.currentLocation.snowfallChance >= 0.45f) {
            val t = run.elapsedRealSeconds
            val count = if (run.isSnowing) 72 else 30
            for (i in 0 until count) {
                val px = run.playerX + (((i * 73) % 24) - 12f) + sin(t * 1.4f + i) * 0.8f
                val pz = run.playerZ + (((i * 137) % 24) - 12f) + cos(t * 1.1f + i) * 0.8f
                val fall = ((i * 0.41f + t * 2.2f) % 8.0f)
                val py = run.playerY + 7.2f - fall
                dynamicFireBuilder.addBox(px, py, pz, 0.06f, 0.06f, 0.06f, 0.94f, 0.97f, 1f, 0.80f)
            }
        }
    }

    private fun drawSkyGradient(
        zenith: FloatArray,
        horizon: FloatArray,
        sunHalo: FloatArray,
        cycleT: Float
    ) {
        val quad = skyQuadBuffer ?: return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(skyProgram)

        GLES20.glUniform3fv(GLES20.glGetUniformLocation(skyProgram, "uSkyZenith"), 1, zenith, 0)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(skyProgram, "uSkyHorizon"), 1, horizon, 0)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(skyProgram, "uSunHaloColor"), 1, sunHalo, 0)
        val sunX = 0.25f + 0.50f * cos(cycleT * Math.PI.toFloat() * 2f) * 0.5f
        val sunY = 0.62f + 0.22f * sin(cycleT * Math.PI.toFloat() * 2f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(skyProgram, "uSunScreenUv"), sunX, sunY)

        val aQuad = GLES20.glGetAttribLocation(skyProgram, "aScreenQuad")
        quad.position(0)
        GLES20.glEnableVertexAttribArray(aQuad)
        GLES20.glVertexAttribPointer(aQuad, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(aQuad)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun setWorldUniforms(
        camX: Float, camY: Float, camZ: Float,
        sunAngle: Float,
        sunColor: FloatArray,
        ambientSky: FloatArray,
        pointPos: FloatArray,
        pointRadius: Float,
        fogColor: FloatArray,
        fogNear: Float,
        fogFar: Float
    ) {
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(worldProgram, "uMvpMatrix"), 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(worldProgram, "uModelMatrix"), 1, false, modelMatrix, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(worldProgram, "uCameraPos"), camX, camY, camZ)
        GLES20.glUniform3f(
            GLES20.glGetUniformLocation(worldProgram, "uSunDir"),
            cos(sunAngle) * 0.6f,
            (0.35f + 0.65f * sin(sunAngle)).coerceAtLeast(0.15f),
            0.45f
        )
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(worldProgram, "uSunColor"), 1, sunColor, 0)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(worldProgram, "uAmbientSky"), 1, ambientSky, 0)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(worldProgram, "uPointLightPos"), 1, pointPos, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(worldProgram, "uPointLightColor"), 1.0f, 0.68f, 0.32f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(worldProgram, "uPointLightRadius"), pointRadius)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(worldProgram, "uFogColor"), 1, fogColor, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(worldProgram, "uFogNear"), fogNear)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(worldProgram, "uFogFar"), fogFar)
    }

    private fun drawMesh(mesh: GlMesh, textureId: Int, unlit: Boolean) {
        if (mesh.vertexCount <= 0) return
        val buf = mesh.vertexBuffer
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(worldProgram, "uTexture"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(worldProgram, "uUnlit"), if (unlit) 1f else 0f)

        val aPos = GLES20.glGetAttribLocation(worldProgram, "aPosition")
        val aNorm = GLES20.glGetAttribLocation(worldProgram, "aNormal")
        val aUv = GLES20.glGetAttribLocation(worldProgram, "aTexCoord")
        val aCol = GLES20.glGetAttribLocation(worldProgram, "aColor")

        buf.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, GlMesh.STRIDE_BYTES, buf)

        buf.position(3)
        GLES20.glEnableVertexAttribArray(aNorm)
        GLES20.glVertexAttribPointer(aNorm, 3, GLES20.GL_FLOAT, false, GlMesh.STRIDE_BYTES, buf)

        buf.position(6)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, GlMesh.STRIDE_BYTES, buf)

        buf.position(8)
        GLES20.glEnableVertexAttribArray(aCol)
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, GlMesh.STRIDE_BYTES, buf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNorm)
        GLES20.glDisableVertexAttribArray(aUv)
        GLES20.glDisableVertexAttribArray(aCol)
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        fun computeDaylightFactor(cycleFraction: Float): Float {
            // 0.0..0.60 — день (6 мин), 0.60..1.00 — ночь (4 мин) с мягкими сумерками
            return when {
                cycleFraction < 0.08f -> 0.45f + (cycleFraction / 0.08f) * 0.55f
                cycleFraction < 0.52f -> 1.0f
                cycleFraction < 0.62f -> 1.0f - ((cycleFraction - 0.52f) / 0.10f) * 0.80f
                cycleFraction < 0.92f -> 0.20f
                else -> 0.20f + ((cycleFraction - 0.92f) / 0.08f) * 0.25f
            }.coerceIn(0.18f, 1.0f)
        }

        fun scaleHexColor(hex: Int, factor: Float): FloatArray {
            val r = (((hex ushr 16) and 0xFF) / 255f * factor).coerceIn(0f, 1f)
            val g = (((hex ushr 8) and 0xFF) / 255f * factor).coerceIn(0f, 1f)
            val b = ((hex and 0xFF) / 255f * factor).coerceIn(0f, 1f)
            return floatArrayOf(r, g, b)
        }
    }
}

class ForestGlSurfaceView(
    context: Context,
    val forestRenderer: ForestGlRenderer = ForestGlRenderer()
) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(forestRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}
