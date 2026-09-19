#!/usr/bin/env bash
# Sauvegarde quotidienne de la BDD PM Fibre (MariaDB dans Docker).
# 1) mariadb-dump -> gzip dans ~/pmfibre/backups/ (rotation 14 jours + 1er du mois gardés 12 mois)
# 2) archive des photos de terrain, seulement si elles ont changé (§ 3.7)
# 3) copie vers le NAS PCTV via smbclient si ~/pmfibre/backup_nas.conf existe.
#
# `pipefail` n'est pas un detail de style : sans lui, le code de retour de
# `mariadb-dump | gzip` est celui de gzip, qui reussit parfaitement a compresser
# un flux vide. Conteneur arrete, mot de passe faux, disque plein cote base : le
# script ecrivait « OK dump », faisait tourner la rotation et copiait l'archive
# inutilisable sur le NAS. Au bout de quatorze jours, plus une seule sauvegarde
# valable, et personne pour s'en douter avant d'en avoir besoin.
set -u
set -o pipefail

BACKUP_DIR="$HOME/pmfibre/backups"
ENV_FILE="$HOME/pmfibre/.env"
NAS_CONF="$HOME/pmfibre/backup_nas.conf"   # variables: NAS_HOST, NAS_SHARE, NAS_DIR
NAS_AUTH="$HOME/pmfibre/backup_nas.auth"   # fichier -A smbclient (username/password), chmod 600
STAMP=$(date +%Y%m%d_%H%M%S)
OUT="$BACKUP_DIR/pmfibre_${STAMP}.sql.gz"
PHOTOS_OUT="$BACKUP_DIR/pmfibre_photos_${STAMP}.tar.gz"
PHOTOS_SIG="$BACKUP_DIR/photos.sig"        # empreinte du lot photos deja archive
LOG="$BACKUP_DIR/backup.log"

mkdir -p "$BACKUP_DIR"

log() { echo "$(date '+%F %T') $*" >> "$LOG"; }

# Restes d'une execution tuee en cours d'ecriture. Ils ne portent pas le suffixe
# attendu par la rotation ni par la copie NAS, donc ils n'ont jamais ete pris
# pour des sauvegardes ; ils occupent juste de la place.
find "$BACKUP_DIR" -name '*.part' -mmin +120 -delete 2>/dev/null || true

# Mot de passe BDD lu depuis le .env du projet (pas en dur ici)
DB_PASSWORD=$(grep -E '^DB_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)
if [ -z "$DB_PASSWORD" ]; then
  log "ERREUR: DB_PASSWORD introuvable dans $ENV_FILE"; exit 1
fi

# 1) Dump (via le conteneur, --single-transaction = cohérent sans bloquer l'API)
#
# L'archive s'ecrit sous un nom provisoire et ne prend son nom definitif qu'une
# fois les trois controles passes. Une sauvegarde a moitie ecrite ne doit jamais
# porter le nom d'une sauvegarde : c'est elle que la rotation comptera comme
# bonne, et c'est elle qu'on tentera de restaurer un jour de panne.
OUT_TMP="$OUT.part"
if ! docker exec pmfibre-db mariadb-dump --single-transaction --routines \
       -upmfibre -p"$DB_PASSWORD" pmfibre 2>>"$LOG" | gzip > "$OUT_TMP"; then
  log "ERREUR: dump échoué (mariadb-dump ou gzip)"; rm -f "$OUT_TMP"; exit 1
fi

# Controle 1 : l'archive se decompresse entierement (CRC et taille gzip).
if ! gzip -t "$OUT_TMP" 2>>"$LOG"; then
  log "ERREUR: archive gzip corrompue ou tronquée"; rm -f "$OUT_TMP"; exit 1
fi

# Controle 2 : le SQL va jusqu'au bout. Un gzip valide ne prouve rien du contenu
# — un flux vide se compresse tres bien. mariadb-dump termine par la ligne
# « -- Dump completed on … », qu'il n'ecrit que s'il est alle au bout.
# La fin est lue dans une variable plutot que passee a `grep -q` : `-q` sort des
# la premiere correspondance, ce qui casse le tuyau en amont et ferait echouer
# le pipeline sous `pipefail`.
FIN_DUMP=$(zcat "$OUT_TMP" 2>>"$LOG" | tail -c 4096)
case "$FIN_DUMP" in
  *"Dump completed"*) ;;
  *) log "ERREUR: dump incomplet (marqueur de fin absent)"; rm -f "$OUT_TMP"; exit 1 ;;
esac

# Controle 3 : plancher grossier. Il n'atteste pas que le dump est complet —
# c'est le role du marqueur ci-dessus — mais il attrape le cas d'un fichier bien
# forme qui ne serait manifestement pas un dump de CETTE base. Ordre de grandeur
# mesure sur une fixture de 100 000 PM : ~1,8 Mo compresse, soit 180 fois le
# plancher. Aucune chance de faux positif sur la base nationale.
OCTETS=$(stat -c %s "$OUT_TMP" 2>/dev/null || echo 0)
if [ "$OCTETS" -lt 10240 ]; then
  log "ERREUR: dump suspect ($OCTETS octets)"; rm -f "$OUT_TMP"; exit 1
fi

mv "$OUT_TMP" "$OUT"
log "OK dump $OUT ($(du -h "$OUT" | cut -f1))"

# 2) Rotation locale : garde 14 jours ; les dumps du 1er du mois gardés 365 jours
find "$BACKUP_DIR" -name 'pmfibre_*.sql.gz' -mtime +14 ! -name 'pmfibre_*01_0*' -delete
find "$BACKUP_DIR" -name 'pmfibre_*.sql.gz' -mtime +365 -delete

# 3) Photos de terrain. Elles vivent dans un volume Docker, hors du dump SQL :
# sans cette etape, une base restauree pointerait vers des fichiers disparus.
# Une archive n'est produite que si le lot a change depuis la derniere : les
# photos sont immuables (leur nom est l'empreinte de leur contenu), donc une
# journee sans depot n'a rien de neuf a sauvegarder, et 14 archives identiques
# de plusieurs dizaines de megaoctets ne serviraient a personne.
PHOTOS_ARCHIVE=""
SIG=$(docker exec pmfibre-api sh -c 'cd /photos 2>/dev/null && find . -type f | sort | sha256sum' 2>>"$LOG" | cut -d' ' -f1)
if [ -z "$SIG" ]; then
  log "INFO: photos illisibles ou volume absent, archive photos sautee"
elif [ -f "$PHOTOS_SIG" ] && [ "$SIG" = "$(cat "$PHOTOS_SIG")" ]; then
  log "INFO: photos inchangees, archive photos sautee"
elif docker exec pmfibre-api tar -czf - -C /photos . > "$PHOTOS_OUT.part" 2>>"$LOG" \
     && gzip -t "$PHOTOS_OUT.part" 2>>"$LOG" \
     && mv "$PHOTOS_OUT.part" "$PHOTOS_OUT"; then
  # L'empreinte n'est gravee qu'apres une archive verifiee : la graver avant
  # ferait sauter la prochaine tentative (« photos inchangees ») alors qu'aucune
  # archive valable n'existe.
  echo "$SIG" > "$PHOTOS_SIG"
  PHOTOS_ARCHIVE="$PHOTOS_OUT"
  log "OK photos $PHOTOS_OUT ($(du -h "$PHOTOS_OUT" | cut -f1))"
  # Rotation : 6 archives. Elles sont cumulatives (chacune contient tout le
  # lot), donc la plus recente suffit a restaurer ; les precedentes ne sont la
  # que pour rattraper une suppression regrettee.
  ls -1t "$BACKUP_DIR"/pmfibre_photos_*.tar.gz 2>/dev/null | tail -n +7 | xargs -r rm -f
else
  log "ERREUR: archive photos echouee ou corrompue"; rm -f "$PHOTOS_OUT" "$PHOTOS_OUT.part"
fi

# 4) Copie NAS (optionnelle : seulement si conf + auth existent)
if [ -f "$NAS_CONF" ] && [ -f "$NAS_AUTH" ]; then
  # shellcheck disable=SC1090
  . "$NAS_CONF"
  smbclient "//${NAS_HOST}/${NAS_SHARE}" -A "$NAS_AUTH" -c "mkdir ${NAS_DIR}" >/dev/null 2>&1  # idempotent
  if smbclient "//${NAS_HOST}/${NAS_SHARE}" -A "$NAS_AUTH" \
       -c "cd ${NAS_DIR}; put ${OUT} $(basename "$OUT")" >>"$LOG" 2>&1; then
    log "OK copie NAS //${NAS_HOST}/${NAS_SHARE}/${NAS_DIR}/$(basename "$OUT")"
    if [ -n "$PHOTOS_ARCHIVE" ]; then
      if smbclient "//${NAS_HOST}/${NAS_SHARE}" -A "$NAS_AUTH"            -c "cd ${NAS_DIR}; put ${PHOTOS_ARCHIVE} $(basename "$PHOTOS_ARCHIVE")" >>"$LOG" 2>&1; then
        log "OK copie NAS $(basename "$PHOTOS_ARCHIVE")"
        PHOTOLIST=$(smbclient "//${NAS_HOST}/${NAS_SHARE}" -A "$NAS_AUTH" -c "cd ${NAS_DIR}; ls pmfibre_photos_*.tar.gz" 2>/dev/null                     | awk '/pmfibre_photos_.*\.tar\.gz/{print $1}' | sort)
        PCOUNT=$(echo "$PHOTOLIST" | grep -c . || true)
        if [ "$PCOUNT" -gt 6 ]; then
          echo "$PHOTOLIST" | head -n $((PCOUNT - 6)) | while read -r f; do
            smbclient "//${NAS_HOST}/${NAS_SHARE}" -A "$NAS_AUTH" -c "cd ${NAS_DIR}; del $f" >>"$LOG" 2>&1
            log "rotation NAS: supprime $f"
          done
        fi
      else
        log "ERREUR: copie NAS des photos echouee (l'archive locale reste disponible)"
      fi
    fi
    # Rotation côté NAS : liste les dumps, supprime les plus vieux au-delà de 30
    NASLIST=$(smbclient "//${NAS_HOST}/${NAS_SHARE}" -A "$NAS_AUTH" -c "cd ${NAS_DIR}; ls pmfibre_*.sql.gz" 2>/dev/null \
              | awk '/pmfibre_.*\.sql\.gz/{print $1}' | sort)
    COUNT=$(echo "$NASLIST" | grep -c . || true)
    if [ "$COUNT" -gt 30 ]; then
      echo "$NASLIST" | head -n $((COUNT - 30)) | while read -r f; do
        smbclient "//${NAS_HOST}/${NAS_SHARE}" -A "$NAS_AUTH" -c "cd ${NAS_DIR}; del $f" >>"$LOG" 2>&1
        log "rotation NAS: supprimé $f"
      done
    fi
  else
    log "ERREUR: copie NAS échouée (le dump local reste disponible)"
  fi
else
  log "INFO: conf NAS absente, copie NAS sautée"
fi
