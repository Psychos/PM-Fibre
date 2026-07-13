import json
import math
import os
import secrets
from datetime import datetime

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, status
from sqlalchemy import select, func
from sqlalchemy.orm import Session as OrmSession

from .db import Base, engine, get_db, wait_for_db
from . import models
from .models import (
    User, Pm, PmPosition, PmPositionHistory, PmComment, PmConfirmation,
    Session as SessionModel, Setting,
)
from . import schemas
from . import auth
from . import ratelimit
from . import geo

app = FastAPI(title="PM Fibre API", version="1.0.0")

PM_DATA_PATH = os.getenv("PM_DATA_PATH", "/data/pm_normandie.json")

# Distance mini (m) entre l'ancienne et la nouvelle position d'un MÊME PM pour
# accepter une modification. En-dessous = considéré identique, refusé.
MIN_MOVE_METERS = 10.0


def _haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlmb = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlmb / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def _import_pm_if_empty(db: OrmSession) -> None:
    count = db.scalar(select(func.count()).select_from(Pm))
    if count and count > 0:
        return
    if not os.path.exists(PM_DATA_PATH):
        return
    with open(PM_DATA_PATH, "r", encoding="utf-8") as f:
        rows = json.load(f)
    for r in rows:
        d = r.get("date")
        try:
            date_pm = datetime.strptime(d, "%Y-%m-%d").date() if d else None
        except ValueError:
            date_pm = None
        db.merge(Pm(
            code=r["code"], oi=r.get("oi"), op=r.get("op"), com=r.get("com"),
            dep=r.get("dep"), dep_code=r.get("dep_code"), etat=r.get("etat"),
            date_pm=date_pm, lgt=r.get("lgt"), tot=r.get("tot"),
            osm_lat=r.get("lat"), osm_lon=r.get("lon"),
        ))
    db.commit()


def _seed_osm_positions(db: OrmSession) -> int:
    """Injecte les positions OSM (pm.osm_lat/lon) comme positions partagées.
    NE TOURNE QU'UNE FOIS (flag en base) : sinon toute position supprimée par un
    admin ressusciterait à chaque redémarrage de l'API."""
    if get_setting(db, "osm_seed_done") == "1":
        return 0
    rows = db.execute(
        select(Pm.code, Pm.osm_lat, Pm.osm_lon)
        .outerjoin(PmPosition, Pm.code == PmPosition.pm_code)
        .where(Pm.osm_lat.isnot(None), Pm.osm_lon.isnot(None), PmPosition.pm_code.is_(None))
    ).all()
    for code, lat, lon in rows:
        db.add(PmPosition(pm_code=code, lat=lat, lon=lon, author="OSM/import"))
    set_setting(db, "osm_seed_done", "1")
    db.commit()
    return len(rows)


# ---- Réglages (table settings, éditables depuis l'app) ----
def get_setting(db: OrmSession, key: str, default: str = "") -> str:
    s = db.get(Setting, key)
    return s.v if s is not None else default


def set_setting(db: OrmSession, key: str, value: str) -> None:
    s = db.get(Setting, key)
    if s is None:
        db.add(Setting(k=key, v=value))
    else:
        s.v = value


def _seed_invitation_codes(db: OrmSession) -> None:
    """Initialise les 2 codes s'ils n'existent pas (reprend l'ancien INVITATION_CODE pour les internes)."""
    import secrets as _secrets
    if db.get(Setting, "invite_code_interne") is None:
        legacy = os.getenv("INVITATION_CODE", "").strip()
        set_setting(db, "invite_code_interne", legacy or f"INTERNE-{_secrets.randbelow(9000) + 1000}")
    if db.get(Setting, "invite_code_externe") is None:
        set_setting(db, "invite_code_externe", f"VISITEUR-{_secrets.randbelow(9000) + 1000}")
    db.commit()


@app.on_event("startup")
def on_startup() -> None:
    wait_for_db()
    Base.metadata.create_all(engine)  # filet de sécurité si init SQL absent
    db = next(get_db())
    try:
        _import_pm_if_empty(db)
        _seed_osm_positions(db)
        _seed_invitation_codes(db)
    finally:
        db.close()


@app.get("/health")
def health():
    return {"status": "ok"}


# =========================================================================
# AUTH (prénom + mot de passe)
# =========================================================================
@app.post("/auth/register", response_model=schemas.TokenResponse)
def register(req: schemas.RegisterRequest, request: Request, db: OrmSession = Depends(get_db)):
    # Codes d'invitation (anti-parasites) : le code saisi détermine le type de compte.
    code_interne = get_setting(db, "invite_code_interne").strip()
    code_externe = get_setting(db, "invite_code_externe").strip()
    user_type = "interne"
    if code_interne or code_externe:
        ip = request.headers.get("cf-connecting-ip") or (request.client.host if request.client else "?")
        # Limite PAR IP (clé dérivée de l'IP) : une clé globale permettrait à un
        # attaquant de bloquer l'inscription pour tout le monde.
        wait = ratelimit.seconds_to_wait(f"reg:{ip}", ip)
        if wait is not None:
            raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS,
                                f"Trop de tentatives. Réessayez dans {int(wait)} s.")
        given = req.invitation_code.strip().lower()
        if code_interne and given == code_interne.lower():
            user_type = "interne"
        elif code_externe and given == code_externe.lower():
            user_type = "externe"
        else:
            ratelimit.record_failure(f"reg:{ip}", ip)
            raise HTTPException(status.HTTP_403_FORBIDDEN,
                                "Code d'invitation invalide. Demande-le à un collègue déjà inscrit.")
    username = req.username.strip()
    # Unicité insensible à la casse (prénom = identifiant).
    existing = db.scalar(select(User).where(func.lower(User.username) == username.lower()))
    if existing is not None:
        raise HTTPException(status.HTTP_409_CONFLICT, "Ce prénom est déjà pris")
    role = "admin" if auth.is_admin_username(username) else "user"
    user = User(
        username=username,
        password_hash=auth.hash_password(req.password),
        email=(req.email.lower() if req.email else None),
        role=role,
        user_type=user_type,
        active=True,
    )
    db.add(user)
    db.commit()
    token = auth.create_session(db, user)
    return schemas.TokenResponse(token=token, username=user.username, role=user.role)


@app.post("/auth/login", response_model=schemas.TokenResponse)
def login(req: schemas.LoginRequest, request: Request, db: OrmSession = Depends(get_db)):
    username = req.username.strip()
    ip = request.headers.get("cf-connecting-ip") or (request.client.host if request.client else "?")
    wait = ratelimit.seconds_to_wait(username, ip)
    if wait is not None:
        raise HTTPException(
            status.HTTP_429_TOO_MANY_REQUESTS,
            f"Trop de tentatives. Réessayez dans {int(wait)} s.",
        )
    user = db.scalar(select(User).where(func.lower(User.username) == username.lower()))
    if user is None or not auth.verify_password(req.password, user.password_hash):
        ratelimit.record_failure(username, ip)
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Prénom ou mot de passe incorrect")
    if not user.active:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Compte désactivé")
    ratelimit.reset(username, ip)
    # Promotion admin si le prénom est listé (utile si ADMIN_USERNAMES ajouté après coup).
    if auth.is_admin_username(user.username) and user.role != "admin":
        user.role = "admin"
    # Ménage : purge des sessions expirées (évite la croissance infinie de la table).
    db.query(SessionModel).filter(SessionModel.expires_at < datetime.utcnow()).delete()
    token = auth.create_session(db, user)
    return schemas.TokenResponse(token=token, username=user.username, role=user.role)


@app.get("/auth/me", response_model=schemas.UserOut)
def me(user: User = Depends(auth.get_current_user)):
    return user


@app.get("/auth/me/stats", response_model=schemas.StatsOut)
def my_stats(db: OrmSession = Depends(get_db), user: User = Depends(auth.get_current_user)):
    positions = db.scalar(
        select(func.count()).select_from(PmPosition).where(PmPosition.author == user.username)
    )
    comments = db.scalar(
        select(func.count()).select_from(PmComment).where(PmComment.author == user.username)
    )
    confirmations = db.scalar(
        select(func.count()).select_from(PmConfirmation).where(PmConfirmation.username == user.username)
    )
    return schemas.StatsOut(
        positions_count=positions or 0,
        comments_count=comments or 0,
        confirmations_count=confirmations or 0,
    )


@app.put("/auth/profile", response_model=schemas.UserOut)
def update_profile(req: schemas.ProfileUpdate, db: OrmSession = Depends(get_db),
                   user: User = Depends(auth.get_current_user)):
    if req.new_password:
        if not req.current_password or not auth.verify_password(req.current_password, user.password_hash):
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "Mot de passe actuel incorrect")
        user.password_hash = auth.hash_password(req.new_password)
    if req.email is not None:
        user.email = req.email.lower()
    db.commit()
    db.refresh(user)
    return user


@app.post("/auth/logout", response_model=schemas.MessageResponse)
def logout(authorization: str | None = Header(default=None),
           db: OrmSession = Depends(get_db), user: User = Depends(auth.get_current_user)):
    # Ne ferme que la session de cet appareil (2 sessions autorisées par compte,
    # l'autre appareil doit rester connecté).
    th = auth.token_hash_from_header(authorization)
    if th:
        db.query(SessionModel).filter(
            SessionModel.token_hash == th, SessionModel.user_id == user.id
        ).delete()
    db.commit()
    return schemas.MessageResponse(message="Déconnecté.")


# =========================================================================
# PM (fiches, positions, commentaires)
# =========================================================================
def _position_out(pos: PmPosition | None) -> schemas.PositionOut | None:
    if pos is None:
        return None
    return schemas.PositionOut(lat=pos.lat, lon=pos.lon, accuracy_m=pos.accuracy_m,
                               author=pos.author, updated_at=pos.updated_at)


@app.get("/pm", response_model=list[schemas.PmListItem])
def list_pm(
    dep: str | None = Query(default=None, description="dep_code ex. 14,27,50,61,76"),
    com: str | None = Query(default=None),
    q: str | None = Query(default=None, description="recherche code/commune/opérateur"),
    has_position: bool | None = Query(default=None),
    limit: int = Query(default=200, le=10000),
    offset: int = Query(default=0, ge=0),
    db: OrmSession = Depends(get_db),
    user: User = Depends(auth.get_current_user),
):
    stmt = select(Pm, PmPosition).outerjoin(PmPosition, Pm.code == PmPosition.pm_code)
    if dep:
        stmt = stmt.where(Pm.dep_code == dep)
    if com:
        stmt = stmt.where(Pm.com.like(f"%{com}%"))
    if q:
        like = f"%{q}%"
        stmt = stmt.where((Pm.code.like(like)) | (Pm.com.like(like)) | (Pm.op.like(like)))
    if has_position is True:
        stmt = stmt.where(PmPosition.pm_code.isnot(None))
    elif has_position is False:
        stmt = stmt.where(PmPosition.pm_code.is_(None))
    stmt = stmt.limit(limit).offset(offset)

    items = []
    for pm, pos in db.execute(stmt).all():
        items.append(schemas.PmListItem(
            code=pm.code, op=pm.op, com=pm.com, dep_code=pm.dep_code, etat=pm.etat,
            position_status="exacte" if pos else "inconnue",
            lat=pos.lat if pos else None, lon=pos.lon if pos else None,
            author=pos.author if pos else None,
            updated_at=pos.updated_at if pos else None,
        ))
    return items


@app.get("/pm/{code}", response_model=schemas.PmOut)
def get_pm(code: str, db: OrmSession = Depends(get_db), user: User = Depends(auth.get_current_user)):
    pm = db.get(Pm, code)
    if pm is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "PM inconnu")
    pos = db.get(PmPosition, code)
    conf_count = db.scalar(
        select(func.count()).select_from(PmConfirmation).where(PmConfirmation.pm_code == code)
    ) or 0
    confirmed_by_me = db.get(PmConfirmation, (code, user.username)) is not None
    return schemas.PmOut(
        code=pm.code, oi=pm.oi, op=pm.op, com=pm.com, dep=pm.dep, dep_code=pm.dep_code,
        etat=pm.etat, date_pm=pm.date_pm, lgt=pm.lgt, tot=pm.tot,
        position_status="exacte" if pos else "inconnue",
        position=_position_out(pos), osm_lat=pm.osm_lat, osm_lon=pm.osm_lon,
        confirmations=conf_count, confirmed_by_me=confirmed_by_me,
        source=pm.source, created_by=pm.created_by, address=pm.address,
    )


DEP_NAMES = {"14": "CALVADOS", "27": "EURE", "50": "MANCHE", "61": "ORNE", "76": "SEINE-MARITIME"}


@app.post("/pm", response_model=schemas.PmOut, status_code=201)
def create_pm(req: schemas.CreatePmRequest, db: OrmSession = Depends(get_db),
              user: User = Depends(auth.get_current_user)):
    """Crée un PM absent de l'ARCEP (ajout terrain). Réf fournie ou générée."""
    code = (req.code or "").strip()
    if not code:
        code = "U" + datetime.utcnow().strftime("%y%m%d%H%M%S") + secrets.token_hex(2)
    if db.get(Pm, code) is not None:
        raise HTTPException(status.HTTP_409_CONFLICT, "Une PM avec cette référence existe déjà")
    pm = Pm(code=code, op=req.op, com=req.com, dep=DEP_NAMES.get(req.dep_code or ""),
            dep_code=req.dep_code, etat="ajout terrain", source="user", created_by=user.username)
    db.add(pm)
    db.flush()  # garantit l'insertion du PM avant la position (contrainte FK)
    if req.lat is not None and req.lon is not None:
        db.add(PmPosition(pm_code=code, lat=req.lat, lon=req.lon,
                          accuracy_m=req.accuracy_m, author=user.username))
        db.add(PmPositionHistory(pm_code=code, lat=req.lat, lon=req.lon,
                                 accuracy_m=req.accuracy_m, author=user.username))
    db.commit()
    return get_pm(code, db, user)


@app.get("/pm-added", response_model=list[schemas.PmOut])
def list_added_pm(db: OrmSession = Depends(get_db), user: User = Depends(auth.get_current_user)):
    """Tous les PM ajoutés par des utilisateurs (à fusionner dans la base locale de l'app)."""
    codes = db.scalars(select(Pm.code).where(Pm.source == "user")).all()
    return [get_pm(c, db, user) for c in codes]


@app.post("/pm/{code}/confirm", response_model=schemas.PmOut)
def confirm_position(code: str, db: OrmSession = Depends(get_db),
                     user: User = Depends(auth.get_current_user)):
    pm = db.get(Pm, code)
    if pm is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "PM inconnu")
    pos = db.get(PmPosition, code)
    if pos is None:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Aucune position exacte à confirmer")
    existing = db.get(PmConfirmation, (code, user.username))
    if existing is None:
        db.add(PmConfirmation(pm_code=code, username=user.username))
        db.commit()
    return get_pm(code, db, user)


@app.put("/pm/{code}/position", response_model=schemas.PositionOut)
def set_position(code: str, body: schemas.PositionIn,
                 db: OrmSession = Depends(get_db), user: User = Depends(auth.get_current_user)):
    pm = db.get(Pm, code)
    if pm is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "PM inconnu")
    # Contrôle « dans la zone ARCEP » : strict en saisie manuelle (erreur de frappe,
    # mauvaise ligne copiée…), tolérant en capture GPS (PM en bordure de zone).
    # Les PM ajoutés à la main (source=user) n'ont pas de zone -> pas de contrôle.
    inside, dist = geo.check_in_zone(code, body.lat, body.lon)
    tolerance = 100.0 if body.manual else 500.0
    if not inside and dist > tolerance:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            f"Position à {dist:.0f} m HORS de la zone ARCEP de ce PM. "
            f"Vérifie les coordonnées (ou le bon PM).",
        )
    pos = db.get(PmPosition, code)
    # Règle des 10 m : refuser une modif trop proche de la position ACTUELLE du
    # MÊME PM (comparaison strictement par PM, jamais globale).
    if pos is not None:
        d = _haversine_m(pos.lat, pos.lon, body.lat, body.lon)
        if d < MIN_MOVE_METERS:
            raise HTTPException(
                status.HTTP_409_CONFLICT,
                f"Position à {d:.1f} m de l'actuelle (< {MIN_MOVE_METERS:.0f} m) : déjà précise, modification inutile.",
            )
    if pos is None:
        pos = PmPosition(pm_code=code)
        db.add(pos)
    pos.lat, pos.lon, pos.accuracy_m = body.lat, body.lon, body.accuracy_m
    pos.author = user.username
    db.add(PmPositionHistory(pm_code=code, lat=body.lat, lon=body.lon,
                             accuracy_m=body.accuracy_m, author=user.username))
    db.commit()
    db.refresh(pos)
    return _position_out(pos)


@app.delete("/pm/{code}/position", response_model=schemas.MessageResponse)
def delete_position(code: str, db: OrmSession = Depends(get_db),
                    admin: User = Depends(auth.require_admin)):
    """Supprime la position partagée d'un PM (admin seulement). L'historique est conservé."""
    pos = db.get(PmPosition, code)
    if pos is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Aucune position pour ce PM")
    db.delete(pos)
    # Les confirmations portaient sur cette position : on les retire aussi.
    db.query(PmConfirmation).filter(PmConfirmation.pm_code == code).delete()
    db.commit()
    return schemas.MessageResponse(message="Position supprimée.")


@app.get("/pm/{code}/comments", response_model=list[schemas.CommentOut])
def list_comments(code: str, db: OrmSession = Depends(get_db), user: User = Depends(auth.get_current_user)):
    rows = db.scalars(select(PmComment).where(PmComment.pm_code == code).order_by(PmComment.created_at)).all()
    return rows


@app.post("/pm/{code}/comments", response_model=schemas.CommentOut, status_code=201)
def add_comment(code: str, body: schemas.CommentIn,
                db: OrmSession = Depends(get_db), user: User = Depends(auth.get_current_user)):
    if db.get(Pm, code) is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "PM inconnu")
    c = PmComment(pm_code=code, body=body.body, author=user.username)
    db.add(c)
    db.commit()
    db.refresh(c)
    return c


# =========================================================================
# ADMIN
# =========================================================================
@app.get("/stats/leaderboard", response_model=list[schemas.LeaderboardEntry])
def leaderboard(db: OrmSession = Depends(get_db), user: User = Depends(auth.get_current_user)):
    """Hall of fame : contributions par utilisateur inscrit (exclut les imports)."""
    entries = []
    for u in db.scalars(select(User)).all():
        pos = db.scalar(select(func.count()).select_from(PmPosition).where(PmPosition.author == u.username)) or 0
        conf = db.scalar(select(func.count()).select_from(PmConfirmation).where(PmConfirmation.username == u.username)) or 0
        com = db.scalar(select(func.count()).select_from(PmComment).where(PmComment.author == u.username)) or 0
        entries.append(schemas.LeaderboardEntry(
            username=u.username, positions_count=pos, confirmations_count=conf, comments_count=com))
    entries.sort(key=lambda e: (e.positions_count, e.confirmations_count, e.comments_count), reverse=True)
    return entries


@app.get("/admin/invitation-codes", response_model=schemas.InvitationCodes)
def admin_get_codes(db: OrmSession = Depends(get_db), admin: User = Depends(auth.require_admin)):
    return schemas.InvitationCodes(
        code_interne=get_setting(db, "invite_code_interne"),
        code_externe=get_setting(db, "invite_code_externe"),
    )


@app.put("/admin/invitation-codes", response_model=schemas.InvitationCodes)
def admin_set_codes(body: schemas.InvitationCodes, db: OrmSession = Depends(get_db),
                    admin: User = Depends(auth.require_admin)):
    # Garde-fou : les deux codes vides ouvriraient l'inscription à n'importe qui.
    if not body.code_interne.strip() and not body.code_externe.strip():
        raise HTTPException(status.HTTP_400_BAD_REQUEST,
                            "Au moins un des deux codes doit être défini (sinon inscription libre).")
    set_setting(db, "invite_code_interne", body.code_interne.strip())
    set_setting(db, "invite_code_externe", body.code_externe.strip())
    db.commit()
    return admin_get_codes(db, admin)


@app.get("/admin/users", response_model=list[schemas.UserOut])
def admin_list_users(db: OrmSession = Depends(get_db), admin: User = Depends(auth.require_admin)):
    return db.scalars(select(User).order_by(User.created_at)).all()


@app.get("/admin/users/{user_id}/stats", response_model=schemas.StatsOut)
def admin_user_stats(user_id: int, db: OrmSession = Depends(get_db), admin: User = Depends(auth.require_admin)):
    u = db.get(User, user_id)
    if u is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Utilisateur inconnu")
    pos = db.scalar(select(func.count()).select_from(PmPosition).where(PmPosition.author == u.username)) or 0
    com = db.scalar(select(func.count()).select_from(PmComment).where(PmComment.author == u.username)) or 0
    conf = db.scalar(select(func.count()).select_from(PmConfirmation).where(PmConfirmation.username == u.username)) or 0
    return schemas.StatsOut(positions_count=pos, comments_count=com, confirmations_count=conf)


@app.put("/admin/users/{user_id}/role", response_model=schemas.UserOut)
def admin_set_role(user_id: int, body: schemas.RoleUpdate,
                   db: OrmSession = Depends(get_db), admin: User = Depends(auth.require_admin)):
    u = db.get(User, user_id)
    if u is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Utilisateur inconnu")
    if u.id == admin.id:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Impossible de changer son propre rôle")
    u.role = body.role
    db.commit()
    db.refresh(u)
    return u


@app.post("/admin/users/{user_id}/activate", response_model=schemas.MessageResponse)
def admin_activate_user(user_id: int, db: OrmSession = Depends(get_db), admin: User = Depends(auth.require_admin)):
    u = db.get(User, user_id)
    if u is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Utilisateur inconnu")
    u.active = True
    db.commit()
    return schemas.MessageResponse(message="Utilisateur réactivé.")


@app.delete("/admin/users/{user_id}", response_model=schemas.MessageResponse)
def admin_delete_user(user_id: int, db: OrmSession = Depends(get_db), admin: User = Depends(auth.require_admin)):
    u = db.get(User, user_id)
    if u is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Utilisateur inconnu")
    if u.id == admin.id:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Impossible de se supprimer soi-même")
    db.delete(u)
    db.commit()
    return schemas.MessageResponse(message="Utilisateur supprimé.")


@app.post("/admin/users/{user_id}/deactivate", response_model=schemas.MessageResponse)
def admin_deactivate_user(user_id: int, db: OrmSession = Depends(get_db), admin: User = Depends(auth.require_admin)):
    u = db.get(User, user_id)
    if u is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Utilisateur inconnu")
    u.active = False
    db.query(SessionModel).filter(SessionModel.user_id == u.id).delete()
    db.commit()
    return schemas.MessageResponse(message="Utilisateur désactivé.")


def _get_comment_editable(db: OrmSession, comment_id: int, user: User) -> PmComment:
    c = db.get(PmComment, comment_id)
    if c is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Commentaire inconnu")
    # Éditable par son auteur OU par un admin.
    if c.author != user.username and user.role != "admin":
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Vous ne pouvez modifier que vos propres commentaires")
    return c


@app.put("/comments/{comment_id}", response_model=schemas.CommentOut)
def edit_comment(comment_id: int, body: schemas.CommentIn,
                 db: OrmSession = Depends(get_db), user: User = Depends(auth.get_current_user)):
    c = _get_comment_editable(db, comment_id, user)
    c.body = body.body
    db.commit()
    db.refresh(c)
    return c


@app.delete("/comments/{comment_id}", response_model=schemas.MessageResponse)
def delete_comment(comment_id: int, db: OrmSession = Depends(get_db),
                   user: User = Depends(auth.get_current_user)):
    c = _get_comment_editable(db, comment_id, user)
    db.delete(c)
    db.commit()
    return schemas.MessageResponse(message="Commentaire supprimé.")
