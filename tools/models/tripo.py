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
import io, json, os, sys, threading, time, urllib.request, urllib.error, uuid
from concurrent.futures import ThreadPoolExecutor
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
MODELS = os.path.join(ROOT, "content", "art", "models")
BASE = os.environ.get("TRIPO_BASE", "https://openapi.tripo3d.ai/v3")
KEY = os.environ.get("TRIPO_API_KEY", "")
sys.path.insert(0, HERE)
import prepare  # noqa: E402

spent = []
# Tripo runs 3 animation tasks at once per account (rigs and retargets share it).
WORKERS = int(os.environ.get("TRIPO_WORKERS", "3"))
# The rig version by skeleton: v1.0 for humanoids (presets preset:biped:*), v2.5 for the others.
RIG_MODELS = {"biped": "v1.0-20240301"}
DETAILS = []   # full texts of the failures, for the run's annotation

# For a portrait: the whole figure, as the image-to-3D generator likes it (by the skeleton it will get).
COMPLETE_TAIL = ", the whole figure visible with margin, plain light grey background, no shadow, same painted style and colours"
COMPLETE_PROMPTS = {
    "quadruped": "full body of the same animal from nose to tail, standing on all four legs, three-quarter side view" + COMPLETE_TAIL,
    "avian": "full body of the same bird, standing with wings slightly open, three-quarter front view" + COMPLETE_TAIL,
}
COMPLETE_PROMPT = ("full body of the same character from head to feet, standing in a neutral pose facing the viewer, "
                   "arms slightly away from the body, the whole figure visible with margin, plain light grey background, "
                   "no shadow, same dark fantasy painted style and colours")


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
    for attempt in range(8):
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                res = json.loads(r.read())
            break
        except urllib.error.HTTPError as e:
            text = e.read()[:500]
            # Too many tasks at once or a passing server error: wait and again.
            if (e.code == 429 or e.code >= 500) and attempt < 7:
                time.sleep(15 * (attempt + 1)); continue
            raise RuntimeError(f"{method} {path}: HTTP {e.code} {text!r}")
        except (urllib.error.URLError, TimeoutError) as e:
            if attempt < 7:
                time.sleep(10); continue
            raise RuntimeError(f"{method} {path}: {e}")
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
            print(f"  {threading.current_thread().name} {what}: {task} done in {time.time() - t0:.0f} s, credits {c}")
            return d
        if st in ("failed", "cancelled", "banned", "expired", "unknown"):
            raise RuntimeError(f"{what}: task {task} {st}: {json.dumps(d)[:1500]}")
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


def save_tasks(raw, **ids):
    """The task ids of an order in raw/<name>/tasks.json (the artifact keeps them: a rig can be animated later)."""
    p = os.path.join(raw, "tasks.json")
    d = json.load(open(p)) if os.path.exists(p) else {}
    d.update(ids)
    json.dump(d, open(p, "w"))


def order(name, o, index):
    raw = os.path.join(HERE, "raw", name)
    os.makedirs(raw, exist_ok=True)
    print(f"{name}: {o['image']}")
    if o.get("rig_task"):
        # Rigged already (its task given): only the animations.
        entry = index.get(name, {"for": o.get("for", []), "front": o.get("front", 0), "source": f"Tripo API, {o['image']}"})
        entry["rig"] = o["rig"]
        animate(name, o, o["rig_task"], raw, entry)
        index[name] = entry
        return
    img = Image.open(os.path.join(ROOT, o["image"])).convert("RGBA")
    buf = io.BytesIO(); img.save(buf, "PNG")
    token = call("POST", "/files", files={"file": (name + ".png", buf.getvalue(), "image/png")})["file_token"]
    source = token
    if o.get("complete"):
        # A portrait → the whole figure standing on a plain background (the generator's "character_completion"),
        # then the model from that picture (its task id is the model's input).
        body = {"input": token, "template": "character_completion", "model": o.get("image_model", "seedream_v5"),
                "prompt": o.get("complete_prompt", COMPLETE_PROMPTS.get(o.get("rig"), COMPLETE_PROMPT))}
        img_task = call("POST", "/generation/image-to-image", body)["task_id"]
        d = wait(img_task, "full figure")
        for key, url in [(k, v) for k, v in (d.get("output") or {}).items() if isinstance(v, str) and v.startswith("http")]:
            download(url, os.path.join(raw, name + "-figure.png"))
            break
        source = img_task
        save_tasks(raw, figure=img_task)
    body = {"input": source, "model": o.get("model", "v3.1-20260211"), "texture": True, "pbr": True,
            "texture_quality": o.get("texture_quality", "detailed"), "orientation": o.get("orientation", "align_image")}
    if o.get("faces"): body["face_limit"] = int(o["faces"])
    if o.get("smart_low_poly"): body["smart_low_poly"] = True
    task = call("POST", "/generation/image-to-model", body)["task_id"]
    save_tasks(raw, model=task)
    d = wait(task, "model")
    links = urls(d.get("output", {}))
    if not links: raise RuntimeError(f"no model in {json.dumps(d)[:500]}")
    model_path = download(dict(links).get("pbr_model", links[0][1]), os.path.join(raw, name + ".glb"))

    entry = {"for": o.get("for", []), "front": o.get("front", 0), "source": f"Tripo API, {o['image']}, task {task}"}
    for k in ("size", "height", "people"):
        if k in o: entry[k] = o[k]
    if o.get("rig"):
        check = call("POST", "/animations/rig-check", {"input": task})
        print(f"  rig check: {json.dumps(check)[:300]}")
        rig_task = call("POST", "/animations/rig", {"input": task, "model": o.get("rig_model", RIG_MODELS.get(o["rig"], "v2.5-20260210")),
                                                     "rig_type": o["rig"], "spec": o.get("spec", "tripo"), "out_format": "glb"})["task_id"]
        save_tasks(raw, rig=rig_task)
        d = wait(rig_task, "rig")
        rigged = download(urls(d.get("output", {}))[0][1], os.path.join(raw, name + "-rig.glb"))
        prepare.shrink_textures(*prepare.read_glb(rigged), os.path.join(MODELS, name + ".glb"))
        entry["rig"] = o["rig"]
        index[name] = entry   # kept even when an animation fails: the rigged model is there already
        print(f"  {name} rig task {rig_task} (give it as \"rig_task\" to order only animations later)")
        try:
            animate(name, o, rig_task, raw, entry)
        except Exception:
            # A figure that cannot move is not kept (the order stays to be made again).
            index.pop(name, None)
            if os.path.exists(os.path.join(MODELS, name + ".glb")): os.remove(os.path.join(MODELS, name + ".glb"))
            raise
    else:
        import subprocess
        subprocess.run([sys.executable, os.path.join(HERE, "prepare.py"), model_path, name], check=True)
    index[name] = entry


def animate(name, o, rig_task, raw, entry):
    """Each animation by its own retarget task, so that one the rig cannot do does not lose the others."""
    entry["animations"] = []
    bad = []
    for preset in o.get("animations", []):
        anim = preset.split(":")[-1]
        try:
            ret = call("POST", "/animations/retarget", {"input": rig_task, "animation": preset, "out_format": "glb",
                                                         "bake_animation": True, "animate_in_place": True})["task_id"]
            d = wait(ret, "animation " + preset)
            links = urls(d.get("output", {}))
            if not links: raise RuntimeError(f"no model in {json.dumps(d)[:800]}")
            p = download(links[0][1], os.path.join(raw, f"{name}@{anim}.glb"))
            prepare.shrink_textures(*prepare.read_glb(p), os.path.join(MODELS, f"{name}@{anim}.glb"))
            entry["animations"].append(anim)
        except Exception as e:
            print(f"  {name} {preset}: FAILED {str(e)[:1500]}")
            with LOCK: DETAILS.append(f"{name} {preset}: {str(e)[:2500]}")
            bad.append(preset)
    if bad and not entry["animations"] and o.get("rig") == "biped":
        raise RuntimeError("no animation came: " + ", ".join(bad))


LOCK = threading.Lock()


def main():
    if not KEY:
        sys.exit("no TRIPO_API_KEY")
    orders = json.load(open(os.path.join(MODELS, "orders.json"), encoding="utf-8"))
    index_path = os.path.join(MODELS, "models.json")
    index = json.load(open(index_path, encoding="utf-8"))
    failed = []
    stop = threading.Event()
    todo = [(n, o) for n, o in orders.items() if not n.startswith("_") and isinstance(o, dict)
            and (o.get("redo") or not os.path.exists(os.path.join(MODELS, n + ".glb")))]
    print(f"{len(todo)} to order, {WORKERS} at a time")

    def one(item):
        name, o = item
        if stop.is_set(): return
        threading.current_thread().name = name
        mine = {name: index[name]} if name in index else {}
        try:
            order(name, o, mine)
        except Exception as e:
            print(f"{name}: FAILED {e}", flush=True)
            with LOCK: DETAILS.append(f"{name}: {str(e)[:2500]}")
            with LOCK: failed.append(name)
            if "2010" in str(e):
                print("no credits left on the API balance: stopping", flush=True)
                stop.set()
            if name not in mine: return
        with LOCK:
            index.update(mine)
            json.dump(index, open(index_path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    # The first order of each skeleton alone first: when it gets no animation, the others of that skeleton
    # are not ordered (they would fail the same and waste the model and the rig).
    probes, seen = [], set()
    for n, o in todo:
        if o.get("rig") and o["rig"] not in seen:
            seen.add(o["rig"]); probes.append((n, o))
    with ThreadPoolExecutor(WORKERS) as pool:
        list(pool.map(one, probes))
    bad_rigs = {o["rig"] for n, o in probes if any(d.startswith(n + ": no animation") for d in DETAILS)}
    rest = [(n, o) for n, o in todo if (n, o) not in probes and o.get("rig") not in bad_rigs]
    for n, o in todo:
        if o.get("rig") in bad_rigs and (n, o) not in probes: print(f"{n}: not ordered, its skeleton {o['rig']} failed on {[p for p, q in probes if q.get('rig') == o['rig']][0]}")
    with ThreadPoolExecutor(WORKERS) as pool:
        list(pool.map(one, rest))
    total = sum(c for _, _, c in spent if isinstance(c, (int, float)))
    print(f"done: {len(spent)} tasks, {total} credits; failed: {', '.join(failed) or 'none'}")
    with open(os.path.join(HERE, "..", "..", "tripo-failures.txt"), "w") as f:
        f.write("\n".join(DETAILS[:6]))
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
