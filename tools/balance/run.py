"""The whole balance chain: old content → monster levels → new monsters → new items → content/balance/."""
import runpy, os, sys
os.chdir(os.path.dirname(os.path.abspath(__file__))); sys.path.insert(0, '.')
for step in ('mobs', 'levels', 'gen_mobs', 'gen_items', 'items_table', 'overlay'):
    print('==', step); runpy.run_path(step + '.py', run_name='step')
