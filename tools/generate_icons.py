#!/usr/bin/env python3
"""Rust-style item icon generator: 34 composite icons (1024x1024 RGBA).

Each icon is composed from multiple shaded primitives + noise texture overlay
+ soft contact shadow. No single-blob placeholders.
"""
import math
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = os.path.join(os.path.dirname(__file__), "..", "app-winterforest", "src", "main", "assets", "icons")
S = 1024


def canvas():
    return Image.new("RGBA", (S, S), (0, 0, 0, 0))


def noise_arr(size, seed, octaves=4):
    rng = np.random.default_rng(seed)
    out = np.zeros((size, size))
    amp = 1.0
    for o in range(octaves):
        n = rng.normal(size=(size, size))
        n = np.array(Image.fromarray(((n - n.min()) / (n.max() - n.min()) * 255).astype(np.uint8)).filter(
            ImageFilter.GaussianBlur(2 ** (octaves - o))))
        out += amp * (n / 255.0)
        amp *= 0.55
    out -= out.min()
    return out / out.max()


def apply_texture(img, strength=0.22, seed=1):
    """Multiply luminance noise into visible pixels only."""
    a = np.array(img).astype(np.float32) / 255.0
    n = noise_arr(S, seed)
    n = np.array(Image.fromarray((n * 255).astype(np.uint8)).resize((S, S)))
    n = n.astype(np.float32) / 255.0
    vis = a[..., 3] > 0.02
    for c in range(3):
        a[..., c][vis] *= (1.0 - strength / 2 + strength * n[vis])
    return Image.fromarray((np.clip(a, 0, 1) * 255).astype(np.uint8), "RGBA")


def vgrad_fill(draw, box, c_top, c_bot, radius=0):
    x0, y0, x1, y1 = box
    h = max(y1 - y0, 1)
    for y in range(int(y0), int(y1)):
        t = (y - y0) / h
        col = tuple(int(a + (b - a) * t) for a, b in zip(c_top, c_bot))
        if radius > 0:
            draw.rounded_rectangle((x0, y, x1, y + 1), radius=radius, fill=col)
        else:
            draw.rectangle((x0, y, x1, y + 1), fill=col)


def soft_shadow(img, blur=26, alpha=110, offset=(16, 22)):
    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    a = img.split()[3]
    sh = Image.new("RGBA", img.size, (8, 12, 18, alpha))
    shadow.paste(sh, offset, a)
    shadow = shadow.filter(ImageFilter.GaussianBlur(blur))
    out = Image.alpha_composite(shadow, img)
    return out


def poly_glow(draw, pts, fill, outline=None, w=0):
    draw.polygon(pts, fill=fill, outline=outline, width=w)


def rotated_rect_pts(cx, cy, w, h, ang):
    c, s = math.cos(ang), math.sin(ang)
    pts = []
    for dx, dy in ((-w / 2, -h / 2), (w / 2, -h / 2), (w / 2, h / 2), (-w / 2, h / 2)):
        pts.append((cx + dx * c - dy * s, cy + dx * s + dy * c))
    return pts


def log_shape(d, cx, cy, length, thick, ang, bark="#6b543c", bark_d="#3c2e1e", end="#b89062"):
    pts = rotated_rect_pts(cx, cy, length, thick, ang)
    d.polygon(pts, fill=bark)
    ex = cx + math.cos(ang) * length / 2
    ey = cy + math.sin(ang) * length / 2
    d.ellipse((ex - thick / 2, ey - thick / 2, ex + thick / 2, ey + thick / 2), fill=end, outline=bark_d, width=5)
    d.ellipse((ex - thick / 5, ey - thick / 5, ex + thick / 5, ey + thick / 5), outline=bark_d, width=4)


def rock_shape(d, cx, cy, r, seed, base="#7a8088", dark="#4a5058", light="#a8b0b8"):
    rng = np.random.default_rng(seed)
    pts = []
    for i in range(9):
        a = i / 9 * 2 * math.pi
        rr = r * (0.75 + 0.35 * rng.random())
        pts.append((cx + math.cos(a) * rr, cy + math.sin(a) * rr * 0.9))
    d.polygon(pts, fill=base)
    # facets
    d.polygon([pts[0], pts[1], pts[2], (cx, cy)], fill=light)
    d.polygon([pts[3], pts[4], pts[5], (cx, cy)], fill=dark)


def finish(img, seed, texture=0.2):
    img = apply_texture(img, texture, seed)
    return soft_shadow(img)


def save(img, name):
    img.save(os.path.join(OUT, name + ".png"), "PNG", optimize=True)
    print("  icon_" + name.replace("icon_", "") + ".png")


# ---------------- icons ----------------

def icon_wood():
    img = canvas(); d = ImageDraw.Draw(img)
    log_shape(d, 400, 620, 640, 150, -0.12)
    log_shape(d, 430, 470, 620, 140, 0.10)
    log_shape(d, 500, 330, 560, 130, -0.05)
    # rope band
    d.polygon(rotated_rect_pts(560, 470, 60, 480, 0.02), fill="#9c8054", outline="#5c4a30", width=6)
    return finish(img, 3, 0.22)

def icon_stone():
    img = canvas(); d = ImageDraw.Draw(img)
    rock_shape(d, 420, 560, 240, 11)
    rock_shape(d, 660, 480, 170, 12)
    rock_shape(d, 560, 700, 150, 13)
    d.ellipse((360, 420, 520, 470), fill=(230, 240, 250, 90))  # frost patch
    return finish(img, 5, 0.2)

def icon_iron_ore():
    img = canvas(); d = ImageDraw.Draw(img)
    rock_shape(d, 512, 540, 260, 21, base="#6a6e74")
    for pts in ([(380, 480), (520, 430), (610, 500), (540, 560), (420, 545)],
                [(500, 620), (620, 560), (680, 640), (580, 690)]):
        d.polygon(pts, fill="#b06838", outline="#7a3c1e", width=5)
    d.ellipse((560, 420, 620, 460), fill="#e8a868")
    return finish(img, 7, 0.18)

def icon_coal():
    img = canvas(); d = ImageDraw.Draw(img)
    rock_shape(d, 430, 580, 210, 31, base="#24262a", dark="#101214", light="#4a4e54")
    rock_shape(d, 640, 500, 160, 32, base="#2a2c30", dark="#141618", light="#565a60")
    d.polygon([(420, 520), (470, 490), (500, 540), (450, 570)], fill=(120, 130, 150, 160))
    d.polygon([(620, 470), (660, 450), (680, 490)], fill=(140, 150, 170, 140))
    return finish(img, 9, 0.16)

def icon_cloth():
    img = canvas(); d = ImageDraw.Draw(img)
    d.rounded_rectangle((300, 330, 720, 700), radius=40, fill="#b9a181", outline="#7a664c", width=6)
    d.rounded_rectangle((340, 380, 680, 500), radius=24, fill="#a89070")
    for y in range(360, 680, 28):
        d.line((320, y, 700, y), fill=(120, 100, 76, 70), width=3)
    # frayed edge
    for x in range(310, 710, 26):
        d.polygon([(x, 700), (x + 12, 700), (x + 6, 730 + (x % 3) * 8)], fill="#b9a181")
    return finish(img, 11, 0.24)

def icon_hide():
    img = canvas(); d = ImageDraw.Draw(img)
    d.ellipse((280, 380, 760, 680), fill="#8a6c4c")
    d.ellipse((320, 410, 720, 650), fill="#a8845c")
    for i in range(14):
        y = 420 + i * 16
        d.arc((330, y - 40, 710, y + 40), 200, 340, fill="#6a4c32", width=5)
    d.polygon(rotated_rect_pts(512, 530, 90, 320, 0.5), fill="#7a5c3c", outline="#4c3826", width=5)
    return finish(img, 13, 0.22)

def icon_meat():
    img = canvas(); d = ImageDraw.Draw(img)
    d.polygon([(320, 520), (430, 400), (620, 380), (730, 480), (700, 640), (520, 700), (360, 650)],
              fill="#a83c34", outline="#6c201c", width=8)
    d.polygon([(360, 520), (460, 430), (600, 420), (680, 500), (640, 610), (500, 650), (390, 610)],
              fill="#c0584c")
    for pts in ([(420, 500), (520, 470), (560, 520)], [(520, 580), (610, 550), (630, 600)]):
        d.polygon(pts, fill="#e8d0b8")
    return finish(img, 15, 0.18)

def icon_fish():
    img = canvas(); d = ImageDraw.Draw(img)
    d.ellipse((280, 430, 720, 620), fill="#8aa8b8", outline="#4c6472", width=6)
    d.polygon([(700, 525), (830, 430), (830, 620)], fill="#7a98a8", outline="#4c6472", width=5)
    d.polygon([(430, 430), (500, 350), (560, 430)], fill="#6a8898")
    d.ellipse((350, 480, 400, 525), fill="#f0f4f6", outline="#2c3c46", width=4)
    d.ellipse((362, 492, 388, 515), fill="#1a242c")
    for i in range(7):
        x = 420 + i * 42
        d.arc((x, 460, x + 70, 590), 300, 60, fill="#5c7886", width=4)
    return finish(img, 17, 0.16)

def icon_berries():
    img = canvas(); d = ImageDraw.Draw(img)
    d.pieslice((330, 480, 700, 820), 0, 180, fill="#7a5a3c", outline="#4c3826", width=6)
    for cx, cy, r in ((430, 470, 52), (510, 440, 56), (590, 465, 50), (470, 520, 48), (560, 520, 50)):
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill="#a82838", outline="#6c1420", width=4)
        d.ellipse((cx - r // 3, cy - r // 2, cx, cy - r // 6), fill=(255, 190, 190, 130))
    d.polygon([(600, 420), (700, 380), (660, 450)], fill="#2c5c34")
    return finish(img, 19, 0.18)

def icon_resin():
    img = canvas(); d = ImageDraw.Draw(img)
    d.polygon([(300, 620), (420, 520), (620, 500), (760, 560), (700, 680), (480, 700)], fill="#5c4630")
    d.ellipse((420, 380, 680, 600), fill="#d29838")
    d.ellipse((455, 410, 645, 570), fill="#e8b858")
    d.ellipse((490, 440, 570, 510), fill="#f8e0a0")
    d.ellipse((560, 500, 620, 560), fill="#c28828")
    return finish(img, 21, 0.14)

def icon_ice():
    img = canvas(); d = ImageDraw.Draw(img)
    d.polygon([(430, 320), (620, 360), (680, 560), (540, 720), (400, 620), (380, 450)],
              fill="#a8d0e8", outline="#5c88a8", width=6)
    d.polygon([(470, 380), (580, 410), (610, 540), (520, 640)], fill="#c8e4f4")
    d.line((470, 400, 580, 620), fill="#7aa8c4", width=6)
    d.line((520, 420, 450, 580), fill="#7aa8c4", width=5)
    return finish(img, 23, 0.12)

def tool_axe(d, ornate=False):
    d.polygon(rotated_rect_pts(430, 560, 620, 64, -0.62), fill="#7a5c3c", outline="#4c3826", width=5)
    d.polygon([(560, 300), (720, 340), (700, 470), (540, 430)], fill="#8a929c", outline="#3c4248", width=6)
    d.polygon([(700, 330), (760, 360), (740, 450), (690, 440)], fill="#c2cad2")
    d.polygon(rotated_rect_pts(360, 660, 120, 70, -0.62), fill="#5c442c")
    if ornate:
        for t in range(3):
            y = 500 + t * 60
            d.polygon([(360 + t * 28, y), (390 + t * 28, y + 14), (360 + t * 28, y + 28)], fill="#e8b858")

def icon_axe():
    img = canvas(); d = ImageDraw.Draw(img)
    tool_axe(d)
    return finish(img, 25, 0.16)

def tool_pickaxe(d, ornate=False):
    d.polygon(rotated_rect_pts(480, 580, 640, 60, -0.55), fill="#7a5c3c", outline="#4c3826", width=5)
    d.polygon([(560, 330), (700, 280), (760, 320), (700, 360), (620, 390)], fill="#8a929c", outline="#3c4248", width=5)
    d.polygon([(560, 330), (430, 300), (360, 350), (450, 380), (540, 380)], fill="#6a727c", outline="#3c4248", width=5)
    d.polygon(rotated_rect_pts(400, 690, 110, 66, -0.55), fill="#5c442c")
    if ornate:
        d.arc((540, 300, 760, 420), 120, 300, fill="#e8b858", width=8)
        d.ellipse((455, 555, 495, 595), fill="#e8b858")
    return None

def icon_pickaxe():
    img = canvas(); d = ImageDraw.Draw(img)
    tool_pickaxe(d)
    return finish(img, 27, 0.16)

def icon_special_pickaxe():
    img = canvas(); d = ImageDraw.Draw(img)
    tool_pickaxe(d, ornate=True)
    return finish(img, 29, 0.16)

def tool_spear(d, steel=False):
    d.polygon(rotated_rect_pts(470, 560, 760, 44, -0.72), fill="#8a6c48", outline="#4c3826", width=4)
    head = "#c2cad2" if steel else "#9aa4ac"
    d.polygon([(620, 240), (700, 320), (640, 360), (580, 320)], fill=head, outline="#3c4248", width=5)
    for t in range(3):
        a = -0.72
        cx = 330 + t * 46 * math.cos(a + 1.2); cy = 700 + t * 46 * math.sin(a + 1.2)
        d.ellipse((cx - 26, cy - 26, cx + 26, cy + 26), fill="#6a5238")

def icon_spear():
    img = canvas(); d = ImageDraw.Draw(img)
    tool_spear(d)
    return finish(img, 31, 0.16)

def icon_recipe_hunting_spear():
    img = canvas(); d = ImageDraw.Draw(img)
    tool_spear(d, steel=True)
    return finish(img, 33, 0.16)

def tool_torch(d, lit=False, big_head=False, carved=False):
    d.polygon(rotated_rect_pts(470, 620, 640, 72, -0.35), fill="#6a5034", outline="#3c2c1c", width=5)
    if carved:
        for t in range(4):
            d.arc((330 + t * 60, 560 + t * 18, 420 + t * 60, 700 + t * 18), 200, 340, fill="#4c3826", width=6)
    for t in range(3):
        y = 380 + t * 56
        d.polygon(rotated_rect_pts(520 + t * 6, y, 150, 44, -0.35), fill="#b9a181", outline="#7a664c", width=4)
    r = 130 if big_head else 105
    d.ellipse((520 - r, 300 - r, 520 + r, 300 + r), fill="#c28828", outline="#7a4c12", width=6)
    d.ellipse((520 - r // 2, 300 - r // 2 - 20, 520 + r // 2, 300 + r // 2), fill="#e8b858")
    if lit:
        for i, (dx, dy, rr, col) in enumerate(((0, -140, 110, (255, 150, 40, 235)), (0, -210, 70, (255, 210, 90, 235)), (-40, -110, 60, (255, 120, 20, 200)), (46, -120, 55, (255, 170, 50, 200)))):
            d.ellipse((520 + dx - rr, 300 + dy - rr, 520 + dx + rr, 300 + dy + rr), fill=col)

def icon_torch():
    img = canvas(); d = ImageDraw.Draw(img)
    tool_torch(d)
    return finish(img, 35, 0.16)

def icon_torch_lit():
    img = canvas(); d = ImageDraw.Draw(img)
    tool_torch(d, lit=True)
    return finish(img, 37, 0.14)

def icon_recipe_torch():
    img = canvas(); d = ImageDraw.Draw(img)
    tool_torch(d, big_head=True)
    return finish(img, 39, 0.16)

def icon_special_resin_torch():
    img = canvas(); d = ImageDraw.Draw(img)
    tool_torch(d, big_head=True, carved=True)
    return finish(img, 41, 0.16)

def icon_old_map():
    img = canvas(); d = ImageDraw.Draw(img)
    d.rounded_rectangle((300, 340, 740, 680), radius=36, fill="#d8c49c", outline="#8a7248", width=6)
    d.ellipse((270, 380, 360, 640), fill="#c8b088", outline="#8a7248", width=5)
    d.ellipse((680, 380, 770, 640), fill="#c8b088", outline="#8a7248", width=5)
    for pts in ([(400, 480), (480, 440), (560, 500), (650, 460)], [(380, 560), (500, 590), (620, 540)]):
        d.line(pts, fill="#7a5c34", width=6)
    d.polygon([(540, 500), (560, 470), (580, 500), (540, 540), (580, 540), (560, 500)], fill="#a82838")
    return finish(img, 43, 0.18)

def icon_thermos():
    img = canvas(); d = ImageDraw.Draw(img)
    d.rounded_rectangle((420, 320, 620, 720), radius=60, fill="#8a929c", outline="#3c4248", width=6)
    vgrad_fill(d, (430, 340, 610, 700), (168, 178, 190), (96, 104, 116), radius=50)
    d.rounded_rectangle((430, 300, 610, 380), radius=30, fill="#b4552b", outline="#6c2c14", width=5)
    d.rounded_rectangle((450, 270, 590, 320), radius=18, fill="#c8cd d".replace(" d", "d2"), outline="#3c4248", width=4)
    d.arc((620, 420, 760, 620), 280, 80, fill="#3c4248", width=16)
    return finish(img, 45, 0.16)

def icon_warm_coat():
    img = canvas(); d = ImageDraw.Draw(img)
    d.polygon([(360, 420), (512, 330), (664, 420), (720, 560), (680, 760), (344, 760), (304, 560)],
              fill="#2c3c4c", outline="#1a242c", width=8)
    d.polygon([(360, 420), (512, 330), (664, 420), (620, 470), (512, 420), (404, 470)], fill="#3c5062")
    d.arc((400, 260, 624, 460), 180, 360, fill="#6a5238", width=26)  # fur hood trim
    for x in range(410, 620, 22):
        d.ellipse((x, 290 + (x % 3) * 6, x + 18, 316), fill="#7a6046")
    d.line((512, 430, 512, 740), fill="#1a242c", width=10)  # zipper
    d.rounded_rectangle((330, 560, 400, 680), radius=16, fill="#2c3c4c")  # sleeves
    d.rounded_rectangle((624, 560, 694, 680), radius=16, fill="#2c3c4c")
    return finish(img, 47, 0.2)

def icon_recipe_berry_tea():
    img = canvas(); d = ImageDraw.Draw(img)
    d.pieslice((360, 460, 680, 760), 0, 180, fill="#c8cd d2".replace(" d", "d"), outline="#5c6268", width=6)
    d.ellipse((360, 420, 680, 540), fill="#7a8288")
    d.ellipse((380, 435, 660, 525), fill="#8c2a2a")
    for cx, cy in ((450, 470), (520, 455), (590, 475)):
        d.ellipse((cx - 22, cy - 22, cx + 22, cy + 22), fill="#b03848")
    d.arc((660, 500, 760, 620), 280, 90, fill="#c8cdd2", width=18)
    for x, y in ((430, 340), (512, 310), (590, 340)):
        d.arc((x - 30, y - 60, x + 30, y + 40), 220, 320, fill=(220, 230, 240, 180), width=10)
    return finish(img, 49, 0.14)

def icon_recipe_roast_feast():
    img = canvas(); d = ImageDraw.Draw(img)
    d.ellipse((280, 500, 760, 720), fill="#6a7278", outline="#3c4248", width=6)
    d.ellipse((310, 520, 730, 690), fill="#8a9298")
    d.polygon([(380, 560), (480, 520), (560, 560), (520, 620), (410, 620)], fill="#a84830")
    d.polygon([(540, 560), (640, 540), (680, 600), (580, 630)], fill="#8c3a28")
    for cx, cy in ((420, 640), (480, 655), (560, 645), (630, 630)):
        d.ellipse((cx - 18, cy - 18, cx + 18, cy + 18), fill="#a82838")
    d.polygon([(600, 520), (680, 500), (650, 545)], fill="#3c6c34")
    return finish(img, 51, 0.16)

def icon_recipe_warm_compress():
    img = canvas(); d = ImageDraw.Draw(img)
    d.rounded_rectangle((330, 460, 700, 700), radius=40, fill="#d8d0c0", outline="#8a8272", width=6)
    d.rounded_rectangle((380, 500, 650, 660), radius=26, fill="#c2b8a4")
    d.rounded_rectangle((420, 380, 610, 500), radius=20, fill="#8a7a5c", outline="#5c4c34", width=5)
    for x, y in ((450, 330), (512, 300), (575, 330)):
        d.arc((x - 26, y - 50, x + 26, y + 30), 220, 320, fill=(220, 230, 240, 170), width=9)
    return finish(img, 53, 0.16)

def icon_recipe_fur_boots():
    img = canvas(); d = ImageDraw.Draw(img)
    for ox, oy in ((0, 0), (150, -40)):
        d.polygon([(340 + ox, 420 + oy), (500 + ox, 420 + oy), (520 + ox, 620 + oy), (640 + ox, 640 + oy),
                   (650 + ox, 720 + oy), (330 + ox, 720 + oy)], fill="#6a4c32", outline="#3c2c1c", width=6)
        d.rounded_rectangle((335 + ox, 400 + oy, 505 + ox, 480 + oy), radius=30, fill="#a8845c")
        for x in range(350 + ox, 500 + ox, 24):
            d.ellipse((x, 405 + oy, x + 20, 445 + oy), fill="#c2a078")
        d.line((360 + ox, 520 + oy, 470 + ox, 520 + oy), fill="#3c2c1c", width=6)
        d.line((360 + ox, 560 + oy, 470 + ox, 560 + oy), fill="#3c2c1c", width=6)
    return finish(img, 55, 0.18)

def icon_recipe_miners_heater():
    img = canvas(); d = ImageDraw.Draw(img)
    d.rounded_rectangle((380, 420, 640, 700), radius=26, fill="#7a7268", outline="#3c3830", width=6)
    d.rounded_rectangle((380, 400, 640, 460), radius=20, fill="#8a8278", outline="#3c3830", width=5)
    for x in range(410, 630, 44):
        d.ellipse((x, 560, x + 24, 584), fill="#241c14")
        d.ellipse((x + 10, 500, x + 30, 520), fill="#241c14")
    d.ellipse((420, 620, 600, 690), fill="#c25a18")
    d.ellipse((455, 635, 565, 680), fill="#e88838")
    d.arc((380, 340, 640, 470), 200, 340, fill="#3c3830", width=10)
    return finish(img, 57, 0.16)

def icon_campfire():
    img = canvas(); d = ImageDraw.Draw(img)
    for cx, cy, r in ((360, 680, 70), (460, 720, 75), (570, 715, 72), (660, 680, 68), (512, 660, 60)):
        rock_shape(d, cx, cy, r, seed=cx, base="#7a8088")
    log_shape(d, 512, 600, 320, 66, 0.12)
    log_shape(d, 512, 590, 300, 62, -0.35)
    for dx, dy, rr, col in ((0, -200, 120, (255, 140, 30, 220)), (0, -300, 85, (255, 200, 80, 225)),
                            (-60, -160, 70, (255, 110, 15, 190)), (70, -170, 66, (255, 160, 40, 190))):
        d.ellipse((512 + dx - rr, 600 + dy - rr, 512 + dx + rr, 600 + dy + rr), fill=col)
    d.line((360, 520, 664, 520), fill="#3c4248", width=12)  # grate
    return finish(img, 59, 0.16)

def icon_field_campfire():
    img = canvas(); d = ImageDraw.Draw(img)
    log_shape(d, 512, 640, 260, 48, 0.2)
    log_shape(d, 500, 630, 240, 44, -0.3)
    for dx, dy, rr, col in ((0, -150, 85, (255, 140, 30, 225)), (0, -230, 55, (255, 210, 90, 230)),
                            (-45, -110, 48, (255, 110, 15, 195)), (50, -120, 44, (255, 165, 45, 195))):
        d.ellipse((512 + dx - rr, 620 + dy - rr, 512 + dx + rr, 620 + dy + rr), fill=col)
    for x, y in ((300, 720), (700, 700), (560, 750)):
        d.ellipse((x - 34, y - 22, x + 34, y + 22), fill=(235, 242, 250, 200))
    return finish(img, 61, 0.14)

def icon_supply_crate():
    img = canvas(); d = ImageDraw.Draw(img)
    d.polygon([(300, 420), (724, 420), (724, 720), (300, 720)], fill="#7a5c3c", outline="#4c3826", width=8)
    for y in (480, 560, 640):
        d.line((300, y, 724, y), fill="#5c442c", width=8)
    d.polygon([(300, 420), (380, 350), (800, 350), (724, 420)], fill="#8a6c48", outline="#4c3826", width=8)
    for cx, cy in ((320, 440), (704, 440), (320, 700), (704, 700)):
        d.polygon([(cx - 26, cy - 26), (cx + 26, cy - 26), (cx + 26, cy + 26), (cx - 26, cy + 26)],
                  fill="#6a727c", outline="#3c4248", width=4)
    d.arc((420, 380, 600, 470), 200, 340, fill="#9c8054", width=14)
    return finish(img, 63, 0.18)

def icon_ice_hole():
    img = canvas(); d = ImageDraw.Draw(img)
    d.ellipse((240, 380, 784, 780), fill="#c8e0ee", outline="#8ab0c8", width=8)
    d.ellipse((330, 450, 694, 710), fill="#2c4c66")
    d.ellipse((360, 480, 664, 680), fill="#1c3448")
    d.arc((330, 450, 694, 710), 200, 340, fill="#8ab0c8", width=10)
    for ang in range(0, 360, 30):
        a = math.radians(ang)
        x1, y1 = 512 + math.cos(a) * 250, 580 + math.sin(a) * 170
        x2, y2 = 512 + math.cos(a) * 300, 580 + math.sin(a) * 210
        d.line((x1, y1, x2, y2), fill="#9cc0d4", width=6)
    return finish(img, 65, 0.12)

def icon_workbench():
    img = canvas(); d = ImageDraw.Draw(img)
    d.polygon([(260, 480), (764, 480), (764, 560), (260, 560)], fill="#7a5c3c", outline="#4c3826", width=6)
    for y in (500, 530):
        d.line((260, y, 764, y), fill="#5c442c", width=5)
    d.polygon([(300, 560), (360, 560), (340, 760), (290, 760)], fill="#6a5034")
    d.polygon([(664, 560), (724, 560), (734, 760), (684, 760)], fill="#6a5034")
    # saw + hammer + lantern on top
    d.polygon([(330, 440), (560, 440), (560, 462), (330, 462)], fill="#8a929c")
    d.polygon([(330, 440), (300, 420), (330, 452)], fill="#7a5c3c")
    d.rectangle((600, 420, 620, 470), fill="#6a727c")
    d.rectangle((590, 400, 630, 430), fill="#8a929c")
    d.rounded_rectangle((680, 390, 750, 470), radius=12, fill="#c89838", outline="#6a4c1c", width=5)
    d.ellipse((695, 405, 735, 445), fill="#f0c868")
    return finish(img, 67, 0.18)


ICONS = [
    "icon_wood", "icon_stone", "icon_iron_ore", "icon_coal", "icon_cloth", "icon_hide", "icon_meat",
    "icon_fish", "icon_berries", "icon_resin", "icon_ice",
    "icon_axe", "icon_pickaxe", "icon_spear", "icon_torch", "icon_torch_lit",
    "icon_old_map", "icon_thermos", "icon_warm_coat", "icon_special_pickaxe", "icon_special_resin_torch",
    "icon_recipe_torch", "icon_recipe_berry_tea", "icon_recipe_roast_feast", "icon_recipe_hunting_spear",
    "icon_recipe_warm_compress", "icon_recipe_fur_boots", "icon_recipe_miners_heater",
    "icon_campfire", "icon_field_campfire", "icon_supply_crate", "icon_ice_hole", "icon_workbench",
]


def main():
    os.makedirs(OUT, exist_ok=True)
    print(f"Generating {len(ICONS)} icons -> {os.path.abspath(OUT)}")
    for name in ICONS:
        img = globals()[name]()
        save(img, name)
    print("ALL ICONS DONE")


if __name__ == "__main__":
    main()
