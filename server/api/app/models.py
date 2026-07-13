from datetime import date, datetime

from sqlalchemy import (
    String, Integer, Double, Boolean, Date, DateTime, Text, ForeignKey, func,
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


class PmPosition(Base):
    __tablename__ = "pm_positions"
    pm_code: Mapped[str] = mapped_column(String(64), ForeignKey("pm.code", ondelete="CASCADE"), primary_key=True)
    lat: Mapped[float] = mapped_column(Double, nullable=False)
    lon: Mapped[float] = mapped_column(Double, nullable=False)
    accuracy_m: Mapped[float | None] = mapped_column(Double)
    author: Mapped[str | None] = mapped_column(String(255))
    updated_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now(), onupdate=func.now())


class PmPositionHistory(Base):
    __tablename__ = "pm_position_history"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    pm_code: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    lat: Mapped[float] = mapped_column(Double, nullable=False)
    lon: Mapped[float] = mapped_column(Double, nullable=False)
    accuracy_m: Mapped[float | None] = mapped_column(Double)
    author: Mapped[str | None] = mapped_column(String(255))
    recorded_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())


class PmComment(Base):
    __tablename__ = "pm_comments"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    pm_code: Mapped[str] = mapped_column(String(64), ForeignKey("pm.code", ondelete="CASCADE"), nullable=False, index=True)
    body: Mapped[str] = mapped_column(Text, nullable=False)
    author: Mapped[str | None] = mapped_column(String(255))
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now(), onupdate=func.now())


class PmConfirmation(Base):
    """Un utilisateur confirme que la position exacte d'un PM est correcte (sans la déplacer)."""
    __tablename__ = "pm_confirmations"
    pm_code: Mapped[str] = mapped_column(String(64), primary_key=True)
    username: Mapped[str] = mapped_column(String(64), primary_key=True)
    confirmed_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now(), onupdate=func.now())
