import json, math
from model import *
from sim import hero
from paths import out as outpath, CONTENT
rows=json.load(open(outpath('mobs_lvl.json')))
def clamp(x,a,b): return max(a,min(b,x))
# Every monster by power (owner 06.10, balance.md §6): its character (the split between health and blow, pause, accuracy,
# evasion, armour) comes from the old one; then health and blow are scaled together by g so that a warrior of the
# monster's level (sim.hero, as BalanceFightTest) stands to it as the target in content/logic/balance.json "power":
# ordinary monsters by the share of health the hero loses killing one (1/r), strong ones by the hero's chance to win.
P=json.load(open(f'{CONTENT}/logic/balance.json'))['power']
def target_ratio(r):
    win=P['win']; lost=P['healthLost']
    if r['id'] in win: return ratio_for_win(win[r['id']]/100)
    if r['kind'] in win: return ratio_for_win(win[r['kind']]/100)
    pct=lost[r['kind']]
    nb=P['newbie']
    if r['kind'] in ('mob','animal') and r['lvl']<nb['levels']:
        pct=nb['healthLost']+(pct-nb['healthLost'])*(r['lvl']-1)/(nb['levels']-1)
    return 100/pct
def make(r,L,g):
    s=r['shape']
    hpf=clamp(math.exp(0.35*s),0.55,1.8); dmgf=clamp(math.exp(-0.35*s),0.55,1.8)
    delay=round(clamp(clamp(r['delay'],3,8)/3.5,1.0,2.0),1)   # owner 04.10: monsters strike every 1–2 s
    hp=max(1,round(mob_hp(L)*hpf*g)); per_blow=mob_dmg(L)*dmgf*g*delay/4
    arch=dict(acc=clamp((r['hit']-80)/2,-15,10), eva=clamp(r['dodge']/3,0,15),
              armor=clamp(0.5+r['armor']/20,0.5,2.5), delay=delay)
    return dict(hp=hp,blow=per_blow,dmin=max(1,round(per_blow*0.6)),dmax=max(1,round(per_blow*1.4)),delay=delay,arch=arch,
                armor=round(armor_set(L)*0.25*arch['armor']),hpf=hpf,dmgf=dmgf)
def as_sim(r,L,m):
    a=m['arch']
    return dict(name=r['name'],L=L,hp=m['hp'],maxhp=m['hp'],acc=1.6*L+4+a['acc'],eva=1.3*L+a['eva'],dmin=m['dmin'],dmax=m['dmax'],
                delay=m['delay'],crit=3,pen=1.0,magic=bool(r.get('magic')),armor=m['armor'],mres=armor_set(L)*0.1,block=(0,0),regen=0)
_heroes={}
def warrior(L):
    if L not in _heroes: _heroes[L]=hero('warrior',L)
    return _heroes[L]
out=[]
for r in rows:
    L=r['lvl']; want=target_ratio(r)
    # r(hero, monster) falls as 1/g² (the monster's health and damage both scale by g)
    g=1.0
    for _ in range(3):
        have=power_ratio(warrior(L),as_sim(r,L,make(r,L,g)))
        g*=math.sqrt(have/want)
    m=make(r,L,g); arch=m['arch']; delay=m['delay']
    # the blow is rounded to whole points; r is exactly 1/health, so health takes up what rounding left
    m['hp']=max(1,round(m['hp']*power_ratio(warrior(L),as_sim(r,L,m))/want))
    # an ordinary fight is not longer than fightMax: less health, a harder blow, the same power
    if r['kind'] in ('mob','animal','citizen'):
        cap=round(P['fightMax']*dps_to(warrior(L),as_sim(r,L,m)))
        if m['hp']>cap:
            q=m['hp']/cap; m['hp']=cap
            m['dmin']=max(1,round(m['blow']*q*0.6)); m['dmax']=max(1,round(m['blow']*q*1.4))
            m['hp']=min(cap,max(1,round(m['hp']*power_ratio(warrior(L),as_sim(r,L,m))/want)))
    # experience and gold follow the danger: how much stronger than an ordinary monster of the level it is by power
    ex=1 if r['kind'] in ('mob','animal') else max(1.0,round(100/P['healthLost']['mob']/want,1))
    out.append(dict(id=r['id'],name=r['name'],kind=r['kind'],lvl=L,hp=m['hp'],dmin=m['dmin'],dmax=m['dmax'],
        delay=delay,magic=bool(r.get('magic')),acc_bonus=arch['acc'],eva_bonus=round(arch['eva']),armor=m['armor'],
        exp=round(mob_exp(L)*ex),gold=round(mob_gold(L)*ex),old=dict(hp=r['hp'],dmg=f"{r['avg']*2:.0f}",exp=r['exp']),
        power=dict(target=round(want,3),g=round(g,3)),
        _a=dict(hp=m['hpf']*g,dmg=m['dmgf']*g,**arch)))
json.dump(out,open(outpath('mobs_new.json'),'w'),ensure_ascii=False,indent=0)
print(len(out))
