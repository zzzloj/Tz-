"""Copies the game's pictures and fonts into the Unity project before a build (unity/Assets/Resources).

Unity does not read .webp, so pictures become PNG/JPG:
  - monsters and NPCs (content/art/mobs, npcs) → Art/<dir>/<key>.png: the full-body figure from
    content/art/figures/<key>.png when there is one (transparent, up to 768×1024), otherwise the portrait
    at 384×384 with a soft round alpha edge — a stand-in until the figure is drawn (owner 06.10);
  - places (content/art/locations) → Art/locations/<key>.jpg, 1280×720;
  - items (content/art/items) → Art/items/<id with '.' → '_'>.jpg, 128×128 (Resources names keep no dots).
Fonts come from the iOS app (engine/iosApp/Tz/Fonts).
The map of places (content/locations: name and exits of each) goes to Data/world.json for the minimap.
The Android adaptive icon (owner 07.10: no white rim from the launcher) is made of two layers in
unity/Assets/Icons: the background is the icon's own dark tone, the foreground the icon in the safe zone.
Run from anywhere: python3 unity/tools/prepare.py
"""
import json, os, shutil, sys
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

def topdown(count):
    """The top-down view (owner 07.10, unity/PROMPTS-TOPDOWN.md) → Resources/TopDown:
    ground textures as PNG bytes (World.cs reads their pixels to bake the ground of a place),
    objects and figures as pictures, and meta.json with where the wall bases, the front corner
    and the feet are in each picture."""
    td = os.path.join(ART, 'topdown')
    out = os.path.join(OUT, 'TopDown'); fresh(out)
    meta = {'objects': {}, 'figures': {}}
    with open(os.path.join(td, 'objects.json'), encoding='utf-8') as fh: sizes = json.load(fh)
    os.makedirs(os.path.join(out, 'ground'))
    for f in sorted(os.listdir(os.path.join(td, 'ground'))):
        if not f.endswith('.webp'): continue
        Image.open(os.path.join(td, 'ground', f)).convert('RGB').resize((512, 512), Image.LANCZOS) \
            .save(os.path.join(out, 'ground', f[:-5] + '.bytes'), format='PNG')
    for kind in ('objects', 'figures'):
        os.makedirs(os.path.join(out, kind))
        src = os.path.join(td, kind)
        if not os.path.isdir(src): continue
        for f in sorted(os.listdir(src)):
            if not f.endswith('.webp'): continue
            im = Image.open(os.path.join(src, f)).convert('RGBA')
            im = im.crop(im.getbbox())
            im.thumbnail((1024, 1024), Image.LANCZOS)
            # A square power-of-two canvas, the picture standing on its bottom edge: Unity scales
            # other sizes to a power of two and the picture would be stretched.
            side = 1 << (max(im.size) - 1).bit_length()
            canvas = Image.new('RGBA', (side, side), (0, 0, 0, 0))
            canvas.paste(im, ((side - im.width) // 2, side - im.height))
            im = canvas
            a = im.split()[3].point(lambda v: 255 if v > 128 else 0)
            w, h = im.size
            px = a.load()
            low = max(y for y in range(h) for x in range(0, w, 2) if px[x, y])
            foot = [x for x in range(w) if px[x, low]] or [w // 2]
            key = f[:-5]
            if kind == 'objects':
                band = [x for y in range(int(h * 0.6), h, 3) for x in range(w) if px[x, y]]
                m = {'corner': sum(foot) / len(foot) / w, 'baseL': min(band) / w, 'baseR': max(band) / w}
                m.update({k: v for k, v in sizes.get(key, {}).items()})
            else:
                m = {'foot': sum(foot) / len(foot) / w}
            meta[kind][key] = m
            im.save(os.path.join(out, kind, key + '.png'), optimize=True)
    with open(os.path.join(out, 'meta.json'), 'w', encoding='utf-8') as fh: json.dump(meta, fh, indent=1)


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
    icons = os.path.join(ROOT, 'unity', 'Assets', 'Icons'); fresh(icons)
    src = Image.open(os.path.join(ART, 'brand', 'icon-1024.png')).convert('RGBA')
    Image.new('RGBA', (432, 432), src.getpixel((6, 6))).save(os.path.join(icons, 'adaptive-bg.png'))
    fg = Image.new('RGBA', (432, 432), (0, 0, 0, 0))
    fg.paste(src.resize((288, 288), Image.LANCZOS), (72, 72))   # the 72 dp of 108 every launcher shows
    fg.save(os.path.join(icons, 'adaptive-fg.png'))
    src.resize((432, 432), Image.LANCZOS).save(os.path.join(icons, 'icon.png'))
    data = os.path.join(OUT, 'Data'); fresh(data)
    world = {}
    locs = os.path.join(ROOT, 'content', 'locations')
    for f in sorted(os.listdir(locs)):
        if not f.endswith('.json'): continue
        with open(os.path.join(locs, f), encoding='utf-8') as fh: loc = json.load(fh)
        world[loc['id']] = {'n': loc.get('name', ''), 'e': [[e.get('label', ''), e['target']] for e in loc.get('exits', []) if e.get('target')]}
    with open(os.path.join(data, 'world.json'), 'w', encoding='utf-8') as fh: json.dump(world, fh, ensure_ascii=False, separators=(',', ':'))
    topdown(count)
    fonts = os.path.join(OUT, 'Fonts'); fresh(fonts)
    for f in ('alegreya_sans_regular.ttf', 'alegreya_sans_bold.ttf', 'cormorant_sc_bold.ttf'):
        shutil.copy(os.path.join(ROOT, 'engine', 'iosApp', 'Tz', 'Fonts', f), fonts)
    print(f'{count} pictures, {len(world)} places and 3 fonts → {OUT}')

if __name__ == '__main__':
    sys.exit(main())
