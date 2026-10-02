"""Prints each PNG of a folder (ScreenshotTest) as a small JPEG in a CI annotation: data:image/jpeg;base64,…"""
import base64, glob, io, os, sys
from PIL import Image

for path in sorted(glob.glob(os.path.join(sys.argv[1], "*.png"))):
    im = Image.open(path).convert("RGB")
    w = 300
    im = im.resize((w, int(im.height * w / im.width)), Image.LANCZOS)
    for q in (60, 45, 30):
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=q)
        data = base64.b64encode(buf.getvalue()).decode()
        if len(data) < 60000:
            break
    print(f"::notice title=Screen {os.path.basename(path)}::data:image/jpeg;base64,{data}")
