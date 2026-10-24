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

    /** Радиус тела игрока при проходе. */
    const val PLAYER_RADIUS = 0.42f

    /** Сруб игрока: центр, полуширина, полуглубина, проём по X, сторона проёма (+1 = +Z). */
    private val BASE_SHELTER = floatArrayOf(-6.8f, -5.8f, 2.3f, 2.1f, -0.85f, 1f)

    /** Зимовье охотника (импортная модель cabin_*, вход на север к фонарю). */
    private val HUNTER_CABIN = floatArrayOf(7.2f, 8.8f, 2.9f, 3.8f, -0.6f, -1f)

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
                WorldObjectKind.SUPPLY_CRATE -> 0.78f
                WorldObjectKind.RESIN_STUMP -> 0.55f
                WorldObjectKind.COAL_SEAM -> 1.0f
                WorldObjectKind.IRON_VEIN -> 1.0f
                WorldObjectKind.SPECIAL_CACHE -> 0.48f
                // Лиственное дерево и полевой куст (модель пользователя) — непроходимые
                WorldObjectKind.OAK_TREE -> 0.70f
                WorldObjectKind.SCRUB_BUSH -> 0.62f
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
                addCabin(rects, HUNTER_CABIN)
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
        rects: List<Rect>
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
        }
        return floatArrayOf(px, pz)
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
        val r = resolve(x - ox, z - oz, radius, circles, rects)
        return floatArrayOf(r[0] + ox, r[1] + oz)
    }

    /** Игрок внутри сруба/зимовья (тепло расходуется медленнее). Координаты глобальные. */
    fun isInsideShelter(loc: LocationId, x: Float, z: Float): Boolean {
        val def = when (loc) {
            LocationId.BASE_CLEARING -> BASE_SHELTER
            LocationId.HUNTERS_CABIN -> HUNTER_CABIN
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
