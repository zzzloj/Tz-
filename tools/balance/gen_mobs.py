import json, math
from model import *
from sim import mob
from paths import out as outpath
rows=json.load(open(outpath('mobs_lvl.json')))
def clamp(x,a,b): return max(a,min(b,x))
# Angels that one hero should be able to beat, rarely (owner 06.10): hp, dmg, exp factors chosen with sim.py so a
# warrior or an archer of level 50 wins about 40–55 % of fights alone.
SOLO_BOSS={'n.w.Veelzevul':(2.2,1.25,3),'n.w.Mihail':(2.2,1.2,3)}
out=[]
for r in rows:
    L=r['lvl']; s=r['shape']
    hpf=clamp(math.exp(0.35*s),0.55,1.8); dmgf=clamp(math.exp(-0.35*s),0.55,1.8)
    eh,ed,ex=ELITE[r['kind']]
    if r['id'] in SOLO_BOSS: eh,ed,ex=SOLO_BOSS[r['id']]   # owner 06.10: one hero of level 50 may kill them, with poor odds
    if r.get('magic'): ed*=caster_dmg(L)   # magic blows go past armour and evasion (see model.caster_dmg)
    delay=round(clamp(clamp(r['delay'],3,8)/3.5,1.0,2.0),1)   # owner 04.10: monsters strike every 1–2 s
    hp=round(mob_hp(L)*hpf*eh); per_blow=mob_dmg(L)*dmgf*ed*delay/4
    arch=dict(acc=clamp((r['hit']-80)/2,-15,10), eva=clamp(r['dodge']/3,0,15),
              armor=clamp(0.5+r['armor']/20,0.5,2.5), delay=delay)
    out.append(dict(id=r['id'],name=r['name'],kind=r['kind'],lvl=L,hp=hp,dmin=round(per_blow*0.6),dmax=max(1,round(per_blow*1.4)),
        delay=delay,magic=bool(r.get('magic')),acc_bonus=arch['acc'],eva_bonus=round(arch['eva']),armor=round(armor_set(L)*0.25*arch['armor']),
        exp=mob_exp(L)*ex,gold=mob_gold(L)*ex,old=dict(hp=r['hp'],dmg=f"{r['avg']*2:.0f}",exp=r['exp']),
        _a=dict(hp=hpf*eh,dmg=dmgf*ed*delay/4*4/delay,**arch)))
json.dump(out,open(outpath('mobs_new.json'),'w'),ensure_ascii=False,indent=0)
print(len(out))
