from sim import *
import json
def calib(L, ttk=18.0, loss=0.35, it=14):
    h = hero("warrior", L); hp = mob_hp(L); dmg = mob_dmg(L)
    for i in range(it):
        w, t, l = stats(h, mob(L, hp, dmg), n=200, seed=i)
        if t: hp *= (ttk / t) ** 0.9
        dmg *= (loss / max(l if l is not None else 0.01, 0.01)) ** 0.8
    return hp, dmg
res = {L: calib(L) for L in range(1, 51)}
from paths import out
json.dump(res, open(out('calib_all.json'), 'w'))
import numpy as np
Ls = np.arange(1, 51); H = np.array([res[L][0] for L in Ls]); D = np.array([res[L][1] for L in Ls])
ph = np.polyfit(Ls, H, 2); pd = np.polyfit(Ls, D, 2)
print('hp fit', ph, 'dmg fit', pd)
for L in (1, 5, 10, 20, 30, 40, 50): print(L, round(res[L][0]), round(np.polyval(ph, L)), round(res[L][1], 1), round(np.polyval(pd, L), 1))
