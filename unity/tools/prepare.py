"""Copies the game's pictures and fonts into the Unity project before a build (unity/Assets/Resources).

Unity does not read .webp, so pictures become PNG/JPG:
  - monsters and NPCs (content/art/mobs, npcs) → Art/<dir>/<key>.png: the full-body figure from
    content/art/figures/<key>.png when there is one (transparent, up to 768×1024), otherwise the portrait
    at 384×384 with a soft round alpha edge — a stand-in until the figure is drawn (owner 06.10);
  - places (content/art/locations) → Art/locations/<key>.jpg, 1280×720;
  - items (content/art/items) → Art/items/<id with '.' → '_'>.jpg, 128×128 (Resources names keep no dots).
Fonts come from the iOS app (engine/iosApp/Tz/Fonts).
Run from anywhere: python3 unity/tools/prepare.py
"""
import os, shutil, sys
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
ART = os.path.join(ROOT, 'content', 'art')
OUT = os.path.join(ROOT, 'unity', 'Assets', 'Resources')

def fresh(path):
    shutil.rmtree(path, ignore_errors=True)
    os.makedirs(path)

def figure_mask(size):
    """Opaque in the middle, fading out towards a circle's edge."""
    m = Image.new('L', (size, size), 0)
    d = ImageDraw.Draw(m)
    pad = size * 0.06
    d.ellipse((pad, pad, size - pad, size - pad), fill=255)
    return m.filter(ImageFilter.GaussianBlur(size * 0.06))

def main():
    figure = 384
    mask = figure_mask(figure)
    count = 0
    drawn = os.path.join(ART, 'figures')   # full-body figures with transparency (unity/PROMPTS.md) replace portraits
    for d in ('mobs', 'npcs'):
        out = os.path.join(OUT, 'Art', d); fresh(out)
        for f in sorted(os.listdir(os.path.join(ART, d))):
            if not f.endswith('.webp'): continue
            full = os.path.join(drawn, f[:-5] + '.png')
            if os.path.exists(full):
                im = Image.open(full).convert('RGBA')
                im.thumbnail((768, 1024), Image.LANCZOS)
            else:
                im = Image.open(os.path.join(ART, d, f)).convert('RGB').resize((figure, figure), Image.LANCZOS).convert('RGBA')
                im.putalpha(mask)
            im.save(os.path.join(out, f[:-5] + '.png'), optimize=True); count += 1
    out = os.path.join(OUT, 'Art', 'locations'); fresh(out)
    for f in sorted(os.listdir(os.path.join(ART, 'locations'))):
        if not f.endswith('.webp'): continue
        Image.open(os.path.join(ART, 'locations', f)).convert('RGB').resize((1280, 720), Image.LANCZOS) \
            .save(os.path.join(out, f[:-5] + '.jpg'), quality=85); count += 1
    out = os.path.join(OUT, 'Art', 'items'); fresh(out)
    for f in sorted(os.listdir(os.path.join(ART, 'items'))):
        if not f.endswith('.webp'): continue
        Image.open(os.path.join(ART, 'items', f)).convert('RGB').resize((128, 128), Image.LANCZOS) \
            .save(os.path.join(out, f[:-5].replace('.', '_') + '.jpg'), quality=85); count += 1
    out = os.path.join(OUT, 'Art', 'brand'); fresh(out)
    Image.open(os.path.join(ART, 'brand', 'icon-1024.png')).convert('RGBA').resize((512, 512), Image.LANCZOS).save(os.path.join(out, 'icon.png'))
    fonts = os.path.join(OUT, 'Fonts'); fresh(fonts)
    for f in ('alegreya_sans_regular.ttf', 'alegreya_sans_bold.ttf', 'cormorant_sc_bold.ttf'):
        shutil.copy(os.path.join(ROOT, 'engine', 'iosApp', 'Tz', 'Fonts', f), fonts)
    print(f'{count} pictures and 3 fonts → {OUT}')

if __name__ == '__main__':
    sys.exit(main())
