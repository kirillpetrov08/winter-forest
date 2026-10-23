package com.ayaks.winterforest.presentation.gl

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.GameRunState
import com.ayaks.winterforest.domain.model.GraphicsQuality
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.ResourceNode
import com.ayaks.winterforest.domain.model.WorldObjectKind
import com.ayaks.winterforest.domain.model.WildlifeKind
import com.ayaks.winterforest.domain.world.WorldPopulator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumMap
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RenderSceneSnapshot(
    val run: GameRunState = GameRunState(),
    val base: BaseProgress = BaseProgress(),
    val quality: GraphicsQuality = GraphicsQuality.HIGH,
    val menuOrbitMode: Boolean = false
)

/**
 * Высокопроизводительный аппаратный 3D-движок OpenGL ES 2.0/3.0 «Winter Forest»:
 * - 3 уровня кэширования GPU VBO (Static World VBOs + Dirty-Flag Node VBOs + Zero-Alloc Dynamic VBOs);
 * - 0 аллокаций ByteBuffer/FloatArray в onDrawFrame() -> стабильные 120+ FPS без GC-фризов
 *   (лимит 60 FPS снят: eglSwapInterval(0) + быстрый режим дисплея + интерполяция тиков);
 * - панорамный купол неба 360° с 3D-горами, звёздами и Северным сиянием;
 * - до 3 тёплых динамических источников света + блики на льду и металле + частицы удара и метели.
 */
class ForestGlRenderer(
    private val onFpsSample: ((Int) -> Unit)? = null,
    private val appContext: Context? = null
) : GLSurfaceView.Renderer {

    @Volatile
    var snapshot: RenderSceneSnapshot = RenderSceneSnapshot()
        set(value) {
            // Окно интерполяции: тики симуляции приходят на 20 Гц, а кадры — 90/120+
            // (лимит 60 FPS снят). Позицию игрока сглаживаем между тиками, чтобы
            // движение на высокой частоте кадров оставалось плавным.
            val prev = field
            val moved =
                value.run.playerX != prev.run.playerX ||
                value.run.playerY != prev.run.playerY ||
                value.run.playerZ != prev.run.playerZ
            if (moved) {
                if (!smoothInitialized ||
                    value.run.currentLocation != prev.run.currentLocation ||
                    snapDistanceExceeded(prev.run, value.run)
                ) {
                    // телепорт / смена локации — без «поездки» камеры через карту
                    smoothPrevX = value.run.playerX
                    smoothPrevY = value.run.playerY
                    smoothPrevZ = value.run.playerZ
                } else {
                    smoothPrevX = smoothCurX
                    smoothPrevY = smoothCurY
                    smoothPrevZ = smoothCurZ
                }
                smoothCurX = value.run.playerX
                smoothCurY = value.run.playerY
                smoothCurZ = value.run.playerZ
                smoothPrevBobSin = smoothCurBobSin
                smoothCurBobSin = kotlin.math.sin(value.run.walkBobPhaseRad)
                smoothStartNs = System.nanoTime()
                smoothInitialized = true
            }
            field = value
        }

    private fun snapDistanceExceeded(a: com.ayaks.winterforest.domain.model.GameRunState,
                                     b: com.ayaks.winterforest.domain.model.GameRunState): Boolean {
        val dx = b.playerX - a.playerX
        val dy = b.playerY - a.playerY
        val dz = b.playerZ - a.playerZ
        return (dx * dx + dy * dy + dz * dz) > SNAP_DISTANCE_M * SNAP_DISTANCE_M
    }

    // Состояние интерполяции позиции камеры между тиками (пишет UI-поток, читает GL)
    @Volatile private var smoothInitialized = false
    @Volatile private var smoothPrevX = 0f
    @Volatile private var smoothPrevY = 0f
    @Volatile private var smoothPrevZ = 0f
    @Volatile private var smoothCurX = 0f
    @Volatile private var smoothCurY = 0f
    @Volatile private var smoothCurZ = 0f
    @Volatile private var smoothPrevBobSin = 0f
    @Volatile private var smoothCurBobSin = 0f
    @Volatile private var smoothStartNs = 0L

    private var viewportWidth = 1920
    private var viewportHeight = 1080
    private var aspectRatio = 16f / 9f

    private var worldProgram = 0
    private var skyProgram = 0
    private var skyQuadVbo: Int = 0

    // Кэшированные handle-ссылки uniform/attribute (без glGetUniformLocation строками на каждом draw call!)
    private var aPositionLoc = -1
    private var aNormalLoc = -1
    private var aTexCoordLoc = -1
    private var aColorLoc = -1
    private var uMvpLoc = -1
    private var uModelLoc = -1
    private var uCamPosLoc = -1
    private var uSunDirLoc = -1
    private var uSunColLoc = -1
    private var uAmbSkyLoc = -1
    private var uPtPos1Loc = -1
    private var uPtColLoc = -1
    private var uPtRad1Loc = -1
    private var uPtPos2Loc = -1
    private var uPtRad2Loc = -1
    private var uPtPos3Loc = -1
    private var uPtRad3Loc = -1
    private var uFogColLoc = -1
    private var uFogNearLoc = -1
    private var uFogFarLoc = -1
    private var uUnlitLoc = -1
    private var uSpecLoc = -1
    private var uTexLoc = -1

    private var skyQuadAttrLoc = -1
    private var skyZenithLoc = -1
    private var skyHorizonLoc = -1
    private var skyFogLoc = -1
    private var skySunUvLoc = -1
    private var skySunHaloLoc = -1
    private var skyCamYawLoc = -1
    private var skyCamPitchLoc = -1
    private var skyAspectLoc = -1
    private var skyTimeLoc = -1
    private var skyAuroraLoc = -1

    private val textures = EnumMap<ProceduralTextureId, Int>(ProceduralTextureId::class.java)
    private var uploadedPaletteLocation: LocationId? = null
    private var uploadedQuality: GraphicsQuality? = null

    // Уровень 1: Статические VBO рельефа, дальнего леса и архитектуры локации (строятся 1 раз!)
    private var staticBuiltLocation: LocationId? = null
    private var staticBuiltQuality: GraphicsQuality? = null
    private var staticBuiltBaseSig: Int = -1
    private val terrainVbo = GlVbo()
    private val lakeIceVbo = GlVbo()
    private val staticBatches = EnumMap<ProceduralTextureId, GlVbo>(ProceduralTextureId::class.java)

    // Уровень 2: Интерактивные ресурсные узлы (перестраиваются ТОЛЬКО при сборе/респавне узла!)
    private var cachedNodeStateHash: Int = Int.MIN_VALUE
    private val nodeBatches = EnumMap<ProceduralTextureId, GlVbo>(ProceduralTextureId::class.java)

    // Уровень 2.5: Импортированные 3D-модели (Poly Haven: камни, кострище, топор, ветви, растение, деревья)
    private val assetMeshCache = HashMap<String, FloatArray>()
    private val assetTextures = HashMap<String, Int>()
    private val assetPropBatches = HashMap<String, GlVbo>()
    private var assetPropsBuiltLoc: LocationId? = null
    private var assetTexturesQuality: GraphicsQuality? = null

    // Уровень 3: Быстрые динамические VBO (животные, оружие в руках, пламя, искры, метель) — 0 аллокаций!
    private val dynBuilders = EnumMap<ProceduralTextureId, MeshBuilder>(ProceduralTextureId::class.java)
    private val dynVbos = EnumMap<ProceduralTextureId, GlVbo>(ProceduralTextureId::class.java)
    private var reusableUploadBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(65536 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    // Преаллоцированные 3D-массивы без мусора в куче
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val zenithRgb = FloatArray(3)
    private val horizonRgb = FloatArray(3)
    private val fogRgb = FloatArray(3)
    private val sunRgb = FloatArray(3)
    private val ambientRgb = FloatArray(3)
    private val ptLight1 = FloatArray(3)
    private val ptLight2 = FloatArray(3)
    private val ptLight3 = FloatArray(3)

    private var orbitTimeNs: Long = 0L
    private var fpsWindowStartNs: Long = 0L
    private var fpsFramesCount: Int = 0

    init {
        for (tex in ProceduralTextureId.entries) {
            staticBatches[tex] = GlVbo()
            nodeBatches[tex] = GlVbo()
            dynBuilders[tex] = MeshBuilder(12288)
            dynVbos[tex] = GlVbo()
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // При пересоздании EGL-контекста сбрасываем старые GPU-хэндлы
        uploadedPaletteLocation = null
        staticBuiltLocation = null
        cachedNodeStateHash = Int.MIN_VALUE
        textures.clear()
        terrainVbo.release()
        lakeIceVbo.release()
        staticBatches.values.forEach { it.release() }
        nodeBatches.values.forEach { it.release() }
        dynVbos.values.forEach { it.release() }
        assetPropBatches.values.forEach { it.release() }
        assetPropBatches.clear()
        assetTextures.values.forEach { id -> if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0) }
        assetTextures.clear()
        assetTexturesQuality = null
        assetPropsBuiltLoc = null

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        worldProgram = createProgram(Shaders.WORLD_VERTEX_SHADER, Shaders.WORLD_FRAGMENT_SHADER)
        skyProgram = createProgram(Shaders.SKY_VERTEX_SHADER, Shaders.SKY_FRAGMENT_SHADER)
        cacheShaderLocations()
        initSkyQuadVbo()

        Matrix.setIdentityM(modelMatrix, 0)
        val snap = snapshot
        ensureGpuSceneReady(snap.run, snap.base, snap.quality)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        aspectRatio = viewportWidth.toFloat() / viewportHeight.toFloat()
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        // Широкоугольная горизонтальная перспектива (62° по вертикали -> ~94° по горизонтали на 16:9 / 20:9!)
        Matrix.perspectiveM(projMatrix, 0, 62f, aspectRatio, 0.12f, 135f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val nowNs = System.nanoTime()
        if (orbitTimeNs == 0L) orbitTimeNs = nowNs
        updateFpsCounter(nowNs)

        val snap = snapshot
        val run = snap.run
        val base = snap.base
        val quality = snap.quality
        val loc = run.currentLocation

        val animTimeSec = if (snap.menuOrbitMode) {
            18f + ((nowNs - orbitTimeNs) / 1_000_000_000.0).toFloat()
        } else {
            run.elapsedRealSeconds
        }

        ensureGpuSceneReady(run, base, quality)
        ensureAssetPropsReady(loc, quality)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // 1. Суточное освещение, цвет неба, Северное сияние и плотность дымки
        val cycleT = (animTimeSec % GameRunState.FULL_CYCLE_SECONDS) / GameRunState.FULL_CYCLE_SECONDS
        val daylight = computeDaylightFactor(cycleT)
        val mineDarkness = if (loc.requiresLightForVisibility && !run.hasActiveLightSource && !snap.menuOrbitMode) {
            0.26f
        } else {
            1.0f
        }
        val effectiveLight = (daylight * mineDarkness).coerceIn(0.12f, 1.0f)

        val pal = loc.palette
        fillScaledHex(pal.skyZenithHex, 0.20f + 0.80f * daylight, zenithRgb)
        fillScaledHex(pal.skyHorizonHex, 0.22f + 0.78f * daylight, horizonRgb)
        fillScaledHex(pal.fogHex, 0.20f + 0.80f * effectiveLight, fogRgb)
        fillScaledHex(pal.sunLightHex, 0.16f + 0.90f * effectiveLight, sunRgb)

        // 2. Положение и ориентация камеры (панорамный пролёт в меню либо живой 1st-person с bobbing шага)
        val camX: Float
        val camY: Float
        val camZ: Float
        val yawRad: Float
        val pitchRad: Float

        if (snap.menuOrbitMode) {
            val orbitAngle = animTimeSec * 0.13f
            val radius = 12.5f + 1.8f * sin(animTimeSec * 0.25f)
            camX = sin(orbitAngle) * radius
            camZ = cos(orbitAngle) * radius + 1.5f
            camY = WorldPopulator.terrainHeight(LocationId.BASE_CLEARING, camX, camZ) + 3.1f + 0.35f * sin(animTimeSec * 0.4f)
            val lookTargetY = WorldPopulator.terrainHeight(LocationId.BASE_CLEARING, -2.0f, -2.0f) + 1.5f
            val dx = -2.0f - camX
            val dy = lookTargetY - camY
            val dz = -2.0f - camZ
            val flatDist = sqrt(dx * dx + dz * dz).coerceAtLeast(0.01f)
            yawRad = kotlin.math.atan2(dx, -dz)
            pitchRad = kotlin.math.atan2(dy, flatDist)
        } else {
            // Позиция сглаживается между 20-герцевыми тиками: на 90/120 FPS шаг
            // не дёргается сеткой тиков, а pitch/yaw (события ввода) идут как есть.
            val alpha = FramePacing.interpAlpha(nowNs - smoothStartNs, TICK_INTERP_WINDOW_NS)
            val px = FramePacing.lerpCoord(smoothPrevX, smoothCurX, alpha, SNAP_DISTANCE_M)
            val py = FramePacing.lerpCoord(smoothPrevY, smoothCurY, alpha, SNAP_DISTANCE_M)
            val pz = FramePacing.lerpCoord(smoothPrevZ, smoothCurZ, alpha, SNAP_DISTANCE_M)
            val stepBobY = FramePacing.lerpBob(smoothPrevBobSin, smoothCurBobSin, alpha) * 0.032f
            val sprintTilt = if (run.isSprinting) -0.02f else 0f
            camX = px
            camY = py + 1.72f + stepBobY
            camZ = pz
            yawRad = Math.toRadians(run.yawDeg.toDouble()).toFloat()
            pitchRad = Math.toRadians(run.pitchDeg.toDouble()).toFloat() + sprintTilt
        }

        val lookX = camX + sin(yawRad) * cos(pitchRad)
        val lookY = camY + sin(pitchRad)
        val lookZ = camZ - cos(yawRad) * cos(pitchRad)

        // 3. Рендер 3D-купола неба с горами 360°, облаками и Северным сиянием
        val auroraStrength = if (loc == LocationId.ABANDONED_MINE) {
            0f
        } else {
            ((0.68f - daylight) * 1.65f).coerceIn(0f, 1f)
        }
        drawSkyDome(cycleT, yawRad, pitchRad, animTimeSec, auroraStrength)

        // 4. Матрицы мира и расстановка 3 тёплых динамических источников света
        Matrix.setLookAtM(viewMatrix, 0, camX, camY, camZ, lookX, lookY, lookZ, 0f, 1f, 0f)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
        Matrix.setIdentityM(modelMatrix, 0)
        System.arraycopy(vpMatrix, 0, mvpMatrix, 0, 16)

        val fireFlicker = 0.92f + 0.08f * sin(animTimeSec * 10.5f)
        // Свет №1: Костёр Базовой поляны либо факел/крепи в штольне
        var rad1 = 0f
        if (loc == LocationId.BASE_CLEARING) {
            ptLight1[0] = 0f
            ptLight1[1] = WorldPopulator.terrainHeight(loc, 0f, 0f) + 1.35f
            ptLight1[2] = 0f
            rad1 = (16f + base.campfireLevel * 2.8f) * fireFlicker
        } else if (loc == LocationId.ABANDONED_MINE) {
            ptLight1[0] = 0f; ptLight1[1] = 2.6f; ptLight1[2] = -6f
            rad1 = 11f * fireFlicker
        }

        // Свет №2: Тёплый свет окон зимовья / походный костёр игрока / маяк реликвии
        var rad2 = 0f
        val activeFieldFire = run.fieldCampfires.lastOrNull()
        if (activeFieldFire != null) {
            ptLight2[0] = activeFieldFire.x
            ptLight2[1] = WorldPopulator.terrainHeight(loc, activeFieldFire.x, activeFieldFire.z) + 1.1f
            ptLight2[2] = activeFieldFire.z
            rad2 = 15.5f * fireFlicker
        } else if (loc == LocationId.HUNTERS_CABIN) {
            ptLight2[0] = 7.2f
            ptLight2[1] = WorldPopulator.terrainHeight(loc, 7.2f, 8.8f) + 1.8f
            ptLight2[2] = 6.2f
            rad2 = 13.5f * fireFlicker
        } else if (loc == LocationId.BASE_CLEARING) {
            ptLight2[0] = -6.5f
            ptLight2[1] = WorldPopulator.terrainHeight(loc, -6.5f, -5.5f) + 1.5f
            ptLight2[2] = -3.2f
            rad2 = 9.5f
        }

        // Свет №3: Факел / походный огонь в руках самого игрока
        var rad3 = 0f
        if (!snap.menuOrbitMode && (run.isTorchLit || run.portableFireRemainingSec > 0f)) {
            ptLight3[0] = camX + sin(yawRad) * 0.6f
            ptLight3[1] = camY - 0.15f
            ptLight3[2] = camZ - cos(yawRad) * 0.6f
            rad3 = (if (run.resinTorchRemainingSec > 0f) 19.5f else 13.5f) * fireFlicker
        }

        val fogScale = quality.fogDistanceScale
        val fogNear = when {
            loc.requiresLightForVisibility && !run.hasActiveLightSource -> 3.0f
            run.isSnowing -> pal.fogNear * 0.76f * fogScale
            else -> pal.fogNear * fogScale
        }
        val fogFar = when {
            loc.requiresLightForVisibility && !run.hasActiveLightSource -> 12.5f
            run.isSnowing -> pal.fogFar * 0.80f * fogScale
            else -> pal.fogFar * fogScale
        }

        ambientRgb[0] = horizonRgb[0] * 0.62f
        ambientRgb[1] = horizonRgb[1] * 0.66f
        ambientRgb[2] = horizonRgb[2] * 0.74f

        GLES20.glUseProgram(worldProgram)
        applyWorldUniforms(camX, camY, camZ, cycleT * (Math.PI.toFloat() * 2f), rad1, rad2, rad3, fogNear, fogFar)

        // 5. Отрисовка статического ландшафта и озёрного льда напрямую из GPU VBO
        val groundTex = if (loc == LocationId.ABANDONED_MINE) ProceduralTextureId.STONE else ProceduralTextureId.SNOW
        drawVbo(terrainVbo, textures[groundTex] ?: 0, unlit = false, specStrength = 0.22f)
        if (loc == LocationId.FROZEN_LAKE) {
            drawVbo(lakeIceVbo, textures[ProceduralTextureId.ICE] ?: 0, unlit = false, specStrength = 0.88f)
        }

        // 6. Отрисовка статических построек, дальнего леса и интерактивных узлов (0 CPU меш-генерации!)
        for (mat in OPAQUE_DRAW_ORDER) {
            val spec = materialSpecular(mat)
            staticBatches[mat]?.let { drawVbo(it, textures[mat] ?: 0, unlit = false, specStrength = spec) }
            nodeBatches[mat]?.let { drawVbo(it, textures[mat] ?: 0, unlit = false, specStrength = spec) }
        }
        staticBatches[ProceduralTextureId.FIRE_EMBER]?.let {
            drawVbo(it, textures[ProceduralTextureId.FIRE_EMBER] ?: 0, unlit = true, specStrength = 0f)
        }
        nodeBatches[ProceduralTextureId.FIRE_EMBER]?.let {
            drawVbo(it, textures[ProceduralTextureId.FIRE_EMBER] ?: 0, unlit = true, specStrength = 0f)
        }
        // 6b. Импортированные 3D-модели Poly Haven (камни, кострище, топор, ветви, растение, деревья)
        for ((modelId, vbo) in assetPropBatches) {
            drawVbo(vbo, assetTextures[modelId] ?: 0, unlit = false, specStrength = 0.18f)
        }

        // 7. Динамический слой (животные, 3D-оружие в руках, пламя, искры удара, метель) через reusable VBO
        buildAndDrawDynamicFrameLayer(
            run = run,
            base = base,
            quality = quality,
            camX = camX,
            camY = camY,
            camZ = camZ,
            yawRad = yawRad,
            pitchRad = pitchRad,
            animTimeSec = animTimeSec,
            menuOrbitMode = snap.menuOrbitMode
        )
    }

    /** Загрузка импортированных моделей и построение статических пропов локации. */
    private fun ensureAssetPropsReady(loc: LocationId, quality: GraphicsQuality) {
        val ctx = appContext ?: return
        if (assetTexturesQuality != quality) {
            assetTextures.values.forEach { id -> if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0) }
            assetTextures.clear()
            val texRes = quality.textureSize
            for (id in AssetProps.ALL_MODEL_IDS) {
                val buf = AssetTextureLibrary.loadRgbaFromAsset(ctx, "models/$id/diff.png", texRes)
                    ?: continue
                val texHandle = IntArray(1)
                GLES20.glGenTextures(1, texHandle, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texHandle[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    texRes, texRes, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
                )
                GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
                assetTextures[id] = texHandle[0]
            }
            assetTexturesQuality = quality
        }
        if (assetPropsBuiltLoc != loc) {
            rebuildAssetPropsVbo(loc)
            assetPropsBuiltLoc = loc
        }
    }

    private fun rebuildAssetPropsVbo(loc: LocationId) {
        val ctx = appContext ?: return
        assetPropBatches.values.forEach { it.release() }
        assetPropBatches.clear()

        val byModel = HashMap<String, MutableList<AssetProps.Placement>>()
        for (p in AssetProps.forLocation(loc)) {
            byModel.getOrPut(p.modelId) { mutableListOf() }.add(p)
        }
        // Модели животных строятся в начале координат — позицию задаёт model-матрица при draw
        for (id in AssetProps.ENTITY_MODEL_IDS) {
            byModel.getOrPut(id) { mutableListOf() }
        }
        for ((modelId, spots0) in byModel) {
            val spots = if (spots0.isEmpty()) listOf(AssetProps.Placement(modelId, 0f, 0f)) else spots0
            val floats = assetMeshCache.getOrPut(modelId) {
                val text = try {
                    ctx.assets.open("models/$modelId/mesh.obj").bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    ""
                }
                if (text.isEmpty()) FloatArray(0) else ObjParser.parseToExpandedFloats(text)
            }
            if (floats.isEmpty()) continue
            val builder = MeshBuilder(floats.size * spots.size + 64)
            for (p in spots) {
                val groundY = WorldPopulator.terrainHeight(loc, p.x, p.z)
                val cos = kotlin.math.cos(p.yawRad)
                val sin = kotlin.math.sin(p.yawRad)
                var i = 0
                while (i + 11 < floats.size) {
                    val lx = floats[i]; val ly = floats[i + 1]; val lz = floats[i + 2]
                    val nx = floats[i + 3]; val ny = floats[i + 4]; val nz = floats[i + 5]
                    val u = floats[i + 6]; val v = floats[i + 7]
                    val r = floats[i + 8]; val g = floats[i + 9]; val b = floats[i + 10]; val a = floats[i + 11]
                    builder.addVertex(
                        p.x + (lx * cos + lz * sin) * p.scale,
                        groundY + ly * p.scale,
                        p.z + (-lx * sin + lz * cos) * p.scale,
                        nx * cos + nz * sin,
                        ny,
                        -nx * sin + nz * cos,
                        u, v,
                        r * p.tintR, g * p.tintG, b * p.tintB, a
                    )
                    i += 12
                }
            }
            val vbo = GlVbo()
            vbo.uploadStatic(builder)
            assetPropBatches[modelId] = vbo
        }
    }

    private fun ensureGpuSceneReady(
        run: GameRunState,
        base: BaseProgress,
        quality: GraphicsQuality
    ) {
        val loc = run.currentLocation

        // 1. Текстуры биома + Mipmap-цепочка
        if (uploadedPaletteLocation != loc || uploadedQuality != quality || textures.isEmpty()) {
            textures.values.forEach { id ->
                if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
            }
            textures.clear()
            val texRes = quality.textureSize
            val snowTintR = ((loc.palette.snowTintHex ushr 16) and 0xFF) / 255f
            val snowTintG = ((loc.palette.snowTintHex ushr 8) and 0xFF) / 255f
            val snowTintB = (loc.palette.snowTintHex and 0xFF) / 255f
            for (kind in ProceduralTextureId.entries) {
                val texHandle = IntArray(1)
                GLES20.glGenTextures(1, texHandle, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texHandle[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)

                // Полные 2048px-текстуры каждого материала из assets/textures (упакованы в APK);
                // процедурная генерация остаётся резервом, если ассет недоступен.
                val snowy = kind == ProceduralTextureId.SNOW || kind == ProceduralTextureId.ICE
                val buf = appContext?.let { ctx ->
                    AssetTextureLibrary.loadRgba(
                        context = ctx,
                        name = AssetTextureLibrary.assetNameFor(kind),
                        outSize = texRes,
                        tintR = if (snowy) snowTintR else 1f,
                        tintG = if (snowy) snowTintG else 1f,
                        tintB = if (snowy) snowTintB else 1f
                    )
                } ?: ProceduralTextures.generateRgbaBuffer(
                    id = kind,
                    palette = loc.palette,
                    size = texRes,
                    seed = 2026 + loc.ordinal * 37
                )
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    texRes, texRes, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
                )
                GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
                textures[kind] = texHandle[0]
            }
            uploadedPaletteLocation = loc
            uploadedQuality = quality
        }

        // 2. Статическая геометрия мира (рельеф + архитектура + два кольца векового леса)
        val baseSig = base.shelterLevel * 31 + base.campfireLevel * 7 + base.workbenchLevel * 3 +
            (if (base.thermosInstalledOnBase) 1000 else 0)
        if (staticBuiltLocation != loc || staticBuiltQuality != quality || staticBuiltBaseSig != baseSig) {
            // На Базовой поляне — высокая сетка (128 шагов) под импортированный ландшафт
            val terrainSteps = if (loc == LocationId.BASE_CLEARING) 128 else quality.terrainSteps
            val terrainBuilder = MeshBuilder.buildTerrainIntoBuilder(loc, halfExtent = 48f, steps = terrainSteps)
            terrainVbo.uploadStatic(terrainBuilder)

            if (loc == LocationId.FROZEN_LAKE) {
                val iceBuilder = MeshBuilder(512).apply {
                    val segs = 12
                    val half = 23.2f
                    val step = (half * 2f) / segs
                    for (iz in 0 until segs) {
                        for (ix in 0 until segs) {
                            val x0 = -half + ix * step; val x1 = x0 + step
                            val z0 = -half + iz * step; val z1 = z0 + step
                            addQuad(
                                x0, -0.16f, z1, x1, -0.16f, z1, x1, -0.16f, z0, x0, -0.16f, z0,
                                0f, 1f, 0f, 1.35f, 1.35f, 0.90f, 0.97f, 1.0f, 0.94f
                            )
                        }
                    }
                }
                lakeIceVbo.uploadStatic(iceBuilder)
            } else {
                lakeIceVbo.release()
            }

            rebuildStaticLocationArchitecture(loc, base, quality)
            staticBuiltLocation = loc
            staticBuiltQuality = quality
            staticBuiltBaseSig = baseSig
            cachedNodeStateHash = Int.MIN_VALUE
        }

        // 3. Интерактивные ресурсные узлы (перезаливаются ТОЛЬКО при изменении их доступности!)
        val nodes = run.currentLocationNodes()
        val nodeSig = computeNodeAvailabilitySignature(loc, nodes)
        if (nodeSig != cachedNodeStateHash) {
            rebuildInteractiveNodesVbo(loc, nodes)
            cachedNodeStateHash = nodeSig
        }
    }

    private fun computeNodeAvailabilitySignature(loc: LocationId, nodes: List<ResourceNode>): Int {
        var h = loc.ordinal * 92821 + nodes.size * 31
        for (i in nodes.indices) {
            h = h * 31 + (if (nodes[i].isAvailable) 1 else 0)
        }
        return h
    }

    private fun rebuildStaticLocationArchitecture(
        loc: LocationId,
        base: BaseProgress,
        quality: GraphicsQuality
    ) {
        val builders = EnumMap<ProceduralTextureId, MeshBuilder>(ProceduralTextureId::class.java)
        for (mat in ProceduralTextureId.entries) {
            builders[mat] = MeshBuilder(16384)
        }
        val bark = builders[ProceduralTextureId.WOOD_BARK]!!
        val foliage = builders[ProceduralTextureId.PINE_FOLIAGE]!!
        val stone = builders[ProceduralTextureId.STONE]!!
        val timber = builders[ProceduralTextureId.TIMBER]!!
        val cloth = builders[ProceduralTextureId.CLOTH]!!
        val metal = builders[ProceduralTextureId.METAL_STEEL]!!
        val fire = builders[ProceduralTextureId.FIRE_EMBER]!!

        if (loc == LocationId.BASE_CLEARING) {
            val baseY = WorldPopulator.terrainHeight(loc, 0f, 0f)
            // Детальное кострище из валунов, углей, перекрещенных поленьев и треноги с котелком
            foliage.addShadowDisc(0f, baseY, 0f, 1.85f, 0.42f)
            for (i in 0 until 10) {
                val a = (i / 10f) * Math.PI.toFloat() * 2f
                stone.addSculptedBoulder(cos(a) * 1.02f, baseY, sin(a) * 1.02f, 0.25f, 0.20f, 0.23f, 50 + i)
            }
            for (i in 0 until 4) {
                val a = (i / 4f) * Math.PI.toFloat() + 0.35f
                bark.addTaperedCylinder(
                    cos(a) * 0.72f, baseY + 0.05f, sin(a) * 0.72f,
                    -cos(a) * 0.35f, baseY + 0.34f, -sin(a) * 0.35f,
                    0.095f, 0.075f, 7, 0.55f, 0.40f, 0.30f
                )
            }
            // Кованая костровая тренога и походный котелок
            for (i in 0 until 3) {
                val a = (i / 3f) * Math.PI.toFloat() * 2f + 0.2f
                metal.addTaperedCylinder(
                    cos(a) * 1.15f, baseY, sin(a) * 1.15f,
                    0f, baseY + 1.65f, 0f,
                    0.032f, 0.022f, 6, 0.52f, 0.55f, 0.60f
                )
            }
            metal.addTaperedCylinder(0f, baseY + 0.72f, 0f, 0f, baseY + 1.04f, 0f, 0.24f, 0.27f, 10, 0.44f, 0.46f, 0.52f)

            // Сруб укрытия выжившего с двускатной заснеженной крышей, крыльцом, дверью, светящимся окном и печной трубой
            val shX = -6.8f; val shZ = -5.8f
            val shY = WorldPopulator.terrainHeight(loc, shX, shZ)
            val wallH = 2.35f + (base.shelterLevel - 1) * 0.16f
            addDetailedLogCabin(
                timber = timber, bark = bark, stone = stone, snowRoof = builders[ProceduralTextureId.SNOW]!!,
                metal = metal, glow = fire, shadow = foliage,
                cx = shX, baseY = shY, cz = shZ,
                width = 4.6f, wallHeight = wallH, depth = 4.2f
            )
            // Поленница дров у стены сруба
            for (row in 0 until 3) {
                for (col in 0 until 4) {
                    val lx = shX + 2.75f + (row % 2) * 0.05f
                    val ly = shY + 0.11f + row * 0.20f
                    val lz = shZ - 1.1f + col * 0.24f
                    bark.addTaperedCylinder(lx - 0.45f, ly, lz, lx + 0.45f, ly, lz, 0.10f, 0.095f, 7)
                }
            }

            // Плотницкий верстак на 4 брусьях с наковальней и Термосом
            val wbX = 4.8f; val wbZ = -4.5f
            val wbY = WorldPopulator.terrainHeight(loc, wbX, wbZ)
            foliage.addShadowDisc(wbX, wbY, wbZ, 1.45f, 0.34f)
            for (dx in listOf(-0.82f, 0.82f)) {
                for (dz in listOf(-0.42f, 0.42f)) {
                    timber.addBox(wbX + dx, wbY, wbZ + dz, 0.16f, 0.96f, 0.16f)
                }
            }
            timber.addBox(wbX, wbY + 0.96f, wbZ, 2.05f, 0.14f, 1.12f)
            metal.addBox(wbX - 0.45f, wbY + 1.10f, wbZ, 0.42f, 0.24f, 0.24f, 0.72f, 0.76f, 0.82f)
            if (base.thermosInstalledOnBase) {
                metal.addTaperedCylinder(wbX + 0.52f, wbY + 1.10f, wbZ, wbX + 0.52f, wbY + 1.62f, wbZ, 0.14f, 0.13f, 10, 0.90f, 0.96f, 1.0f)
                cloth.addTaperedCylinder(wbX + 0.52f, wbY + 1.24f, wbZ, wbX + 0.52f, wbY + 1.44f, wbZ, 0.146f, 0.146f, 10)
            }
        } else if (loc == LocationId.HUNTERS_CABIN) {
            val cabX = 7.2f; val cabZ = 8.8f
            val cabY = WorldPopulator.terrainHeight(loc, cabX, cabZ)
            // Зимовье рисуется импортированной 3D-моделью (cabin_wood/bark/stone/shingle/burned)
            // Сушильные козлы охотника возле зимовья
            bark.addTaperedCylinder(cabX - 4.2f, cabY, cabZ - 1.2f, cabX - 4.2f, cabY + 2.0f, cabZ - 0.2f, 0.08f, 0.06f, 6)
            bark.addTaperedCylinder(cabX - 4.2f, cabY, cabZ + 0.8f, cabX - 4.2f, cabY + 2.0f, cabZ - 0.2f, 0.08f, 0.06f, 6)
            cloth.addOrientedBox(cabX - 4.2f, cabY + 1.25f, cabZ - 0.2f, 0.10f, 1.05f, 1.35f, 0f, 0f, 0f, 0.85f, 0.72f, 0.60f)
        } else if (loc == LocationId.FROZEN_LAKE) {
            // Деревянный рыбацкий причал у кромки льда
            val px = -4.5f
            for (i in 0 until 7) {
                val pz = 22.8f - i * 1.15f
                timber.addBox(px, -0.12f, pz, 2.2f, 0.12f, 0.95f)
                bark.addTaperedCylinder(px - 0.95f, -0.8f, pz, px - 0.95f, 0.35f, pz, 0.09f, 0.08f, 6)
                bark.addTaperedCylinder(px + 0.95f, -0.8f, pz, px + 0.95f, 0.35f, pz, 0.09f, 0.08f, 6)
            }
        } else if (loc == LocationId.ABANDONED_MINE) {
            // Арочные бревенчатые крепи штольни с рельсами узкоколейки и фонарями
            for (zStep in listOf(-18f, -10f, -2f, 6f, 14f, 22f)) {
                val gy = WorldPopulator.terrainHeight(loc, 0f, zStep)
                bark.addTaperedCylinder(-3.3f, gy, zStep, -3.1f, gy + 3.4f, zStep, 0.20f, 0.18f, 8)
                bark.addTaperedCylinder(3.3f, gy, zStep, 3.1f, gy + 3.4f, zStep, 0.20f, 0.18f, 8)
                timber.addBox(0f, gy + 3.32f, zStep, 6.9f, 0.36f, 0.42f)
                timber.addOrientedBox(-2.35f, gy + 2.85f, zStep, 1.4f, 0.20f, 0.22f, 0f, 0f, 0.68f)
                timber.addOrientedBox(2.35f, gy + 2.85f, zStep, 1.4f, 0.20f, 0.22f, 0f, 0f, -0.68f)
                stone.addSculptedBoulder(-4.6f, gy, zStep + 2.2f, 1.4f, 2.2f, 1.6f, 300 + zStep.toInt(), snowCapTint = false)
                stone.addSculptedBoulder(4.6f, gy, zStep - 1.8f, 1.5f, 2.3f, 1.5f, 400 + zStep.toInt(), snowCapTint = false)
            }
            // Железные рельсы и шпалы вдоль штольни
            for (i in -11..11) {
                val rz = i * 2.1f
                val ry = WorldPopulator.terrainHeight(loc, 0f, rz)
                timber.addBox(0f, ry + 0.01f, rz, 1.65f, 0.08f, 0.24f)
            }
            metal.addBox(-0.55f, 0.12f, 0f, 0.09f, 0.09f, 48f)
            metal.addBox(0.55f, 0.12f, 0f, 0.09f, 0.09f, 48f)
        }

        // Северные резные деревянные ворота перехода в следующую зону + указательный фонарь
        val gateZ = -35.5f
        val portalY = WorldPopulator.terrainHeight(loc, 0f, gateZ)
        bark.addTaperedCylinder(-1.85f, portalY, gateZ, -1.75f, portalY + 3.35f, gateZ, 0.20f, 0.16f, 8)
        bark.addTaperedCylinder(1.85f, portalY, gateZ, 1.75f, portalY + 3.35f, gateZ, 0.20f, 0.16f, 8)
        timber.addBox(0f, portalY + 3.05f, gateZ, 4.4f, 0.34f, 0.40f)
        builders[ProceduralTextureId.SNOW]!!.addBox(0f, portalY + 3.39f, gateZ, 4.55f, 0.16f, 0.52f)
        fire.addCrossedFlame(-1.55f, portalY + 2.25f, gateZ + 0.28f, 0.26f, 0.42f)
        fire.addCrossedFlame(1.55f, portalY + 2.25f, gateZ + 0.28f, 0.26f, 0.42f)

        // Живописные поваленные заснеженные стволы и валуны в мире
        for (i in 0 until 7) {
            val a = i * 0.91f + 0.4f
            val rDist = 13.5f + (i % 3) * 7.2f
            val lx = cos(a) * rDist
            val lz = sin(a) * rDist
            val ly = WorldPopulator.terrainHeight(loc, lx, lz)
            if (loc != LocationId.FROZEN_LAKE || rDist > 24.5f) {
                bark.addTaperedCylinder(
                    lx - cos(a + 1.1f) * 1.5f, ly + 0.14f, lz - sin(a + 1.1f) * 1.5f,
                    lx + cos(a + 1.1f) * 1.5f, ly + 0.28f, lz + sin(a + 1.1f) * 1.5f,
                    0.22f, 0.16f, 8
                )
                stone.addSculptedBoulder(lx + 1.3f, ly, lz - 0.8f, 0.65f, 0.48f, 0.58f, 700 + i)
            }
        }

        // Два эшелона вековых елей по периметру мира (плотная живая линия леса без плоского обрыва!)
        val ringCount = quality.perimeterTrees
        for (i in 0 until ringCount) {
            val ang = (i.toFloat() / ringCount.toFloat()) * Math.PI.toFloat() * 2f
            if (abs(sin(ang)) < 0.09f && cos(ang) < 0f) continue // проход у северных ворот
            val radInner = 39.2f + (i % 3) * 1.6f
            val bx = sin(ang) * radInner
            val bz = -cos(ang) * radInner
            val by = MeshBuilder.terrainWithBorderCliff(loc, bx, bz)
            addDetailedPineTree(bark, foliage, bx, by, bz, scale = 1.25f + (i % 4) * 0.16f, seed = i * 13)
        }
        val outerCount = (ringCount * 3) / 4
        for (i in 0 until outerCount) {
            val ang = (i.toFloat() / outerCount.toFloat()) * Math.PI.toFloat() * 2f + 0.11f
            val radOuter = 44.5f + (i % 2) * 1.8f
            val bx = sin(ang) * radOuter
            val bz = -cos(ang) * radOuter
            val by = MeshBuilder.terrainWithBorderCliff(loc, bx, bz)
            addDetailedPineTree(bark, foliage, bx, by, bz, scale = 1.55f + (i % 3) * 0.18f, seed = 500 + i, drawShadow = false)
        }

        for ((mat, builder) in builders) {
            staticBatches[mat]?.uploadStatic(builder)
        }
    }

    private fun rebuildInteractiveNodesVbo(loc: LocationId, nodes: List<ResourceNode>) {
        val builders = EnumMap<ProceduralTextureId, MeshBuilder>(ProceduralTextureId::class.java)
        for (mat in ProceduralTextureId.entries) {
            builders[mat] = MeshBuilder(12288)
        }
        val bark = builders[ProceduralTextureId.WOOD_BARK]!!
        val foliage = builders[ProceduralTextureId.PINE_FOLIAGE]!!
        val stone = builders[ProceduralTextureId.STONE]!!
        val timber = builders[ProceduralTextureId.TIMBER]!!
        val cloth = builders[ProceduralTextureId.CLOTH]!!
        val metal = builders[ProceduralTextureId.METAL_STEEL]!!
        val fur = builders[ProceduralTextureId.FUR_HIDE]!!
        val fire = builders[ProceduralTextureId.FIRE_EMBER]!!
        val ice = builders[ProceduralTextureId.ICE]!!

        for ((idx, node) in nodes.withIndex()) {
            if (!node.isAvailable) continue
            val y = WorldPopulator.terrainHeight(loc, node.x, node.z)
            when (node.kind) {
                WorldObjectKind.PINE_TREE -> {
                    addDetailedPineTree(bark, foliage, node.x, y, node.z, scale = 0.94f + (idx % 4) * 0.11f, seed = 90 + idx)
                }
                WorldObjectKind.BERRY_BUSH -> {
                    foliage.addShadowDisc(node.x, y, node.z, 1.15f, 0.34f)
                    foliage.addSpruceBoughTier(node.x, y + 0.08f, node.z, 0.92f, 0.88f, tips = 7, twistRad = idx * 0.6f)
                    foliage.addSpruceBoughTier(node.x + 0.25f, y + 0.22f, node.z - 0.18f, 0.68f, 0.72f, tips = 6, twistRad = 1.1f)
                    for (b in 0 until 7) {
                        val ba = b * 0.9f + idx
                        cloth.addSculptedBoulder(
                            node.x + cos(ba) * 0.46f, y + 0.32f + (b % 3) * 0.16f, node.z + sin(ba) * 0.46f,
                            0.075f, 0.075f, 0.075f, 60 + b, r = 1.0f, g = 0.16f, b = 0.20f, snowCapTint = false
                        )
                    }
                }
                WorldObjectKind.STONE_OUTCROP -> {
                    foliage.addShadowDisc(node.x, y, node.z, 1.55f, 0.36f)
                    stone.addSculptedBoulder(node.x, y, node.z, 0.92f, 0.85f, 0.82f, 120 + idx)
                    stone.addSculptedBoulder(node.x + 0.62f, y - 0.06f, node.z - 0.42f, 0.56f, 0.52f, 0.54f, 220 + idx)
                }
                WorldObjectKind.SUPPLY_CRATE -> {
                    foliage.addShadowDisc(node.x, y, node.z, 1.05f, 0.36f)
                    timber.addOrientedBox(node.x, y + 0.44f, node.z, 1.02f, 0.88f, 1.02f, idx * 0.35f, 0f, 0f)
                    metal.addOrientedBox(node.x, y + 0.89f, node.z, 1.06f, 0.06f, 1.06f, idx * 0.35f, 0f, 0f)
                    cloth.addOrientedBox(node.x, y + 0.98f, node.z, 0.72f, 0.20f, 0.62f, idx * 0.35f + 0.2f, 0f, 0f)
                }
                WorldObjectKind.RESIN_STUMP -> {
                    foliage.addShadowDisc(node.x, y, node.z, 0.95f, 0.34f)
                    bark.addTaperedCylinder(node.x, y, node.z, node.x, y + 0.98f, node.z, 0.46f, 0.38f, 10)
                    fire.addSculptedBoulder(node.x + 0.34f, y + 0.44f, node.z + 0.12f, 0.16f, 0.22f, 0.15f, 310 + idx, 1f, 0.72f, 0.16f, false)
                    fire.addSculptedBoulder(node.x - 0.28f, y + 0.62f, node.z - 0.20f, 0.12f, 0.16f, 0.12f, 340 + idx, 1f, 0.78f, 0.22f, false)
                }
                WorldObjectKind.ICE_HOLE -> {
                    for (r in 0 until 8) {
                        val a = (r / 8f) * Math.PI.toFloat() * 2f
                        ice.addSculptedBoulder(node.x + cos(a) * 0.82f, y + 0.08f, node.z + sin(a) * 0.82f, 0.24f, 0.14f, 0.22f, 410 + r)
                    }
                    ice.addShadowDisc(node.x, y + 0.09f, node.z, 0.72f, 0.88f)
                    bark.addTaperedCylinder(node.x + 0.95f, y + 0.05f, node.z, node.x + 0.45f, y + 1.28f, node.z, 0.045f, 0.025f, 6)
                }
                WorldObjectKind.COAL_SEAM -> {
                    foliage.addShadowDisc(node.x, y, node.z, 1.45f, 0.38f)
                    stone.addSculptedBoulder(node.x, y, node.z, 0.95f, 0.92f, 0.85f, 510 + idx, 0.24f, 0.26f, 0.30f, snowCapTint = false)
                    metal.addSculptedBoulder(node.x + 0.35f, y + 0.25f, node.z + 0.28f, 0.36f, 0.34f, 0.34f, 540 + idx, 0.18f, 0.20f, 0.24f, snowCapTint = false)
                }
                WorldObjectKind.IRON_VEIN -> {
                    foliage.addShadowDisc(node.x, y, node.z, 1.45f, 0.38f)
                    stone.addSculptedBoulder(node.x, y, node.z, 0.90f, 0.95f, 0.82f, 610 + idx, 0.62f, 0.48f, 0.40f, snowCapTint = false)
                    metal.addSculptedBoulder(node.x - 0.28f, y + 0.32f, node.z + 0.30f, 0.38f, 0.36f, 0.32f, 640 + idx, 0.94f, 0.68f, 0.44f, snowCapTint = false)
                }
                WorldObjectKind.GAME_CARCASS -> {
                    foliage.addShadowDisc(node.x, y, node.z, 1.10f, 0.36f)
                    fur.addSculptedBoulder(node.x, y - 0.05f, node.z, 0.68f, 0.42f, 0.46f, 710 + idx, 0.72f, 0.56f, 0.44f)
                }
                WorldObjectKind.SPECIAL_CACHE -> {
                    foliage.addShadowDisc(node.x, y, node.z, 1.35f, 0.44f)
                    timber.addBox(node.x, y, node.z, 1.24f, 0.66f, 0.86f, 0.95f, 0.78f, 0.52f)
                    metal.addBox(node.x, y + 0.66f, node.z, 1.28f, 0.16f, 0.90f, 1.0f, 0.88f, 0.55f)
                    bark.addTaperedCylinder(node.x + 0.78f, y, node.z, node.x + 0.78f, y + 1.85f, node.z, 0.05f, 0.04f, 6)
                }
            }
        }

        for ((mat, builder) in builders) {
            nodeBatches[mat]?.uploadStatic(builder)
        }
    }

    private fun buildAndDrawDynamicFrameLayer(
        run: GameRunState,
        base: BaseProgress,
        quality: GraphicsQuality,
        camX: Float,
        camY: Float,
        camZ: Float,
        yawRad: Float,
        pitchRad: Float,
        animTimeSec: Float,
        menuOrbitMode: Boolean
    ) {
        for (b in dynBuilders.values) b.clear()

        val bark = dynBuilders[ProceduralTextureId.WOOD_BARK]!!
        val timber = dynBuilders[ProceduralTextureId.TIMBER]!!
        val stone = dynBuilders[ProceduralTextureId.STONE]!!
        val cloth = dynBuilders[ProceduralTextureId.CLOTH]!!
        val metal = dynBuilders[ProceduralTextureId.METAL_STEEL]!!
        val fur = dynBuilders[ProceduralTextureId.FUR_HIDE]!!
        val fire = dynBuilders[ProceduralTextureId.FIRE_EMBER]!!
        val snow = dynBuilders[ProceduralTextureId.SNOW]!!

        val loc = run.currentLocation
        val fwdX = sin(yawRad)
        val fwdZ = -cos(yawRad)

        // 1. Живое пламя и искры базового костра + дым из печной трубы зимовья
        if (loc == LocationId.BASE_CLEARING) {
            val baseY = WorldPopulator.terrainHeight(loc, 0f, 0f)
            val flick = 0.86f + 0.20f * sin(animTimeSec * 10.5f)
            fire.addCrossedFlame(
                0f, baseY + 0.16f, 0f,
                width = 0.52f + base.campfireLevel * 0.06f,
                height = (0.85f + base.campfireLevel * 0.12f) * flick,
                swayX = sin(animTimeSec * 6.5f) * 0.06f
            )
            for (s in 0 until 8) {
                val rise = ((animTimeSec * 1.45f + s * 0.37f) % 2.6f)
                val sx = sin(animTimeSec * 2.4f + s * 1.7f) * (0.18f + rise * 0.14f)
                val sz = cos(animTimeSec * 2.1f + s * 2.3f) * (0.18f + rise * 0.14f)
                fire.addBox(sx, baseY + 0.45f + rise, sz, 0.045f, 0.045f, 0.045f, 1f, 0.80f, 0.28f, (1f - rise / 2.6f) * 0.9f)
            }
            // Клубы тёплого дыма из трубы сруба
            val chY = WorldPopulator.terrainHeight(loc, -6.8f, -5.8f) + 3.9f
            for (p in 0 until 5) {
                val rise = ((animTimeSec * 0.75f + p * 0.7f) % 3.5f)
                val rad = 0.16f + rise * 0.14f
                snow.addBox(-5.1f + rise * 0.28f, chY + rise, -4.6f - rise * 0.12f, rad, rad * 0.75f, rad, 0.82f, 0.85f, 0.90f, (0.42f * (1f - rise / 3.5f)).coerceAtLeast(0.05f))
            }
        }

        // 2. Полевые костры игрока + маяки несобранных уникальных находок
        for (fc in run.fieldCampfires) {
            val fy = WorldPopulator.terrainHeight(loc, fc.x, fc.z)
            stone.addSculptedBoulder(fc.x + 0.35f, fy, fc.z, 0.18f, 0.14f, 0.16f, 17)
            stone.addSculptedBoulder(fc.x - 0.35f, fy, fc.z + 0.12f, 0.18f, 0.14f, 0.16f, 23)
            bark.addTaperedCylinder(fc.x - 0.35f, fy + 0.04f, fc.z - 0.2f, fc.x + 0.35f, fy + 0.22f, fc.z + 0.2f, 0.07f, 0.06f, 6)
            val flick = 0.85f + 0.22f * sin(animTimeSec * 11.2f)
            fire.addCrossedFlame(fc.x, fy + 0.12f, fc.z, 0.44f, 0.72f * flick, sin(animTimeSec * 7f) * 0.05f)
        }

        for (node in run.currentLocationNodes()) {
            if (node.isAvailable && node.kind == WorldObjectKind.SPECIAL_CACHE) {
                val ny = WorldPopulator.terrainHeight(loc, node.x, node.z)
                val hover = ny + 1.05f + 0.16f * sin(animTimeSec * 3.1f)
                fire.addCrossedFlame(node.x, hover, node.z, 0.24f, 0.42f, 0f, 1f, 0.92f, 0.42f, 0.85f)
            }
        }

        // 3. Живая 3D-фауна (зайцы, северные олени с рогами, таёжные волки) с отсечением по пирамиде обзора
        val importedBeasts = ArrayList<Triple<WildlifeKind, FloatArray, Float>>(4)
        for ((idx, beast) in run.currentWildlife().withIndex()) {
            if (!beast.isAlive) continue
            val dx = beast.x - camX
            val dz = beast.z - camZ
            val distSq = dx * dx + dz * dz
            if (distSq > 56f * 56f) continue
            if (distSq > 9f) {
                val invD = 1f / sqrt(distSq)
                val dotFwd = (dx * invD) * fwdX + (dz * invD) * fwdZ
                if (dotFwd < -0.35f) continue // Frustum culling за спиной камеры!
            }
            val gy = WorldPopulator.terrainHeight(loc, beast.x, beast.z)
            if (beast.kind == WildlifeKind.TIMBER_WOLF || beast.kind == WildlifeKind.NORTHERN_DEER) {
                // Импортированные модели волка и оленя (рисуются с model-матрицей ниже)
                fur.addShadowDisc(beast.x, gy, beast.z, if (beast.kind == WildlifeKind.TIMBER_WOLF) 1.05f else 1.3f, 0.34f)
                importedBeasts.add(Triple(beast.kind, floatArrayOf(beast.x, gy, beast.z), beast.headingRad))
                continue
            }
            MeshBuilder.addWildlifeCreature(
                furBuilder = fur,
                barkHornBuilder = bark,
                glowBuilder = fire,
                kind = beast.kind,
                x = beast.x,
                groundY = gy,
                z = beast.z,
                headingRad = beast.headingRad,
                animPhase = animTimeSec * (4.8f + beast.kind.cruiseSpeedMps) + idx * 1.7f
            )
        }

        // 4. Всплеск 3D-щепы / искр в точке удара по дереву, руде или зверю
        if (run.hitFeedbackTimerSec > 0f) {
            val p = 1f - (run.hitFeedbackTimerSec / 0.5f).coerceIn(0f, 1f)
            for (k in 0 until 10) {
                val ang = k * 0.63f
                val spread = 0.18f + p * 0.95f
                val px = run.hitWorldX + cos(ang) * spread
                val pz = run.hitWorldZ + sin(ang) * spread
                val py = run.hitWorldY + sin(p * Math.PI.toFloat()) * 0.45f - p * p * 0.35f
                fire.addOrientedBox(px, py, pz, 0.07f, 0.05f, 0.09f, ang + p * 4f, p * 3f, 0f, 1f, 0.86f, 0.48f, (1f - p) * 0.95f)
            }
        }

        // 5. Детальная 3D-модель экипированного оружия / инструмента от первого лица
        if (!menuOrbitMode) {
            val swingNorm = (run.swingAnimRemainingSec / run.equippedTool.swingDurationSec.coerceAtLeast(0.1f))
                .coerceIn(0f, 1f)
            MeshBuilder.addFirstPersonEquippedItem(
                timberBuilder = timber,
                metalBuilder = metal,
                clothBuilder = cloth,
                fireBuilder = fire,
                tool = run.equippedTool,
                torchLit = run.isTorchLit,
                camX = camX,
                camY = camY,
                camZ = camZ,
                yawRad = yawRad,
                pitchRad = pitchRad,
                swingProgress = 1f - swingNorm,
                walkBobPhase = run.walkBobPhaseRad,
                timeSec = animTimeSec
            )
        }

        // 6. Объёмная зимняя позёмка / снегопад в передней полусфере камеры (frustum-aware!)
        val particleBudget = if (run.isSnowing || menuOrbitMode) {
            quality.snowParticles
        } else {
            quality.snowParticles / 3
        }
        val windX = sin(animTimeSec * 0.35f) * 1.4f + 0.9f
        for (i in 0 until particleBudget) {
            val localFwd = 1.2f + ((i * 53) % 190) * 0.11f
            val localSide = (((i * 97) % 260) - 130) * 0.11f
            val fall = ((i * 0.37f + animTimeSec * (if (run.isSnowing) 3.4f else 1.8f)) % 9.5f)
            val px = camX + fwdX * localFwd + cos(yawRad) * localSide + sin(animTimeSec * 1.6f + i) * 0.45f + windX * (fall * 0.12f)
            val pz = camZ + fwdZ * localFwd + sin(yawRad) * localSide + cos(animTimeSec * 1.3f + i) * 0.45f
            val py = camY + 5.2f - fall
            val sz = 0.030f + (i % 3) * 0.012f
            snow.addBox(px, py, pz, sz, sz * 1.25f, sz, 0.95f, 0.98f, 1.0f, 0.78f)
        }

        // Заливаем и рисуем динамический кадр через 1 переиспользуемый native FloatBuffer (0 аллокаций!)
        for (mat in OPAQUE_DRAW_ORDER) {
            val builder = dynBuilders[mat] ?: continue
            if (builder.vertexCount <= 0) continue
            val vbo = dynVbos[mat] ?: continue
            reusableUploadBuffer = vbo.uploadDynamic(builder, reusableUploadBuffer)
            // Хлопья метели и дым — ЯРКИЕ незатеняемые частицы (без тёмных граней кубиков!)
            val unlitParticles = mat == ProceduralTextureId.SNOW
            drawVbo(vbo, textures[mat] ?: 0, unlit = unlitParticles, specStrength = materialSpecular(mat))
        }
        val fireBuilder = dynBuilders[ProceduralTextureId.FIRE_EMBER]!!
        if (fireBuilder.vertexCount > 0) {
            val fireVbo = dynVbos[ProceduralTextureId.FIRE_EMBER]!!
            reusableUploadBuffer = fireVbo.uploadDynamic(fireBuilder, reusableUploadBuffer)
            drawVbo(fireVbo, textures[ProceduralTextureId.FIRE_EMBER] ?: 0, unlit = true, specStrength = 0f)
        }

        // Импортированные 3D-модели животных (волк/олень) — отдельные draw с model-матрицей
        for ((kind, pos, heading) in importedBeasts) {
            val modelId = if (kind == WildlifeKind.TIMBER_WOLF) "wolf_body" else "deer_body"
            drawModelInstance(
                modelId = modelId,
                x = pos[0], y = pos[1], z = pos[2],
                yawRad = -heading + Math.PI.toFloat() * 0.5f,
                scale = 1f
            )
        }
    }

    private fun addDetailedPineTree(
        bark: MeshBuilder,
        foliage: MeshBuilder,
        x: Float,
        y: Float,
        z: Float,
        scale: Float,
        seed: Int,
        drawShadow: Boolean = true
    ) {
        if (drawShadow) {
            foliage.addShadowDisc(x, y, z, 2.25f * scale, alpha = 0.36f)
        }
        // Ствол с корневым расширением у комля
        bark.addTaperedCylinder(x, y - 0.1f, z, x, y + 4.8f * scale, z, 0.30f * scale, 0.09f * scale, 8)
        for (r in 0 until 4) {
            val ra = r * 1.57f + seed * 0.2f
            bark.addTaperedCylinder(
                x + cos(ra) * 0.52f * scale, y - 0.06f, z + sin(ra) * 0.52f * scale,
                x + cos(ra) * 0.14f * scale, y + 0.42f * scale, z + sin(ra) * 0.14f * scale,
                0.09f * scale, 0.07f * scale, 5
            )
        }
        // 5 многолучевых ярусов заснеженных еловых лап с поворотом каждого яруса
        val tiers = arrayOf(
            floatArrayOf(1.15f, 2.35f, 1.55f),
            floatArrayOf(2.20f, 1.98f, 1.48f),
            floatArrayOf(3.15f, 1.58f, 1.38f),
            floatArrayOf(4.02f, 1.18f, 1.25f),
            floatArrayOf(4.78f, 0.72f, 1.15f)
        )
        for ((ti, t) in tiers.withIndex()) {
            foliage.addSpruceBoughTier(
                cx = x,
                baseY = y + t[0] * scale,
                cz = z,
                radius = t[1] * scale,
                height = t[2] * scale,
                tips = if (drawShadow) 9 else 7,
                twistRad = seed * 0.31f + ti * 0.42f
            )
        }
    }

    private fun addDetailedLogCabin(
        timber: MeshBuilder,
        bark: MeshBuilder,
        stone: MeshBuilder,
        snowRoof: MeshBuilder,
        metal: MeshBuilder,
        glow: MeshBuilder,
        shadow: MeshBuilder,
        cx: Float,
        baseY: Float,
        cz: Float,
        width: Float,
        wallHeight: Float,
        depth: Float
    ) {
        shadow.addShadowDisc(cx, baseY, cz, width * 0.85f, alpha = 0.42f)
        // Каменный фундамент
        stone.addBox(cx, baseY - 0.12f, cz, width + 0.24f, 0.34f, depth + 0.24f)
        // Основной сруб
        timber.addBox(cx, baseY + 0.22f, cz, width, wallHeight, depth)
        // Выступающие венцы брёвен («перерубы» на всех 4 углах!)
        val courses = 7
        val stepY = wallHeight / courses
        val hw = width * 0.5f; val hd = depth * 0.5f
        for (i in 0 until courses) {
            val ly = baseY + 0.30f + i * stepY
            val rLog = stepY * 0.48f
            bark.addTaperedCylinder(cx - hw - 0.28f, ly, cz + hd, cx + hw + 0.28f, ly, cz + hd, rLog, rLog, 7)
            bark.addTaperedCylinder(cx - hw - 0.28f, ly, cz - hd, cx + hw + 0.28f, ly, cz - hd, rLog, rLog, 7)
            bark.addTaperedCylinder(cx - hw, ly + stepY * 0.5f, cz - hd - 0.28f, cx - hw, ly + stepY * 0.5f, cz + hd + 0.28f, rLog, rLog, 7)
            bark.addTaperedCylinder(cx + hw, ly + stepY * 0.5f, cz - hd - 0.28f, cx + hw, ly + stepY * 0.5f, cz + hd + 0.28f, rLog, rLog, 7)
        }
        // Двускатная заснеженная крыша с коньковым бревном
        val roofBaseY = baseY + 0.22f + wallHeight
        val slopeLen = width * 0.66f
        val roofAngle = 0.56f
        timber.addOrientedBox(cx - width * 0.28f, roofBaseY + 0.62f, cz, slopeLen, 0.14f, depth + 0.72f, 0f, 0f, roofAngle)
        timber.addOrientedBox(cx + width * 0.28f, roofBaseY + 0.62f, cz, slopeLen, 0.14f, depth + 0.72f, 0f, 0f, -roofAngle)
        snowRoof.addOrientedBox(cx - width * 0.28f, roofBaseY + 0.76f, cz, slopeLen + 0.08f, 0.18f, depth + 0.80f, 0f, 0f, roofAngle)
        snowRoof.addOrientedBox(cx + width * 0.28f, roofBaseY + 0.76f, cz, slopeLen + 0.08f, 0.18f, depth + 0.80f, 0f, 0f, -roofAngle)
        bark.addTaperedCylinder(cx, roofBaseY + 1.24f, cz - hd - 0.42f, cx, roofBaseY + 1.24f, cz + hd + 0.42f, 0.13f, 0.13f, 8)

        // Каменная печная труба
        stone.addBox(cx + hw * 0.55f, roofBaseY + 0.35f, cz + hd * 0.25f, 0.62f, 1.55f, 0.62f)
        // Входная дверь с петлями, ступенями крыльца и тёплые светящиеся окна с переплётом
        timber.addBox(cx - 0.85f, baseY + 0.24f, cz + hd + 0.05f, 0.96f, 1.72f, 0.10f, 0.72f, 0.52f, 0.34f)
        metal.addBox(cx - 0.50f, baseY + 1.05f, cz + hd + 0.12f, 0.09f, 0.09f, 0.06f)
        timber.addBox(cx - 0.85f, baseY, cz + hd + 0.48f, 1.45f, 0.24f, 0.78f)
        // Тёплое янтарное свечение окон на фасаде и боковой стене
        glow.addBox(cx + 0.95f, baseY + 1.05f, cz + hd + 0.06f, 0.88f, 0.72f, 0.08f, 1.0f, 0.80f, 0.36f, 1f)
        timber.addBox(cx + 0.95f, baseY + 1.05f, cz + hd + 0.11f, 0.07f, 0.76f, 0.05f)
        timber.addBox(cx + 0.95f, baseY + 1.38f, cz + hd + 0.11f, 0.92f, 0.07f, 0.05f)
        glow.addBox(cx + hw + 0.06f, baseY + 1.05f, cz, 0.08f, 0.70f, 0.86f, 1.0f, 0.78f, 0.34f, 1f)
    }

    private fun initSkyQuadVbo() {
        val quadCoords = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            -1f, 1f,
            1f, 1f,
            1f, 1f
        )
        val buf = ByteBuffer.allocateDirect(quadCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(quadCoords)
                position(0)
            }
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        skyQuadVbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, skyQuadVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quadCoords.size * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun cacheShaderLocations() {
        aPositionLoc = GLES20.glGetAttribLocation(worldProgram, "aPosition")
        aNormalLoc = GLES20.glGetAttribLocation(worldProgram, "aNormal")
        aTexCoordLoc = GLES20.glGetAttribLocation(worldProgram, "aTexCoord")
        aColorLoc = GLES20.glGetAttribLocation(worldProgram, "aColor")
        uMvpLoc = GLES20.glGetUniformLocation(worldProgram, "uMvpMatrix")
        uModelLoc = GLES20.glGetUniformLocation(worldProgram, "uModelMatrix")
        uCamPosLoc = GLES20.glGetUniformLocation(worldProgram, "uCameraPos")
        uSunDirLoc = GLES20.glGetUniformLocation(worldProgram, "uSunDir")
        uSunColLoc = GLES20.glGetUniformLocation(worldProgram, "uSunColor")
        uAmbSkyLoc = GLES20.glGetUniformLocation(worldProgram, "uAmbientSky")
        uPtPos1Loc = GLES20.glGetUniformLocation(worldProgram, "uPointLightPos")
        uPtColLoc = GLES20.glGetUniformLocation(worldProgram, "uPointLightColor")
        uPtRad1Loc = GLES20.glGetUniformLocation(worldProgram, "uPointLightRadius")
        uPtPos2Loc = GLES20.glGetUniformLocation(worldProgram, "uPointLightPos2")
        uPtRad2Loc = GLES20.glGetUniformLocation(worldProgram, "uPointLightRadius2")
        uPtPos3Loc = GLES20.glGetUniformLocation(worldProgram, "uPointLightPos3")
        uPtRad3Loc = GLES20.glGetUniformLocation(worldProgram, "uPointLightRadius3")
        uFogColLoc = GLES20.glGetUniformLocation(worldProgram, "uFogColor")
        uFogNearLoc = GLES20.glGetUniformLocation(worldProgram, "uFogNear")
        uFogFarLoc = GLES20.glGetUniformLocation(worldProgram, "uFogFar")
        uUnlitLoc = GLES20.glGetUniformLocation(worldProgram, "uUnlit")
        uSpecLoc = GLES20.glGetUniformLocation(worldProgram, "uSpecularStrength")
        uTexLoc = GLES20.glGetUniformLocation(worldProgram, "uTexture")

        skyQuadAttrLoc = GLES20.glGetAttribLocation(skyProgram, "aScreenQuad")
        skyZenithLoc = GLES20.glGetUniformLocation(skyProgram, "uSkyZenith")
        skyHorizonLoc = GLES20.glGetUniformLocation(skyProgram, "uSkyHorizon")
        skyFogLoc = GLES20.glGetUniformLocation(skyProgram, "uFogColor")
        skySunUvLoc = GLES20.glGetUniformLocation(skyProgram, "uSunScreenUv")
        skySunHaloLoc = GLES20.glGetUniformLocation(skyProgram, "uSunHaloColor")
        skyCamYawLoc = GLES20.glGetUniformLocation(skyProgram, "uCamYaw")
        skyCamPitchLoc = GLES20.glGetUniformLocation(skyProgram, "uCamPitch")
        skyAspectLoc = GLES20.glGetUniformLocation(skyProgram, "uAspect")
        skyTimeLoc = GLES20.glGetUniformLocation(skyProgram, "uTime")
        skyAuroraLoc = GLES20.glGetUniformLocation(skyProgram, "uAuroraStrength")
        skyCloudsHighLoc = GLES20.glGetUniformLocation(skyProgram, "uCloudsHigh")
        skyCloudsLowLoc = GLES20.glGetUniformLocation(skyProgram, "uCloudsLow")
        skySunDiscLoc = GLES20.glGetUniformLocation(skyProgram, "uSunDisc")
        ensureSkyTextures()
    }

    private var skyTexHigh = 0
    private var skyTexLow = 0
    private var skyTexSun = 0
    private var skyCloudsHighLoc = -1
    private var skyCloudsLowLoc = -1
    private var skySunDiscLoc = -1

    private fun uploadSkyTexture(path: String, wrapRepeat: Boolean): Int {
        val ctx = appContext ?: return 0
        val bmp = runCatching {
            ctx.assets.open(path).use { android.graphics.BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return 0
        val outSize = if (wrapRepeat) 1024 else 256
        val scaled = if (bmp.width != outSize || bmp.height != outSize) {
            android.graphics.Bitmap.createScaledBitmap(bmp, outSize, outSize, true)
        } else {
            bmp
        }
        val buf = java.nio.ByteBuffer.allocateDirect(outSize * outSize * 4)
            .order(java.nio.ByteOrder.nativeOrder())
        scaled.copyPixelsToBuffer(buf)
        buf.position(0)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val wrap = if (wrapRepeat) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            outSize, outSize, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )
        return tex[0]
    }

    private fun ensureSkyTextures() {
        if (skyTexHigh == 0) skyTexHigh = uploadSkyTexture("sky/clouds_high.png", wrapRepeat = true)
        if (skyTexLow == 0) skyTexLow = uploadSkyTexture("sky/clouds_low.png", wrapRepeat = true)
        if (skyTexSun == 0) skyTexSun = uploadSkyTexture("sky/sun_disc.png", wrapRepeat = false)
    }

    private fun drawSkyDome(
        cycleT: Float,
        camYaw: Float,
        camPitch: Float,
        timeSec: Float,
        auroraStrength: Float
    ) {
        if (skyQuadVbo == 0) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(skyProgram)

        GLES20.glUniform3fv(skyZenithLoc, 1, zenithRgb, 0)
        GLES20.glUniform3fv(skyHorizonLoc, 1, horizonRgb, 0)
        GLES20.glUniform3fv(skyFogLoc, 1, fogRgb, 0)
        GLES20.glUniform3fv(skySunHaloLoc, 1, sunRgb, 0)
        val sunAz = cycleT * Math.PI.toFloat() * 2f - camYaw * 0.35f
        val sunX = (0.5f + 0.34f * sin(sunAz)).coerceIn(-0.4f, 1.4f)
        val sunY = (0.56f + 0.24f * sin(cycleT * Math.PI.toFloat() * 2f) - camPitch * 0.55f).coerceIn(-0.3f, 1.3f)
        GLES20.glUniform2f(skySunUvLoc, sunX, sunY)
        GLES20.glUniform1f(skyCamYawLoc, camYaw)
        GLES20.glUniform1f(skyCamPitchLoc, camPitch)
        GLES20.glUniform1f(skyAspectLoc, aspectRatio)
        GLES20.glUniform1f(skyTimeLoc, timeSec)
        GLES20.glUniform1f(skyAuroraLoc, auroraStrength)

        ensureSkyTextures()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, skyTexHigh)
        GLES20.glUniform1i(skyCloudsHighLoc, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, skyTexLow)
        GLES20.glUniform1i(skyCloudsLowLoc, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, skyTexSun)
        GLES20.glUniform1i(skySunDiscLoc, 2)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, skyQuadVbo)
        GLES20.glEnableVertexAttribArray(skyQuadAttrLoc)
        GLES20.glVertexAttribPointer(skyQuadAttrLoc, 2, GLES20.GL_FLOAT, false, 8, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(skyQuadAttrLoc)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun applyWorldUniforms(
        camX: Float, camY: Float, camZ: Float,
        sunAngle: Float,
        rad1: Float,
        rad2: Float,
        rad3: Float,
        fogNear: Float,
        fogFar: Float
    ) {
        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uModelLoc, 1, false, modelMatrix, 0)
        GLES20.glUniform3f(uCamPosLoc, camX, camY, camZ)
        GLES20.glUniform3f(
            uSunDirLoc,
            cos(sunAngle) * 0.62f,
            (0.32f + 0.68f * sin(sunAngle)).coerceAtLeast(0.14f),
            0.46f
        )
        GLES20.glUniform3fv(uSunColLoc, 1, sunRgb, 0)
        GLES20.glUniform3fv(uAmbSkyLoc, 1, ambientRgb, 0)
        GLES20.glUniform3fv(uPtPos1Loc, 1, ptLight1, 0)
        GLES20.glUniform3f(uPtColLoc, 1.0f, 0.70f, 0.34f)
        GLES20.glUniform1f(uPtRad1Loc, rad1)
        GLES20.glUniform3fv(uPtPos2Loc, 1, ptLight2, 0)
        GLES20.glUniform1f(uPtRad2Loc, rad2)
        GLES20.glUniform3fv(uPtPos3Loc, 1, ptLight3, 0)
        GLES20.glUniform1f(uPtRad3Loc, rad3)
        GLES20.glUniform3fv(uFogColLoc, 1, fogRgb, 0)
        GLES20.glUniform1f(uFogNearLoc, fogNear)
        GLES20.glUniform1f(uFogFarLoc, fogFar)
    }

    /** Разовая отрисовка импортированной модели в точке мира (волк/олень и т.п.). */
    private fun drawModelInstance(modelId: String, x: Float, y: Float, z: Float, yawRad: Float, scale: Float) {
        val vbo = assetPropBatches[modelId] ?: return
        val tex = assetTextures[modelId] ?: return
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, z)
        Matrix.rotateM(modelMatrix, 0, yawRad * 180f / Math.PI.toFloat(), 0f, 1f, 0f)
        if (scale != 1f) Matrix.scaleM(modelMatrix, 0, scale, scale, scale)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uModelLoc, 1, false, modelMatrix, 0)
        drawVbo(vbo, tex, unlit = false, specStrength = 0.22f)
        // восстановить identity для последующих отрисовок
        Matrix.setIdentityM(modelMatrix, 0)
        System.arraycopy(vpMatrix, 0, mvpMatrix, 0, 16)
        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uModelLoc, 1, false, modelMatrix, 0)
    }

    private fun drawVbo(vbo: GlVbo, textureId: Int, unlit: Boolean, specStrength: Float) {
        if (vbo.bufferId == 0 || vbo.vertexCount <= 0 || textureId == 0) return
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTexLoc, 0)
        GLES20.glUniform1f(uUnlitLoc, if (unlit) 1f else 0f)
        GLES20.glUniform1f(uSpecLoc, specStrength)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo.bufferId)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, GlMesh.STRIDE_BYTES, 0)

        GLES20.glEnableVertexAttribArray(aNormalLoc)
        GLES20.glVertexAttribPointer(aNormalLoc, 3, GLES20.GL_FLOAT, false, GlMesh.STRIDE_BYTES, 12)

        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, GlMesh.STRIDE_BYTES, 24)

        GLES20.glEnableVertexAttribArray(aColorLoc)
        GLES20.glVertexAttribPointer(aColorLoc, 4, GLES20.GL_FLOAT, false, GlMesh.STRIDE_BYTES, 32)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vbo.vertexCount)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aNormalLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        GLES20.glDisableVertexAttribArray(aColorLoc)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun updateFpsCounter(nowNs: Long) {
        if (fpsWindowStartNs == 0L) {
            fpsWindowStartNs = nowNs
            fpsFramesCount = 0
            return
        }
        fpsFramesCount += 1
        val elapsedNs = nowNs - fpsWindowStartNs
        if (elapsedNs >= 1_000_000_000L) {
            val fps = ((fpsFramesCount * 1_000_000_000L) / elapsedNs).toInt().coerceIn(1, 240)
            onFpsSample?.invoke(fps)
            fpsWindowStartNs = nowNs
            fpsFramesCount = 0
        }
    }

    private fun materialSpecular(mat: ProceduralTextureId): Float = when (mat) {
        ProceduralTextureId.ICE -> 0.85f
        ProceduralTextureId.METAL_STEEL -> 0.72f
        ProceduralTextureId.SNOW -> 0.25f
        ProceduralTextureId.STONE -> 0.16f
        else -> 0.06f
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
        /** Окно интерполяции позиции = период тика симуляции (20 Гц). */
        private const val TICK_INTERP_WINDOW_NS = 50_000_000L

        /** Скачок больше этой дистанции считается телепортом. */
        private const val SNAP_DISTANCE_M = 4.0f

        private val OPAQUE_DRAW_ORDER = arrayOf(
            ProceduralTextureId.WOOD_BARK,
            ProceduralTextureId.TIMBER,
            ProceduralTextureId.STONE,
            ProceduralTextureId.METAL_STEEL,
            ProceduralTextureId.FUR_HIDE,
            ProceduralTextureId.CLOTH,
            ProceduralTextureId.ICE,
            ProceduralTextureId.SNOW,
            ProceduralTextureId.PINE_FOLIAGE
        )

        fun computeDaylightFactor(cycleFraction: Float): Float {
            return when {
                cycleFraction < 0.08f -> 0.45f + (cycleFraction / 0.08f) * 0.55f
                cycleFraction < 0.52f -> 1.0f
                cycleFraction < 0.62f -> 1.0f - ((cycleFraction - 0.52f) / 0.10f) * 0.80f
                cycleFraction < 0.92f -> 0.20f
                else -> 0.20f + ((cycleFraction - 0.92f) / 0.08f) * 0.25f
            }.coerceIn(0.18f, 1.0f)
        }

        fun scaleHexColor(hex: Int, factor: Float): FloatArray {
            val out = FloatArray(3)
            fillScaledHex(hex, factor, out)
            return out
        }

        private fun fillScaledHex(hex: Int, factor: Float, out: FloatArray) {
            out[0] = (((hex ushr 16) and 0xFF) / 255f * factor).coerceIn(0f, 1f)
            out[1] = (((hex ushr 8) and 0xFF) / 255f * factor).coerceIn(0f, 1f)
            out[2] = ((hex and 0xFF) / 255f * factor).coerceIn(0f, 1f)
        }
    }
}

class ForestGlSurfaceView(
    context: Context,
    onFpsSample: ((Int) -> Unit)? = null,
    val forestRenderer: ForestGlRenderer = ForestGlRenderer(onFpsSample, context.applicationContext)
) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        // Лимит 60 FPS снят: swap interval 0 отключает ожидание vsync в eglSwapBuffers,
        // кадры уходят на панель с максимально возможной частотой (90/120/144 Гц).
        setEGLWindowSurfaceFactory(SwapIntervalZeroSurfaceFactory())
        setRenderer(forestRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        // Android 11+ (API 30): явный запрос системе — не троттлить частоту кадров.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.surface.setFrameRate(
                0f,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
            )
        }
    }

    /** EGL-поверхность с eglSwapInterval(0) — рендер без ожидания вертикального интервала. */
    private class SwapIntervalZeroSurfaceFactory : GLSurfaceView.EGLWindowSurfaceFactory {
        override fun createWindowSurface(
            egl: EGL10,
            display: EGLDisplay,
            config: EGLConfig,
            nativeWindow: Any?
        ): EGLSurface {
            val surface = egl.eglCreateWindowSurface(display, config, nativeWindow, null)
            // 0 = не ждать vsync при показе кадра (лимит в 60 FPS снят)
            egl.eglSwapInterval(display, 0)
            return surface
        }

        override fun destroySurface(egl: EGL10, display: EGLDisplay, surface: EGLSurface) {
            egl.eglDestroySurface(display, surface)
        }
    }
}
