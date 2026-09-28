"""End-to-end player scenario in a real browser through the WML renderer.

usage: scenario.py <base-url>
Fails on: a step that does not show what it should, a JS error, an HTTP 5xx,
or a password in any request URL other than the engine's own sid.
"""
import random
import re
import string
import sys

from playwright.sync_api import sync_playwright

from lib import fold

BASE = sys.argv[1].rstrip("/")
NICK = "sc" + "".join(random.choice(string.ascii_lowercase) for _ in range(6))
PASS = "p" + "".join(random.choice(string.ascii_lowercase + string.digits) for _ in range(7))
NEWPASS = PASS[:-1] + "z"
problems = []


def check(cond, what):
    print(("ok   " if cond else "FAIL ") + what, flush=True)
    if not cond:
        problems.append(what)
    return cond


with sync_playwright() as pw:
    browser = pw.chromium.launch()
    page = browser.new_page(user_agent="Mozilla/5.0 (Linux; Android 10) TzScenario")
    page.on("pageerror", lambda e: problems.append(f"JS error: {e}"))
    page.on("console", lambda m: problems.append(f"console {m.type}: {m.text}") if m.type == "error" else None)
    page.on("response", lambda r: problems.append(f"HTTP {r.status} {r.url}") if r.status >= 500 else None)

    def url_check(req):
        u = req.url
        if re.search(r"[?&](p|pass|newpass)=[^&]", u) and "sid=" not in u:
            problems.append(f"password in URL: {u}")
    page.on("request", url_check)

    def body():
        return fold(page.inner_text("main"))

    def click(label):
        page.wait_for_timeout(1150)  # the engine rejects a repeat within one second
        links = [a for a in page.query_selector_all("main a, nav a") if fold(a.inner_text().strip()) == label
                 or fold(a.inner_text()).strip().endswith(label)]
        if not check(bool(links), f"link «{label}» is present"):
            raise SystemExit(1)
        links[0].click()
        page.wait_for_load_state()

    try:
        page.goto(BASE + "/1/g.php")
        check("Территория Зла" in body() and page.query_selector("input[name=nn]") is not None, "start page")

        page.fill("[name=nn]", NICK); page.fill("[name=pass]", PASS)
        click("- регистрация")
        code = re.search(r"код:\s*\*\s*(\d+)", body())
        check(code is not None, "registration shows a check code")
        page.fill("[name=chis]", code.group(1))
        click("[далее]")
        check("Регистрация успешно завершена" in body(), "account registered")

        click("[в игру]")
        check("Пол персонажа" in body(), "character form")
        page.select_option("select[name=sex]", label="Муж."); page.fill("[name=age]", "25")
        click("Продолжить")
        check("Регистрация завершена" in body(), "character created")
        click("Начать игру")
        check("Добро пожаловать, " + NICK in body(), "welcome screen")

        click("[в игру]")
        click("[дальше]")
        keys = [fold(a.inner_text()) for a in page.query_selector_all("nav a")]
        check(all(k in keys for k in ("[персонаж]", "[сказать]", "[карта]", "[сохранить]")), f"soft keys {keys}")

        click("Привратник Уин")
        check("[говорить/взять]" in body(), "NPC menu card")
        click("[говорить/взять]")
        check("Приветствую тебя" in body(), "NPC dialogue")
        click("[в игру]")

        click("[персонаж]")
        check("нож" in body(), "starting knife in inventory")
        click("нож")
        click("Использовать")
        check("[одето]" in body(), "knife equipped")
        click("[в игру]")

        click("[сказать]")
        page.fill("[name=say]", "Привет, ёжик!")
        click("[сказать]")
        check("говорит: Привет, ёжик!" in body(), "chat keeps Cyrillic and ё")
        click("[дальше]")

        click("[карта]")
        sizes = page.evaluate("[...document.images].map(i => i.naturalWidth)")
        check(sizes and all(w > 0 for w in sizes), f"map image renders {sizes}")
        click("[в игру]")

        click("[сохранить]")
        check("Персонаж сохранен" in body(), "character saved")

        sid = re.search(r"sid=([^&#]+)", page.url)
        page.wait_for_timeout(1150)
        page.goto(f"{BASE}/1/g.php?sid={sid.group(1) if sid else ''}&adm=smp&zx=mda")
        check("телепорт" in body(), "admin panel (zx=mda, kept for testing)")

        page.wait_for_timeout(1150)
        page.goto(BASE + "/1/g.php")
        page.fill("[name=nn]", NICK); page.fill("[name=pass]", PASS)
        click("[сменить пароль]")
        page.fill("[name=newpass]", NEWPASS)
        click("[сохранить]")
        check("Настройки сохранены" in body(), "password changed")
    except SystemExit:
        pass
    finally:
        browser.close()

for p in problems:
    print("PROBLEM", p)
sys.exit(1 if problems else 0)
