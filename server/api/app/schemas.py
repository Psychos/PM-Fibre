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
