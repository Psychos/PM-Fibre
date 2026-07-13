"""Limiteur de tentatives de connexion (anti-force brute), en mémoire.

Simple et sans dépendance : suffisant pour un petit service mono-worker.
Se réinitialise au redémarrage du conteneur (acceptable).
"""
import time
from collections import defaultdict
from threading import Lock

_WINDOW_S = 300          # fenêtre glissante : 5 min
_MAX_PER_USER = 5        # échecs autorisés par prénom dans la fenêtre
_MAX_PER_IP = 20         # échecs autorisés par IP (plus large : NAT/collègues partagés)

_fails: dict[str, list[float]] = defaultdict(list)
_lock = Lock()


def _purge(key: str, now: float) -> list[float]:
    arr = [t for t in _fails[key] if now - t < _WINDOW_S]
    _fails[key] = arr
    return arr


def seconds_to_wait(username: str, ip: str) -> float | None:
    """None si l'essai est autorisé, sinon le nb de secondes à patienter."""
    now = time.time()
    with _lock:
        u = _purge(f"u:{username.lower()}", now)
        i = _purge(f"i:{ip}", now)
        for arr, cap in ((u, _MAX_PER_USER), (i, _MAX_PER_IP)):
            if len(arr) >= cap:
                return max(1.0, _WINDOW_S - (now - arr[0]))
    return None


def record_failure(username: str, ip: str) -> None:
    now = time.time()
    with _lock:
        _fails[f"u:{username.lower()}"].append(now)
        _fails[f"i:{ip}"].append(now)


def reset(username: str, ip: str) -> None:
    with _lock:
        _fails.pop(f"u:{username.lower()}", None)
