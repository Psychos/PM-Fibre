#!/usr/bin/env bash
# Sauvegarde quotidienne de la BDD PM Fibre (MariaDB dans Docker).
# 1) mariadb-dump -> gzip dans ~/pmfibre/backups/ (rotation 14 jours + 1er du mois gardés 12 mois)
# 2) archive des photos de terrain, seulement si elles ont changé (§ 3.7)
# 3) copie vers le NAS PCTV via smbclient si ~/pmfibre/backup_nas.conf existe.
set -u

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

# Mot de passe BDD lu depuis le .env du projet (pas en dur ici)
DB_PASSWORD=$(grep -E '^DB_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)
if [ -z "$DB_PASSWORD" ]; then
  log "ERREUR: DB_PASSWORD introuvable dans $ENV_FILE"; exit 1
fi

# 1) Dump (via le conteneur, --single-transaction = cohérent sans bloquer l'API)
if docker exec pmfibre-db mariadb-dump --single-transaction --routines \
     -upmfibre -p"$DB_PASSWORD" pmfibre 2>>"$LOG" | gzip > "$OUT"; then
  SIZE=$(du -h "$OUT" | cut -f1)
  log "OK dump $OUT ($SIZE)"
else
  log "ERREUR: dump échoué"; rm -f "$OUT"; exit 1
fi

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
elif docker exec pmfibre-api tar -czf - -C /photos . > "$PHOTOS_OUT" 2>>"$LOG"; then
  echo "$SIG" > "$PHOTOS_SIG"
  PHOTOS_ARCHIVE="$PHOTOS_OUT"
  log "OK photos $PHOTOS_OUT ($(du -h "$PHOTOS_OUT" | cut -f1))"
  # Rotation : 6 archives. Elles sont cumulatives (chacune contient tout le
  # lot), donc la plus recente suffit a restaurer ; les precedentes ne sont la
  # que pour rattraper une suppression regrettee.
  ls -1t "$BACKUP_DIR"/pmfibre_photos_*.tar.gz 2>/dev/null | tail -n +7 | xargs -r rm -f
else
  log "ERREUR: archive photos echouee"; rm -f "$PHOTOS_OUT"
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
