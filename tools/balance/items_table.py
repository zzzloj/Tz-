import json,glob,sys
from model import weapon_dps, armor_set, item_price
from paths import CONTENT, out
import os
C=CONTENT+'/items'
I={}
for f in glob.glob(C+'/*.json'):
    d=json.load(open(f)); I[d['id']]=d
NEW=json.load(open(out('items_new.json')))
MAP=os.environ.get('MAP_DATA','')   # optional: item sources for the review table
SRC={i['id']:i['src'] for i in json.load(open(MAP))['items']} if MAP and os.path.isfile(MAP) else {}
def n(v):
    try: return float(v)
    except: return 0.0
def req(d):
    r=d.get('req') or []
    if isinstance(r,str): r=[int(x) for x in r.split(':') if x.strip().lstrip('-').isdigit()]
    return (list(r)+[0,0,0])[:3]
def src(k):
    out=[]
    for s in SRC.get(k.split('..')[0] if k not in SRC else k,[]):
        t=s['t']
        if t=='shop': out.append(('shop','лавка: '+s['who']))
        elif t=='drop': out.append(('drop',f"дроп: {s['who']}"+(f" ({s['lvl']})" if s.get('lvl') else '')))
        elif t=='craft': out.append(('craft','крафт: '+(s.get('skill') or s.get('tool') or '')))
        elif t=='butcher': out.append(('drop','разделка: '+(s.get('who') or '')))
        elif t in('quest','teach'): out.append(('quest','квест: '+(s.get('who') or '')))
        elif t=='world': out.append(('world','лежит в мире'))
        elif t=='gather': out.append(('craft','сбор'))
        elif t=='npc': out.append(('quest','у NPC: '+(s.get('who') or '')))
    seen=[];[seen.append(x) for x in out if x not in seen]
    return seen[:4]
WT={'i.w.k':'Ножи и кинжалы','i.w.r':'Метательное','i.w.r.b':'Луки','i.w.r.c':'Арбалеты','i.w.s':'Мечи','i.w.spear':'Копья','i.w.t':'Топоры','i.w.u':'Посохи и жезлы'}
def wtype(k):
    p=k.split('.')
    if p[2]=='r' and len(p)>3 and p[3] in('b','c'): return WT['i.w.r.'+p[3]]
    return WT.get('.'.join(p[:3]),'Прочее')
SLOT={'b':'Тело','h':'Голова','l':'Ноги','p':'Руки','c':'Обувь','e':'Плащ','s':'Щит','r':'Одежда','w':'Одежда','a':'Аксессуар','o':'Аксессуар','d':'Аксессуар','k':'Украшение','n':'Украшение','m':'Украшение'}
SH={'b':.30,'h':.15,'l':.15,'p':.12,'c':.08,'e':.05,'s':.15}
GEMN={g['id'].split('.')[-1]:g['name'] for g in I.values() if g['id'].startswith('i.i.')}
def gems(k): return [GEMN.get(g,g) for g in k.split('..')[1:]]

# ---- special decisions (proposals) ----
DRAK_W={'i.w.s.dr1..me'}
weapons=[]
for w in NEW['weapons']:
    if w['id'] not in I: continue
    d=I[w['id']]; r=req(d); k=w['id']
    o=dict(id=k,name=w['name'],type=wtype(k),src=src(k),gems=gems(k),
        old=dict(dmg=[int(n(d.get('dmg_min'))),int(n(d.get('dmg_max')))],speed=n(d.get('speed')),req=r,price=d.get('price')),
        new=dict(tier=w['tier'],level=w['level'],dmg=[w['dmg_min'],w['dmg_max']],speed=w['speed'],req=w['req'],price=w['price']),flag='',note='')
    nw=o['new']
    thrown=d.get('ammo')==k
    if thrown:
        nw['price']=max(1,round(w['price']/40)); o['note']='Метательное расходуется: цена за штуку = 1/40 цены оружия ступени.'
    if k in DRAK_W:
        T=50; avg=weapon_dps(T)*6; nw.update(tier=50,level=45,dmg=[round(avg*.6),round(avg*1.4)],req=[0,0,0],price=None)
        o['flag']='relic'; o['note']='Реликвия: награда за большой серверный квест, не продаётся. Ступень 50 без требований к атрибутам, уровень 45. Вместе с латами, шлемом, поножами, поручами и плащом Драккара даёт бонус комплекта.'
    elif k in('i.w.s.dr','i.w.s.dr..me'):
        T=30; avg=weapon_dps(T)*6; nw.update(tier=30,level=27,dmg=[round(avg*.6),round(avg*1.4)],req=[0,0,0],price=None)
        o['flag']='relic'; o['note']='«Волшебный» — младшая версия вещи Драккара (награда поменьше). Ступень 30 без требований к атрибутам, уровень 27, не продаётся.'
    elif k=='i.w.r.c.gazov2':
        T=50; avg=weapon_dps(T)*1.15*5; nw.update(tier=50,level=45,dmg=[round(avg*.8),round(avg*1.2)],price=None)
        o['flag']='relic'; o['note']='Одна из самых ценных вещей оригинала. Предлагаю реликвию ступени 50 (по силе выходил 44), не продаётся.'
    elif k in('i.w.s.dvtop','i.w.r.boebum'):
        T=w['tier']; sp=8; avg=weapon_dps(T)*sp; nw.update(speed=sp,dmg=[round(avg*.8),round(avg*1.2)])
        o['flag']='manual'; o['note']='Скорость 12 — пауза между ударами 12 с, оружие неиграбельно. Предлагаю скорость 8 и урон по ступени.'
    elif k=='i.w.u.vkd':
        nw.update(tier=1,level=1,dmg=[1,2],req=[0,0,0],price=d.get('price'))
        o['flag']='manual'; o['note']='Квестовый полуфабрикат (урон 1–1). Оставить слабым, цена прежняя.'
    elif 'россии' in w['name']:
        nw['price']=10000; o['flag']='manual'; o['note']='Шуточный предмет за 10 000. Предлагаю боевые характеристики как у меча фараона, а цену 10 000 оставить как статусную.'
    # speed per class (owner 04.10: towards PoE2, balance.md §13)
    if o['flag']!='manual' or k in('i.w.s.dvtop','i.w.r.boebum'):
        nm=w['name']
        two=any(x in nm for x in ('двуручн','секира','алебарда','фламберг','двухсторонн','глефа','длинное копье'))
        t=o['type']
        P=1.5 if two or t=='Арбалеты' else {'Ножи и кинжалы':1.0,'Метательное':1.0,'Копья':1.0,'Мечи':1.1,'Топоры':1.2,'Луки':1.3,'Посохи и жезлы':1.3}.get(t,1.2)
        if 'шпага' in nm: P=1.0
        mag=d.get('verb') in ('магией','молнией'); xb=k.startswith('i.w.r.c.')
        T=nw['tier']; avg=weapon_dps(T)*(0.75 if mag else 1)*(1.15 if xb else 1)*P
        od=(n(d.get('dmg_min'))+n(d.get('dmg_max')))/2 or 1
        sp=max(.15,min(.6,(n(d.get('dmg_max'))-n(d.get('dmg_min')))/2/od))
        if k=='i.w.u.vkd': avg=1.5; sp=.33
        nw['speed']=P; nw['dmg']=[max(1,round(avg*(1-sp))),max(1,round(avg*(1+sp)))]
        if k in('i.w.s.dvtop','i.w.r.boebum'): o['note']='Было 12 с между ударами. Теперь пауза по виду оружия, урон по ступени.'
    if k!='i.w.u.vkd':
        nm=w['name']
        heavy=any(x in nm for x in ('двуручн','алебарда','секира','фламберг','двухсторонн','глефа')) or k.startswith('i.w.r.c.')
        light=o['type'] in ('Ножи и кинжалы','Метательное') or 'шпага' in nm
        prop='пробивает 25 % брони' if heavy else ('броня цели считается на 15 % больше' if light else '')
        if prop: o['note']=(prop[0].upper()+prop[1:]+'.'+(' '+o['note'] if o['note'] else ''))
    weapons.append(o)

DRAK={'i.a.b.dr1..ob..am','i.a.h.dr1..ag','i.a.l.dr1..zm..az','i.a.p.dr1..ku','i.a.e.dr1..ma..sa..dy'}
armor=[]
for a in NEW['armor']:
    if a['id'] not in I: continue
    d=I[a['id']]; k=a['id']; r=req(d); slot=a['slot']
    o=dict(id=k,name=a['name'],slot=SLOT.get(slot,slot),src=src(k),gems=gems(k),
        old=dict(armor=int(n(d.get('armor'))),req=r,price=d.get('price')),
        new=dict(tier=a['tier'],level=a['level'],armor=a['armor'],req=a['req'],price=a['price']),flag='',note='')
    nw=o['new']
    if a['tier']==0:
        o['flag']='cosm'; nw.update(tier=None,level=None,armor=0,price=d.get('price'))
    if k in DRAK:
        nw.update(tier=50,level=45,armor=round(armor_set(50)*SH[slot]*1.1),req=[0,0,0],price=None)
        o['flag']='relic'; o['note']='Реликвия Драккара: броня ступени 50 +10 %, без требований к атрибутам, уровень 45, не продаётся.'
    elif '.dr' in k and slot in SH:
        nw.update(tier=30,level=27,armor=round(armor_set(30)*SH[slot]),req=[0,0,0],price=None)
        o['flag']='relic'; o['note']='«Волшебная» вещь сейчас без брони, только самоцветы. Предлагаю броню ступени 30 и уровень 27, не продаётся.'
    elif k=='i.a.m.vlast':
        o['flag']='relic'; o['note']='Особый эффект сохраняется: двойной урон по зомби-горожанам Мёртвого города. Стоит 5 монет — предлагаю реликвию, не продаётся.'; nw['price']=None
    elif k=='i.a.d.msl':
        o['flag']='cosm'; o['note']='Награда. Броня 1 убирается, вещь остаётся знаком отличия.'; nw.update(armor=0,tier=None,level=None)
    armor.append(o)

# ---- gems ----
E={0:'меткость',1:'мин. урон',2:'макс. урон',3:'пауза удара',5:'броня',6:'уклон',7:'парирование',8:'броня щита',9:'уклон от магии',10:'отражение магии',11:'сопр. магии',52:'жизнь',54:'мана'}
GEMNEW={
 'ag':('Меткость +5','Меткость в новой формуле — очки, 1 очко = 1 % шанса попасть.'),
 'am':('Здоровье +5 %','Было +5 при здоровье 20–60; станет процентом, чтобы не обесцениться к 50 уровню (здоровье до ~212).'),
 'az':('Пауза удара −10 %','Было −3 с при паузе 3–5 с, это −60–100 %. Минимум паузы 2 с остаётся.'),
 'do':('Урон +10 %, меткость −10','Было +5 урона (≈+60 % к старому урону) и −20 % меткости.'),
 'dy':('Магзащита +5 %',''),
 'fa':('В оружии: шанс поджога 15 % (ещё 30 % урона удара за 3 с). В ожерелье: поджог на вас вдвое короче','Решение 04.10: стихий с сопротивлениями пока не делаем, вместо урона огнём — шанс поджога.'),
 'gt':('Некромантия: шанс поднять +10 %','Исправлено 04.10: в данных была «пауза удара +10 с»; теперь камень только помогает некромантии, как сказано в описании.'),
 'hr':('Магзащита +10 %, броня −5 %, мана −5 %',''),
 'jd':('В оружии: шанс отравления 20 % (ещё 20 % урона удара за 4 с, до 3 раз). В ожерелье: отравление на вас вдвое короче','Решение 04.10: вместо стихийного урона — эффект.'),
 'kc':('Броня +5 %, магзащита −5 %',''),
 'kp':('Шанс крита +3 %','Сейчас шанс крита +4 %. В новой формуле шанс 3–5 % + 0,5 % за ловкость, +3 % — заметная прибавка.'),
 'kr':('Сила крита +25 % (×2 → ×2,25)','Сейчас в данных «мана +10» — ошибка; описание обещает «критический урон». Отдельной силы крита в игре нет, крит всегда ×2 — предлагаю ввести её этим камнем.'),
 'ku':('Блок щитом +3 %','Блок в новой формуле до 30 %.'),
 'ld':('В оружии: шанс замедления 20 % (пауза цели +30 % на 2 с). В ожерелье: замедление на вас вдвое короче','Решение 04.10: вместо стихийного урона — эффект.'),
 'ma':('Мана +10 %','Было +10 при мане 20–60.'),
 'me':('Урон +5 %','Было +5 к максимальному урону.'),
 'ne':('Здоровье +10 %, броня −10 %, мана −15 %',''),
 'ob':('Броня +5 %','Было +5 при полной броне ~35 у старого комплекта.'),
 'on':('Броня +10 %, здоровье −10 %',''),
 'sa':('Магзащита +5 %, отражение магии +10 %',''),
 'zm':('Уклон +5','Уклон — очки, как меткость.'),
}
gemrows=[]
for k,d in sorted(I.items()):
    if not k.startswith('i.i.'): continue
    g=k.split('.')[-1]
    cur=', '.join(f"{E.get(a,a)} {'+' if b>0 else ''}{b}{' с' if a==3 else ''}" for a,b in d.get('effects',[]))
    nv,why=GEMNEW.get(g,('',''))
    bug='ошибка' in why
    gemrows.append(dict(id=k,name=d['name'],desc=d.get('description',''),cur=cur,new=nv,note=why,flag='manual' if bug or 'механике' in why else '',src=src(k),price=d.get('price')))
ALL={}
def pieces(ids):
    out=[]
    for i in ids:
        x=next((y for y in weapons+armor if y['id']==i),None)
        if x: out.append(f"{x['name']} ({x['new']['tier']})")
    return ', '.join(out)
sets=[
 dict(name='Драккара',kind='Именной',parts=pieces(['i.w.s.dr1..me','i.a.b.dr1..ob..am','i.a.h.dr1..ag','i.a.l.dr1..zm..az','i.a.p.dr1..ku','i.a.e.dr1..ma..sa..dy']),
   cur='нет (только сумма брони и вставленные самоцветы)',new='2 вещи: здоровье +5 %. 4 вещи: броня +10 %. Все 6: урон +10 %, здоровье ещё +5 %',note='Утверждено 04.10. Реликвия, награда за большие серверные квесты.'),
 dict(name='Волшебный',kind='Именной',parts=pieces(['i.w.s.dr','i.a.b.dr','i.a.h.dr','i.a.l.dr','i.a.p.dr','i.a.e.dr']),
   cur='нет (вещи без брони, только самоцветы)',new='3 вещи: здоровье и мана +5 %. Все 6: магзащита +10 %',note='Утверждено 04.10. Младшая награда серверных квестов.'),
 dict(name='Палладина',kind='Именной',parts=pieces(['i.w.s.p','i.a.b.pdosp','i.a.h.p','i.a.l.p','i.a.p.p','i.a.e.p']),
   cur='нет',new='3 вещи: броня +3 %. Все 6: здоровье +3 %, меткость +3',note='Продают кузнецы Натан и Навзил по грамоте.',flag='manual'),
 dict(name='Мага',kind='Именной',parts=pieces(['i.a.b.rmage','i.a.b.mmage','i.a.e.mage','i.w.u.jmage','i.w.u.pmage','i.w.k.smage']),
   cur='нет',new='Роба или мантия + плащ + оружие мага: мана +5 %, урон заклинаний +3 %',note='Тело и оружие — любое из вариантов.',flag='manual'),
 dict(name='Адамантовый',kind='Именной',parts=pieces(['i.a.h.ms','i.a.b.sborn','i.a.p.ms','i.a.l.ms','i.w.s.master']),
   cur='все 5: жизнь +5, мана +5, меткость +5, броня +5, урон +4/+3',new='3 вещи: броня +3 %. Все 5: здоровье +3 %, урон +3 %, меткость +3',note='Был в оригинале (f_calcparam.dat:100). Куётся кузнецом.'),
 dict(name='Огра и тролля',kind='Именной',parts=pieces(['i.a.l.ogr','i.a.p.ogr','i.a.b.troll','i.a.h.wolf','i.a.h.whitewolf']),
   cur='ноги и лапы огра, шкура тролля, голова волка или белого волка: жизнь +5, мана +5, броня +4',new='Все 4: здоровье +3 %, броня +3 %',note='Был в оригинале (f_calcparam.dat:102). Шьётся из шкур.'),
 dict(name='Обсидиановый',kind='Именной',parts=pieces(['i.a.b.obsnagrud','i.a.l.obspon','i.a.p.obspor','i.a.s.obschit']),
   cur='нет',new='Все 4: броня +3 %, блок щитом +3 %',note='Самая тяжёлая броня (ступени 46–50).',flag='manual'),
 dict(name='Египетский (фараон, сфинкс, Сэт)',kind='Именной',parts=pieces(['i.a.h.massf','i.w.s.farmech','i.w.s.jezl','i.w.r.b.lset']),
   cur='нет',new='Маска сфинкса + одно оружие (меч или жезл фараона, лук Сэта): урон +3 %, меткость +3',note='Маска сфинкса бывает двух видов (вторая — награда Навзила за три головы сфинксов). Если были ещё вещи Сэта — назовите, в данных только лук.',flag='manual'),
 dict(name='Демона',kind='Именной',parts=pieces(['i.w.u.jezl','i.a.e.dempl']),
   cur='нет',new='Жезл + плащ демона: урон заклинаний +3 %, магзащита +3 %',note='Пара вещей.',flag='manual'),
]
# ---- food & potions ----
food=[]
def pct(v):
    if v in(None,''): return None
    p=round(n(v)/40*100/5)*5
    return max(5,min(100,p))
for k,d in sorted(I.items()):
    if d.get('heal_hp') in(None,'') and d.get('heal_mana') in(None,''): continue
    hp=n(d.get('heal_hp')) or None; mp=n(d.get('heal_mana')) or None
    food.append(dict(id=k,name=d['name'],kind='Зелья и напитки' if k.startswith('i.f.b.') else 'Еда',hp=hp,mp=mp,php=pct(hp) if hp else None,pmp=pct(mp) if mp else None,price=d.get('price'),src=src(k)))
json.dump(dict(weapons=weapons,armor=armor,gems=gemrows,sets=sets,food=food),open(out('items_table.json'),'w'),ensure_ascii=False)
print(len(weapons),len(armor),len(gemrows),len(food))
import collections
print(collections.Counter(a['flag'] for a in armor), collections.Counter(w['flag'] for w in weapons))
