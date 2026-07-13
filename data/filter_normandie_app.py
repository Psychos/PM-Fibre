# Filtre pm_full.json (national) -> sous-ensemble Normandie, même schéma.
# L'app garde ainsi, pour chaque PM, le centroïde de zone ARCEP (orientation ≈)
# et les points exacts OSM (p=1). Les positions partagées exactes viennent du serveur.
import json, os

SRC = r'E:\PM\data\pm_full.json'
DST = r'E:\PM\app\src\main\assets\pm_full.json'
NORM = {'CALVADOS', 'EURE', 'MANCHE', 'ORNE', 'SEINE-MARITIME'}

data = json.load(open(SRC, encoding='utf-8'))
sub = [x for x in data if x.get('dep') in NORM]
json.dump(sub, open(DST, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))

from collections import Counter
c = Counter(x['dep'] for x in sub)
print('national:', len(data), '-> Normandie:', len(sub))
for k, v in sorted(c.items()):
    print(f'  {k:16} {v}')
print('p=1 (point exact OSM):', sum(1 for x in sub if x.get('p') == 1))
print('taille asset:', round(os.path.getsize(DST) / 1024, 1), 'Ko (avant:', round(os.path.getsize(SRC) / 1048576, 1), 'Mo)')
