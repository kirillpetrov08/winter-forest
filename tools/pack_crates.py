#!/usr/bin/env python3
"""Пакует военные ящики из models/special_cache.blend в два варианта:

- cache_crate      — закрытый ящик (створка a + крышка на месте);
- cache_crate_open — вскрытый ящик (створка b + крышка b откинута).

Использует ту же механику pack_new_models.pack_prop (блендинг-экстрактор
без Blender, атлас текстур, нормализация размера).
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pack_new_models as pnm

CLOSED_PARTS = [
    "old_military_crate_a",
    "old_military_crate_cloth_a",
    "old_military_crate_latch_a",
    "old_military_crate_loop_a",
    "old_military_crate_lid_a",
]
OPEN_PARTS = [
    "old_military_crate_b",
    "old_military_crate_cloth_b",
    "old_military_crate_latch_b",
    "old_military_crate_loop_b",
    "old_military_crate_lid_b",
]

MAT = dict(mat_map={"military": "@old_military_crate_diff"},
           default_tex="@old_military_crate_diff")


def main():
    pnm.ensure_tex("models/special_cache.blend")
    ok = 0
    if pnm.pack_prop(
        "cache_crate",
        blend="models/special_cache.blend",
        keep=CLOSED_PARTS,
        target=0.95,
        by_height=False,
        **MAT,
    ):
        ok += 1
    if pnm.pack_prop(
        "cache_crate_open",
        blend="models/special_cache.blend",
        keep=OPEN_PARTS,
        target=0.95,
        by_height=False,
        **MAT,
    ):
        ok += 1
    print("packed %d crate models" % ok)


if __name__ == "__main__":
    main()
