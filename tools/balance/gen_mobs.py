import json, math
from model import *
from sim import mob
from paths import out as outpath
rows=json.load(open(outpath('mobs_lvl.json')))
def clamp(x,a,b): return max(a,min(b,x))
from sim import hero, stats
# Bosses and elites are for one hero, not a group (owner 06.10): hp ×(1+1.5t) and blow ×(1+0.25t) of an ordinary
# monster of the level, t chosen with sim.py so a warrior of the monster's level (as BalanceFightTest) wins
# about 75 % of fights with a boss or an elite (owner 06.10: «50 % — мало»), and only 45 % with the angels Вельзевул and
# Михаил (owner 06.10: one hero may kill them, with poor odds). The mage alone, without potions, rarely wins.
SOLO_TARGET={'boss':0.75,'elite':0.75}
ANGELS=0.45
OWN_TARGET={'n.c.orckap':0.90,'n.c.orcma':0.90,'n.c.darklord':0.80}   # owner 06.10: orcs ~90 %, the dark lord 80 %
def make(r,L,eh,ed):
    s=r['shape']
    hpf=clamp(math.exp(0.35*s),0.55,1.8); dmgf=clamp(math.exp(-0.35*s),0.55,1.8)
    if r.get('magic'): ed*=caster_dmg(L)   # magic blows go past armour and evasion (see model.caster_dmg)
    delay=round(clamp(clamp(r['delay'],3,8)/3.5,1.0,2.0),1)   # owner 04.10: monsters strike every 1–2 s
    hp=round(mob_hp(L)*hpf*eh); per_blow=mob_dmg(L)*dmgf*ed*delay/4
    arch=dict(acc=clamp((r['hit']-80)/2,-15,10), eva=clamp(r['dodge']/3,0,15),
              armor=clamp(0.5+r['armor']/20,0.5,2.5), delay=delay)
    return dict(hp=hp,dmin=round(per_blow*0.6),dmax=max(1,round(per_blow*1.4)),delay=delay,arch=arch,
                armor=round(armor_set(L)*0.25*arch['armor']),hpf=hpf,dmgf=dmgf,ed=ed)
def as_sim(r,L,m):
    a=m['arch']
    return dict(name=r['name'],L=L,hp=m['hp'],maxhp=m['hp'],acc=1.6*L+4+a['acc'],eva=1.3*L+a['eva'],dmin=m['dmin'],dmax=m['dmax'],
                delay=m['delay'],crit=3,pen=1.0,magic=bool(r.get('magic')),armor=m['armor'],mres=armor_set(L)*0.1,block=(0,0),regen=0)
_heroes={}
def solo_odds(r,L,t):
    m=as_sim(r,L,make(r,L,1+1.5*t,1+0.25*t))
    if L not in _heroes: _heroes[L]=hero('warrior',L)
    return stats(_heroes[L],m,n=200)[0]
def solo_t(r,L,target):
    if solo_odds(r,L,0)<target: return 0.0
    lo,hi=0.0,4.0
    for _ in range(9):
        mid=(lo+hi)/2
        if solo_odds(r,L,mid)>=target: lo=mid
        else: hi=mid
    return lo
out=[]
for r in rows:
    L=r['lvl']
    eh,ed,ex=ELITE[r['kind']]
    if r['kind'] in SOLO_TARGET:
        t=solo_t(r,L,OWN_TARGET.get(r['id'],ANGELS if r['id'].startswith('n.w.') else SOLO_TARGET[r['kind']])); eh,ed=1+1.5*t,1+0.25*t; ex=max(1,round(eh*ed))
    m=make(r,L,eh,ed); arch=m['arch']; delay=m['delay']
    out.append(dict(id=r['id'],name=r['name'],kind=r['kind'],lvl=L,hp=m['hp'],dmin=m['dmin'],dmax=m['dmax'],
        delay=delay,magic=bool(r.get('magic')),acc_bonus=arch['acc'],eva_bonus=round(arch['eva']),armor=m['armor'],
        exp=mob_exp(L)*ex,gold=mob_gold(L)*ex,old=dict(hp=r['hp'],dmg=f"{r['avg']*2:.0f}",exp=r['exp']),
        _a=dict(hp=m['hpf']*eh,dmg=m['dmgf']*m['ed']*delay/4*4/delay,**arch)))
json.dump(out,open(outpath('mobs_new.json'),'w'),ensure_ascii=False,indent=0)
print(len(out))
