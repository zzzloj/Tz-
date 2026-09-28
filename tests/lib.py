"""Shared helpers for the game tests: raw HTTP client, text normalisation,
player registration through the game's own screens."""
import html
import http.cookiejar
import re
import time
import urllib.parse
import urllib.request

UA = "Mozilla/5.0 (Linux; Android 10) TzTests"
# Latin letters the original authors typed in place of look-alike Cyrillic ones.
_LOOKALIKE = str.maketrans("AaBCcEeHKkMOoPpTXxy", "АаВСсЕеНКкМОоРрТХху")
_WORD = re.compile(r"[A-Za-zА-Яа-яЁё]+")


def fold(text):
    """Map look-alike Latin letters inside Cyrillic words to Cyrillic, for matching."""
    def fix(m):
        w = m.group(0)
        return w.translate(_LOOKALIKE) if re.search(r"[А-Яа-яЁё]", w) else w
    return _WORD.sub(fix, text)


def decode_wml(raw):
    s = raw.decode("utf-8", "replace") if isinstance(raw, bytes) else raw
    return re.sub(r"&#x0?([0-9a-fA-F]+);", lambda m: chr(int(m.group(1), 16)), s)


def text_of(wml):
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", wml))).strip()


class Clock:
    """Frozen clock shared by test instances started with TZ_CLOCK_FILE."""

    def __init__(self, path, start=1780000000):
        self.path, self.now = path, start
        self.write()

    def write(self):
        with open(self.path + ".new", "w") as f:
            f.write(time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(self.now)))
        import os
        os.replace(self.path + ".new", self.path)

    def advance(self, seconds):
        self.now += seconds
        self.write()


class Client:
    """Raw WML client (bypasses the HTML shell) with its own cookie jar."""

    def __init__(self, base, pause=1.15, ua=None):
        self.base = base.rstrip("/")
        # The engine's double-click guard keys on the User-Agent: one per client.
        self.ua = ua or f"{UA} c{id(self)}"
        self.pause = pause
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.last = 0.0

    def request(self, path, data=None):
        # The engine rejects two identical requests within a second (glast.dat).
        wait = self.pause - (time.time() - self.last)
        if wait > 0:
            time.sleep(wait)
        body = urllib.parse.urlencode(data).encode() if data is not None else None
        req = urllib.request.Request(self.base + path, data=body,
                                     headers={"User-Agent": self.ua, "X-Legacy-Internal": "1"})
        try:
            with self.opener.open(req, timeout=90) as r:
                status, raw = r.status, r.read()
        except urllib.error.HTTPError as e:
            status, raw = e.code, e.read()
        self.last = time.time()
        return status, decode_wml(raw)

    def get(self, path):
        return self.request(path)

    def post(self, path, data):
        return self.request(path, data)


def register_player(base, nick, password, sex="m", age=25):
    """Create an account and a character the way a player does. Returns a Client."""
    c = Client(base)
    status, page = c.get(f"/gamereg.php?log=new&nn={nick}&pass={password}&pi=001")
    code = re.search(r"код:\s*\*\s*(?:<[^>]+>)?\s*(\d+)", page)
    assert status == 200 and code, f"registration step 1 failed for {nick}: {text_of(page)[:200]}"
    status, page = c.get(f"/gamereg2.php?log=new&nn={nick}&pass={password}&pi=&chis={code.group(1)}")
    assert "успешно" in fold(text_of(page)), f"registration failed for {nick}: {text_of(page)[:200]}"
    status, page = c.post("/1/f_connect.php", {"login": nick, "p": password})
    assert "Пол персонажа" in fold(text_of(page)), f"character form missing for {nick}: {text_of(page)[:200]}"
    status, page = c.get(f"/1/g.php?login=u.{nick}&p={password}&sex={sex}&age={age}&site=reg2")
    assert "Регистрация завершена" in fold(text_of(page)), f"character creation failed for {nick}: {text_of(page)[:200]}"
    return c


def enter_world(client, nick, password):
    """Log a registered player into server 1 the way the login form does;
    returns the sid for game links."""
    status, page = client.post("/1/f_connect.php", {"login": nick, "p": password})
    for _ in range(4):  # "Нажмите на ссылку ниже / Продолжить" hops
        if "Добро пожаловать" in fold(text_of(page)):
            break
        more = re.search(r'href="((?:f_connect|g)\.php\?[^"]*)"[^>]*>\s*Продолжить', page)
        if not more:
            break
        target = html.unescape(more.group(1))
        if target.startswith("f_connect.php"):
            q = dict(urllib.parse.parse_qsl(target.split("?", 1)[1]))
            status, page = client.post("/1/f_connect.php", q)
        else:
            status, page = client.get("/1/" + target)
    assert status == 200 and "Добро пожаловать" in fold(text_of(page)), f"{nick} cannot enter: {text_of(page)[:200]}"
    sid = re.search(r"sid=([a-z0-9]+\.[a-z0-9]+)\.", page)
    return (sid.group(1) if sid else f"{nick}.{password}") + ".x"


def normalise_for_diff(wml):
    """Remove values that legitimately differ between two runs."""
    s = re.sub(r"(sid=[a-z0-9]+\.[a-z0-9]+\.)[a-z]", r"\1*", wml)
    s = re.sub(r"\b\d\d:\d\d\b", "HH:MM", s)
    s = re.sub(r"\[\d+(\.\d+)?kb\]", "[Nkb]", s)
    s = re.sub(r"\b(r|rnd|refresh)=[a-z0-9]+", r"\1=N", s)
    s = re.sub(r"(ontimer=\"[^\"]*sid=[^\"&]*)", r"\1", s)
    return [l for l in re.sub(r"<", "\n<", s).split("\n") if l.strip()]
