package com.ayaks.winterforest.presentation.gl

import com.ayaks.winterforest.domain.model.LocationId

/**
 * Импортированные 3D-модели (Poly Haven + авторские паки, упакованы
 * tools/pack_models.py / pack_animals_cabin.py / pack_new_models.py)
 * и их декоративные расстановки по локациям. Координаты — мировые,
 * высота берётся из рельефа при построении VBO.
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
        val tintB: Float = 1f
    )

    /** Все модели, которые нужно загрузить из assets/models/. */
    val ALL_MODEL_IDS = listOf(
        "rock_09", "rock_moss_a", "rock_moss_b", "fire_pit", "wooden_axe",
        "dry_branches_a", "dry_branches_b", "calathea", "tree_bark", "tree_leaves",
        "cabin_wood", "cabin_bark", "cabin_stone", "cabin_shingle", "cabin_burned",
        "wolf_body", "deer_body",
        "spear", "pickaxe", "fence", "winter_tree", "workbench", "barrel",
        "gate", "lantern", "cache_crate", "woodstack", "crate", "fish"
    )

    /** Модели животных: VBO строится в начале координат, позиция задаётся model-матрицей. */
    val ENTITY_MODEL_IDS = listOf("wolf_body", "deer_body")

    /** Тусклые/тёплые тонировки под северную палитру (листва холоднее, кора теплее). */
    private fun placements(loc: LocationId): List<Placement> = when (loc) {
        LocationId.BASE_CLEARING -> listOf(
            Placement("fire_pit", 0f, 0f, 0.35f, 1.0f),
            Placement("wooden_axe", 5.75f, -3.55f, 2.1f, 1.0f),
            Placement("rock_09", 7.1f, -1.1f, 0.8f, 1.15f),
            Placement("rock_moss_a", -3.3f, 2.7f, 1.9f, 1.0f),
            Placement("rock_moss_b", -9.6f, -7.8f, 0.4f, 0.9f),
            Placement("dry_branches_a", -8.3f, 3.5f, 1.2f, 1.0f),
            Placement("dry_branches_b", 2.1f, 7.7f, 2.8f, 1.0f),
            Placement("tree_bark", -12.6f, 10.6f, 0.9f, 1.0f, 0.94f, 0.96f, 1.0f),
            Placement("tree_leaves", -12.6f, 10.6f, 0.9f, 1.0f, 0.62f, 0.78f, 0.72f),
            Placement("tree_bark", 13.2f, -8.4f, 2.4f, 0.92f, 0.94f, 0.96f, 1.0f),
            Placement("tree_leaves", 13.2f, -8.4f, 2.4f, 0.92f, 0.58f, 0.74f, 0.70f),
            // верстак-тележка — игровой ландмарк (4.8, -4.5)
            Placement("workbench", 4.8f, -4.5f, 2.35f, 1.0f),
            Placement("spear", 5.35f, -5.15f, 1.15f, 1.0f),
            Placement("pickaxe", 4.25f, -5.15f, 2.6f, 1.0f),
            Placement("crate", 6.35f, -2.55f, 1.9f, 1.0f),
            Placement("cache_crate", 4.2f, -3.6f, 0.5f, 1.0f),
            Placement("barrel", -5.85f, -6.7f, 0.6f, 1.0f),
            Placement("lantern", 1.2f, 1.1f, 0.3f, 1.0f),
            Placement("woodstack", -7.65f, -5.25f, 0.25f, 1.0f),
            Placement("woodstack", -7.25f, -5.75f, 1.15f, 1.0f),
            Placement("woodstack", -7.5f, -4.7f, 2.05f, 1.0f),
            Placement("fence", -9.3f, -2.7f, 1.42f, 1.0f),
            Placement("fence", -10.2f, -4.7f, 1.55f, 1.0f),
            Placement("winter_tree", -16.8f, 13.2f, 0.55f, 0.95f)
        )
        LocationId.HUNTERS_CABIN -> listOf(
            Placement("cabin_wood", 7.2f, 8.8f, 3.6f, 0.78f),
            Placement("cabin_bark", 7.2f, 8.8f, 3.6f, 0.78f),
            Placement("cabin_stone", 7.2f, 8.8f, 3.6f, 0.78f),
            Placement("cabin_shingle", 7.2f, 8.8f, 3.6f, 0.78f),
            Placement("cabin_burned", 7.2f, 8.8f, 3.6f, 0.78f),
            Placement("fire_pit", 4.5f, 6.3f, 1.1f, 0.92f),
            Placement("wooden_axe", 8.35f, 7.0f, 2.6f, 1.0f),
            Placement("calathea", 6.15f, 7.15f, 0.7f, 1.0f),
            Placement("rock_moss_b", 9.9f, 5.1f, 1.5f, 1.0f),
            Placement("rock_09", 3.2f, 11.6f, 2.2f, 1.1f),
            Placement("dry_branches_a", -5.9f, 3.2f, 0.3f, 1.0f),
            Placement("dry_branches_b", 11.8f, 13.4f, 1.7f, 1.0f),
            Placement("tree_bark", 12.7f, 12.1f, 1.6f, 1.05f, 0.94f, 0.96f, 1.0f),
            Placement("tree_leaves", 12.7f, 12.1f, 1.6f, 1.05f, 0.62f, 0.78f, 0.72f),
            // ворота у тропы, фонарь у света избы (7.2, 6.2), ящик на тайнике (9, 9.5)
            Placement("gate", 7.2f, 4.7f, 3.35f, 0.9f),
            Placement("lantern", 7.2f, 6.2f, 0.2f, 1.0f),
            Placement("cache_crate", 9.0f, 9.5f, 1.25f, 1.0f),
            Placement("barrel", 5.35f, 8.25f, 0.8f, 1.0f),
            Placement("woodstack", 9.7f, 7.25f, 1.9f, 1.0f),
            Placement("woodstack", 10.05f, 7.75f, 2.6f, 1.0f),
            Placement("fence", 3.55f, 6.05f, 1.18f, 1.0f),
            Placement("fence", 2.85f, 4.35f, 1.32f, 1.0f),
            Placement("pickaxe", 6.05f, 10.25f, 2.2f, 1.0f),
            Placement("crate", 8.45f, 5.45f, 0.4f, 1.0f),
            Placement("winter_tree", 14.6f, 5.9f, 2.25f, 0.85f)
        )
        LocationId.DENSE_SPRUCE -> listOf(
            Placement("dry_branches_a", -6.4f, -9.2f, 0.9f, 1.0f),
            Placement("dry_branches_b", 8.8f, -12.5f, 2.3f, 1.0f),
            Placement("dry_branches_a", 13.2f, 6.1f, 1.5f, 0.9f),
            Placement("rock_moss_a", -11.5f, 4.4f, 0.2f, 1.0f),
            Placement("rock_moss_b", 4.9f, 12.8f, 2.9f, 1.0f),
            Placement("rock_09", -14.2f, -3.6f, 1.1f, 1.15f),
            Placement("rock_09", 16.1f, -2.2f, 2.5f, 0.9f),
            Placement("winter_tree", -12.9f, 8.1f, 0.35f, 1.0f),
            Placement("winter_tree", 11.7f, -14.3f, 1.85f, 1.0f),
            Placement("cache_crate", -9.5f, -11.0f, 2.4f, 1.0f),
            Placement("fence", -2.3f, 7.35f, 0.72f, 1.0f),
            Placement("fence", 1.75f, 10.55f, 1.08f, 1.0f),
            Placement("woodstack", 6.45f, -9.15f, 1.35f, 1.0f),
            Placement("barrel", -13.55f, -2.25f, 2.1f, 1.0f),
            Placement("spear", 3.35f, -5.55f, 0.35f, 1.0f),
            Placement("crate", -8.15f, -9.65f, 1.6f, 1.0f)
        )
        LocationId.FROZEN_LAKE -> listOf(
            Placement("rock_09", -19.5f, 12.2f, 0.6f, 1.2f),
            Placement("rock_09", 21.0f, -9.8f, 2.1f, 1.0f),
            Placement("rock_moss_a", -23.4f, -6.5f, 1.4f, 1.1f),
            Placement("dry_branches_b", 17.6f, 15.1f, 0.8f, 1.0f),
            // улов на льду у тайника (10.5, -8.5)
            Placement("fish", 7.2f, -6.3f, 2.2f, 1.0f),
            Placement("fish", 8.15f, -5.85f, 0.75f, 1.0f),
            Placement("cache_crate", 10.5f, -8.5f, 0.9f, 1.0f),
            Placement("lantern", 9.25f, -7.55f, 1.4f, 1.0f),
            Placement("spear", 7.75f, -7.05f, 2.5f, 1.0f),
            Placement("fence", -12.15f, 4.85f, 1.62f, 1.0f),
            Placement("woodstack", -18.35f, 9.45f, 0.42f, 1.0f),
            Placement("barrel", -20.55f, -7.15f, 1.15f, 1.0f)
        )
        LocationId.ABANDONED_MINE -> listOf(
            Placement("rock_moss_b", -5.2f, -14.8f, 0.7f, 1.0f),
            Placement("rock_moss_a", 5.6f, -17.3f, 2.2f, 1.0f),
            Placement("rock_09", -6.8f, 20.5f, 1.3f, 1.15f),
            Placement("rock_09", 7.2f, 24.0f, 0.5f, 0.95f),
            Placement("wooden_axe", -3.7f, -2.1f, 1.8f, 1.0f),
            Placement("dry_branches_a", 3.9f, -25.2f, 2.7f, 1.0f),
            // врата к жиле, тайник шахты (-8, 10)
            Placement("gate", -8.0f, 7.6f, 0.0f, 0.85f),
            Placement("cache_crate", -8.0f, 10.0f, 3.0f, 1.0f),
            Placement("lantern", -7.05f, 8.45f, 2.2f, 1.0f),
            Placement("pickaxe", -8.95f, 9.35f, 0.6f, 1.0f),
            Placement("pickaxe", -6.25f, 11.35f, 2.4f, 1.0f),
            Placement("barrel", -4.45f, 12.55f, 1.7f, 1.0f),
            Placement("fence", 2.25f, 21.55f, 0.38f, 1.0f),
            Placement("fence", 3.45f, 23.05f, 0.52f, 1.0f),
            Placement("crate", -9.45f, 12.05f, 1.05f, 1.0f),
            Placement("woodstack", -3.15f, 8.75f, 2.85f, 1.0f)
        )
    }

    fun forLocation(loc: LocationId): List<Placement> = placements(loc)
}
