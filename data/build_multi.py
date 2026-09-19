# -*- coding: utf-8 -*-
"""Filtre le shapefile ARCEP ZAPM (T2 2026, le plus recent disponible) pour
produire les PM, par defaut sur toute la France.

    python build_multi.py                 # France entiere (103 departements)
    python build_multi.py 14,27,50,61,76  # sous-ensemble

Source brute, non versionnee (~280 Mo extraite), a retelecharger au besoin :
  https://www.data.gouv.fr/datasets/le-marche-du-haut-et-tres-haut-debit-fixe-deploiements
  -> ressource "2026T2-Zapm" (zip 74 Mo), a extraire dans raw_2026T2/extracted/

Positions precises : reutilise les positions OSM deja connues dans
pm_full.json (p=1, issu d'un merge precedent avec PMZ.geojson) par code PM.
Les sources OSM brutes (PMZ.geojson, KML) ne sont plus disponibles sur ce
poste ; leurs positions deja fusionnees restent valides (l'emplacement d'un
PM ne bouge quasiment jamais) et sont donc recyclees ici plutot que perdues.
"""
import json, os, sys
from collections import defaultdict
import shapefile

SHP = "raw_2026T2/extracted/2026T2_ZAPM"
OUT = "pm_multi.json"

# Vide = tous les departements. Le nom affiche vient de NOM_DEP dans la donnee,
# il n'y a donc pas de table de correspondance a tenir a jour.
DEPS = set(sys.argv[1].split(",")) if len(sys.argv) > 1 else set()
NAMES = {}

# 1) Positions OSM connues, recyclees depuis l'ancien merge (pm_full.json, p=1)
print("Chargement des positions OSM connues (pm_full.json)...")
pos = {}
with open("pm_full.json", encoding="utf-8") as f:
    full = json.load(f)
for rec in full:
    if rec.get("p") == 1 and rec.get("code") and rec.get("lat") is not None:
        pos[rec["code"]] = (rec["lat"], rec["lon"])
print("  Positions OSM recyclees:", len(pos))

# 2) Correspondance operateurs
oi_map = json.load(open("oi_map.json", encoding="utf-8"))

# 3) ARCEP ZAPM T2 2026 -> PM des departements retenus
print("Filtrage des PM dans l'ARCEP T2 2026 (%s)..." % (",".join(sorted(DEPS)) if DEPS else "France entiere"))
r = shapefile.Reader(SHP)
fields = [f[0] for f in r.fields[1:]]
idx = {n: i for i, n in enumerate(fields)}


def as_int(v):
    try:
        return int(round(float(v)))
    except Exception:
        return None


out = []
per_dep = defaultdict(lambda: [0, 0])
for rec in r.iterRecords():
    dep = (rec[idx["INSEE_DEP"]] or "").strip()
    if not dep or (DEPS and dep not in DEPS):
        continue
    NAMES.setdefault(dep, (rec[idx["NOM_DEP"]] or "").strip() or dep)
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
        "src": "osm" if p else None,
    }
    out.append(item)
    per_dep[dep][0] += 1
    if p:
        per_dep[dep][1] += 1

with open(OUT, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, separators=(",", ":"))

print(f"\n=== Bilan (7 departements, donnees T2 2026) : {len(out)} PM ===")
tot_known = 0
for dep in sorted(per_dep):
    t, k = per_dep[dep]
    tot_known += k
    print(f"  {dep} {NAMES[dep]:16} : {t:5} PM  |  position connue : {k:5} ({100*k//max(t,1)}%)  |  inconnue : {t-k}")
print(f"  {'TOTAL':23}: {len(out):5} PM  |  position connue : {tot_known} ({100*tot_known//max(len(out),1)}%)  |  inconnue : {len(out)-tot_known}")
print(f"\nFichier: {OUT} ({round(os.path.getsize(OUT)/1024,1)} Ko)")
