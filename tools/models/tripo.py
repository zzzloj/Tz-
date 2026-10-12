"""Orders 3D models from Tripo's API (https://developers.tripo3d.ai) for the orders in
content/art/models/orders.json and puts the results, made light (prepare.py), into content/art/models.
CI runs it (.github/workflows/models.yml) when orders.json changes on main, with the owner's key in the
repository secret TRIPO_API_KEY; it never runs from the session (no key, no network there).

For every order whose model is not there yet (or has "redo": true):
  1. the picture → PNG → POST /files → file token;
  2. POST /generation/image-to-model (model version, face limit, PBR texture) → task, waited for;
  3. if "rig" is given: rig check, POST /animations/rig (rig type), then POST /animations/retarget with the
     order's animations (preset names of the API), each result downloaded;
  4. the raw files to tools/models/raw/<name>/ (CI keeps them as an artifact), the light ones to
     content/art/models/<name>.glb (and <name>@<animation>.glb), the order's "for" and "front" to models.json.
The credits each task took are printed (the run's annotation shows them)."""
import io, json, os, sys, time, urllib.request, urllib.error, uuid
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
MODELS = os.path.join(ROOT, "content", "art", "models")
BASE = os.environ.get("TRIPO_BASE", "https://openapi.tripo3d.ai/v3")
KEY = os.environ.get("TRIPO_API_KEY", "")
sys.path.insert(0, HERE)
import prepare  # noqa: E402

spent = []


def call(method, path, body=None, files=None):
    url = BASE + path
    headers = {"Authorization": "Bearer " + KEY}
    data = None
    if files:
        boundary = uuid.uuid4().hex
        parts = []
        for field, (fname, blob, mime) in files.items():
            parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; filename=\"{fname}\"\r\nContent-Type: {mime}\r\n\r\n".encode() + blob + b"\r\n")
        data = b"".join(parts) + f"--{boundary}--\r\n".encode()
        headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            res = json.loads(r.read())
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{method} {path}: HTTP {e.code} {e.read()[:500]!r}")
    if res.get("code", 0) != 0:
        raise RuntimeError(f"{method} {path}: {json.dumps(res)[:500]}")
    return res.get("data", res)


def wait(task, what):
    t0 = time.time()
    while True:
        d = call("GET", f"/tasks/{task}")
        st = d.get("status")
        if st == "success":
            c = d.get("credits_consumed") or d.get("consumed_credit") or d.get("credits")
            spent.append((what, task, c))
            print(f"  {what}: {task} done in {time.time() - t0:.0f} s, credits {c}")
            return d
        if st in ("failed", "cancelled", "banned", "expired", "unknown"):
            raise RuntimeError(f"{what}: task {task} {st}: {json.dumps(d)[:500]}")
        if time.time() - t0 > 1200:
            raise RuntimeError(f"{what}: task {task} still {st} after 20 min")
        time.sleep(3)


def urls(output):
    """Every model link in a task's output (model_url, pbr_model, model_urls…)."""
    found = []
    def walk(o, key=""):
        if isinstance(o, dict):
            for k, v in o.items(): walk(v, k)
        elif isinstance(o, list):
            for v in o: walk(v, key)
        elif isinstance(o, str) and o.startswith("http") and "model" in key and "image" not in key:
            found.append((key, o))
    walk(output)
    return found


def download(url, path):
    with urllib.request.urlopen(url, timeout=300) as r, open(path, "wb") as f:
        f.write(r.read())
    return path


def order(name, o, index):
    raw = os.path.join(HERE, "raw", name)
    os.makedirs(raw, exist_ok=True)
    print(f"{name}: {o['image']}")
    img = Image.open(os.path.join(ROOT, o["image"])).convert("RGBA")
    buf = io.BytesIO(); img.save(buf, "PNG")
    token = call("POST", "/files", files={"file": (name + ".png", buf.getvalue(), "image/png")})["file_token"]
    body = {"input": token, "model": o.get("model", "v3.1-20260211"), "texture": True, "pbr": True,
            "texture_quality": o.get("texture_quality", "detailed"), "orientation": o.get("orientation", "align_image")}
    if o.get("faces"): body["face_limit"] = int(o["faces"])
    if o.get("smart_low_poly"): body["smart_low_poly"] = True
    task = call("POST", "/generation/image-to-model", body)["task_id"]
    d = wait(task, "model")
    links = urls(d.get("output", {}))
    if not links: raise RuntimeError(f"no model in {json.dumps(d)[:500]}")
    model_path = download(dict(links).get("pbr_model", links[0][1]), os.path.join(raw, name + ".glb"))

    entry = {"for": o.get("for", []), "front": o.get("front", 0), "source": f"Tripo API, {o['image']}, task {task}"}
    if o.get("rig"):
        check = call("POST", "/animations/rig-check", {"input": task})
        print(f"  rig check: {json.dumps(check)[:300]}")
        rig_task = call("POST", "/animations/rig", {"input": task, "model": o.get("rig_model", "v2.5-20260210"),
                                                     "rig_type": o["rig"], "spec": o.get("spec", "tripo"), "out_format": "glb"})["task_id"]
        d = wait(rig_task, "rig")
        rigged = download(urls(d.get("output", {}))[0][1], os.path.join(raw, name + "-rig.glb"))
        prepare.shrink_textures(*prepare.read_glb(rigged), os.path.join(MODELS, name + ".glb"))
        entry["rig"] = o["rig"]
        anims = o.get("animations", [])
        if anims:
            ret = call("POST", "/animations/retarget", {"input": rig_task, "animations": anims, "out_format": "glb",
                                                         "bake_animation": True, "animate_in_place": True})["task_id"]
            d = wait(ret, "animations")
            links = urls(d.get("output", {}))
            entry["animations"] = []
            for k, (key, url) in enumerate(links):
                anim = anims[k].split(":")[-1] if k < len(anims) else f"a{k}"
                p = download(url, os.path.join(raw, f"{name}@{anim}.glb"))
                prepare.shrink_textures(*prepare.read_glb(p), os.path.join(MODELS, f"{name}@{anim}.glb"))
                entry["animations"].append(anim)
    else:
        import subprocess
        subprocess.run([sys.executable, os.path.join(HERE, "prepare.py"), model_path, name], check=True)
    index[name] = entry


def main():
    if not KEY:
        sys.exit("no TRIPO_API_KEY")
    orders = json.load(open(os.path.join(MODELS, "orders.json"), encoding="utf-8"))
    index_path = os.path.join(MODELS, "models.json")
    index = json.load(open(index_path, encoding="utf-8"))
    failed = []
    for name, o in orders.items():
        if name.startswith("_") or not isinstance(o, dict): continue
        if os.path.exists(os.path.join(MODELS, name + ".glb")) and not o.get("redo"):
            continue
        try:
            order(name, o, index)
        except Exception as e:
            print(f"{name}: FAILED {e}")
            failed.append(name)
        json.dump(index, open(index_path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    total = sum(c for _, _, c in spent if isinstance(c, (int, float)))
    print(f"done: {len(spent)} tasks, {total} credits; failed: {', '.join(failed) or 'none'}")


if __name__ == "__main__":
    main()
