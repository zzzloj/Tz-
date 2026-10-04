import json, glob, math
from model import *
from paths import CONTENT as C, out as outpath
def n(v):
    try: return float(v)
    except: return 0.0
def req(d):
    r=d.get('req') or []
    if isinstance(r,str): r=[int(x) for x in r.split(':') if x.strip().isdigit()]
    return (list(r)+[0,0,0])[:3]
W=[];A=[]
for f in glob.glob(C+'/items/i.w.*.json'):
    d=json.load(open(f)); r=req(d); sp=n(d.get('speed')) or 4
    xb=d['id'].startswith('i.w.r.c.'); mag=d.get('verb') in ('магией','молнией')
    avg=(n(d.get('dmg_min'))+n(d.get('dmg_max')))/2+(0 if xb else 3)
    power=avg*(1.35 if mag else 1)/max(3,sp-2)*(1+0.12*sum(r))
    W.append(dict(d=d,r=r,sp=sp,xb=xb,mag=mag,power=power))
for f in glob.glob(C+'/items/i.a.*.json'):
    d=json.load(open(f)); A.append(dict(d=d,r=req(d),slot=d['id'].split('.')[2],ar=n(d.get('armor'))))
W_RANK=0.0
def tiers(items,key):
    items=sorted(items,key=key); N=len(items); vals=[key(i) for i in items]
    lo,hi=math.log(max(vals[0],.05)),math.log(vals[-1])
    for k,i in enumerate(items):
        lg=(math.log(max(key(i),.05))-lo)/(hi-lo) if hi>lo else 0
        i['tier']=max(1,min(50,round(1+49*(W_RANK*k/(N-1)+(1-W_RANK)*lg))))
tiers(W,lambda i:i['power'])
out=[]
for i in W:
    d=i['d']; T=i['tier']; sp=i['sp']
    dps=weapon_dps(T)*(0.75 if i['mag'] else 1)*(1.15 if i['xb'] else 1)
    avg=dps*sp; old=(n(d.get('dmg_min'))+n(d.get('dmg_max')))/2 or 1
    spread=(n(d.get('dmg_max'))-n(d.get('dmg_min')))/2/old if old else .4
    spread=max(.15,min(.6,spread))
    out.append(dict(id=d['id'],name=d['name'],tier=T,old=f"{d.get('dmg_min')}-{d.get('dmg_max')} sp{d.get('speed')} req{i['r']} {d.get('price')}",
        dmg_min=max(0,round(avg*(1-spread))),dmg_max=max(1,round(avg*(1+spread))),speed=sp,
        level=max(1,T-3),req=[min(10,x*2) for x in i['r']],price=item_price(T)))
arm=[]
by={}
for i in A: by.setdefault(i['slot'],[]).append(i)
SH={'b':.30,'h':.15,'l':.15,'p':.12,'c':.08,'e':.05,'s':.15}
for slot,items in by.items():
    real=[i for i in items if i['ar']>0]
    if real and slot in SH: tiers(real,lambda i:i['ar']+sum(i['r'])*0.3)
    for i in items:
        d=i['d']
        if i['ar']<=0 or slot not in SH:
            arm.append(dict(id=d['id'],name=d['name'],slot=slot,tier=0,armor=0,level=1,req=[min(10,x*2) for x in i['r']],price=d.get('price'),old=f"{i['ar']:.0f} req{i['r']} {d.get('price')}")); continue
        T=i['tier']
        arm.append(dict(id=d['id'],name=d['name'],slot=slot,tier=T,armor=round(armor_set(T)*SH[slot]),level=max(1,T-3),
            req=[min(10,x*2) for x in i['r']],price=round(item_price(T)*(0.25 if slot!='s' else 0.5)*SH[slot]/0.15*0.6),
            old=f"{i['ar']:.0f} req{i['r']} {d.get('price')}"))
json.dump(dict(weapons=out,armor=arm),open(outpath('items_new.json'),'w'),ensure_ascii=False,indent=0)
for w in sorted(out,key=lambda w:w['tier']): print(f"T{w['tier']:2} {w['name'][:22]:22} {w['dmg_min']:3}-{w['dmg_max']:<3} sp{w['speed']:.0f} lvl{w['level']:2} req{w['req']} {w['price']:6}  был {w['old']}")
