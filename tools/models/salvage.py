"""Takes the models an earlier run of models.yml downloaded but did not commit (its artifact tripo-raw, put by the
workflow into tools/models/raw) into content/art/models, the same way tripo.py does after ordering:
  - a static model (the order has no "rig"): raw/<name>/<name>.glb → prepare.py;
  - a rigged one: raw/<name>/<name>-rig.glb and its animations raw/<name>/<name>@<animation>.glb, textures made
    small; a biped without a single animation is left out (it is ordered again: a standing figure without
    moves is no use), the others keep their rig alone (the game moves them).
Its task ids (raw/<name>/tasks.json, written by tripo.py since 13.10) go with it when they are there.
Nothing is ordered, no credits spent. Run: python3 tools/models/salvage.py <run id>."""
import json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
MODELS = os.path.join(ROOT, "content", "art", "models")
RAW = os.path.join(HERE, "raw")
sys.path.insert(0, HERE)
import prepare  # noqa: E402


def main():
    run = sys.argv[1] if len(sys.argv) > 1 else "?"
    orders = json.load(open(os.path.join(MODELS, "orders.json"), encoding="utf-8"))
    index_path = os.path.join(MODELS, "models.json")
    index = json.load(open(index_path, encoding="utf-8"))
    taken, skipped = [], []
    for name, o in orders.items():
        if name.startswith("_") or not isinstance(o, dict): continue
        if os.path.exists(os.path.join(MODELS, name + ".glb")) and not o.get("redo"): continue
        raw = os.path.join(RAW, name)
        if not os.path.isdir(raw): continue
        tasks = {}
        tp = os.path.join(raw, "tasks.json")
        if os.path.exists(tp): tasks = json.load(open(tp))
        entry = {"for": o.get("for", []), "front": o.get("front", 0),
                 "source": f"Tripo API, {o['image']}" + (f", task {tasks['model']}" if tasks.get("model") else "") + f" (run {run})"}
        for k in ("size", "height", "people"):
            if k in o: entry[k] = o[k]
        if o.get("rig"):
            rigged = os.path.join(raw, name + "-rig.glb")
            if not os.path.exists(rigged):
                skipped.append(f"{name} (no rig)"); continue
            anims = [a.split(":")[-1] for a in o.get("animations", [])
                     if os.path.exists(os.path.join(raw, f"{name}@{a.split(':')[-1]}.glb"))]
            if not anims and o["rig"] == "biped":
                skipped.append(f"{name} (biped without animations)"); continue
            prepare.shrink_textures(*prepare.read_glb(rigged), os.path.join(MODELS, name + ".glb"))
            for a in anims:
                prepare.shrink_textures(*prepare.read_glb(os.path.join(raw, f"{name}@{a}.glb")), os.path.join(MODELS, f"{name}@{a}.glb"))
            entry["rig"] = o["rig"]
            entry["animations"] = anims
            taken.append(f"{name} ({o['rig']}: {', '.join(anims) or 'no animations'})")
        else:
            model = os.path.join(raw, name + ".glb")
            if not os.path.exists(model):
                skipped.append(f"{name} (no model)"); continue
            subprocess.run([sys.executable, os.path.join(HERE, "prepare.py"), model, name], check=True)
            taken.append(name)
        index[name] = entry
    json.dump(index, open(index_path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"salvaged from run {run}: {len(taken)} — {', '.join(taken) or 'none'}")
    print(f"not taken: {', '.join(skipped) or 'none'}")


if __name__ == "__main__":
    main()
