#!/usr/bin/env python3
"""Generates: 24 UI glyphs (512), 9 UI chrome, 5 sky layers, 6 VFX sprites."""
import math
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..", "app-winterforest", "src", "main", "assets")
GLY = os.path.join(ROOT, "glyphs")
UI = os.path.join(ROOT, "ui")
SKY = os.path.join(ROOT, "sky")
VFX = os.path.join(ROOT, "vfx")

# ---------------- helpers ----------------

def tile_noise(size, beta, seed, aniso=1.0):
    rng = np.random.default_rng(seed)
    white = rng.normal(size=(size if isinstance(size, int) else size[0], size if isinstance(size, int) else size[1]))
    F = np.fft.fft2(white)
    h, w = white.shape
    fy = np.fft.fftfreq(h)[:, None]
    fx = np.fft.fftfreq(w)[None, :]
    r = np.sqrt((fx / aniso) ** 2 + (fy * aniso) ** 2)
    r[0, 0] = 1.0
    out = np.real(np.fft.ifft2(F / r ** beta))
    out -= out.min()
    return out / out.max()


def save(img, path):
    img.save(path, "PNG", optimize=True)
    print(f"  {os.path.relpath(path, ROOT)}")


# ---------------- glyphs (512 RGBA) ----------------

G = 512

def gcanvas():
    return Image.new("RGBA", (G, G), (0, 0, 0, 0))

def glyph_base(draw_fn):
    img = gcanvas()
    d = ImageDraw.Draw(img)
    draw_fn(d)
    # subtle top-light gradient into the glyph
    a = np.array(img).astype(np.float32)
    grad = np.linspace(1.12, 0.88, G)[:, None, None]
    a[..., :3] = np.clip(a[..., :3] * grad, 0, 255)
    return Image.fromarray(a.astype(np.uint8), "RGBA")

W = (245, 250, 255, 255)

def gl_health(d):
    d.pieslice((110, 130, 270, 300), 180, 360, fill=W)
    d.pieslice((250, 130, 410, 300), 180, 360, fill=W)
    d.polygon([(118, 235), (256, 420), (394, 235)], fill=W)
    d.rectangle((232, 210, 280, 258), fill=(60, 20, 30, 255))
    d.rectangle((243, 199, 269, 269), fill=(60, 20, 30, 255))

def gl_hunger(d):
    d.ellipse((150, 120, 380, 340), fill=W)
    d.rounded_rectangle((300, 250, 420, 320), radius=30, fill=W)
    d.ellipse((390, 250, 460, 320), fill=W)
    d.rectangle((120, 300, 170, 420), fill=W)
    d.ellipse((100, 390, 190, 460), fill=W)

def gl_warmth(d):
    d.polygon([(256, 60), (360, 220), (400, 320), (360, 440), (256, 480), (152, 440), (112, 320), (196, 220)], fill=W)
    d.polygon([(256, 200), (320, 310), (300, 400), (256, 440), (212, 400), (192, 310)], fill=(255, 170, 60, 255))

def gl_cold(d):
    for ang in range(0, 360, 60):
        a = math.radians(ang)
        x2, y2 = 256 + math.cos(a) * 190, 256 + math.sin(a) * 190
        d.line((256, 256, x2, y2), fill=W, width=28)
        for s in (-1, 1):
            bx = 256 + math.cos(a) * 120
            by = 256 + math.sin(a) * 120
            d.line((bx, by, bx + math.cos(a + s * 0.9) * 70, by + math.sin(a + s * 0.9) * 70), fill=W, width=20)

def gl_stamina(d):
    d.polygon([(300, 60), (160, 280), (250, 280), (210, 460), (360, 230), (265, 230)], fill=W)

def gl_xp(d):
    pts = []
    for i in range(10):
        ang = math.radians(-90 + i * 36)
        r = 200 if i % 2 == 0 else 85
        pts.append((256 + math.cos(ang) * r, 256 + math.sin(ang) * r))
    d.polygon(pts, fill=W)

def gl_level(d):
    for off in (-60, 60):
        d.polygon([(256 + off - 70, 160), (256 + off + 60, 256), (256 + off - 70, 352),
                   (256 + off - 30, 352), (256 + off + 100, 256), (256 + off - 30, 160)], fill=W)

def gl_sun(d):
    d.ellipse((176, 176, 336, 336), fill=W)
    for ang in range(0, 360, 45):
        a = math.radians(ang)
        d.line((256 + math.cos(a) * 130, 256 + math.sin(a) * 130,
                256 + math.cos(a) * 200, 256 + math.sin(a) * 200), fill=W, width=24)

def gl_moon(d):
    d.ellipse((130, 110, 390, 370), fill=W)
    d.ellipse((230, 90, 470, 330), fill=(0, 0, 0, 0))

def gl_timer(d):
    d.polygon([(150, 80), (362, 80), (256, 256)], fill=W)
    d.polygon([(150, 432), (362, 432), (256, 256)], fill=W)
    d.rectangle((140, 70, 372, 100), fill=W)
    d.rectangle((140, 412, 372, 442), fill=W)

def gl_snowfall(d):
    d.ellipse((120, 130, 392, 280), fill=W)
    d.ellipse((80, 170, 240, 300), fill=W)
    d.ellipse((280, 160, 430, 290), fill=W)
    for x, y in ((160, 380), (256, 420), (352, 380)):
        d.ellipse((x - 16, y - 16, x + 16, y + 16), fill=W)

def gl_wind(d):
    for i, y in enumerate((160, 256, 352)):
        d.arc((100 - i * 10, y - 50, 380 + i * 10, y + 50), 250, 130, fill=W, width=26)
        d.ellipse((360 + i * 6, y - 26, 412 + i * 6, y + 26), fill=W)

def gl_backpack(d):
    d.rounded_rectangle((150, 180, 362, 460), radius=48, fill=W)
    d.rounded_rectangle((200, 110, 312, 200), radius=40, fill=W)
    d.rounded_rectangle((190, 300, 322, 360), radius=20, fill=(40, 50, 62, 255))

def gl_craft(d):
    d.polygon([(120, 380), (360, 140), (420, 200), (180, 440)], fill=W)
    d.ellipse((320, 100, 450, 230), fill=W)
    d.polygon([(100, 340), (180, 420), (120, 470), (60, 400)], fill=W)
    d.line((200, 140, 320, 260), fill=(40, 50, 62, 255), width=18)

def gl_map(d):
    d.polygon([(100, 150), (220, 120), (320, 160), (420, 130), (420, 390), (320, 420), (220, 380), (100, 410)], fill=W)
    d.line((220, 120, 220, 380), fill=(40, 50, 62, 255), width=10)
    d.line((320, 160, 320, 420), fill=(40, 50, 62, 255), width=10)
    d.ellipse((230, 200, 290, 260), fill=(196, 60, 60, 255))
    d.polygon([(260, 250), (260, 320), (240, 300), (280, 300)], fill=(196, 60, 60, 255))

def gl_home(d):
    d.polygon([(256, 100), (430, 250), (390, 250), (390, 430), (122, 430), (122, 250), (82, 250)], fill=W)
    d.rectangle((220, 310, 292, 430), fill=(40, 50, 62, 255))

def gl_sprint(d):
    d.ellipse((300, 90, 380, 170), fill=W)
    d.polygon([(280, 170), (350, 170), (300, 300), (380, 320), (360, 370), (230, 330)], fill=W)
    d.line((250, 380, 140, 460), fill=W, width=30)
    d.line((320, 380, 430, 430), fill=W, width=30)

def gl_eat(d):
    d.ellipse((140, 260, 372, 420), fill=W)
    d.arc((140, 180, 372, 340), 180, 360, fill=W, width=22)
    for x in (200, 256, 312):
        d.arc((x - 18, 120, x + 18, 220), 120, 300, fill=W, width=12)

def gl_ignite(d):
    d.rectangle((230, 220, 282, 460), fill=W)
    d.polygon([(256, 60), (330, 190), (256, 240), (182, 190)], fill=(255, 170, 60, 255))
    d.polygon([(256, 110), (300, 195), (256, 225), (212, 195)], fill=(255, 220, 120, 255))

def gl_place(d):
    d.ellipse((170, 100, 342, 272), fill=W)
    d.polygon([(256, 240), (320, 320), (192, 320)], fill=W)
    d.rectangle((160, 380, 352, 430), fill=W)
    d.polygon([(256, 340), (300, 390), (212, 390)], fill=W)

def gl_warning(d):
    d.ellipse((120, 180, 280, 340), fill=W)
    d.ellipse((230, 140, 390, 300), fill=W)
    d.ellipse((300, 220, 420, 340), fill=W)
    d.ellipse((160, 300, 300, 440), fill=W)
    d.ellipse((300, 320, 400, 420), fill=W)

def gl_reward(d):
    d.rounded_rectangle((110, 220, 402, 430), radius=24, fill=W)
    d.rectangle((100, 180, 412, 240), fill=W)
    d.rectangle((236, 180, 276, 430), fill=(255, 160, 50, 255))
    d.arc((150, 100, 256, 220), 0, 360, fill=W, width=20)
    d.arc((256, 100, 362, 220), 0, 360, fill=W, width=20)
    d.polygon([(220, 300), (220, 370), (290, 335)], fill=(40, 50, 62, 255))

def gl_settings(d):
    for ang in range(0, 360, 45):
        a = math.radians(ang)
        d.polygon([(256 + math.cos(a - 0.14) * 150, 256 + math.sin(a - 0.14) * 150),
                   (256 + math.cos(a) * 220, 256 + math.sin(a) * 220),
                   (256 + math.cos(a + 0.14) * 150, 256 + math.sin(a + 0.14) * 150)], fill=W)
    d.ellipse((140, 140, 372, 372), fill=W)
    d.ellipse((210, 210, 302, 302), fill=(40, 50, 62, 255))

def gl_revive(d):
    d.pieslice((120, 120, 280, 290), 180, 360, fill=W)
    d.pieslice((232, 120, 392, 290), 180, 360, fill=W)
    d.polygon([(128, 225), (256, 380), (384, 225)], fill=W)
    d.polygon([(256, 260), (320, 330), (280, 330), (280, 420), (232, 420), (232, 330), (192, 330)], fill=(255, 170, 60, 255))

GLYPHS = {
    "glyph_health": gl_health, "glyph_hunger": gl_hunger, "glyph_warmth": gl_warmth,
    "glyph_cold": gl_cold, "glyph_stamina": gl_stamina, "glyph_xp": gl_xp, "glyph_level": gl_level,
    "glyph_sun": gl_sun, "glyph_moon": gl_moon, "glyph_timer": gl_timer, "glyph_snowfall": gl_snowfall,
    "glyph_wind": gl_wind, "glyph_backpack": gl_backpack, "glyph_craft": gl_craft, "glyph_map": gl_map,
    "glyph_home": gl_home, "glyph_sprint": gl_sprint, "glyph_eat": gl_eat, "glyph_ignite": gl_ignite,
    "glyph_place": gl_place, "glyph_warning": gl_warning, "glyph_reward": gl_reward,
    "glyph_settings": gl_settings, "glyph_revive": gl_revive,
}

# ---------------- UI chrome ----------------

def ui_panel_bg():
    n = tile_noise(512, 1.6, 201)
    n2 = tile_noise(512, 1.1, 202)
    base = 0.10 + 0.08 * n
    a = np.zeros((512, 512, 4), np.float32)
    a[..., 0] = base * 0.9
    a[..., 1] = base * 1.0
    a[..., 2] = base * 1.15
    a[..., 3] = 0.85 * 255 * (0.85 + 0.15 * n2)
    img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA")
    d = ImageDraw.Draw(img)
    rng = np.random.default_rng(7)
    for _ in range(60):
        x, y = rng.integers(0, 512, 2)
        l = rng.integers(30, 200)
        ang = rng.random() * math.pi
        d.line((x, y, x + math.cos(ang) * l, y + math.sin(ang) * l), fill=(200, 215, 235, 14), width=1)
    return img

def ui_panel_scratches():
    img = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    rng = np.random.default_rng(9)
    for _ in range(90):
        x, y = rng.integers(0, 512, 2)
        l = rng.integers(40, 260)
        ang = rng.random() * math.pi
        d.line((x, y, x + math.cos(ang) * l, y + math.sin(ang) * l), fill=(220, 232, 248, 26), width=1)
    return img.filter(ImageFilter.GaussianBlur(0.6))

def ui_slot_frame(active):
    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    edge = (232, 120, 52, 255) if active else (176, 196, 218, 190)
    fill = (30, 22, 14, 130) if active else (18, 26, 36, 150)
    d.rounded_rectangle((8, 8, 248, 248), radius=26, fill=fill, outline=edge, width=6)
    d.rounded_rectangle((22, 22, 234, 234), radius=18, outline=(255, 255, 255, 26), width=2)
    return img

def ui_button(accent):
    img = Image.new("RGBA", (256, 128), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if accent:
        for y in range(12, 116):
            t = (y - 12) / 104
            d.line((14, y, 242, y), fill=(int(232 - 60 * t), int(133 - 50 * t), int(58 - 20 * t), 255))
        d.rounded_rectangle((12, 10, 244, 118), radius=18, outline=(255, 190, 120, 220), width=3)
    else:
        for y in range(12, 116):
            t = (y - 12) / 104
            d.line((14, y, 242, y), fill=(int(36 + 14 * t), int(44 + 14 * t), int(54 + 14 * t), 235))
        d.rounded_rectangle((12, 10, 244, 118), radius=18, outline=(170, 190, 214, 160), width=3)
    return img

def ui_divider():
    img = Image.new("RGBA", (512, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for x in range(512):
        a = int(170 * math.sin(math.pi * x / 512) ** 0.7)
        d.line((x, 6, x, 10), fill=(190, 214, 238, a))
    return img

def ui_logo():
    img = Image.new("RGBA", (2048, 1024), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    font = None
    for fp in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
               "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"):
        if os.path.exists(fp):
            font = ImageFont.truetype(fp, 210)
            break
    text, sub = "WINTER FOREST", "TAIGA SURVIVAL"
    if font:
        tw = d.textlength(text, font=font)
        x = (2048 - tw) / 2
        d.text((x + 10, 380 + 12), text, font=font, fill=(10, 20, 32, 220))
        d.text((x, 380), text, font=font, fill=(228, 238, 250, 255))
        # frost stroke
        d.text((x - 3, 377), text, font=font, outline=(120, 180, 240, 120), width=3)
        f2 = ImageFont.truetype(font.path, 64) if hasattr(font, "path") else font
        sw = d.textlength(sub, font=f2)
        d.text(((2048 - sw) / 2, 640), sub, font=f2, fill=(140, 190, 235, 230))
    else:
        d.rectangle((300, 420, 1748, 500), fill=(228, 238, 250, 255))
        d.rectangle((700, 560, 1348, 610), fill=(140, 190, 235, 230))
    # snow caps along the top of the text
    rng = np.random.default_rng(3)
    for _ in range(140):
        x = rng.integers(300, 1750)
        y = rng.integers(370, 470)
        r = rng.integers(3, 12)
        d.ellipse((x, y, x + r, y + r), fill=(235, 245, 255, 90))
    return img

def ui_map_bg():
    n0 = tile_noise(1024, 1.5, 301)
    n1 = tile_noise(1024, 1.2, 302)
    t = n0[..., None]
    col = np.array([0.76, 0.68, 0.52]) * (0.8 + 0.35 * t) + np.array([0.35, 0.28, 0.18]) * 0.12 * n1[..., None]
    stain = (n1 > 0.82)[..., None] * 0.16
    col = col * (1 - stain) + np.array([0.45, 0.34, 0.2]) * stain
    return Image.fromarray((np.clip(col, 0, 1) * 255).astype(np.uint8), "RGB").convert("RGBA")

# ---------------- sky ----------------

def clouds(w, h, seed, density, soft):
    n = tile_noise((h, w), 1.7, seed)
    n2 = tile_noise((h, w), 1.15, seed + 1)
    m = np.clip((n * 0.65 + n2 * 0.35 - (1 - density)) / 0.35, 0, 1) ** soft
    a = np.zeros((h, w, 4), np.float32)
    a[..., 0] = 0.96
    a[..., 1] = 0.97
    a[..., 2] = 1.0
    a[..., 3] = m * 235
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA")

def sun_disc():
    s = 512
    yy, xx = np.mgrid[0:s, 0:s]
    r = np.sqrt((xx - s / 2) ** 2 + (yy - s / 2) ** 2) / (s / 2)
    core = np.clip(1 - r * 1.15, 0, 1) ** 0.6
    halo = np.exp(-r * 3.2) * 0.75
    a = np.zeros((s, s, 4), np.float32)
    a[..., 0] = 1.0
    a[..., 1] = 0.92 - 0.25 * r
    a[..., 2] = 0.72 - 0.4 * r
    a[..., 3] = np.clip(core + halo, 0, 1) * 255
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA")

def moon_disc():
    s = 512
    yy, xx = np.mgrid[0:s, 0:s]
    r = np.sqrt((xx - s / 2) ** 2 + (yy - s / 2) ** 2) / (s / 2)
    n = tile_noise(s, 1.4, 501)
    body = np.clip(1 - r * 1.35, 0, 1)
    shade = 0.82 + 0.18 * n
    a = np.zeros((s, s, 4), np.float32)
    a[..., 0] = 0.92 * shade
    a[..., 1] = 0.95 * shade
    a[..., 2] = 1.0 * shade
    a[..., 3] = np.clip(body * 1.6 + np.exp(-r * 3) * 0.35, 0, 1) * 255
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA")

def aurora_band():
    w, h = 2048, 512
    n = tile_noise((h, w), 1.5, 601, aniso=0.25)
    yy = np.linspace(0, 1, h)[:, None]
    band = np.exp(-((yy - 0.55) / 0.22) ** 2)
    m = band * (0.35 + 0.65 * n)
    a = np.zeros((h, w, 4), np.float32)
    a[..., 0] = 0.25 + 0.35 * (1 - yy)
    a[..., 1] = 0.95 - 0.35 * yy
    a[..., 2] = 0.62 + 0.35 * yy
    a[..., 3] = np.clip(m, 0, 1) * 210
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA")

# ---------------- vfx ----------------

def snowflake():
    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for ang in range(0, 360, 60):
        a = math.radians(ang)
        x2, y2 = 128 + math.cos(a) * 108, 128 + math.sin(a) * 108
        d.line((128, 128, x2, y2), fill=(255, 255, 255, 255), width=10)
        for s in (-1, 1):
            bx = 128 + math.cos(a) * 66
            by = 128 + math.sin(a) * 66
            d.line((bx, by, bx + math.cos(a + s * 1.0) * 40, by + math.sin(a + s * 1.0) * 40),
                   fill=(255, 255, 255, 240), width=7)
    return img

def smoke_puff():
    s = 256
    n = tile_noise(s, 1.8, 701)
    yy, xx = np.mgrid[0:s, 0:s]
    r = np.sqrt((xx - s / 2) ** 2 + (yy - s / 2) ** 2) / (s / 2)
    m = np.clip(1 - r * 1.25, 0, 1) ** 1.3 * (0.55 + 0.45 * n)
    a = np.zeros((s, s, 4), np.float32)
    a[..., :3] = 0.72
    a[..., 3] = m * 200
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA")

def ember_particle():
    s = 128
    yy, xx = np.mgrid[0:s, 0:s]
    r = np.sqrt((xx - s / 2) ** 2 + (yy - s / 2) ** 2) / (s / 2)
    a = np.zeros((s, s, 4), np.float32)
    a[..., 0] = 1.0
    a[..., 1] = 0.55 + 0.4 * np.clip(1 - r * 1.4, 0, 1)
    a[..., 2] = 0.12 + 0.5 * np.clip(1 - r * 2, 0, 1)
    a[..., 3] = np.clip((1 - r * 1.15), 0, 1) ** 1.4 * 255
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA")

def glow_soft():
    s = 256
    yy, xx = np.mgrid[0:s, 0:s]
    r = np.sqrt((xx - s / 2) ** 2 + (yy - s / 2) ** 2) / (s / 2)
    a = np.zeros((s, s, 4), np.float32)
    a[..., :3] = 1.0
    a[..., 3] = np.clip(np.exp(-r * 3.4), 0, 1) * 255
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA")

def flame_sheet():
    img = Image.new("RGBA", (1024, 256), (0, 0, 0, 0))
    for f in range(4):
        s = 256
        yy, xx = np.mgrid[0:s, 0:s]
        n = tile_noise(s, 1.4, 801 + f)
        u = xx / s
        v = yy / s
        wob = 0.12 * np.sin(u * 6 + f * 1.7) + 0.1 * n
        body = np.clip(1 - np.abs(u - 0.5 - wob) / 0.32, 0, 1) * np.clip(1 - v * 1.05, 0, 1) ** 0.8
        core = np.clip(1 - np.abs(u - 0.5 - wob * 0.6) / 0.16, 0, 1) * np.clip(1 - v * 1.4, 0, 1)
        a = np.zeros((s, s, 4), np.float32)
        a[..., 0] = 1.0
        a[..., 1] = 0.42 + 0.5 * core
        a[..., 2] = 0.08 + 0.55 * core ** 2
        a[..., 3] = np.clip(body * 1.25, 0, 1) * 255
        frame = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA")
        img.paste(frame, (f * 256, 0), frame)
    return img

def hit_marker():
    img = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for ang in range(0, 360, 45):
        a = math.radians(ang)
        d.line((64 + math.cos(a) * 18, 64 + math.sin(a) * 18,
                64 + math.cos(a) * 56, 64 + math.sin(a) * 56), fill=(255, 255, 255, 255), width=7)
    return img


def main():
    for pth in (GLY, UI, SKY, VFX):
        os.makedirs(pth, exist_ok=True)
    print("glyphs:")
    for name, fn in GLYPHS.items():
        save(glyph_base(fn), os.path.join(GLY, name + ".png"))
    print("ui:")
    save(ui_panel_bg(), os.path.join(UI, "panel_bg.png"))
    save(ui_panel_scratches(), os.path.join(UI, "panel_scratches.png"))
    save(ui_slot_frame(False), os.path.join(UI, "slot_frame.png"))
    save(ui_slot_frame(True), os.path.join(UI, "slot_frame_active.png"))
    save(ui_button(False), os.path.join(UI, "button_normal.png"))
    save(ui_button(True), os.path.join(UI, "button_accent.png"))
    save(ui_divider(), os.path.join(UI, "divider_line.png"))
    save(ui_logo(), os.path.join(UI, "logo_winter_forest.png"))
    save(ui_map_bg(), os.path.join(UI, "map_bg_parchment.png"))
    print("sky:")
    save(clouds(2048, 1024, 401, 0.55, 1.1), os.path.join(SKY, "clouds_high.png"))
    save(clouds(2048, 1024, 402, 0.62, 1.35), os.path.join(SKY, "clouds_low.png"))
    save(sun_disc(), os.path.join(SKY, "sun_disc.png"))
    save(moon_disc(), os.path.join(SKY, "moon_disc.png"))
    save(aurora_band(), os.path.join(SKY, "aurora_band.png"))
    print("vfx:")
    save(snowflake(), os.path.join(VFX, "snowflake.png"))
    save(smoke_puff(), os.path.join(VFX, "smoke_puff.png"))
    save(ember_particle(), os.path.join(VFX, "ember_particle.png"))
    save(glow_soft(), os.path.join(VFX, "glow_soft.png"))
    save(flame_sheet(), os.path.join(VFX, "flame_sheet.png"))
    save(hit_marker(), os.path.join(VFX, "hit_marker.png"))
    print("GLYPHS+UI+SKY+VFX DONE")


if __name__ == "__main__":
    main()
