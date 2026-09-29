#!/usr/bin/env python3
"""Smoke test of a deployed game server: register, character, walk.

usage: smoke.py <base url>
Prints a GitHub annotation with the result; exits 1 on failure.
Creates one throwaway account "smoke_<random>" per run.
"""
import json
import random
import string
import sys
import time
import urllib.error
import urllib.request

base = sys.argv[1].rstrip("/")


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            text = r.read().decode()
            return r.status, json.loads(text) if text.startswith(("{", "[")) else text
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        return e.code, json.loads(text) if text.startswith("{") else text


steps = []
try:
    for _ in range(30):
        try:
            if call("GET", "/api/health")[0] == 200:
                break
        except Exception:
            pass
        time.sleep(10)
    status, report = call("GET", "/api/content/report")
    assert status == 200, (status, report)
    steps.append(f"content: {report['locations']} locations, {len(report['problems'])} problems")

    login = "smoke_" + "".join(random.choices(string.ascii_lowercase, k=8))
    status, auth = call("POST", "/api/auth/register", {"login": login, "password": "smoke-pass-123"})
    assert status == 201, (status, auth)
    token = auth["token"]
    steps.append("register ok")

    name = "Smoke" + "".join(random.choices(string.ascii_lowercase, k=8))
    status, ch = call("POST", "/api/characters", {"name": name, "sex": "m"}, token)
    assert status == 201 and ch["location"] == "_begin", (status, ch)
    steps.append(f"character {name} at _begin, HP {ch['hp']}/{ch['hpMax']}")

    status, game = call("GET", "/api/game", token=token)
    exit_ = game["location"]["exits"][0]
    status, moved = call("POST", "/api/game/move", {"target": exit_["target"]}, token)
    assert status == 200 and moved["location"]["id"] == exit_["target"], (status, moved)
    steps.append(f"move «{exit_['label']}» → {moved['location']['name']}")

    npcs = [n["name"] for n in game["location"]["npcs"]]
    assert game["inventory"] and game["inventory"][0]["id"] == "i.w.k.begin", game["inventory"]
    status, g = call("POST", "/api/game/equip", {"item": "i.w.k.begin"}, token)
    assert status == 200 and g["inventory"][0]["equipped"], (status, g)
    status, g = call("POST", "/api/game/drop", {"item": "i.w.k.begin"}, token)
    assert status == 200 and not g["inventory"] and any(i["id"] == "i.w.k.begin" for i in g["location"]["items"]), (status, g)
    status, g = call("POST", "/api/game/take", {"item": "i.w.k.begin"}, token)
    assert status == 200 and g["inventory"][0]["id"] == "i.w.k.begin", (status, g)
    steps.append(f"start NPCs: {', '.join(npcs) or '—'}; knife: equip, drop, take back ok")

    status, err = call("POST", "/api/game/move", {"target": "arena"}, token)
    assert status == 400 and err["error"] == "not_an_exit", (status, err)
    status, _ = call("GET", "/api/me", token="forged")
    assert status == 401
    steps.append("cheat move refused, forged token refused")

    status, _ = call("POST", "/api/auth/logout", {}, token)
    status, _ = call("GET", "/api/me", token=token)
    assert status == 401
    steps.append("logout ok")
    print("::notice title=Smoke " + base + "::" + "%0A".join(steps))
except Exception as e:
    print("::error title=Smoke " + base + "::" + "%0A".join(steps + [f"FAILED: {e!r}"])[:3000])
    sys.exit(1)
