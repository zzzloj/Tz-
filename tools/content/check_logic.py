#!/usr/bin/env python3
"""Checks content/logic (the new engine's dialog logic) the same way the
server does (engine/server/.../Dialogs.kt), without a JVM.

usage: check_logic.py [content dir] [--untranslated]
Exit code 1 if there are problems.
"""
import json
import pathlib
import sys

CONDITIONS = {"has", "lacks", "money", "equipped", "skill", "newbie", "ready", "waiting", "flag", "noflag",
              "known", "unknown", "here", "notHere", "npcAt", "noNpcAt", "arg", "chance", "sex", "ghost", "any", "not"}
ACTIONS = {"take", "give", "exp", "start", "stop", "set", "clear", "learn", "teach", "teleport", "spawn", "remove",
           "place", "removeHere", "resurrect", "heal", "say", "journal", "handler", "giveNpc"}
RULE_KEYS = {"if", "do", "text", "options", "hide", "goto"}
SKILLS = {"str", "dex", "int", "meditation", "steal", "animaltaming", "hand", "coldweapon", "ranged", "parring", "uklon",
          "magic", "magic_resist", "magic_uklon", "regeneration", "hiding", "look", "steallook", "animallore", "spirit",
          "healing", "alchemy", "mine", "smith", "lumb", "bow", "stone", "fish", "food", "necro", "currier", "weaver",
          "exp", "points"}
# Handlers the server implements (Dialogs.HANDLERS in engine/server/.../Dialogs.kt).
HANDLERS = {"arena-count", "hide-item-random", "repair-boat", "lower-int", "npc-hand-over", "require-pk",
            "clan-status", "clan-leave", "clan-name-input", "clan-create", "clan-restore",
            "castle-keeper-access", "castle-rune-list", "castle-contract", "castle-teleport",
            "arena-enter", "bounty-list", "bounty-form", "bounty-place", "bounty-claim",
            "hire-mercenary", "buy-pet", "pet-owned-here", "pet-free", "sell-pet", "pet-return", "marten-unicorn",
            "sacrifice-pet", "hire-fairy", "kasten-squad", "escort"}


def handler_supported(a):
    h = a.get("handler")
    return h is None or h in HANDLERS
ENGINE_TOPICS = {"buy", "buy2", "sell", "tobank", "frombank"}

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else
                    pathlib.Path(__file__).resolve().parents[2] / "content")
items = {p.stem for p in (root / "items").glob("*.json")}
npcs = {p.stem for p in (root / "npcs").glob("*.json")}
locations = {p.stem for p in (root / "locations").glob("*.json")}
jokes = {p.name for p in (root / "raw/speak").glob("h.*")}
dialogs = {p.stem: json.loads(p.read_text()) for p in (root / "dialogs").glob("*.json")}
logic_dir = root / "logic"
# timers.json, plus timers-<part>.json while several people translate at once (merged into timers.json later).
timers = {}
for tp in sorted(logic_dir.glob("timers*.json")):
    for k, v in json.loads(tp.read_text()).items():
        if k in timers:
            print(f"  duplicate timer {k} in {tp.name}")
        timers[k] = v
problems = []


def item_ok(i):
    if not isinstance(i, str):
        return False
    base = i.split("_")[0].split("-")[0]
    return i in items or base in items or base.split("..")[0] in items


for k, t in timers.items():
    if t.get("scope", "player") not in ("player", "world"):
        problems.append(f"timers.json: {k}: scope must be player or world")
    mn, mx = t.get("min", 0), t.get("max", t.get("min", 0))
    if not isinstance(mn, int) or not isinstance(mx, int) or mn <= 0 or mx < mn:
        problems.append(f"timers.json: {k}: bad min/max")
    if not t.get("about"):
        problems.append(f"timers.json: {k}: say what it is in \"about\"")

logic = {}
for p in sorted(logic_dir.glob("*.json")):
    if p.name.startswith("timers"):
        continue
    try:
        o = json.loads(p.read_text())
    except Exception as e:
        problems.append(f"logic/{p.name}: {e}")
        continue
    if o.get("id") != p.stem:
        problems.append(f"logic/{p.name}: id must be {p.stem}")
    logic[p.stem] = o.get("topics") or {}


def topic_exists(d, t):
    return (t == "end" or t in ENGINE_TOPICS or t in logic.get(d, {})
            or t in (dialogs.get(d, {}).get("topics") or {}))


def check_cond(where, c):
    if not isinstance(c, dict):
        problems.append(f"{where}: condition must be an object: {c}")
        return
    k = next((x for x in c if x in CONDITIONS), None)
    if k is None:
        problems.append(f"{where}: unknown condition {c}")
    elif k in ("has", "lacks") and not item_ok(c[k]):
        problems.append(f"{where}: unknown item {c[k]}")
    elif k == "skill" and c[k] not in SKILLS:
        problems.append(f"{where}: unknown skill {c[k]}")
    elif k in ("ready", "waiting") and c[k] not in timers:
        problems.append(f"{where}: unknown timer {c[k]} (add it to timers.json)")
    elif k == "any":
        for x in c[k]:
            check_cond(where, x)
    elif k == "not":
        check_cond(where, c[k])
    elif k in ("npcAt", "noNpcAt") and c.get("location") and c["location"] not in locations:
        problems.append(f"{where}: unknown location {c['location']}")
    elif k == "arg" and not isinstance(c[k], list):
        problems.append(f"{where}: arg must be a list of strings")


def check_action(where, a):
    if not isinstance(a, dict):
        problems.append(f"{where}: action must be an object: {a}")
        return
    k = next((x for x in a if x in ACTIONS), None)
    if k is None:
        problems.append(f"{where}: unknown action {a}")
    elif k in ("take", "give", "place", "giveNpc") and not item_ok(a[k]):
        problems.append(f"{where}: unknown item {a[k]}")
    elif k in ("start", "stop") and a[k] not in timers:
        problems.append(f"{where}: unknown timer {a[k]} (add it to timers.json)")
    elif k == "teach" and not (str(a[k]).startswith(("m.", "p.")) or a[k] in SKILLS):
        problems.append(f"{where}: teach unknown skill {a[k]}")
    elif k == "teleport" and a[k] not in locations:
        problems.append(f"{where}: unknown location {a[k]}")
    elif k == "spawn":
        if a[k] not in npcs:
            problems.append(f"{where}: unknown NPC template {a[k]}")
        if a.get("location") and a["location"] not in locations:
            problems.append(f"{where}: unknown location {a['location']}")
    elif k == "say" and a[k] not in jokes:
        problems.append(f"{where}: unknown joke file {a[k]}")
    if "count" in a and (not isinstance(a["count"], int) or a["count"] < 1):
        problems.append(f"{where}: count must be a positive integer")


for d, topics in logic.items():
    if d not in dialogs and not (root / "raw/speak" / d).exists():
        problems.append(f"logic/{d}: no dialog {d} in content/dialogs or raw/speak")
    for t, rules in topics.items():
        where = f"logic/{d}/{t}"
        if not isinstance(rules, list) or not rules:
            problems.append(f"{where}: a topic is a non-empty list of rules")
            continue
        for r in rules:
            extra = set(r) - RULE_KEYS
            if extra:
                problems.append(f"{where}: unknown rule fields {sorted(extra)}")
            for c in r.get("if", []):
                check_cond(where, c)
            for a in r.get("do", []):
                check_action(where, a)
            if r.get("goto") and not topic_exists(d, r["goto"]):
                problems.append(f"{where}: goto missing topic \"{r['goto']}\"")
            for o in r.get("options") or []:
                if set(o) - {"label", "goto", "arg", "if"}:
                    problems.append(f"{where}: unknown option fields {sorted(set(o) - {'label', 'goto', 'arg', 'if'})}")
                if not topic_exists(d, o.get("goto", "")):
                    problems.append(f"{where}: option goes to missing topic \"{o.get('goto')}\"")
                for c in o.get("if", []):
                    check_cond(where, c)
            for h in r.get("hide", []):
                if not isinstance(h, str):
                    problems.append(f"{where}: hide lists topic ids")
            if isinstance(r.get("text"), str) and r["text"].startswith("eval:"):
                problems.append(f"{where}: text is still PHP")


def is_eval(v):
    if isinstance(v, str):
        return v.startswith("eval:")
    return v.get("text", "").startswith("eval:") or any(o.get("label", "").startswith("eval:") for o in v.get("options", []))


untranslated = sorted(f"{d}/{t}" for d, o in dialogs.items() for t, v in o["topics"].items()
                      if is_eval(v) and t not in logic.get(d, {}))
handlers = sorted(f"{d}/{t}" for d, topics in logic.items() for t, rules in topics.items()
                  if any(not handler_supported(a) for r in rules for a in r.get("do", [])))
raw_dialogs = sorted(p.name for p in (root / "raw/speak").glob("n.*") if p.name not in logic)

print(f"logic: {len(logic)} dialogs, {len(timers)} timers, {len(problems)} problems, "
      f"{len(untranslated)} eval topics not moved, {len(handlers)} topics wait for a handler, "
      f"{len(raw_dialogs)} PHP dialogs (raw/speak) not moved")
for m in problems:
    print("  " + m)
if "--untranslated" in sys.argv:
    for m in untranslated:
        print("  untranslated " + m)
    for m in handlers:
        print("  handler " + m)
    for m in raw_dialogs:
        print("  raw " + m)
sys.exit(1 if problems else 0)
