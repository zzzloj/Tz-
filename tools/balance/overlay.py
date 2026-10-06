"""Writes content/balance/*.json — the new balance for the new engine (owner 04.10.2026).

The old fields in content/items and content/npcs stay as they were: the old
PHP stand is still built from them as the reference. The new engine reads
these overlay files on top.

Run the whole chain:  python3 run.py   (mobs → levels → gen_mobs → gen_items → items_table → overlay)
"""
import json, os
from paths import CONTENT, out
from model import armor_set

M = json.load(open(out('mobs_new.json')))
T = json.load(open(out('items_table.json')))
dst = os.path.join(CONTENT, 'balance'); os.makedirs(dst, exist_ok=True)

def wclass(k, name):
    n = name.lower()
    # A weapon that strikes with magic is a staff whatever its id says (жезл фараона is i.w.s.*).
    try: verb = json.load(open(os.path.join(CONTENT, 'items', k + '.json'))).get('verb')
    except Exception: verb = None
    if verb in ('магией', 'молнией'): return 'staff'
    if k.startswith('i.w.r.c.'): return 'crossbow'
    if k.startswith('i.w.r.b.'): return 'bow'
    if any(x in n for x in ('двуручн', 'алебард', 'секир', 'фламберг', 'двухсторонн', 'глеф', 'длинное копь')): return 'heavy'
    if k.startswith('i.w.r.'): return 'thrown'
    if k.startswith('i.w.k.'): return 'knife'
    if k.startswith('i.w.spear'): return 'spear'
    if 'шпага' in n: return 'rapier'
    if k.startswith('i.w.t.'): return 'axe'
    if k.startswith('i.w.u.'): return 'staff'
    return 'sword'

npcs = {}
for m in M:
    L = m['lvl']
    npcs[m['id']] = dict(level=L, kind=m['kind'], hp=m['hp'], dmg=[m['dmin'], m['dmax']], pause=m['delay'],
                         accuracy=round(1.6 * L + 4 + m['acc_bonus'], 1), evasion=round(1.3 * L + m['eva_bonus'], 1),
                         armor=m['armor'], magicDefence=round(armor_set(L) * 0.1, 1), exp=m['exp'], gold=m['gold'])

items = {}
for w in T['weapons']:
    n = w['new']
    items[w['id']] = dict(type='weapon', **{'class': wclass(w['id'], w['name'])}, tier=n['tier'], level=n['level'], dmg=n['dmg'],
                          pause=n['speed'], req=n['req'], price=n['price'], relic=w['flag'] == 'relic')
for a in T['armor']:
    n = a['new']
    items[a['id']] = dict(type='armor', tier=n['tier'], level=n['level'] or 1, armor=n['armor'], req=n['req'],
                          price=n['price'], relic=a['flag'] == 'relic')
for f in T['food']:
    items[f['id']] = dict(type='food', healPct=f['php'], manaPct=f['pmp'])

# Gems in the new terms (items_table.py has the reasons). Keys: acc, eva (points); *Pct (percent);
# critChance (points of percent), critMult (added to ×1.5), block (points), ailment (ignite|chill|poison), necroPct.
GEMS = {
    'ag': {'acc': 5}, 'am': {'hpPct': 5}, 'az': {'pausePct': -10}, 'do': {'dmgPct': 10, 'acc': -10}, 'dy': {'mdefPct': 5},
    'fa': {'ailment': 'ignite'}, 'gt': {'necroPct': 10}, 'hr': {'mdefPct': 10, 'armorPct': -5, 'manaPct': -5},
    'jd': {'ailment': 'poison'}, 'kc': {'armorPct': 5, 'mdefPct': -5}, 'kp': {'critChance': 3}, 'kr': {'critMult': 0.25},
    'ku': {'block': 3}, 'ld': {'ailment': 'chill'}, 'ma': {'manaPct': 10}, 'me': {'dmgPct': 5},
    'ne': {'hpPct': 10, 'armorPct': -10, 'manaPct': -15}, 'ob': {'armorPct': 5}, 'on': {'armorPct': 10, 'hpPct': -10},
    'sa': {'mdefPct': 10}, 'zm': {'eva': 5},
}
for g, e in GEMS.items(): items['i.i.' + g] = dict(type='gem', effects=e)

# Sets: groups of alternatives (base ids without maker, sharpening or gems); bonus by the number of groups worn.
SETS = [
    dict(name='Драккара', groups=[['i.w.s.dr1'], ['i.a.b.dr1'], ['i.a.h.dr1'], ['i.a.l.dr1'], ['i.a.p.dr1'], ['i.a.e.dr1']],
         bonus={'2': {'hpPct': 5}, '4': {'armorPct': 10}, '6': {'dmgPct': 10, 'hpPct': 5}}),
    dict(name='Волшебный', groups=[['i.w.s.dr'], ['i.a.b.dr'], ['i.a.h.dr'], ['i.a.l.dr'], ['i.a.p.dr'], ['i.a.e.dr']],
         bonus={'3': {'hpPct': 5, 'manaPct': 5}, '6': {'mdefPct': 10}}),
    dict(name='Палладина', groups=[['i.w.s.p'], ['i.a.b.pdosp'], ['i.a.h.p'], ['i.a.l.p'], ['i.a.p.p'], ['i.a.e.p']],
         bonus={'3': {'armorPct': 3}, '6': {'hpPct': 3, 'acc': 3}}),
    dict(name='Мага', groups=[['i.a.b.rmage', 'i.a.b.mmage'], ['i.a.e.mage'], ['i.w.u.jmage', 'i.w.u.pmage', 'i.w.k.smage']],
         bonus={'3': {'manaPct': 5, 'spellPct': 3}}),
    dict(name='Адамантовый', groups=[['i.a.h.ms'], ['i.a.b.sborn'], ['i.a.p.ms'], ['i.a.l.ms'], ['i.w.s.master']],
         bonus={'3': {'armorPct': 3}, '5': {'hpPct': 3, 'dmgPct': 3, 'acc': 3}}),
    dict(name='Огра и тролля', groups=[['i.a.l.ogr'], ['i.a.p.ogr'], ['i.a.b.troll'], ['i.a.h.wolf', 'i.a.h.whitewolf']],
         bonus={'4': {'hpPct': 3, 'armorPct': 3}}),
    dict(name='Обсидиановый', groups=[['i.a.b.obsnagrud'], ['i.a.l.obspon'], ['i.a.p.obspor'], ['i.a.s.obschit']],
         bonus={'4': {'armorPct': 3, 'block': 3}}),
    dict(name='Египетский', groups=[['i.a.h.massf'], ['i.w.s.farmech', 'i.w.s.jezl', 'i.w.r.b.lset']],
         bonus={'2': {'dmgPct': 3, 'acc': 3}}),
    dict(name='Демона', groups=[['i.w.u.jezl'], ['i.a.e.dempl']], bonus={'2': {'spellPct': 3, 'mdefPct': 3}}),
]

def dump(name, obj, note):
    with open(os.path.join(dst, name), 'w') as f:
        json.dump({'_comment': note, **obj}, f, ensure_ascii=False, indent=1, sort_keys=True)
        f.write('\n')
dump('npcs.json', npcs, 'Generated by tools/balance/overlay.py — do not edit by hand. New balance of every NPC (level, health, blow, pause in s).')
dump('items.json', items, 'Generated by tools/balance/overlay.py — do not edit by hand. New balance of weapons, armour, food (percent of maximum), gems.')
dump('sets.json', {'sets': SETS}, 'Generated by tools/balance/overlay.py. Set bonuses by the number of groups worn.')
print(len(npcs), 'npcs,', len(items), 'items,', len(SETS), 'sets ->', dst)
