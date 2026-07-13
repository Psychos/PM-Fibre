import base64
import hashlib
import hmac
import os
import secrets
from datetime import datetime, timedelta

from fastapi import Depends, Header, HTTPException, status
from sqlalchemy.orm import Session as OrmSession

from .db import get_db
from .models import User, Session as SessionModel


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def admin_usernames() -> list[str]:
    raw = os.getenv("ADMIN_USERNAMES", "")
    return [u.strip().lower() for u in raw.split(",") if u.strip()]


def is_admin_username(username: str) -> bool:
    return username.strip().lower() in admin_usernames()


# ---- Mots de passe (pbkdf2_sha256, lib standard) ----
_PBKDF2_ITER = 200_000


def hash_password(password: str) -> str:
    salt = os.urandom(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, _PBKDF2_ITER)
    return f"pbkdf2_sha256${_PBKDF2_ITER}${base64.b64encode(salt).decode()}${base64.b64encode(dk).decode()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        algo, iters, salt_b64, hash_b64 = stored.split("$")
        if algo != "pbkdf2_sha256":
            return False
        salt = base64.b64decode(salt_b64)
        expected = base64.b64decode(hash_b64)
        dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, int(iters))
        return hmac.compare_digest(dk, expected)
    except Exception:  # noqa: BLE001
        return False


# ---- Sessions ----
def token_hash_from_header(authorization: str | None) -> str | None:
    """Hash du jeton Bearer présenté, ou None si l'en-tête est absent/malformé."""
    if not authorization or not authorization.lower().startswith("bearer "):
        return None
    return _sha256(authorization.split(" ", 1)[1].strip())


def create_session(db: OrmSession, user: User) -> str:
    token = secrets.token_urlsafe(32)
    ttl_days = int(os.getenv("SESSION_TTL_DAYS", "60"))
    # Limite anti-partage de compte : au plus N sessions actives par utilisateur
    # (N appareils). On garde les N-1 plus récentes, la nouvelle complète le quota ;
    # les plus anciennes sont évincées (leur jeton devient 401 → retour au login).
    max_sessions = int(os.getenv("MAX_SESSIONS_PER_USER", "2"))
    if max_sessions > 0:
        existing = (
            db.query(SessionModel)
            .filter(SessionModel.user_id == user.id)
            .order_by(SessionModel.created_at.desc(), SessionModel.token_hash)
            .all()
        )
        for old in existing[max_sessions - 1:]:
            db.delete(old)
    sess = SessionModel(
        token_hash=_sha256(token),
        user_id=user.id,
        expires_at=datetime.utcnow() + timedelta(days=ttl_days),
    )
    db.add(sess)
    user.last_login = datetime.utcnow()
    db.commit()
    return token


def get_current_user(
    authorization: str | None = Header(default=None),
    db: OrmSession = Depends(get_db),
) -> User:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Jeton manquant")
    token = authorization.split(" ", 1)[1].strip()
    sess = db.get(SessionModel, _sha256(token))
    if sess is None or sess.expires_at < datetime.utcnow():
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Session invalide ou expirée")
    user = db.get(User, sess.user_id)
    if user is None or not user.active:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Compte inactif")
    return user


def require_admin(user: User = Depends(get_current_user)) -> User:
    if user.role != "admin":
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Réservé aux administrateurs")
    return user
