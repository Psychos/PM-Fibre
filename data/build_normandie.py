# -*- coding: utf-8 -*-
import json, re, os
from collections import Counter, defaultdict
import xml.etree.ElementTree as ET
import shapefile

SCRATCH = r"C:\Users\olivi\AppData\Local\Temp\claude\E--PM\0f71b5d7-2824-471a-b6ea-aaf845951b97\scratchpad"
DEPS = {"14", "27", "50", "61", "76"}  # Calvados, Eure, Manche, Orne, Seine-Maritime

# 1) Positions OSM connues, par ref ARCEP (union KML + geojson)
print("Chargement des positions OSM connues...")
pos = {}  # ref -> (lat, lon)

# a) geojson PMZ
with open(f"{SCRATCH}/PMZ.geojson", encoding="utf-8") as f:
    gj = json.load(f)
for feat in gj["features"]:
    ref = feat["properties"].get("ref:FR:ARCEP")
    if not ref:
        continue
    g = feat["geometry"]; t = g["type"]; c = g["coordinates"]
    if t == "Point":
        lon, lat = c[0], c[1]
    elif t == "Polygon":
        r = c[0]; lon = sum(p[0] for p in r)/len(r); lat = sum(p[1] for p in r)/len(r)
    elif t == "LineString":
        lon = sum(p[0] for p in c)/len(c); lat = sum(p[1] for p in c)/len(c)
    else:
        continue
    if -6 <= lon <= 10 and 41 <= lat <= 52:
        pos[ref] = (round(lat, 6), round(lon, 6))

# b) KML (fichier du collegue, plus recent) -> priorite
ns = "{http://www.opengis.net/kml/2.2}"
root = ET.parse(f"{SCRATCH}/pm_collegue.kml").getroot()
for pm in root.findall(".//"+ns+"Placemark"):
    d = pm.find(ns+"description")
    desc = d.text if d is not None else ""
    m = re.search(r"ref-FR-ARCEP:\s*([^<]*)", desc or "")
    ref = (m.group(1).strip() if m else "")
    if not ref or ref.upper() == "NC":
        continue
    cc = pm.find(".//"+ns+"coordinates")
    if cc is None:
        continue
    parts = cc.text.strip().split(",")
    lon, lat = float(parts[0]), float(parts[1])
    if -6 <= lon <= 10 and 41 <= lat <= 52:
        pos[ref] = (round(lat, 6), round(lon, 6))

print("  Positions OSM connues (refs uniques):", len(pos))

# 2) Correspondance operateurs
oi_map = json.load(open(f"{SCRATCH}/oi_map.json", encoding="utf-8"))

# 3) ARCEP ZAPM -> PM de Normandie
print("Filtrage des PM de Normandie dans l'ARCEP...")
r = shapefile.Reader(f"{SCRATCH}/zapm/2026T1_ZAPM")
fields = [f[0] for f in r.fields[1:]]
idx = {n: i for i, n in enumerate(fields)}

def as_int(v):
    try: return int(round(float(v)))
    except: return None

out = []
per_dep = defaultdict(lambda: [0, 0])  # dep -> [total, avec position]
for rec in r.iterRecords():
    dep = (rec[idx["INSEE_DEP"]] or "").strip()
    if dep not in DEPS:
        continue
    ref = rec[idx["RefPM"]]
    p = pos.get(ref)
    oi = (rec[idx["CodeOI"]] or "").strip()
    item = {
        "code": ref or None,
        "oi": oi or None,
        "op": oi_map.get(oi, oi) or None,
        "com": rec[idx["NOM_COM"]] or None,
        "dep": rec[idx["NOM_DEP"]] or None,
        "dep_code": dep,
        "etat": rec[idx["EtatPM"]] or None,
        "date": rec[idx["date_debut"]] or None,
        "lgt": as_int(rec[idx["lgtMadPM"]]),
        "tot": as_int(rec[idx["lgtZAPM"]]),
        "lat": p[0] if p else None,
        "lon": p[1] if p else None,
        "src": "osm" if p else None,   # position connue (OSM) ou inconnue
    }
    out.append(item)
    per_dep[dep][0] += 1
    if p: per_dep[dep][1] += 1

with open(f"{SCRATCH}/pm_normandie.json", "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, separators=(",", ":"))

print(f"\n=== Bilan Normandie : {len(out)} PM ===")
names = {"14": "Calvados", "27": "Eure", "50": "Manche", "61": "Orne", "76": "Seine-Maritime"}
tot_known = 0
for dep in sorted(per_dep):
    t, k = per_dep[dep]
    tot_known += k
    print(f"  {dep} {names[dep]:16} : {t:5} PM  |  position connue : {k:5} ({100*k//max(t,1)}%)  |  inconnue : {t-k}")
print(f"  {'TOTAL':23}: {len(out):5} PM  |  position connue : {tot_known} ({100*tot_known//max(len(out),1)}%)  |  inconnue : {len(out)-tot_known}")
print(f"\nFichier: pm_normandie.json ({round(os.path.getsize(f'{SCRATCH}/pm_normandie.json')/1024,1)} Ko)")
