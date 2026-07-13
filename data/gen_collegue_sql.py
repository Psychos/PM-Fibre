# Génère le SQL d'injection des 736 positions du collègue (auteur "collègue").
# Écrase les positions "OSM/import" (moins fiables) mais PRÉSERVE les captures utilisateurs.
import json

pos = json.load(open(r"E:\PM\data\positions_collegue.json", encoding="utf-8"))

def esc(s):
    return s.replace("\\", "\\\\").replace("'", "''")

rows = []
for code, d in pos.items():
    rows.append(f"('{esc(code)}',{d['lat']},{d['lon']},'collègue')")

sql = (
    "INSERT INTO pm_positions (pm_code, lat, lon, author) VALUES\n"
    + ",\n".join(rows)
    + "\nON DUPLICATE KEY UPDATE "
    "lat=IF(author='OSM/import',VALUES(lat),lat),"
    "lon=IF(author='OSM/import',VALUES(lon),lon),"
    "author=IF(author='OSM/import',VALUES(author),author);\n"
)

open(r"E:\PM\data\positions_collegue.sql", "w", encoding="utf-8").write(sql)
print("SQL généré:", len(rows), "positions ->", r"E:\PM\data\positions_collegue.sql")
