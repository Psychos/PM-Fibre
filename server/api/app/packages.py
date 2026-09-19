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


def vide_cache() -> None:
    """Après régénération des paquets (nouveau trimestre ARCEP)."""
    global _manifeste
    with _lock:
        _manifeste = None
        _zones_cache.clear()
