package com.ayaks.winterforest.domain.world

import com.ayaks.winterforest.domain.model.LocationId
import com.ayaks.winterforest.domain.model.ResourceNode
import com.ayaks.winterforest.domain.model.WorldObjectKind
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Непроходимость игрового мира: стволы деревьев, валуны, ящики и стены срубов
 * со сквозными дверными проёмами. Используется и игроком, и зверями — никто
 * не «проходит сквозь текстуры».
 *
 * Всё чистая математика без Android — тестируется на JVM.
 */
object WorldCollision {

    /** Круглый коллайдер (дерево, куст, бочка, ящик). */
    data class Circle(val x: Float, val z: Float, val radius: Float)

    /** Прямоугольный коллайдер (стена/сруб), проём описывается разрывом между сегментами. */
    data class Rect(val minX: Float, val maxX: Float, val minZ: Float, val maxZ: Float)

    /**
     * Ориентированный по yaw прямоугольник в плане — стена повёрнутого строения.
     *
     * Импортные модели (зимовье охотника) стоят под углом 3.6 рад. Axis-aligned
     * [Rect] вокруг такой модели либо пропускает игрока сквозь настоящие брёвна,
     * либо строит до метра «невидимых стен» в углах. [Box] повторяет стену точно:
     * точка переводится в локальную систему строения, выталкивание считается там же.
     */
    data class Box(
        val cx: Float,
        val cz: Float,
        val halfX: Float,
        val halfZ: Float,
        val yaw: Float
    )

    /** Радиус тела игрока при проходе. */
    const val PLAYER_RADIUS = 0.42f

    /** Сруб игрока: центр, полуширина, полуглубина, проём по X, сторона проёма (+1 = +Z). */
    private val BASE_SHELTER = floatArrayOf(-6.8f, -5.8f, 2.3f, 2.1f, -0.85f, 1f)

    /**
     * Зимовье охотника — импортная модель `cabin_wood` (поворот 3.6 рад).
     * Обмер по сетке: сруб занимает локальные X [-1.55…+1.05], Z [-4.75…+0.30],
     * толщина бревна ~0.16 м, дверь — в восточной длинной стене при Z [-2.30…-1.45].
     * Прежние 2.9 × 3.8 м AABB были взяты «на глаз»: они шире модели на 1.6 м
     * по X и на 2.25 м короче по Z, отсюда невидимые стены у углов.
     */
    private const val CABIN_X = 7.2f
    private const val CABIN_Z = 8.8f
    private const val CABIN_YAW = 3.6f
    private const val CABIN_MIN_X = -1.55f
    private const val CABIN_MAX_X = 1.05f
    private const val CABIN_MIN_Z = -4.75f
    private const val CABIN_MAX_Z = 0.30f
    // Видимый проём в модели — 0.85 м, но игрок с радиусом 0.42 (диаметр 0.84)
    // проходил бы в него впритык: для коллизии створ расширен до 1.15 м.
    private val CABIN_DOOR = floatArrayOf(-2.45f, -1.30f) // Z-диапазон проёма

    /**
     * Полный набор препятствий локации: узлы ресурсов (когда доступны),
     * декоративные поваленные стволы с валунами и строения.
     */
    fun obstacles(loc: LocationId, nodes: List<ResourceNode>): Pair<List<Circle>, List<Rect>> {
        val circles = ArrayList<Circle>(nodes.size + 24)
        val rects = ArrayList<Rect>(8)
        // Узлы приходят в глобальных координатах единого мира — переводим в локальные
        val (ox, oz) = WorldRegions.centerOf(loc)

        for (node in nodes) {
            if (!node.isAvailable) continue
            val r = when (node.kind) {
                WorldObjectKind.PINE_TREE -> 0.55f
                WorldObjectKind.BERRY_BUSH -> 0.72f
                WorldObjectKind.STONE_OUTCROP -> 1.25f
                // Ящики рисуются моделью автора (cache_crate, 0.95×0.63 м):
                // радиус коллизии — по полуширине модели, иначе «невидимая стена» в 60 см
                WorldObjectKind.SUPPLY_CRATE -> 0.55f
                // Военный ящик раньше выпадал из when → игрок проходил сквозь него
                WorldObjectKind.MILITARY_CRATE -> 0.55f
                // Куст ткани (cloth_bush, 0.36×0.40 м) — непроходимый, но узкий
                WorldObjectKind.CLOTH_BUSH -> 0.30f
                WorldObjectKind.RESIN_STUMP -> 0.55f
                WorldObjectKind.COAL_SEAM -> 1.0f
                WorldObjectKind.IRON_VEIN -> 1.0f
                WorldObjectKind.SPECIAL_CACHE -> 0.48f
                // Лиственное дерево и полевой куст (модель пользователя) — непроходимые
                WorldObjectKind.OAK_TREE -> 0.70f
                WorldObjectKind.SCRUB_BUSH -> 0.62f
                // валежник и галька низкие: почти не мешают ходьбе
                WorldObjectKind.FALLEN_BRANCH -> 0.34f
                WorldObjectKind.PEBBLES -> 0.28f
                // ICE_HOLE — лунка для рыбалки, GAME_CARCASS — туша: не мешают
                else -> continue
            }
            circles.add(Circle(node.x - ox, node.z - oz, r))
        }

        // Поваленные стволы + валуны (та же формула, что у декора в рендере)
        for (i in 0 until 7) {
            val a = i * 0.91f + 0.4f
            val rDist = 13.5f + (i % 3) * 7.2f
            if (loc == LocationId.FROZEN_LAKE && rDist <= 24.5f) continue
            val lx = kotlin.math.cos(a) * rDist
            val lz = kotlin.math.sin(a) * rDist
            circles.add(Circle(lx, lz, 1.05f))
            circles.add(Circle(lx + 1.3f, lz - 0.8f, 0.68f))
        }

        // Архитектура локаций
        when (loc) {
            LocationId.BASE_CLEARING -> {
                addCabin(rects, BASE_SHELTER)
                circles.add(Circle(4.8f, -4.5f, 1.32f)) // верстак
                circles.add(Circle(-7.65f, -5.25f, 0.72f)) // поленницы
                circles.add(Circle(-7.25f, -5.75f, 0.72f))
                circles.add(Circle(-5.85f, -6.7f, 0.48f)) // бочка
                circles.add(Circle(4.2f, -3.6f, 0.62f)) // тайник-ящик
                circles.add(Circle(-12.6f, 10.6f, 0.55f)) // сухие деревья
                circles.add(Circle(13.2f, -8.4f, 0.55f))
                // деревья и кусты пака Trees & Bushes — непроходимые
                circles.add(Circle(-11.5f, -9.5f, 0.70f))
                circles.add(Circle(10.8f, 6.2f, 0.65f))
                circles.add(Circle(7.9f, 3.1f, 0.62f))
                circles.add(Circle(-4.2f, 8.9f, 0.62f))
                circles.add(Circle(12.4f, -3.2f, 0.58f))
            }
            LocationId.HUNTERS_CABIN -> {
                // Стены зимовья — ориентированные [Box] (см. cabinWalls), здесь только пропсы
                circles.add(Circle(6.05f, 10.25f, 0.45f)) // кирка у стены
                circles.add(Circle(8.45f, 5.45f, 0.5f)) // ящик
                circles.add(Circle(9.0f, 9.5f, 0.62f)) // тайник
                circles.add(Circle(14.6f, 5.9f, 0.6f)) // зимнее дерево (тории)
                // деревья и кусты пака Trees & Bushes — непроходимые
                circles.add(Circle(1.2f, 12.8f, 0.70f))
                circles.add(Circle(12.3f, 2.4f, 0.60f))
                circles.add(Circle(5.1f, 4.15f, 0.62f))
                circles.add(Circle(-2.8f, 9.6f, 0.62f))
            }
            LocationId.DENSE_SPRUCE -> {
                circles.add(Circle(-12.9f, 8.1f, 0.6f))
                circles.add(Circle(11.7f, -14.3f, 0.6f))
                circles.add(Circle(-9.5f, -11.0f, 0.62f))
                circles.add(Circle(-8.15f, -9.65f, 0.5f))
                // деревья и кусты пака Trees & Bushes — непроходимые
                circles.add(Circle(-6.7f, 2.9f, 0.70f))
                circles.add(Circle(8.6f, -6.4f, 0.70f))
                circles.add(Circle(2.2f, -3.6f, 0.65f))
                circles.add(Circle(-11.2f, -6.8f, 0.58f))
            }
            LocationId.FROZEN_LAKE -> {
                circles.add(Circle(10.5f, -8.5f, 0.62f))
                circles.add(Circle(-7.65f, -5.25f, 0.72f))
                // дерево и кусты пака Trees & Bushes — непроходимые
                circles.add(Circle(-16.5f, -2.2f, 0.65f))
                circles.add(Circle(-13.8f, 5.5f, 0.62f))
                circles.add(Circle(14.9f, 6.8f, 0.58f))
            }
            LocationId.ABANDONED_MINE -> {
                // Каменные крепи штольни + глыбы
                for (zStep in listOf(-18f, -10f, -2f, 6f, 14f, 22f)) {
                    circles.add(Circle(-3.2f, zStep, 0.34f))
                    circles.add(Circle(3.2f, zStep, 0.34f))
                    circles.add(Circle(-4.6f, zStep + 2.2f, 1.35f))
                    circles.add(Circle(4.6f, zStep - 1.8f, 1.35f))
                }
                circles.add(Circle(-8.0f, 10.0f, 0.62f))
                circles.add(Circle(-9.45f, 12.05f, 0.5f))
                // сухой куст пака Trees & Bushes — непроходимый
                circles.add(Circle(-2.4f, -8.2f, 0.62f))
            }
        }
        return circles to rects
    }

    /** Стены сруба четырьмя сегментами: фасад разорван дверным проёмом (сторона side). */
    private fun addCabin(rects: MutableList<Rect>, def: FloatArray) {
        val cx = def[0]
        val cz = def[1]
        val hw = def[2]
        val hd = def[3]
        val doorCx = cx + def[4]
        val side = def[5]
        val doorHalf = 0.62f
        val wall = 0.26f
        val facadeZ = cz + side * hd
        // фасад слева и справа от проёма
        rects.add(Rect(cx - hw, doorCx - doorHalf, facadeZ - wall, facadeZ + wall))
        rects.add(Rect(doorCx + doorHalf, cx + hw, facadeZ - wall, facadeZ + wall))
        // противоположная стена и боковины
        val backZ = cz - side * hd
        rects.add(Rect(cx - hw, cx + hw, backZ - wall, backZ + wall))
        rects.add(Rect(cx - hw - wall, cx - hw + wall, cz - hd, cz + hd))
        rects.add(Rect(cx + hw - wall, cx + hw + wall, cz - hd, cz + hd))
    }

    /**
     * Выталкивание круга (игрок/зверь) из препятствий: касание по касательной
     * даёт «скольжение» вдоль стены, застревание невозможно.
     */
    fun resolve(
        x: Float,
        z: Float,
        radius: Float,
        circles: List<Circle>,
        rects: List<Rect>,
        boxes: List<Box> = emptyList()
    ): FloatArray {
        var px = x
        var pz = z
        for (pass in 0..1) {
            for (c in circles) {
                val dx = px - c.x
                val dz = pz - c.z
                val rr = c.radius + radius
                val d2 = dx * dx + dz * dz
                if (d2 < rr * rr && d2 > 1e-8f) {
                    val d = sqrt(d2)
                    px = c.x + dx / d * rr
                    pz = c.z + dz / d * rr
                }
            }
            for (r in rects) {
                val qx = px.coerceIn(r.minX, r.maxX)
                val qz = pz.coerceIn(r.minZ, r.maxZ)
                val dx = px - qx
                val dz = pz - qz
                val d2 = dx * dx + dz * dz
                if (d2 >= radius * radius) continue
                if (d2 > 1e-8f) {
                    val d = sqrt(d2)
                    px = qx + dx / d * radius
                    pz = qz + dz / d * radius
                } else {
                    // центр прямо в стене — выталкиваем к ближайшему краю
                    val pushL = px - r.minX
                    val pushR = r.maxX - px
                    val pushD = pz - r.minZ
                    val pushU = r.maxZ - pz
                    val m = min(min(pushL, pushR), min(pushD, pushU))
                    when (m) {
                        pushL -> px = r.minX - radius
                        pushR -> px = r.maxX + radius
                        pushD -> pz = r.minZ - radius
                        else -> pz = r.maxZ + radius
                    }
                }
            }
            for (b in boxes) {
                val out = resolveBox(b, px, pz, radius)
                px = out[0]
                pz = out[1]
            }
        }
        return floatArrayOf(px, pz)
    }

    /** Выталкивание круга из ориентированного прямоугольника (стена зимовья). */
    private fun resolveBox(b: Box, x: Float, z: Float, radius: Float): FloatArray {
        val c = kotlin.math.cos(b.yaw)
        val s = kotlin.math.sin(b.yaw)
        val dx = x - b.cx
        val dz = z - b.cz
        // обратное к world = center + (lx*cos + lz*sin, -lx*sin + lz*cos)
        val lx = dx * c - dz * s
        val lz = dx * s + dz * c
        val qx = lx.coerceIn(-b.halfX, b.halfX)
        val qz = lz.coerceIn(-b.halfZ, b.halfZ)
        val ex = lx - qx
        val ez = lz - qz
        val d2 = ex * ex + ez * ez
        if (d2 >= radius * radius) return floatArrayOf(x, z)
        val nx: Float
        val nz: Float
        if (d2 > 1e-8f) {
            val d = sqrt(d2)
            nx = qx + ex / d * radius
            nz = qz + ez / d * radius
        } else {
            // центр внутри стены — выталкиваем к ближайшей грани
            val pushL = lx + b.halfX
            val pushR = b.halfX - lx
            val pushD = lz + b.halfZ
            val pushU = b.halfZ - lz
            val m = min(min(pushL, pushR), min(pushD, pushU))
            when (m) {
                pushL -> { nx = -b.halfX - radius; nz = lz }
                pushR -> { nx = b.halfX + radius; nz = lz }
                pushD -> { nx = lx; nz = -b.halfZ - radius }
                else -> { nx = lx; nz = b.halfZ + radius }
            }
        }
        return floatArrayOf(b.cx + nx * c + nz * s, b.cz - nx * s + nz * c)
    }

    /**
     * Стены зимовья охотника в мировых координатах локации: четыре сегмента сруба,
     * восточная длинная стена разорвана дверным проёмом (Z [-2.30…-1.45] локально).
     * Локальные размеры сняты с самой модели `cabin_wood`, поэтому невидимых стен нет.
     */
    fun cabinWalls(): List<Box> {
        val halfW = (CABIN_MAX_X - CABIN_MIN_X) * 0.5f      // 1.30
        val midX = (CABIN_MAX_X + CABIN_MIN_X) * 0.5f       // -0.25
        val wall = 0.10f
        val westX = CABIN_MIN_X + wall                       // -1.45
        val eastX = CABIN_MAX_X - wall                       // +0.95
        fun box(lx: Float, lz: Float, hx: Float, hz: Float) = Box(CABIN_X + lx, CABIN_Z + lz, hx, hz, CABIN_YAW)
        val doorZ0 = CABIN_DOOR[0]
        val doorZ1 = CABIN_DOOR[1]
        return listOf(
            // западная длинная стена — сплошная
            box(westX, (CABIN_MIN_Z + CABIN_MAX_Z) * 0.5f, wall, (CABIN_MAX_Z - CABIN_MIN_Z) * 0.5f),
            // восточная длинная стена: до проёма и после него
            box(eastX, (CABIN_MIN_Z + doorZ0) * 0.5f, wall, (doorZ0 - CABIN_MIN_Z) * 0.5f),
            box(eastX, (doorZ1 + CABIN_MAX_Z) * 0.5f, wall, (CABIN_MAX_Z - doorZ1) * 0.5f),
            // торцы
            box(midX, CABIN_MIN_Z + wall, halfW, wall),
            box(midX, CABIN_MAX_Z - wall, halfW, wall),
            // каменный очаг (cabin_stone) выступает в комнату на 0.3 м у западной стены
            box(-1.245f, -2.29f, 0.205f, 0.42f)
        )
    }


    /** Удобный обёртка: собрать препятствия и разрешить позицию за один вызов (координаты глобальные). */
    fun resolveFor(
        loc: LocationId,
        nodes: List<ResourceNode>,
        x: Float,
        z: Float,
        radius: Float = PLAYER_RADIUS
    ): FloatArray {
        val (ox, oz) = WorldRegions.centerOf(loc)
        val (circles, rects) = obstacles(loc, nodes)
        val boxes = if (loc == LocationId.HUNTERS_CABIN) cabinWalls() else emptyList()
        val r = resolve(x - ox, z - oz, radius, circles, rects, boxes)
        return floatArrayOf(r[0] + ox, r[1] + oz)
    }

    /** Игрок внутри сруба/зимовья (тепло расходуется медленнее). Координаты глобальные. */
    fun isInsideShelter(loc: LocationId, x: Float, z: Float): Boolean {
        if (loc == LocationId.HUNTERS_CABIN) {
            // «Внутри» — по локальным осям повёрнутого сруба, с отступом от стены
            val (ox, oz) = WorldRegions.centerOf(loc)
            val c = kotlin.math.cos(CABIN_YAW)
            val s = kotlin.math.sin(CABIN_YAW)
            val dx = (x - ox) - CABIN_X
            val dz = (z - oz) - CABIN_Z
            val lx = dx * c - dz * s
            val lz = dx * s + dz * c
            return lx > CABIN_MIN_X + 0.3f && lx < CABIN_MAX_X - 0.3f &&
                lz > CABIN_MIN_Z + 0.3f && lz < CABIN_MAX_Z - 0.3f
        }
        val def = when (loc) {
            LocationId.BASE_CLEARING -> BASE_SHELTER
            else -> return false
        }
        val (ox, oz) = WorldRegions.centerOf(loc)
        val lx = x - ox
        val lz = z - oz
        val cx = def[0]
        val cz = def[1]
        val hw = def[2]
        val hd = def[3]
        return lx > cx - hw + 0.3f && lx < cx + hw - 0.3f && lz > cz - hd + 0.3f && lz < cz + hd - 0.3f
    }
}
