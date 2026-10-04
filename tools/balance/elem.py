"""Elements as formulas, for the owner to try before any code (04.10.2026).
Types: phys (armour), magic (magic defence), fire, cold, light, poison (resistances).
Run: python3 elem.py"""
import random, statistics
from sim import hero, mob, hit_chance, armor_cut, magic_cut
from model import mob_hp, mob_dmg, weapon_dps, armor_set

RES_CAP, RES_FLOOR = 75, -50
# ailments: chance per hit, what they do
IGNITE = dict(share=0.30, dur=3.0)      # fire: burns for 30 % of the hit over 3 s (refreshes, does not stack)
CHILL = dict(chance=0.25, slow=0.30, dur=2.0)  # cold: target's pause +30 % for 2 s
SHOCK = dict(chance=0.20, more=0.15, dur=3.0)  # lightning: target takes +15 % damage for 3 s
POISON = dict(share=0.20, dur=4.0, stacks=3)   # poison: 20 % of the hit over 4 s, up to 3 stacks

def res_of(u, t): return max(RES_FLOOR, min(RES_CAP, u.get('res', {}).get(t, 0)))

def cut(dmg, t, att, dfn, big_hits=False):
    if t == 'phys':
        k = 50 + 10 * att['L'] + (5 * dmg if big_hits else 0)
        return dmg * (1 - (dfn['armor'] / (dfn['armor'] + k) if dfn['armor'] > 0 else 0))
    if t == 'magic': return dmg * (1 - magic_cut(dfn['mres'], att['L']))
    return dmg * (1 - res_of(dfn, t) / 100)

def fight(h, m, rnd, big_hits=False, limit=600, dt=0.05):
    U = [dict(h), dict(m)]
    for u in U: u.update(next=0.0, burn=[], poison=[], chill=0.0, shock=0.0)
    U[1]['next'] = 0.5
    t = 0.0
    while t < limit:
        for i in (0, 1):
            a, d = U[i], U[1 - i]
            if t >= a['next']:
                pause = a['delay'] * (1 + CHILL['slow'] if t < a['chill'] else 1)
                a['next'] = t + pause
                if rnd.random() * 100 < hit_chance(a['acc'], d['eva']):
                    raw = rnd.uniform(a['dmin'], a['dmax'])
                    crit = 2 if rnd.random() * 100 < a['crit'] else 1
                    more = 1 + SHOCK['more'] if t < d['shock'] else 1
                    parts = [(a['type'], raw * (1 - a.get('add', 0)))] + ([(a['add_type'], raw * a['add'])] if a.get('add') else [])
                    for typ, x in parts:
                        dmg = cut(x * crit, typ, a, d, big_hits) * more
                        d['hp'] -= dmg
                        if typ == 'fire': d['burn'] = [t + IGNITE['dur'], dmg * IGNITE['share'] / IGNITE['dur']]
                        if typ == 'cold' and rnd.random() < CHILL['chance']: d['chill'] = t + CHILL['dur']
                        if typ == 'light' and rnd.random() < SHOCK['chance']: d['shock'] = t + SHOCK['dur']
                        if typ == 'poison':
                            d['poison'] = ([p for p in d['poison'] if p[0] > t] + [[t + POISON['dur'], dmg * POISON['share'] / POISON['dur']]])[-POISON['stacks']:]
        for u in U:
            if u['burn'] and t < u['burn'][0]: u['hp'] -= u['burn'][1] * dt
            for p in u['poison']:
                if t < p[0]: u['hp'] -= p[1] * dt
        if U[1]['hp'] <= 0: return True, t, U[0]['hp']
        if U[0]['hp'] <= 0: return False, t, 0
        t += dt
    return False, limit, U[0]['hp']

def stats(h, m, n=300, seed=1, **kw):
    rnd = random.Random(seed); w = 0; ts = []; ls = []
    for _ in range(n):
        ok, t, hp = fight(h, m, rnd, **kw)
        if ok: w += 1; ts.append(t); ls.append(1 - hp / h['maxhp'])
    return w / n, statistics.mean(ts) if ts else None, statistics.mean(ls) if ls else None

def H(arch='warrior', L=25, res=None, add=0, add_type=None):
    h = hero(arch, L); h['type'] = 'magic' if h['magic'] else 'phys'; h['res'] = res or {}
    if add: h['add'], h['add_type'] = add, add_type
    return h
def M(L=25, typ='phys', res=None):
    m = mob(L, mob_hp(L), mob_dmg(L), {'magic': typ != 'phys'}); m['type'] = typ; m['res'] = res or {}
    return m

def row(label, r): w, t, l = r; print(f"  {label:44} победы {w*100:5.1f} %   бой {t or 0:5.1f} с   теряет здоровья {(l or 0)*100:5.1f} %")

if __name__ == '__main__':
    L = 25
    print(f"Воин {L} ур. против монстра {L} ур. (сейчас паузы 4 с — для сравнения стихий друг с другом этого хватает)\n")
    print("1. Монстр бьёт разным уроном, у героя нет сопротивлений:")
    for typ, name in [('phys', 'физический (броня)'), ('magic', 'чистая магия (магзащита)'), ('fire', 'огонь (поджог)'), ('cold', 'холод (замедление)'), ('light', 'молния (шок)'), ('poison', 'яд (отравление)')]:
        row(name, stats(H(), M(L, typ)))
    print("\n2. Огненный монстр, у героя сопротивление огню:")
    for r in (0, 25, 50, 75): row(f"сопротивление огню {r} %", stats(H(res={'fire': r}), M(L, 'fire')))
    print("\n3. Герой с самоцветом «+10 % урона огнём» против монстров с разным отношением к огню:")
    row("без самоцвета, обычный монстр", stats(H(), M(L)))
    row("с самоцветом, обычный монстр", stats(H(add=.10, add_type='fire'), M(L)))
    row("с самоцветом, огненный (сопр. огню 50 %)", stats(H(add=.10, add_type='fire'), M(L, res={'fire': 50})))
    row("с самоцветом, ледяной (огонь −25 %)", stats(H(add=.10, add_type='fire'), M(L, res={'fire': -25})))
    print("\n4. «Крупный удар» против брони (тот же урон в секунду):")
    for arch, label in [('warrior', 'воин')]:
        h = H(arch); m = M(L); m['armor'] = armor_set(L) * 1.0
        print(f"  броня цели {m['armor']:.0f} (полный комплект {L} ступени)")
        for pause in (1.0, 1.5):
            a = dict(h); avg = weapon_dps(L - 2) * pause; a['dmin'], a['dmax'] = avg * .6, avg * 1.4; a['delay'] = pause
            k0 = armor_cut(m['armor'], L); k1 = m['armor'] / (m['armor'] + 50 + 10 * L + 5 * avg)
            print(f"  пауза {pause} с, средний удар {avg:4.1f}: броня гасит {k0*100:4.1f} % сейчас, {k1*100:4.1f} % с «крупным ударом»")
