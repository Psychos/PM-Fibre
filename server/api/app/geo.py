"""Validation « position dans la zone ARCEP du PM ».

Les anneaux ZAPM (simplifiés ~20 m) sont lus **par département, à la demande**,
dans les paquets de `packages.py` (§ 4.7).

Auparavant ce module chargeait d'un bloc un `zones_normandie.json` unique. Deux
raisons de ne pas prolonger ce choix :

  - le fichier national pèse 21 Mo une fois la France entière couverte, à garder
    en mémoire en permanence pour servir quelques requêtes par minute ;
  - il n'était pas versionné. Le fichier existe bien sur PsyOne, copié à la main
    le 16 septembre, mais `git ls-files server/data/` ne connaît que
    `pm_normandie.json`. Un redéploiement depuis le dépôt seul aurait donc donné
    un `_load()` en échec, silencieusement rattrapé par `except OSError: {}`, et
    un `check_in_zone` répondant « dans la zone » pour **tous** les PM : le
    contrôle aurait disparu sans que rien ne le signale.

Le second point vaut d'être retenu : une zone introuvable reste permissive — on
n'a pas le droit de refuser une position de terrain parce qu'une donnée manque —
mais cela rend le manque invisible. `zone_connue()` permet à l'appelant de
savoir si le contrôle a réellement eu lieu.
"""
import math

from . import packages


def zones_dep(dep_code: str | None) -> dict:
    if not dep_code:
        return {}
    return packages.zones_du_departement(dep_code)


def zone_connue(code: str, dep_code: str | None) -> bool:
    """Vrai si ce PM a bien une zone ARCEP, donc si le contrôle est effectif."""
    return bool(zones_dep(dep_code).get(code))


def _point_in_ring(lat: float, lon: float, ring: list) -> bool:
    """Ray casting (x=lon, y=lat)."""
    inside = False
    n = len(ring)
    j = n - 1
    for i in range(n):
        yi, xi = ring[i][0], ring[i][1]
        yj, xj = ring[j][0], ring[j][1]
        if (yi > lat) != (yj > lat):
            x_cross = (xj - xi) * (lat - yi) / (yj - yi) + xi
            if lon < x_cross:
                inside = not inside
        j = i
    return inside


def _dist_to_ring_m(lat: float, lon: float, ring: list) -> float:
    """Distance min (m) du point aux segments de l'anneau (approx. équirectangulaire)."""
    coslat = math.cos(math.radians(lat))
    def to_xy(p):
        return (math.radians(p[1]) * coslat * 6371000.0, math.radians(p[0]) * 6371000.0)
    px, py = to_xy([lat, lon])
    best = float("inf")
    for i in range(len(ring)):
        ax, ay = to_xy(ring[i])
        bx, by = to_xy(ring[(i + 1) % len(ring)])
        dx, dy = bx - ax, by - ay
        seg2 = dx * dx + dy * dy
        t = 0.0 if seg2 == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / seg2))
        cx, cy = ax + t * dx, ay + t * dy
        d = math.hypot(px - cx, py - cy)
        if d < best:
            best = d
    return best


def check_in_zone(code: str, lat: float, lon: float,
                  dep_code: str | None = None) -> tuple[bool, float]:
    """(dans_la_zone, distance_m_à_la_zone).

    `dep_code` vient de la fiche PM, déjà chargée par l'appelant : c'est lui qui
    désigne le paquet à ouvrir. Sans zone connue -> (True, 0.0), cas des PM
    ajoutés à la main (source=user) et des départements non déployés.
    """
    rings = zones_dep(dep_code).get(code)
    if not rings:
        return True, 0.0
    for ring in rings:
        if _point_in_ring(lat, lon, ring):
            return True, 0.0
    dist = min(_dist_to_ring_m(lat, lon, ring) for ring in rings)
    return False, dist
