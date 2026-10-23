package com.ayaks.winterforest.presentation.gl

import com.ayaks.winterforest.domain.model.LocationId

/**
 * Импортированные 3D-модели (Poly Haven, упакованы tools/pack_models.py)
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
        "wolf_body", "deer_body"
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
            Placement("tree_leaves", 13.2f, -8.4f, 2.4f, 0.92f, 0.58f, 0.74f, 0.70f)
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
            Placement("tree_leaves", 12.7f, 12.1f, 1.6f, 1.05f, 0.62f, 0.78f, 0.72f)
        )
        LocationId.DENSE_SPRUCE -> listOf(
            Placement("dry_branches_a", -6.4f, -9.2f, 0.9f, 1.0f),
            Placement("dry_branches_b", 8.8f, -12.5f, 2.3f, 1.0f),
            Placement("dry_branches_a", 13.2f, 6.1f, 1.5f, 0.9f),
            Placement("rock_moss_a", -11.5f, 4.4f, 0.2f, 1.0f),
            Placement("rock_moss_b", 4.9f, 12.8f, 2.9f, 1.0f),
            Placement("rock_09", -14.2f, -3.6f, 1.1f, 1.15f),
            Placement("rock_09", 16.1f, -2.2f, 2.5f, 0.9f)
        )
        LocationId.FROZEN_LAKE -> listOf(
            Placement("rock_09", -19.5f, 12.2f, 0.6f, 1.2f),
            Placement("rock_09", 21.0f, -9.8f, 2.1f, 1.0f),
            Placement("rock_moss_a", -23.4f, -6.5f, 1.4f, 1.1f),
            Placement("dry_branches_b", 17.6f, 15.1f, 0.8f, 1.0f)
        )
        LocationId.ABANDONED_MINE -> listOf(
            Placement("rock_moss_b", -5.2f, -14.8f, 0.7f, 1.0f),
            Placement("rock_moss_a", 5.6f, -17.3f, 2.2f, 1.0f),
            Placement("rock_09", -6.8f, 20.5f, 1.3f, 1.15f),
            Placement("rock_09", 7.2f, 24.0f, 0.5f, 0.95f),
            Placement("wooden_axe", -3.7f, -2.1f, 1.8f, 1.0f),
            Placement("dry_branches_a", 3.9f, -25.2f, 2.7f, 1.0f)
        )
    }

    fun forLocation(loc: LocationId): List<Placement> = placements(loc)
}
