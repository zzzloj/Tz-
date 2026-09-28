#!/usr/bin/env python3
"""Build the engine's data files (cp1251) from content/ (UTF-8 JSON).

    build.py <content dir> <game/1 dir>

Writes items/, items1/, npc/, speak/, desc/, l_f/, l_i/ and l_t/ into the
game directory. l_t/ (the engine's fallback copy of a location, read only when
l_i/ cannot be parsed) gets the same starting state as l_i/. Inverse of
tools/content/export.php; tools/content/verify.php checks the pair.
"""
import json
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
SCHEMA = json.loads((HERE / "schema.json").read_text(encoding="utf-8"))
SID = "{{sid}}"
INT_KEY = re.compile(r"^(0|-?[1-9][0-9]{0,17})$")
HTACCESS = b"<Files *>\norder allow,deny\ndeny from all\n</Files>\n"
DIRS = ["items", "items1", "npc", "speak", "desc", "l_f", "l_i", "l_t"]


class ContentError(Exception):
    pass


def cp(s, where):
    try:
        return s.encode("cp1251")
    except UnicodeEncodeError as e:
        raise ContentError(f"{where}: character {s[e.start:e.end]!r} does not exist in cp1251")


# ---- engine strings --------------------------------------------------------

def fmt(value, typ, where):
    if isinstance(value, str):
        return value
    if isinstance(value, bool) or value is None:
        raise ContentError(f"{where}: unexpected {value!r}")
    if isinstance(value, int):
        return str(value)
    if isinstance(value, list) and typ == "ints":
        return ":".join(str(int(x)) for x in value)
    if isinstance(value, list) and typ == "effects":
        return ",".join(f"{int(a)}:{int(b)}" for a, b in value)
    raise ContentError(f"{where}: cannot write {value!r} as {typ}")


def join_fields(obj, fields, where):
    parts, missing = [], False
    for name, typ in fields:
        if name not in obj:
            missing = True
            continue
        if missing:
            raise ContentError(f"{where}: field {name} present after a missing one")
        parts.append(fmt(obj[name], typ, f"{where}.{name}"))
    extra = obj.get("extra", [])
    if extra and missing:
        raise ContentError(f"{where}: extra fields after a missing one")
    parts.extend(extra)
    known = {n for n, _ in fields} | {"extra", "id", "kind"}
    unknown = set(obj) - known
    if unknown:
        raise ContentError(f"{where}: unknown fields {sorted(unknown)}")
    return "|".join(parts)


def item_kind(item_id):
    for k in SCHEMA["item_kinds"]:
        if item_id.startswith(k["prefix"]):
            return k


def item_text(obj, where):
    k = item_kind(obj["id"])
    if obj.get("kind") != k["kind"]:
        raise ContentError(f"{where}: kind {obj.get('kind')!r}, id says {k['kind']!r}")
    return join_fields(obj, k["fields"], where)


def list_text(value, kind, where):
    if isinstance(value, str):
        return value
    if kind == "ids":
        return "|".join(value)
    if kind == "counted":
        return "|".join(f"{x['id']}:{int(x['count'])}" for x in value)
    return "|".join(f"{x['id']}:{int(x['chance'])}:{int(x['min'])}:{int(x['max'])}" for x in value)


def entity(obj, where):
    """JSON entity (NPC template or live NPC) -> PHP array value."""
    out = {}
    for k, v in obj.items():
        if k == "id" and where.startswith("npcs/"):
            continue
        if isinstance(v, dict) and set(v) == {"$php"}:
            out[k] = v["$php"]
        elif k in ("char", "war") and isinstance(v, dict):
            out[k] = join_fields(v, SCHEMA[k], f"{where}.{k}")
        elif k in SCHEMA["entity_lists"] and isinstance(v, list):
            out[k] = list_text(v, SCHEMA["entity_lists"][k], f"{where}.{k}")
        else:
            out[k] = v
    return out


def dialog_topic(v, where):
    if isinstance(v, str):
        return v
    parts = [v["text"]]
    for o in v["options"]:
        parts.append(o["label"])
        if "goto" in o:
            parts.append(o["goto"])
    return "#".join(parts)


def location_state(loc, where):
    """Location JSON -> (state array or None for an empty l_i file) / False if no l_i file."""
    if "state" in loc and loc["state"] is None:
        return None
    state = {}
    if "name" in loc:
        parts = [loc["name"]]
        if "zone" in loc:
            parts.append(fmt(loc["zone"], "int", f"{where}.zone"))
        for x in loc.get("exits", []):
            parts.append(x["label"])
            if "target" in x:
                parts.append(x["target"])
        state["d"] = "|".join(parts)
    if "objects" in loc:
        objs = loc["objects"]
        if isinstance(objs, list):  # an empty PHP array
            objs = {str(i): x for i, x in enumerate(objs)}
        state["i"] = {k: entity(x, f"{where}.{k}") if isinstance(x, dict) else x for k, x in objs.items()}
    if "timers" in loc:
        state["t"] = loc["timers"]
    for k, v in loc.get("state_extra", {}).items():
        state[k] = v
    if not state:
        return False
    order = loc.get("key_order")
    if order:
        state = {k: state[k] for k in order}
    return state


# ---- PHP formats -----------------------------------------------------------

def php_key(k):
    return int(k) if isinstance(k, str) and INT_KEY.match(k) else k


def serialize(v, where):
    if isinstance(v, bool):
        return b"b:%d;" % v
    if v is None:
        return b"N;"
    if isinstance(v, int):
        return b"i:%d;" % v
    if isinstance(v, float):
        raise ContentError(f"{where}: floats are not supported")
    if isinstance(v, str):
        b = cp(v, where)
        return b's:%d:"%s";' % (len(b), b)
    items = enumerate(v) if isinstance(v, list) else v.items()
    body = []
    for k, x in items:
        k = php_key(k)
        body.append((b"i:%d;" % k) if isinstance(k, int) else serialize(k, where))
        body.append(serialize(x, where))
    return b"a:%d:{%s}" % (len(body) // 2, b"".join(body))


def php_str(s, where, sid=False):
    def lit(t):
        return b"'" + cp(t, where).replace(b"\\", b"\\\\").replace(b"'", b"\\'") + b"'"
    if sid and SID in s:
        return b".$sid.".join(lit(p) for p in s.split(SID))
    return lit(s)


def php_value(v, where, sid=False):
    if isinstance(v, bool):
        return b"true" if v else b"false"
    if v is None:
        return b"null"
    if isinstance(v, int):
        return str(v).encode()
    if isinstance(v, str):
        return php_str(v, where, sid)
    if isinstance(v, float):
        raise ContentError(f"{where}: floats are not supported")
    items = enumerate(v) if isinstance(v, list) else v.items()
    body = []
    for k, x in items:
        k = php_key(k)
        key = str(k).encode() if isinstance(k, int) else php_str(k, where)
        body.append(key + b"=>" + php_value(x, where, sid))
    return b"array(" + b",".join(body) + b")"


def php_array_file(var, arr, where, sid=False):
    lines = [b"<?php", b"$" + var.encode() + b"=array("]
    for k, x in arr.items():
        k = php_key(k)
        key = str(k).encode() if isinstance(k, int) else php_str(k, where)
        lines.append(key + b"=>" + php_value(x, where, sid) + b",")
    lines.append(b");")
    return b"\n".join(lines) + b"\n"


# ---- build -----------------------------------------------------------------

def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def build(content, game):
    counts = {}
    for d in DIRS:
        (game / d).mkdir(parents=True, exist_ok=True)
    for d in ("items", "npc", "speak", "desc", "l_f", "l_t"):
        (game / d / ".htaccess").write_bytes(HTACCESS)

    def write(rel, data):
        (game / rel).write_bytes(data)
        top = rel.split("/")[0]
        counts[top] = counts.get(top, 0) + 1

    for src, dst in (("items", "items"), ("items_dublon", "items1")):
        for p in sorted((content / src).glob("*.json")):
            obj = load(p)
            if obj["id"] + ".json" != p.name:
                raise ContentError(f"{src}/{p.name}: id {obj['id']!r} does not match the file name")
            write(f"{dst}/{obj['id']}", cp(item_text(obj, f"{src}/{p.name}"), p.name))

    for p in sorted((content / "npcs").glob("*.json")):
        obj = load(p)
        where = f"npcs/{p.name}"
        write(f"npc/{obj['id']}", php_array_file("npc", entity(obj, where), where))

    for p in sorted((content / "dialogs").glob("*.json")):
        obj = load(p)
        where = f"dialogs/{p.name}"
        topics = {k: dialog_topic(v, f"{where}.{k}") for k, v in obj["topics"].items()}
        write(f"speak/{obj['id']}", php_array_file("dialog", topics, where, sid=True))

    raw = content / "raw"
    for p in sorted(x for x in raw.rglob("*") if x.is_file()):
        rel = p.relative_to(raw).as_posix()
        write(rel, cp(p.read_bytes().decode("utf-8"), rel))  # keep \r\n as is

    for p in sorted((content / "locations").glob("*.json")):
        loc = load(p)
        where = f"locations/{p.name}"
        state = location_state(loc, where)
        if state is not False:
            data = b"" if state is None else serialize(state, where)
            write(f"l_i/{loc['id']}", data)
            write(f"l_t/{loc['id']}", data)
        if "description" in loc:
            write(f"l_f/{loc['id']}", cp(loc["description"], where))
    return counts


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    try:
        counts = build(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]))
    except (ContentError, KeyError) as e:
        sys.exit(f"content error: {e}")
    print("built " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))


if __name__ == "__main__":
    main()
