"""Differential test: the same player actions against two builds of the game
(e.g. the previous commit and the current one) on identical fresh worlds.
Every screen must be the same after normalising times and random link
suffixes. Randomness is made reproducible with LEGACY_RANDOM_SEED.

Both instances must run with the same TZ_CLOCK_FILE (tests/env/instance.sh):
the test freezes their clock and moves it 2 s per step, so the world's own
timers (AI every 240 s, regeneration, logout) fire on the same step in both.

usage: ab_walk.py <base-url-A> <base-url-B> <steps> <seed>
Exit 1 when screens differ, unless AB_ACCEPT=1 (an intended change).
"""
import difflib
import html
import os
import random
import re
import sys

from lib import Client, Clock, fold, normalise_for_diff, text_of

A, B, STEPS, SEED = sys.argv[1].rstrip("/"), sys.argv[2].rstrip("/"), int(sys.argv[3]), int(sys.argv[4])
AVOID = re.compile(r"ca=|adm=|zx=|delete|drop=|site=logout|trade=|f_site_list|forum|sms|newpass|&kill|cnick|nnick|photo|foto", re.I)
rnd = random.Random(SEED)
clock = Clock(os.environ["TZ_CLOCK_FILE"])
ca, cb = Client(A, pause=0, ua="TzAB"), Client(B, pause=0, ua="TzAB")


def both(path, data=None):
    """One step: the same request to both copies at the same frozen time."""
    clock.advance(2)
    return ca.request(path, data)[1], cb.request(path, data)[1]


# registration in lockstep, the same screens a player goes through
nick, pw = f"ab{SEED}", f"pw{SEED}ab"
pa, pb = both(f"/gamereg.php?log=new&nn={nick}&pass={pw}&pi=001")
codes = [re.search(r"код:\s*\*\s*(?:<[^>]+>)?\s*(\d+)", p).group(1) for p in (pa, pb)]
clock.advance(2)
ok = [c.get(f"/gamereg2.php?log=new&nn={nick}&pass={pw}&pi=&chis={code}")[1] for c, code in ((ca, codes[0]), (cb, codes[1]))]
assert all("успешно" in fold(text_of(p)) for p in ok), "registration failed"
both("/1/f_connect.php", {"login": nick, "p": pw})
pa, pb = both(f"/1/g.php?login=u.{nick}&p={pw}&sex=m&age=25&site=reg2")
assert "Регистрация завершена" in fold(text_of(pa)) and "Регистрация завершена" in fold(text_of(pb)), "character creation failed"
pa, pb = both("/1/f_connect.php", {"login": nick, "p": pw})
for _ in range(4):
    if "Добро пожаловать" in fold(text_of(pa)):
        break
    more = re.search(r'href="((?:f_connect|g)\.php\?[^"]*)"[^>]*>\s*Продолжить', pa)
    target = html.unescape(more.group(1))
    if target.startswith("f_connect.php"):
        import urllib.parse
        pa, pb = both("/1/f_connect.php", dict(urllib.parse.parse_qsl(target.split("?", 1)[1])))
    else:
        pa, pb = both("/1/" + target)
assert "Добро пожаловать" in fold(text_of(pa)), "cannot enter the world"
sid = f"{nick}.{pw}.x"

# a fixed part (admin tools used for testing) then a seeded random walk
fixed = [f"/1/g.php?sid={sid}", f"/1/g.php?sid={sid}&adm=smp&zx=mda", f"/1/g.php?sid={sid}&adm=mnn&inp=500&zx=mda",
         f"/1/g.php?sid={sid}&adm=adni&inp=i.w.k.begin&val=1&zx=mda", f"/1/g.php?sid={sid}&cl=i&cj=1",
         f"/1/g.php?sid={sid}&cl=p&cj=1", f"/1/g.php?sid={sid}&cl=m&cj=1", f"/1/g.php?sid={sid}&ci=1",
         f"/1/g.php?sid={sid}&cs=n.beginner", f"/1/g.php?sid={sid}&map=3", f"/1/g.php?sid={sid}&msg=1",
         f"/1/g.php?sid={sid}&cm=new", f"/1/g.php?sid={sid}&ce=1"]
same, diffs, cur, visited = 0, [], None, set()
for i in range(len(fixed) + STEPS):
    path = fixed[i] if i < len(fixed) else cur
    visited.add(re.sub(r"sid=[^&]+", "sid", path))
    pa, pb = both(path)
    na, nb = normalise_for_diff(pa), normalise_for_diff(pb)
    if na == nb:
        same += 1
    else:
        diffs.append((f"[step {i}] {path}", list(difflib.unified_diff(na, nb, "A", "B", n=0, lineterm=""))[2:14]))
    hrefs = [html.unescape(h) for h in re.findall(r'href="([^"#][^"]*)"', pa)]
    hrefs = [h for h in hrefs if "$(" not in h and not h.startswith("http") and not AVOID.search(h)]
    base = path.split("?")[0].rsplit("/", 1)[0]
    cur = (lambda h: h if h.startswith("/") else base + "/" + h)(rnd.choice(hrefs)) if hrefs else f"/1/g.php?sid={sid}"

total = len(fixed) + STEPS
print(f"ab_walk: {same}/{total} screens identical, {len(visited)} distinct requests")
for path, d in diffs[:20]:
    print("DIFF", path[:120])
    for l in d:
        print("     ", l[:200])
if diffs and os.environ.get("AB_ACCEPT") != "1":
    print("ab_walk: screens changed. If this is intended, put [ab-accept] in the commit message.")
    sys.exit(1)
