"""Random-walk crawler: registers players, then clicks random links in the game.

usage: crawl.py <base-url> <php-error-log> <players> <steps> <seed>
Fails on PHP fatal errors in the log, HTTP 5xx and JS errors; decks the\nrenderer had to repair are listed as notes.
"""
import random
import re
import sys
import threading

from playwright.sync_api import sync_playwright

from lib import fold, register_player

BASE, ERRLOG = sys.argv[1].rstrip("/"), sys.argv[2]
PLAYERS, STEPS, SEED = int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
AVOID = re.compile(r"удал|выход|delete|пароль|фото|сменить|клан|брос|форум|админ", re.I)
issues, notes, stats = [], [], {"clicks": 0, "game": 0}
lock = threading.Lock()


def on_console(page, m):
    if m.type not in ("error", "warning") or "wml" not in m.text.lower():
        return
    # The renderer recovers from malformed decks written by the original
    # engine (stray "&", unclosed tags); report them, but do not fail on them.
    target = notes if "not well-formed" in m.text else issues
    with lock: target.append(f"{m.text[:120]} at {re.sub(r'sid=[^&]+', 'sid=…', page.url)[:160]}")


def crawl(n):
    rnd = random.Random(SEED * 100 + n)
    nick, pw = f"cr{SEED}x{n}", f"pw{SEED}{n}"
    register_player(BASE, nick, pw)
    with sync_playwright() as p:
        b = p.chromium.launch()
        page = b.new_page(user_agent=f"Mozilla/5.0 (Linux; Android 10) TzCrawler{n}")
        page.on("pageerror", lambda e: issues.append(f"JS error: {e}"))
        page.on("console", lambda m: on_console(page, m))
        page.on("response", lambda r: issues.append(f"HTTP {r.status} {r.url}") if r.status >= 500 else None)
        page.goto(BASE + "/1/g.php")
        page.fill("[name=nn]", nick); page.fill("[name=pass]", pw)
        page.get_by_role("link", name=re.compile("войти|вoйти")).first.click(); page.wait_for_load_state()
        for _ in range(6):  # Продолжить -> [в игру] -> journal
            if re.search(r"/[12]/g\.php\?.*sid=", page.url):
                break
            nxt = [a for a in page.query_selector_all("a") if fold(a.inner_text().strip()) in ("Продолжить", "[в игру]", "В игру")]
            if not nxt:
                break
            page.wait_for_timeout(1100); nxt[0].click(); page.wait_for_load_state()
        last_game = None
        for _ in range(STEPS):
            url = page.url
            if re.search(r"/[12]/g\.php\?.*sid=", url):
                last_game = url.split("#")[0]
                with lock: stats["game"] += 1
            elif last_game and rnd.random() < 0.7:
                page.wait_for_timeout(1100); page.goto(last_game); page.wait_for_load_state(); continue
            for inp in page.query_selector_all("main input[type=text]"):
                inp.fill(rnd.choice(["1", "5", "тест", "ёж"]))
            links = [a for a in page.query_selector_all("main a, nav a") if not AVOID.search(fold(a.inner_text() or ""))]
            if not links:
                page.go_back(); page.wait_for_load_state(); continue
            page.wait_for_timeout(1100)
            try:
                rnd.choice(links).click(timeout=4000); page.wait_for_load_state()
                with lock: stats["clicks"] += 1
            except Exception as e:  # a link that vanished under an AI refresh is not a bug
                pass
        b.close()


def guarded(n):
    try:
        crawl(n)
    except Exception as e:
        with lock: issues.append(f"crawler {n} crashed: {e!r}"[:300])


threads = [threading.Thread(target=guarded, args=(n,)) for n in range(PLAYERS)]
for t in threads: t.start()
for t in threads: t.join()

fatals = [l.strip() for l in open(ERRLOG, errors="replace") if "PHP Fatal error" in l]
print(f"crawl: {stats['clicks']} clicks, {stats['game']} game screens, {len(fatals)} PHP fatals, {len(issues)} browser issues, {len(notes)} malformed decks (informational)")
for f in sorted(set(re.sub(r"^\[[^]]*\] ", "", x) for x in fatals))[:20]: print("FATAL", f)
for i in sorted(set(issues))[:20]: print("ISSUE", i)
for i in sorted(set(notes))[:10]: print("NOTE", i)
sys.exit(1 if fatals or issues else 0)
