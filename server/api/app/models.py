from datetime import date, datetime

from sqlalchemy import (
    BigInteger, String, Integer, Double, Boolean, Date, DateTime, Text,
    ForeignKey, func,
)
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base


class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    username: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    email: Mapped[str | None] = mapped_column(String(255), nullable=True)
    role: Mapped[str] = mapped_column(String(16), default="user", nullable=False)
    user_type: Mapped[str] = mapped_column(String(16), default="interne", nullable=False)  # interne | externe
    active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    last_login: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class Setting(Base):
    """Réglages éditables à chaud depuis l'app (codes d'invitation, etc.)."""
    __tablename__ = "settings"
    k: Mapped[str] = mapped_column(String(64), primary_key=True)
    v: Mapped[str] = mapped_column(String(255), nullable=False, default="")


class Session(Base):
    __tablename__ = "sessions"
    token_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[int] = mapped_column(Integer, ForeignKey("users.id", ondelete="CASCADE"))
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)


class Pm(Base):
    __tablename__ = "pm"
    code: Mapped[str] = mapped_column(String(64), primary_key=True)
    oi: Mapped[str | None] = mapped_column(String(16))
    op: Mapped[str | None] = mapped_column(String(255))
    com: Mapped[str | None] = mapped_column(String(255), index=True)
    dep: Mapped[str | None] = mapped_column(String(64))
    dep_code: Mapped[str | None] = mapped_column(String(3), index=True)
    etat: Mapped[str | None] = mapped_column(String(32))
    date_pm: Mapped[date | None] = mapped_column(Date)
    lgt: Mapped[int | None] = mapped_column(Integer)
    tot: Mapped[int | None] = mapped_column(Integer)
    osm_lat: Mapped[float | None] = mapped_column(Double)
    osm_lon: Mapped[float | None] = mapped_column(Double)
    source: Mapped[str] = mapped_column(String(16), default="arcep", nullable=False)  # 'arcep' | 'user'
    created_by: Mapped[str | None] = mapped_column(String(64))
    address: Mapped[str | None] = mapped_column(String(255))
    # § 3.4 : un PM disparu du referentiel ARCEP est marque, jamais supprime
    # (les FK sont ON DELETE CASCADE : un DELETE emporterait sa position exacte).
    retired_at: Mapped[datetime | None] = mapped_column(DateTime, index=True)
    # Dernier millesime ARCEP ou ce PM etait present, p.ex. « ZAPM 2026T2 ».
    dataset_version: Mapped[str | None] = mapped_column(String(32))


class PmPosition(Base):
    __tablename__ = "pm_positions"
    pm_code: Mapped[str] = mapped_column(String(64), ForeignKey("pm.code", ondelete="CASCADE"), primary_key=True)
    lat: Mapped[float] = mapped_column(Double, nullable=False)
    lon: Mapped[float] = mapped_column(Double, nullable=False)
    accuracy_m: Mapped[float | None] = mapped_column(Double)
    # § 4.4 : rapide | precise | manuelle | osm. NULL = saisie anterieure au gel
    # de schema. Sans lui, la regle des 10 m ne peut pas arbitrer entre deux
    # saisies de qualites differentes.
    method: Mapped[str | None] = mapped_column(String(16))
    author: Mapped[str | None] = mapped_column(String(255))
    updated_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now(), onupdate=func.now())


class PmPositionHistory(Base):
    __tablename__ = "pm_position_history"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    pm_code: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    lat: Mapped[float] = mapped_column(Double, nullable=False)
    lon: Mapped[float] = mapped_column(Double, nullable=False)
    accuracy_m: Mapped[float | None] = mapped_column(Double)
    method: Mapped[str | None] = mapped_column(String(16))
    author: Mapped[str | None] = mapped_column(String(255))
    recorded_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())


class PmComment(Base):
    __tablename__ = "pm_comments"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    pm_code: Mapped[str] = mapped_column(String(64), ForeignKey("pm.code", ondelete="CASCADE"), nullable=False, index=True)
    body: Mapped[str] = mapped_column(Text, nullable=False)
    author: Mapped[str | None] = mapped_column(String(255))
    # Qui a écrit, par identifiant et non par prénom (§ F13). `author` reste le
    # libellé affiché ; `author_user_id` seul décide du droit de modifier.
    # NULL = contribution d'un compte supprimé : plus personne n'en hérite.
    author_user_id: Mapped[int | None] = mapped_column(Integer)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now(), onupdate=func.now())


class PmConfirmation(Base):
    """Un utilisateur confirme que la position exacte d'un PM est correcte (sans la déplacer)."""
    __tablename__ = "pm_confirmations"
    pm_code: Mapped[str] = mapped_column(String(64), primary_key=True)
    username: Mapped[str] = mapped_column(String(64), primary_key=True)
    confirmed_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now(), onupdate=func.now())


# ---------------------------------------------------------------------------
# Gel de schema (§ 4.3) : les tables ci-dessous sont creees vides, en attendant
# leur interface. Une migration sur une base deja installee chez des
# utilisateurs coute bien plus cher qu'une table inutilisee quelques semaines.
# ---------------------------------------------------------------------------

class PmTag(Base):
    """§ 3.6 — etiquettes, deux familles, multi-choix.

    Une ligne par etiquette posee : l'auteur est conserve, et retirer une
    etiquette est un DELETE, pas la reecriture d'une liste.
    """
    __tablename__ = "pm_tags"
    pm_code: Mapped[str] = mapped_column(
        String(64), ForeignKey("pm.code", ondelete="CASCADE"), primary_key=True)
    tag: Mapped[str] = mapped_column(String(32), primary_key=True)  # acces_haie, site_shelter...
    family: Mapped[str] = mapped_column(String(16), nullable=False)  # acces | site
    author: Mapped[str | None] = mapped_column(String(255))
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())


class PmAccess(Base):
    """§ 3.7 — « comment y acceder » : indication courte + point d'acces facultatif.

    Table distincte de `pm` a dessein : `pm` est reecrite a chaque import ARCEP,
    et ce qui vient des utilisateurs ne doit pas vivre dans une table que
    l'import reecrit (§ 4.2).
    """
    __tablename__ = "pm_access"
    pm_code: Mapped[str] = mapped_column(
        String(64), ForeignKey("pm.code", ondelete="CASCADE"), primary_key=True)
    note: Mapped[str | None] = mapped_column(String(255))
    lat: Mapped[float | None] = mapped_column(Double)   # point d'acces, si different du PM
    lon: Mapped[float | None] = mapped_column(Double)
    author: Mapped[str | None] = mapped_column(String(255))
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now())


class PmPhoto(Base):
    """§ 3.7 — le fichier vit sur le disque, la base garde de quoi le retrouver."""
    __tablename__ = "pm_photos"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    pm_code: Mapped[str] = mapped_column(
        String(64), ForeignKey("pm.code", ondelete="CASCADE"), nullable=False, index=True)
    kind: Mapped[str] = mapped_column(String(16), default="pm", nullable=False)  # pm | acces
    filename: Mapped[str] = mapped_column(String(255), nullable=False)
    sha256: Mapped[str | None] = mapped_column(String(64))
    bytes: Mapped[int | None] = mapped_column(Integer)
    width: Mapped[int | None] = mapped_column(Integer)
    height: Mapped[int | None] = mapped_column(Integer)
    author: Mapped[str | None] = mapped_column(String(255))
    author_user_id: Mapped[int | None] = mapped_column(Integer)   # cf. PmComment (§ F13)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())


class Tombstone(Base):
    """§ 4.1 — pierres tombales, pour la synchro incrementale.

    Une synchro par `since` ne voit que ce qui existe : sans trace des
    suppressions, un commentaire efface sur le serveur resterait sur le
    telephone indefiniment.

    Pas de cle etrangere vers `pm` : une pierre tombale doit survivre a la
    disparition de ce qu'elle decrit — c'est sa seule raison d'etre.
    """
    __tablename__ = "tombstones"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    kind: Mapped[str] = mapped_column(String(16), nullable=False)  # position | comment | ...
    pm_code: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    ref: Mapped[str | None] = mapped_column(String(64))  # id du commentaire, slug d'etiquette...
    deleted_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now(), index=True)
    author: Mapped[str | None] = mapped_column(String(255))
