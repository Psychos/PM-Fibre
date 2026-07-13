# -*- coding: utf-8 -*-
import json, math, time, os
from collections import Counter
import shapefile
import openpyxl

SCRATCH = r"C:\Users\olivi\AppData\Local\Temp\claude\E--PM\0f71b5d7-2824-471a-b6ea-aaf845951b97\scratchpad"

def merc_to_wgs84(x, y):
    R = 6378137.0
    lon = (x / R) * 180.0 / math.pi
    lat = (2.0 * math.atan(math.exp(y / R)) - math.pi / 2.0) * 180.0 / math.pi
    return lat, lon

def ring_cent_area(pts):
    a = cx = cy = 0.0
    n = len(pts)
    for i in range(n - 1):
        x0, y0 = pts[i]; x1, y1 = pts[i + 1]
        cross = x0 * y1 - x1 * y0
        a += cross; cx += (x0 + x1) * cross; cy += (y0 + y1) * cross
    if a == 0:
        return None
    a *= 0.5
    return cx / (6 * a), cy / (6 * a), a

def _clean(ring):
    # retire les points parasites nuls (0,0) presents dans certains polygones ARCEP
    return [p for p in ring if abs(p[0]) > 1 or abs(p[1]) > 1]

def poly_centroid(shape):
    pts = shape.points
    parts = list(shape.parts) + [len(pts)]
    sumA = sumX = sumY = 0.0
    allpts = []
    for i in range(len(parts) - 1):
        ring = _clean(pts[parts[i]:parts[i + 1]])
        allpts.extend(ring)
        if len(ring) < 3:
            continue
        if ring[0] != ring[-1]:
            ring = ring + [ring[0]]
        r = ring_cent_area(ring)
        if r is None:
            continue
        cx, cy, a = r
        sumA += a; sumX += cx * a; sumY += cy * a
    if not allpts:
        return None
    xs = [p[0] for p in allpts]; ys = [p[1] for p in allpts]
    xmin, xmax, ymin, ymax = min(xs), max(xs), min(ys), max(ys)
    if sumA == 0:
        return (xmin + xmax) / 2, (ymin + ymax) / 2
    cx, cy = sumX / sumA, sumY / sumA
    # Garde-fou : le centre doit tomber dans le rectangle englobant de la zone
    if not (xmin <= cx <= xmax and ymin <= cy <= ymax):
        return (xmin + xmax) / 2, (ymin + ymax) / 2
    return cx, cy

def haversine(lat1, lon1, lat2, lon2):
    R = 6371000.0
    dLat = math.radians(lat2 - lat1); dLon = math.radians(lon2 - lon1)
    a = math.sin(dLat/2)**2 + math.cos(math.radians(lat1))*math.cos(math.radians(lat2))*math.sin(dLon/2)**2
    return 2 * R * math.asin(math.sqrt(a))

# 0) Correspondance Code OI -> nom operateur
print("Lecture correspondance operateurs...")
wb = openpyxl.load_workbook(f"{SCRATCH}/ref_oi.xlsx")
ws = wb["OI FttH"]
oi_map = {}
for row in ws.iter_rows(min_row=2, values_only=True):
    code = row[1]; name = row[3]; mere = row[0]
    if code:
        oi_map[str(code).strip()] = str(name).strip() if name else str(code).strip()
print("  Operateurs mappes:", len(oi_map))

# 1) Points exacts OSM par code
print("Lecture OSM (points exacts)...")
osm = {}
with open(f"{SCRATCH}/PMZ.geojson", encoding="utf-8") as f:
    gj = json.load(f)
for feat in gj["features"]:
    ref = feat["properties"].get("ref:FR:ARCEP")
    if not ref:
        continue
    g = feat["geometry"]; t = g["type"]; c = g["coordinates"]
    if t == "Point":
        lon, lat = c[0], c[1]
    elif t == "Polygon":
        ring = c[0]; lon = sum(p[0] for p in ring)/len(ring); lat = sum(p[1] for p in ring)/len(ring)
    elif t == "LineString":
        lon = sum(p[0] for p in c)/len(c); lat = sum(p[1] for p in c)/len(c)
    else:
        continue
    if not (-22 <= lat <= 52 and -64 <= lon <= 56):
        continue  # point OSM manifestement errone
    osm[ref] = (round(lat, 6), round(lon, 6))
print("  OSM codes avec point exact:", len(osm))

# 2) ARCEP ZAPM
print("Lecture ARCEP ZAPM... (~10s)")
r = shapefile.Reader(f"{SCRATCH}/zapm/2026T1_ZAPM")
fields = [f[0] for f in r.fields[1:]]
idx = {name: i for i, name in enumerate(fields)}
out = []
unmatched = Counter()
precise = 0
outofbounds = 0
dists = []
def as_int(v):
    try: return int(round(float(v)))
    except: return None
def in_france(lat, lon):
    return -22 <= lat <= 52 and -64 <= lon <= 56
for sr in r.iterShapeRecords():
    rec = sr.record
    ref = rec[idx["RefPM"]]
    c = poly_centroid(sr.shape)
    if c is None:
        continue
    clat, clon = merc_to_wgs84(c[0], c[1])
    if not in_france(clat, clon):
        outofbounds += 1
    oi = (rec[idx["CodeOI"]] or "").strip()
    if oi and oi not in oi_map:
        unmatched[oi] += 1
    if ref in osm:
        lat, lon = osm[ref]; p = 1; precise += 1
        dists.append(haversine(lat, lon, clat, clon))
    else:
        lat, lon = round(clat, 6), round(clon, 6); p = 0
    out.append({
        "code": ref or None,
        "oi": oi or None,
        "com": rec[idx["NOM_COM"]] or None,
        "dep": rec[idx["NOM_DEP"]] or None,
        "etat": rec[idx["EtatPM"]] or None,
        "date": rec[idx["date_debut"]] or None,
        "lgt": as_int(rec[idx["lgtMadPM"]]),
        "tot": as_int(rec[idx["lgtZAPM"]]),
        "lat": lat, "lon": lon, "p": p,
    })
print(f"  ARCEP PM traites: {len(out)}")

with open(f"{SCRATCH}/pm_full.json", "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
with open(f"{SCRATCH}/oi_map.json", "w", encoding="utf-8") as f:
    json.dump(oi_map, f, ensure_ascii=False, separators=(",", ":"))
print("  pm_full.json:", round(os.path.getsize(f'{SCRATCH}/pm_full.json')/1048576, 2), "Mo")
print("  oi_map.json:", round(os.path.getsize(f'{SCRATCH}/oi_map.json')/1024, 1), "Ko")

print("\n=== Codes OI ARCEP sans correspondance (top) ===")
if unmatched:
    for oi, n in unmatched.most_common(15):
        print(f"  {oi:8} {n}")
else:
    print("  Aucun : tous les operateurs sont identifies.")

print(f"\n=== Coherence ===")
print(f"  Points hors bornes France: {outofbounds}")
print(f"\n=== Precision ===")
print(f"  Point exact (OSM): {precise} / {len(out)}")
if dists:
    dists.sort()
    print(f"  Ecart centre-zone vs point exact -> mediane {dists[len(dists)//2]:.0f} m, "
          f"90e pct {dists[int(len(dists)*0.9)]:.0f} m, max {max(dists):.0f} m")
