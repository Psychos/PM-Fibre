# -*- coding: utf-8 -*-
# Calcule, par PM, la position du batiment raccorde le plus central (medoid)
# + son adresse, a partir du fichier ARCEP Immeubles (4,3 Go).
import zipfile, io, csv, json, time, os

SCRATCH = r"C:\Users\olivi\AppData\Local\Temp\claude\E--PM\0f71b5d7-2824-471a-b6ea-aaf845951b97\scratchpad"
ZIP = f"{SCRATCH}/immeuble.zip"

# index colonnes
X, Y, PM = 0, 1, 13
NUM, BIS, TYPE, NOM, CP, COM = 3, 4, 5, 6, 9, 10

def rows():
    z = zipfile.ZipFile(ZIP)
    name = z.namelist()[0]
    f = z.open(name)
    tw = io.TextIOWrapper(f, encoding="utf-8", errors="replace")
    reader = csv.reader(tw)
    next(reader)  # header
    return reader

# --- Passe 1 : moyenne (centre) des batiments par PM ---
print("Passe 1/2 : centre des batiments par PM...")
t0 = time.time()
sums = {}  # pm -> [sx, sy, n]
n = 0
for r in rows():
    try:
        x = float(r[X]); y = float(r[Y])
    except (ValueError, IndexError):
        continue
    pm = r[PM]
    if not pm:
        continue
    s = sums.get(pm)
    if s is None:
        sums[pm] = [x, y, 1]
    else:
        s[0] += x; s[1] += y; s[2] += 1
    n += 1
    if n % 2_000_000 == 0:
        print(f"  {n:,} lignes... ({time.time()-t0:.0f}s)")
print(f"  {n:,} batiments, {len(sums):,} PM ({time.time()-t0:.0f}s)")

means = {pm: (s[0]/s[2], s[1]/s[2], s[2]) for pm, s in sums.items()}

# --- Passe 2 : batiment le plus proche du centre (medoid) + adresse ---
print("Passe 2/2 : batiment central + adresse...")
t0 = time.time()
best = {}  # pm -> [dist2, lat, lon, adresse, nb]
n = 0
for r in rows():
    try:
        x = float(r[X]); y = float(r[Y])
    except (ValueError, IndexError):
        continue
    pm = r[PM]
    m = means.get(pm)
    if m is None:
        continue
    mx, my, cnt = m
    d2 = (x-mx)**2 + (y-my)**2
    b = best.get(pm)
    if b is None or d2 < b[0]:
        parts = [r[NUM], r[BIS], r[TYPE], r[NOM]]
        voie = " ".join(p for p in parts if p).strip()
        cp = r[CP]; com = r[COM]
        adr = ", ".join(p for p in [voie, f"{cp} {com}".strip()] if p).strip(", ")
        best[pm] = [d2, round(y, 6), round(x, 6), adr, cnt]
    n += 1
    if n % 2_000_000 == 0:
        print(f"  {n:,} lignes... ({time.time()-t0:.0f}s)")

out = {pm: {"lat": b[1], "lon": b[2], "addr": b[3], "nb": b[4]} for pm, b in best.items()}
with open(f"{SCRATCH}/pm_positions.json", "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
print(f"  PM avec position batiment : {len(out):,}")
print(f"  Fichier : {round(os.path.getsize(f'{SCRATCH}/pm_positions.json')/1048576,2)} Mo")
print("Termine.")
