"""Prints each PNG of a folder (ScreenshotTest) as a small JPEG in CI annotations.

An annotation keeps about 4000 characters and a step shows at most 10 notices,
so each picture goes in base64 pieces: «Screen <name> i/n». Join them in order."""
import base64, glob, io, os, sys
from PIL import Image

CHUNK = 3900
budget = 9  # notices left in this step (one is the Tests summary of another step)
for path in sorted(glob.glob(os.path.join(sys.argv[1], "*.png"))):
    im = Image.open(path).convert("RGB")
    w = 270
    im = im.resize((w, int(im.height * w / im.width)), Image.LANCZOS)
    for q in (55, 40, 30, 20):
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=q, optimize=True)
        data = base64.b64encode(buf.getvalue()).decode()
        if len(data) <= CHUNK * budget:
            break
    parts = [data[i:i + CHUNK] for i in range(0, len(data), CHUNK)]
    if len(parts) > budget:
        print(f"::warning title=Screen {os.path.basename(path)}::too big for annotations")
        continue
    for i, p in enumerate(parts):
        print(f"::notice title=Screen {os.path.basename(path)} {i + 1}/{len(parts)}::{p}")
    budget -= len(parts)
