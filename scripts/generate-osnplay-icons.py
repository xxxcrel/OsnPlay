#!/usr/bin/env python3
"""Render bitmap launcher icons and the Lynk & Co CarPlay return icon (requires Pillow)."""
import argparse
from pathlib import Path
import shutil
from statistics import median
from PIL import Image, ImageDraw, ImageOps

parser = argparse.ArgumentParser()
parser.add_argument('--logo', type=Path, required=True)
parser.add_argument('--carplay-icon', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
resources = root / 'mobile/src/osnplay/res'
assets = root / 'asset/osnplay'
assets.mkdir(parents=True, exist_ok=True)
if args.logo.resolve() != (assets / 'lynkco-logo.png').resolve():
    shutil.copy2(args.logo, assets / 'lynkco-logo.png')

icon_source = assets / 'apple-carplay-icon.jpg'
if args.carplay_icon.resolve() != icon_source.resolve():
    shutil.copy2(args.carplay_icon, icon_source)
width, height = 768, 432
original = Image.open(args.carplay_icon).convert('RGBA').resize((288, 288), Image.Resampling.LANCZOS)
background = Image.new('RGBA', (width, height))
draw = ImageDraw.Draw(background)
# Extend each row of Apple's green gradient into the padding. Exclude the white
# glyph/corners, keeping the central source image and its original proportions.
greens = []
for y in range(original.height):
    samples = [original.getpixel((x, y))[:3] for x in range(original.width)]
    samples = [pixel for pixel in samples if pixel[1] > pixel[0] + 30 and pixel[1] > pixel[2] + 30]
    if not samples:
        raise ValueError(f'No CarPlay green found on source row {y}')
    greens.append(tuple(int(median(pixel[channel] for pixel in samples)) for channel in range(3)))
top = (height - original.height) // 2
for y in range(height):
    # Skip the JPEG's anti-aliased outer white edge when extending its colors.
    color = greens[min(max(y - top, 4), original.height - 5)]
    draw.line((0, y, width - 1, y), fill=(*color, 255))
# The central rectangle contains the complete unmodified glyph but excludes
# the source square's rounded white corners, leaving a seamless green card.
glyph_box = (40, 34, 248, 254)
background.alpha_composite(original.crop(glyph_box), ((width - 288) // 2 + glyph_box[0], top + glyph_box[1]))
for density, pixels in [('mdpi', 96), ('hdpi', 144), ('xhdpi', 192), ('xxhdpi', 288), ('xxxhdpi', 384)]:
    folder = resources / f'mipmap-{density}'
    folder.mkdir(parents=True, exist_ok=True)
    for name in ('ic_osnplay', 'ic_osnplay_round'):
        background.resize((pixels, pixels * 9 // 16), Image.Resampling.LANCZOS).save(folder / f'{name}.png')

logo = Image.open(args.logo).convert('RGBA')
logo = logo.crop(logo.getchannel('A').getbbox())
# Preserve the official mark shape, rendered white for the dark CarPlay icon.
white_mark = Image.new('RGBA', logo.size, 'white')
white_mark.putalpha(logo.getchannel('A'))
white_mark = ImageOps.contain(white_mark, (444, 150), Image.Resampling.LANCZOS)
return_icon = Image.new('RGBA', (512, 512), (19, 23, 29, 255))
return_draw = ImageDraw.Draw(return_icon)
return_draw.rounded_rectangle((14, 14, 498, 498), radius=92, outline=(92, 98, 108, 255), width=3)
return_icon.alpha_composite(white_mark, ((512 - white_mark.width) // 2, (512 - white_mark.height) // 2))
raw = resources / 'raw'
raw.mkdir(parents=True, exist_ok=True)
return_icon.save(raw / 'ic_car_home.png')
preview = background.resize((512, 288), Image.Resampling.LANCZOS)
preview.save(assets / 'osnplay-launcher-preview.png')
banner = resources / 'drawable-nodpi'
banner.mkdir(parents=True, exist_ok=True)
preview.save(banner / 'osnplay_banner.png')
return_icon.save(assets / 'lynkco-carplay-preview.png')
print('Generated rectangular CarPlay-green launcher icons/banner and the Lynk & Co return icon.')
