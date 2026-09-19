# Extrait les polygones ZAPM (zones ARCEP), simplifies, pour la validation
# serveur "position dans la zone" et l'onglet "Autour" hors ligne.
# Source : shapefile ARCEP T2 2026 (voir l'en-tete de build_multi.py pour le
# lien de telechargement), pas l'ancien T1 2026 utilise par build_zones.py.
#
#   python build_zones_multi.py                 # France entiere
#   python build_zones_multi.py 14,27,50,61,76  # sous-ensemble
#
# Sortie : zones_multi.json  { code_pm: [ [ [lat,lon], ... ] (anneaux) ] }
import json, math, os, sys
import shapefile

SHP = "raw_2026T2/extracted/2026T2_ZAPM"
OUT = "zones_multi.json"
DEPS = set(sys.argv[1].split(",")) if len(sys.argv) > 1 else set()  # vide = tous
TOL = 0.0002  # ~22 m : ample pour valider une saisie manuelle


def merc_to_wgs84(x, y):
    R = 6378137.0
    lon = (x / R) * 180.0 / math.pi
    lat = (2.0 * math.atan(math.exp(y / R)) - math.pi / 2.0) * 180.0 / math.pi
    return lat, lon


def rdp(points, tol):
    if len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay = points[a]
        bx, by = points[b]
        dmax, imax = 0.0, -1
        dx, dy = bx - ax, by - ay
        norm = math.hypot(dx, dy) or 1e-12
        for i in range(a + 1, b):
            px, py = points[i]
            d = abs(dx * (ay - py) - dy * (ax - px)) / norm
            if d > dmax:
                dmax, imax = d, i
        if dmax > tol and imax > 0:
            keep[imax] = True
            stack.append((a, imax))
            stack.append((imax, b))
    return [p for p, k in zip(points, keep) if k]


r = shapefile.Reader(SHP)
fields = [f[0] for f in r.fields[1:]]
idx = {n: i for i, n in enumerate(fields)}

zones = {}
npts_total = 0
for sr in r.iterShapeRecords():
    rec = sr.record
    if DEPS and (rec[idx["INSEE_DEP"]] or "").strip() not in DEPS:
        continue
    code = rec[idx["RefPM"]]
    if not code:
        continue
    pts = sr.shape.points
    parts = list(sr.shape.parts) + [len(pts)]
    rings = []
    for i in range(len(parts) - 1):
        ring = [p for p in pts[parts[i]:parts[i + 1]] if abs(p[0]) > 1 or abs(p[1]) > 1]
        if len(ring) < 4:
            continue
        wgs = [merc_to_wgs84(x, y) for x, y in ring]
        simp = rdp([(lon, lat) for lat, lon in wgs], TOL)
        if len(simp) < 4:
            simp = [(lon, lat) for lat, lon in wgs[:: max(1, len(wgs) // 8)]]
        rings.append([[round(lat, 5), round(lon, 5)] for lon, lat in simp])
        npts_total += len(simp)
    if rings:
        zones[code] = rings

json.dump(zones, open(OUT, "w", encoding="utf-8"), separators=(",", ":"))
print("Zones exportees:", len(zones), "| points totaux:", npts_total)
print("Taille:", round(os.path.getsize(OUT) / 1048576, 2), "Mo")
