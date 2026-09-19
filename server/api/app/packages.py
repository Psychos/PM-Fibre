"""Lecture des paquets par département produits par `data/build_packages.py`.

Le serveur consomme **les archives que le site sert déjà à l'app** plutôt qu'un
export national séparé : une seule chaîne à régénérer chaque trimestre, et le
millésime ARCEP porté par le manifeste devient la référence commune à l'app,
au site et à la base (§ 3.2, § 4.2).

    /packages/manifest.json
    /packages/deps/<code>.tgz   ->  pm.json + zones.json

Deux usages, volontairement distincts :
  - l'import serveur balaie les 103 paquets une fois par millésime : il lit sans
    rien garder en mémoire ;
  - `geo.py` ne veut qu'un département à la fois, mais souvent : il passe par le
    cache borné ci-dessous (§ 4.7).
"""
from __future__ import annotations

import hashlib
import json
import os
import tarfile
import threading
from collections import OrderedDict
from pathlib import Path

PACKAGES_DIR = Path(os.getenv("PACKAGES_DIR", "/packages"))

# Nombre de départements dont les zones restent en mémoire. Un département pèse
# ~200 Ko de zones décompressées (3 Mo pour le pire, le Nord) ; douze suffisent
# à couvrir largement le plafond de 6 départements par utilisateur (§ 3.1) sans
# revenir au fichier national de 21 Mo chargé d'un bloc.
CACHE_MAX_DEPS = 12

_lock = threading.Lock()
_manifeste: dict | None = None
_zones_cache: "OrderedDict[str, dict]" = OrderedDict()


def _chemin_paquet(code: str) -> Path:
    return PACKAGES_DIR / "deps" / f"{code}.tgz"


def manifeste(recharge: bool = False) -> dict:
    """Manifeste, mis en cache. `recharge=True` pour relire après régénération."""
    global _manifeste
    if _manifeste is None or recharge:
        with _lock:
            if _manifeste is None or recharge:
                try:
                    with open(PACKAGES_DIR / "manifest.json", encoding="utf-8") as f:
                        _manifeste = json.load(f)
                except (OSError, ValueError):
                    _manifeste = {}
    return _manifeste


def version_donnees() -> str:
    """Millésime ARCEP des paquets présents, p.ex. « ZAPM 2026T2 ». Vide si absent."""
    return manifeste().get("dataset", "")


def departements() -> list[dict]:
    return manifeste().get("deps", [])


def codes_departements() -> list[str]:
    return [d["code"] for d in departements() if d.get("code")]


def nom_departement(code: str) -> str | None:
    """« 14 » -> « Calvados ». None si le département n'est pas déployé."""
    for d in departements():
        if d.get("code") == code:
            return d.get("nom")
    return None


def _lit_membre(code: str, membre: str) -> dict | list | None:
    """Extrait un fichier d'un paquet. None si le paquet ou le membre manque."""
    chemin = _chemin_paquet(code)
    if not chemin.is_file():
        return None
    try:
        with tarfile.open(chemin, mode="r:gz") as tar:
            f = tar.extractfile(membre)
            if f is None:
                return None
            return json.loads(f.read().decode("utf-8"))
    except (OSError, tarfile.TarError, ValueError):
        return None


def pm_du_departement(code: str) -> list[dict]:
    """Fiches PM d'un département. Sans cache : sert au balayage de l'import."""
    return _lit_membre(code, "pm.json") or []


def zones_du_departement(code: str) -> dict:
    """Anneaux ZAPM d'un département, avec cache borné (§ 4.7)."""
    with _lock:
        zones = _zones_cache.get(code)
        if zones is not None:
            _zones_cache.move_to_end(code)
            return zones

    # Décompression hors verrou : elle dure des dizaines de ms et ne doit pas
    # bloquer les requêtes portant sur les autres départements.
    zones = _lit_membre(code, "zones.json") or {}

    with _lock:
        _zones_cache[code] = zones
        _zones_cache.move_to_end(code)
        while len(_zones_cache) > CACHE_MAX_DEPS:
            _zones_cache.popitem(last=False)
    return zones


def _verifie_paquet(code: str, entree: dict) -> str | None:
    """Anomalie constatée sur un paquet, ou None s'il est conforme au manifeste.

    On s'arrête à la première anomalie : les quatre contrôles vont du moins cher
    au plus cher, et un paquet dont la taille est déjà fausse n'a rien à
    apprendre de plus.
    """
    chemin = _chemin_paquet(code)
    if not chemin.is_file():
        return f"{code} : paquet absent ({chemin})"

    taille = chemin.stat().st_size
    attendue = entree.get("size")
    if isinstance(attendue, int) and taille != attendue:
        return f"{code} : {taille} octets, {attendue} annonces"

    empreinte = entree.get("sha256")
    if empreinte:
        h = hashlib.sha256()
        try:
            with open(chemin, "rb") as f:
                for bloc in iter(lambda: f.read(1 << 20), b""):
                    h.update(bloc)
        except OSError as e:
            return f"{code} : paquet illisible ({e})"
        if h.hexdigest() != empreinte:
            return (f"{code} : empreinte {h.hexdigest()[:12]}..., "
                    f"{empreinte[:12]}... annoncee")

    # Jusqu'ici on a validé une archive ; reste à valider ce qu'elle contient,
    # car c'est `pm.json` — et lui seul — que l'import balaie.
    fiches = _lit_membre(code, "pm.json")
    if fiches is None:
        return f"{code} : pm.json absent ou illisible dans le paquet"
    if not isinstance(fiches, list):
        return f"{code} : pm.json n'est pas une liste"
    attendues = entree.get("pm")
    if isinstance(attendues, int) and len(fiches) != attendues:
        return f"{code} : {len(fiches)} fiches, {attendues} annoncees"
    return None


def verifie_lot(recharge: bool = False) -> list[str]:
    """Contrôle **l'ensemble** des paquets annoncés, avant tout import.

    Un paquet absent ou abîmé ne se voit pas : `_lit_membre` rend `None`,
    `pm_du_departement` rend une liste vide, et l'import conclut sincèrement que
    le département est vide — il retire ses PM, puis enregistre le millésime
    comme importé. Le redémarrage suivant ne retente donc rien : la panne est
    silencieuse et définitive.

    D'où un contrôle *préalable* et *global*. Préalable, parce qu'un import à
    moitié fait laisse une base incohérente ; global, parce que refuser le lot
    entier est la seule décision sûre quand on ne sait pas lequel des 103
    paquets manque.

    Renvoie la liste des anomalies — vide si le lot est sain.
    """
    m = manifeste(recharge=recharge)
    if not m:
        return [f"manifeste absent ou illisible dans {PACKAGES_DIR}"]
    if not m.get("dataset"):
        return ["manifeste sans millesime (cle « dataset »)"]
    deps = m.get("deps") or []
    if not deps:
        return ["manifeste sans departements (cle « deps »)"]

    anomalies: list[str] = []
    for d in deps:
        code = d.get("code")
        if not code:
            anomalies.append(f"entree de manifeste sans code : {d!r}")
            continue
        souci = _verifie_paquet(code, d)
        if souci:
            anomalies.append(souci)
    return anomalies


def vide_cache() -> None:
    """Après régénération des paquets (nouveau trimestre ARCEP)."""
    global _manifeste
    with _lock:
        _manifeste = None
        _zones_cache.clear()
