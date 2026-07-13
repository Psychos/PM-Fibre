# Rapproche les réfs ARCEP du KML collègue avec nos codes PM Normandie.
import re, json

KML = r"C:\Users\olivi\AppData\Local\Temp\claude\E--PM\0f71b5d7-2824-471a-b6ea-aaf845951b97\scratchpad\pm_collegue.kml"
PM = r"E:\PM\data\pm_normandie.json"

pm = json.load(open(PM, encoding="utf-8"))
codes = {x["code"] for x in pm if x.get("code")}
# codes déjà positionnés par OSM dans le dataset serveur
has_osm = {x["code"] for x in pm if x.get("lat") is not None}
print("Codes PM Normandie:", len(codes), "| déjà positionnés (OSM):", len(has_osm))

t = open(KML, encoding="utf-8", errors="replace").read()
pms = re.findall(r"<Placemark.*?</Placemark>", t, re.S)

def field(desc, key):
    m = re.search(re.escape(key) + r":\s*([^<]*)", desc)
    return (m.group(1).strip() if m else "")

kml_by_ref = {}
for p in pms:
    d = re.search(r"<description><!\[CDATA\[(.*?)\]\]>", p, re.S)
    desc = d.group(1) if d else ""
    ref = field(desc, "ref-FR-ARCEP")
    if not ref or ref.upper() == "NC":
        continue
    try:
        lat = float(field(desc, "lat")); lon = float(field(desc, "lon"))
    except ValueError:
        continue
    kml_by_ref[ref] = (lat, lon, field(desc, "operator"))

print("Réfs ARCEP distinctes dans le KML:", len(kml_by_ref))

exact = codes & set(kml_by_ref)
print("MATCH EXACT avec nos codes PM:", len(exact))
new_pos = exact - has_osm
print("  dont NOUVELLES (pas déjà positionnées):", len(new_pos))

# Si peu de match exact, tester une normalisation (enlever préfixes/espaces/casse)
def norm(s): return re.sub(r"[^A-Z0-9]", "", s.upper())
codes_norm = {norm(c): c for c in codes}
kml_norm = {norm(r): r for r in kml_by_ref}
match_norm = set(codes_norm) & set(kml_norm)
print("MATCH après normalisation (sans ponctuation/casse):", len(match_norm))

# Exemples de réfs KML non matchées
unmatched = [r for r in kml_by_ref if r not in codes][:10]
print("Exemples réfs KML non matchées:", unmatched)
print("Exemples codes PM:", list(codes)[:10])
