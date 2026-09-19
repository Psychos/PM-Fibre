# -*- coding: utf-8 -*-
"""Decoupe les donnees ARCEP en un paquet par departement + manifeste.

Entrees (versionnees, pas besoin du shapefile source qui n'est plus sur le
poste) :
  - pm_multi.json    : PM avec dep_code, issus de build_multi.py (ZAPM T2 2026)
  - zones_multi.json : anneaux ZAPM simplifies, issus de build_zones_multi.py
  - pm_full.json     : ancien national T1, utilise UNIQUEMENT comme source des
                       centroides de repli (voir ci-dessous)

Sortie : site/public/data/
  - deps/<code>.tgz  : pm.json + zones.json du departement
  - manifest.json    : dataset, generated, max_deps, min_app_version, deps[]

Deux regles de format, imposees par l'app (roadmap 5.1) :

1. Aucune position nulle. PmRepository.kt fait `getDouble("lat")`, non
   optionnel : un null fait planter le chargement. Tout PM sort donc avec une
   position, exacte (`p: 1`) ou approchee (centroide de sa zone ARCEP).
   Le centroide est repris tel quel de pm_full.json quand il y figure, pour que
   l'app continue d'afficher exactement la meme position approchee qu'avant ;
   il n'est recalcule depuis les anneaux que pour les PM apparus en T2.
2. Le marqueur de position exacte est `p: 1`, pas `src: "osm"`. C'est ce que
   lit deja `optInt("p", 0) == 1`. `src` reste interne a pm_multi.json.

Les archives sont deterministes (mtime, uid/gid, ordre figes) : un contenu
inchange donne le meme sha256, sinon le manifeste signalerait une mise a jour
a chaque regeneration.
"""
import gzip
import hashlib
import io
import json
import os
import tarfile
from collections import defaultdict
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT_DIR = os.path.join(ROOT, "site", "public", "data")
DEPS_DIR = os.path.join(OUT_DIR, "deps")

DATASET = "ZAPM 2026T2"
MAX_DEPS = 6        # roadmap 3.1 — relevable sans republier l'APK
MIN_APP_VERSION = 6  # versionCode minimal sachant lire ces paquets (actuel : 5)

# Particules qui restent en minuscules dans un nom de departement.
_PARTICULES = {"de", "du", "des", "la", "le", "les", "d", "l", "et", "sur"}


def joli_nom(nom_majuscules: str) -> str:
    """"SEINE-MARITIME" -> "Seine-Maritime", "COTES-D'ARMOR" -> "Cotes-d'Armor".

    Generalise aux 103 departements : pas de table a tenir a jour.
    """
    if not nom_majuscules:
        return ""
    out = []
    premier = True  # seul le tout premier mot echappe a la regle des particules
    for morceau in _split_garde_separateurs(nom_majuscules.lower()):
        if morceau in ("-", " ", "'"):
            out.append(morceau)
            continue
        if not premier and morceau in _PARTICULES:
            out.append(morceau)          # Hauts-de-Seine, Val-d'Oise, Lot-et-Garonne
        else:
            out.append(morceau.capitalize())
        premier = False
    return "".join(out)


def _split_garde_separateurs(s: str):
    courant = ""
    for c in s:
        if c in "- '":
            if courant:
                yield courant
                courant = ""
            yield c
        else:
            courant += c
    if courant:
        yield courant


def centroide(anneaux) -> tuple[float, float] | None:
    """Centroide pondere par l'aire du plus grand anneau ([lat, lon], ...).

    Repli sur la moyenne des sommets si l'anneau est degenere (aire nulle).
    """
    if not anneaux:
        return None
    anneau = max(anneaux, key=len)
    if len(anneau) < 3:
        return None
    a2 = cx = cy = 0.0
    n = len(anneau)
    for i in range(n):
        y1, x1 = anneau[i]
        y2, x2 = anneau[(i + 1) % n]
        croix = x1 * y2 - x2 * y1
        a2 += croix
        cx += (x1 + x2) * croix
        cy += (y1 + y2) * croix
    if abs(a2) < 1e-12:
        return (sum(p[0] for p in anneau) / n, sum(p[1] for p in anneau) / n)
    return (cy / (3.0 * a2), cx / (3.0 * a2))


def ecrit_tgz(chemin: str, fichiers: dict[str, bytes]) -> None:
    """Archive deterministe : meme contenu -> meme sha256."""
    brut = io.BytesIO()
    with tarfile.open(fileobj=brut, mode="w", format=tarfile.GNU_FORMAT) as tar:
        for nom in sorted(fichiers):
            donnees = fichiers[nom]
            info = tarfile.TarInfo(nom)
            info.size = len(donnees)
            info.mtime = 0
            info.mode = 0o644
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            tar.addfile(info, io.BytesIO(donnees))
    with open(chemin, "wb") as f:
        with gzip.GzipFile(filename="", mode="wb", fileobj=f, mtime=0) as gz:
            gz.write(brut.getvalue())


def sha256(chemin: str) -> str:
    h = hashlib.sha256()
    with open(chemin, "rb") as f:
        for bloc in iter(lambda: f.read(1 << 20), b""):
            h.update(bloc)
    return h.hexdigest()


def main() -> None:
    pm = json.load(open(os.path.join(HERE, "pm_multi.json"), encoding="utf-8"))
    zones = json.load(open(os.path.join(HERE, "zones_multi.json"), encoding="utf-8"))

    print("Chargement des centroides de repli (pm_full.json)...")
    repli = {}
    for r in json.load(open(os.path.join(HERE, "pm_full.json"), encoding="utf-8")):
        if r.get("code") and r.get("lat") is not None:
            repli[r["code"]] = (r["lat"], r["lon"])
    print(f"  {len(repli)} positions disponibles en repli")

    par_dep = defaultdict(list)
    zones_par_dep = defaultdict(dict)
    stats = {"exact": 0, "repli_pm_full": 0, "repli_calcule": 0, "sans_position": 0}

    for r in pm:
        dep = r.get("dep_code")
        code = r.get("code")
        if not dep or not code:
            continue

        lat, lon = r.get("lat"), r.get("lon")
        exact = lat is not None and r.get("src") == "osm"
        if lat is None:
            if code in repli:
                lat, lon = repli[code]
                stats["repli_pm_full"] += 1
            else:
                c = centroide(zones.get(code))
                if c is None:
                    # Sans position ET sans zone : inexploitable par l'app.
                    stats["sans_position"] += 1
                    continue
                lat, lon = round(c[0], 5), round(c[1], 5)
                stats["repli_calcule"] += 1
        else:
            stats["exact"] += 1

        fiche = {
            "code": code, "oi": r.get("oi"), "op": r.get("op"),
            "com": r.get("com"), "dep": r.get("dep"), "dep_code": dep,
            "etat": r.get("etat"), "date": r.get("date"),
            "lgt": r.get("lgt"), "tot": r.get("tot"),
            "lat": lat, "lon": lon,
        }
        if exact:
            fiche["p"] = 1
        par_dep[dep].append(fiche)
        if code in zones:
            zones_par_dep[dep][code] = zones[code]

    os.makedirs(DEPS_DIR, exist_ok=True)

    entrees = []
    for dep in sorted(par_dep):
        fiches = sorted(par_dep[dep], key=lambda x: x["code"])
        nom = joli_nom(fiches[0].get("dep") or "")
        chemin = os.path.join(DEPS_DIR, f"{dep}.tgz")
        ecrit_tgz(chemin, {
            "pm.json": json.dumps(fiches, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
            "zones.json": json.dumps(zones_par_dep[dep], ensure_ascii=False,
                                     separators=(",", ":"), sort_keys=True).encode("utf-8"),
        })
        taille = os.path.getsize(chemin)
        entrees.append({
            "code": dep, "nom": nom, "pm": len(fiches),
            "exact": sum(1 for x in fiches if x.get("p") == 1),
            "zones": len(zones_par_dep[dep]),
            "size": taille, "sha256": sha256(chemin), "url": f"deps/{dep}.tgz",
        })
        print(f"  {dep} {nom:18} {len(fiches):5} PM  {taille/1024:7.1f} Ko")

    manifeste = {
        "dataset": DATASET,
        "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "max_deps": MAX_DEPS,
        "min_app_version": MIN_APP_VERSION,
        "deps": entrees,
    }
    chemin_manifeste = os.path.join(OUT_DIR, "manifest.json")
    with open(chemin_manifeste, "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifeste, f, ensure_ascii=False, indent=2)
        f.write("\n")

    total = sum(e["pm"] for e in entrees)
    poids = sum(e["size"] for e in entrees)
    print(f"\n=== {len(entrees)} departements, {total} PM, {poids/1024/1024:.2f} Mo ===")
    print(f"  position exacte (p=1)      : {stats['exact']}")
    print(f"  repli centroide pm_full    : {stats['repli_pm_full']}")
    print(f"  repli centroide recalcule  : {stats['repli_calcule']}")
    if stats["sans_position"]:
        print(f"  ECARTES (ni position ni zone) : {stats['sans_position']}")
    print(f"\nManifeste : {os.path.relpath(chemin_manifeste, ROOT)}")


if __name__ == "__main__":
    main()
