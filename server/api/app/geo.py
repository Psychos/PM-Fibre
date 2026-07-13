"""Validation « position dans la zone ARCEP du PM ».

Les polygones ZAPM (simplifiés ~20 m) sont chargés depuis /data/zones_normandie.json :
{ code_pm: [ anneau1, anneau2, ... ] } avec anneau = [[lat, lon], ...].
"""
import json
import math
import os
import threading

ZONES_PATH = os.getenv("ZONES_PATH", "/data/zones_normandie.json")

_zones: dict | None = None
_lock = threading.Lock()


def _load() -> dict:
    global _zones
    if _zones is None:
        with _lock:
            if _zones is None:
                try:
                    with open(ZONES_PATH, encoding="utf-8") as f:
                        _zones = json.load(f)
                except OSError:
                    _zones = {}
    return _zones


def has_zone(code: str) -> bool:
    return code in _load()


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


def check_in_zone(code: str, lat: float, lon: float) -> tuple[bool, float]:
    """(dans_la_zone, distance_m_à_la_zone). Si pas de zone connue -> (True, 0)."""
    rings = _load().get(code)
    if not rings:
        return True, 0.0
    for ring in rings:
        if _point_in_ring(lat, lon, ring):
            return True, 0.0
    dist = min(_dist_to_ring_m(lat, lon, ring) for ring in rings)
    return False, dist
