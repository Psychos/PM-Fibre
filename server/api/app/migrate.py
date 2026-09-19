"""Migrations de schéma : scripts SQL numérotés + table de suivi (§ 4.3).

`Base.metadata.create_all` et `db/init/01_schema.sql` créent les tables absentes
mais **jamais** une colonne sur une table existante. Toute évolution passe donc
par un fichier de `server/db/migrations/`, appliqué une fois et tracé.

La feuille de route proposait un `schema_version` unique dans `settings` ; une
table dédiée est retenue à la place, pour le même coût : elle garde la date de
chaque application et signale un fichier ajouté après coup avec un numéro déjà
dépassé, qu'un simple compteur avalerait en silence.

Les scripts doivent rester **réexécutables** (`IF NOT EXISTS`) : MariaDB ne sait
pas annuler un DDL, donc un script interrompu au milieu est repris tel quel.
"""
from __future__ import annotations

import os
import re
from pathlib import Path

from sqlalchemy import text
from sqlalchemy.engine import Engine

MIGRATIONS_DIR = Path(os.getenv("MIGRATIONS_DIR", "/app/migrations"))

_NOM_VALIDE = re.compile(r"^(\d{3})_[a-z0-9_]+\.sql$")


def _decoupe(sql: str) -> list[str]:
    """Découpe un script en instructions.

    Retire les commentaires `--` avant de découper sur `;`, sinon un `;` en
    commentaire ferait éclater l'instruction qui le suit. Les migrations ne
    contiennent ni procédure ni trigger, donc pas de `DELIMITER` à gérer.
    """
    lignes = []
    for ligne in sql.splitlines():
        sans_commentaire = re.sub(r"--.*$", "", ligne)
        if sans_commentaire.strip():
            lignes.append(sans_commentaire)
    return [i.strip() for i in "\n".join(lignes).split(";") if i.strip()]


def migrations_disponibles() -> list[Path]:
    if not MIGRATIONS_DIR.is_dir():
        return []
    return [p for p in sorted(MIGRATIONS_DIR.iterdir())
            if _NOM_VALIDE.match(p.name)]


def applique(engine: Engine) -> list[str]:
    """Applique les migrations en attente. Renvoie les noms appliqués."""
    with engine.begin() as conn:
        conn.execute(text(
            "CREATE TABLE IF NOT EXISTS schema_migrations ("
            " nom VARCHAR(128) PRIMARY KEY,"
            " applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        ))

    with engine.connect() as conn:
        deja = {r[0] for r in conn.execute(text("SELECT nom FROM schema_migrations"))}

    appliquees = []
    for chemin in migrations_disponibles():
        if chemin.name in deja:
            continue
        sql = chemin.read_text(encoding="utf-8")
        # Une instruction par transaction : MariaDB committe implicitement chaque
        # DDL de toute façon, un BEGIN global donnerait une fausse impression
        # d'atomicité.
        for instruction in _decoupe(sql):
            with engine.begin() as conn:
                conn.execute(text(instruction))
        with engine.begin() as conn:
            conn.execute(text("INSERT INTO schema_migrations (nom) VALUES (:n)"),
                         {"n": chemin.name})
        appliquees.append(chemin.name)

    return appliquees
