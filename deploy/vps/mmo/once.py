"""A one-time change of the MMO database (SQLite in ~/tz-mmo/data), run by .github/workflows/mmo-vps.yml while
the server is stopped, once per version of this file (its hash is kept in data/once.sha).

14.10.2026 (owner's request, testing): every account's password becomes the test one «1234» (the framework
keeps MD5 of it without salt), user names become small letters (the game now types logins in small letters:
the phone's keyboard made «Scuko» of «scuko»). Prints the accounts."""
import glob, hashlib, sqlite3

TEST = hashlib.md5("1234".encode()).hexdigest()
for path in glob.glob("/data/**/*.sqlite3", recursive=True):
    db = sqlite3.connect(path)
    if not db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='userlogin'").fetchone():
        continue
    rows = db.execute("SELECT id, username FROM userlogin").fetchall()
    taken = {u.lower() for _, u in rows if u == u.lower()}
    for uid, name in rows:
        if name != name.lower() and name.lower() not in taken:
            db.execute("UPDATE userlogin SET username=? WHERE id=?", (name.lower(), uid))
            taken.add(name.lower())
    db.execute("UPDATE userlogin SET password=?", (TEST,))
    db.commit()
    names = [r[0] for r in db.execute("SELECT username FROM userlogin ORDER BY username")]
    chars = db.execute("SELECT COUNT(*) FROM characters").fetchone()[0] if db.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='characters'").fetchone() else 0
    print(f"{path}: {len(names)} accounts, password 1234: {', '.join(names)}; {chars} characters")
    db.close()
