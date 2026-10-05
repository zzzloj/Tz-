"""Reference heroes, monster templates and a time-stepped fight, used to calibrate curves."""
import math, random, statistics
from model import *
MOB_PAUSE = 1.4   # ordinary monster's pause (old 3–8 s → 1–2 s)

# ---------------------------------------------------------------- reference heroes
PLANS = {
    # stat order a point goes to (cycling, each up to its cap); crafts take no points (they grow by practice, 04.10)
    'warrior': [('str', 10), ('cw', 10), ('dex', 10), ('dodge', 10), ('parry', 10), ('regen', 10),
                ('hand', 4), ('awareness', 10)],
    'archer':  [('dex', 10), ('rng', 10), ('str', 8), ('dodge', 10), ('regen', 10), ('hiding', 10),
                ('lore', 10), ('taming', 10)],
    'mage':    [('int', 10), ('magic', 10), ('dex', 8), ('mres', 10), ('medit', 10), ('dodge', 10),
                ('regen', 10), ('healing', 10), ('spirit', 10)],
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

# ---------------------------------------------------------------- the things really there (content/balance/items.json)
import json as _json, os as _os
from paths import CONTENT as _C
_ITEMS = None
CLASSES = {'warrior': ('knife', 'sword', 'axe', 'spear', 'rapier', 'heavy'), 'archer': ('bow', 'crossbow'), 'mage': ('staff',)}
def items():
    global _ITEMS
    if _ITEMS is None:
        p = _os.path.join(_C, 'balance', 'items.json')
        _ITEMS = {k: v for k, v in _json.load(open(p)).items() if not k.startswith('_')} if _os.path.exists(p) else {}
    return _ITEMS

def real_gear(arch, L, s, gear_lag=2):
    """The best weapon of the archetype's classes and the best armour in every slot of item level ≤ L − gear_lag
    whose requirements (str, dex, int) the hero meets — what BalanceFightTest gives the warrior on the server."""
    top = max(1, L - gear_lag)
    def fits(v):
        r = (v.get('req') or [0, 0, 0]) + [0, 0, 0]
        return v.get('level', 1) <= top and s['str'] >= r[0] and s['dex'] >= r[1] and s['int'] >= r[2]
    weapons = [v for v in items().values() if v.get('type') == 'weapon' and v.get('class') in CLASSES[arch] and fits(v)]
    weapon = max(weapons, key=lambda v: (v['dmg'][0] + v['dmg'][1]) / 2 / PAUSE.get(v['class'], 1.2), default=None)
    best = {}
    for k, v in items().items():
        if v.get('type') != 'armor' or not v.get('armor') or not fits(v): continue
        slot = k[:6]
        if slot == 'i.a.s.' and arch != 'warrior': continue
        if v['armor'] > best.get(slot, 0): best[slot] = v['armor']
    return weapon, sum(best.values()), 'i.a.s.' in best

def hero(arch, L, gear_lag=2, spend=True, weapon_tier=None, armor_share=None):
    s = build(arch, L, spend)
    g = s.get
    if weapon_tier is None and armor_share is None and items():
        return hero_real(arch, L, s, gear_lag)
    T = weapon_tier if weapon_tier is not None else tier_worn(L, gear_lag)
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
                meva=evasion(g('dex'), g('mdodge', 0), L),
                dmin=(wmin + flat * pause / 4) * mult, dmax=(wmax + flat * pause / 4) * mult, delay=delay(pause, g('dex')),
                crit=crit_chance(cls), pen=pen_factor(cls), magic=kind == 'magic', armor=armor,
                mres=magic_resist(g('int'), g('mres', 0), armor), block=block(arch == 'warrior', g('parry', 0), g('dex')),
                regen=g('regen', 0), stats=s,
                spell=spell_of(L, g('int'), g('magic', 0), g('dex')) if kind == 'magic' else None,
                mana=mana_max(g('int'), L))

def hero_real(arch, L, s, gear_lag):
    g = s.get
    weapon, armor, shield = real_gear(arch, L, s, gear_lag)
    kind = {'warrior': 'melee', 'archer': 'ranged', 'mage': 'magic'}[arch]
    cls = weapon['class'] if weapon else 'hand'
    pause = PAUSE[cls]
    wmin, wmax = (weapon['dmg'] if weapon else (1, 3))
    wskill = g({'melee': 'cw', 'ranged': 'rng', 'magic': 'magic'}[kind], 0) if weapon else g('hand', 0)
    flat, mult = dmg_bonus(g('str'), wskill, 'crossbow' if cls == 'crossbow' else 'magic' if kind == 'magic' else 'melee')
    hp = hp_max(g('str'), L)
    return dict(name=arch, L=L, hp=hp, maxhp=hp, acc=accuracy(g('dex'), wskill, L), eva=evasion(g('dex'), g('dodge', 0), L),
                meva=evasion(g('dex'), g('mdodge', 0), L),
                dmin=(wmin + flat * pause / 4) * mult, dmax=(wmax + flat * pause / 4) * mult, delay=delay(pause, g('dex')),
                crit=crit_chance(cls), pen=pen_factor(cls), magic=kind == 'magic', armor=armor,
                mres=magic_resist(g('int'), g('mres', 0), armor), block=block(shield, g('parry', 0), g('dex')),
                regen=g('regen', 0), stats=s,
                spell=spell_of(L, g('int'), g('magic', 0), g('dex')) if kind == 'magic' else None,
                mana=mana_max(g('int'), L))

def spell_of(L, int_, magic, dex):
    """The mage's best single-target spell: damage ≈ 1.6 weapon blows of tier L, boosted by int and magic."""
    if magic < 1: return None
    unit = weapon_dps(tier_worn(L)) * 4
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
    # a magic blow meets magic evasion (the server: Formulas.attack), a physical one — evasion
    eva = dfn.get('meva', dfn['eva']) if att['magic'] else dfn['eva']
    if rnd.random() * 100 >= hit_chance(att['acc'], eva): return 0.0
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
