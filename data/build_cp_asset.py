# Construit l'asset code_postal -> communes (normalisées) pour la Normandie,
# depuis la base officielle des codes postaux (data.gouv / La Poste).
# Vérifie la couverture des communes présentes dans notre dataset PM.
import csv, json, re, unicodedata

CSV = r"C:\Users\olivi\AppData\Local\Temp\cp_france.csv"
PM = r"E:\PM\app\src\main\assets\pm_full.json"
OUT = r"E:\PM\app\src\main\assets\cp_normandie.json"
DEPS = {"14", "27", "50", "61", "76"}


def norm(s: str) -> str:
    """Normalise pour comparaison : sans accents, majuscules, alphanumérique seul."""
    s = unicodedata.normalize("NFD", s)
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    return re.sub(r"[^A-Z0-9]", "", s.upper())


cp_map = {}  # code_postal -> set de communes normalisées
with open(CSV, encoding="utf-8") as f:
    for row in csv.DictReader(f):
        dep = row["code_departement"].strip().zfill(2)
        if dep not in DEPS:
            continue
        cp = row["code_postal"].strip().zfill(5)
        name = row["nom_commune_complet"].strip()
        if not name:
            continue
        cp_map.setdefault(cp, set()).add(norm(name))

out = {cp: sorted(communes) for cp, communes in sorted(cp_map.items())}
json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))

# Couverture : nos communes PM se retrouvent-elles dans le référentiel ?
pm = json.load(open(PM, encoding="utf-8"))
pm_coms = {norm(x["com"]) for x in pm if x.get("com")}
ref_coms = set()
for communes in cp_map.values():
    ref_coms.update(communes)
missing = pm_coms - ref_coms
print("Codes postaux Normandie:", len(out))
print("Communes PM distinctes:", len(pm_coms), "| couvertes:", len(pm_coms & ref_coms), "| manquantes:", len(missing))
print("Exemples manquantes:", sorted(missing)[:10])
import os
print("Taille asset:", round(os.path.getsize(OUT) / 1024, 1), "Ko")
