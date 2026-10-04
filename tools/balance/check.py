import json, statistics, random
from model import *
from sim import hero, stats, fight
from paths import out
M={m['id']:m for m in json.load(open(out('mobs_new.json')))}
def simmob(m):
    L=m['lvl']
    return dict(name=m['name'],L=L,hp=m['hp'],maxhp=m['hp'],acc=1.6*L+4+m['acc_bonus'],eva=1.3*L+m['eva_bonus'],
                dmin=m['dmin'],dmax=m['dmax'],delay=m['delay'],crit=3,pen=1.0,magic=False,armor=m['armor'],mres=armor_set(L)*0.1,block=(0,0),regen=0)
if __name__=='__main__':
    print('--- newbie, level 1')
    for label,h in [('knife, no points, naked',hero('warrior',1,spend=False,weapon_tier=1,armor_share=0)),
                    ('knife, 4 points, naked',hero('warrior',1,weapon_tier=1,armor_share=0))]:
        for mid in ('n.c.rat','n.c.pauk','n.c.scorpion','n.c.sneak'):
            w,t,l=stats(h,simmob(M[mid]),n=600)
            print(f"{label:26} vs {M[mid]['name']:16} L{M[mid]['lvl']} win {w*100:3.0f}% {t or 0:4.0f}s lost {(l or 0)*100:3.0f}%")
    print('--- same-level reference heroes vs every ordinary monster')
    bad=[]
    for m in sorted(M.values(),key=lambda m:m['lvl']):
        if m['kind'] not in ('mob','animal'): continue
        row=[f"L{m['lvl']:2} {m['name'][:18]:18} hp{m['hp']:4} {m['dmin']}-{m['dmax']}/{m['delay']:.0f}s"]
        for a in ('warrior','archer','mage'):
            w,t,l=stats(hero(a,m['lvl']),simmob(m),n=200)
            row.append(f"{a[0]} {w*100:3.0f}% {t or 0:3.0f}s {(l or 0)*100:3.0f}%")
            if w<0.8 or (t or 99)>40: bad.append((a,m['name'],m['lvl'],round(w*100),round(t or 0)))
        print(' | '.join(row))
    print('outliers',bad)
