"""Copies the pictures the MMO uses from content/art (webp, which Unity does not read) into
mmo/Assets/Tz/Art as png, before Unity starts (CI: .github/workflows/mmo.yml; needs Pillow).
Ground: content/art/topdown/ground/*.webp → Assets/Tz/Art/Ground/*.png, 512 px (tiles of 4 m)."""
import glob, os
from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
OUT = os.path.join(ROOT, "mmo", "Assets", "Tz", "Art")


def main():
    n = 0
    os.makedirs(os.path.join(OUT, "Ground"), exist_ok=True)
    for f in sorted(glob.glob(os.path.join(ROOT, "content", "art", "topdown", "ground", "*.webp"))):
        im = Image.open(f).convert("RGB").resize((512, 512), Image.LANCZOS)
        im.save(os.path.join(OUT, "Ground", os.path.basename(f)[:-5] + ".png"))
        n += 1
    print(f"art: {n} ground textures")


main()
