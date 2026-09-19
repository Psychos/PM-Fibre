"""Import national des PM, versionné par le millésime ARCEP (§ 4.2, § 4.3).

Remplace `_import_pm_if_empty`, qui ne se déclenchait que sur une base vide :
en production la table `pm` n'a plus jamais été vide depuis la première mise en
service, donc l'import n'a plus jamais tourné et les données sont restées au
périmètre initial.

Le déclencheur est désormais le champ `dataset` du manifeste des paquets
(« ZAPM 2026T2 »), comparé au réglage `pm_dataset_version`. Changer de
trimestre consiste à régénérer les paquets et à redémarrer l'API.

Trois garanties, dans l'ordre d'importance :

1. **Rien de ce qui vient des utilisateurs n'est touché.** L'import n'écrit que
   les colonnes du référentiel ARCEP. `address`, `source`, `created_by` sont
   préservées, et les tables positions / commentaires / confirmations /
   étiquettes / photos ne sont pas ouvertes.

2. **Aucune suppression.** Un PM absent du nouveau millésime est marqué
   `retired_at`, jamais supprimé : les clés étrangères sont ON DELETE CASCADE,
   un DELETE emporterait la position exacte relevée sur le terrain (§ 3.4).

3. **Seules les positions exactes entrent en base.** Les paquets donnent une
   position à chaque PM, mais 88 % sont des centroïdes de zone, calculés pour
   que l'app ait toujours un point à afficher. Recopier ces centroïdes dans
   `osm_lat/osm_lon` reviendrait à les présenter comme des relevés — c'est la
   colonne que `_seed_osm_positions` promeut en position partagée. Seul le
   marqueur `p == 1` est retenu.
"""
from __future__ import annotations

import logging
from datetime import datetime

from sqlalchemy import func, select, text, update
from sqlalchemy.dialects.mysql import insert as mysql_insert
from sqlalchemy.orm import Session as OrmSession

from . import packages
from .models import Pm, PmPosition

log = logging.getLogger(__name__)

CLE_VERSION = "pm_dataset_version"
TAILLE_LOT = 1000

# Colonnes issues du référentiel ARCEP : les seules que l'import a le droit de
# réécrire sur un PM existant.
_COLONNES_ARCEP = ("oi", "op", "com", "dep", "dep_code", "etat", "date_pm", "lgt", "tot")


class LotIncomplet(RuntimeError):
    """Les paquets ne forment pas l'ensemble annoncé : on n'importe rien."""


#: Dernier import refusé, exposé par `/health`. Un import qui ne se fait pas
#: n'a aucun symptôme visible — la base garde simplement l'ancien millésime —
#: et une ligne de journal dans un conteneur ne réveille personne.
dernier_refus: dict | None = None


def _date(valeur: str | None):
    if not valeur:
        return None
    try:
        return datetime.strptime(valeur, "%Y-%m-%d").date()
    except (ValueError, TypeError):
        return None


def _ligne(fiche: dict, version: str) -> dict:
    exact = fiche.get("p") == 1
    return {
        "code": fiche["code"],
        "oi": fiche.get("oi"), "op": fiche.get("op"), "com": fiche.get("com"),
        "dep": fiche.get("dep"), "dep_code": fiche.get("dep_code"),
        "etat": fiche.get("etat"), "date_pm": _date(fiche.get("date")),
        "lgt": fiche.get("lgt"), "tot": fiche.get("tot"),
        # cf. garantie 3 : le centroïde approché reste dans le paquet, pas en base
        "osm_lat": fiche.get("lat") if exact else None,
        "osm_lon": fiche.get("lon") if exact else None,
        "source": "arcep",
        "dataset_version": version,
        "retired_at": None,
    }


def _ecrit_lot(db: OrmSession, lignes: list[dict]) -> None:
    stmt = mysql_insert(Pm.__table__).values(lignes)
    db.execute(stmt.on_duplicate_key_update(
        **{c: getattr(stmt.inserted, c) for c in _COLONNES_ARCEP},
        # Une position OSM connue ne se perd pas si un millésime ultérieur ne la
        # porte plus : on ajoute, on n'efface jamais.
        osm_lat=func.coalesce(stmt.inserted.osm_lat, Pm.__table__.c.osm_lat),
        osm_lon=func.coalesce(stmt.inserted.osm_lon, Pm.__table__.c.osm_lon),
        dataset_version=stmt.inserted.dataset_version,
        retired_at=stmt.inserted.retired_at,
    ))


def importe(db: OrmSession, version: str) -> dict:
    """Balaie tous les paquets et aligne la table `pm`. Renvoie un bilan."""
    avant = db.scalar(select(func.count()).select_from(Pm.__table__)) or 0
    vus = 0

    # Le lot a été validé juste avant ; ce second contrôle, lui, tient pendant
    # le balayage. Une régénération des paquets en cours d'import remplacerait
    # des archives sous nos pieds, et la seule trace serait un département qui
    # rend soudain zéro fiche.
    attendues = {d["code"]: d.get("pm") for d in packages.departements() if d.get("code")}

    for code_dep in packages.codes_departements():
        fiches = packages.pm_du_departement(code_dep)
        attendu = attendues.get(code_dep)
        if isinstance(attendu, int) and len(fiches) != attendu:
            raise LotIncomplet(
                f"{code_dep} : {len(fiches)} fiches lues pendant l'import, "
                f"{attendu} annoncees")
        lot: list[dict] = []
        for fiche in fiches:
            if not fiche.get("code"):
                continue
            lot.append(_ligne(fiche, version))
            if len(lot) >= TAILLE_LOT:
                _ecrit_lot(db, lot)
                vus += len(lot)
                lot = []
        if lot:
            _ecrit_lot(db, lot)
            vus += len(lot)
        db.commit()
        log.info("import %s : %d PM", code_dep, len(fiches))

    # § 3.4 — retrait, jamais suppression.
    retires = db.execute(
        update(Pm.__table__)
        .where(Pm.__table__.c.source == "arcep")
        .where(Pm.__table__.c.retired_at.is_(None))
        .where((Pm.__table__.c.dataset_version.is_(None))
               | (Pm.__table__.c.dataset_version != version))
        .values(retired_at=func.now())
    ).rowcount
    db.commit()

    apres = db.scalar(select(func.count()).select_from(Pm.__table__)) or 0
    return {"version": version, "vus": vus, "avant": avant,
            "apres": apres, "ajoutes": apres - avant, "retires": retires}


def amorce_positions_osm(db: OrmSession) -> int:
    """Publie les positions OSM comme positions partagées, sans résurrection.

    L'ancienne version ne tournait qu'une fois, protégée par un drapeau global :
    la relancer aurait ressuscité toute position supprimée par un admin. Elle ne
    pouvait donc pas servir les départements ajoutés depuis.

    Les pierres tombales lèvent ce blocage : une position supprimée laisse une
    trace, et l'amorçage saute les PM qui en portent une. Il redevient
    réexécutable à chaque millésime, ce qu'exige l'élargissement du périmètre.
    """
    rows = db.execute(
        select(Pm.code, Pm.osm_lat, Pm.osm_lon)
        .outerjoin(PmPosition, Pm.code == PmPosition.pm_code)
        .where(Pm.osm_lat.isnot(None), Pm.osm_lon.isnot(None),
               PmPosition.pm_code.is_(None))
    ).all()
    if not rows:
        return 0

    tombes = {r[0] for r in db.execute(text(
        "SELECT DISTINCT pm_code FROM tombstones WHERE kind = 'position'"
    ))}

    nouvelles = [{"pm_code": c, "lat": lat, "lon": lon, "author": "OSM/import",
                  "method": "osm"}
                 for c, lat, lon in rows if c not in tombes]
    for i in range(0, len(nouvelles), TAILLE_LOT):
        db.execute(PmPosition.__table__.insert().prefix_with("IGNORE"),
                   nouvelles[i:i + TAILLE_LOT])
    db.commit()
    return len(nouvelles)


def protege_suppressions_anterieures(db: OrmSession) -> int:
    """Pose les pierres tombales manquantes, une seule fois, avant le 1er import.

    `amorce_positions_osm` ne saute que les PM portant une pierre tombale. Or
    les positions supprimées par un admin **avant** l'existence de cette table
    n'en ont aucune : au premier démarrage sous le nouveau schéma, elles
    seraient ressuscitées — précisément ce que le drapeau global `osm_seed_done`
    empêchait jusqu'ici.

    Un PM qui a une position OSM connue mais aucune position courante, alors que
    l'amorçage historique a déjà tourné, ne peut être que cela : une suppression
    volontaire. On la matérialise avant que l'import n'ajoute quoi que ce soit.
    """
    deja = db.execute(text(
        "INSERT INTO tombstones (kind, pm_code, author) "
        "SELECT 'position', p.code, 'migration' FROM pm p "
        "LEFT JOIN pm_positions pos ON pos.pm_code = p.code "
        "WHERE p.osm_lat IS NOT NULL AND pos.pm_code IS NULL"
    )).rowcount
    db.commit()
    if deja:
        log.info("%d suppressions anterieures protegees par une pierre tombale", deja)
    return deja


def _refuse(version: str, anomalies: list[str]) -> None:
    """Journalise un refus d'import et le garde pour `/health`."""
    global dernier_refus
    dernier_refus = {"version": version, "anomalies": anomalies[:20],
                     "total": len(anomalies)}
    log.error("Import ARCEP %s REFUSE : %d anomalie(s). Le millesime n'est pas "
              "enregistre : corriger les paquets puis redemarrer l'API relance "
              "l'import a zero.", version, len(anomalies))
    for a in anomalies[:20]:
        log.error("  %s", a)
    if len(anomalies) > 20:
        log.error("  ... et %d autre(s)", len(anomalies) - 20)


def importe_si_necessaire(db: OrmSession, get_setting, set_setting) -> dict | None:
    """Point d'entrée au démarrage. None si les paquets sont déjà en base."""
    global dernier_refus
    version = packages.version_donnees()
    if not version:
        log.warning("Aucun manifeste dans %s : import ignore", packages.PACKAGES_DIR)
        return None

    if get_setting(db, CLE_VERSION) == version:
        return None

    # Tout ou rien (§ F04). Importer un lot amputé coûterait le retrait des PM
    # des départements manquants — et le millésime serait enregistré, donc
    # aucun redémarrage ne rattraperait la perte.
    anomalies = packages.verifie_lot()
    if anomalies:
        _refuse(version, anomalies)
        return None

    # Premier démarrage sous le schéma versionné, sur une base où l'amorçage
    # historique a déjà tourné : rattraper les suppressions qu'il ignorait.
    if not get_setting(db, CLE_VERSION) and get_setting(db, "osm_seed_done") == "1":
        protege_suppressions_anterieures(db)

    log.info("Import ARCEP %s : demarrage (%d paquets verifies)",
             version, len(packages.codes_departements()))
    try:
        bilan = importe(db, version)
    except LotIncomplet as e:
        # Les écritures déjà validées restent, mais elles portent le nouveau
        # millésime sans que rien n'ait été retiré ni enregistré : le prochain
        # démarrage refera le balayage entier. C'est la reprise.
        db.rollback()
        _refuse(version, [str(e)])
        return None
    dernier_refus = None
    bilan["positions_osm"] = amorce_positions_osm(db)
    set_setting(db, CLE_VERSION, version)
    db.commit()
    log.info("Import ARCEP %s : %d PM vus, %+d en base, %d retires, "
             "%d positions OSM amorcees", version, bilan["vus"],
             bilan["ajoutes"], bilan["retires"], bilan["positions_osm"])
    return bilan
