package com.ayaks.winterforest.presentation.gl

import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.EquippedTool
import com.ayaks.winterforest.domain.model.GameRunState
import com.ayaks.winterforest.domain.model.GraphicsQuality
import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.ResourceNode
import com.ayaks.winterforest.domain.model.SpecialLootId
import com.ayaks.winterforest.domain.model.ResourceType
import com.ayaks.winterforest.domain.model.WorldObjectKind
import com.ayaks.winterforest.domain.model.WildlifeKind
import com.ayaks.winterforest.domain.world.WorldPopulator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumMap
import javax.microedition.khronos.egl.EGLConfig
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
 * - 0 аллокаций ByteBuffer/FloatArray в onDrawFrame() -> ровные 60 FPS на vsync без GC-фризов
 *   (eglSwapInterval(1) + запрос 60 Гц у поверхности + интерполяция 20-Гц тиков);
 * - панорамный купол неба 360° с 3D-горами, звёздами и Северным сиянием;
 * - до 3 тёплых динамических источников света + блики на льду и металле + частицы удара и метели.
 */
class ForestGlRenderer(
    private val onFpsSample: ((Int) -> Unit)? = null,
    private val appContext: Context? = null,
    /**
     * Вызывается ОДИН раз из GL-потока после первого полностью отрисованного кадра.
     * Это единственный честный признак «карта загружена»: до него экран загрузки
     * скрывает интерфейс, после — снимается.
     */
    private val onFirstFrameReady: (() -> Unit)? = null
) : GLSurfaceView.Renderer {

    /** Флаг первого кадра: колбэк готовности сцены не должен дёргаться каждый кадр. */
    @Volatile private var firstFrameReported = false

    @Volatile
    var snapshot: RenderSceneSnapshot = RenderSceneSnapshot()
        set(value) {
            // Окно интерполяции: тики симуляции приходят на 20 Гц, а кадры — на 60 Гц.
            // Позицию игрока сглаживаем между тиками, чтобы шаг не дёргался сеткой тиков.
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
                smoothPrevBobPhase = smoothCurBobPhase
                smoothCurBobPhase = value.run.walkBobPhaseRad
                smoothStartNs = System.nanoTime()
                smoothInitialized = true
            }
            syncBeasts(value)
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
    @Volatile private var smoothPrevBobPhase = 0f
    @Volatile private var smoothCurBobPhase = 0f
    @Volatile private var smoothStartNs = 0L

    /**
     * Сглаживание зверей между 20-герцевыми тиками (ключ — стабильный id зверя):
     * позиция, поворот по кратчайшей дуге и фаза шага лап. Без этого звери
     * дёргаются сеткой тиков и «телепортируются» на высоких FPS.
     */
    private class BeastSmooth {
        @Volatile var prevX = 0f
        @Volatile var prevZ = 0f
        @Volatile var prevH = 0f
        @Volatile var prevGait = 0f
        @Volatile var curX = 0f
        @Volatile var curZ = 0f
        @Volatile var curH = 0f
        @Volatile var curGait = 0f
        @Volatile var startNs = 0L
        @Volatile var initialized = false
        @Volatile var moving = false
    }

    private val beastSmooth = HashMap<String, BeastSmooth>()
    private var beastSmoothLoc: LocationId? = null

    private fun syncBeasts(value: RenderSceneSnapshot) {
        val now = System.nanoTime()
        synchronized(beastSmooth) {
            // В едином мире фауна всех районов видна сразу — сглаживание не сбрасываем
            val seen = HashSet<String>(beastSmooth.size + 4)
            for (beast in value.run.currentWildlife()) {
                if (!beast.isAlive) continue
                seen.add(beast.id)
                val bs = beastSmooth.getOrPut(beast.id) { BeastSmooth() }
                val dx = beast.x - bs.curX
                val dz = beast.z - bs.curZ
                val teleport = (dx * dx + dz * dz) > SNAP_DISTANCE_M * SNAP_DISTANCE_M
                val posMoved = beast.x != bs.curX || beast.z != bs.curZ
                val headMoved = beast.headingRad != bs.curH
                if (!bs.initialized || teleport) {
                    bs.prevX = beast.x; bs.prevZ = beast.z
                    bs.prevH = beast.headingRad; bs.prevGait = bs.curGait
                    bs.curX = beast.x; bs.curZ = beast.z
                    bs.curH = beast.headingRad
                    bs.initialized = true
                    bs.moving = false
                    bs.startNs = now
                } else if (posMoved || headMoved) {
                    if (posMoved) {
                        bs.prevX = bs.curX
                        bs.prevZ = bs.curZ
                        bs.prevGait = bs.curGait
                        bs.curGait += kotlin.math.sqrt(dx * dx + dz * dz) * GAIT_RAD_PER_METER
                        bs.moving = true
                    } else {
                        bs.moving = false
                    }
                    bs.prevH = bs.curH
                    bs.curX = beast.x; bs.curZ = beast.z
                    bs.curH = beast.headingRad
                    bs.startNs = now
                } else {
                    bs.moving = false
                }
            }
            if (seen.size != beastSmooth.size) {
                beastSmooth.keys.retainAll(seen)
            }
        }
    }

    private fun beastSmoothOf(id: String): BeastSmooth? = synchronized(beastSmooth) { beastSmooth[id] }

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
    private var assetPropsBuiltQuality: GraphicsQuality? = null
    private var assetTexturesQuality: GraphicsQuality? = null

    // Железные ворота на тропах между районами: рама + две створки на петлях.
    // Створки распахиваются, когда игрок подходит к проходу, и закрываются позади.
    private class TrailGate(val x: Float, val z: Float, val yawRad: Float)
    private val trailGates = listOf(
        TrailGate(0f, -48f, 0f),
        TrailGate(48f, 0f, (Math.PI / 2.0).toFloat()),
        TrailGate(-48f, 0f, (Math.PI / 2.0).toFloat()),
        TrailGate(0f, 48f, 0f)
    )
    private var gateFrameVbo: GlVbo? = null
    private var gateDoorLVbo: GlVbo? = null
    private var gateDoorRVbo: GlVbo? = null
    private val gateOpenAmount = FloatArray(4)
    private var gateAnimPrevNs = 0L


    // Камни-ресурсы (камень/руда/уголь) рисуются МОДЕЛЯМИ автора (rock_09 / rock_moss_*),
    // а не процедурными «булыжниками»: батчи перестраиваются вместе с узлами локации.
    private val rockNodeBatches = HashMap<String, GlVbo>()

    // Модели инструментов в РУКАХ игрока (первое лицо): топор/кирка/копьё
    private val toolHandVbos = HashMap<String, GlVbo>()

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
        // Целевые 60 FPS: eglSwapInterval(1) заставляет кадр ждать вертикальный
        // синхроимпульс. Прежний интервал 0 разрешал 120+ полных кадров тяжёлой
        // сцены в секунду — SoC упиралась в термопакет и роняла частоты, что
        // на устройстве выглядело как постоянные микрофризы.
        val eglDisplay = EGL14.eglGetCurrentDisplay()
        if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglSwapInterval(eglDisplay, FramePacing.TARGET_SWAP_INTERVAL)
        }
        // При пересоздании EGL-контекста сбрасываем старые GPU-хэндлы.
        // Небо (облака/солнце/луна и quad) раньше не сбрасывалось: после каждого
        // поворота экрана утекало 4 текстуры и один VBO.
        releaseSkyResources()
        uploadedPaletteLocation = null
        staticBuiltQuality = null
        staticBuiltBaseSig = -1
        cachedNodeStateHash = Int.MIN_VALUE
        textures.clear()
        terrainVbo.release()
        lakeIceVbo.release()
        staticBatches.values.forEach { it.release() }
        nodeBatches.values.forEach { it.release() }
        dynVbos.values.forEach { it.release() }
        assetPropBatches.values.forEach { it.release() }
        assetPropBatches.clear()
        entityParts.values.forEach { ep ->
            ep.body.release()
            ep.legs.forEach { it.vbo.release() }
        }
        entityParts.clear()
        rockNodeBatches.values.forEach { it.release() }
        rockNodeBatches.clear()
        toolHandVbos.values.forEach { it.release() }
        toolHandVbos.clear()
        assetTextures.values.forEach { id -> if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0) }
        assetTextures.clear()
        assetTexturesQuality = null
        assetPropsBuiltQuality = null

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
        // Днём зенит — холодное синее небо: иначе палитра «Snowy Night» даёт чёрный верх
        val dayMix = daylight * daylight
        zenithRgb[0] = zenithRgb[0] * (1f - dayMix) + 0.33f * dayMix
        zenithRgb[1] = zenithRgb[1] * (1f - dayMix) + 0.46f * dayMix
        zenithRgb[2] = zenithRgb[2] * (1f - dayMix) + 0.68f * dayMix
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
            camY = WorldPopulator.worldHeight(camX, camZ) + 3.1f + 0.35f * sin(animTimeSec * 0.4f)
            val lookTargetY = WorldPopulator.worldHeight(-2.0f, -2.0f) + 1.5f
            val dx = -2.0f - camX
            val dy = lookTargetY - camY
            val dz = -2.0f - camZ
            val flatDist = sqrt(dx * dx + dz * dz).coerceAtLeast(0.01f)
            yawRad = kotlin.math.atan2(dx, -dz)
            pitchRad = kotlin.math.atan2(dy, flatDist)
        } else {
            // Позиция сглаживается между 20-герцевыми тиками: на 60 FPS шаг
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
            ptLight1[1] = WorldPopulator.worldHeight(0f, 0f) + 1.35f
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
            ptLight2[1] = MeshBuilder.sampleWorldHeight(activeFieldFire.x, activeFieldFire.z, worldTerrainSteps(quality)) + 1.1f
            ptLight2[2] = activeFieldFire.z
            rad2 = 15.5f * fireFlicker
        } else if (loc == LocationId.HUNTERS_CABIN) {
            ptLight2[0] = 7.2f
            ptLight2[1] = MeshBuilder.sampleWorldHeight(7.2f, 128.8f, worldTerrainSteps(quality)) + 1.8f
            ptLight2[2] = 126.2f
            rad2 = 13.5f * fireFlicker
        } else if (loc == LocationId.BASE_CLEARING) {
            ptLight2[0] = -6.5f
            ptLight2[1] = WorldPopulator.worldHeight(-6.5f, -5.5f) + 1.5f
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
        // 6b-2. Железные ворота на тропах: створки распахиваются при подходе игрока
        drawTrailGates(camX, camZ, quality)
        // 6c. Камни-ресурсы (камень/руда/уголь) моделями автора
        for ((modelId, vbo) in rockNodeBatches) {
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

        // 8. Первый кадр собран и отправлен в swapchain — сообщаем UI, что тайгу
        //    больше не нужно прятать за экраном загрузки (ровно один раз за сессию).
        if (!firstFrameReported) {
            firstFrameReported = true
            onFirstFrameReady?.invoke()
        }
    }

    /** Загрузка импортированных моделей и построение статических пропов локации. */
    private fun ensureAssetPropsReady(loc: LocationId, quality: GraphicsQuality) {
        val ctx = appContext ?: return
        if (assetTexturesQuality != quality) {
            assetTextures.values.forEach { id -> if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0) }
            assetTextures.clear()
            // Текстуры пропов грузим в НАТИВНОМ размере источника (512×512, часть 512×256):
            // апскейл до 1024/2048 не добавлял деталей, но стоил 4–16× видеопамяти
            // (34 модели × 16 МБ = 544 МБ на ULTRA — гарантированный OOM).
            val texCap = quality.textureSize
            for (id in AssetProps.ALL_MODEL_IDS) {
                val tex = AssetTextureLibrary.loadModelTexture(ctx, "models/$id/diff.png", texCap)
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
                    tex.width, tex.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, tex.buffer
                )
                GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
                assetTextures[id] = texHandle[0]
            }
            assetTexturesQuality = quality
        }
        if (entityParts.isEmpty()) {
            for (id in AssetProps.ENTITY_MODEL_IDS) {
                buildEntityParts(id)
            }
        }
        if (toolHandVbos.isEmpty()) {
            for (id in listOf("wooden_axe", "pickaxe", "spear")) {
                buildToolHandVbo(id)
            }
        }
        if (assetPropsBuiltQuality != quality) {
            rebuildAssetPropsVbo(quality)
            buildTrailGateVbos()
            assetPropsBuiltQuality = quality
        }
    }

    /** VBO железных ворот: рама + левая и правая створки (общая нормализация мешей). */
    private fun buildTrailGateVbos() {
        val ctx = appContext ?: return
        gateFrameVbo?.release(); gateDoorLVbo?.release(); gateDoorRVbo?.release()
        gateFrameVbo = null; gateDoorLVbo = null; gateDoorRVbo = null
        val frame = readAssetFloats(ctx, "models/gate/mesh_frame.obj")
        val left = readAssetFloats(ctx, "models/gate/mesh_left.obj")
        val right = readAssetFloats(ctx, "models/gate/mesh_right.obj")
        if (frame.isNotEmpty()) {
            gateFrameVbo = GlVbo().also { it.uploadStatic(floatsToBuilder(frame)) }
        }
        if (left.isNotEmpty()) {
            gateDoorLVbo = GlVbo().also { it.uploadStatic(floatsToBuilder(left)) }
        }
        if (right.isNotEmpty()) {
            gateDoorRVbo = GlVbo().also { it.uploadStatic(floatsToBuilder(right)) }
        }
    }

    private fun rebuildAssetPropsVbo(quality: GraphicsQuality) {
        val ctx = appContext ?: return
        assetPropBatches.values.forEach { it.release() }
        assetPropBatches.clear()

        // Все районы большого мира: расстановки + авторские камни смещены в глобальные координаты
        val byModel = HashMap<String, MutableList<AssetProps.Placement>>()
        for (region in com.ayaks.winterforest.domain.world.WorldRegions.REGIONS) {
            for (p0 in AssetProps.forLocation(region.id)) {
                val p = p0.copy(x = p0.x + region.cx, z = p0.z + region.cz)
                byModel.getOrPut(p.modelId) { mutableListOf() }.add(p)
            }
        }
        // Модели животных НЕ пекутся в статику: они рисуются по месту через entityParts
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
                val groundY = MeshBuilder.sampleWorldHeight(p.x, p.z, worldTerrainSteps(quality))
                val cos = kotlin.math.cos(p.yawRad)
                val sin = kotlin.math.sin(p.yawRad)
                val cp = kotlin.math.cos(p.pitchRad)
                val sp = kotlin.math.sin(p.pitchRad)
                var i = 0
                while (i + 11 < floats.size) {
                    val lx = floats[i]; val ly = floats[i + 1]; val lz = floats[i + 2]
                    val nx = floats[i + 3]; val ny = floats[i + 4]; val nz = floats[i + 5]
                    val u = floats[i + 6]; val v = floats[i + 7]
                    val r = floats[i + 8]; val g = floats[i + 9]; val b = floats[i + 10]; val a = floats[i + 11]
                    // pitch вокруг локальной X, затем yaw вокруг Y
                    val px1 = lx
                    val py1 = ly * cp - lz * sp
                    val pz1 = ly * sp + lz * cp
                    val nx1 = nx
                    val ny1 = ny * cp - nz * sp
                    val nz1 = ny * sp + nz * cp
                    builder.addVertex(
                        p.x + (px1 * cos + pz1 * sin) * p.scale,
                        groundY + py1 * p.scale,
                        p.z + (-px1 * sin + pz1 * cos) * p.scale,
                        nx1 * cos + nz1 * sin,
                        ny1,
                        -nx1 * sin + nz1 * cos,
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
        // Разобранные в FloatArray меши живут только до загрузки в VBO: кэш на все
        // 33 модели держал ~41 МБ heap (а cabin_wood один — 4.6 МБ) до конца сессии.
        // Геометрия уже на GPU, повторная сборка перечитает файл с диска.
        assetMeshCache.clear()
    }

    private fun ensureGpuSceneReady(
        run: GameRunState,
        base: BaseProgress,
        quality: GraphicsQuality
    ) {
        val loc = run.currentLocation

        // 1. Текстуры биома + Mipmap-цепочка (одна палитра на весь единый мир —
        //    без перезаливки текстур при переходе между районами)
        if (uploadedQuality != quality || textures.isEmpty()) {
            textures.values.forEach { id ->
                if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
            }
            textures.clear()
            val texRes = quality.textureSize
            val snowTintHex = LocationId.BASE_CLEARING.palette.snowTintHex
            val snowTintR = ((snowTintHex ushr 16) and 0xFF) / 255f
            val snowTintG = ((snowTintHex ushr 8) and 0xFF) / 255f
            val snowTintB = (snowTintHex and 0xFF) / 255f
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

        // 2. Статическая геометрия ЕДИНОГО большого мира (рельеф + архитектура всех районов + лес)
        val baseSig = base.shelterLevel * 31 + base.campfireLevel * 7 + base.workbenchLevel * 3 +
            base.furnaceLevel * 13 + (if (base.thermosInstalledOnBase) 1000 else 0)
        if (staticBuiltQuality != quality || staticBuiltBaseSig != baseSig) {
            val terrainSteps = worldTerrainSteps(quality)
            val terrainBuilder = MeshBuilder.buildWorldTerrainIntoBuilder(steps = terrainSteps)
            terrainVbo.uploadStatic(terrainBuilder)

            // Лёд Замёрзшего озера — в глобальных координатах района (120, 0)
            val iceBuilder = MeshBuilder(512).apply {
                val segs = 12
                val half = 23.2f
                val step = (half * 2f) / segs
                for (iz in 0 until segs) {
                    for (ix in 0 until segs) {
                        val x0 = -half + ix * step; val x1 = x0 + step
                        val z0 = -half + iz * step; val z1 = z0 + step
                        addQuad(
                            120f + x0, -0.16f, z1, 120f + x1, -0.16f, z1, 120f + x1, -0.16f, z0, 120f + x0, -0.16f, z0,
                            0f, 1f, 0f, 1.35f, 1.35f, 0.90f, 0.97f, 1.0f, 0.94f
                        )
                    }
                }
            }
            lakeIceVbo.uploadStatic(iceBuilder)

            rebuildStaticWorldArchitecture(base, quality)
            staticBuiltQuality = quality
            staticBuiltBaseSig = baseSig
            cachedNodeStateHash = Int.MIN_VALUE
        }

        // 3. Интерактивные ресурсные узлы (перезаливаются при изменении доступности ИЛИ смене района)
        val nodes = run.currentLocationNodes()
        val nodeSig = computeNodeAvailabilitySignature(
            run.playerX, run.playerZ, nodes, run.droppedItems
        )
        if (nodeSig != cachedNodeStateHash) {
            rebuildInteractiveNodesVbo(
                nodes, run.playerX, run.playerZ, quality,
                droppedItems = run.droppedItems,
                highlightDropId = run.nearestDroppedItem()?.id
            )
            cachedNodeStateHash = nodeSig
        }
    }

    private fun worldTerrainSteps(quality: GraphicsQuality): Int = when (quality) {
        GraphicsQuality.LOW -> 140
        GraphicsQuality.MEDIUM -> 180
        GraphicsQuality.HIGH -> 220
        GraphicsQuality.ULTRA -> 260
    }

    private fun computeNodeAvailabilitySignature(
        px: Float,
        pz: Float,
        nodes: List<ResourceNode>,
        dropped: List<com.ayaks.winterforest.domain.model.DroppedItem> = emptyList()
    ): Int {
        // Смена района игрока = новая выборка дальних узлов
        var h = (px / 32f).toInt() * 73856093 xor (pz / 32f).toInt() * 19349663
        h = h * 31 + nodes.size
        for (i in nodes.indices) {
            h = h * 31 + (if (nodes[i].isAvailable) 1 else 0)
        }
        // §7.12: выброшенные предметы тоже пекутся в VBO узлов
        h = h * 31 + dropped.size
        for (i in dropped.indices) {
            h = h * 31 + dropped[i].id.hashCode()
        }
        return h
    }

    /** Собирает архитектуру всех пяти районов большого мира со сдвигом в глобальные координаты. */
    private fun rebuildStaticWorldArchitecture(base: BaseProgress, quality: GraphicsQuality) {
        val shared = EnumMap<ProceduralTextureId, MeshBuilder>(ProceduralTextureId::class.java)
        for (mat in ProceduralTextureId.entries) {
            shared[mat] = MeshBuilder(32768)
        }
        val temp = EnumMap<ProceduralTextureId, MeshBuilder>(ProceduralTextureId::class.java)
        for (mat in ProceduralTextureId.entries) {
            temp[mat] = MeshBuilder(16384)
        }
        for (region in com.ayaks.winterforest.domain.world.WorldRegions.REGIONS) {
            for (mat in ProceduralTextureId.entries) {
                temp[mat]?.clear()
            }
            buildLocationArchitecture(region.id, base, quality, temp)
            for (mat in ProceduralTextureId.entries) {
                val src = temp[mat] ?: continue
                shared[mat]?.appendTranslatedFrom(src, region.cx, region.cz)
            }
        }
        // Вековой лес по краю всего мира — сплошная живая стена
        val bark = shared[ProceduralTextureId.WOOD_BARK]!!
        val foliage = shared[ProceduralTextureId.PINE_FOLIAGE]!!
        val ringCount = quality.perimeterTrees
        for (i in 0 until ringCount) {
            val ang = (i.toFloat() / ringCount.toFloat()) * Math.PI.toFloat() * 2f
            val rad = 158.5f + (i % 3) * 1.7f
            val bx = sin(ang) * rad
            val bz = -cos(ang) * rad
            val by = MeshBuilder.sampleWorldHeight(bx, bz, worldTerrainSteps(quality))
            addDetailedPineTree(bark, foliage, bx, by, bz, scale = 1.6f + (i % 4) * 0.18f, seed = i * 17, drawShadow = false)
        }
        val outerCount = (ringCount * 3) / 4
        for (i in 0 until outerCount) {
            val ang = (i.toFloat() / outerCount.toFloat()) * Math.PI.toFloat() * 2f + 0.09f
            val rad = 164.5f + (i % 2) * 1.9f
            val bx = sin(ang) * rad
            val bz = -cos(ang) * rad
            val by = MeshBuilder.sampleWorldHeight(bx, bz, worldTerrainSteps(quality))
            addDetailedPineTree(bark, foliage, bx, by, bz, scale = 1.9f + (i % 3) * 0.2f, seed = 900 + i, drawShadow = false)
        }
        for ((mat, builder) in shared) {
            staticBatches[mat]?.uploadStatic(builder)
        }
    }

    private fun buildLocationArchitecture(
        loc: LocationId,
        base: BaseProgress,
        quality: GraphicsQuality,
        builders: EnumMap<ProceduralTextureId, MeshBuilder>
    ) {
        val bark = builders[ProceduralTextureId.WOOD_BARK]!!
        val foliage = builders[ProceduralTextureId.PINE_FOLIAGE]!!
        val stone = builders[ProceduralTextureId.STONE]!!
        val timber = builders[ProceduralTextureId.TIMBER]!!
        val cloth = builders[ProceduralTextureId.CLOTH]!!
        val metal = builders[ProceduralTextureId.METAL_STEEL]!!
        val fire = builders[ProceduralTextureId.FIRE_EMBER]!!

        if (loc == LocationId.BASE_CLEARING) {
            val baseY = WorldPopulator.worldHeight(0f, 0f)
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

            // Верстак-тележка рисуется импортной моделью (4.8, -4.5); сверху — наковальня и Термос
            val wbX = 4.8f; val wbZ = -4.5f
            val wbY = WorldPopulator.terrainHeight(loc, wbX, wbZ)
            foliage.addShadowDisc(wbX, wbY, wbZ, 1.45f, 0.34f)
            metal.addBox(wbX - 0.45f, wbY + 1.18f, wbZ, 0.42f, 0.24f, 0.24f, 0.72f, 0.76f, 0.82f)
            if (base.thermosInstalledOnBase) {
                metal.addTaperedCylinder(wbX + 0.52f, wbY + 1.16f, wbZ, wbX + 0.52f, wbY + 1.68f, wbZ, 0.14f, 0.13f, 10, 0.90f, 0.96f, 1.0f)
                cloth.addTaperedCylinder(wbX + 0.52f, wbY + 1.30f, wbZ, wbX + 0.52f, wbY + 1.50f, wbZ, 0.146f, 0.146f, 10)
            }
            // Каменная печь (строится игроком): массивный корпус, чугунная дверца, труба
            if (base.furnaceLevel >= 1) {
                val fx = 6.3f; val fz = -5.3f
                val fy = WorldPopulator.terrainHeight(loc, fx, fz)
                foliage.addShadowDisc(fx, fy, fz, 1.65f, 0.38f)
                stone.addBox(fx, fy + 0.70f, fz, 1.42f, 1.40f, 1.22f)
                stone.addBox(fx, fy + 1.48f, fz, 1.58f, 0.16f, 1.38f)
                stone.addBox(fx + 0.38f, fy + 2.32f, fz - 0.24f, 0.38f, 1.70f, 0.38f)
                stone.addBox(fx + 0.38f, fy + 3.18f, fz - 0.24f, 0.50f, 0.12f, 0.50f)
                metal.addBox(fx - 0.74f, fy + 0.66f, fz, 0.08f, 0.60f, 0.50f)
                metal.addBox(fx - 0.80f, fy + 0.66f, fz + 0.18f, 0.05f, 0.16f, 0.09f)
                fire.addBox(fx - 0.78f, fy + 0.66f, fz, 0.03f, 0.42f, 0.34f, 1.0f, 0.62f, 0.26f, 1f)
            }
        } else if (loc == LocationId.HUNTERS_CABIN) {
            val cabX = 7.2f; val cabZ = 8.8f
            val cabY = WorldPopulator.terrainHeight(loc, cabX, cabZ)
            // Зимовье рисуется импортированной 3D-моделью (cabin_wood/bark/stone/shingle/burned),
            // её поворот — 3.6 рад. Внутренность достроена ВДОЛЬ этой оси, чтобы пол перекрывал
            // снег, а стены и мебель не «вылазили» сквозь импортную оболочку.
            val cabYaw = 3.6f
            val cy = kotlin.math.cos(cabYaw)
            val sy = kotlin.math.sin(cabYaw)
            fun cabAt(dx: Float, dz: Float): FloatArray =
                floatArrayOf(cabX + dx * cy + dz * sy, cabZ - dx * sy + dz * cy)
            // Интерьер вписан в РЕАЛЬНЫЕ стены импортной модели (обмер по сетке:
            // сруб X [-1.55…+1.05], Z [-4.75…+0.30]; дверь в восточной стене при
            // Z [-2.30…-1.45]). Прежняя раскладка была нарисована для сруба
            // 2.9 × 3.8 м: кровать и «свечение двери» висели снаружи избы,
            // печь и стол протыкали брёвна. Внутренний габарит — 2.2 × 4.7 м.
            // Сплошной деревянный пол (снег больше не «вылазит» сквозь избу)
            val floor = cabAt(-0.25f, -2.22f)
            timber.addOrientedBox(floor[0], cabY + 0.22f, floor[1], 2.24f, 0.14f, 4.72f, cabYaw, 0f, 0f, 0.72f, 0.58f, 0.42f)
            // Кровать охотника у западной стены, изголовьем к печи
            val bed = cabAt(-0.75f, -0.95f)
            timber.addOrientedBox(bed[0], cabY + 0.42f, bed[1], 1.00f, 0.34f, 1.80f, cabYaw, 0f, 0f)
            cloth.addOrientedBox(bed[0], cabY + 0.62f, bed[1], 0.94f, 0.14f, 1.45f, cabYaw, 0f, 0f, 0.62f, 0.42f, 0.34f)
            // Обеденный стол со скамьёй у окна
            val table = cabAt(0.34f, -2.85f)
            timber.addOrientedBox(table[0], cabY + 0.74f, table[1], 0.82f, 0.09f, 1.00f, cabYaw, 0f, 0f)
            timber.addOrientedBox(table[0], cabY + 0.38f, table[1], 0.15f, 0.72f, 0.15f, cabYaw, 0f, 0f)
            val bench = cabAt(0.34f, -3.55f)
            timber.addOrientedBox(bench[0], cabY + 0.44f, bench[1], 0.38f, 0.10f, 0.92f, cabYaw, 0f, 0f)
            // Чугунная печка с трубой и тёплым светом из топки.
            // Высота сруба — 1.86 м (масштаб 1.0): труба 0.8 м над топкой уходит в
            // потолок, а не сквозь крышу, как было при масштабе 0.78 (H = 1.45 м).
            val stove = cabAt(-1.00f, -3.92f)
            metal.addOrientedBox(stove[0], cabY + 0.50f, stove[1], 0.62f, 0.72f, 0.62f, cabYaw, 0f, 0f, 0.35f, 0.36f, 0.38f)
            metal.addOrientedBox(stove[0], cabY + 1.20f, stove[1], 0.18f, 0.72f, 0.18f, cabYaw, 0f, 0f, 0.32f, 0.33f, 0.35f)
            val firebox = cabAt(-0.70f, -3.92f)
            fire.addOrientedBox(firebox[0], cabY + 0.42f, firebox[1], 0.05f, 0.28f, 0.26f, cabYaw, 0f, 0f, 1.0f, 0.62f, 0.26f, 1f)
            // Тёплое свечение у дверного проёма (восточная стена, Z -2.30…-1.45)
            val glowWall = cabAt(0.72f, -1.88f)
            fire.addOrientedBox(glowWall[0], cabY + 1.00f, glowWall[1], 0.05f, 0.85f, 0.80f, cabYaw, 0f, 0f, 1.0f, 0.76f, 0.34f, 0.55f)
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
            // (породы у штольни — модели автора rock_moss_*, см. generatedRockPlacements)
            for (zStep in listOf(-18f, -10f, -2f, 6f, 14f, 22f)) {
                val gy = WorldPopulator.terrainHeight(loc, 0f, zStep)
                bark.addTaperedCylinder(-3.3f, gy, zStep, -3.1f, gy + 3.4f, zStep, 0.20f, 0.18f, 8)
                bark.addTaperedCylinder(3.3f, gy, zStep, 3.1f, gy + 3.4f, zStep, 0.20f, 0.18f, 8)
                timber.addBox(0f, gy + 3.32f, zStep, 6.9f, 0.36f, 0.42f)
                timber.addOrientedBox(-2.35f, gy + 2.85f, zStep, 1.4f, 0.20f, 0.22f, 0f, 0f, 0.68f)
                timber.addOrientedBox(2.35f, gy + 2.85f, zStep, 1.4f, 0.20f, 0.22f, 0f, 0f, -0.68f)
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

        // Живописные поваленные заснеженные стволы в мире (валуны — модели автора, см. generatedRockPlacements)
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
            }
        }

        // Ели по периметру района: живая стена с четырьмя проходами-коридорами к соседям
        val ringCount = maxOf(8, quality.perimeterTrees / 2)
        for (i in 0 until ringCount) {
            val ang = (i.toFloat() / ringCount.toFloat()) * Math.PI.toFloat() * 2f
            if (abs(sin(ang)) < 0.13f || abs(cos(ang)) < 0.13f) continue
            val radInner = 40.2f + (i % 3) * 1.6f
            val bx = sin(ang) * radInner
            val bz = -cos(ang) * radInner
            val by = WorldPopulator.terrainHeight(loc, bx, bz)
            addDetailedPineTree(bark, foliage, bx, by, bz, scale = 1.25f + (i % 4) * 0.16f, seed = i * 13)
        }
        val outerCount = (ringCount * 3) / 4
        for (i in 0 until outerCount) {
            val ang = (i.toFloat() / outerCount.toFloat()) * Math.PI.toFloat() * 2f + 0.11f
            if (abs(sin(ang)) < 0.13f || abs(cos(ang)) < 0.13f) continue
            val radOuter = 45.5f + (i % 2) * 1.8f
            val bx = sin(ang) * radOuter
            val bz = -cos(ang) * radOuter
            val by = WorldPopulator.terrainHeight(loc, bx, bz)
            addDetailedPineTree(bark, foliage, bx, by, bz, scale = 1.55f + (i % 3) * 0.18f, seed = 500 + i, drawShadow = false)
        }
    }

    /**
     * Декоративные камни мира: раньше рисовались процедурными «булыжниками»,
     * теперь это модели автора (rock_09 — светлый, rock_moss_* — тёмный).
     */
    private fun rebuildInteractiveNodesVbo(
        nodes: List<ResourceNode>,
        playerX: Float,
        playerZ: Float,
        quality: GraphicsQuality,
        droppedItems: List<com.ayaks.winterforest.domain.model.DroppedItem> = emptyList(),
        highlightDropId: String? = null
    ) {
        val builders = EnumMap<ProceduralTextureId, MeshBuilder>(ProceduralTextureId::class.java)
        for (mat in ProceduralTextureId.entries) {
            builders[mat] = MeshBuilder(12288)
        }
        // [x, y, z, yaw, scale, tintR, tintG, tintB] — камни-ресурсы моделями автора
        val nodeRockSpots = HashMap<String, MutableList<FloatArray>>()
        val terrainSteps = worldTerrainSteps(quality)
        if (nodeCacheQuality != quality) {
            nodeGeometryCache.clear()
            nodeCacheQuality = quality
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
            if (!node.isAvailable) {
                // Вскрытый ящик остаётся на земле открытым до конца перезарядки (90 с)
                if (node.kind == WorldObjectKind.SUPPLY_CRATE || node.kind == WorldObjectKind.MILITARY_CRATE) {
                    val ndx2 = node.x - playerX
                    val ndz2 = node.z - playerZ
                    if (ndx2 * ndx2 + ndz2 * ndz2 <= 118f * 118f) {
                        val y2 = MeshBuilder.sampleWorldHeight(node.x, node.z, terrainSteps)
                        nodeRockSpots.getOrPut("cache_crate_open") { mutableListOf() }
                            .add(floatArrayOf(node.x, y2, node.z, idx * 0.7f, 1.0f, 1f, 1f, 1f))
                    }
                }
                continue
            }
            // Дальние узлы не пекутся: VBO пересобирается при смене района игрока
            val ndx = node.x - playerX
            val ndz = node.z - playerZ
            if (ndx * ndx + ndz * ndz > 118f * 118f) continue
            val y = MeshBuilder.sampleWorldHeight(node.x, node.z, terrainSteps)
            // §7.17: геометрия узла печётся ОДИН раз на id узла и переиспользуется
            // при каждой пересборке VBO (сбор ресурса больше не перегоняет
            // процедурные ёлки/кусты заново — берём запечённые меши).
            val cachedEntry = nodeGeometryCache.getOrPut(node.id) {
                val tmp = EnumMap<ProceduralTextureId, MeshBuilder>(ProceduralTextureId::class.java)
                for (mat in ProceduralTextureId.entries) {
                    tmp[mat] = MeshBuilder(4096)
                }
                val tmpSpots = HashMap<String, MutableList<FloatArray>>()
                bakeNodeGeometry(tmp, node, idx, y, tmpSpots)
                val baked = EnumMap<ProceduralTextureId, GlMesh>(ProceduralTextureId::class.java)
                for (mat in ProceduralTextureId.entries) {
                    val m = tmp[mat]!!.build()
                    if (m.vertexCount > 0) baked[mat] = m
                }
                val spots = ArrayList<Pair<String, FloatArray>>()
                for ((modelId, list) in tmpSpots) {
                    for (spot in list) spots.add(modelId to spot)
                }
                NodeGeometry(baked, spots)
            }
            for ((mat, mesh) in cachedEntry.meshes) {
                builders[mat]?.appendMesh(mesh)
            }
            // Авторские модели-«пятна» (камни, ящики, пни) реестрится КАЖДУЮ
            // пересборку: nodeRockSpots живёт только на время одного вызова.
            for ((modelId, spot) in cachedEntry.spots) {
                nodeRockSpots.getOrPut(modelId) { mutableListOf() }.add(spot)
            }

        }

        // §7.12: выброшенные предметы лежат процедурными модельками на снегу
        for (item in droppedItems) {
            val dx = item.x - playerX
            val dz = item.z - playerZ
            if (dx * dx + dz * dz > 118f * 118f) continue
            val iy = MeshBuilder.sampleWorldHeight(item.x, item.z, terrainSteps)
            addDroppedItemProp(bark, foliage, stone, cloth, metal, fire, ice, item, iy)
            if (item.id == highlightDropId) {
                // янтарное кольцо-подсветка предмета под прицелом
                for (r in 0 until 8) {
                    val a = (r / 8f) * Math.PI.toFloat() * 2f
                    fire.addSculptedBoulder(
                        item.x + cos(a) * 0.34f, iy + 0.03f, item.z + sin(a) * 0.34f,
                        0.035f, 0.02f, 0.035f, 700 + r, 1f, 0.72f, 0.22f, false
                    )
                }
            }
        }

        for ((mat, builder) in builders) {
            nodeBatches[mat]?.uploadStatic(builder)
        }

        // Камни-ресурсы моделями автора: отдельные батчи по моделям (пересборка при изменении узлов)
        rockNodeBatches.values.forEach { it.release() }
        rockNodeBatches.clear()
        for ((modelId, spots) in nodeRockSpots) {
            val floats = assetMeshCache.getOrPut(modelId) {
                val ctx = appContext
                if (ctx != null) readAssetFloats(ctx, "models/$modelId/mesh.obj") else FloatArray(0)
            }
            if (floats.isEmpty()) continue
            val builder = MeshBuilder(spots.size * floats.size + 64)
            for (s in spots) {
                appendTransformedMesh(
                    builder, floats,
                    x = s[0], y = s[1], z = s[2],
                    yawRad = s[3], pitchRad = 0f, scale = s[4],
                    tintR = s[5], tintG = s[6], tintB = s[7]
                )
            }
            val vbo = GlVbo()
            vbo.uploadStatic(builder)
            rockNodeBatches[modelId] = vbo
        }
        assetMeshCache.clear()
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
            val baseY = WorldPopulator.worldHeight(0f, 0f)
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
            val chY = WorldPopulator.worldHeight(-6.8f, -5.8f) + 3.9f
            for (p in 0 until 5) {
                val rise = ((animTimeSec * 0.75f + p * 0.7f) % 3.5f)
                val rad = 0.16f + rise * 0.14f
                snow.addBox(-5.1f + rise * 0.28f, chY + rise, -4.6f - rise * 0.12f, rad, rad * 0.75f, rad, 0.82f, 0.85f, 0.90f, (0.42f * (1f - rise / 3.5f)).coerceAtLeast(0.05f))
            }
            // Каменная печь: живое пламя в топке и дым из трубы
            if (base.furnaceLevel >= 1) {
                val fx = 6.3f; val fz = -5.3f
                val fy = WorldPopulator.worldHeight(fx, fz)
                val flick2 = 0.86f + 0.20f * sin(animTimeSec * 9.2f)
                fire.addCrossedFlame(fx - 0.88f, fy + 0.62f, fz, 0.15f, 0.28f * flick2, 0f)
                for (p in 0 until 3) {
                    val rise = ((animTimeSec * 0.9f + p * 1.1f) % 3.2f)
                    val rad = 0.13f + rise * 0.12f
                    snow.addBox(
                        fx + 0.38f + rise * 0.12f, fy + 3.3f + rise, fz - 0.24f - rise * 0.08f,
                        rad, rad * 0.7f, rad, 0.80f, 0.83f, 0.88f,
                        (0.40f * (1f - rise / 3.2f)).coerceAtLeast(0.05f)
                    )
                }
            }
        }

        // 2. Полевые костры игрока + маяки несобранных уникальных находок
        for (fc in run.fieldCampfires) {
            val fy = MeshBuilder.sampleWorldHeight(fc.x, fc.z, worldTerrainSteps(quality))
            stone.addSculptedBoulder(fc.x + 0.35f, fy, fc.z, 0.18f, 0.14f, 0.16f, 17)
            stone.addSculptedBoulder(fc.x - 0.35f, fy, fc.z + 0.12f, 0.18f, 0.14f, 0.16f, 23)
            bark.addTaperedCylinder(fc.x - 0.35f, fy + 0.04f, fc.z - 0.2f, fc.x + 0.35f, fy + 0.22f, fc.z + 0.2f, 0.07f, 0.06f, 6)
            val flick = 0.85f + 0.22f * sin(animTimeSec * 11.2f)
            fire.addCrossedFlame(fc.x, fy + 0.12f, fc.z, 0.44f, 0.72f * flick, sin(animTimeSec * 7f) * 0.05f)
        }

        for (node in run.currentLocationNodes()) {
            if (node.isAvailable && node.kind == WorldObjectKind.SPECIAL_CACHE) {
                val ny = MeshBuilder.sampleWorldHeight(node.x, node.z, worldTerrainSteps(quality))
                val hover = ny + 1.05f + 0.16f * sin(animTimeSec * 3.1f)
                fire.addCrossedFlame(node.x, hover, node.z, 0.24f, 0.42f, 0f, 1f, 0.92f, 0.42f, 0.85f)
            }
        }

        // 3. Живая 3D-фауна (таёжные крысы, северные олени с рогами, волки) с отсечением по пирамиде обзора.
        //    Позиции/повороты интерполируются между 20-герцевыми тиками — движение плавное,
        //    а фаза шага привязана к пройденному пути (лапы перебираются ровно тогда, когда зверь идёт).
        val frameNowNs = System.nanoTime()
        importedBeasts.clear()
        for ((idx, beast) in run.currentWildlife().withIndex()) {
            if (!beast.isAlive) continue
            val bs = beastSmoothOf(beast.id)
            val alpha = if (bs != null && bs.initialized) {
                FramePacing.interpAlpha(frameNowNs - bs.startNs, TICK_INTERP_WINDOW_NS)
            } else {
                1f
            }
            val bx: Float
            val bz: Float
            val bh: Float
            val gait: Float
            val moving: Boolean
            if (bs != null && bs.initialized) {
                bx = FramePacing.lerpCoord(bs.prevX, bs.curX, alpha, SNAP_DISTANCE_M)
                bz = FramePacing.lerpCoord(bs.prevZ, bs.curZ, alpha, SNAP_DISTANCE_M)
                bh = FramePacing.lerpAngle(bs.prevH, bs.curH, alpha)
                gait = bs.prevGait + (bs.curGait - bs.prevGait) * alpha
                moving = bs.moving || alpha < 1f
            } else {
                bx = beast.x; bz = beast.z; bh = beast.headingRad
                gait = 0f; moving = false
            }
            val dx = bx - camX
            val dz = bz - camZ
            val distSq = dx * dx + dz * dz
            if (distSq > 56f * 56f) continue
            if (distSq > 9f) {
                val invD = 1f / sqrt(distSq)
                val dotFwd = (dx * invD) * fwdX + (dz * invD) * fwdZ
                if (dotFwd < -0.35f) continue // Frustum culling за спиной камеры!
            }
            val gy = MeshBuilder.sampleWorldHeight(bx, bz, worldTerrainSteps(quality))
            // Все виды фауны имеют собственные импортированные модели
            // (wolf_body / deer_body / rat_body): тело + 4 ноги с шагом (draw ниже).
            // Процедурная MeshBuilder.addWildlifeCreature здесь больше не вызывается —
            // раньше она рисовала «дублёров» зверей, у которых модель совпадала с
            // другим видом (заяц рисовался оленем), отсюда одинаковые олени на карте.
            val shadowR = when (beast.kind) {
                WildlifeKind.TIMBER_WOLF -> 1.05f
                WildlifeKind.TAIGA_RAT -> 0.42f
                WildlifeKind.NORTHERN_DEER -> 1.3f
            }
            fur.addShadowDisc(bx, gy, bz, shadowR, 0.34f)
            val slot = importedBeasts.size
            val draw = if (slot < beastPool.size) {
                beastPool[slot].also {
                    it.kind = beast.kind; it.x = bx; it.y = gy; it.z = bz
                    it.heading = bh; it.gait = gait; it.moving = moving
                }
            } else {
                BeastDraw(beast.kind, bx, gy, bz, bh, gait, moving).also { beastPool.add(it) }
            }
            importedBeasts.add(draw)
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

        // 5. Экипированный инструмент от первого лица: топор/кирка/копьё — ИМПОРТНЫЕ модели
        //    в руках игрока (не на земле!), факел — процедурный (модели факела нет).
        if (!menuOrbitMode) {
            val swingNorm = (run.swingAnimRemainingSec / run.equippedTool.swingDurationSec.coerceAtLeast(0.1f))
                .coerceIn(0f, 1f)
            // bob-фаза тоже интерполируется между тиками — рука не дёргается
            val fpAlpha = FramePacing.interpAlpha(frameNowNs - smoothStartNs, TICK_INTERP_WINDOW_NS)
            val fpBobPhase = FramePacing.lerpAngle(smoothPrevBobPhase, smoothCurBobPhase, fpAlpha)
            if (run.equippedTool == EquippedTool.TORCH) {
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
                    walkBobPhase = fpBobPhase,
                    timeSec = animTimeSec
                )
            } else {
                // Варежка на хвате (та же позиция/наклон, что у модели инструмента)
                val fwdXh = sin(yawRad) * cos(pitchRad)
                val fwdYh = sin(pitchRad)
                val fwdZh = -cos(yawRad) * cos(pitchRad)
                val rightXh = cos(yawRad)
                val rightZh = sin(yawRad)
                val bobXh = sin(fpBobPhase * 0.5f) * 0.022f
                val bobYh = abs(cos(fpBobPhase * 0.5f)) * 0.020f + sin(animTimeSec * 2.1f) * 0.006f
                val chopArcH = sin((1f - swingNorm) * Math.PI.toFloat())
                val gx = camX + fwdXh * (0.58f + chopArcH * 0.24f) + rightXh * (0.34f - chopArcH * 0.10f + bobXh)
                val gy = camY - 0.34f + fwdYh * 0.52f - chopArcH * 0.16f - bobYh
                val gz = camZ + fwdZh * (0.58f + chopArcH * 0.24f) + rightZh * (0.34f - chopArcH * 0.10f + bobXh)
                val thetaH = 0.28f + chopArcH * 0.85f
                cloth.addOrientedBox(
                    gx, gy, gz, 0.145f, 0.115f, 0.175f,
                    -yawRad, -thetaH, 0f, 0.58f, 0.45f, 0.34f
                )
                fur.addOrientedBox(
                    gx - fwdXh * 0.055f, gy - 0.055f, gz - fwdZh * 0.055f,
                    0.175f, 0.15f, 0.155f,
                    -yawRad, -thetaH, 0f, 0.42f, 0.34f, 0.28f
                )
            }
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

        // Импортированные 3D-модели животных (волк/олень) — тело + ноги с шагом
        for (bd in importedBeasts) {
            // Без else: когда в WildlifeKind добавят новый вид, компилятор сам
            // потребует модель для него (прежний else молча рисовал оленя).
            val modelId = when (bd.kind) {
                WildlifeKind.TIMBER_WOLF -> "wolf_body"
                WildlifeKind.TAIGA_RAT -> "rat_body"
                WildlifeKind.NORTHERN_DEER -> "deer_body"
            }
            drawAnimatedEntity(
                modelId = modelId,
                x = bd.x, y = bd.y, z = bd.z,
                yawRad = -bd.heading + Math.PI.toFloat() * 0.5f,
                gaitPhase = bd.gait,
                swingAmp = if (bd.moving) 0.30f else 0f
            )
        }

        // Модель топора/кирки/копья в руках — рисуется поверх динамики (ближе всего к камере).
        // §7.11: с пустыми руками (NONE) и с факелом модель инструмента не рисуется.
        val handToolModelId: String? = when (run.equippedTool) {
            EquippedTool.AXE -> "wooden_axe"
            EquippedTool.PICKAXE -> "pickaxe"
            EquippedTool.SPEAR -> "spear"
            EquippedTool.NONE, EquippedTool.TORCH -> null
        }
        if (!menuOrbitMode && handToolModelId != null) {
            val swingNorm = (run.swingAnimRemainingSec / run.equippedTool.swingDurationSec.coerceAtLeast(0.1f))
                .coerceIn(0f, 1f)
            val fpAlpha = FramePacing.interpAlpha(frameNowNs - smoothStartNs, TICK_INTERP_WINDOW_NS)
            val fpBobPhase = FramePacing.lerpAngle(smoothPrevBobPhase, smoothCurBobPhase, fpAlpha)
            drawFirstPersonToolModel(
                modelId = handToolModelId,
                camX = camX,
                camY = camY,
                camZ = camZ,
                yawRad = yawRad,
                pitchRad = pitchRad,
                swingProgress = 1f - swingNorm,
                walkBobPhase = fpBobPhase,
                timeSec = animTimeSec
            )
        }
    }

    /** Процедурная геометрия узла добычи (ели, кусты, пни, лунки) в свои батчи. */
    private fun bakeNodeGeometry(
        builders: EnumMap<ProceduralTextureId, MeshBuilder>,
        node: ResourceNode,
        idx: Int,
        y: Float,
        nodeRockSpots: HashMap<String, MutableList<FloatArray>>
    ) {
        val bark = builders[ProceduralTextureId.WOOD_BARK]!!
        val foliage = builders[ProceduralTextureId.PINE_FOLIAGE]!!
        val cloth = builders[ProceduralTextureId.CLOTH]!!
        val fire = builders[ProceduralTextureId.FIRE_EMBER]!!
        val ice = builders[ProceduralTextureId.ICE]!!
        val fur = builders[ProceduralTextureId.FUR_HIDE]!!
        val timber = builders[ProceduralTextureId.TIMBER]!!
        val metal = builders[ProceduralTextureId.METAL_STEEL]!!
        when (node.kind) {

                WorldObjectKind.PINE_TREE -> {
                    addDetailedPineTree(bark, foliage, node.x, y, node.z, scale = 0.94f + (idx % 4) * 0.11f, seed = 90 + idx)
                }
                WorldObjectKind.BERRY_BUSH -> {
                    foliage.addShadowDisc(node.x, y, node.z, 1.15f, 0.34f)
                    foliage.addSpruceBoughTier(node.x, y + 0.08f, node.z, 0.92f, 0.88f, tips = 7, twistRad = idx * 0.6f)
                    foliage.addSpruceBoughTier(node.x + 0.25f, y + 0.22f, node.z - 0.18f, 0.68f, 0.72f, tips = 6, twistRad = 1.1f)
                    foliage.addSpruceBoughTier(node.x - 0.12f, y + 0.38f, node.z + 0.10f, 0.46f, 0.50f, tips = 6, twistRad = 2.05f)
                    // Ягоды утоплены в крону: раньше шарики «висели» над кустом
                    for (b in 0 until 7) {
                        val ba = b * 0.9f + idx
                        cloth.addSculptedBoulder(
                            node.x + cos(ba) * 0.32f, y + 0.14f + (b % 3) * 0.11f, node.z + sin(ba) * 0.32f,
                            0.075f, 0.075f, 0.075f, 60 + b, r = 1.0f, g = 0.16f, b = 0.20f, snowCapTint = false
                        )
                    }
                }
                WorldObjectKind.STONE_OUTCROP -> {
                    foliage.addShadowDisc(node.x, y, node.z, 1.55f, 0.36f)
                    // Светлая порода автора (rock_09) — из неё падает Камень
                    nodeRockSpots.getOrPut("rock_09") { mutableListOf() }
                        .add(floatArrayOf(node.x, y, node.z, idx * 1.71f, 1.22f, 1f, 1f, 1f))
                }
                WorldObjectKind.SUPPLY_CRATE, WorldObjectKind.MILITARY_CRATE -> {
                    foliage.addShadowDisc(node.x, y, node.z, 0.72f, 0.34f)
                    nodeRockSpots.getOrPut("cache_crate") { mutableListOf() }
                        .add(floatArrayOf(node.x, y, node.z, idx * 0.7f, 1.0f, 1f, 1f, 1f))
                }
                WorldObjectKind.CLOTH_BUSH -> {
                    // Куст технической конопли автора (0.54 м) — источник Ткани
                    foliage.addShadowDisc(node.x, y, node.z, 0.42f, 0.32f)
                    nodeRockSpots.getOrPut("cloth_bush") { mutableListOf() }
                        .add(
                            floatArrayOf(
                                node.x, y, node.z,
                                idx * 1.37f,
                                0.92f + (idx % 4) * 0.09f,
                                1f, 1f, 1f
                            )
                        )
                }
                WorldObjectKind.FALLEN_BRANCH -> {
                    // сухие ветки автора: чередуем две модели, тонировка снегом
                    foliage.addShadowDisc(node.x, y, node.z, 0.58f, 0.30f)
                    val branchModel = if (idx % 2 == 0) "dry_branches_a" else "dry_branches_b"
                    nodeRockSpots.getOrPut(branchModel) { mutableListOf() }
                        .add(
                            floatArrayOf(
                                node.x, y, node.z,
                                idx * 1.13f,
                                0.86f + (idx % 3) * 0.12f,
                                0.96f, 0.97f, 1.0f
                            )
                        )
                }
                WorldObjectKind.PEBBLES -> {
                    // галька: тот же авторский валун, но мелкий и высветленный
                    nodeRockSpots.getOrPut("rock_09") { mutableListOf() }
                        .add(floatArrayOf(node.x, y, node.z, idx * 0.97f, 0.40f, 0.88f, 0.90f, 0.94f))
                }
                WorldObjectKind.RESIN_STUMP -> {
                    // §7.10: смоляной ствол — авторская модель tree_bark (не процедурный
                    // цилиндр): каждое видимое дерево в мире обязано быть узлом
                    foliage.addShadowDisc(node.x, y, node.z, 0.95f, 0.34f)
                    nodeRockSpots.getOrPut("tree_bark") { mutableListOf() }
                        .add(floatArrayOf(node.x, y, node.z, idx * 0.83f, 1.0f, 1f, 1f, 1f))
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
                    // Угольная жила — тёмная тонировка светлой породы
                    nodeRockSpots.getOrPut("rock_09") { mutableListOf() }
                        .add(floatArrayOf(node.x, y, node.z, idx * 1.37f, 1.18f, 0.30f, 0.30f, 0.34f))
                    nodeRockSpots.getOrPut("rock_moss_a") { mutableListOf() }
                        .add(floatArrayOf(node.x + 0.52f, y - 0.05f, node.z + 0.34f, idx * 2.1f, 0.55f, 0.26f, 0.26f, 0.30f))
                }
                WorldObjectKind.IRON_VEIN -> {
                    foliage.addShadowDisc(node.x, y, node.z, 1.45f, 0.38f)
                    // Тёмная порода автора (rock_moss) — из неё падает Железная руда
                    nodeRockSpots.getOrPut("rock_moss_b") { mutableListOf() }
                        .add(floatArrayOf(node.x, y, node.z, idx * 1.13f, 1.12f, 1f, 1f, 1f))
                    nodeRockSpots.getOrPut("rock_moss_a") { mutableListOf() }
                        .add(floatArrayOf(node.x - 0.48f, y - 0.04f, node.z + 0.42f, idx * 2.31f, 0.62f, 1f, 1f, 1f))
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
                // Лиственные деревья и полевые кусты пака Trees & Bushes (модель пользователя)
                WorldObjectKind.OAK_TREE -> {
                    foliage.addShadowDisc(node.x, y, node.z, 1.55f, 0.36f)
                    val oakId = OAK_MODELS[idx % OAK_MODELS.size]
                    nodeRockSpots.getOrPut(oakId) { mutableListOf() }
                        .add(floatArrayOf(node.x, y, node.z, idx * 1.71f, 0.92f + (idx % 3) * 0.12f, 0.94f, 0.97f, 1f))
                }
                WorldObjectKind.SCRUB_BUSH -> {
                    foliage.addShadowDisc(node.x, y, node.z, 1.05f, 0.34f)
                    val bushId = SCRUB_MODELS[idx % SCRUB_MODELS.size]
                    nodeRockSpots.getOrPut(bushId) { mutableListOf() }
                        .add(floatArrayOf(node.x, y, node.z, idx * 2.13f, 0.94f + (idx % 3) * 0.10f, 0.96f, 0.97f, 1f))
                    // Ягоды утоплены в крону куста — признак добычи
                    for (b in 0 until 5) {
                        val ba = b * 1.1f + idx
                        cloth.addSculptedBoulder(
                            node.x + cos(ba) * 0.28f, y + 0.30f + (b % 3) * 0.10f, node.z + sin(ba) * 0.28f,
                            0.07f, 0.07f, 0.07f, 60 + b, r = 1.0f, g = 0.16f, b = 0.20f, snowCapTint = false
                        )
                    }
                }
            }
    }

    /**
     * §7.12: процедурная моделька выброшенного предмета (≥200 полигонов суммарно:
     * 2–4 примитива на предмет). Каждый предмет читается по силуэту: бревно,
     * валун, связка ягод, тюк ткани, слиток, тушка рыбы, свиток, термос и т.д.
     */
    private fun addDroppedItemProp(
        bark: MeshBuilder,
        foliage: MeshBuilder,
        stone: MeshBuilder,
        cloth: MeshBuilder,
        metal: MeshBuilder,
        fire: MeshBuilder,
        ice: MeshBuilder,
        item: com.ayaks.winterforest.domain.model.DroppedItem,
        y: Float
    ) {
        val x = item.x
        val z = item.z
        val yaw = item.yawRad
        bark.addShadowDisc(x, y, z, 0.34f, 0.30f)
        when (item.resourceType) {
            ResourceType.WOOD -> {
                bark.addTaperedCylinder(x - 0.18f, y + 0.07f, z, x + 0.18f, y + 0.07f, z, 0.07f, 0.06f, 10, 0.88f, 0.72f, 0.52f)
                bark.addTaperedCylinder(x - 0.12f, y + 0.17f, z + 0.06f, x + 0.14f, y + 0.17f, z - 0.04f, 0.055f, 0.05f, 10, 0.82f, 0.66f, 0.48f)
                foliage.addSculptedBoulder(x + 0.2f, y + 0.06f, z + 0.1f, 0.05f, 0.04f, 0.05f, 71, 0.35f, 0.5f, 0.35f, false)
            }
            ResourceType.STONE -> {
                stone.addSculptedBoulder(x, y + 0.11f, z, 0.16f, 0.13f, 0.15f, 72, 0.62f, 0.66f, 0.70f, false)
                stone.addSculptedBoulder(x + 0.14f, y + 0.06f, z - 0.1f, 0.08f, 0.06f, 0.08f, 73, 0.70f, 0.74f, 0.78f, false)
            }
            ResourceType.BERRIES -> {
                foliage.addSculptedBoulder(x, y + 0.05f, z, 0.14f, 0.05f, 0.12f, 74, 0.30f, 0.45f, 0.30f, false)
                for (b in 0 until 5) {
                    val a = b * 1.25f
                    cloth.addSculptedBoulder(x + cos(a) * 0.08f, y + 0.10f, z + sin(a) * 0.08f, 0.045f, 0.045f, 0.045f, 75 + b, 1.0f, 0.16f, 0.20f, false)
                }
            }
            ResourceType.CLOTH -> {
                cloth.addOrientedBox(x, y + 0.05f, z, 0.16f, 0.05f, 0.12f, yaw, 0f, 0f, 0.85f, 0.82f, 0.72f)
                cloth.addOrientedBox(x, y + 0.11f, z, 0.12f, 0.035f, 0.09f, yaw + 0.4f, 0f, 0f, 0.80f, 0.78f, 0.70f)
            }
            ResourceType.RESIN -> {
                fire.addSculptedBoulder(x, y + 0.09f, z, 0.11f, 0.10f, 0.10f, 76, 1f, 0.72f, 0.16f, false)
                fire.addSculptedBoulder(x + 0.09f, y + 0.04f, z + 0.06f, 0.05f, 0.04f, 0.05f, 77, 1f, 0.78f, 0.22f, false)
            }
            ResourceType.FISH -> {
                metal.addEllipsoid(x, y + 0.07f, z, 0.20f, 0.06f, 0.08f, yawRad = yaw, r = 0.72f, g = 0.78f, b = 0.84f, uvScale = 1.4f)
                metal.addSculptedBoulder(x + 0.18f, y + 0.08f, z, 0.05f, 0.07f, 0.02f, 78, 0.66f, 0.72f, 0.78f, false)
            }
            ResourceType.ICE -> {
                ice.addCone(x, y, z, 0.10f, 0.24f, 8)
                ice.addSculptedBoulder(x + 0.12f, y + 0.05f, z - 0.08f, 0.06f, 0.05f, 0.06f, 79, 0.85f, 0.92f, 0.97f, false)
            }
            ResourceType.COAL -> {
                stone.addSculptedBoulder(x, y + 0.09f, z, 0.13f, 0.10f, 0.12f, 80, 0.20f, 0.20f, 0.22f, false)
                stone.addSculptedBoulder(x - 0.11f, y + 0.05f, z + 0.08f, 0.07f, 0.05f, 0.07f, 81, 0.16f, 0.16f, 0.18f, false)
            }
            ResourceType.IRON, ResourceType.IRON_INGOT -> {
                if (item.resourceType == ResourceType.IRON) {
                    stone.addSculptedBoulder(x, y + 0.10f, z, 0.14f, 0.11f, 0.13f, 82, 0.72f, 0.46f, 0.32f, false)
                    stone.addSculptedBoulder(x + 0.10f, y + 0.05f, z + 0.09f, 0.07f, 0.06f, 0.07f, 83, 0.66f, 0.42f, 0.30f, false)
                } else {
                    metal.addOrientedBox(x, y + 0.05f, z, 0.13f, 0.05f, 0.08f, yaw, 0f, 0f, 0.80f, 0.82f, 0.86f)
                    metal.addOrientedBox(x + 0.04f, y + 0.11f, z - 0.03f, 0.11f, 0.04f, 0.07f, yaw + 0.3f, 0f, 0f, 0.86f, 0.88f, 0.92f)
                }
            }
            ResourceType.MEAT -> {
                cloth.addSculptedBoulder(x, y + 0.09f, z, 0.15f, 0.09f, 0.11f, 84, 0.78f, 0.22f, 0.24f, false)
                bark.addTaperedCylinder(x + 0.10f, y + 0.10f, z, x + 0.20f, y + 0.16f, z + 0.04f, 0.025f, 0.02f, 8, 0.92f, 0.90f, 0.86f)
            }
            ResourceType.HIDE -> {
                cloth.addOrientedBox(x, y + 0.03f, z, 0.18f, 0.025f, 0.14f, yaw, 0f, 0f, 0.62f, 0.48f, 0.36f)
                cloth.addOrientedBox(x - 0.05f, y + 0.06f, z + 0.04f, 0.12f, 0.02f, 0.09f, yaw + 0.5f, 0f, 0f, 0.68f, 0.54f, 0.42f)
            }
            null -> when (item.specialLootId) {
                SpecialLootId.OLD_MAP -> {
                    bark.addTaperedCylinder(x - 0.12f, y + 0.045f, z, x + 0.12f, y + 0.045f, z, 0.045f, 0.04f, 10, 0.86f, 0.80f, 0.66f)
                    cloth.addOrientedBox(x + 0.02f, y + 0.02f, z + 0.08f, 0.10f, 0.015f, 0.06f, yaw, 0f, 0f, 0.88f, 0.84f, 0.72f)
                }
                SpecialLootId.RESIN_TORCH -> {
                    bark.addTaperedCylinder(x, y + 0.02f, z, x + 0.10f, y + 0.30f, z - 0.04f, 0.030f, 0.024f, 8, 0.80f, 0.64f, 0.46f)
                    fire.addSculptedBoulder(x + 0.12f, y + 0.34f, z - 0.05f, 0.06f, 0.08f, 0.06f, 85, 1f, 0.66f, 0.18f, false)
                }
                SpecialLootId.THERMOS -> {
                    metal.addTaperedCylinder(x, y, z, x, y + 0.26f, z, 0.055f, 0.05f, 12, 0.72f, 0.76f, 0.80f)
                    metal.addTaperedCylinder(x, y + 0.26f, z, x, y + 0.31f, z, 0.045f, 0.04f, 12, 0.45f, 0.48f, 0.52f)
                }
                SpecialLootId.PICKAXE -> {
                    bark.addTaperedCylinder(x - 0.14f, y + 0.04f, z, x + 0.14f, y + 0.10f, z, 0.026f, 0.022f, 8, 0.84f, 0.68f, 0.50f)
                    metal.addOrientedBox(x, y + 0.13f, z, 0.16f, 0.03f, 0.035f, yaw, 0f, 0.3f, 0.62f, 0.66f, 0.70f)
                }
                SpecialLootId.WARM_COAT -> {
                    cloth.addSculptedBoulder(x, y + 0.10f, z, 0.16f, 0.11f, 0.13f, 86, 0.55f, 0.42f, 0.30f, false)
                    cloth.addOrientedBox(x, y + 0.03f, z, 0.14f, 0.03f, 0.11f, yaw, 0f, 0f, 0.48f, 0.36f, 0.26f)
                }
                SpecialLootId.SOLDIER_RATION -> {
                    cloth.addOrientedBox(x, y + 0.06f, z, 0.13f, 0.06f, 0.09f, yaw, 0f, 0f, 0.42f, 0.46f, 0.32f)
                    metal.addTaperedCylinder(x + 0.12f, y, z + 0.06f, x + 0.12f, y + 0.12f, z + 0.06f, 0.045f, 0.042f, 10, 0.70f, 0.74f, 0.78f)
                }
                SpecialLootId.HUNTING_KNIFE -> {
                    metal.addOrientedBox(x + 0.06f, y + 0.025f, z, 0.11f, 0.012f, 0.028f, yaw, 0f, 0f, 0.80f, 0.84f, 0.88f)
                    bark.addOrientedBox(x - 0.08f, y + 0.03f, z, 0.06f, 0.02f, 0.022f, yaw, 0f, 0f, 0.42f, 0.30f, 0.20f)
                }
                SpecialLootId.FIRST_AID_KIT -> {
                    cloth.addOrientedBox(x, y + 0.06f, z, 0.13f, 0.06f, 0.09f, yaw, 0f, 0f, 0.92f, 0.94f, 0.96f)
                    fire.addOrientedBox(x, y + 0.125f, z, 0.05f, 0.008f, 0.018f, yaw, 0f, 0f, 0.85f, 0.18f, 0.16f)
                    fire.addOrientedBox(x, y + 0.125f, z, 0.018f, 0.008f, 0.05f, yaw, 0f, 0f, 0.85f, 0.18f, 0.16f)
                }
                null -> Unit
            }
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
            floatArrayOf(1.95f, 2.20f, 1.50f),
            floatArrayOf(2.75f, 1.92f, 1.45f),
            floatArrayOf(3.55f, 1.58f, 1.36f),
            floatArrayOf(4.25f, 1.18f, 1.25f),
            floatArrayOf(4.85f, 0.72f, 1.15f)
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
        val hw = width * 0.5f; val hd = depth * 0.5f
        val wallT = 0.30f
        val doorCx = cx - 0.85f
        val doorHalf = 0.58f
        val doorTopY = baseY + 0.22f + 2.05f
        // Стены сруба сегментами: фасад (+Z) разорван реальным дверным проёмом,
        // внутрь можно войти (2D-коллизии WorldCollision повторяют этот контур).
        timber.addBox(cx, baseY + 0.22f + wallHeight * 0.5f, cz - hd + wallT * 0.5f, width, wallHeight, wallT)
        timber.addBox(cx - hw + wallT * 0.5f, baseY + 0.22f + wallHeight * 0.5f, cz, wallT, wallHeight, depth - wallT * 2f)
        timber.addBox(cx + hw - wallT * 0.5f, baseY + 0.22f + wallHeight * 0.5f, cz, wallT, wallHeight, depth - wallT * 2f)
        val leftW = (doorCx - doorHalf) - (cx - hw)
        val rightW = (cx + hw) - (doorCx + doorHalf)
        if (leftW > 0.05f) {
            timber.addBox(cx - hw + leftW * 0.5f, baseY + 0.22f + wallHeight * 0.5f, cz + hd - wallT * 0.5f, leftW, wallHeight, wallT)
        }
        if (rightW > 0.05f) {
            timber.addBox(cx + hw - rightW * 0.5f, baseY + 0.22f + wallHeight * 0.5f, cz + hd - wallT * 0.5f, rightW, wallHeight, wallT)
        }
        // Притолока над дверью
        val lintelH = (baseY + 0.22f + wallHeight) - doorTopY
        if (lintelH > 0.05f) {
            timber.addBox(doorCx, doorTopY + lintelH * 0.5f, cz + hd - wallT * 0.5f, doorHalf * 2f + 0.1f, lintelH, wallT)
        }
        // Пол внутри сруба
        timber.addBox(cx, baseY + 0.26f, cz, width - wallT * 1.6f, 0.10f, depth - wallT * 1.6f)
        // Выступающие венцы брёвен («перерубы» на углах); фасадный ряд прерывается дверью
        val courses = 7
        val stepY = wallHeight / courses
        for (i in 0 until courses) {
            val ly = baseY + 0.30f + i * stepY
            val rLog = stepY * 0.48f
            val frontBreak = ly < doorTopY - 0.12f
            if (frontBreak) {
                val segL = (doorCx - doorHalf - 0.12f) - (cx - hw - 0.28f)
                val segR = (cx + hw + 0.28f) - (doorCx + doorHalf + 0.12f)
                if (segL > 0.1f) {
                    bark.addTaperedCylinder(cx - hw - 0.28f, ly, cz + hd, doorCx - doorHalf - 0.12f, ly, cz + hd, rLog, rLog, 7)
                }
                if (segR > 0.1f) {
                    bark.addTaperedCylinder(doorCx + doorHalf + 0.12f, ly, cz + hd, cx + hw + 0.28f, ly, cz + hd, rLog, rLog, 7)
                }
            } else {
                bark.addTaperedCylinder(cx - hw - 0.28f, ly, cz + hd, cx + hw + 0.28f, ly, cz + hd, rLog, rLog, 7)
            }
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
        // Дверь распахнута наружу (вход внутрь свободен), ступени крыльца
        val doorSwing = 1.30f
        val hingeX = doorCx - doorHalf
        timber.addOrientedBox(
            hingeX + kotlin.math.cos(doorSwing) * doorHalf,
            baseY + 0.24f + 1.02f,
            cz + hd + 0.06f + kotlin.math.sin(doorSwing) * doorHalf,
            doorHalf * 2f, 1.78f, 0.09f,
            doorSwing, 0f, 0f,
            0.72f, 0.52f, 0.34f
        )
        metal.addBox(hingeX + 0.08f, baseY + 1.05f, cz + hd + 0.12f, 0.09f, 0.09f, 0.06f)
        timber.addBox(doorCx, baseY, cz + hd + 0.48f, 1.45f, 0.24f, 0.78f)
        // Тёплое янтарное свечение: окна с переплётом + свет печи внутри сруба
        glow.addBox(cx + 0.95f, baseY + 1.05f, cz + hd + 0.06f, 0.88f, 0.72f, 0.08f, 1.0f, 0.80f, 0.36f, 1f)
        timber.addBox(cx + 0.95f, baseY + 1.05f, cz + hd + 0.11f, 0.07f, 0.76f, 0.05f)
        timber.addBox(cx + 0.95f, baseY + 1.38f, cz + hd + 0.11f, 0.92f, 0.07f, 0.05f)
        glow.addBox(cx + hw + 0.06f, baseY + 1.05f, cz, 0.08f, 0.70f, 0.86f, 1.0f, 0.78f, 0.34f, 1f)
        glow.addBox(cx + 0.55f, baseY + 1.15f, cz - hd + wallT + 0.06f, 1.15f, 0.85f, 0.08f, 1.0f, 0.74f, 0.34f, 1f)
    }

    /** Освобождает GPU-ресурсы неба (текстуры и quad) при пересоздании контекста. */
    private fun releaseSkyResources() {
        val doomed = skyHandlesScratch
        var n = 0
        if (skyTexHigh != 0) { doomed[n++] = skyTexHigh; skyTexHigh = 0 }
        if (skyTexLow != 0) { doomed[n++] = skyTexLow; skyTexLow = 0 }
        if (skyTexSun != 0) { doomed[n++] = skyTexSun; skyTexSun = 0 }
        if (skyTexMoon != 0) { doomed[n++] = skyTexMoon; skyTexMoon = 0 }
        if (n > 0) GLES20.glDeleteTextures(n, doomed, 0)
        if (skyQuadVbo != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(skyQuadVbo), 0)
            skyQuadVbo = 0
        }
    }

    /** Переиспользуемый массив для удаления текстур неба (без аллокации в кадре). */
    private val skyHandlesScratch = IntArray(4)

    private fun initSkyQuadVbo() {
        val quadCoords = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            -1f, 1f,
            1f, -1f,
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
        skyMoonDiscLoc = GLES20.glGetUniformLocation(skyProgram, "uMoonDisc")
        skyNightAmountLoc = GLES20.glGetUniformLocation(skyProgram, "uNightAmount")
        skyDiscColorLoc = GLES20.glGetUniformLocation(skyProgram, "uDiscColor")
        ensureSkyTextures()
    }

    private var skyTexHigh = 0
    private var skyTexLow = 0
    private var skyTexSun = 0
    private var skyTexMoon = 0
    private var skyCloudsHighLoc = -1
    private var skyCloudsLowLoc = -1
    private var skySunDiscLoc = -1
    private var skyMoonDiscLoc = -1
    private var skyNightAmountLoc = -1
    private var skyDiscColorLoc = -1
    /** Переиспользуемый буфер цвета диска светила (0 аллокаций в кадре). */
    private val discRgb = FloatArray(3)

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
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val wrap = if (wrapRepeat) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            outSize, outSize, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )
        // mipmaps — небо и облака не «плывут» и не мерцают вдали
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        return tex[0]
    }

    private fun ensureSkyTextures() {
        if (skyTexHigh == 0) skyTexHigh = uploadSkyTexture("sky/clouds_high.png", wrapRepeat = true)
        if (skyTexLow == 0) skyTexLow = uploadSkyTexture("sky/clouds_low.png", wrapRepeat = true)
        if (skyTexSun == 0) skyTexSun = uploadSkyTexture("sky/sun_disc.png", wrapRepeat = false)
        if (skyTexMoon == 0) skyTexMoon = uploadSkyTexture("sky/moon_disc.png", wrapRepeat = false)
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
        // Ночь/день для неба: 0 — полнодень, 1 — глубокая ночь. Плавная синусоида
        // без ступеней, поэтому светило и его цвет меняются непрерывно.
        val nightAmount = (0.5f - 0.5f * cos(cycleT * Math.PI.toFloat() * 2f))
        GLES20.glUniform1f(skyNightAmountLoc, nightAmount)
        // Цвет диска: днём тёплое солнце, ночью холодная луна (смесь по nightAmount).
        val moonR = 0.72f
        val moonG = 0.80f
        val moonB = 0.95f
        discRgb[0] = sunRgb[0] * (1f - nightAmount) + moonR * nightAmount
        discRgb[1] = sunRgb[1] * (1f - nightAmount) + moonG * nightAmount
        discRgb[2] = sunRgb[2] * (1f - nightAmount) + moonB * nightAmount
        GLES20.glUniform3fv(skyDiscColorLoc, 1, discRgb, 0)
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
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, skyTexMoon)
        GLES20.glUniform1i(skyMoonDiscLoc, 3)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

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

    /**
     * Ворота на тропах: рама стоит на земле, обе створки висят на петлях у
     * внешних краёв и распахиваются на 95°, когда игрок ближе 7.5 м.
     * Дальние ворота не рисуются (они в тумане и за деревьями) — 0 лишних треугольников.
     */
    private fun drawTrailGates(camX: Float, camZ: Float, quality: GraphicsQuality) {
        val frame = gateFrameVbo ?: return
        val left = gateDoorLVbo ?: return
        val right = gateDoorRVbo ?: return
        val tex = assetTextures["gate"] ?: return

        val nowNs = System.nanoTime()
        val dt = if (gateAnimPrevNs == 0L) 0f else ((nowNs - gateAnimPrevNs) / 1_000_000_000.0).toFloat()
        gateAnimPrevNs = nowNs
        val step = (dt.coerceIn(0f, 0.12f) * 2.6f)

        for ((idx, gate) in trailGates.withIndex()) {
            val dx = camX - gate.x
            val dz = camZ - gate.z
            val dist = sqrt(dx * dx + dz * dz)
            val target = if (dist < 7.5f) 1f else 0f
            val cur = gateOpenAmount[idx]
            gateOpenAmount[idx] = when {
                cur < target -> (cur + step).coerceAtMost(target)
                cur > target -> (cur - step).coerceAtLeast(target)
                else -> cur
            }
            if (dist > 46f) continue

            val gy = MeshBuilder.sampleWorldHeight(gate.x, gate.z, worldTerrainSteps(quality))
            drawTrailGatePart(frame, tex, gate, gy, 0f, 0f)
            val openness = gateOpenAmount[idx]
            val swing = openness * 95f
            drawGateDoor(left, tex, gate, gy, -GATE_HINGE_X, -swing)
            drawGateDoor(right, tex, gate, gy, GATE_HINGE_X, swing)
        }

        Matrix.setIdentityM(modelMatrix, 0)
        System.arraycopy(vpMatrix, 0, mvpMatrix, 0, 16)
        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uModelLoc, 1, false, modelMatrix, 0)
    }

    /** Рама ворот (статичная часть) в точке тропы. */
    private fun drawTrailGatePart(vbo: GlVbo, tex: Int, gate: TrailGate, groundY: Float, hingeX: Float, angleDeg: Float) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, gate.x, groundY, gate.z)
        Matrix.rotateM(modelMatrix, 0, gate.yawRad * 180f / Math.PI.toFloat(), 0f, 1f, 0f)
        Matrix.scaleM(modelMatrix, 0, GATE_SCALE, GATE_SCALE, GATE_SCALE)
        if (hingeX != 0f || angleDeg != 0f) {
            Matrix.translateM(modelMatrix, 0, hingeX, 0f, 0f)
            Matrix.rotateM(modelMatrix, 0, angleDeg, 0f, 1f, 0f)
            Matrix.translateM(modelMatrix, 0, -hingeX, 0f, 0f)
        }
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uModelLoc, 1, false, modelMatrix, 0)
        drawVbo(vbo, tex, unlit = false, specStrength = 0.30f)
    }

    /** Створка на петле: поворот вокруг вертикальной оси у внешнего края. */
    private fun drawGateDoor(vbo: GlVbo, tex: Int, gate: TrailGate, groundY: Float, hingeX: Float, angleDeg: Float) {
        drawTrailGatePart(vbo, tex, gate, groundY, hingeX, angleDeg)
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

    /** Модель зверя: тело + 4 ноги-сегмента с маятниковым шагом (перебирают ногами). */
    // §7.17: переиспользуемые записи отрисовки зверей — без аллокаций в кадре
    private class BeastDraw(
        var kind: WildlifeKind,
        var x: Float,
        var y: Float,
        var z: Float,
        var heading: Float,
        var gait: Float,
        var moving: Boolean
    )

    private val beastPool = ArrayList<BeastDraw>(8)

    /** §7.17: запечённая геометрия узлов по id (переживает пересборки VBO). */
    private val nodeGeometryCache = HashMap<String, NodeGeometry>()
    private var nodeCacheQuality: GraphicsQuality? = null

    private class NodeGeometry(
        val meshes: EnumMap<ProceduralTextureId, GlMesh>,
        val spots: List<Pair<String, FloatArray>>
    )
    private val importedBeasts = ArrayList<BeastDraw>(8)

    private class LegPart(val vbo: GlVbo, val pivotX: Float, val pivotY: Float, val pivotZ: Float)

    private class EntityParts(val body: GlVbo, val legs: List<LegPart>)

    private val entityParts = HashMap<String, EntityParts>()

    private fun buildEntityParts(modelId: String) {
        val ctx = appContext ?: return
        val importedBody = readAssetFloats(ctx, "models/$modelId/mesh_body.obj")
        val bodyFloats = if (importedBody.isNotEmpty()) {
            importedBody
        } else {
            // Цельная модель без разбивки на ноги (например, крыса автора)
            readAssetFloats(ctx, "models/$modelId/mesh.obj")
        }
        if (bodyFloats.isEmpty()) return
        val bodyVbo = GlVbo()
        bodyVbo.uploadStatic(floatsToBuilder(bodyFloats))
        // Ноги берутся ТОЛЬКО из ассета: mesh_leg0…mesh_leg3 у оленя и волка
        // заполнены настоящей геометрией (олень раньше был половиной туши, и дальние ноги
        // дорисовывались зеркальной копией — теперь источник зеркалится в
        // tools/pack_animals_cabin.py, поэтому рантайм-костыль не нужен).
        // Модель без разбивки (крыса) остаётся цельной и шагает телом.
        val legs = ArrayList<LegPart>(4)
        for (k in 0..3) {
            val floats = readAssetFloats(ctx, "models/$modelId/mesh_leg$k.obj")
            if (floats.isEmpty()) continue
            legs.add(buildLegPart(floats))
        }
        // диагональные пары шага: сортируем «вперёд-назад, слева-направо»
        val sorted = legs.sortedWith(compareByDescending<LegPart> { it.pivotZ }.thenBy { it.pivotX })
        entityParts[modelId] = EntityParts(bodyVbo, sorted)
    }

    /**
     * Нога зверя: меш + «шар» тазобедренного/коленного сустава на верхнем срезе.
     * Шар вращается вместе с ногой и закрывает стык с телом при любом размахе.
     * Радиус — от центроида среза ноги (не от оси мира!), шар компактный и
     * утоплен в стык; UV каждого шипа шара берётся у ближайшей вершины среза —
     * шар продолжает текстуру ноги и не «выпирает» пятном.
     */
    private fun buildLegPart(floats: FloatArray): LegPart {
        var maxY = Float.NEGATIVE_INFINITY
        var i = 1
        while (i < floats.size) {
            if (floats[i] > maxY) maxY = floats[i]
            i += 12
        }
        val bandY = maxY - (maxY * 0.28f).coerceAtLeast(0.05f)
        // 1-й проход: центроид верхнего среза
        var sx = 0f
        var sz = 0f
        var n = 0
        i = 0
        while (i + 11 < floats.size) {
            if (floats[i + 1] > bandY) {
                sx += floats[i]; sz += floats[i + 2]
                n++
            }
            i += 12
        }
        if (n == 0) {
            val vbo0 = GlVbo()
            vbo0.uploadStatic(floatsToBuilder(floats))
            return LegPart(vbo0, 0f, maxY * 0.92f, 0f)
        }
        val px = sx / n
        val pz = sz / n
        // 2-й проход: радиус поперечного среза ОТ ЦЕНТРОИДА + опорные вершины среза
        var rad = 0.03f
        val topX = FloatArray(n)
        val topY = FloatArray(n)
        val topZ = FloatArray(n)
        val topU = FloatArray(n)
        val topV = FloatArray(n)
        var ti = 0
        i = 0
        while (i + 11 < floats.size) {
            if (floats[i + 1] > bandY) {
                val dx = floats[i] - px
                val dz = floats[i + 2] - pz
                val rr = sqrt(dx * dx + dz * dz)
                if (rr > rad) rad = rr
                topX[ti] = floats[i]; topY[ti] = floats[i + 1]; topZ[ti] = floats[i + 2]
                topU[ti] = floats[i + 6]; topV[ti] = floats[i + 7]
                ti++
            }
            i += 12
        }
        val pivotY = maxY * 0.92f
        // Компактный шар строго в стыке: чуть больше среза, но не выпирает из силуэта
        val ballR = (rad * 1.15f).coerceIn(0.035f, 0.10f)
        val builder = floatsToBuilder(floats)
        appendJointBall(builder, px, pivotY - ballR * 0.2f, pz, ballR, topX, topY, topZ, topU, topV, ti)
        val vbo = GlVbo()
        vbo.uploadStatic(builder)
        return LegPart(vbo, px, pivotY, pz)
    }

    /** UV-шар сустава: каждый шип получает UV ближайшей вершины верхнего среза ноги. */
    private fun appendJointBall(
        builder: MeshBuilder,
        cx: Float,
        cy: Float,
        cz: Float,
        r: Float,
        topX: FloatArray,
        topY: FloatArray,
        topZ: FloatArray,
        topU: FloatArray,
        topV: FloatArray,
        topN: Int
    ) {
        val rings = 5
        val segs = 8
        for (ring in 0 until rings) {
            val phi0 = Math.PI.toFloat() * (ring.toFloat() / rings)
            val phi1 = Math.PI.toFloat() * ((ring + 1).toFloat() / rings)
            for (seg in 0 until segs) {
                val th0 = Math.PI.toFloat() * 2f * (seg.toFloat() / segs)
                val th1 = Math.PI.toFloat() * 2f * ((seg + 1).toFloat() / segs)
                fun pt(phi: Float, th: Float): FloatArray {
                    val sy = kotlin.math.cos(phi)
                    val sr = kotlin.math.sin(phi)
                    return floatArrayOf(
                        cx + r * sr * kotlin.math.cos(th),
                        cy + r * sy,
                        cz + r * sr * kotlin.math.sin(th)
                    )
                }
                val a = pt(phi0, th0)
                val b = pt(phi0, th1)
                val c = pt(phi1, th1)
                val d = pt(phi1, th0)
                fun emit(p: FloatArray) {
                    val nx = (p[0] - cx) / r
                    val ny = (p[1] - cy) / r
                    val nz = (p[2] - cz) / r
                    // UV — ближайшая вершина среза ноги: шар без шва продолжает мех
                    var best = 0
                    var bestD = Float.MAX_VALUE
                    for (k in 0 until topN) {
                        val ddx = topX[k] - p[0]
                        val ddy = topY[k] - p[1]
                        val ddz = topZ[k] - p[2]
                        val dd = ddx * ddx + ddy * ddy + ddz * ddz
                        if (dd < bestD) {
                            bestD = dd
                            best = k
                        }
                    }
                    builder.addVertex(p[0], p[1], p[2], nx, ny, nz, topU[best], topV[best], 1f, 1f, 1f, 1f)
                }
                emit(a); emit(b); emit(c)
                emit(a); emit(c); emit(d)
            }
        }
    }

    /**
     * Модель инструмента в пространстве «кисти»: рукоять вдоль +Y, хват в начале
     * координат, масштаб и смещение хвата запечены в вершинах.
     * wooden_axe лежит вдоль Z в авторской сцене — перекладываем рукоятью вверх (x,y,z)→(−x,z,y).
     */
    private fun buildToolHandVbo(modelId: String) {
        val ctx = appContext ?: return
        val floats = readAssetFloats(ctx, "models/$modelId/mesh.obj")
        if (floats.isEmpty()) return
        val remapXZ = modelId == "wooden_axe"
        val gripShift = when (modelId) {
            "wooden_axe" -> 1.30f
            "pickaxe" -> -0.30f
            "spear" -> -0.55f
            else -> 0f
        }
        val scale = when (modelId) {
            "wooden_axe" -> 0.28f
            "pickaxe" -> 0.52f
            "spear" -> 0.52f
            else -> 1f
        }
        val builder = MeshBuilder(floats.size + 64)
        var i = 0
        while (i + 11 < floats.size) {
            val lx = floats[i]; val ly = floats[i + 1]; val lz = floats[i + 2]
            val nx = floats[i + 3]; val ny = floats[i + 4]; val nz = floats[i + 5]
            val u = floats[i + 6]; val v = floats[i + 7]
            val r = floats[i + 8]; val g = floats[i + 9]; val b = floats[i + 10]; val a = floats[i + 11]
            val px: Float; val py: Float; val pz: Float
            val pnx: Float; val pny: Float; val pnz: Float
            if (remapXZ) {
                px = -lx; py = lz; pz = ly
                pnx = -nx; pny = nz; pnz = ny
            } else {
                px = lx; py = ly; pz = lz
                pnx = nx; pny = ny; pnz = nz
            }
            builder.addVertex(
                px * scale,
                (py + gripShift) * scale,
                pz * scale,
                pnx, pny, pnz,
                u, v,
                r, g, b, a
            )
            i += 12
        }
        val vbo = GlVbo()
        vbo.uploadStatic(builder)
        toolHandVbos[modelId] = vbo
    }

    /** Модель топора/кирки/копья в руках игрока (первое лицо) с замахом и покачиванием шага. */
    private fun drawFirstPersonToolModel(
        modelId: String,
        camX: Float,
        camY: Float,
        camZ: Float,
        yawRad: Float,
        pitchRad: Float,
        swingProgress: Float,
        walkBobPhase: Float,
        timeSec: Float
    ) {
        val vbo = toolHandVbos[modelId] ?: return
        val tex = assetTextures[modelId] ?: return
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
        val theta = 0.26f + chopArc * 0.62f
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, baseX, baseY, baseZ)
        Matrix.rotateM(modelMatrix, 0, -yawRad * 180f / Math.PI.toFloat(), 0f, 1f, 0f)
        // Инструмент привязан к голове: наклон камеры (pitch) входит в поворот вокруг
        // локальной оси X вместе с замахом. Без pitch топор оставался горизонтальным,
        // когда игрок смотрел вверх/вниз, и «отлипал» от рук.
        Matrix.rotateM(modelMatrix, 0, (-pitchRad - theta) * 180f / Math.PI.toFloat(), 1f, 0f, 0f)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uModelLoc, 1, false, modelMatrix, 0)
        drawVbo(vbo, tex, unlit = false, specStrength = 0.26f)
    }

    private fun readAssetFloats(ctx: android.content.Context, path: String): FloatArray {
        val text = try {
            ctx.assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        return if (text.isEmpty()) FloatArray(0) else ObjParser.parseToExpandedFloats(text)
    }

    private fun floatsToBuilder(floats: FloatArray): MeshBuilder {
        val builder = MeshBuilder(floats.size + 64)
        var i = 0
        while (i + 11 < floats.size) {
            builder.addVertex(
                floats[i], floats[i + 1], floats[i + 2],
                floats[i + 3], floats[i + 4], floats[i + 5],
                floats[i + 6], floats[i + 7],
                floats[i + 8], floats[i + 9], floats[i + 10], floats[i + 11]
            )
            i += 12
        }
        return builder
    }

    /**
     * Печь импортированного меша в builder со сдвигом/поворотом/масштабом/тонировкой
     * (та же математика, что у статических пропов: pitch вокруг X, затем yaw вокруг Y).
     */
    private fun appendTransformedMesh(
        builder: MeshBuilder,
        floats: FloatArray,
        x: Float,
        y: Float,
        z: Float,
        yawRad: Float,
        pitchRad: Float,
        scale: Float,
        tintR: Float = 1f,
        tintG: Float = 1f,
        tintB: Float = 1f
    ) {
        val cos = kotlin.math.cos(yawRad)
        val sin = kotlin.math.sin(yawRad)
        val cp = kotlin.math.cos(pitchRad)
        val sp = kotlin.math.sin(pitchRad)
        var i = 0
        while (i + 11 < floats.size) {
            val lx = floats[i]; val ly = floats[i + 1]; val lz = floats[i + 2]
            val nx = floats[i + 3]; val ny = floats[i + 4]; val nz = floats[i + 5]
            val u = floats[i + 6]; val v = floats[i + 7]
            val r = floats[i + 8]; val g = floats[i + 9]; val b = floats[i + 10]; val a = floats[i + 11]
            val px1 = lx
            val py1 = ly * cp - lz * sp
            val pz1 = ly * sp + lz * cp
            val nx1 = nx
            val ny1 = ny * cp - nz * sp
            val nz1 = ny * sp + nz * cp
            builder.addVertex(
                x + (px1 * cos + pz1 * sin) * scale,
                y + py1 * scale,
                z + (-px1 * sin + pz1 * cos) * scale,
                nx1 * cos + nz1 * sin,
                ny1,
                -nx1 * sin + nz1 * cos,
                u, v,
                r * tintR, g * tintG, b * tintB, a
            )
            i += 12
        }
    }

    /** Отрисовка зверя: тело стоит на месте, ноги качаются вокруг тазобедренных пиотов. */
    private fun drawAnimatedEntity(
        modelId: String,
        x: Float,
        y: Float,
        z: Float,
        yawRad: Float,
        gaitPhase: Float,
        swingAmp: Float
    ) {
        val parts = entityParts[modelId] ?: return
        val tex = assetTextures[modelId] ?: return
        val yawDeg = yawRad * 180f / Math.PI.toFloat()
        // тело
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, z)
        Matrix.rotateM(modelMatrix, 0, yawDeg, 0f, 1f, 0f)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uModelLoc, 1, false, modelMatrix, 0)
        drawVbo(parts.body, tex, unlit = false, specStrength = 0.22f)
        // ноги: маятник вокруг пиота, диагональные пары в противофазе
        for ((idx, leg) in parts.legs.withIndex()) {
            val phase = gaitPhase + (if (idx == 1 || idx == 2) Math.PI.toFloat() else 0f)
            val swing = if (swingAmp > 0.001f) sin(phase) * swingAmp else 0f
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, x, y, z)
            Matrix.rotateM(modelMatrix, 0, yawDeg, 0f, 1f, 0f)
            Matrix.translateM(modelMatrix, 0, leg.pivotX, leg.pivotY, leg.pivotZ)
            if (swing != 0f) Matrix.rotateM(modelMatrix, 0, swing * 180f / Math.PI.toFloat(), 1f, 0f, 0f)
            Matrix.translateM(modelMatrix, 0, -leg.pivotX, -leg.pivotY, -leg.pivotZ)
            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
            GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(uModelLoc, 1, false, modelMatrix, 0)
            drawVbo(leg.vbo, tex, unlit = false, specStrength = 0.22f)
        }
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
        /** Масштаб ворот на тропе: модель 2.55 м → почти 3 м в мире. */
        const val GATE_SCALE = 1.15f
        /** Петля створки в координатах модели (из tools/pack_gate_doors.py). */
        const val GATE_HINGE_X = 1.2483f

        /** Окно интерполяции позиции = период тика симуляции (20 Гц). */
        private const val TICK_INTERP_WINDOW_NS = 50_000_000L

        /** Лиственные деревья пака Trees & Bushes (модель пользователя). */
        private val OAK_MODELS = arrayOf("oak_a", "oak_b", "oak_c")

        /** Полевые кусты пака Trees & Bushes (модель пользователя). */
        private val SCRUB_MODELS = arrayOf("bush_green", "bush_dry", "bush_thicket")

        /** Скачок больше этой дистанции считается телепортом. */
        private const val SNAP_DISTANCE_M = 4.0f

        /** Радиан фазы шага лап на метр пути (видимый шаг ~0.7 м). */
        private const val GAIT_RAD_PER_METER = 9.0f

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
    onFirstFrameReady: (() -> Unit)? = null,
    val forestRenderer: ForestGlRenderer = ForestGlRenderer(onFpsSample, context.applicationContext, onFirstFrameReady)
) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        // Целевые 60 FPS: swap interval 1 (ожидание vsync) выставляется в
        // onSurfaceCreated, а поверхность просит фиксированные 60 Гц.
        setRenderer(forestRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        // Android 11+ (API 30): FIXED вместо DEFAULT — система не поднимает
        // частоту панели выше запрошенных 60 Гц ради этой поверхности.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // FRAME_RATE_COMPATIBILITY_FIXED == 0: контент с постоянной частотой
            // (игра), а не видео. Литерал вместо константы — константа отсутствует
            // в части сборок android.jar, а значение стабильно с API 30.
            holder.surface.setFrameRate(FramePacing.TARGET_SURFACE_FRAME_RATE, 0)
        }
    }
}
