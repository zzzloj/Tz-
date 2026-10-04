"""Reference heroes, monster templates and a time-stepped fight, used to calibrate curves."""
import math, random, statistics
from model import *
MOB_PAUSE = 1.4   # ordinary monster's pause (old 3–8 s → 1–2 s)

# ---------------------------------------------------------------- reference heroes
PLANS = {
    # stat order a point goes to (cycling, each up to its cap)
    'warrior': [('str', 10), ('cw', 10), ('dex', 10), ('dodge', 10), ('parry', 10), ('regen', 10),
                ('hand', 4), ('awareness', 10), ('lumb', 10), ('mine', 10), ('smith', 10), ('fish', 10)],
    'archer':  [('dex', 10), ('rng', 10), ('str', 8), ('dodge', 10), ('regen', 10), ('hiding', 10),
                ('bow', 10), ('lumb', 10), ('lore', 10), ('taming', 10), ('food', 10)],
    'mage':    [('int', 10), ('magic', 10), ('dex', 8), ('mres', 10), ('medit', 10), ('dodge', 10),
                ('regen', 10), ('alchemy', 10), ('healing', 10), ('spirit', 10)],
}
ATTRS = ('str', 'dex', 'int')

def build(arch, L, spend=True):
    s = {'str': ATTR_START, 'dex': ATTR_START, 'int': ATTR_START}
    pts = total_points(L) if spend else 0
    plan = PLANS[arch]
    while pts > 0:
        moved = False
        for stat, cap in plan:
            if pts == 0: break
            v = s.get(stat, 0)
            if v >= cap: continue
            if stat in ATTRS and sum(s[a] for a in ATTRS) >= ATTR_SUM_MAX: continue
            if stat not in ATTRS and sum(v2 for k, v2 in s.items() if k not in ATTRS) >= SKILL_SUM_MAX: continue
            s[stat] = v + 1; pts -= 1; moved = True
        if not moved: break
    return s

def hero(arch, L, gear_lag=2, spend=True, weapon_tier=None, armor_share=None):
    s = build(arch, L, spend)
    g = s.get
    T = weapon_tier if weapon_tier is not None else max(1, L - gear_lag)
    kind = {'warrior': 'melee', 'archer': 'ranged', 'mage': 'magic'}[arch]
    cls = {'warrior': 'sword', 'archer': 'bow', 'mage': 'staff'}[arch]
    pause = PAUSE[cls]
    wdps = weapon_dps(T) * (0.75 if kind == 'magic' else 1.0)
    avg = wdps * pause
    wmin, wmax = avg * 0.6, avg * 1.4
    wskill = g({'melee': 'cw', 'ranged': 'rng', 'magic': 'magic'}[kind], 0)
    flat, mult = dmg_bonus(g('str'), wskill, 'magic' if kind == 'magic' else 'melee')
    share = armor_share if armor_share is not None else {'warrior': 1.0, 'archer': .7, 'mage': .4}[arch]
    armor = armor_set(T) * share + (shield_armor(T) if arch == 'warrior' else 0)
    hp = hp_max(g('str'), L)
    return dict(name=arch, L=L, hp=hp, maxhp=hp, acc=accuracy(g('dex'), wskill, L), eva=evasion(g('dex'), g('dodge', 0), L),
                dmin=(wmin + flat * pause / 4) * mult, dmax=(wmax + flat * pause / 4) * mult, delay=delay(pause, g('dex')),
                crit=crit_chance(cls), pen=pen_factor(cls), magic=kind == 'magic', armor=armor,
                mres=magic_resist(g('int'), g('mres', 0), armor), block=block(arch == 'warrior', g('parry', 0), g('dex')),
                regen=g('regen', 0), stats=s,
                spell=spell_of(L, g('int'), g('magic', 0), g('dex')) if kind == 'magic' else None,
                mana=mana_max(g('int'), L))

def spell_of(L, int_, magic, dex):
    """The mage's best single-target spell: damage ≈ 1.6 weapon blows of tier L, boosted by int and magic."""
    if magic < 1: return None
    unit = weapon_dps(max(1, L - 2)) * 4
    avg = 1.8 * unit * (1 + 0.04 * int_ + 0.03 * magic)
    return dict(dmin=avg * .7, dmax=avg * 1.3, delay=delay(1.3, dex), cd=10.0, cost=4 + 0.6 * L)

# ---------------------------------------------------------------- monsters
def mob(L, hp, dmg, arch=None, elite=False):
    a = arch or {}
    d = a.get('delay', MOB_PAUSE)
    blow = dmg * d / 4
    return dict(name='mob', L=L, hp=hp * a.get('hp', 1), maxhp=hp * a.get('hp', 1),
                acc=1.6 * L + 4 + a.get('acc', 0), eva=1.3 * L + a.get('eva', 0),
                dmin=blow * 0.6 * a.get('dmg', 1), dmax=blow * 1.4 * a.get('dmg', 1),
                delay=d, crit=3, pen=1.0, magic=a.get('magic', False),
                armor=armor_set(L) * 0.25 * a.get('armor', 1), mres=armor_set(L) * 0.1, block=(0, 0), regen=0)

# ---------------------------------------------------------------- fight
def blow(att, dfn, rnd):
    if rnd.random() * 100 >= hit_chance(att['acc'], dfn['eva']): return 0.0
    d = rnd.uniform(att['dmin'], att['dmax'])
    if att['magic']: d *= 1 - magic_cut(dfn['mres'], att['L'])
    else:
        d *= 1 - armor_cut(dfn['armor'], att['L'], att.get('pen', 1.0))
        bc, bs = dfn['block']
        if bc and rnd.random() * 100 < bc: d *= 1 - bs
    if rnd.random() * 100 < att['crit']: d *= CRIT_MULT
    return d

def fight(h, m, rnd, limit=600):
    h = dict(h); m = dict(m); t = 0.0
    th, tm = 0.0, 0.3            # the hero strikes first, the monster answers a moment later
    sp = h.get('spell'); ready = 0.0; mana = h.get('mana', 0); h['used'] = 0
    while t < limit:
        if th <= tm:
            t = th
            if sp and t >= ready and mana >= sp['cost']:
                a = dict(h, dmin=sp['dmin'], dmax=sp['dmax'], acc=h['acc'] + 20)
                m['hp'] -= blow(a, m, rnd); mana -= sp['cost']; h['used'] += sp['cost']
                ready = t + sp['cd']; th += sp['delay']
            else:
                m['hp'] -= blow(h, m, rnd); th += h['delay']
            if m['hp'] <= 0: return True, t, h['hp']
        else:
            t = tm; h['hp'] -= blow(m, h, rnd); tm += m['delay']
            if h['hp'] <= 0: return False, t, 0
    return False, limit, h['hp']

def stats(h, m, n=400, seed=1):
    rnd = random.Random(seed); wins = 0; ts = []; lost = []
    for _ in range(n):
        w, t, hp = fight(h, m, rnd)
        if w: wins += 1; ts.append(t); lost.append(1 - hp / h['maxhp'])
    return wins / n, (statistics.mean(ts) if ts else None), (statistics.mean(lost) if lost else None)
