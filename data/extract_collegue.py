# Extrait les VRAIS points du collègue (format RefPM) et les rapproche de nos codes PM.
import re, json

KML = r"C:\Users\olivi\AppData\Local\Temp\claude\E--PM\0f71b5d7-2824-471a-b6ea-aaf845951b97\scratchpad\pm_collegue.kml"
PM = r"E:\PM\data\pm_normandie.json"

codes = {x["code"] for x in json.load(open(PM, encoding="utf-8")) if x.get("code")}

t = open(KML, encoding="utf-8", errors="replace").read()
pms = re.findall(r"<Placemark.*?</Placemark>", t, re.S)

def data_val(pm, name):
    m = re.search(r'<Data name="' + re.escape(name) + r'">\s*<value>(.*?)</value>', pm, re.S)
    return (m.group(1).strip() if m else "")

pts = {}   # RefPM -> (lat, lon, adresse)
for pm in pms:
    ref = data_val(pm, "RefPM")
    if not ref:
        continue
    try:
        lat = float(data_val(pm, "lat")); lon = float(data_val(pm, "lon"))
    except ValueError:
        continue
    pts[ref] = (lat, lon, data_val(pm, "AdressePM"))

print("Points collègue avec RefPM:", len(pts))
match = set(pts) & codes
print("  -> correspondent à un code PM Normandie:", len(match))
print("  -> RefPM hors Normandie / inconnus:", len(pts) - len(match))

# Export des positions matchées pour injection serveur
out = {r: {"lat": pts[r][0], "lon": pts[r][1], "adresse": pts[r][2]} for r in match}
json.dump(out, open(r"E:\PM\data\positions_collegue.json", "w", encoding="utf-8"),
          ensure_ascii=False, indent=0)
print("Exporté:", len(out), "positions -> positions_collegue.json")
print("Exemples:", list(out.items())[:3])
