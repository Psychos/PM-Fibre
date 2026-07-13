# Génère le SQL de mise à jour des adresses PM (depuis le fichier collègue).
import json

pos = json.load(open(r"E:\PM\data\positions_collegue.json", encoding="utf-8"))

def esc(s):
    return s.replace("\\", "\\\\").replace("'", "''")

lines = []
for code, d in pos.items():
    adr = (d.get("adresse") or "").strip()
    if not adr:
        continue
    lines.append(f"UPDATE pm SET address='{esc(adr)}' WHERE code='{esc(code)}';")

open(r"E:\PM\data\adresses_collegue.sql", "w", encoding="utf-8").write("\n".join(lines) + "\n")
print("Adresses:", len(lines), "-> adresses_collegue.sql")
