import json, math, re
from collections import Counter
from paths import out
rows=json.load(open(out('mobs_old.json')))
BOSS={'n.c.officer','n.c.guildmaster','n.c.darklord','n.c.demogn','n.c.zemkor','n.a.b.jarpt','n.c.beginner',
      'n.c.mumyfar','n.c.palset','n.c.darkinkv','n.c.orcma','n.c.orckap','n.c.zsfinks','n.c.ksfinks','n.c.osfinks','n.c.wolfsvita','n.c.wolfwar'}
ELITE_X={'elite':2.5,'boss':5.0,'guard':2.5}
for r in rows:
    named=bool(re.match(r'n\.c\.[A-Z]',r['id'])) or '-зомби' in (r['name'] or '') or '[' in (r['name'] or '')
    if r.get('other'):
        # Not monsters (04.10): guards and faction warriors fight like elites; angels are bosses; townsfolk ordinary.
        g=r['id'].startswith(('n.g.','n.o.')) or r['id']=='n.m.gnomcastle' or __import__('re').match(r'n\.[tp]\.\d',r['id'])
        r['kind']='boss' if r['id'].startswith('n.w.') else ('guard' if g else 'citizen')
    else:
        r['kind']='boss' if r['id'] in BOSS else ('elite' if named or r['id'].startswith('n.c.gol.') else ('animal' if r['animal'] else 'mob'))   # golems wake in mines (06.10)
ords=sorted([r for r in rows if r['kind']=='mob'],key=lambda r:r['threat'])
N=len(ords); lo=math.log(ords[0]['threat']); hi=math.log(ords[-1]['threat'])
for i,r in enumerate(ords):
    rank=i/(N-1); lg=(math.log(r['threat'])-lo)/(hi-lo)
    r['lvl']=max(1,min(50,round(1+49*(0.6*rank+0.4*lg))))
def level_of(threat):
    below=[m for m in ords if m['threat']<=threat]
    if not below: return 1
    if len(below)==N:   # stronger than every ordinary monster: extrapolate in log
        top=ords[-1]; return min(50, round(top['lvl']+12*math.log(threat/top['threat'])))
    return below[-1]['lvl']
for r in rows:
    if r['kind']=='animal': r['lvl']=min(25,level_of(r['threat']))
    elif r['kind'] in ELITE_X: r['lvl']=level_of(r['threat']/ELITE_X[r['kind']])
    elif r['kind']=='citizen': r['lvl']=level_of(r['threat'])
    if r['kind']=='guard': r['lvl']=max(r['lvl'],30)   # guards outclass the criminals they chase
    if r['id'].startswith('n.w.'): r['lvl']=50          # the angels
    if r['id'].startswith('n.c.gol.'): r['lvl']=30      # golems in the mines: elite of level 30 (06.10)
# Bosses (owner 06.10): the strongest ordinary monster within three steps of where the boss appears, plus 2, at most 50;
# a boss with no monsters around keeps its level from threat.
import glob, os
from paths import CONTENT, here
exits={}
for f in glob.glob(os.path.join(CONTENT,'locations','*.json')):
    d=json.load(open(f)); exits[d['id']]=[e['target'] for e in d.get('exits',[]) if 'target' in e]
spawns=json.load(open(here('spawns.json')))
by_id={r['id']:r for r in rows}
mobs_at={}
for k,v in spawns.items():
    if k in by_id and by_id[k]['kind'] in ('mob','animal'):
        for l in v['locs']: mobs_at.setdefault(l,[]).append(by_id[k]['lvl'])
def around(start,steps=3):
    seen={start:0}; queue=[start]
    while queue:
        x=queue.pop(0)
        if seen[x]<steps:
            for y in exits.get(x,[]):
                if y not in seen: seen[y]=seen[x]+1; queue.append(y)
    return seen
for r in rows:
    if r['kind']!='boss' or r['id'].startswith('n.w.'): continue
    near=[lv for l in spawns.get(r['id'],{}).get('locs',[]) for x in around(l) for lv in mobs_at.get(x,[])]
    if near: r['lvl']=min(50,max(near)+2)
json.dump(rows,open(out('mobs_lvl.json'),'w'),ensure_ascii=False)
if __name__=='__main__':
    print('ordinary',N)
    for k in ('mob','elite','boss','animal'):
        c=Counter((r['lvl']-1)//5 for r in rows if r['kind']==k)
        print(k,{f"{b*5+1}-{b*5+5}":c[b] for b in sorted(c)})
    for r in sorted(rows,key=lambda r:(r['lvl'],r['kind'])): print(r['lvl'],r['kind'],r['id'],r['name'])
