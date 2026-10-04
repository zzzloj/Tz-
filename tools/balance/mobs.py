import json, glob, math, re
from paths import CONTENT as C, out, here
sp=json.load(open(here('spawns.json')))
def n(v):
    try: return float(v)
    except: return 0.0
rows=[]
for f in glob.glob(C+'/npcs/n.*.json'):
    d=json.load(open(f)); c=d['char']; w=d['war']
    hp=n(c.get('hp_max'))
    if hp<=0: continue
    other=not d['id'].startswith(('n.c.','n.a.'))   # townsfolk, guards, factions, angels
    hit=min(100,n(w.get('hit_chance'))); a=(n(w.get('dmg_min'))+n(w.get('dmg_max')))/2
    dl=max(1,n(w.get('attack_delay')) or 4); ar=n(w.get('armor')); dg=min(60,n(w.get('dodge')))
    ehp=hp*(1+ar/15)/(1-dg/100); edps=max(0.05,hit/100*a/dl)
    rows.append(dict(id=d['id'],name=c.get('name'),hp=hp,hit=hit,avg=a,delay=dl,armor=ar,dodge=dg,exp=n(w.get('exp_value')),
        ehp=ehp,edps=edps,threat=math.sqrt(ehp*edps),animal=d['id'].startswith('n.a.'),other=other,
        spawn=sp.get(d['id'],{}).get('n',0),dist=sp.get(d['id'],{}).get('dist')))
lo=min(r['threat'] for r in rows if r['id']=='n.c.rat'); hi=max(r['threat'] for r in rows if not r['other'])
for r in rows:
    x=(math.log(r['threat'])-math.log(lo))/(math.log(hi)-math.log(lo))
    r['lvl']=max(1,min(50,round(1+49*x)))
    r['shape']=math.log(r['ehp']/r['edps'])   # >0 tanky, <0 glass cannon
med=sorted(r['shape'] for r in rows)[len(rows)//2]
for r in rows: r['shape']-=med
rows.sort(key=lambda r:r['threat'])
json.dump(rows,open(out('mobs_old.json'),'w'),ensure_ascii=False)
if __name__=='__main__':
    for r in rows: print("%-20s %-24s L%2d thr%6.2f shape%5.2f sp%2d d%s"%(r['id'][:20],(r['name'] or '')[:24],r['lvl'],r['threat'],r['shape'],r['spawn'],round(r['dist']) if r['dist'] else '-'))
