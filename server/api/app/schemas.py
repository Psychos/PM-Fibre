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
    # Étiquettes et accès : servis avec la fiche, pas par deux appels de plus —
    # ils sont affichés dès l'ouverture, en tête (§ 3.7).
    tags: list[str] = []
    access: "AccessOut | None" = None
    # Les metadonnees seulement : les octets se demandent photo par photo.
    photos: list["PhotoOut"] = []


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


# ---- Étiquettes et accès (§ 3.6, § 3.7) ----
class AccessIn(BaseModel):
    """« Comment y accéder » : indication courte, et le point d'accès s'il diffère.

    Les trois champs sont facultatifs et remis à zéro par `null` : vider la note
    est une opération légitime (l'accès a changé, la haie a été arrachée).
    """
    note: str | None = Field(default=None, max_length=255)
    lat: float | None = Field(default=None, ge=-90, le=90)
    lon: float | None = Field(default=None, ge=-180, le=180)


class AccessOut(BaseModel):
    note: str | None = None
    lat: float | None = None
    lon: float | None = None
    author: str | None = None
    updated_at: datetime


class TagsIn(BaseModel):
    """L'ensemble complet des étiquettes du PM, pas un ajout.

    Le client envoie ce qu'il voit après sa modification ; le serveur en déduit
    les ajouts et les retraits. Un PATCH incrémental obligerait l'application
    hors ligne à tenir une file d'ajouts et de retraits ordonnée, pour un objet
    qui tient en six cases à cocher.
    """
    tags: list[str] = Field(default_factory=list, max_length=32)


class TagOut(BaseModel):
    tag: str
    family: str
    author: str | None = None
    created_at: datetime


# ---- Synchronisation des étiquettes et des accès (§ 4.1) ----
class SyncTagOut(BaseModel):
    code: str
    tag: str
    family: str
    author: str | None = None
    created_at: datetime


class SyncAccessOut(BaseModel):
    code: str
    note: str | None = None
    lat: float | None = None
    lon: float | None = None
    author: str | None = None
    updated_at: datetime


class SyncMetaOut(BaseModel):
    """Même contrat que `SyncPositionsOut`, pour ce qui n'est pas une position.

    Un seul curseur pour les deux familles : deux curseurs séparés se seraient
    désynchronisés au premier appel interrompu, et rien ne dit qu'une étiquette
    et une note d'accès arrivent dans le même ordre.
    """
    deps: list[str]
    since: datetime | None
    next_since: datetime
    complete: bool
    tags: list[SyncTagOut]
    access: list[SyncAccessOut]
    deleted_tags: list[str]     # "code|tag", le couple retiré
    deleted_access: list[str]   # codes dont l'accès a été effacé


# `PmOut` cite `AccessOut`, défini plus bas : la référence avant déclaration
# doit être résolue une fois le module entièrement lu.
# ---- Photos (§ 3.7) ----
class PhotoOut(BaseModel):
    """Métadonnées d'une photo. Le contenu se récupère par `GET /photos/{id}`.

    Une liste de fiches ne descend jamais d'octets d'image : sur le terrain, la
    fiche s'ouvre d'abord, les vignettes suivent — et parfois pas du tout, si le
    réseau est mauvais.
    """
    id: int
    code: str
    kind: str                   # pm | acces
    bytes: int | None = None
    width: int | None = None
    height: int | None = None
    author: str | None = None
    created_at: datetime


PmOut.model_rebuild()
