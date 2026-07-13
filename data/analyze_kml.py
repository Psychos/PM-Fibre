# Analyse du KML collègue : répartition Normandie, refs ARCEP exploitables, opérateurs.
import re, sys

KML = r"C:\Users\olivi\AppData\Local\Temp\claude\E--PM\0f71b5d7-2824-471a-b6ea-aaf845951b97\scratchpad\pm_collegue.kml"

t = open(KML, encoding="utf-8", errors="replace").read()
pms = re.findall(r"<Placemark.*?</Placemark>", t, re.S)
print("Placemarks totaux:", len(pms))

# Bbox Normandie approx
LAT0, LAT1, LON0, LON1 = 48.1, 50.15, -1.95, 1.85

def field(desc, key):
    m = re.search(re.escape(key) + r":\s*([^<]*)", desc)
    return (m.group(1).strip() if m else "")

n_norm = 0
n_ref_ok = 0            # a une vraie réf ARCEP (pas NC/vide)
n_ref_ok_norm = 0
ops = {}
man_made = {}
for p in pms:
    d = re.search(r"<description><!\[CDATA\[(.*?)\]\]>", p, re.S)
    desc = d.group(1) if d else ""
    try:
        lat = float(field(desc, "lat")); lon = float(field(desc, "lon"))
    except ValueError:
        continue
    ref = field(desc, "ref-FR-ARCEP")
    ref_ok = ref and ref.upper() != "NC"
    mm = field(desc, "man_made")
    man_made[mm] = man_made.get(mm, 0) + 1
    in_norm = LAT0 <= lat <= LAT1 and LON0 <= lon <= LON1
    if in_norm:
        n_norm += 1
        op = field(desc, "operator")
        ops[op] = ops.get(op, 0) + 1
    if ref_ok:
        n_ref_ok += 1
        if in_norm:
            n_ref_ok_norm += 1

print("En Normandie (bbox):", n_norm)
print("Avec réf ARCEP exploitable (national):", n_ref_ok)
print("Avec réf ARCEP exploitable ET en Normandie:", n_ref_ok_norm)
print("Types man_made:", dict(sorted(man_made.items(), key=lambda x: -x[1])[:8]))
print("Opérateurs en Normandie:", dict(sorted(ops.items(), key=lambda x: -x[1])[:8]))
