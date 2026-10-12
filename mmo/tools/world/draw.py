"""Draws the map plans mmo/tools/world/plan-<n>.json (made by `dotnet run --project mmo/tools/world`)
as pictures plan-<n>.png next to them: ground, paths, buildings, trees and rocks, people (yellow),
monsters (red), exits (white), the start (gold ring). For looking at a map without Unity."""
import glob, json, math, os, sys
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
PX = 2.0  # pixels a metre
GROUND = [(96, 128, 64), (52, 84, 44), (124, 104, 72), (196, 176, 120), (70, 84, 60),
          (128, 128, 128), (140, 104, 64), (150, 130, 100), (52, 92, 150)]
PROP = {"tree": (24, 56, 24), "tree-dead": (70, 56, 40), "bush": (60, 110, 50), "rock": (110, 110, 110),
        "grave": (180, 180, 170), "barrel": (110, 70, 40), "crates": (130, 90, 50), "sign": (255, 255, 255)}
BUILD = {"home": (150, 90, 60), "shop": (200, 160, 60), "tavern": (190, 100, 40), "hall": (170, 170, 200),
         "barn": (120, 90, 60), "house": (160, 120, 90)}


def main():
    font = None
    for f in ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",):
        if os.path.exists(f):
            font = ImageFont.truetype(f, 11)
    for path in sorted(glob.glob(os.path.join(HERE, "plan-*.json"))):
        p = json.load(open(path, encoding="utf-8"))
        x0, z0, x1, z1 = p["bounds"]
        W, H = int((x1 - x0) * PX), int((z1 - z0) * PX)
        img = Image.new("RGB", (W, H))
        d = ImageDraw.Draw(img)
        T = lambda x, z: ((x - x0) * PX, (z1 - z) * PX)
        c = p["cell"]
        for j, row in enumerate(p["ground"]):
            for i, s in enumerate(row):
                a = T(x0 + i * c, z0 + (j + 1) * c)
                d.rectangle([a, (a[0] + c * PX, a[1] + c * PX)], fill=GROUND[int(s)])
        for ax, az, bx, bz, w, s in p["paths"]:
            d.line([T(ax, az), T(bx, bz)], fill=GROUND[s], width=max(1, int(w * PX)))
        for pl in p["places"]:
            x, z = T(pl["x"], pl["z"])
            r = 6 * PX
            d.ellipse([x - r, z - r, x + r, z + r], fill=GROUND[5] if pl["kind"] in ("Town", "Pier") else GROUND[2])
        for b in p["buildings"]:
            ang = math.radians(b["rot"])
            fwd = (math.sin(ang), math.cos(ang)); side = (math.cos(ang), -math.sin(ang))
            pts = []
            for sx, sz in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
                pts.append(T(b["x"] + side[0] * sx * b["w"] / 2 + fwd[0] * sz * b["d"] / 2,
                             b["z"] + side[1] * sx * b["w"] / 2 + fwd[1] * sz * b["d"] / 2))
            d.polygon(pts, fill=BUILD.get(b["kind"], (150, 150, 150)), outline=(40, 30, 20))
            if b["name"]:
                dx, dz = T(*b["door"])
                d.rectangle([dx - 2, dz - 2, dx + 2, dz + 2], fill=(255, 220, 120))
        for k, x, z, s in p["props"]:
            x, z = T(x, z)
            r = (2.2 if k in ("tree", "tree-dead") else 1.2) * s * PX
            d.ellipse([x - r, z - r, x + r, z + r], fill=PROP.get(k, (255, 0, 255)))
        for name, x, z in p["npcs"]:
            x, z = T(x, z)
            d.ellipse([x - 3, z - 3, x + 3, z + 3], fill=(255, 230, 40))
        for t, x, z, n in p["spawns"]:
            x, z = T(x, z)
            d.ellipse([x - 4, z - 4, x + 4, z + 4], outline=(255, 40, 40), width=2)
        for name, x, z in p["exits"]:
            x, z = T(x, z)
            d.rectangle([x - 3, z - 3, x + 3, z + 3], fill=(255, 255, 255))
        sx, sz = T(*p["start"])
        d.ellipse([sx - 8, sz - 8, sx + 8, sz + 8], outline=(255, 200, 0), width=3)
        if font and "--names" in sys.argv:
            for pl in p["places"]:
                x, z = T(pl["x"], pl["z"])
                d.text((x + 6, z - 6), pl["name"], fill=(255, 255, 255), font=font)
            for b in p["buildings"]:
                if b["name"]:
                    x, z = T(b["x"], b["z"])
                    d.text((x - 20, z), b["name"], fill=(255, 255, 200), font=font)
        out = path[:-5] + ".png"
        img.save(out)
        print(out, W, H)


main()
