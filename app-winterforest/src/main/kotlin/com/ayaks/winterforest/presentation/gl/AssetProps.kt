package com.ayaks.winterforest.presentation.gl

import com.ayaks.winterforest.domain.model.LocationId

/**
 * Импортированные 3D-модели (Poly Haven + авторские паки, упакованы
 * tools/pack_models.py / pack_animals_cabin.py / pack_new_models.py /
 * tools/fix_animal_textures.py) и их декоративные расстановки по локациям.
 * Координаты — мировые, высота берётся из рельефа при построении VBO.
 */
object AssetProps {

    data class Placement(
        val modelId: String,
        val x: Float,
        val z: Float,
        val yawRad: Float = 0f,
        val scale: Float = 1f,
        val tintR: Float = 1f,
        val tintG: Float = 1f,
        val tintB: Float = 1f,
        /** Наклон вокруг локальной оси X (копьё/кирка «воткнуты» под углом, а не столбами). */
        val pitchRad: Float = 0f
    )

    /** Все модели, которые нужно загрузить из assets/models/. */
    val ALL_MODEL_IDS = listOf(
        "rock_09", "rock_moss_a", "rock_moss_b", "fire_pit", "wooden_axe",
        "dry_branches_a", "dry_branches_b", "tree_bark",
        "cabin_wood", "cabin_bark", "cabin_stone", "cabin_shingle", "cabin_burned",
        "wolf_body", "deer_body", "rat_body",
        "spear", "pickaxe", "winter_tree", "workbench", "barrel",
        "gate", "lantern", "cache_crate", "cache_crate_open", "woodstack", "crate", "cloth_bush",
        "oak_a", "oak_b", "oak_c", "bush_green", "bush_dry", "bush_thicket"
    )

    /**
     * Модели животных: VBO частей строится в начале координат, позицию задаёт
     * model-матрица. Ровно по одной записи на вид — прежний дубль `rat_body`
     * заставлял [com.ayaks.winterforest.presentation.gl.ForestGlRenderer] дважды
     * собирать детали крысы, причём вторая сборка перезаписывала первую в кэше
     * без release() старого VBO (утечка GPU-буфера на каждом старте сцены).
     */
    val ENTITY_MODEL_IDS = listOf("wolf_body", "deer_body", "rat_body")

    /**
     * Тусклые/тёплые тонировки под северную палитру (листва холоднее, кора теплее).
     *
     * §7.10: здесь остаются ТОЛЬКО осмысленные ландмарки и постройки (верстак,
     * ящики, ворота, фонари, сруб, поленницы). Камни, деревья, кусты и валежник
     * больше не лежат декором: каждый видимый камень/дерево в мире — это
     * интерактивный узел добычи (WorldPopulator), иначе игрок видел ресурсы,
     * которые «нельзя добыть».
     */
    private fun placements(loc: LocationId): List<Placement> = when (loc) {
        LocationId.BASE_CLEARING -> listOf(
            // верстак-тележка — игровой ландмарк (4.8, -4.5)
            Placement("workbench", 4.8f, -4.5f, 2.35f, 1.0f),
            Placement("crate", 6.35f, -2.55f, 1.9f, 1.0f),
            Placement("cache_crate", 4.2f, -3.6f, 0.5f, 1.0f),
            Placement("barrel", -5.85f, -6.7f, 0.6f, 1.0f),
            Placement("lantern", 1.2f, 1.1f, 0.3f, 1.0f),
            Placement("woodstack", -7.65f, -5.25f, 0.25f, 1.0f),
            Placement("woodstack", -7.25f, -5.75f, 1.15f, 1.0f),
            Placement("woodstack", -7.5f, -4.7f, 2.05f, 1.0f),
            // заборов нет — открытая поляна, ничего не мешает обзору
            Placement("winter_tree", -16.8f, 13.2f, 0.55f, 0.95f),
            // листва и кусты пака Trees & Bushes (модель пользователя)
        )
        LocationId.HUNTERS_CABIN -> listOf(
            // Масштаб 1.0: сруб высотой 1.86 м. При 0.78 крыша опускалась до 1.45 м —
            // ниже камеры игрока (1.72 м), и печная труба с подсветкой торчали сквозь неё.
            Placement("cabin_wood", 7.2f, 8.8f, 3.6f, 1.0f),
            Placement("cabin_bark", 7.2f, 8.8f, 3.6f, 1.0f),
            Placement("cabin_stone", 7.2f, 8.8f, 3.6f, 1.0f),
            Placement("cabin_shingle", 7.2f, 8.8f, 3.6f, 1.0f),
            Placement("cabin_burned", 7.2f, 8.8f, 3.6f, 1.0f),
            Placement("fire_pit", 4.5f, 6.3f, 1.1f, 0.72f),
            // ворота у тропы, фонарь у света избы (7.2, 6.2), закрытый ящик на тайнике (9, 9.5)
            Placement("gate", 7.2f, 4.7f, 3.35f, 0.9f),
            Placement("lantern", 7.2f, 6.2f, 0.2f, 1.0f),
            Placement("cache_crate", 9.0f, 9.5f, 1.25f, 1.0f),
            Placement("barrel", 5.35f, 8.25f, 0.8f, 1.0f),
            Placement("woodstack", 9.7f, 7.25f, 1.9f, 1.0f),
            Placement("woodstack", 10.05f, 7.75f, 2.6f, 1.0f),
            Placement("crate", 8.45f, 5.45f, 0.4f, 1.0f),
            Placement("winter_tree", 14.6f, 5.9f, 2.25f, 0.85f),
            // листва и кусты пака Trees & Bushes (модель пользователя)
        )
        LocationId.DENSE_SPRUCE -> listOf(
            Placement("winter_tree", -12.9f, 8.1f, 0.35f, 1.0f),
            Placement("winter_tree", 11.7f, -14.3f, 1.85f, 1.0f),
            Placement("cache_crate", -9.5f, -11.0f, 2.4f, 1.0f),
            Placement("woodstack", 6.45f, -9.15f, 1.35f, 1.0f),
            Placement("barrel", -13.55f, -2.25f, 2.1f, 1.0f),
            Placement("crate", -8.15f, -9.65f, 1.6f, 1.0f),
            // листва и кусты пака Trees & Bushes (модель пользователя)
        )
        LocationId.FROZEN_LAKE -> listOf(
            Placement("cache_crate", 10.5f, -8.5f, 0.9f, 1.0f),
            Placement("lantern", 9.25f, -7.55f, 1.4f, 1.0f),
            Placement("woodstack", -18.35f, 9.45f, 0.42f, 1.0f),
            Placement("barrel", -20.55f, -7.15f, 1.15f, 1.0f),
            // листва и кусты пака Trees & Bushes (модель пользователя)
        )
        LocationId.ABANDONED_MINE -> listOf(
            // врата к жиле, тайник шахты (-8, 10)
            Placement("gate", -8.0f, 7.6f, 0.0f, 0.85f),
            Placement("cache_crate", -8.0f, 10.0f, 3.0f, 1.0f),
            Placement("lantern", -7.05f, 8.45f, 2.2f, 1.0f),
            Placement("barrel", -4.45f, 12.55f, 1.7f, 1.0f),
            Placement("crate", -9.45f, 12.05f, 1.05f, 1.0f),
            Placement("woodstack", -3.15f, 8.75f, 2.85f, 1.0f),
            // сухой куст пака Trees & Bushes у штольни (модель пользователя)
        )
    }

    fun forLocation(loc: LocationId): List<Placement> = placements(loc)
}
