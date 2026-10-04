"""Balance model for «Амулет дракона. Reborn»: new scale (levels 1-50, attributes 1-10,
skills 0-10), combat formulas, monster and item curves. Used by sim.py to calibrate and check."""
import math, random

MAX_LEVEL = 50

# ---------------------------------------------------------------- progression
def points_for_level(L):
    """Training points granted on reaching level L (L>=2); creation gives CREATION_POINTS."""
    return 2 + (2 if L % 10 == 0 else 0)
CREATION_POINTS = 4
ATTR_START, ATTR_MAX, ATTR_SUM_MAX = 2, 10, 24
SKILL_MAX, SKILL_SUM_MAX = 10, 100

def total_points(L):
    return CREATION_POINTS + sum(points_for_level(l) for l in range(2, L + 1))

# ---------------------------------------------------------------- hero stats
def hp_max(str_, L):   return 15 + 5 * str_ + 3 * (L - 1)
def mana_max(int_, L): return 10 + 5 * int_ + 2 * (L - 1)

def accuracy(dex, wskill, L):   return 2 * dex + 3 * wskill + L
def evasion(dex, dodge, L):     return dex + 3 * dodge + L
def hit_chance(acc, eva):       return max(15, min(95, 60 + acc - eva))

# owner 04.10: pauses by weapon class (s), dex −2 %/point, floor 0.7 s
PAUSE = {'knife': 1.0, 'spear': 1.0, 'rapier': 1.0, 'thrown': 1.0, 'sword': 1.1, 'axe': 1.2, 'bow': 1.3, 'staff': 1.3,
         'heavy': 1.5, 'crossbow': 1.5, 'hand': 1.0}
def delay(pause, dex):          return max(0.7, pause * (1 - 0.02 * dex))
# crit only from items: base chance by weapon class, base multiplier ×1.5, no caps
CRIT_BASE = {'knife': 7, 'thrown': 7, 'bow': 6, 'crossbow': 6, 'hand': 3}
def crit_chance(cls, bonus=0):  return CRIT_BASE.get(cls, 5) + bonus
CRIT_MULT = 1.5
# armour penetration: heavy weapons ignore 25 %, light ones face 15 % more
def pen_factor(cls):            return 0.75 if cls in ('heavy', 'crossbow') else 1.15 if cls in ('knife', 'rapier', 'thrown') else 1.0

def armor_k(attacker_level):    return 50 + 10 * attacker_level
def armor_cut(armor, attacker_level, pen=1.0):
    """Share of physical damage absorbed by armour (0..1); pen scales the armour the blow meets."""
    a = armor * pen
    return a / (a + armor_k(attacker_level)) if a > 0 else 0.0
def magic_resist(int_, mres, armor): return 5 * int_ + 8 * mres + 0.25 * armor
def magic_cut(resist, attacker_level): return resist / (resist + armor_k(attacker_level)) if resist > 0 else 0.0

def dmg_bonus(str_, wskill, kind):
    """(flat, mult) added to weapon damage: str/2 flat (not crossbows, not magic), +3 % per weapon skill point."""
    flat = 0 if kind in ('crossbow', 'magic') else str_ / 2
    return flat, 1 + 0.03 * wskill

def block(shield, parry, dex):
    """Shield block chance (%) and the share of the blow it stops."""
    return (min(30, 5 + 2 * parry + dex / 2), 0.5) if shield else (0, 0)

# regeneration out of combat: per 5 s, after 10 s without blows
def regen_per_5s(maxhp, regen_skill, safe=False):
    return maxhp * (0.015 + 0.003 * regen_skill) * (3 if safe else 1)

# ---------------------------------------------------------------- item curves
def weapon_dps(L):
    """Average damage per second of a tier-L weapon in hand of a hero with dex 0, no bonuses."""
    return 1.2 + 0.36 * (L - 1)        # L1 ≈1.2, L50 ≈ 18.8
def armor_set(L):
    """Total armour of a full tier-L set (no shield)."""
    return 10 + 6 * (L - 1)             # L1 10, L50 304
ARMOR_SLOTS = {'b': .30, 'h': .15, 'l': .15, 'p': .12, 'c': .08, 'e': .05, 'a': .0, 'd': .0}
def shield_armor(L): return round(0.15 * armor_set(L))
def item_price(L, kind='weapon'):
    f = {'weapon': 1.0, 'armor_set': 2.0, 'shield': 0.5, 'potion': 0.05}.get(kind, 1.0)
    return round(f * (10 + 6 * L ** 2))

# ---------------------------------------------------------------- monster curves
def mob_exp(L, elite=False):
    e = round(8 * L ** 1.45)
    return e * 3 if elite else e

def exp_mod(hero_L, mob_L):
    d = mob_L - hero_L
    if d >= 0: return min(1.5, 1 + 0.08 * d)
    if d >= -4: return 1.0
    return max(0.1, 1 - 0.15 * (-d - 4))

def mob_gold(L): return round(2 + 0.9 * L ** 1.6)

# ---------------------------------------------------------------- calibrated monster curves (calib_all.py)
def mob_hp(L):  return max(18.0, 7.7 + 4.1 * L + 0.122 * L * L)   # ordinary monster (calibrated 04.10 for 1–1.5 s pauses)
def mob_dmg(L): return 2.9 + 0.85 * L + 0.0054 * L * L    # average damage per 4 s (blow = this × pause / 4)
ELITE = {'mob': (1, 1, 1), 'animal': (1, 1, 1), 'citizen': (1, 1, 1), 'elite': (3.0, 1.3, 3), 'guard': (3.0, 1.3, 3), 'boss': (8.0, 1.6, 8)}  # hp, dmg, exp
def exp_to_next(L): return round(25 * L ** 2.7)
