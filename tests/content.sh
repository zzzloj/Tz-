#!/bin/sh
# content/ must build, and exporting the built files again must give the same
# JSON: this keeps build.py and export.php (the schema) in step and catches
# content edits the engine format cannot hold.
set -e
root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="${TEST_ROOT:-/tmp/tz-tests}/content"
rm -rf "$tmp"; mkdir -p "$tmp"
python3 "$root/tools/content/build.py" "$root/content" "$tmp/game1"
php -d short_open_tag=On "$root/tools/content/export.php" "$tmp/game1" "$tmp/again" > /dev/null
python3 - "$root/content" "$tmp/again" <<'PY'
import json, pathlib, sys
a, b = map(pathlib.Path, sys.argv[1:])
# logic/ and crafting.* are read only by the new engine (engine/), the old one has no such files.
def files(d): return {p.relative_to(d).as_posix() for p in d.rglob("*") if p.is_file() and not p.relative_to(d).as_posix().startswith(("logic/", "crafting."))}
fa, fb = files(a) - {"README.md"}, files(b)
bad = [f"only in content: {f}" for f in sorted(fa - fb)] + [f"only after rebuild: {f}" for f in sorted(fb - fa)]
for f in sorted(fa & fb):
    x, y = (a / f).read_bytes(), (b / f).read_bytes()
    if f.endswith(".json"):
        x, y = json.loads(x), json.loads(y)
    if x != y:
        bad.append(f"changes on rebuild: {f}")
print(f"content: {len(fa)} files, {len(bad)} problems")
for m in bad[:30]: print("  " + m)
sys.exit(1 if bad else 0)
PY
