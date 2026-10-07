"""Reference numbers for the MMO's C# balance (mmo/Assets/Tz/Scripts/Rules/TzBalance.cs) from the
balance model tools/balance/model.py, which mirrors the game server's Balance.kt. Writes reference.json;
the dotnet check (Program.cs) recomputes every case in C# and fails on a difference.
Run: python3 mmo/tools/check/reference.py && dotnet run --project mmo/tools/check"""
import json, os, random, sys
here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(here, '..', '..', '..', 'tools', 'balance'))
import model as m

cases = []
def case(fn, args, value): cases.append({'f': fn, 'a': args, 'v': value})

for L in (1, 2, 5, 9, 10, 17, 30, 50):
    case('ExpToNext', [L], m.exp_to_next(L))
    case('TotalPoints', [L], m.total_points(L))
    case('TierWorn', [L], m.tier_worn(L))
    case('MonsterHp', [L], m.mob_hp(L))
    case('MonsterDamagePer4s', [L], m.mob_dmg(L))
    case('MonsterExp', [L], m.mob_exp(L))
    case('MonsterGold', [L], m.mob_gold(L))
    case('WeaponDps', [L], m.weapon_dps(L))
    for s in (0, 3, 10):
        case('HpMax', [s, L], m.hp_max(s, L))
        case('ManaMax', [s, L], m.mana_max(s, L))
        case('Accuracy', [s, 2, L], m.accuracy(s, 2, L))
        case('Evasion', [s, 4, L], m.evasion(s, 4, L))
        case('BlockChance', [s, 10 - s], m.block(True, s, 10 - s)[0])
        case('RegenPerTick', [m.hp_max(s, L), s, 0], m.regen_per_5s(m.hp_max(s, L), s, False))
        case('RegenPerTick', [m.hp_max(s, L), s, 1], m.regen_per_5s(m.hp_max(s, L), s, True))
        case('MagicDefence', [s, 10 - s, 40], m.magic_resist(s, 10 - s, 40))
    for a in (0, 5, 60, 300):
        case('ArmorCut', [a, L, 1.0], m.armor_cut(a, L))
        case('ArmorCut', [a, L, 0.75], m.armor_cut(a, L, 0.75))
        case('MagicCut', [a, L], m.magic_cut(a, L))
    for h in (1, 12, 40, 49):
        case('ExpByGap', [h, L], m.exp_mod(h, L))
for acc, eva in ((0, 0), (10, 70), (80, 0), (30, 25)):
    case('HitChance', [acc, eva], m.hit_chance(acc, eva))
for cls, p in m.PAUSE.items():
    for dex in (0, 5, 10):
        case('Pause', [cls, dex], m.delay(p, dex))
    case('CritBase', [cls], m.crit_chance(cls))
    case('Penetration', [cls], m.pen_factor(cls))

# Duels: random fighters, the power ratio and win chance.
rnd = random.Random(7)
for _ in range(40):
    def fighter():
        L = rnd.randint(1, 50)
        dmin = rnd.randint(1, 30)
        return {'L': L, 'acc': rnd.randint(0, 80), 'eva': rnd.randint(0, 60), 'meva': rnd.randint(0, 60),
                'dmin': dmin, 'dmax': dmin + rnd.randint(0, 20), 'armor': rnd.randint(0, 200), 'mres': rnd.randint(0, 100),
                'block': (rnd.choice([0, 0, 15, 30]), 0.5), 'crit': rnd.choice([3, 5, 7]), 'pen': rnd.choice([0.75, 1.0, 1.15]),
                'magic': rnd.random() < 0.25, 'delay': rnd.choice([1.0, 1.1, 1.3, 1.5]), 'maxhp': rnd.randint(20, 600)}
    a, d = fighter(), fighter()
    r = m.power_ratio(a, d)
    case('Duel', [a, d], [r, m.win_chance(r)])

json.dump(cases, open(os.path.join(here, 'reference.json'), 'w'), ensure_ascii=False)
print(len(cases), 'cases')
