# Winter Forest → стиль Rust: пакет ассетов (v1, реализован)

Статус: **все ассеты сгенерированы процедурно** скриптами `tools/generate_textures.py`,
`tools/generate_icons.py`, `tools/generate_ui_sky_vfx.py` (PIL + numpy, бесшовный FFT-шум,
слоистые материалы, композитные иконки с тенями). Имена и размеры фиксированы — код на них завязан.

## Состав пакета (131 файл)

| Папка | Кол-во | Размер | Назначение |
|---|---|---|---|
| `assets/textures/` | 54 | 2048² (замены) / 1024² (новые) | Материалы GL: снег/лёд/кора/хвоя/камень/ткань/мех/металл/угли + ржавые индустриальные (профлист, мешковина, тент, бетон, сруб, обугленное) + `loc_*` виды локаций + `item_*` |
| `assets/icons/` | 33 | 1024² RGBA | Предметные иконки Rust-стиля: ресурсы (11), инструменты (5), находки (5), рецепты (7), объекты (5) — `icon_*.png` |
| `assets/glyphs/` | 24 | 512² RGBA | HUD-глифы: `glyph_health/hunger/warmth/cold/stamina/xp/level/sun/moon/timer/snowfall/wind/backpack/craft/map/home/sprint/eat/ignite/place/warning/reward/settings/revive.png` |
| `assets/sky/` | 5 | 2048×1024 / 512² / 2048×512 | `clouds_high/low.png`, `sun_disc.png`, `moon_disc.png`, `aurora_band.png` |
| `assets/vfx/` | 6 | 128–1024² | `snowflake`, `smoke_puff`, `ember_particle`, `glow_soft`, `flame_sheet` (4 кадра), `hit_marker` |
| `assets/ui/` | 9 | 512² / 256² / 2048×1024 | `panel_bg`, `panel_scratches`, `slot_frame(_active)`, `button_normal/accent`, `divider_line`, `logo_winter_forest`, `map_bg_parchment` |

## Стиль
- Свет: тёплый ключевой сверху-слева, холодный подсвет; у иконок мягкая контактная тень.
- Палитра: снег `#EAF2F8`, синие тени `#9FB8CC`, хвоя `#24382A`, ржавчина `#B4552B`, акцент `#E8853A`.
- Текстуры — бесшовные (FFT 1/f-шум), без запечённых теней; иконки — прозрачный фон.

## Перегенерация
`python3 tools/generate_textures.py && python3 tools/generate_icons.py && python3 tools/generate_ui_sky_vfx.py`
(нужны `numpy` и `pillow`).
