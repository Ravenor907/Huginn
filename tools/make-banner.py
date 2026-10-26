#!/usr/bin/env python3
"""Draws docs/banner.png: Huginn's gold raven and Muninn's silver one facing each other.
Needs the fonts from tools/get-fonts.sh (CI fetches them). Usage: make-banner.py OUT.png"""
import sys
from PIL import Image, ImageDraw, ImageFont, ImageOps

W, H = 1600, 520
c = Image.new("RGB", (W, H), (16, 17, 18))
d = ImageDraw.Draw(c)
gold = Image.open("qml/assets/raven-gold.png").convert("RGBA").resize((340, 340), Image.LANCZOS)
silver = ImageOps.mirror(Image.open("site/raven.png").convert("RGBA").resize((340, 340), Image.LANCZOS))  # faces left, toward the middle
c.paste(gold, (150, 70), gold)
c.paste(silver, (W - 150 - 340, 70), silver)
title = ImageFont.truetype("fonts/Cinzel.ttf", 76)
try:
    title.set_variation_by_axes([700])
except Exception:
    pass
runes = ImageFont.truetype("fonts/NotoSansRunic-Regular.ttf", 34)
body = ImageFont.truetype("fonts/Barlow-Medium.ttf", 28)

def centered(y, text, font, fill, spacing=0):
    w = sum(d.textlength(ch, font=font) for ch in text) + spacing * (len(text) - 1)
    x = (W - w) / 2
    for ch in text:
        d.text((x, y), ch, font=font, fill=fill)
        x += d.textlength(ch, font=font) + spacing

centered(150, "HUGINN", title, (194, 162, 94), 10)
centered(245, "& MUNINN", title, (200, 205, 211), 10)
d.line([(600, 350), (1000, 350)], fill=(44, 47, 51), width=2)
centered(368, "ᚺᚢᚷᛁᚾᚾ · ᛗᚢᚾᛁᚾᚾ", runes, (142, 137, 127), 8)
centered(432, "Thought and Memory: private messaging and file tools for handheld and phone", body, (156, 151, 141))
c.save(sys.argv[1] if len(sys.argv) > 1 else "docs/banner.png", optimize=True)
