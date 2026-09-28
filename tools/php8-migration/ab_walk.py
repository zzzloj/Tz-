"""Deterministic parallel walk: same link choice on 5.6 and 8.4, diff each page."""
import re, time, sys, random, html
import difflib, urllib.request
UA="Mozilla/5.0 (Linux; Android 10) ABTest"
def get(base, path):
    req = urllib.request.Request(base + path, headers={"User-Agent": UA, "X-Legacy-Internal": "1"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r: body = r.read()
    except urllib.error.HTTPError as e: body = e.read()
    s = body.decode("utf-8", "replace")
    return re.sub(r"&#x0?([0-9a-fA-F]+);", lambda m: chr(int(m.group(1), 16)), s)
visited=[]
def norm(s):
    s = re.sub(r"(sid=[a-z0-9]+\.[a-z0-9]+\.)[a-z]", r"\1*", s)
    s = re.sub(r"\b\d\d:\d\d\b", "HH:MM", s)
    s = re.sub(r"\[\d+(\.\d+)?kb\]", "[Nkb]", s)
    s = re.sub(r"(r|rnd|refresh)=[a-z0-9]+", r"\1=N", s)
    s = re.sub(r"<", "\n<", s)
    return [l for l in s.split("\n") if l.strip()]
login, pw, steps, seed = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
rnd = random.Random(seed)
AVOID = re.compile(r"ca=|adm=|zx=|delete|drop=|site=logout|trade=|cl=a|f_site_list|forum|sms|newpass|&kill|&ce=|nnick|cnick", re.I)
B = {"56": "http://127.0.0.1:8070", "84": "http://127.0.0.1:8060"}
cur = f"/1/g.php?site=connect2&login=u.{login}&p={pw}"
same = diff = 0; diffs = []
for i in range(steps):
    visited.append(cur)
    pages = {k: get(b, cur) for k, b in B.items()}
    time.sleep(1.15)
    a, b = norm(pages["56"]), norm(pages["84"])
    if a == b: same += 1
    else:
        if diff == 0: print('FIRST DIFF at step', i, cur); print('  previous steps:', visited[-6:-1])
        diff += 1
        d = list(difflib.unified_diff(a, b, n=0, lineterm=""))[2:12]
        diffs.append((cur, d))
    hrefs = [html.unescape(h) for h in re.findall(r'href="([^"#][^"]*)"', pages["56"])]
    hrefs = [h for h in hrefs if "$(" not in h and not h.startswith("http") and not AVOID.search(h)]
    if not hrefs: cur = f"/1/g.php?sid={login}.{pw}.x"; continue
    h = rnd.choice(hrefs)
    base = cur.split("?")[0].rsplit("/", 1)[0]
    cur = h if h.startswith("/") else base + "/" + h
print(f"steps {steps}: identical {same}, different {diff}")
import collections
kinds=collections.Counter(re.sub(r"sid=[^&]+","sid",re.sub(r"=[^&]*","=",v.split("?",1)[-1]))[:40] for v in visited)
print(len(set(visited)),"distinct urls; kinds:",kinds.most_common(25))
for cur, d in diffs[:25]:
    print("DIFF", cur[:110])
    for l in d: print("     ", l[:180])
