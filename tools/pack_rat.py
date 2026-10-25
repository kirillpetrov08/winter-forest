#!/usr/bin/env python3
"""Пакует модель крысы (из привезённой пользователем models/Rigged Rat.blend)
в game-ready группу app-winterforest/src/main/assets/models/rat_body/."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pack_animals_cabin as pac
from extract_packed_images import extract_packed

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# "body" — тушка, "eyes_teeth" — глаза/зубы. Камера/свет/арматура без меша — пропускаются автоматически.
KEEP = {"body", "eyes_teeth"}

def main():
    # unpack textures from the user's .blend pack
    extract_packed(os.path.join(ROOT, "models/Rigged Rat.blend"), "/tmp/packed_rat")
    pac.pack_model(
        os.path.join(ROOT, "models/Rigged Rat.blend"),
        {
            "rat_body": ({ "body": "rat_body" }, True,
                         "/tmp/packed_rat/rat_diffuse.png"),
            "rat_eyes": ({ "eyes": "eyes_teeth" }, False, (26, 24, 22)),
        },
        # 0.219 м в холке — крупная тайга-крыса; длина с хвостом ~0.82 м
        target=0.219, by_height=True, keep_objects=KEEP
    )

if __name__ == "__main__":
    main()
