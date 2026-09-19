from datetime import date, datetime

from pydantic import BaseModel, EmailStr, Field


# ---- Auth (prénom + mot de passe) ----
class RegisterRequest(BaseModel):
    username: str = Field(min_length=2, max_length=64)
    password: str = Field(min_length=4, max_length=128)
    email: EmailStr | None = None      # optionnel, conservé pour plus tard
    invitation_code: str = ""          # code d'équipe requis si INVITATION_CODE est défini


class LoginRequest(BaseModel):
    username: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=128)


class ProfileUpdate(BaseModel):
    email: EmailStr | None = None
    current_password: str | None = None
    new_password: str | None = Field(default=None, min_length=4, max_length=128)


class StatsOut(BaseModel):
    positions_count: int
    comments_count: int
    confirmations_count: int = 0


class LeaderboardEntry(BaseModel):
    username: str
    positions_count: int
    confirmations_count: int
    comments_count: int


class RoleUpdate(BaseModel):
    role: str = Field(pattern="^(user|admin)$")


class PasswordResetOut(BaseModel):
    username: str
    temporary_password: str


class InvitationCodes(BaseModel):
    code_interne: str = Field(max_length=64)
    code_externe: str = Field(max_length=64)


class TokenResponse(BaseModel):
    token: str
    username: str
    role: str


class MessageResponse(BaseModel):
    message: str


# ---- PM ----
class PositionOut(BaseModel):
    lat: float
    lon: float
    accuracy_m: float | None = None
    author: str | None = None
    updated_at: datetime | None = None


class CommentOut(BaseModel):
    id: int
    body: str
    author: str | None = None
    created_at: datetime
    updated_at: datetime


class PmOut(BaseModel):
    code: str
    oi: str | None = None
    op: str | None = None
    com: str | None = None
    dep: str | None = None
    dep_code: str | None = None
    etat: str | None = None
    date_pm: date | None = None
    lgt: int | None = None
    tot: int | None = None
    # Statut de position : "exacte" si position user, sinon "inconnue".
    position_status: str
    position: PositionOut | None = None
    osm_lat: float | None = None
    osm_lon: float | None = None
    confirmations: int = 0
    confirmed_by_me: bool = False
    source: str = "arcep"
    created_by: str | None = None
    address: str | None = None


class PmListItem(BaseModel):
    code: str
    op: str | None = None
    com: str | None = None
    dep_code: str | None = None
    etat: str | None = None
    position_status: str
    lat: float | None = None
    lon: float | None = None
    author: str | None = None
    updated_at: datetime | None = None


class PositionIn(BaseModel):
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
    accuracy_m: float | None = Field(default=None, ge=0)
    manual: bool = False   # saisie manuelle -> contrôle de zone plus strict
    # Comment la position a été obtenue. Facultatif : les clients antérieurs à
    # la capture précise ne l'envoient pas, et une valeur inconnue est ignorée
    # plutôt que refusée — un champ déclaratif ne doit pas faire perdre une
    # position relevée sur le terrain.
    method: str | None = Field(default=None, max_length=16)


class CreatePmRequest(BaseModel):
    code: str | None = Field(default=None, max_length=64)   # réf si connue, sinon générée
    op: str | None = Field(default=None, max_length=255)    # opérateur
    com: str = Field(min_length=1, max_length=255)          # commune (requise)
    dep_code: str | None = Field(default=None, max_length=3)
    lat: float | None = Field(default=None, ge=-90, le=90)
    lon: float | None = Field(default=None, ge=-180, le=180)
    accuracy_m: float | None = Field(default=None, ge=0)


class CommentIn(BaseModel):
    body: str = Field(min_length=1, max_length=5000)


# ---- Admin ----
class UserOut(BaseModel):
    id: int
    username: str
    email: EmailStr | None = None
    role: str
    user_type: str = "interne"
    active: bool
    created_at: datetime
    last_login: datetime | None = None


# ---- Synchronisation incrémentale (§ 4.1) ----
class SyncPositionOut(BaseModel):
    code: str
    lat: float
    lon: float
    accuracy_m: float | None = None
    method: str | None = None
    author: str | None = None
    updated_at: datetime


class SyncPositionsOut(BaseModel):
    """Une page de synchro, avec de quoi savoir s'il en reste.

    `complete=False` veut dire « rappelle-moi avec next_since », et non « voilà
    tout ». C'est la différence avec l'ancien `/pm?limit=10000`, qui rendait une
    liste tronquée indiscernable d'une liste complète — et le client, croyant la
    seconde, effaçait les positions manquantes.

    Garantie du curseur : tout ce qui porte un horodatage <= `next_since` a été
    livré. Le client ne mémorise `next_since` qu'une fois la page appliquée.
    """
    deps: list[str]          # périmètre effectif ; borne une éventuelle purge
    since: datetime | None   # curseur reçu, renvoyé tel quel
    next_since: datetime     # curseur à présenter au prochain appel
    complete: bool           # False -> rappeler immédiatement
    positions: list[SyncPositionOut]
    deleted: list[str]       # PM dont la position a été supprimée depuis `since`
